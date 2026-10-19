package ir.mamoot.app.storage

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class LocalMessage(
    val localId: Long,
    val serverId: String?,
    val clientId: String,
    val conversationId: String,
    val senderId: String,
    val kind: String,
    val envelopeBody: String?,
    val plainText: String?,
    val createdAt: String,
    val status: String,
    val deleted: Boolean,
)

/**
 * Local message cache. Decrypted plaintext is stored **encrypted at rest**
 * with the KeyStore master key (column plain_enc) — the raw SQLite file
 * never contains readable chat content. Key-exchange envelopes are kept as
 * hidden rows so resync deduplication never reprocesses them.
 */
class LocalDb(context: Context, private val secure: SecureStore) :
    SQLiteOpenHelper(context, "mamoot.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE messages (
              local_id INTEGER PRIMARY KEY AUTOINCREMENT,
              server_id TEXT UNIQUE,
              client_id TEXT UNIQUE NOT NULL,
              conv_id TEXT NOT NULL,
              sender_id TEXT NOT NULL,
              kind TEXT NOT NULL,
              env_body TEXT,
              plain_enc TEXT,
              created_at TEXT NOT NULL,
              status TEXT NOT NULL,
              deleted INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX idx_messages_conv ON messages(conv_id, created_at, local_id)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // v1 only.
    }

    @Synchronized
    fun insert(
        serverId: String?,
        clientId: String,
        conversationId: String,
        senderId: String,
        kind: String,
        envelopeBody: String?,
        plainText: String?,
        createdAt: String,
        status: String,
        deleted: Boolean,
    ): Long {
        val values = ContentValues().apply {
            put("server_id", serverId)
            put("client_id", clientId)
            put("conv_id", conversationId)
            put("sender_id", senderId)
            put("kind", kind)
            put("env_body", envelopeBody)
            put("plain_enc", plainText?.let { secure.encrypt(it) })
            put("created_at", createdAt)
            put("status", status)
            put("deleted", if (deleted) 1 else 0)
        }
        return writableDatabase.insertWithOnConflict(
            "messages", null, values, SQLiteDatabase.CONFLICT_IGNORE,
        )
    }

    @Synchronized
    fun markSentByClientId(clientId: String, serverId: String, createdAt: String) {
        val values = ContentValues().apply {
            put("server_id", serverId)
            put("created_at", createdAt)
            put("status", STATUS_SENT)
        }
        writableDatabase.update("messages", values, "client_id = ?", arrayOf(clientId))
    }

    @Synchronized
    fun markKxSentByClientId(clientId: String, serverId: String, createdAt: String) {
        val values = ContentValues().apply {
            put("server_id", serverId)
            put("created_at", createdAt)
            put("status", STATUS_KX)
        }
        writableDatabase.update("messages", values, "client_id = ?", arrayOf(clientId))
    }

    @Synchronized
    fun setStatusByLocalId(localId: Long, status: String) {
        val values = ContentValues().apply { put("status", status) }
        writableDatabase.update("messages", values, "local_id = ?", arrayOf(localId.toString()))
    }

    @Synchronized
    fun markDeletedByServerId(serverId: String) {
        val values = ContentValues().apply {
            put("deleted", 1)
            put("plain_enc", null as String?)
            put("env_body", null as String?)
        }
        writableDatabase.update("messages", values, "server_id = ?", arrayOf(serverId))
    }

    fun hasServerId(serverId: String): Boolean = getByServerId(serverId) != null

    fun getByServerId(serverId: String): LocalMessage? {
        readableDatabase.query(
            "messages", COLUMNS, "server_id = ?", arrayOf(serverId),
            null, null, null, "1",
        ).use { cursor -> return if (cursor.moveToFirst()) readRow(cursor) else null }
    }

    fun getByClientId(clientId: String): LocalMessage? {
        readableDatabase.query(
            "messages", COLUMNS, "client_id = ?", arrayOf(clientId),
            null, null, null, "1",
        ).use { cursor -> return if (cursor.moveToFirst()) readRow(cursor) else null }
    }

    fun getByLocalId(localId: Long): LocalMessage? {
        readableDatabase.query(
            "messages", COLUMNS, "local_id = ?", arrayOf(localId.toString()),
            null, null, null, "1",
        ).use { cursor -> return if (cursor.moveToFirst()) readRow(cursor) else null }
    }

    fun listVisible(conversationId: String): List<LocalMessage> {
        val out = mutableListOf<LocalMessage>()
        readableDatabase.query(
            "messages", COLUMNS,
            "conv_id = ? AND status NOT IN (?, ?)",
            arrayOf(conversationId, STATUS_KX, STATUS_KX_PENDING),
            null, null, "created_at ASC, local_id ASC",
        ).use { cursor ->
            while (cursor.moveToNext()) out.add(readRow(cursor))
        }
        return out
    }

    fun listPendingKx(conversationId: String): List<LocalMessage> {
        val out = mutableListOf<LocalMessage>()
        readableDatabase.query(
            "messages", COLUMNS,
            "conv_id = ? AND status = ?",
            arrayOf(conversationId, STATUS_KX_PENDING),
            null, null, "local_id ASC",
        ).use { cursor ->
            while (cursor.moveToNext()) out.add(readRow(cursor))
        }
        return out
    }

    fun lastServerCreatedAt(conversationId: String): String? {
        readableDatabase.rawQuery(
            "SELECT MAX(created_at) FROM messages WHERE conv_id = ? AND server_id IS NOT NULL",
            arrayOf(conversationId),
        ).use { cursor ->
            return if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getString(0) else null
        }
    }

    @Synchronized
    fun deleteAll() {
        writableDatabase.delete("messages", null, null)
    }

    private fun readRow(cursor: Cursor): LocalMessage {
        fun col(name: String): Int = cursor.getColumnIndexOrThrow(name)
        val plainEnc = cursor.getString(col("plain_enc"))
        return LocalMessage(
            localId = cursor.getLong(col("local_id")),
            serverId = cursor.getString(col("server_id")),
            clientId = cursor.getString(col("client_id")),
            conversationId = cursor.getString(col("conv_id")),
            senderId = cursor.getString(col("sender_id")),
            kind = cursor.getString(col("kind")),
            envelopeBody = cursor.getString(col("env_body")),
            plainText = secure.decryptOrNull(plainEnc),
            createdAt = cursor.getString(col("created_at")),
            status = cursor.getString(col("status")),
            deleted = cursor.getInt(col("deleted")) == 1,
        )
    }

    companion object {
        const val STATUS_SENT = "sent"
        const val STATUS_SENDING = "sending"
        const val STATUS_FAILED = "failed"
        const val STATUS_KX = "kx"
        const val STATUS_KX_PENDING = "kx_pending"

        private val COLUMNS = arrayOf(
            "local_id", "server_id", "client_id", "conv_id", "sender_id",
            "kind", "env_body", "plain_enc", "created_at", "status", "deleted",
        )
    }
}
