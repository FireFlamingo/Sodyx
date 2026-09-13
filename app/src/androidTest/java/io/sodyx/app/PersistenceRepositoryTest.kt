package io.sodyx.app

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.sodyx.app.data.SQLiteSodyxRepository
import io.sodyx.domain.DisplayAlias
import io.sodyx.domain.RelationshipId
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PersistenceRepositoryTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var dbName: String
    private lateinit var repository: SQLiteSodyxRepository

    @Before fun setUp() {
        dbName = "sodyx-test-${UUID.randomUUID()}.db"
        repository = SQLiteSodyxRepository(context, dbName)
    }

    @After fun tearDown() {
        repository.close()
        context.deleteDatabase(dbName)
    }

    @Test fun identityAndRelationshipSurviveReopen() {
        val identity = repository.localIdentity()
        val relationship = repository.createLocalTestRelationship(DisplayAlias("Copper Sky"))
        repository.close()
        repository = SQLiteSodyxRepository(context, dbName)
        assertEquals(identity, repository.localIdentity())
        assertEquals(listOf("Copper Sky"), repository.listRelationships().map { it.alias.value })
        assertEquals(relationship, repository.listRelationships().single().id)
    }

    @Test fun sessionAndMessagesSurviveReopenInInsertionOrder() {
        val relationship = repository.createLocalTestRelationship(DisplayAlias("Quiet Falcon"))
        val session = repository.startSession(relationship)
        repository.appendLocalMessage(session.id, "first")
        repository.appendLocalMessage(session.id, "second")
        repository.close()
        repository = SQLiteSodyxRepository(context, dbName)
        assertEquals(session, repository.activeSession(relationship))
        assertEquals(listOf("first", "second"), repository.loadMessages(session.id).map { it.text })
    }

    @Test fun repeatedStartReturnsOneActiveSession() {
        val relationship = repository.createLocalTestRelationship(DisplayAlias("One"))
        val first = repository.startSession(relationship)
        assertEquals(first, repository.startSession(relationship))
        assertEquals(first.id, repository.listRelationships().single().activeSessionId)
    }

    @Test fun endIsIdempotentAndRemovesMessagesFromNormalAndRawViews() {
        val relationship = repository.createLocalTestRelationship(DisplayAlias("Close me"))
        val session = repository.startSession(relationship)
        repository.appendLocalMessage(session.id, "erase me")
        repository.endSession(session.id)
        repository.endSession(session.id)
        assertNull(repository.activeSession(relationship))
        assertTrue(repository.loadMessages(session.id).isEmpty())
        repository.close()
        context.openOrCreateDatabase(dbName, Context.MODE_PRIVATE, null).use { db ->
            db.setForeignKeyConstraintsEnabled(true)
            db.rawQuery(
                "SELECT COUNT(*) FROM messages WHERE session_id = ?",
                arrayOf(session.id.value.toString())
            ).use { c ->
                assertTrue(c.moveToFirst())
                assertEquals(0, c.getInt(0))
            }
            db.rawQuery(
                "SELECT state FROM sessions WHERE id = ?",
                arrayOf(session.id.value.toString())
            ).use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("CLOSED", c.getString(0))
            }
        }
        repository = SQLiteSodyxRepository(context, dbName)
        assertNull(repository.activeSession(relationship))
        assertTrue(repository.loadMessages(session.id).isEmpty())
    }

    @Test fun staleAppendIsRejectedAndInvalidAppendRollsBack() {
        val relationship = repository.createLocalTestRelationship(DisplayAlias("Stale"))
        val session = repository.startSession(relationship)
        assertThrows(IllegalArgumentException::class.java) {
            repository.appendLocalMessage(session.id, " ")
        }
        assertTrue(repository.loadMessages(session.id).isEmpty())
        repository.endSession(session.id)
        assertThrows(IllegalArgumentException::class.java) {
            repository.appendLocalMessage(session.id, "too late")
        }
        assertTrue(repository.loadMessages(session.id).isEmpty())
    }

    @Test fun unrelatedRelationshipAndFreshSessionRemainIndependent() {
        val firstRelationship = repository.createLocalTestRelationship(DisplayAlias("First"))
        val secondRelationship = repository.createLocalTestRelationship(DisplayAlias("Second"))
        val firstSession = repository.startSession(firstRelationship)
        val secondSession = repository.startSession(secondRelationship)
        repository.appendLocalMessage(firstSession.id, "first only")
        repository.appendLocalMessage(secondSession.id, "second survives")
        repository.endSession(firstSession.id)
        assertEquals(
            "Second",
            repository.listRelationships().single {
                it.id == secondRelationship
            }.alias.value
        )
        assertEquals(secondSession.id, repository.activeSession(secondRelationship)?.id)
        assertEquals(
            listOf("second survives"),
            repository.loadMessages(secondSession.id).map {
                it.text
            }
        )
        val freshSession = repository.startSession(firstRelationship)
        assertNotEquals(firstSession.id, freshSession.id)
        assertTrue(repository.loadMessages(freshSession.id).isEmpty())
    }

    @Test fun separateRepositoriesSerializeAppendAndEndWithoutResurrection() {
        val relationship = repository.createLocalTestRelationship(DisplayAlias("Race"))
        val session = repository.startSession(relationship)
        val other = SQLiteSodyxRepository(context, dbName)
        val ready = CountDownLatch(2)
        val go = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val append = executor.submit {
                ready.countDown()
                go.await()
                try {
                    repository.appendLocalMessage(session.id, "race")
                    true
                } catch (_: IllegalArgumentException) {
                    false
                }
            }
            val end = executor.submit {
                ready.countDown()
                go.await()
                other.endSession(session.id)
                true
            }
            assertTrue(ready.await(2, TimeUnit.SECONDS))
            go.countDown()
            append.get(30, TimeUnit.SECONDS)
            end.get(30, TimeUnit.SECONDS)
            assertNull(other.activeSession(relationship))
            assertTrue(other.loadMessages(session.id).isEmpty())
        } finally {
            executor.shutdownNow()
            other.close()
        }
    }

    @Test fun missingRelationshipAndClosedRepositoryFailClearly() {
        assertThrows(IllegalArgumentException::class.java) {
            repository.startSession(RelationshipId(UUID.randomUUID()))
        }
        repository.close()
        assertThrows(IllegalStateException::class.java) { repository.localIdentity() }
        repository = SQLiteSodyxRepository(context, dbName)
    }

    @Test fun relationshipParticipantForeignKeysCannotBeOrphaned() {
        val relationship = repository.createLocalTestRelationship(DisplayAlias("Protected"))
        repository.close()
        context.openOrCreateDatabase(dbName, Context.MODE_PRIVATE, null).use { db ->
            db.setForeignKeyConstraintsEnabled(true)
            db.rawQuery(
                "SELECT local_pairwise_id FROM relationships WHERE id = ?",
                arrayOf(relationship.value.toString())
            ).use { c ->
                assertTrue(c.moveToFirst())
                assertThrows(SQLiteConstraintException::class.java) {
                    db.delete("pairwise_identities", "id = ?", arrayOf(c.getString(0)))
                }
            }
        }
        repository = SQLiteSodyxRepository(context, dbName)
        assertEquals("Protected", repository.listRelationships().single().alias.value)
    }

    @Test fun relationshipEndpointRolesAndAssignedRolesCannotBeChanged() {
        val relationship = repository.createLocalTestRelationship(DisplayAlias("Roles"))
        repository.close()
        context.openOrCreateDatabase(dbName, Context.MODE_PRIVATE, null).use { db ->
            db.setForeignKeyConstraintsEnabled(true)
            lateinit var local: String
            lateinit var peer: String
            db.rawQuery(
                "SELECT local_pairwise_id, peer_pairwise_id FROM relationships WHERE id = ?",
                arrayOf(relationship.value.toString())
            ).use { c ->
                assertTrue(c.moveToFirst())
                local = c.getString(0)
                peer = c.getString(1)
            }
            assertThrows(SQLiteConstraintException::class.java) {
                db.execSQL(
                    "UPDATE pairwise_identities SET role = 'PEER' WHERE id = ?",
                    arrayOf(local)
                )
            }
            assertThrows(SQLiteConstraintException::class.java) {
                db.execSQL(
                    "UPDATE relationships SET local_pairwise_id = ? WHERE id = ?",
                    arrayOf(peer, relationship.value.toString())
                )
            }
            assertThrows(SQLiteConstraintException::class.java) {
                db.execSQL(
                    "INSERT INTO relationships(id, alias, local_pairwise_id, peer_pairwise_id) VALUES (?, ?, ?, ?)",
                    arrayOf(UUID.randomUUID().toString(), "Invalid", peer, local)
                )
            }
        }
        repository = SQLiteSodyxRepository(context, dbName)
        assertEquals("Roles", repository.listRelationships().single().alias.value)
    }
}
