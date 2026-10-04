package io.sodyx.app.security

import io.sodyx.app.data.crypto.EncryptedProtocolStateStore
import io.sodyx.app.data.crypto.StoredSecureMessage
import io.sodyx.app.network.OpaqueEnvelope
import io.sodyx.app.network.RelayDeliveryMailbox
import io.sodyx.app.network.RelayRetrievalMailbox
import io.sodyx.app.network.RelayTransport
import io.sodyx.security.EncryptedMessage
import io.sodyx.security.ProtocolAddress
import io.sodyx.security.SignalE2eeEngine
import io.sodyx.security.SignalFailure
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * An established, verified pairwise session. The same encrypted SQLite transaction
 * commits each ratchet step and its local message record before network I/O.
 */
internal class SecureMessagePipeline(
    private val store: EncryptedProtocolStateStore,
    private val engine: SignalE2eeEngine,
    private val outgoingRelay: RelayTransport,
    private val incomingRelay: RelayTransport,
    private val peer: ProtocolAddress,
    private val sessionId: String,
    private val outgoingMailbox: RelayDeliveryMailbox,
    private val incomingMailbox: RelayRetrievalMailbox,
    private val nowEpochMillis: () -> Long = System::currentTimeMillis
) {
    suspend fun send(text: String): StoredSecureMessage {
        require(text.isNotBlank()) { "Message cannot be blank" }
        val plaintext = text.encodeToByteArray()
        require(plaintext.size <= MAX_PLAINTEXT_BYTES) { "Message is too large" }
        val id = UUID.randomUUID().toString()
        val wire = withContext(Dispatchers.IO) {
            store.transaction {
                val encrypted = engine.encrypt(peer, plaintext)
                SecureWireEnvelope.encode(encrypted).also {
                    store.saveMessage(id, sessionId, true, plaintext, it, nowEpochMillis())
                }
            }
        }
        // A network failure leaves the encrypted record pending for retryPending().
        outgoingRelay.send(outgoingMailbox, OpaqueEnvelope.fromEncryptedBytes(wire))
        return withContext(Dispatchers.IO) {
            store.transaction {
                store.markDelivered(id)
                store.messages(sessionId).first { it.id == id }
            }
        }
    }

    suspend fun retryPending(): Int {
        val pending = withContext(Dispatchers.IO) {
            store.transaction {
                store.messages(sessionId).filter { it.senderLocal && !it.delivered }
            }
        }
        pending.forEach { message ->
            outgoingRelay.send(
                outgoingMailbox,
                OpaqueEnvelope.fromEncryptedBytes(message.wireEnvelope)
            )
            withContext(Dispatchers.IO) { store.transaction { store.markDelivered(message.id) } }
        }
        return pending.size
    }

    /** Acknowledge only after authenticated decryption and durable storage. */
    suspend fun poll(): Int {
        var accepted = 0
        for (received in incomingRelay.receive(incomingMailbox)) {
            val id = received.id.value
            val wire = received.envelope.copyBytes()
            val outcome = withContext(Dispatchers.IO) {
                try {
                    store.transaction {
                        if (store.hasMessage(id)) {
                            ReceiveOutcome.Duplicate
                        } else {
                            val encrypted = SecureWireEnvelope.decode(wire)
                            val plaintext = engine.decrypt(peer, encrypted)
                            if (plaintext.isEmpty() || plaintext.size > MAX_PLAINTEXT_BYTES) {
                                ReceiveOutcome.Invalid
                            } else {
                                store.saveMessage(
                                    id,
                                    sessionId,
                                    false,
                                    plaintext,
                                    wire,
                                    nowEpochMillis()
                                )
                                ReceiveOutcome.Accepted
                            }
                        }
                    }
                } catch (_: IllegalArgumentException) {
                    ReceiveOutcome.Invalid
                } catch (_: SignalFailure.ReplayedMessage) {
                    ReceiveOutcome.Duplicate
                } catch (_: SignalFailure.AuthenticationFailed) {
                    ReceiveOutcome.Invalid
                } catch (_: SignalFailure.UnsupportedCiphertextType) {
                    ReceiveOutcome.Invalid
                } catch (_: SignalFailure.IdentityMismatch) {
                    ReceiveOutcome.NeedsVerification
                } catch (_: SignalFailure.UntrustedIdentity) {
                    ReceiveOutcome.NeedsVerification
                } catch (_: SignalFailure.UnverifiedIdentity) {
                    ReceiveOutcome.NeedsVerification
                } catch (_: SignalFailure.MissingSession) {
                    ReceiveOutcome.NeedsVerification
                }
            }
            if (outcome == ReceiveOutcome.NeedsVerification) return accepted
            incomingRelay.acknowledge(incomingMailbox, received.id)
            if (outcome == ReceiveOutcome.Accepted) accepted++
        }
        return accepted
    }

    suspend fun messages(): List<StoredSecureMessage> = withContext(Dispatchers.IO) {
        store.transaction { store.messages(sessionId) }
    }

    suspend fun closeLocalSession() = withContext(Dispatchers.IO) {
        store.transaction {
            engine.destroySession(peer)
            store.deleteMessages(sessionId)
        }
    }

    private enum class ReceiveOutcome { Accepted, Duplicate, Invalid, NeedsVerification }

    private companion object {
        const val MAX_PLAINTEXT_BYTES = 64 * 1024
    }
}

/** Versioned binary wrapper; no identity, alias, mailbox, or plaintext field. */
internal object SecureWireEnvelope {
    private val magic = byteArrayOf('S'.code.toByte(), 'D'.code.toByte(), 'X'.code.toByte())
    private const val VERSION: Byte = 1
    private const val HEADER_BYTES = 9
    private const val MAX_WIRE_BYTES = 262_144

    fun encode(message: EncryptedMessage): ByteArray {
        require(message.type in 0..255)
        require(message.serialized.isNotEmpty())
        require(message.serialized.size <= MAX_WIRE_BYTES - HEADER_BYTES)
        return ByteBuffer.allocate(HEADER_BYTES + message.serialized.size)
            .order(ByteOrder.BIG_ENDIAN)
            .put(magic)
            .put(VERSION)
            .put(message.type.toByte())
            .putInt(message.serialized.size)
            .put(message.serialized)
            .array()
    }

    fun decode(wire: ByteArray): EncryptedMessage {
        require(wire.size in (HEADER_BYTES + 1)..MAX_WIRE_BYTES)
        val buffer = ByteBuffer.wrap(wire).order(ByteOrder.BIG_ENDIAN)
        val foundMagic = ByteArray(magic.size).also(buffer::get)
        require(foundMagic.contentEquals(magic))
        require(buffer.get() == VERSION)
        val type = buffer.get().toInt() and 0xff
        val length = buffer.int
        require(length > 0 && length == buffer.remaining())
        return EncryptedMessage(type, ByteArray(length).also(buffer::get))
    }
}
