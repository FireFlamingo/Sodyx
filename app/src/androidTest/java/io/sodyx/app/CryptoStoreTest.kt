package io.sodyx.app

import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.sodyx.app.data.crypto.EncryptedProtocolStateStore
import io.sodyx.security.KyberPreKeyUse
import io.sodyx.security.LocalAccountState
import io.sodyx.security.ProtocolAddress
import java.util.UUID
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CryptoStoreTest {
    @Test
    fun encryptedStateSurvivesReopenAndRollsBackFailedTransactions() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val databaseName = "protocol-test-${UUID.randomUUID()}.db"
        val peer = ProtocolAddress("one-relationship", 1)
        val secret = "private identity material".encodeToByteArray()
        val store = EncryptedProtocolStateStore(context, databaseName)
        try {
            store.transaction { transaction ->
                transaction.saveLocalAccount(LocalAccountState(secret, 42))
                transaction.saveSession(peer, "ratchet-state".encodeToByteArray())
                transaction.saveKyberPreKey(7, "kyber-key".encodeToByteArray())
                store.putSecret("relay-retrieval", byteArrayOf(9, 8, 7))
                store.saveMessage(
                    "message-1",
                    "session-1",
                    true,
                    "hello".encodeToByteArray(),
                    byteArrayOf(1, 2, 3),
                    1_700_000_000_000L
                )
            }
            assertThrows(IllegalStateException::class.java) {
                store.transaction { transaction ->
                    transaction.saveSession(peer, "rolled-back".encodeToByteArray())
                    store.saveMessage(
                        "message-2",
                        "session-1",
                        true,
                        "rollback".encodeToByteArray(),
                        byteArrayOf(4),
                        1_700_000_000_001L
                    )
                    error("abort transaction")
                }
            }
            assertEquals(
                KyberPreKeyUse.FIRST_USE,
                store.transaction {
                    it.markKyberPreKeyUsed(7, byteArrayOf(1))
                }
            )
            assertEquals(
                KyberPreKeyUse.REUSED,
                store.transaction {
                    it.markKyberPreKeyUsed(7, byteArrayOf(1))
                }
            )
            assertEquals(
                KyberPreKeyUse.FIRST_USE,
                store.transaction {
                    it.markKyberPreKeyUsed(7, byteArrayOf(2))
                }
            )

            SQLiteDatabase.openDatabase(
                context.getDatabasePath(databaseName).path,
                null,
                SQLiteDatabase.OPEN_READONLY
            )
                .use { raw ->
                    raw.rawQuery("SELECT payload FROM local_account", null).use { cursor ->
                        assertTrue(cursor.moveToFirst())
                        assertFalse(cursor.getBlob(0).contentEquals(secret))
                    }
                    raw.rawQuery("SELECT body FROM message_records WHERE id = 'message-1'", null)
                        .use { cursor ->
                            assertTrue(cursor.moveToFirst())
                            assertFalse(
                                cursor.getBlob(0).contentEquals("hello".encodeToByteArray())
                            )
                        }
                }
            store.close()

            val reopened = EncryptedProtocolStateStore(context, databaseName)
            try {
                reopened.transaction { transaction ->
                    assertArrayEquals(secret, transaction.localAccount()?.serializedIdentityKeyPair)
                    assertArrayEquals(
                        "ratchet-state".encodeToByteArray(),
                        transaction.session(peer)
                    )
                    assertEquals(
                        KyberPreKeyUse.REUSED,
                        transaction.markKyberPreKeyUsed(7, byteArrayOf(1))
                    )
                    assertArrayEquals(byteArrayOf(9, 8, 7), reopened.secret("relay-retrieval"))
                    assertFalse(reopened.hasMessage("message-2"))
                    val messages = reopened.messages("session-1")
                    assertEquals(1, messages.size)
                    assertArrayEquals("hello".encodeToByteArray(), messages.single().plaintext)
                    assertArrayEquals(byteArrayOf(1, 2, 3), messages.single().wireEnvelope)
                }
                reopened.destroy()
                assertFalse(context.getDatabasePath(databaseName).exists())
            } finally {
                reopened.close()
            }
        } finally {
            store.close()
            context.deleteDatabase(databaseName)
        }
    }

    @Test
    fun peerIdentityReplacementIsReportedAndRowsAreScoped() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val databaseName = "protocol-test-${UUID.randomUUID()}.db"
        val store = EncryptedProtocolStateStore(context, databaseName)
        val first = ProtocolAddress("contact-a", 1)
        val second = ProtocolAddress("contact-b", 1)
        try {
            store.transaction { transaction ->
                assertNotNull(transaction.savePeerIdentity(first, byteArrayOf(1)))
                transaction.savePeerIdentity(second, byteArrayOf(2))
                transaction.saveSession(first, byteArrayOf(3))
                transaction.saveSession(second, byteArrayOf(4))
                transaction.deleteSessions(first.name)
                assertEquals(null, transaction.session(first))
                assertArrayEquals(byteArrayOf(4), transaction.session(second))
            }
        } finally {
            store.destroy()
        }
    }
}
