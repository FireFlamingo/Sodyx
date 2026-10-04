package io.sodyx.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.sodyx.app.data.crypto.EncryptedProtocolStateStore
import io.sodyx.security.ProtocolAddress
import io.sodyx.security.SignalE2eeEngine
import io.sodyx.security.SignalFailure
import java.util.UUID
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SecureProtocolIntegrationTest {
    @Test
    fun verifiedPairwiseExchangeSurvivesReopenAndRejectsReplay() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val aliceName = "protocol-alice-${UUID.randomUUID()}.db"
        val bobName = "protocol-bob-${UUID.randomUUID()}.db"
        val aliceAddress = ProtocolAddress("alice-this-relationship", 1)
        val bobAddress = ProtocolAddress("bob-this-relationship", 1)
        val aliceStore = EncryptedProtocolStateStore(context, aliceName)
        val bobStore = EncryptedProtocolStateStore(context, bobName)

        try {
            val alice = SignalE2eeEngine(aliceStore)
            val bob = SignalE2eeEngine(bobStore)
            val aliceIdentity = alice.initializeIfNeeded()
            bob.initializeIfNeeded()
            bob.provisionPreKeys(41, 51, 2, 61)
            val bobBundle = bob.publishPreKeyBundle(bobAddress, 51, 41, 61)

            assertThrows(SignalFailure.UnverifiedIdentity::class.java) {
                alice.establishSession(bobBundle)
            }
            alice.confirmVerifiedIdentity(bobAddress, bobBundle.serializedIdentityKey)
            bob.confirmVerifiedIdentity(aliceAddress, aliceIdentity.serializedIdentityKey)
            alice.establishSession(bobBundle)

            val initial = alice.encrypt(bobAddress, "first secure message".encodeToByteArray())
            assertArrayEquals(
                "first secure message".encodeToByteArray(),
                bob.decrypt(aliceAddress, initial)
            )
            val reply = bob.encrypt(aliceAddress, "reply".encodeToByteArray())
            assertArrayEquals("reply".encodeToByteArray(), alice.decrypt(bobAddress, reply))

            aliceStore.close()
            bobStore.close()

            val reopenedAliceStore = EncryptedProtocolStateStore(context, aliceName)
            val reopenedBobStore = EncryptedProtocolStateStore(context, bobName)
            try {
                val reopenedAlice = SignalE2eeEngine(reopenedAliceStore)
                val reopenedBob = SignalE2eeEngine(reopenedBobStore)
                val afterRestart = reopenedAlice.encrypt(
                    bobAddress,
                    "after restart".encodeToByteArray()
                )
                assertArrayEquals(
                    "after restart".encodeToByteArray(),
                    reopenedBob.decrypt(aliceAddress, afterRestart)
                )
                assertThrows(SignalFailure.ReplayedMessage::class.java) {
                    reopenedBob.decrypt(aliceAddress, afterRestart)
                }
                reopenedAlice.destroySession(bobAddress)
                assertThrows(SignalFailure.MissingSession::class.java) {
                    reopenedAlice.encrypt(bobAddress, "must fail".encodeToByteArray())
                }
            } finally {
                reopenedAliceStore.destroy()
                reopenedBobStore.destroy()
            }
            assertFalse(context.getDatabasePath(aliceName).exists())
            assertFalse(context.getDatabasePath(bobName).exists())
        } finally {
            aliceStore.close()
            bobStore.close()
            context.deleteDatabase(aliceName)
            context.deleteDatabase(bobName)
        }
    }
}
