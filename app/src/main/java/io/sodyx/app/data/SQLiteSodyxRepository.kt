package io.sodyx.app.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import io.sodyx.domain.DisplayAlias
import io.sodyx.domain.InvitationDirection
import io.sodyx.domain.InvitationPayload
import io.sodyx.domain.InvitationState
import io.sodyx.domain.LocalDeviceIdentityRef
import io.sodyx.domain.Message
import io.sodyx.domain.MessageId
import io.sodyx.domain.PairwiseIdentity
import io.sodyx.domain.PairwiseIdentityId
import io.sodyx.domain.RelationshipId
import io.sodyx.domain.Session
import io.sodyx.domain.SessionId
import java.util.UUID

private const val DB_NAME = "sodyx-local.db"
private const val DB_VERSION = 2

class SQLiteSodyxRepository(context: Context, databaseName: String = DB_NAME) : SodyxRepository {
    private val helper = Database(context.applicationContext, databaseName)
    private val db: SQLiteDatabase by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        helper.writableDatabase
    }
    private var closed = false

    override fun localIdentity(): LocalDeviceIdentityRef = synchronized(this) {
        ensureOpen()
        LocalDeviceIdentityRef(UUID.fromString(db.queryOne("local_identity", "id")))
    }

    override fun listRelationships(): List<RelationshipRow> = synchronized(this) {
        ensureOpen()
        db.rawQuery(
            "SELECT r.id, r.alias, s.id, r.peer_pairwise_id FROM relationships r " +
                "LEFT JOIN sessions s ON s.relationship_id = r.id AND s.state = 'ACTIVE' " +
                "ORDER BY r.rowid",
            null
        ).use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(
                        RelationshipRow(
                            RelationshipId(UUID.fromString(c.getString(0))),
                            DisplayAlias(c.getString(1)),
                            c.getStringOrNull(2)?.let { SessionId(UUID.fromString(it)) },
                            c.getString(3)
                        )
                    )
                }
            }
        }
    }

    override fun createLocalTestRelationship(alias: DisplayAlias): RelationshipId =
        synchronized(this) {
            ensureOpen()
            val relationship = UUID.randomUUID()
            val localPair = UUID.randomUUID()
            val peerPair = UUID.randomUUID()
            db.transaction {
                insertOrThrow(
                    "pairwise_identities",
                    null,
                    values(
                        "id" to localPair.toString(),
                        "role" to "LOCAL"
                    )
                )
                insertOrThrow(
                    "pairwise_identities",
                    null,
                    values(
                        "id" to peerPair.toString(),
                        "role" to "PEER"
                    )
                )
                insertOrThrow(
                    "relationships",
                    null,
                    values(
                        "id" to relationship.toString(),
                        "alias" to alias.value,
                        "local_pairwise_id" to localPair.toString(),
                        "peer_pairwise_id" to peerPair.toString()
                    )
                )
            }
            RelationshipId(relationship)
        }

    override fun startSession(relationshipId: RelationshipId): SessionRow = synchronized(this) {
        ensureOpen()
        try {
            db.transaction<SessionRow> {
                val existing = querySession(relationshipId)
                if (existing != null) {
                    existing
                } else {
                    val rel = db.rawQuery(
                        "SELECT alias, local_pairwise_id, peer_pairwise_id FROM relationships WHERE id = ?",
                        arrayOf(relationshipId.value.toString())
                    ).use { c ->
                        require(c.moveToFirst()) { "Relationship not found" }
                        arrayOf(c.getString(0), c.getString(1), c.getString(2))
                    }
                    val id = SessionId(UUID.randomUUID())
                    Session.restore(
                        id,
                        relationshipId,
                        PairwiseIdentity(PairwiseIdentityId(UUID.fromString(rel[1]))),
                        PairwiseIdentity(PairwiseIdentityId(UUID.fromString(rel[2]))),
                        DisplayAlias("You"),
                        DisplayAlias(rel[0]),
                        io.sodyx.domain.SessionState.CREATED
                    ).transitionTo(io.sodyx.domain.SessionState.ACTIVE)
                    insertOrThrow(
                        "sessions",
                        null,
                        values(
                            "id" to id.value.toString(),
                            "relationship_id" to relationshipId.value.toString(),
                            "local_alias" to "You",
                            "peer_alias" to rel[0],
                            "state" to "ACTIVE"
                        )
                    )
                    SessionRow(id, relationshipId, DisplayAlias("You"), DisplayAlias(rel[0]))
                }
            }
        } catch (_: SQLiteConstraintException) {
            querySession(relationshipId)
                ?: throw IllegalStateException("Session could not be created")
        }
    }

    override fun activeSession(relationshipId: RelationshipId): SessionRow? = synchronized(this) {
        ensureOpen()
        querySession(relationshipId)
    }

    override fun loadMessages(sessionId: SessionId): List<MessageRow> = synchronized(this) {
        ensureOpen()
        db.rawQuery(
            "SELECT m.id, m.sender_local, m.text FROM messages m JOIN sessions s ON s.id = m.session_id AND s.state = 'ACTIVE' WHERE m.session_id = ? ORDER BY m.sequence",
            arrayOf(sessionId.value.toString())
        ).use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(
                        MessageRow(
                            MessageId(UUID.fromString(c.getString(0))),
                            sessionId,
                            c.getInt(1) != 0,
                            c.getString(2)
                        )
                    )
                }
            }
        }
    }

    override fun appendLocalMessage(sessionId: SessionId, text: String): MessageRow =
        synchronized(this) {
            ensureOpen()
            db.transaction<MessageRow> {
                require(text.isNotBlank()) { "Message text must not be blank" }
                val stored = readStoredSession(sessionId, requireActive = true)
                val relationshipId = stored.relationshipId
                val session = Session.restore(
                    sessionId,
                    relationshipId,
                    PairwiseIdentity(PairwiseIdentityId(UUID.fromString(stored.localPairwiseId))),
                    PairwiseIdentity(PairwiseIdentityId(UUID.fromString(stored.peerPairwiseId))),
                    stored.localAlias,
                    stored.peerAlias,
                    io.sodyx.domain.SessionState.ACTIVE
                )
                val id = MessageId(UUID.randomUUID())
                Message.create(session, id, session.participants.local.identity, text)
                insertOrThrow(
                    "messages",
                    null,
                    values(
                        "id" to id.value.toString(),
                        "session_id" to sessionId.value.toString(),
                        "sender_local" to 1,
                        "text" to text
                    )
                )
                MessageRow(id, sessionId, true, text)
            }
        }

    override fun endSession(sessionId: SessionId) = synchronized(this) {
        ensureOpen()
        db.transaction {
            db.delete("messages", "session_id = ?", arrayOf(sessionId.value.toString()))
            val stored = readStoredSession(sessionId, requireActive = false)
            val state = stored.state
            if (state == "ACTIVE") {
                Session.restore(
                    sessionId,
                    stored.relationshipId,
                    PairwiseIdentity(PairwiseIdentityId(UUID.fromString(stored.localPairwiseId))),
                    PairwiseIdentity(PairwiseIdentityId(UUID.fromString(stored.peerPairwiseId))),
                    stored.localAlias,
                    stored.peerAlias,
                    io.sodyx.domain.SessionState.ACTIVE
                ).transitionTo(
                    io.sodyx.domain.SessionState.ENDING
                ).transitionTo(io.sodyx.domain.SessionState.CLOSED)
                db.compileStatement(
                    "UPDATE sessions SET state = 'CLOSED' WHERE id = ?"
                ).use { statement ->
                    statement.bindString(1, sessionId.value.toString())
                    require(statement.executeUpdateDelete() == 1) { "Session close failed" }
                }
            }
        }
    }

    override fun createInvitation(nowEpochMillis: Long, ttlMillis: Long): InvitationRow =
        synchronized(this) {
            ensureOpen()
            require(ttlMillis in 1..86_400_000L)
            require(nowEpochMillis >= 0)
            val expires = try {
                Math.addExact(nowEpochMillis, ttlMillis)
            } catch (_: ArithmeticException) {
                throw IllegalArgumentException("Invitation expiry overflows epoch milliseconds")
            }
            val payload =
                InvitationPayload(
                    io.sodyx.domain.InvitationId(UUID.randomUUID()),
                    UUID.randomUUID(),
                    io.sodyx.domain.PairwiseIdentityId(UUID.randomUUID()),
                    nowEpochMillis,
                    expires
                )
            db.insertOrThrow(
                "invitations",
                null,
                values(
                    "id" to payload.id.value.toString(),
                    "token" to payload.token.toString(),
                    "pseudonym" to payload.pseudonym.value.toString(),
                    "created_at" to payload.createdEpochMillis,
                    "expires_at" to payload.expiresEpochMillis,
                    "state" to "OPEN",
                    "direction" to "OUTBOUND"
                )
            )
            InvitationRow(payload, InvitationState.OPEN, InvitationDirection.OUTBOUND)
        }

    override fun listInvitations(): List<InvitationRow> = synchronized(this) {
        ensureOpen()
        db.rawQuery(
            "SELECT id, token, pseudonym, created_at, expires_at, state, direction FROM invitations ORDER BY rowid",
            null
        ).use { c ->
            buildList { while (c.moveToNext()) add(invitationFrom(c)) }
        }
    }

    override fun redeemInvitation(
        payload: String,
        alias: DisplayAlias,
        nowEpochMillis: Long
    ): RedemptionResult = synchronized(this) {
        ensureOpen()
        val p = try {
            InvitationPayload.parse(payload)
        } catch (_: IllegalArgumentException) {
            return@synchronized RedemptionResult.Invalid
        }
        if (nowEpochMillis < 0L || nowEpochMillis < p.createdEpochMillis) {
            return@synchronized RedemptionResult.Invalid
        }
        db.transaction<RedemptionResult> {
            val existing = db.rawQuery(
                "SELECT token, pseudonym, created_at, expires_at, state FROM invitations WHERE id = ?",
                arrayOf(p.id.value.toString())
            ).use { c ->
                if (!c.moveToFirst()) {
                    null
                } else {
                    listOf(
                        c.getString(0),
                        c.getString(1),
                        c.getLong(2).toString(),
                        c.getLong(3).toString(),
                        c.getString(4)
                    )
                }
            }
            val matches = existing == null || (
                existing[0] == p.token.toString() &&
                    existing[1] == p.pseudonym.value.toString() &&
                    existing[2] == p.createdEpochMillis.toString() &&
                    existing[3] == p.expiresEpochMillis.toString()
                )
            if (!matches) return@transaction RedemptionResult.Invalid
            if (existing != null && nowEpochMillis < existing[2].toLong()) {
                return@transaction RedemptionResult.Invalid
            }
            if (existing?.get(4) == "REDEEMED") return@transaction RedemptionResult.AlreadyRedeemed
            if (nowEpochMillis >= (existing?.get(3)?.toLong() ?: p.expiresEpochMillis) ||
                existing?.get(4) == "EXPIRED"
            ) {
                db.execSQL(
                    "UPDATE invitations SET state='EXPIRED' WHERE id=?",
                    arrayOf(p.id.value.toString())
                )
                return@transaction RedemptionResult.Expired
            }
            val collision = db.rawQuery(
                "SELECT 1 FROM invitations WHERE token = ? OR pseudonym = ? " +
                    "UNION ALL SELECT 1 FROM pairwise_identities WHERE id = ? LIMIT 1",
                arrayOf(
                    p.token.toString(),
                    p.pseudonym.value.toString(),
                    p.pseudonym.value.toString()
                )
            ).use { it.moveToFirst() }
            if (existing == null && collision) return@transaction RedemptionResult.Invalid
            val invitationId = p.id.value.toString()
            db.execSQL(
                "INSERT OR IGNORE INTO invitations(id,token,pseudonym,created_at,expires_at,state,direction) VALUES(?,?,?,?,?,'OPEN','IMPORTED')",
                arrayOf<Any?>(
                    invitationId,
                    p.token.toString(),
                    p.pseudonym.value.toString(),
                    p.createdEpochMillis,
                    p.expiresEpochMillis
                )
            )
            val relationship = UUID.randomUUID()
            val local = UUID.randomUUID()
            val peer = p.pseudonym.value
            insertOrThrow(
                "pairwise_identities",
                null,
                values(
                    "id" to local.toString(),
                    "role" to "LOCAL"
                )
            )
            insertOrThrow(
                "pairwise_identities",
                null,
                values("id" to peer.toString(), "role" to "PEER")
            )
            insertOrThrow(
                "relationships",
                null,
                values(
                    "id" to relationship.toString(),
                    "alias" to alias.value,
                    "local_pairwise_id" to local.toString(),
                    "peer_pairwise_id" to peer.toString()
                )
            )
            val changed = db.compileStatement(
                "UPDATE invitations SET state='REDEEMED', redeemed_at=?, relationship_id=? WHERE id=? AND token=? AND state='OPEN' AND expires_at>? "
            ).use { s ->
                s.bindLong(1, nowEpochMillis)
                s.bindString(2, relationship.toString())
                s.bindString(3, invitationId)
                s.bindString(4, p.token.toString())
                s.bindLong(5, nowEpochMillis)
                s.executeUpdateDelete()
            }
            require(changed == 1) { "Invitation redemption race" }
            RedemptionResult.Redeemed(RelationshipId(relationship))
        }
    }

    override fun close() {
        synchronized(this) {
            if (!closed) {
                closed = true
                helper.close()
            }
        }
    }
    private fun ensureOpen() {
        check(!closed) { "Repository is closed" }
    }

    private fun querySession(id: RelationshipId): SessionRow? = db.rawQuery(
        "SELECT id, local_alias, peer_alias FROM sessions WHERE relationship_id = ? AND state = 'ACTIVE'",
        arrayOf(id.value.toString())
    ).use { c ->
        if (!c.moveToFirst()) {
            null
        } else {
            SessionRow(
                SessionId(UUID.fromString(c.getString(0))),
                id,
                DisplayAlias(c.getString(1)),
                DisplayAlias(c.getString(2))
            )
        }
    }
    private fun invitationFrom(c: android.database.Cursor): InvitationRow = InvitationRow(
        InvitationPayload(
            io.sodyx.domain.InvitationId(UUID.fromString(c.getString(0))),
            UUID.fromString(c.getString(1)),
            io.sodyx.domain.PairwiseIdentityId(UUID.fromString(c.getString(2))),
            c.getLong(3),
            c.getLong(4)
        ),
        InvitationState.valueOf(c.getString(5)),
        InvitationDirection.valueOf(c.getString(6))
    )
    private data class StoredSession(
        val relationshipId: RelationshipId,
        val localPairwiseId: String,
        val peerPairwiseId: String,
        val localAlias: DisplayAlias,
        val peerAlias: DisplayAlias,
        val state: String
    )
    private fun readStoredSession(id: SessionId, requireActive: Boolean): StoredSession =
        db.rawQuery(
            "SELECT s.relationship_id, r.local_pairwise_id, r.peer_pairwise_id, s.local_alias, s.peer_alias, s.state FROM sessions s JOIN relationships r ON r.id = s.relationship_id WHERE s.id = ?",
            arrayOf(id.value.toString())
        ).use { c ->
            require(c.moveToFirst()) { "Session not found" }
            val state = c.getString(5)
            if (requireActive) require(state == "ACTIVE") { "Session is not active" }
            StoredSession(
                RelationshipId(UUID.fromString(c.getString(0))),
                c.getString(1),
                c.getString(2),
                DisplayAlias(c.getString(3)),
                DisplayAlias(c.getString(4)),
                state
            )
        }

    private class Database(context: Context, name: String) :
        SQLiteOpenHelper(context, name, null, DB_VERSION) {
        // Default rollback journaling is deliberate for this small local store; transactions remain atomic across close/reopen boundaries.
        override fun onConfigure(db: SQLiteDatabase) {
            db.setForeignKeyConstraintsEnabled(true)
            db.disableWriteAheadLogging()
        }
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE local_identity (id TEXT PRIMARY KEY NOT NULL)")
            db.execSQL(
                "CREATE TABLE pairwise_identities (id TEXT PRIMARY KEY NOT NULL, role TEXT NOT NULL CHECK(role IN ('LOCAL','PEER')))"
            )
            db.execSQL(
                "CREATE TABLE relationships (id TEXT PRIMARY KEY NOT NULL, alias TEXT NOT NULL, local_pairwise_id TEXT NOT NULL UNIQUE REFERENCES pairwise_identities(id), peer_pairwise_id TEXT NOT NULL UNIQUE REFERENCES pairwise_identities(id), CHECK(local_pairwise_id <> peer_pairwise_id))"
            )
            db.execSQL(
                "CREATE TRIGGER relationships_roles_insert BEFORE INSERT ON relationships BEGIN SELECT CASE WHEN (SELECT role FROM pairwise_identities WHERE id = NEW.local_pairwise_id) <> 'LOCAL' OR (SELECT role FROM pairwise_identities WHERE id = NEW.peer_pairwise_id) <> 'PEER' THEN RAISE(ABORT, 'invalid pairwise endpoint roles') END; END"
            )
            db.execSQL(
                "CREATE TRIGGER relationships_roles_update BEFORE UPDATE OF local_pairwise_id, peer_pairwise_id ON relationships BEGIN SELECT CASE WHEN (SELECT role FROM pairwise_identities WHERE id = NEW.local_pairwise_id) <> 'LOCAL' OR (SELECT role FROM pairwise_identities WHERE id = NEW.peer_pairwise_id) <> 'PEER' THEN RAISE(ABORT, 'invalid pairwise endpoint roles') END; END"
            )
            db.execSQL(
                "CREATE TRIGGER pairwise_role_immutable BEFORE UPDATE OF role ON pairwise_identities BEGIN SELECT CASE WHEN EXISTS (SELECT 1 FROM relationships WHERE local_pairwise_id = OLD.id OR peer_pairwise_id = OLD.id) THEN RAISE(ABORT, 'pairwise role is immutable once assigned') END; END"
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
            db.execSQL(
                "CREATE TABLE invitations (id TEXT PRIMARY KEY NOT NULL, token TEXT UNIQUE NOT NULL, pseudonym TEXT UNIQUE NOT NULL, created_at INTEGER NOT NULL, expires_at INTEGER NOT NULL, state TEXT NOT NULL CHECK(state IN ('OPEN','REDEEMED','EXPIRED')), direction TEXT NOT NULL CHECK(direction IN ('OUTBOUND','IMPORTED')), redeemed_at INTEGER, relationship_id TEXT REFERENCES relationships(id), CHECK(created_at >= 0 AND expires_at > created_at), CHECK((state='REDEEMED') = (redeemed_at IS NOT NULL AND relationship_id IS NOT NULL)))"
            )
            db.execSQL("CREATE INDEX invitations_open_expiry ON invitations(state, expires_at)")
            db.insertOrThrow(
                "local_identity",
                null,
                ContentValues().apply {
                    put("id", UUID.randomUUID().toString())
                }
            )
        }
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            require(oldVersion == 1 && newVersion == 2) {
                "Unsupported database upgrade: $oldVersion to $newVersion"
            }
            if (oldVersion == 1) {
                db.execSQL(
                    "CREATE TABLE invitations (id TEXT PRIMARY KEY NOT NULL, token TEXT UNIQUE NOT NULL, pseudonym TEXT UNIQUE NOT NULL, created_at INTEGER NOT NULL, expires_at INTEGER NOT NULL, state TEXT NOT NULL CHECK(state IN ('OPEN','REDEEMED','EXPIRED')), direction TEXT NOT NULL CHECK(direction IN ('OUTBOUND','IMPORTED')), redeemed_at INTEGER, relationship_id TEXT REFERENCES relationships(id), CHECK(created_at >= 0 AND expires_at > created_at), CHECK((state='REDEEMED') = (redeemed_at IS NOT NULL AND relationship_id IS NOT NULL)))"
                )
                db.execSQL("CREATE INDEX invitations_open_expiry ON invitations(state, expires_at)")
            }
        }
    }
}

private fun <T> SQLiteDatabase.transaction(block: SQLiteDatabase.() -> T): T {
    beginTransaction()
    return try {
        val result = block()
        setTransactionSuccessful()
        result
    } finally {
        endTransaction()
    }
}
private fun values(vararg pairs: Pair<String, Any>): ContentValues = ContentValues().apply {
    pairs.forEach { (key, value) ->
        when (value) {
            is String -> put(key, value)
            is Int -> put(key, value)
            is Long -> put(key, value)
            else -> error("Unsupported value type")
        }
    }
}
private fun android.database.Cursor.getStringOrNull(index: Int): String? =
    if (isNull(index)) null else getString(index)
private fun SQLiteDatabase.queryOne(table: String, column: String): String =
    rawQuery("SELECT $column FROM $table LIMIT 1", null).use { c ->
        require(c.moveToFirst())
        c.getString(0)
    }
