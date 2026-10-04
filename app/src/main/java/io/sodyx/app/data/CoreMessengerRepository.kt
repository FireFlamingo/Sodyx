package io.sodyx.app.data

import android.content.Context
import io.sodyx.app.data.crypto.EncryptedProtocolStateStore
import io.sodyx.app.data.crypto.StoredSecureMessage
import io.sodyx.app.network.HttpRelayTransport
import io.sodyx.app.network.RelayDeliveryMailbox
import io.sodyx.app.network.RelayEndpoint
import io.sodyx.app.network.RelayRetrievalMailbox
import io.sodyx.app.network.RelayTransport
import io.sodyx.app.security.ContactCard
import io.sodyx.app.security.ContactCardCodec
import io.sodyx.app.security.SecureMessagePipeline
import io.sodyx.security.ProtocolAddress
import io.sodyx.security.SignalE2eeEngine
import java.security.SecureRandom
import java.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal class CoreConversation(
    val contact: CoreContact,
    val myCard: String,
    val myVerificationCode: String,
    val peerVerificationCode: String?,
    val expiresAtEpochMillis: Long,
    val messages: List<StoredSecureMessage>
)

/** Coordinates one independent encrypted database and identity per connection. */
internal class CoreMessengerRepository(
    context: Context,
    registryName: String = "sodyx-contacts.db",
    private val relayFactory: (String) -> RelayTransport = {
        HttpRelayTransport(RelayEndpoint.production(it))
    }
) {
    private val context = context.applicationContext
    private val registry = CoreContactRegistry(this.context, registryName)
    private val mutex = Mutex()

    suspend fun contacts(): List<CoreContact> = mutex.withLock {
        withContext(Dispatchers.IO) {
            registry.list().also { contacts ->
                contacts.filter { it.state == CoreContactState.Closed }.forEach {
                    openStore(it.id).destroy()
                }
            }
        }
    }

    suspend fun create(alias: String, relayUrl: String): CoreConversation = mutex.withLock {
        require(
            alias.trim().isNotBlank() && alias.trim().length <= 64 && alias.none(Char::isISOControl)
        )
        RelayEndpoint.production(relayUrl)
        val mailbox = relayFactory(relayUrl).createMailbox(SESSION_TTL_SECONDS)
        withContext(Dispatchers.IO) {
            val contact = registry.create(alias.trim())
            try {
                openStore(contact.id).use { store ->
                    val engine = SignalE2eeEngine(store)
                    engine.initializeIfNeeded()
                    engine.provisionPreKeys(1, 1, 1, 1)
                    val handle = ByteArray(32).also(SecureRandom()::nextBytes)
                    val address = ProtocolAddress(
                        Base64.getUrlEncoder().withoutPadding().encodeToString(handle),
                        1
                    )
                    val card = ContactCard(
                        relayUrl.trimEnd('/'),
                        mailbox.delivery.encodedForEncryptedControl(),
                        mailbox.expiresAtEpochMillis,
                        engine.publishPreKeyBundle(address, 1, 1, 1)
                    )
                    store.transaction {
                        store.putSecret(
                            LOCAL_CARD,
                            ContactCardCodec.encode(card).encodeToByteArray()
                        )
                        store.putSecret(
                            RETRIEVAL,
                            mailbox.retrieval.authorizationValue().encodeToByteArray()
                        )
                    }
                }
                detailLocked(contact.id)
            } catch (error: Exception) {
                openStore(contact.id).destroy()
                registry.remove(contact.id)
                throw error
            }
        }
    }

    suspend fun connect(id: String, peerCardText: String): CoreConversation = mutex.withLock {
        val peerCard = ContactCardCodec.decode(peerCardText.trim())
        val now = System.currentTimeMillis()
        require(peerCard.expiresAtEpochMillis in (now + 1)..(now + MAX_SESSION_MILLIS)) {
            "Contact card expired or has an invalid lifetime"
        }
        withContext(Dispatchers.IO) {
            val contact = registry.get(id)
            check(contact.state == CoreContactState.Pairing) {
                "Connection is already paired or closed"
            }
            openStore(id).use { store ->
                store.transaction {
                    val myCard = ContactCardCodec.decode(requireSecret(store, LOCAL_CARD))
                    require(myCard.expiresAtEpochMillis > System.currentTimeMillis())
                    require(myCard.bundle.address != peerCard.bundle.address)
                    require(
                        !myCard.bundle.serializedIdentityKey.contentEquals(
                            peerCard.bundle.serializedIdentityKey
                        )
                    )
                    val engine = SignalE2eeEngine(store)
                    engine.confirmVerifiedIdentity(
                        peerCard.bundle.address,
                        peerCard.bundle.serializedIdentityKey
                    )
                    engine.establishSession(peerCard.bundle)
                    store.putSecret(
                        PEER_CARD,
                        ContactCardCodec.encode(peerCard).encodeToByteArray()
                    )
                }
            }
            registry.setState(id, CoreContactState.Ready)
            detailLocked(id)
        }
    }

    suspend fun conversation(id: String): CoreConversation = mutex.withLock {
        withContext(Dispatchers.IO) { detailLocked(id) }
    }

    suspend fun send(id: String, text: String): CoreConversation = mutex.withLock {
        requireReady(id)
        openStore(id).use { store -> pipeline(id, store).send(text) }
        withContext(Dispatchers.IO) { detailLocked(id) }
    }

    suspend fun sync(id: String): CoreConversation = mutex.withLock {
        requireReady(id)
        openStore(id).use { store ->
            val pipeline = pipeline(id, store)
            pipeline.poll()
            pipeline.retryPending()
        }
        withContext(Dispatchers.IO) { detailLocked(id) }
    }

    suspend fun close(id: String) = mutex.withLock {
        val mailbox = withContext(Dispatchers.IO) {
            val secret = openStore(id).use { store ->
                store.transaction {
                    val local = store.secret(
                        LOCAL_CARD
                    )?.decodeToString()?.let(ContactCardCodec::decode)
                    val retrieval = store.secret(RETRIEVAL)?.decodeToString()
                    if (local != null && retrieval != null) {
                        local.relayUrl to
                            RelayRetrievalMailbox(retrieval, local.expiresAtEpochMillis)
                    } else {
                        null
                    }
                }
            }
            registry.setState(id, CoreContactState.Closed)
            openStore(id).destroy()
            secret
        }
        if (mailbox != null) {
            try {
                relayFactory(mailbox.first).revoke(mailbox.second)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Local keys are already destroyed. Server expiry is the offline deletion backstop.
            }
        }
    }

    fun dispose() {
        registry.close()
    }

    private suspend fun requireReady(id: String) = withContext(Dispatchers.IO) {
        check(registry.get(id).state == CoreContactState.Ready) { "Connection is not ready" }
    }

    private fun detailLocked(id: String): CoreConversation {
        val contact = registry.get(id)
        check(contact.state != CoreContactState.Closed) { "Connection is closed" }
        return openStore(id).use { store ->
            store.transaction {
                val text = requireSecret(store, LOCAL_CARD)
                val local = ContactCardCodec.decode(text)
                val peer = store.secret(PEER_CARD)?.decodeToString()?.let(ContactCardCodec::decode)
                CoreConversation(
                    contact,
                    text,
                    ContactCardCodec.verificationCode(local),
                    peer?.let(ContactCardCodec::verificationCode),
                    minOf(local.expiresAtEpochMillis, peer?.expiresAtEpochMillis ?: Long.MAX_VALUE),
                    store.messages(id)
                )
            }
        }
    }

    private suspend fun pipeline(
        id: String,
        store: EncryptedProtocolStateStore
    ): SecureMessagePipeline = withContext(Dispatchers.IO) {
        store.transaction {
            val local = ContactCardCodec.decode(requireSecret(store, LOCAL_CARD))
            val peer = ContactCardCodec.decode(requireSecret(store, PEER_CARD))
            val retrieval = requireSecret(store, RETRIEVAL)
            SecureMessagePipeline(
                store,
                SignalE2eeEngine(store),
                relayFactory(peer.relayUrl),
                relayFactory(local.relayUrl),
                peer.bundle.address,
                id,
                RelayDeliveryMailbox(peer.deliveryCapability, peer.expiresAtEpochMillis),
                RelayRetrievalMailbox(retrieval, local.expiresAtEpochMillis)
            )
        }
    }

    private fun requireSecret(store: EncryptedProtocolStateStore, name: String): String =
        checkNotNull(store.secret(name)) { "Connection setup is incomplete" }.decodeToString()

    private fun openStore(id: String): EncryptedProtocolStateStore =
        EncryptedProtocolStateStore(context, "sodyx-contact-$id.db")

    private companion object {
        const val SESSION_TTL_SECONDS = 7 * 24 * 60 * 60
        const val MAX_SESSION_MILLIS = SESSION_TTL_SECONDS * 1000L + 60_000L
        const val LOCAL_CARD = "local-card"
        const val PEER_CARD = "peer-card"
        const val RETRIEVAL = "retrieval-capability"
    }
}
