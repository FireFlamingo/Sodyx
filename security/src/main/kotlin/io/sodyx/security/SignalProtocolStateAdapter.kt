package io.sodyx.security

import org.signal.libsignal.protocol.IdentityKey
import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.protocol.InvalidKeyIdException
import org.signal.libsignal.protocol.NoSessionException
import org.signal.libsignal.protocol.ReusedBaseKeyException
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.ecc.ECPublicKey
import org.signal.libsignal.protocol.groups.state.SenderKeyRecord
import org.signal.libsignal.protocol.state.IdentityKeyStore
import org.signal.libsignal.protocol.state.KyberPreKeyRecord
import org.signal.libsignal.protocol.state.PreKeyRecord
import org.signal.libsignal.protocol.state.SessionRecord
import org.signal.libsignal.protocol.state.SignalProtocolStore
import org.signal.libsignal.protocol.state.SignedPreKeyRecord

/** Adapts opaque, application-persisted libsignal records to libsignal's store APIs. */
internal class SignalProtocolStateAdapter(private val state: ProtocolStateStore) :
    SignalProtocolStore {
    private val currentTransaction = ThreadLocal<ProtocolStateTransaction?>()

    fun <T> inTransaction(block: () -> T): T = state.transaction { transaction ->
        check(currentTransaction.get() == null) { "A libsignal transaction is already active." }
        currentTransaction.set(transaction)
        try {
            block()
        } finally {
            currentTransaction.remove()
        }
    }

    override fun getIdentityKeyPair(): IdentityKeyPair =
        IdentityKeyPair(requireTransaction().localAccountOrThrow().serializedIdentityKeyPair)

    fun localAccountOrNull(): LocalAccountState? = requireTransaction().localAccount()

    fun saveLocalAccount(state: LocalAccountState) {
        requireTransaction().saveLocalAccount(state)
    }

    override fun getLocalRegistrationId(): Int =
        requireTransaction().localAccountOrThrow().registrationId

    override fun saveIdentity(
        address: SignalProtocolAddress,
        identityKey: IdentityKey
    ): IdentityKeyStore.IdentityChange {
        val transaction = requireTransaction()
        return when (
            transaction.savePeerIdentity(address.toSodyxAddress(), identityKey.serialize())
        ) {
            IdentityWriteResult.NEW_OR_UNCHANGED -> IdentityKeyStore.IdentityChange.NEW_OR_UNCHANGED
            IdentityWriteResult.REPLACED_EXISTING ->
                IdentityKeyStore.IdentityChange.REPLACED_EXISTING
        }
    }

    override fun isTrustedIdentity(
        address: SignalProtocolAddress,
        identityKey: IdentityKey,
        direction: IdentityKeyStore.Direction
    ): Boolean {
        val existing = requireTransaction().peerIdentity(address.toSodyxAddress()) ?: return true
        return existing.contentEquals(identityKey.serialize())
    }

    override fun getIdentity(address: SignalProtocolAddress): IdentityKey? =
        requireTransaction().peerIdentity(address.toSodyxAddress())?.let(::IdentityKey)

    override fun loadPreKey(preKeyId: Int): PreKeyRecord =
        requireTransaction().preKey(preKeyId)?.let(::PreKeyRecord)
            ?: throw InvalidKeyIdException("No prekey exists for ID $preKeyId.")

    override fun storePreKey(preKeyId: Int, record: PreKeyRecord) {
        requireTransaction().savePreKey(preKeyId, record.serialize())
    }

    override fun containsPreKey(preKeyId: Int): Boolean =
        requireTransaction().preKey(preKeyId) != null

    override fun removePreKey(preKeyId: Int) {
        requireTransaction().removePreKey(preKeyId)
    }

    override fun loadSession(address: SignalProtocolAddress): SessionRecord =
        requireTransaction().session(address.toSodyxAddress())?.let(::SessionRecord)
            ?: SessionRecord()

    override fun loadExistingSessions(addresses: List<SignalProtocolAddress>): List<SessionRecord> =
        addresses.map { address ->
            requireTransaction().session(address.toSodyxAddress())?.let(::SessionRecord)
                ?: throw NoSessionException("No session exists for $address.")
        }

    override fun getSubDeviceSessions(name: String): List<Int> =
        requireTransaction().sessionAddressesFor(name).map(ProtocolAddress::deviceId)

    override fun storeSession(address: SignalProtocolAddress, record: SessionRecord) {
        requireTransaction().saveSession(address.toSodyxAddress(), record.serialize())
    }

    override fun containsSession(address: SignalProtocolAddress): Boolean =
        requireTransaction().session(address.toSodyxAddress()) != null

    override fun deleteSession(address: SignalProtocolAddress) {
        requireTransaction().deleteSession(address.toSodyxAddress())
    }

    override fun deleteAllSessions(name: String) {
        requireTransaction().deleteSessions(name)
    }

    override fun loadSignedPreKey(signedPreKeyId: Int): SignedPreKeyRecord =
        requireTransaction().signedPreKey(signedPreKeyId)?.let(::SignedPreKeyRecord)
            ?: throw InvalidKeyIdException("No signed prekey exists for ID $signedPreKeyId.")

    override fun loadSignedPreKeys(): List<SignedPreKeyRecord> =
        requireTransaction().signedPreKeys().values.map(::SignedPreKeyRecord)

    override fun storeSignedPreKey(signedPreKeyId: Int, record: SignedPreKeyRecord) {
        requireTransaction().saveSignedPreKey(signedPreKeyId, record.serialize())
    }

    override fun containsSignedPreKey(signedPreKeyId: Int): Boolean =
        requireTransaction().signedPreKey(signedPreKeyId) != null

    override fun removeSignedPreKey(signedPreKeyId: Int) {
        requireTransaction().removeSignedPreKey(signedPreKeyId)
    }

    override fun loadKyberPreKey(kyberPreKeyId: Int): KyberPreKeyRecord =
        requireTransaction().kyberPreKey(kyberPreKeyId)?.let(::KyberPreKeyRecord)
            ?: throw InvalidKeyIdException("No Kyber prekey exists for ID $kyberPreKeyId.")

    override fun loadKyberPreKeys(): List<KyberPreKeyRecord> =
        requireTransaction().kyberPreKeys().values.map(::KyberPreKeyRecord)

    override fun storeKyberPreKey(kyberPreKeyId: Int, record: KyberPreKeyRecord) {
        requireTransaction().saveKyberPreKey(kyberPreKeyId, record.serialize())
    }

    override fun containsKyberPreKey(kyberPreKeyId: Int): Boolean =
        requireTransaction().kyberPreKey(kyberPreKeyId) != null

    override fun markKyberPreKeyUsed(
        kyberPreKeyId: Int,
        signedPreKeyId: Int,
        baseKey: ECPublicKey
    ) {
        if (
            requireTransaction().markKyberPreKeyUsed(kyberPreKeyId, baseKey.serialize()) ==
            KyberPreKeyUse.REUSED
        ) {
            throw ReusedBaseKeyException(
                "Kyber prekey $kyberPreKeyId was already used by this base key."
            )
        }
    }

    override fun storeSenderKey(
        sender: SignalProtocolAddress,
        distributionId: java.util.UUID,
        record: SenderKeyRecord
    ) = unsupportedGroupOperation()

    override fun loadSenderKey(
        sender: SignalProtocolAddress,
        distributionId: java.util.UUID
    ): SenderKeyRecord = unsupportedGroupOperation()

    private fun requireTransaction(): ProtocolStateTransaction =
        checkNotNull(currentTransaction.get()) {
            "libsignal storage was used outside an engine transaction."
        }

    private fun ProtocolStateTransaction.localAccountOrThrow(): LocalAccountState =
        requireNotNull(localAccount()) { "No local libsignal account has been initialized." }

    private fun SignalProtocolAddress.toSodyxAddress() = ProtocolAddress(getName(), getDeviceId())

    private fun unsupportedGroupOperation(): Nothing = throw UnsupportedOperationException(
        "Sodyx's pairwise adapter does not provide group sender-key storage."
    )
}
