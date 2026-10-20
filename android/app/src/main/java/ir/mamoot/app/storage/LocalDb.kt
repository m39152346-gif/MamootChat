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
    val replyToServerId: String? = null,
    val forwarded: Boolean = false,
    /** JSON object {userId: emoji}; stored encrypted at rest like plaintext. */
    val reactionsJson: String? = null,
    val retryCount: Int = 0,
    val nextRetryMs: Long? = null,
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
 *
 * v3 adds: reply threading (reply_to_server_id), forwarded flag, reaction
 * cache (reactions_enc) and the outgoing retry schedule
 * (retry_count / next_retry_ms) for the offline outbox. WAL is enabled for
 * crash resilience and read/write concurrency.
 */
class LocalDb(context: Context, private val secure: SecureStore) :
    SQLiteOpenHelper(context, "mamoot.db", null, 3) {

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.enableWriteAheadLogging()
    }

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
              edited INTEGER NOT NULL DEFAULT 0,
              reply_to_server_id TEXT,
              forwarded INTEGER NOT NULL DEFAULT 0,
              reactions_enc TEXT,
              retry_count INTEGER NOT NULL DEFAULT 0,
              next_retry_ms INTEGER
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
        if (oldVersion < 3) {
            db.execSQL("ALTER TABLE messages ADD COLUMN reply_to_server_id TEXT")
            db.execSQL("ALTER TABLE messages ADD COLUMN forwarded INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE messages ADD COLUMN reactions_enc TEXT")
            db.execSQL("ALTER TABLE messages ADD COLUMN retry_count INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE messages ADD COLUMN next_retry_ms INTEGER")
        }
    }

    override fun onDowngrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Keep the data: extra columns are harmless to older code. Throwing
        // (the default) would crash the app after a downgrade install.
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
        replyToServerId: String? = null,
        forwarded: Boolean = false,
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
            put("reply_to_server_id", replyToServerId)
            put("forwarded", if (forwarded) 1 else 0)
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
            put("retry_count", 0)
            putNull("next_retry_ms")
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
    fun updatePlainByServerId(
        serverId: String,
        plainText: String,
        ttlSeconds: Int,
        expiresAtMs: Long?,
        replyToServerId: String? = null,
        forwarded: Boolean = false,
    ) {
        val values = ContentValues().apply {
            put("plain_enc", secure.encrypt(plainText))
            put("ttl_seconds", ttlSeconds)
            if (expiresAtMs != null) put("expires_at_ms", expiresAtMs) else putNull("expires_at_ms")
            put("reply_to_server_id", replyToServerId)
            put("forwarded", if (forwarded) 1 else 0)
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
    fun setReactionsJsonByServerId(serverId: String, reactionsJson: String?) {
        val values = ContentValues().apply {
            if (reactionsJson == null) putNull("reactions_enc") else put("reactions_enc", secure.encrypt(reactionsJson))
        }
        writableDatabase.update("messages", values, "server_id = ?", arrayOf(serverId))
    }

    @Synchronized
    fun scheduleRetry(localId: Long, retryCount: Int, nextRetryMs: Long?) {
        val values = ContentValues().apply {
            put("status", STATUS_FAILED)
            put("retry_count", retryCount)
            if (nextRetryMs != null) put("next_retry_ms", nextRetryMs) else putNull("next_retry_ms")
        }
        writableDatabase.update("messages", values, "local_id = ?", arrayOf(localId.toString()))
    }

    /**
     * Outgoing rows waiting to (re)post: never reached the server and their
     * backoff window has elapsed. Includes rows stuck in "sending" by a
     * process death.
     */
    fun listRetryableOutgoing(conversationId: String?, nowMs: Long, limit: Int): List<LocalMessage> {
        var where = "server_id IS NULL AND env_body IS NOT NULL AND status IN (?, ?) " +
            "AND (next_retry_ms IS NULL OR next_retry_ms <= ?)"
        val args = mutableListOf(STATUS_FAILED, STATUS_SENDING, nowMs.toString())
        if (conversationId != null) {
            where += " AND conv_id = ?"
            args.add(conversationId)
        }
        return queryList(where, args.toTypedArray(), "created_at ASC, local_id ASC", limit.toString())
    }

    /** Every stored row (incl. hidden kx/control) — encrypted-backup source. */
    fun listAllForBackup(): List<LocalMessage> =
        queryList("1 = 1", emptyArray(), "conv_id ASC, created_at ASC, local_id ASC", null)

    /**
     * Imports one backup row. Returns true when a new row was inserted;
     * when the message already exists locally only missing fields are filled.
     */
    @Synchronized
    fun insertBackup(m: LocalMessage): Boolean {
        val existing = (m.serverId?.let { getByServerId(it) }) ?: getByClientId(m.clientId)
        if (existing != null) {
            if (existing.plainText == null && m.plainText != null && existing.serverId != null) {
                updatePlainByServerId(
                    existing.serverId, m.plainText, m.ttlSeconds, m.expiresAtMs,
                    m.replyToServerId, m.forwarded,
                )
            }
            if (existing.reactionsJson == null && m.reactionsJson != null && existing.serverId != null) {
                setReactionsJsonByServerId(existing.serverId, m.reactionsJson)
            }
            return false
        }
        val values = ContentValues().apply {
            put("server_id", m.serverId)
            put("client_id", m.clientId)
            put("conv_id", m.conversationId)
            put("sender_id", m.senderId)
            put("kind", m.kind)
            put("env_body", m.envelopeBody)
            put("plain_enc", m.plainText?.let { secure.encrypt(it) })
            put("created_at", m.createdAt)
            put("status", m.status)
            put("deleted", if (m.deleted) 1 else 0)
            put("ttl_seconds", m.ttlSeconds)
            if (m.expiresAtMs != null) put("expires_at_ms", m.expiresAtMs)
            put("expired", if (m.expired) 1 else 0)
            put("edited", if (m.edited) 1 else 0)
            put("reply_to_server_id", m.replyToServerId)
            put("forwarded", if (m.forwarded) 1 else 0)
            put("reactions_enc", m.reactionsJson?.let { secure.encrypt(it) })
            put("retry_count", m.retryCount)
            if (m.nextRetryMs != null) put("next_retry_ms", m.nextRetryMs)
        }
        return writableDatabase.insertWithOnConflict(
            "messages", null, values, SQLiteDatabase.CONFLICT_IGNORE,
        ) > 0
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
            replyToServerId = cursor.getString(col("reply_to_server_id")),
            forwarded = cursor.getInt(col("forwarded")) == 1,
            reactionsJson = secure.decryptOrNull(cursor.getString(col("reactions_enc"))),
            retryCount = cursor.getInt(col("retry_count")),
            nextRetryMs = cursor.getLong(col("next_retry_ms")).takeIf { !cursor.isNull(col("next_retry_ms")) },
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
            "reply_to_server_id", "forwarded", "reactions_enc",
            "retry_count", "next_retry_ms",
        )
    }
}
