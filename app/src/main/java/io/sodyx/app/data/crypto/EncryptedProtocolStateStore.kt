package io.sodyx.app.data.crypto

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import io.sodyx.security.IdentityWriteResult
import io.sodyx.security.KyberPreKeyUse
import io.sodyx.security.LocalAccountState
import io.sodyx.security.ProtocolAddress
import io.sodyx.security.ProtocolStateStore
import io.sodyx.security.ProtocolStateTransaction
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Durable, transaction-scoped storage for opaque libsignal serializations.
 *
 * Each stored protocol blob is independently AES-GCM encrypted with a key held
 * by Android Keystore. SQLite only sees encrypted protocol material. Row-specific
 * additional authenticated data prevents a ciphertext from being moved between
 * protocol records without detection.
 */
class EncryptedProtocolStateStore(
    context: Context,
    private val databaseName: String,
    private val keyAlias: String = keyAliasFor(databaseName)
) : ProtocolStateStore,
    AutoCloseable {
    private val applicationContext = context.applicationContext
    private val helper = Database(applicationContext, databaseName)
    private val database: SQLiteDatabase by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        helper.writableDatabase
    }
    private val lock = Any()
    private val activeTransaction = ThreadLocal<TransactionContext?>()
    private var closed = false

    /** Application secrets and message records share the ratchet transaction. */
    fun putSecret(name: String, value: ByteArray) {
        requireRecordId(name)
        requireActiveTransaction()
        database.insertWithOnConflict(
            "app_secrets",
            null,
            values("name" to name, "payload" to encrypt(value, secretAad(name))),
            SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    fun secret(name: String): ByteArray? {
        requireRecordId(name)
        requireActiveTransaction()
        return database.query(
            "app_secrets",
            arrayOf("payload"),
            "name = ?",
            arrayOf(name),
            null,
            null,
            null
        ).use { cursor ->
            if (cursor.moveToFirst()) decrypt(cursor.getBlob(0), secretAad(name)) else null
        }
    }

    fun saveMessage(
        id: String,
        sessionId: String,
        senderLocal: Boolean,
        plaintext: ByteArray,
        wireEnvelope: ByteArray,
        createdAtEpochMillis: Long
    ) {
        requireRecordId(id)
        requireRecordId(sessionId)
        require(plaintext.isNotEmpty() && plaintext.size <= MAX_MESSAGE_BYTES)
        require(wireEnvelope.isNotEmpty() && wireEnvelope.size <= MAX_MESSAGE_BYTES)
        require(createdAtEpochMillis >= 0)
        requireActiveTransaction()
        database.insertOrThrow(
            "message_records",
            null,
            values(
                "id" to id,
                "session_id" to sessionId,
                "sender_local" to if (senderLocal) 1 else 0,
                "body" to encrypt(plaintext, messageAad(id, "body")),
                "wire" to encrypt(wireEnvelope, messageAad(id, "wire")),
                "delivered" to 0,
                "created_at" to createdAtEpochMillis
            )
        )
    }

    fun hasMessage(id: String): Boolean {
        requireRecordId(id)
        requireActiveTransaction()
        return database.rawQuery(
            "SELECT 1 FROM message_records WHERE id = ?",
            arrayOf(id)
        ).use { it.moveToFirst() }
    }

    fun markDelivered(id: String) {
        requireRecordId(id)
        requireActiveTransaction()
        database.execSQL("UPDATE message_records SET delivered = 1 WHERE id = ?", arrayOf(id))
    }

    fun messages(sessionId: String): List<StoredSecureMessage> {
        requireRecordId(sessionId)
        requireActiveTransaction()
        return database.rawQuery(
            "SELECT id, sender_local, body, wire, delivered, created_at FROM message_records " +
                "WHERE session_id = ? ORDER BY sequence",
            arrayOf(sessionId)
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val id = cursor.getString(0)
                    add(
                        StoredSecureMessage(
                            id,
                            sessionId,
                            cursor.getInt(1) != 0,
                            decrypt(cursor.getBlob(2), messageAad(id, "body")),
                            decrypt(cursor.getBlob(3), messageAad(id, "wire")),
                            cursor.getInt(4) != 0,
                            cursor.getLong(5)
                        )
                    )
                }
            }
        }
    }

    fun deleteMessages(sessionId: String) {
        requireRecordId(sessionId)
        requireActiveTransaction()
        database.delete("message_records", "session_id = ?", arrayOf(sessionId))
    }

    private fun requireActiveTransaction() {
        check(activeTransaction.get() != null) {
            "Message operations require a protocol transaction"
        }
    }

    private fun requireRecordId(value: String) {
        require(value.isNotBlank() && value.length <= 128 && value.none(Char::isISOControl))
    }

    private fun secretAad(name: String): ByteArray =
        "app_secret\u0000$name".toByteArray(StandardCharsets.UTF_8)

    private fun messageAad(id: String, column: String): ByteArray =
        "message\u0000$id\u0000$column".toByteArray(StandardCharsets.UTF_8)

    override fun <T> transaction(block: (ProtocolStateTransaction) -> T): T = synchronized(lock) {
        ensureOpen()
        activeTransaction.get()?.let { return@synchronized block(it) }

        database.beginTransaction()
        val context = TransactionContext(database)
        activeTransaction.set(context)
        try {
            val result = block(context)
            database.setTransactionSuccessful()
            result
        } finally {
            activeTransaction.remove()
            database.endTransaction()
        }
    }

    override fun close() = synchronized(lock) {
        if (!closed) {
            helper.close()
            closed = true
        }
    }

    /**
     * Destroys all persisted protocol state and removes the Keystore key that can
     * decrypt it. Call this only for explicit account/session destruction.
     */
    fun destroy() = synchronized(lock) {
        helper.close()
        closed = true
        // Delete the wrapping key first. Cleanup of a closed tombstone must not reopen a database.
        keyStore().deleteEntry(keyAlias)
        applicationContext.deleteDatabase(databaseName)
    }

    private fun ensureOpen() {
        check(!closed) { "Protocol state store is closed" }
    }

    private inner class TransactionContext(private val db: SQLiteDatabase) :
        ProtocolStateTransaction {
        override fun localAccount(): LocalAccountState? = db.rawQuery(
            "SELECT registration_id, payload FROM local_account WHERE singleton = 1",
            null
        ).use { cursor ->
            if (!cursor.moveToFirst()) {
                null
            } else {
                LocalAccountState(decrypt(cursor.getBlob(1), LOCAL_ACCOUNT_AAD), cursor.getInt(0))
            }
        }

        override fun saveLocalAccount(state: LocalAccountState) {
            require(state.registrationId > 0) { "Registration id must be positive" }
            db.insertOrThrow(
                "local_account",
                null,
                values(
                    "singleton" to 1,
                    "registration_id" to state.registrationId,
                    "payload" to encrypt(state.serializedIdentityKeyPair, LOCAL_ACCOUNT_AAD)
                )
            )
        }

        override fun peerIdentity(address: ProtocolAddress): ByteArray? = readBlob(
            "peer_identities",
            "name = ? AND device_id = ?",
            arrayOf(address.name, address.deviceId.toString()),
            addressAad("peer_identity", address)
        )

        override fun savePeerIdentity(
            address: ProtocolAddress,
            serializedIdentityKey: ByteArray
        ): IdentityWriteResult {
            val previous = peerIdentity(address)
            val result = if (previous == null || previous.contentEquals(serializedIdentityKey)) {
                IdentityWriteResult.NEW_OR_UNCHANGED
            } else {
                IdentityWriteResult.REPLACED_EXISTING
            }
            db.insertWithOnConflict(
                "peer_identities",
                null,
                values(
                    "name" to address.name,
                    "device_id" to address.deviceId,
                    "payload" to
                        encrypt(serializedIdentityKey, addressAad("peer_identity", address))
                ),
                SQLiteDatabase.CONFLICT_REPLACE
            )
            return result
        }

        override fun session(address: ProtocolAddress): ByteArray? = readBlob(
            "sessions",
            "name = ? AND device_id = ?",
            arrayOf(address.name, address.deviceId.toString()),
            addressAad("session", address)
        )

        override fun saveSession(address: ProtocolAddress, serializedSession: ByteArray) {
            db.insertWithOnConflict(
                "sessions",
                null,
                values(
                    "name" to address.name,
                    "device_id" to address.deviceId,
                    "payload" to encrypt(serializedSession, addressAad("session", address))
                ),
                SQLiteDatabase.CONFLICT_REPLACE
            )
        }

        override fun deleteSession(address: ProtocolAddress) {
            db.delete(
                "sessions",
                "name = ? AND device_id = ?",
                arrayOf(address.name, address.deviceId.toString())
            )
        }

        override fun deleteSessions(name: String) {
            require(name.isNotBlank()) { "Protocol address name must not be blank" }
            db.delete("sessions", "name = ?", arrayOf(name))
        }

        override fun sessionAddressesFor(name: String): Set<ProtocolAddress> {
            require(name.isNotBlank()) { "Protocol address name must not be blank" }
            return db.rawQuery(
                "SELECT name, device_id FROM sessions WHERE name = ? ORDER BY device_id",
                arrayOf(name)
            ).use { cursor ->
                buildSet {
                    while (cursor.moveToNext()) {
                        add(
                            ProtocolAddress(cursor.getString(0), cursor.getInt(1))
                        )
                    }
                }
            }
        }

        override fun preKey(id: Int): ByteArray? = numberedBlob("prekeys", id)

        override fun savePreKey(id: Int, serialized: ByteArray) =
            saveNumberedBlob("prekeys", id, serialized)

        override fun removePreKey(id: Int) = removeNumberedBlob("prekeys", id)

        override fun signedPreKey(id: Int): ByteArray? = numberedBlob("signed_prekeys", id)

        override fun signedPreKeys(): Map<Int, ByteArray> = numberedBlobs("signed_prekeys")

        override fun saveSignedPreKey(id: Int, serialized: ByteArray) =
            saveNumberedBlob("signed_prekeys", id, serialized)

        override fun removeSignedPreKey(id: Int) = removeNumberedBlob("signed_prekeys", id)

        override fun kyberPreKey(id: Int): ByteArray? = numberedBlob("kyber_prekeys", id)

        override fun kyberPreKeys(): Map<Int, ByteArray> = numberedBlobs("kyber_prekeys")

        override fun saveKyberPreKey(id: Int, serialized: ByteArray) =
            saveNumberedBlob("kyber_prekeys", id, serialized)

        override fun markKyberPreKeyUsed(id: Int, baseKey: ByteArray): KyberPreKeyUse {
            require(id >= 0) { "Prekey id must not be negative" }
            require(kyberPreKey(id) != null) { "Unknown Kyber prekey $id" }
            val baseKeyHash = MessageDigest.getInstance("SHA-256").digest(baseKey)
            return if (
                db.insertWithOnConflict(
                    "kyber_prekey_uses",
                    null,
                    values("prekey_id" to id, "base_key_hash" to baseKeyHash),
                    SQLiteDatabase.CONFLICT_IGNORE
                ) == -1L
            ) {
                KyberPreKeyUse.REUSED
            } else {
                KyberPreKeyUse.FIRST_USE
            }
        }

        private fun numberedBlob(table: String, id: Int): ByteArray? {
            require(id >= 0) { "Prekey id must not be negative" }
            return readBlob(
                table,
                "id = ?",
                arrayOf(id.toString()),
                numberedAad(table, id, "payload")
            )
        }

        private fun numberedBlobs(table: String): Map<Int, ByteArray> = db.rawQuery(
            "SELECT id, payload FROM $table ORDER BY id",
            null
        ).use { cursor ->
            buildMap {
                while (cursor.moveToNext()) {
                    val id = cursor.getInt(0)
                    put(id, decrypt(cursor.getBlob(1), numberedAad(table, id, "payload")))
                }
            }
        }

        private fun saveNumberedBlob(table: String, id: Int, serialized: ByteArray) {
            require(id >= 0) { "Prekey id must not be negative" }
            val existing = numberedBlob(table, id)
            require(existing == null || existing.contentEquals(serialized)) {
                "A published prekey ID cannot be replaced with different key material"
            }
            if (existing == null) {
                db.insertOrThrow(
                    table,
                    null,
                    values(
                        "id" to id,
                        "payload" to encrypt(serialized, numberedAad(table, id, "payload"))
                    )
                )
            }
        }

        private fun removeNumberedBlob(table: String, id: Int) {
            require(id >= 0) { "Prekey id must not be negative" }
            db.delete(table, "id = ?", arrayOf(id.toString()))
        }

        private fun readBlob(
            table: String,
            selection: String,
            arguments: Array<String>,
            aad: ByteArray
        ): ByteArray? =
            db.query(table, arrayOf("payload"), selection, arguments, null, null, null).use {
                if (it.moveToFirst()) decrypt(it.getBlob(0), aad) else null
            }
    }

    private fun encrypt(plaintext: ByteArray, aad: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, encryptionKey())
        val nonce = cipher.iv
        check(nonce.size == NONCE_LENGTH) { "Unexpected Android Keystore GCM nonce length" }
        cipher.updateAAD(aad)
        val encrypted = cipher.doFinal(plaintext.copyOf())
        return ByteBuffer.allocate(1 + nonce.size + encrypted.size)
            .put(CIPHERTEXT_VERSION)
            .put(nonce)
            .put(encrypted)
            .array()
    }

    private fun decrypt(stored: ByteArray, aad: ByteArray): ByteArray {
        require(stored.size >= 1 + NONCE_LENGTH + GCM_TAG_BYTES) {
            "Invalid encrypted protocol state"
        }
        require(stored[0] == CIPHERTEXT_VERSION) { "Unsupported protocol state encryption version" }
        val nonce = stored.copyOfRange(1, 1 + NONCE_LENGTH)
        val encrypted = stored.copyOfRange(1 + NONCE_LENGTH, stored.size)
        val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, encryptionKey(), GCMParameterSpec(GCM_TAG_BITS, nonce))
        cipher.updateAAD(aad)
        return cipher.doFinal(encrypted)
    }

    private fun encryptionKey(): SecretKey {
        val store = keyStore()
        val existing = store.getKey(keyAlias, null) as? SecretKey
        if (existing != null) return existing
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                keyAlias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    private fun addressAad(type: String, address: ProtocolAddress): ByteArray =
        "$type\\u0000${address.name}\\u0000${address.deviceId}".toByteArray(StandardCharsets.UTF_8)

    private fun numberedAad(table: String, id: Int, column: String): ByteArray =
        "$table\\u0000$id\\u0000$column".toByteArray(StandardCharsets.UTF_8)

    private fun values(vararg pairs: Pair<String, Any>): ContentValues = ContentValues().apply {
        pairs.forEach { (key, value) ->
            when (value) {
                is Int -> put(key, value)
                is Long -> put(key, value)
                is String -> put(key, value)
                is ByteArray -> put(key, value.copyOf())
                else -> error("Unsupported SQLite value for $key")
            }
        }
    }

    private class Database(context: Context, name: String) :
        SQLiteOpenHelper(context, name, null, DATABASE_VERSION) {
        override fun onConfigure(db: SQLiteDatabase) {
            db.setForeignKeyConstraintsEnabled(true)
            // Rollback journaling keeps encrypted state changes atomic across a process crash.
            db.disableWriteAheadLogging()
        }

        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE local_account (singleton INTEGER PRIMARY KEY CHECK(singleton = 1), registration_id INTEGER NOT NULL CHECK(registration_id > 0), payload BLOB NOT NULL)"
            )
            db.execSQL(
                "CREATE TABLE peer_identities (name TEXT NOT NULL, device_id INTEGER NOT NULL CHECK(device_id >= 1), payload BLOB NOT NULL, PRIMARY KEY(name, device_id))"
            )
            db.execSQL(
                "CREATE TABLE sessions (name TEXT NOT NULL, device_id INTEGER NOT NULL CHECK(device_id >= 1), payload BLOB NOT NULL, PRIMARY KEY(name, device_id))"
            )
            db.execSQL(
                "CREATE TABLE prekeys (id INTEGER PRIMARY KEY CHECK(id >= 0), payload BLOB NOT NULL)"
            )
            db.execSQL(
                "CREATE TABLE signed_prekeys (id INTEGER PRIMARY KEY CHECK(id >= 0), payload BLOB NOT NULL)"
            )
            db.execSQL(
                "CREATE TABLE kyber_prekeys (id INTEGER PRIMARY KEY CHECK(id >= 0), payload BLOB NOT NULL)"
            )
            db.execSQL(
                "CREATE TABLE kyber_prekey_uses (prekey_id INTEGER NOT NULL REFERENCES kyber_prekeys(id) ON DELETE CASCADE, base_key_hash BLOB NOT NULL, PRIMARY KEY(prekey_id, base_key_hash))"
            )
            createApplicationTables(db)
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            require(oldVersion == 1 && newVersion == 2) {
                "Unsupported protocol-state upgrade: $oldVersion to $newVersion"
            }
            createApplicationTables(db)
        }

        private fun createApplicationTables(db: SQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE app_secrets (name TEXT PRIMARY KEY NOT NULL, " +
                    "payload BLOB NOT NULL)"
            )
            db.execSQL(
                "CREATE TABLE message_records (sequence INTEGER PRIMARY KEY AUTOINCREMENT, " +
                    "id TEXT UNIQUE NOT NULL, session_id TEXT NOT NULL, " +
                    "sender_local INTEGER NOT NULL " +
                    "CHECK(sender_local IN (0,1)), body BLOB NOT NULL, wire BLOB NOT NULL, " +
                    "delivered INTEGER NOT NULL CHECK(delivered IN (0,1)), " +
                    "created_at INTEGER NOT NULL)"
            )
            db.execSQL(
                "CREATE INDEX message_records_session " +
                    "ON message_records(session_id, sequence)"
            )
        }
    }

    private companion object {
        const val DATABASE_VERSION = 2
        const val MAX_MESSAGE_BYTES = 262_144
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val CIPHER_TRANSFORMATION = "AES/GCM/NoPadding"
        const val CIPHERTEXT_VERSION: Byte = 1
        const val NONCE_LENGTH = 12
        const val GCM_TAG_BITS = 128
        const val GCM_TAG_BYTES = GCM_TAG_BITS / Byte.SIZE_BITS
        val LOCAL_ACCOUNT_AAD = "local_account\\u0000account".toByteArray(StandardCharsets.UTF_8)

        fun keyAliasFor(databaseName: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(databaseName.toByteArray(StandardCharsets.UTF_8))
                .joinToString(separator = "") { byte -> "%02x".format(byte) }
            return "io.sodyx.protocol.$digest"
        }
    }
}

class StoredSecureMessage(
    val id: String,
    val sessionId: String,
    val senderLocal: Boolean,
    val plaintext: ByteArray,
    val wireEnvelope: ByteArray,
    val delivered: Boolean,
    val createdAtEpochMillis: Long
) {
    override fun toString(): String =
        "StoredSecureMessage(senderLocal=$senderLocal, delivered=$delivered)"
}
