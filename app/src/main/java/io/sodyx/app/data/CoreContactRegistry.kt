package io.sodyx.app.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteOpenHelper
import java.util.UUID

internal enum class CoreContactState { Pairing, Ready, Closed }

internal data class CoreContact(val id: String, val alias: String, val state: CoreContactState)

/** Local display metadata only. Capabilities, keys and message content live in the encrypted store. */
internal class CoreContactRegistry(context: Context, name: String = "sodyx-contacts.db") :
    SQLiteOpenHelper(context.applicationContext, name, null, 1) {
    override fun onCreate(db: android.database.sqlite.SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE contacts (id TEXT PRIMARY KEY NOT NULL, alias TEXT NOT NULL, " +
                "state TEXT NOT NULL CHECK(state IN ('Pairing','Ready','Closed')))"
        )
    }

    override fun onUpgrade(
        db: android.database.sqlite.SQLiteDatabase,
        oldVersion: Int,
        newVersion: Int
    ) {
        error("Unsupported contact registry upgrade")
    }

    fun create(alias: String): CoreContact {
        require(alias.isNotBlank() && alias.length <= 64 && alias.none(Char::isISOControl))
        val contact = CoreContact(UUID.randomUUID().toString(), alias, CoreContactState.Pairing)
        writableDatabase.insertOrThrow(
            "contacts",
            null,
            ContentValues().apply {
                put("id", contact.id)
                put("alias", contact.alias)
                put("state", contact.state.name)
            }
        )
        return contact
    }

    fun list(): List<CoreContact> = readableDatabase.rawQuery(
        "SELECT id, alias, state FROM contacts ORDER BY rowid DESC",
        null
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) {
                add(
                    CoreContact(
                        cursor.getString(0),
                        cursor.getString(1),
                        CoreContactState.valueOf(cursor.getString(2))
                    )
                )
            }
        }
    }

    fun get(id: String): CoreContact = list().firstOrNull { it.id == id }
        ?: error("Connection not found")

    fun setState(id: String, state: CoreContactState) {
        check(
            writableDatabase.update(
                "contacts",
                ContentValues().apply {
                    put("state", state.name)
                    if (state == CoreContactState.Closed) put("alias", "")
                },
                "id = ?",
                arrayOf(id)
            ) == 1
        )
    }

    fun remove(id: String) {
        writableDatabase.delete("contacts", "id = ?", arrayOf(id))
    }
}
