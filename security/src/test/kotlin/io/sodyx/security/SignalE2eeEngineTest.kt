package io.sodyx.security

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SignalE2eeEngineTest {
    private val aliceAddress = ProtocolAddress("alice-pairwise", 1)
    private val bobAddress = ProtocolAddress("bob-pairwise", 1)

    @Test
    fun verifiedOfflineSetupEncryptDecryptAndReplayRejection() {
        val alice = SignalE2eeEngine(MemoryProtocolStateStore())
        val bob = SignalE2eeEngine(MemoryProtocolStateStore())
        val aliceIdentity = alice.initializeIfNeeded()
        bob.initializeIfNeeded()
        bob.provisionPreKeys(7, 31, 1, 11)
        val bundle = bob.publishPreKeyBundle(bobAddress, 31, 7, 11)

        assertThrows(SignalFailure.UnverifiedIdentity::class.java) {
            alice.establishSession(bundle)
        }
        alice.confirmVerifiedIdentity(bobAddress, bundle.serializedIdentityKey)
        bob.confirmVerifiedIdentity(aliceAddress, aliceIdentity.serializedIdentityKey)
        alice.establishSession(bundle)

        val ciphertext = alice.encrypt(bobAddress, "hello, bob".encodeToByteArray())
        assertArrayEquals("hello, bob".encodeToByteArray(), bob.decrypt(aliceAddress, ciphertext))
        assertThrows(SignalFailure.ReplayedMessage::class.java) {
            bob.decrypt(aliceAddress, ciphertext)
        }
        val reply = bob.encrypt(aliceAddress, "hello, alice".encodeToByteArray())
        assertArrayEquals("hello, alice".encodeToByteArray(), alice.decrypt(bobAddress, reply))
    }

    @Test
    fun tamperingDoesNotConsumeValidMessage() {
        val alice = SignalE2eeEngine(MemoryProtocolStateStore())
        val bob = SignalE2eeEngine(MemoryProtocolStateStore())
        val aliceIdentity = alice.initializeIfNeeded()
        bob.initializeIfNeeded()
        bob.provisionPreKeys(1, 1, 1, 1)
        val bundle = bob.publishPreKeyBundle(bobAddress, 1, 1, 1)
        alice.confirmVerifiedIdentity(bobAddress, bundle.serializedIdentityKey)
        bob.confirmVerifiedIdentity(aliceAddress, aliceIdentity.serializedIdentityKey)
        alice.establishSession(bundle)

        val valid = alice.encrypt(bobAddress, "authenticated".encodeToByteArray())
        val modified = valid.serialized.copyOf()
        modified[modified.lastIndex] = (modified.last().toInt() xor 1).toByte()
        assertThrows(SignalFailure::class.java) {
            bob.decrypt(aliceAddress, EncryptedMessage(valid.type, modified))
        }
        assertArrayEquals("authenticated".encodeToByteArray(), bob.decrypt(aliceAddress, valid))
    }

    @Test
    fun separateStoresGenerateDifferentPairwiseIdentities() {
        val first = SignalE2eeEngine(MemoryProtocolStateStore())
        val second = SignalE2eeEngine(MemoryProtocolStateStore())
        val firstIdentity = first.initializeIfNeeded().serializedIdentityKey
        assertFalse(firstIdentity.contentEquals(second.initializeIfNeeded().serializedIdentityKey))
        assertArrayEquals(firstIdentity, first.initializeIfNeeded().serializedIdentityKey)
    }

    @Test
    fun changedPeerIdentityRequiresExplicitReplacement() {
        val alice = SignalE2eeEngine(MemoryProtocolStateStore())
        val original = SignalE2eeEngine(MemoryProtocolStateStore()).initializeIfNeeded()
        val replacement = SignalE2eeEngine(MemoryProtocolStateStore()).initializeIfNeeded()
        alice.initializeIfNeeded()
        alice.confirmVerifiedIdentity(bobAddress, original.serializedIdentityKey)
        assertThrows(IllegalArgumentException::class.java) {
            alice.confirmVerifiedIdentity(bobAddress, replacement.serializedIdentityKey)
        }
        assertNotEquals(
            original.serializedIdentityKey.toList(),
            replacement.serializedIdentityKey.toList()
        )
        alice.acceptVerifiedIdentityChange(bobAddress, replacement.serializedIdentityKey)
        assertTrue(alice.initialize().serializedIdentityKey.isNotEmpty())
    }
}

/** Test store with rollback semantics, matching the app's durable transaction contract. */
private class MemoryProtocolStateStore : ProtocolStateStore {
    private var account: LocalAccountState? = null
    private var peers = mutableMapOf<ProtocolAddress, ByteArray>()
    private var sessions = mutableMapOf<ProtocolAddress, ByteArray>()
    private var preKeys = mutableMapOf<Int, ByteArray>()
    private var signedPreKeys = mutableMapOf<Int, ByteArray>()
    private var kyberPreKeys = mutableMapOf<Int, ByteArray>()
    private var usedKyberBases = mutableSetOf<Pair<Int, List<Byte>>>()

    override fun <T> transaction(block: (ProtocolStateTransaction) -> T): T {
        val beforeAccount = account?.let {
            LocalAccountState(it.serializedIdentityKeyPair.copyOf(), it.registrationId)
        }
        val beforePeers = peers.copyBytes()
        val beforeSessions = sessions.copyBytes()
        val beforePreKeys = preKeys.copyBytes()
        val beforeSigned = signedPreKeys.copyBytes()
        val beforeKyber = kyberPreKeys.copyBytes()
        val beforeUsed = usedKyberBases.toMutableSet()
        try {
            return block(Transaction())
        } catch (error: Throwable) {
            account = beforeAccount
            peers = beforePeers
            sessions = beforeSessions
            preKeys = beforePreKeys
            signedPreKeys = beforeSigned
            kyberPreKeys = beforeKyber
            usedKyberBases = beforeUsed
            throw error
        }
    }

    private inner class Transaction : ProtocolStateTransaction {
        override fun localAccount(): LocalAccountState? = account?.let {
            LocalAccountState(it.serializedIdentityKeyPair.copyOf(), it.registrationId)
        }

        override fun saveLocalAccount(state: LocalAccountState) {
            check(account == null)
            account =
                LocalAccountState(state.serializedIdentityKeyPair.copyOf(), state.registrationId)
        }

        override fun peerIdentity(address: ProtocolAddress): ByteArray? = peers[address]?.copyOf()

        override fun savePeerIdentity(
            address: ProtocolAddress,
            serializedIdentityKey: ByteArray
        ): IdentityWriteResult {
            val old = peers.put(address, serializedIdentityKey.copyOf())
            return if (old == null || old.contentEquals(serializedIdentityKey)) {
                IdentityWriteResult.NEW_OR_UNCHANGED
            } else {
                IdentityWriteResult.REPLACED_EXISTING
            }
        }

        override fun session(address: ProtocolAddress): ByteArray? = sessions[address]?.copyOf()

        override fun saveSession(address: ProtocolAddress, serializedSession: ByteArray) {
            sessions[address] = serializedSession.copyOf()
        }

        override fun deleteSession(address: ProtocolAddress) {
            sessions.remove(address)
        }

        override fun deleteSessions(name: String) {
            sessions.keys.removeAll { it.name == name }
        }

        override fun sessionAddressesFor(name: String): Set<ProtocolAddress> =
            sessions.keys.filterTo(mutableSetOf()) { it.name == name }

        override fun preKey(id: Int): ByteArray? = preKeys[id]?.copyOf()

        override fun savePreKey(id: Int, serialized: ByteArray) {
            preKeys[id] = serialized.copyOf()
        }

        override fun removePreKey(id: Int) {
            preKeys.remove(id)
        }

        override fun signedPreKey(id: Int): ByteArray? = signedPreKeys[id]?.copyOf()

        override fun signedPreKeys(): Map<Int, ByteArray> = signedPreKeys.copyBytes()

        override fun saveSignedPreKey(id: Int, serialized: ByteArray) {
            signedPreKeys[id] = serialized.copyOf()
        }

        override fun removeSignedPreKey(id: Int) {
            signedPreKeys.remove(id)
        }

        override fun kyberPreKey(id: Int): ByteArray? = kyberPreKeys[id]?.copyOf()

        override fun kyberPreKeys(): Map<Int, ByteArray> = kyberPreKeys.copyBytes()

        override fun saveKyberPreKey(id: Int, serialized: ByteArray) {
            kyberPreKeys[id] = serialized.copyOf()
        }

        override fun markKyberPreKeyUsed(id: Int, baseKey: ByteArray): KyberPreKeyUse =
            if (usedKyberBases.add(
                    id to baseKey.toList()
                )
            ) {
                KyberPreKeyUse.FIRST_USE
            } else {
                KyberPreKeyUse.REUSED
            }
    }

    private fun <K> MutableMap<K, ByteArray>.copyBytes(): MutableMap<K, ByteArray> =
        mapValuesTo(mutableMapOf()) { (_, value) -> value.copyOf() }
}
