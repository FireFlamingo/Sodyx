package io.sodyx.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.sodyx.app.data.RedemptionResult
import io.sodyx.app.data.SQLiteSodyxRepository
import io.sodyx.domain.DisplayAlias
import io.sodyx.domain.InvitationDirection
import io.sodyx.domain.InvitationState
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class InvitationRepositoryTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var name: String
    private lateinit var repository: SQLiteSodyxRepository

    @Before fun setUp() {
        name = "sodyx-invite-${UUID.randomUUID()}.db"
        repository = SQLiteSodyxRepository(context, name)
    }

    @After fun tearDown() {
        repository.close()
        context.deleteDatabase(name)
    }

    @Test fun createListAndReopenPreservesOutboundInvitation() {
        val created = repository.createInvitation(1_000L, 60_000L)
        assertEquals(InvitationState.OPEN, created.state)
        assertEquals(InvitationDirection.OUTBOUND, created.direction)
        repository.close()
        repository = SQLiteSodyxRepository(context, name)
        assertEquals(created.payload, repository.listInvitations().single().payload)
    }

    @Test fun ttlBoundsAndOverflowAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { repository.createInvitation(0, 0) }
        assertThrows(IllegalArgumentException::class.java) {
            repository.createInvitation(0, 86_400_001)
        }
        assertThrows(IllegalArgumentException::class.java) { repository.createInvitation(-1, 1) }
        assertThrows(IllegalArgumentException::class.java) {
            repository.createInvitation(Long.MAX_VALUE, 1)
        }
    }

    @Test fun validImportCreatesAliasPseudonymRelationshipAndRedeemedState() {
        val outbound = repository.createInvitation(1_000, 60_000)
        val result = repository.redeemInvitation(
            outbound.payload.encode(),
            DisplayAlias("Copper Sky"),
            2_000
        )
        assertTrue(result is RedemptionResult.Redeemed)
        val relationshipId = (result as RedemptionResult.Redeemed).relationshipId
        val row = repository.listRelationships().single { it.id == relationshipId }
        assertEquals("Copper Sky", row.alias.value)
        assertEquals(outbound.payload.pseudonym.value.toString(), row.peerPseudonym)
        assertEquals(InvitationState.REDEEMED, repository.listInvitations().single().state)
        assertEquals(InvitationDirection.OUTBOUND, repository.listInvitations().single().direction)
        assertEquals(
            RedemptionResult.AlreadyRedeemed,
            repository.redeemInvitation(outbound.payload.encode(), DisplayAlias("Other"), 2_001)
        )
        assertEquals(1, repository.listRelationships().size)
    }

    @Test fun invitationRedeemedInRecipientDatabaseIsMarkedImported() {
        val sourceName = "sodyx-invite-source-${UUID.randomUUID()}.db"
        val source = SQLiteSodyxRepository(context, sourceName)
        try {
            val invitation = source.createInvitation(1_000, 60_000)
            val result = repository.redeemInvitation(
                invitation.payload.encode(),
                DisplayAlias("Imported"),
                2_000
            )
            assertTrue(result is RedemptionResult.Redeemed)
            assertEquals(
                InvitationDirection.IMPORTED,
                repository.listInvitations().single().direction
            )
            assertEquals("Imported", repository.listRelationships().single().alias.value)
            assertEquals(
                invitation.payload.pseudonym.value.toString(),
                repository.listRelationships().single().peerPseudonym
            )
        } finally {
            source.close()
            context.deleteDatabase(sourceName)
        }
    }

    @Test fun exactExpiryIsRejectedAndMarkedExpired() {
        val invitation = repository.createInvitation(1_000, 60_000)
        assertEquals(
            RedemptionResult.Expired,
            repository.redeemInvitation(
                invitation.payload.encode(),
                DisplayAlias("Expired"),
                61_000
            )
        )
        assertEquals(InvitationState.EXPIRED, repository.listInvitations().single().state)
        assertEquals(
            RedemptionResult.Expired,
            repository.redeemInvitation(invitation.payload.encode(), DisplayAlias("Again"), 61_001)
        )
    }

    @Test fun redemptionRejectsBeforeCreatedAndAcceptsExactCreatedBoundary() {
        val invitation = repository.createInvitation(1_000, 60_000)
        assertEquals(
            RedemptionResult.Invalid,
            repository.redeemInvitation(invitation.payload.encode(), DisplayAlias("Too early"), -1)
        )
        assertEquals(1, repository.listInvitations().size)
        assertEquals(InvitationState.OPEN, repository.listInvitations().single().state)
        assertEquals(0, repository.listRelationships().size)
        val result = repository.redeemInvitation(
            invitation.payload.encode(),
            DisplayAlias("At creation"),
            1_000
        )
        assertTrue(result is RedemptionResult.Redeemed)
        assertEquals(1, repository.listRelationships().size)
    }

    @Test fun malformedOrMismatchedPayloadDoesNotCreateRows() {
        val invitation = repository.createInvitation(1_000, 60_000)
        val before = repository.listRelationships().size
        assertEquals(
            RedemptionResult.Invalid,
            repository.redeemInvitation(
                invitation.payload.encode() + "|tamper=x",
                DisplayAlias("Bad"),
                2_000
            )
        )
        val mismatched = invitation.payload.encode().replace(
            "pseudonym=${invitation.payload.pseudonym.value}",
            "pseudonym=${UUID.randomUUID()}"
        )
        assertEquals(
            RedemptionResult.Invalid,
            repository.redeemInvitation(mismatched, DisplayAlias("Bad"), 2_000)
        )
        assertEquals(before, repository.listRelationships().size)
        assertEquals(InvitationState.OPEN, repository.listInvitations().single().state)
    }

    @Test fun pseudonymCollisionRollsBackSecondRedemptionAndPreservesIsolation() {
        val first = repository.createInvitation(1_000, 60_000)
        repository.redeemInvitation(first.payload.encode(), DisplayAlias("First"), 2_000)
        val unknown = io.sodyx.domain.InvitationPayload(
            io.sodyx.domain.InvitationId(UUID.randomUUID()),
            UUID.randomUUID(),
            first.payload.pseudonym,
            1_000,
            61_000
        )
        assertEquals(
            RedemptionResult.Invalid,
            repository.redeemInvitation(unknown.encode(), DisplayAlias("Collision"), 2_001)
        )
        assertEquals(1, repository.listRelationships().size)
        assertEquals(1, repository.listInvitations().size)
    }

    @Test fun unknownPayloadCollidingWithExistingRelationshipPeerReturnsInvalid() {
        val relationship = repository.createLocalTestRelationship(DisplayAlias("Existing"))
        val existingPeer = repository.listRelationships().single {
            it.id == relationship
        }.peerPseudonym
        val unknown = io.sodyx.domain.InvitationPayload(
            io.sodyx.domain.InvitationId(UUID.randomUUID()),
            UUID.randomUUID(),
            io.sodyx.domain.PairwiseIdentityId(UUID.fromString(existingPeer)),
            1_000,
            61_000
        )
        assertEquals(
            RedemptionResult.Invalid,
            repository.redeemInvitation(unknown.encode(), DisplayAlias("Collision"), 2_000)
        )
        assertEquals(1, repository.listRelationships().size)
        assertTrue(repository.listInvitations().isEmpty())
    }

    @Test fun concurrentRepositoriesRedeemExactlyOnce() {
        val invitation = repository.createInvitation(1_000, 60_000)
        val second = SQLiteSodyxRepository(context, name)
        val ready = CountDownLatch(2)
        val go = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val first = pool.submit<RedemptionResult> {
                ready.countDown()
                go.await()
                repository.redeemInvitation(invitation.payload.encode(), DisplayAlias("One"), 2_000)
            }
            val other = pool.submit<RedemptionResult> {
                ready.countDown()
                go.await()
                second.redeemInvitation(invitation.payload.encode(), DisplayAlias("Two"), 2_000)
            }
            assertTrue(ready.await(30, TimeUnit.SECONDS))
            go.countDown()
            val results = listOf(first.get(30, TimeUnit.SECONDS), other.get(30, TimeUnit.SECONDS))
            assertEquals(1, results.count { it is RedemptionResult.Redeemed })
            assertEquals(1, results.count { it == RedemptionResult.AlreadyRedeemed })
            assertEquals(1, repository.listRelationships().size)
        } finally {
            pool.shutdownNow()
            second.close()
        }
    }

    @Test fun phase3VersionOneDatabaseMigratesAndPreservesExistingRows() {
        repository.close()
        context.deleteDatabase(name)
        val local = UUID.randomUUID().toString()
        val peer = UUID.randomUUID().toString()
        val relationship = UUID.randomUUID().toString()
        val session = UUID.randomUUID().toString()
        val message = UUID.randomUUID().toString()
        context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null).use { db ->
            db.setForeignKeyConstraintsEnabled(true)
            db.execSQL("CREATE TABLE local_identity (id TEXT PRIMARY KEY NOT NULL)")
            db.execSQL(
                "CREATE TABLE pairwise_identities (id TEXT PRIMARY KEY NOT NULL, role TEXT NOT NULL CHECK(role IN ('LOCAL','PEER')))"
            )
            db.execSQL(
                "CREATE TABLE relationships (id TEXT PRIMARY KEY NOT NULL, alias TEXT NOT NULL, local_pairwise_id TEXT NOT NULL UNIQUE REFERENCES pairwise_identities(id), peer_pairwise_id TEXT NOT NULL UNIQUE REFERENCES pairwise_identities(id))"
            )
            db.execSQL(
                "CREATE TABLE sessions (id TEXT PRIMARY KEY NOT NULL, relationship_id TEXT NOT NULL REFERENCES relationships(id), local_alias TEXT NOT NULL, peer_alias TEXT NOT NULL, state TEXT NOT NULL CHECK(state IN ('ACTIVE','CLOSED')))"
            )
            db.execSQL(
                "CREATE UNIQUE INDEX one_active_session ON sessions(relationship_id) WHERE state = 'ACTIVE'"
            )
            db.execSQL(
                "CREATE TABLE messages (sequence INTEGER PRIMARY KEY AUTOINCREMENT, id TEXT UNIQUE NOT NULL, session_id TEXT NOT NULL REFERENCES sessions(id) ON DELETE CASCADE, sender_local INTEGER NOT NULL CHECK(sender_local IN (0,1)), text TEXT NOT NULL)"
            )
            db.execSQL("INSERT INTO local_identity(id) VALUES (?)", arrayOf(local))
            db.execSQL(
                "INSERT INTO pairwise_identities(id,role) VALUES (?, 'LOCAL'), (?, 'PEER')",
                arrayOf(local, peer)
            )
            db.execSQL(
                "INSERT INTO relationships(id,alias,local_pairwise_id,peer_pairwise_id) VALUES (?, 'Legacy', ?, ?)",
                arrayOf(relationship, local, peer)
            )
            db.execSQL(
                "INSERT INTO sessions(id,relationship_id,local_alias,peer_alias,state) VALUES (?, ?, 'You', 'Legacy', 'ACTIVE')",
                arrayOf(session, relationship)
            )
            db.execSQL(
                "INSERT INTO messages(id,session_id,sender_local,text) VALUES (?, ?, 1, 'preserved')",
                arrayOf(message, session)
            )
            db.execSQL("PRAGMA user_version = 1")
        }
        repository = SQLiteSodyxRepository(context, name)
        assertEquals(local, repository.localIdentity().value.toString())
        assertEquals("Legacy", repository.listRelationships().single().alias.value)
        val relationshipId = repository.listRelationships().single().id
        val sessionRow = repository.activeSession(relationshipId)
        assertTrue(sessionRow != null)
        assertEquals(listOf("preserved"), repository.loadMessages(sessionRow!!.id).map { it.text })
        assertTrue(repository.listInvitations().isEmpty())
    }

    @Test fun closedRepositoryRejectsInvitationOperations() {
        repository.close()
        assertThrows(IllegalStateException::class.java) { repository.createInvitation(1, 1) }
        assertThrows(IllegalStateException::class.java) { repository.listInvitations() }
        assertThrows(IllegalStateException::class.java) {
            repository.redeemInvitation("bad", DisplayAlias("x"), 1)
        }
        repository = SQLiteSodyxRepository(context, name)
    }
}
