package io.sodyx.security

import java.time.Clock
import org.signal.libsignal.protocol.DuplicateMessageException
import org.signal.libsignal.protocol.IdentityKey
import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.protocol.InvalidKeyException
import org.signal.libsignal.protocol.InvalidKeyIdException
import org.signal.libsignal.protocol.InvalidMessageException
import org.signal.libsignal.protocol.InvalidVersionException
import org.signal.libsignal.protocol.LegacyMessageException
import org.signal.libsignal.protocol.NoSessionException
import org.signal.libsignal.protocol.SessionBuilder
import org.signal.libsignal.protocol.SessionCipher
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.UntrustedIdentityException
import org.signal.libsignal.protocol.ecc.ECKeyPair
import org.signal.libsignal.protocol.ecc.ECPublicKey
import org.signal.libsignal.protocol.kem.KEMKeyPair
import org.signal.libsignal.protocol.kem.KEMKeyType
import org.signal.libsignal.protocol.kem.KEMPublicKey
import org.signal.libsignal.protocol.message.CiphertextMessage
import org.signal.libsignal.protocol.message.PreKeySignalMessage
import org.signal.libsignal.protocol.message.SignalMessage
import org.signal.libsignal.protocol.state.KyberPreKeyRecord
import org.signal.libsignal.protocol.state.PreKeyBundle
import org.signal.libsignal.protocol.state.PreKeyRecord
import org.signal.libsignal.protocol.state.SignedPreKeyRecord
import org.signal.libsignal.protocol.util.KeyHelper

/**
 * Narrow pairwise E2EE boundary backed directly by libsignal 0.102.2.
 *
 * All state transitions happen inside [ProtocolStateStore.transaction], so a
 * caller's database implementation can commit ratchet state atomically with
 * its encrypted envelope bookkeeping.
 */
class SignalE2eeEngine(state: ProtocolStateStore, private val clock: Clock = Clock.systemUTC()) {
    private val protocolStore = SignalProtocolStateAdapter(state)

    fun initialize(): LocalIdentity = protocolStore.inTransaction {
        val existing = protocolStore.getIdentityKeyPair()
        LocalIdentity(existing.publicKey.serialize(), protocolStore.localRegistrationId)
    }

    /** Creates the local account once. Calling it again returns the persisted identity. */
    fun initializeIfNeeded(): LocalIdentity = protocolStore.inTransaction {
        if (protocolStore.localAccountOrNull() == null) {
            createLocalAccount()
        } else {
            val existing = protocolStore.getIdentityKeyPair()
            LocalIdentity(existing.publicKey.serialize(), protocolStore.localRegistrationId)
        }
    }

    /**
     * Creates a signed prekey, one-time prekeys, and a Kyber prekey. IDs are
     * caller assigned so the delivery service can make rotation auditable.
     */
    fun provisionPreKeys(
        signedPreKeyId: Int,
        firstOneTimePreKeyId: Int,
        oneTimePreKeyCount: Int,
        kyberPreKeyId: Int
    ) {
        require(signedPreKeyId >= 0)
        require(firstOneTimePreKeyId >= 0)
        require(oneTimePreKeyCount > 0)
        require(kyberPreKeyId >= 0)
        require(firstOneTimePreKeyId.toLong() + oneTimePreKeyCount - 1 <= Int.MAX_VALUE)
        protocolStore.inTransaction {
            val account = protocolStore.getIdentityKeyPair()
            val timestamp = clock.millis()
            val signedKeyPair = ECKeyPair.generate()
            protocolStore.storeSignedPreKey(
                signedPreKeyId,
                SignedPreKeyRecord(
                    signedPreKeyId,
                    timestamp,
                    signedKeyPair,
                    account.privateKey.calculateSignature(signedKeyPair.publicKey.serialize())
                )
            )
            repeat(oneTimePreKeyCount) { offset ->
                val keyId = firstOneTimePreKeyId + offset
                protocolStore.storePreKey(keyId, PreKeyRecord(keyId, ECKeyPair.generate()))
            }
            val kyberKeyPair = KEMKeyPair.generate(KEMKeyType.KYBER_1024)
            protocolStore.storeKyberPreKey(
                kyberPreKeyId,
                KyberPreKeyRecord(
                    kyberPreKeyId,
                    timestamp,
                    kyberKeyPair,
                    account.privateKey.calculateSignature(kyberKeyPair.publicKey.serialize())
                )
            )
        }
    }

    /** Returns a public prekey bundle that can be delivered asynchronously. */
    fun publishPreKeyBundle(
        address: ProtocolAddress,
        oneTimePreKeyId: Int,
        signedPreKeyId: Int,
        kyberPreKeyId: Int
    ): PublicPreKeyBundle = protocolStore.inTransaction {
        val account = protocolStore.getIdentityKeyPair()
        val oneTime = protocolStore.loadPreKey(oneTimePreKeyId)
        val signed = protocolStore.loadSignedPreKey(signedPreKeyId)
        val kyber = protocolStore.loadKyberPreKey(kyberPreKeyId)
        PublicPreKeyBundle(
            address = address,
            registrationId = protocolStore.localRegistrationId,
            oneTimePreKeyId = oneTime.id,
            serializedOneTimePreKey = oneTime.keyPair.publicKey.serialize(),
            signedPreKeyId = signed.id,
            serializedSignedPreKey = signed.keyPair.publicKey.serialize(),
            signedPreKeySignature = signed.signature,
            serializedIdentityKey = account.publicKey.serialize(),
            kyberPreKeyId = kyber.id,
            serializedKyberPreKey = kyber.keyPair.publicKey.serialize(),
            kyberPreKeySignature = kyber.signature
        )
    }

    /** Records a peer key only after the caller has verified it through a separate trusted channel. */
    fun confirmVerifiedIdentity(peer: ProtocolAddress, serializedIdentityKey: ByteArray) =
        protocolStore.inTransaction {
            val address = peer.toSignalAddress()
            val existing = protocolStore.getIdentity(address)
            require(existing == null || existing.serialize().contentEquals(serializedIdentityKey)) {
                "An identity change requires explicit replacement."
            }
            protocolStore.saveIdentity(address, IdentityKey(serializedIdentityKey))
        }

    /** Establishes an outbound session only for a previously verified peer key. */
    fun establishSession(peer: PublicPreKeyBundle) = protocolStore.inTransaction {
        val pinned = protocolStore.getIdentity(peer.address.toSignalAddress())
            ?: throw SignalFailure.UnverifiedIdentity()
        if (!pinned.serialize().contentEquals(peer.serializedIdentityKey)) {
            throw SignalFailure.IdentityMismatch()
        }
        val localAddress = selfAddress()
        SessionBuilder(
            protocolStore,
            peer.address.toSignalAddress(),
            localAddress
        ).process(peer.toLibsignalBundle())
    }

    fun encrypt(peer: ProtocolAddress, plaintext: ByteArray): EncryptedMessage =
        protocolStore.inTransaction {
            val encrypted = SessionCipher(
                protocolStore,
                selfAddress(),
                peer.toSignalAddress()
            ).encrypt(plaintext)
            EncryptedMessage(encrypted.type, encrypted.serialize())
        }

    /**
     * Authenticates and decrypts a libsignal envelope. A duplicate, tampered,
     * malformed, legacy, missing-session, or untrusted-identity input produces
     * an explicit [SignalFailure], never plaintext.
     */
    fun decrypt(peer: ProtocolAddress, encrypted: EncryptedMessage): ByteArray =
        protocolStore.inTransaction {
            val cipher = SessionCipher(protocolStore, selfAddress(), peer.toSignalAddress())
            try {
                when (encrypted.type) {
                    CiphertextMessage.PREKEY_TYPE -> {
                        val message = PreKeySignalMessage(encrypted.serialized)
                        val pinned = protocolStore.getIdentity(peer.toSignalAddress())
                            ?: throw SignalFailure.UnverifiedIdentity()
                        if (!pinned.serialize().contentEquals(message.identityKey.serialize())) {
                            throw SignalFailure.IdentityMismatch()
                        }
                        cipher.decrypt(message)
                    }
                    CiphertextMessage.WHISPER_TYPE -> cipher.decrypt(
                        SignalMessage(encrypted.serialized)
                    )
                    else -> throw SignalFailure.UnsupportedCiphertextType(encrypted.type)
                }
            } catch (failure: SignalFailure) {
                throw failure
            } catch (failure: DuplicateMessageException) {
                throw SignalFailure.ReplayedMessage(failure)
            } catch (failure: UntrustedIdentityException) {
                throw SignalFailure.UntrustedIdentity(failure)
            } catch (failure: NoSessionException) {
                throw SignalFailure.MissingSession(failure)
            } catch (
                failure: InvalidMessageException
            ) {
                throw SignalFailure.AuthenticationFailed(failure)
            } catch (failure: InvalidVersionException) {
                throw SignalFailure.AuthenticationFailed(failure)
            } catch (failure: LegacyMessageException) {
                throw SignalFailure.AuthenticationFailed(failure)
            } catch (failure: InvalidKeyException) {
                throw SignalFailure.AuthenticationFailed(failure)
            } catch (failure: InvalidKeyIdException) {
                throw SignalFailure.AuthenticationFailed(failure)
            }
        }

    /** A verified identity replacement invalidates every existing session for that peer name. */
    fun acceptVerifiedIdentityChange(peer: ProtocolAddress, serializedIdentityKey: ByteArray) {
        protocolStore.inTransaction {
            protocolStore.deleteAllSessions(peer.name)
            protocolStore.saveIdentity(peer.toSignalAddress(), IdentityKey(serializedIdentityKey))
        }
    }

    fun destroySession(peer: ProtocolAddress) = protocolStore.inTransaction {
        protocolStore.deleteSession(peer.toSignalAddress())
    }

    private fun createLocalAccount(): LocalIdentity {
        val identity = IdentityKeyPair.generate()
        val registrationId = KeyHelper.generateRegistrationId(false)
        protocolStore.saveLocalAccount(LocalAccountState(identity.serialize(), registrationId))
        return LocalIdentity(identity.publicKey.serialize(), registrationId)
    }

    private fun selfAddress(): SignalProtocolAddress = SignalProtocolAddress("sodyx-self", 1)

    private fun ProtocolAddress.toSignalAddress() = SignalProtocolAddress(name, deviceId)
}

data class LocalIdentity(val serializedIdentityKey: ByteArray, val registrationId: Int)

data class PublicPreKeyBundle(
    val address: ProtocolAddress,
    val registrationId: Int,
    val oneTimePreKeyId: Int,
    val serializedOneTimePreKey: ByteArray,
    val signedPreKeyId: Int,
    val serializedSignedPreKey: ByteArray,
    val signedPreKeySignature: ByteArray,
    val serializedIdentityKey: ByteArray,
    val kyberPreKeyId: Int,
    val serializedKyberPreKey: ByteArray,
    val kyberPreKeySignature: ByteArray
) {
    internal fun toLibsignalBundle() = PreKeyBundle(
        registrationId,
        address.deviceId,
        oneTimePreKeyId,
        ECPublicKey(serializedOneTimePreKey),
        signedPreKeyId,
        ECPublicKey(serializedSignedPreKey),
        signedPreKeySignature,
        IdentityKey(serializedIdentityKey),
        kyberPreKeyId,
        KEMPublicKey(serializedKyberPreKey),
        kyberPreKeySignature
    )
}

data class EncryptedMessage(val type: Int, val serialized: ByteArray)

sealed class SignalFailure(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class UnverifiedIdentity : SignalFailure("The peer identity has not been verified.")

    class IdentityMismatch :
        SignalFailure("The peer prekey bundle does not match the verified identity.")

    class ReplayedMessage(cause: Throwable) :
        SignalFailure("The encrypted message was already processed.", cause)

    class AuthenticationFailed(cause: Throwable) :
        SignalFailure("The encrypted message failed authentication.", cause)

    class MissingSession(cause: Throwable) :
        SignalFailure("No session exists for this encrypted message.", cause)

    class UntrustedIdentity(cause: Throwable) :
        SignalFailure("The peer identity key changed or is untrusted.", cause)

    class UnsupportedCiphertextType(type: Int) :
        SignalFailure("Unsupported libsignal ciphertext type: $type.")
}
