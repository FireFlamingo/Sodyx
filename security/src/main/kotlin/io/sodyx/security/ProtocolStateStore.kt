package io.sodyx.security

/**
 * The application-owned durable boundary for opaque libsignal state.
 *
 * A transaction covers an entire libsignal operation. It must be reentrant so
 * nested adapter callbacks share the same database transaction. Implementations
 * must return defensive copies of every byte array.
 */
interface ProtocolStateStore {
    fun <T> transaction(block: (ProtocolStateTransaction) -> T): T
}

interface ProtocolStateTransaction {
    fun localAccount(): LocalAccountState?

    fun saveLocalAccount(state: LocalAccountState)

    fun peerIdentity(address: ProtocolAddress): ByteArray?

    fun savePeerIdentity(
        address: ProtocolAddress,
        serializedIdentityKey: ByteArray
    ): IdentityWriteResult

    fun session(address: ProtocolAddress): ByteArray?

    fun saveSession(address: ProtocolAddress, serializedSession: ByteArray)

    fun deleteSession(address: ProtocolAddress)

    fun deleteSessions(name: String)

    fun sessionAddressesFor(name: String): Set<ProtocolAddress>

    fun preKey(id: Int): ByteArray?

    fun savePreKey(id: Int, serialized: ByteArray)

    fun removePreKey(id: Int)

    fun signedPreKey(id: Int): ByteArray?

    fun signedPreKeys(): Map<Int, ByteArray>

    fun saveSignedPreKey(id: Int, serialized: ByteArray)

    fun removeSignedPreKey(id: Int)

    fun kyberPreKey(id: Int): ByteArray?

    fun kyberPreKeys(): Map<Int, ByteArray>

    fun saveKyberPreKey(id: Int, serialized: ByteArray)

    fun markKyberPreKeyUsed(id: Int, baseKey: ByteArray): KyberPreKeyUse
}

data class LocalAccountState(val serializedIdentityKeyPair: ByteArray, val registrationId: Int)

data class ProtocolAddress(val name: String, val deviceId: Int) {
    init {
        require(name.isNotBlank()) { "Protocol address name must not be blank." }
        require(deviceId >= 1) { "Protocol device ID must be positive." }
    }
}

enum class IdentityWriteResult {
    NEW_OR_UNCHANGED,
    REPLACED_EXISTING
}

enum class KyberPreKeyUse {
    FIRST_USE,
    REUSED
}
