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
    val ttlSeconds: Int = 0,
    val expiresAtMs: Long? = null,
    val expired: Boolean = false,
    val edited: Boolean = false,
)

/**
 * Local message cache. Decrypted plaintext is stored **encrypted at rest**
 * with the KeyStore master key (column plain_enc) — the raw SQLite file
 * never contains readable chat content. Key-exchange and control envelopes
 * are kept as hidden rows so resync deduplication never reprocesses them.
 *
 * v2 adds: self-destruct timers (ttl_seconds / expires_at_ms / expired),
 * edited flag, and paginated history reads (no more loading + decrypting
 * the whole conversation on every refresh).
 */
class LocalDb(context: Context, private val secure: SecureStore) :
    SQLiteOpenHelper(context, "mamoot.db", null, 2) {

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
              deleted INTEGER NOT NULL DEFAULT 0,
              ttl_seconds INTEGER NOT NULL DEFAULT 0,
              expires_at_ms INTEGER,
              expired INTEGER NOT NULL DEFAULT 0,
              edited INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX idx_messages_conv ON messages(conv_id, created_at, local_id)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE messages ADD COLUMN ttl_seconds INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE messages ADD COLUMN expires_at_ms INTEGER")
            db.execSQL("ALTER TABLE messages ADD COLUMN expired INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE messages ADD COLUMN edited INTEGER NOT NULL DEFAULT 0")
        }
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
        ttlSeconds: Int = 0,
        expiresAtMs: Long? = null,
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
            put("ttl_seconds", ttlSeconds)
            if (expiresAtMs != null) put("expires_at_ms", expiresAtMs)
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

    @Synchronized
    fun updatePlainByServerId(serverId: String, plainText: String, ttlSeconds: Int, expiresAtMs: Long?) {
        val values = ContentValues().apply {
            put("plain_enc", secure.encrypt(plainText))
            put("ttl_seconds", ttlSeconds)
            if (expiresAtMs != null) put("expires_at_ms", expiresAtMs) else putNull("expires_at_ms")
        }
        writableDatabase.update("messages", values, "server_id = ?", arrayOf(serverId))
    }

    /** Plain-text (non-E2EE) edit sync: refresh body + plaintext, flag edited. */
    @Synchronized
    fun updateTextByServerId(serverId: String, body: String) {
        val values = ContentValues().apply {
            put("env_body", body)
            put("plain_enc", secure.encrypt(body))
            put("edited", 1)
        }
        writableDatabase.update("messages", values, "server_id = ?", arrayOf(serverId))
    }

    /** E2EE revise control: replace cached plaintext, keep original envelope. */
    @Synchronized
    fun applyReviseByServerId(serverId: String, plainText: String) {
        val values = ContentValues().apply {
            put("plain_enc", secure.encrypt(plainText))
            put("edited", 1)
        }
        writableDatabase.update("messages", values, "server_id = ?", arrayOf(serverId))
    }

    @Synchronized
    fun setExpiresAt(localId: Long, expiresAtMs: Long) {
        val values = ContentValues().apply { put("expires_at_ms", expiresAtMs) }
        writableDatabase.update("messages", values, "local_id = ?", arrayOf(localId.toString()))
    }

    @Synchronized
    fun deleteByLocalId(localId: Long) {
        writableDatabase.delete("messages", "local_id = ?", arrayOf(localId.toString()))
    }

    /**
     * Marks self-destructed messages as expired (wiping local plaintext)
     * and returns the affected rows so own messages can also be deleted
     * server-side (best effort).
     */
    @Synchronized
    fun purgeExpired(nowMs: Long): List<LocalMessage> {
        val doomed = mutableListOf<LocalMessage>()
        readableDatabase.query(
            "messages", COLUMNS,
            "expires_at_ms IS NOT NULL AND expires_at_ms <= ? AND deleted = 0",
            arrayOf(nowMs.toString()), null, null, null,
        ).use { cursor ->
            while (cursor.moveToNext()) doomed.add(readRow(cursor))
        }
        if (doomed.isNotEmpty()) {
            val values = ContentValues().apply {
                put("deleted", 1)
                put("expired", 1)
                put("plain_enc", null as String?)
                put("env_body", null as String?)
            }
            writableDatabase.update(
                "messages", values,
                "expires_at_ms IS NOT NULL AND expires_at_ms <= ? AND deleted = 0",
                arrayOf(nowMs.toString()),
            )
        }
        return doomed
    }

    fun hasServerId(serverId: String): Boolean = getByServerId(serverId) != null

    fun getByServerId(serverId: String): LocalMessage? = queryOne("server_id = ?", serverId)

    fun getByClientId(clientId: String): LocalMessage? = queryOne("client_id = ?", clientId)

    fun getByLocalId(localId: Long): LocalMessage? = queryOne("local_id = ?", localId.toString())

    private fun queryOne(selection: String, arg: String): LocalMessage? {
        readableDatabase.query(
            "messages", COLUMNS, selection, arrayOf(arg),
            null, null, null, "1",
        ).use { cursor -> return if (cursor.moveToFirst()) readRow(cursor) else null }
    }

    private val visibleWhere = "conv_id = ? AND status NOT IN (?, ?, ?)"
    private fun visibleArgs(conversationId: String) =
        arrayOf(conversationId, STATUS_KX, STATUS_KX_PENDING, STATUS_HIDDEN)

    fun countVisible(conversationId: String): Int {
        readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM messages WHERE $visibleWhere",
            visibleArgs(conversationId),
        ).use { cursor -> return if (cursor.moveToFirst()) cursor.getInt(0) else 0 }
    }

    fun listVisible(conversationId: String): List<LocalMessage> =
        queryList(visibleWhere, visibleArgs(conversationId), "created_at ASC, local_id ASC", null)

    fun listVisibleLatest(conversationId: String, limit: Int): List<LocalMessage> =
        queryList(visibleWhere, visibleArgs(conversationId), "created_at DESC, local_id DESC", limit.toString())
            .asReversed()

    fun listVisibleBefore(
        conversationId: String,
        beforeCreatedAt: String,
        beforeLocalId: Long,
        limit: Int,
    ): List<LocalMessage> {
        val where = "$visibleWhere AND (created_at < ? OR (created_at = ? AND local_id < ?))"
        val args = visibleArgs(conversationId) + arrayOf(beforeCreatedAt, beforeCreatedAt, beforeLocalId.toString())
        return queryList(where, args, "created_at DESC, local_id DESC", limit.toString()).asReversed()
    }

    /** Incoming encrypted rows whose plaintext is not cached yet (self-heal candidates). */
    fun listPlaceholders(conversationId: String): List<LocalMessage> =
        queryList(
            "conv_id = ? AND kind = 'e2ee' AND status IN (?, ?) AND deleted = 0 AND plain_enc IS NULL AND env_body IS NOT NULL",
            arrayOf(conversationId, STATUS_SENT, STATUS_HIDDEN),
            "created_at ASC, local_id ASC",
            "400",
        )

    /** Own timed messages still waiting for the peer's read receipt to start their countdown. */
    fun listAwaitingExpiry(conversationId: String, myUserId: String): List<LocalMessage> =
        queryList(
            "conv_id = ? AND sender_id = ? AND ttl_seconds > 0 AND expires_at_ms IS NULL AND deleted = 0",
            arrayOf(conversationId, myUserId),
            "created_at ASC, local_id ASC",
            "200",
        )

    private fun queryList(where: String, args: Array<String>, order: String, limit: String?): List<LocalMessage> {
        val out = mutableListOf<LocalMessage>()
        readableDatabase.query(
            "messages", COLUMNS, where, args, null, null, order, limit,
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
        val expiresIdx = col("expires_at_ms")
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
            ttlSeconds = cursor.getInt(col("ttl_seconds")),
            expiresAtMs = if (cursor.isNull(expiresIdx)) null else cursor.getLong(expiresIdx),
            expired = cursor.getInt(col("expired")) == 1,
            edited = cursor.getInt(col("edited")) == 1,
        )
    }

    companion object {
        const val STATUS_SENT = "sent"
        const val STATUS_SENDING = "sending"
        const val STATUS_FAILED = "failed"
        const val STATUS_KX = "kx"
        const val STATUS_KX_PENDING = "kx_pending"
        const val STATUS_HIDDEN = "hidden"

        private val COLUMNS = arrayOf(
            "local_id", "server_id", "client_id", "conv_id", "sender_id",
            "kind", "env_body", "plain_enc", "created_at", "status", "deleted",
            "ttl_seconds", "expires_at_ms", "expired", "edited",
        )
    }
}
