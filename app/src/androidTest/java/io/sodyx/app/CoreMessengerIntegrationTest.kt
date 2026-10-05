package io.sodyx.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.sodyx.app.data.CoreContactState
import io.sodyx.app.data.CoreMessengerRepository
import io.sodyx.app.network.OpaqueEnvelope
import io.sodyx.app.network.RelayDeliveryId
import io.sodyx.app.network.RelayDeliveryMailbox
import io.sodyx.app.network.RelayMailboxProvision
import io.sodyx.app.network.RelayNetworkException
import io.sodyx.app.network.RelayReceivedEnvelope
import io.sodyx.app.network.RelayRetrievalMailbox
import io.sodyx.app.network.RelayTransport
import io.sodyx.app.security.ContactCardCodec
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CoreMessengerIntegrationTest {
    @Test
    fun twoVerifiedPeersQueueDeliverRestartAndClose() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val aliceName = "alice-registry-${UUID.randomUUID()}.db"
        val bobName = "bob-registry-${UUID.randomUUID()}.db"
        val relay = MemoryRelay()
        var alice = CoreMessengerRepository(context, aliceName) { relay }
        val bob = CoreMessengerRepository(context, bobName) { relay }
        try {
            val a = alice.create("Bob", "https://relay.example")
            val b = bob.create("Alice", "https://relay.example")
            alice.connect(a.contact.id, b.myCard)
            bob.connect(b.contact.id, a.myCard)
            relay.failNextSend = true
            try {
                alice.send(a.contact.id, "queued securely")
                error("Expected transport failure")
            } catch (_: RelayNetworkException) { }
            assertFalse(alice.conversation(a.contact.id).messages.single().delivered)
            alice.sync(a.contact.id)
            val received = bob.sync(b.contact.id)
            assertEquals("queued securely", received.messages.single().plaintext.decodeToString())
            bob.send(b.contact.id, "reply")
            assertEquals(
                "reply",
                alice.sync(a.contact.id).messages.last().plaintext.decodeToString()
            )

            alice.dispose()
            alice = CoreMessengerRepository(context, aliceName) { relay }
            assertEquals(2, alice.conversation(a.contact.id).messages.size)
            relay.tamperNextSend = true
            alice.send(a.contact.id, "after restart")
            assertEquals(
                "after restart",
                bob.sync(b.contact.id).messages.last().plaintext.decodeToString()
            )
            val bobCard = ContactCardCodec.decode(b.myCard)
            val destination = RelayDeliveryMailbox(
                bobCard.deliveryCapability,
                bobCard.expiresAtEpochMillis
            )
            val firstWire = alice.conversation(a.contact.id).messages.first().wireEnvelope
            relay.send(destination, OpaqueEnvelope.fromEncryptedBytes(firstWire))
            assertEquals(3, bob.sync(b.contact.id).messages.size)
            alice.close(a.contact.id)
            assertEquals(CoreContactState.Closed, alice.contacts().single().state)
            assertFalse(context.getDatabasePath("sodyx-contact-${a.contact.id}.db").exists())
            assertTrue(relay.revocations > 0)
        } finally {
            for (repository in listOf(alice, bob)) {
                repository.contacts().filter {
                    it.state != CoreContactState.Closed
                }.forEach { repository.close(it.id) }
                repository.dispose()
            }
            context.deleteDatabase(aliceName)
            context.deleteDatabase(bobName)
        }
    }
}

internal class MemoryRelay : RelayTransport {
    private data class Box(
        val provision: RelayMailboxProvision,
        val messages: MutableList<RelayReceivedEnvelope> = mutableListOf()
    )
    private val boxes = mutableListOf<Box>()
    var failNextSend = false
    var tamperNextSend = false
    var revocations = 0

    @Volatile var receiveCalls = 0

    override suspend fun createMailbox(ttlSeconds: Int): RelayMailboxProvision {
        val expiry = System.currentTimeMillis() + ttlSeconds * 1000L
        val provision = RelayMailboxProvision(
            RelayDeliveryMailbox(token(32), expiry),
            RelayRetrievalMailbox(token(32), expiry),
            expiry
        )
        boxes += Box(provision)
        return provision
    }

    override suspend fun send(
        destination: RelayDeliveryMailbox,
        envelope: OpaqueEnvelope
    ): RelayDeliveryId {
        val loseResponse = failNextSend
        failNextSend = false
        val box = boxes.single {
            it.provision.delivery.authorizationValue() ==
                destination.authorizationValue()
        }
        if (tamperNextSend) {
            tamperNextSend = false
            val corrupted = envelope.copyBytes().also {
                it[it.lastIndex] = (it.last().toInt() xor 1).toByte()
            }
            box.messages += RelayReceivedEnvelope(
                RelayDeliveryId(token(16)),
                OpaqueEnvelope.fromEncryptedBytes(corrupted)
            )
        }
        val id = RelayDeliveryId(token(16))
        box.messages += RelayReceivedEnvelope(id, envelope)
        if (loseResponse) throw RelayNetworkException("Lost acceptance response fixture")
        return id
    }

    override suspend fun receive(source: RelayRetrievalMailbox): List<RelayReceivedEnvelope> {
        receiveCalls++
        return boxes.single {
            it.provision.retrieval.authorizationValue() == source.authorizationValue()
        }.messages.toList()
    }

    override suspend fun acknowledge(source: RelayRetrievalMailbox, id: RelayDeliveryId) {
        boxes.single { it.provision.retrieval.authorizationValue() == source.authorizationValue() }
            .messages.removeAll { it.id == id }
    }

    override suspend fun revoke(source: RelayRetrievalMailbox) {
        boxes.removeAll {
            it.provision.retrieval.authorizationValue() == source.authorizationValue()
        }
        revocations++
    }

    private fun token(bytes: Int): String = Base64.getUrlEncoder().withoutPadding()
        .encodeToString(ByteArray(bytes).also(SecureRandom()::nextBytes))
}
