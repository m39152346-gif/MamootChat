package ir.mamoot.app.sync

import ir.mamoot.app.Threads
import ir.mamoot.app.crypto.BackupException
import ir.mamoot.app.crypto.Crypto
import ir.mamoot.app.crypto.DecryptException
import ir.mamoot.app.crypto.SecureBackup
import ir.mamoot.app.crypto.IdentityManager
import ir.mamoot.app.crypto.SecretSession
import ir.mamoot.app.net.ApiException
import ir.mamoot.app.net.Conversation
import ir.mamoot.app.net.MemberInfo
import ir.mamoot.app.net.MessageRow
import ir.mamoot.app.net.Profile
import ir.mamoot.app.net.SupabaseApi
import ir.mamoot.app.storage.LocalDb
import ir.mamoot.app.storage.LocalMessage
import ir.mamoot.app.storage.SecureStore
import ir.mamoot.app.util.UiUtil
import java.io.IOException
import java.security.PublicKey
import java.time.Instant
import java.util.UUID
import org.json.JSONObject

enum class SessionUiState { READY, NO_SESSION, WAITING_PEER, PEER_NO_KEY, PEER_KEY_CHANGED, GROUP_PLAIN }

data class UiMessage(
    val localId: Long,
    val serverId: String?,
    val senderId: String,
    val mine: Boolean,
    val text: String?,
    val createdAt: String,
    val status: String,
    val deleted: Boolean,
    val encrypted: Boolean,
    val ttlSeconds: Int = 0,
    val expiresAtMs: Long? = null,
    val expired: Boolean = false,
    val edited: Boolean = false,
    val system: Boolean = false,
    val replyToServerId: String? = null,
    val replySnippet: String? = null,
    val forwarded: Boolean = false,
    val reactions: Map<String, String> = emptyMap(),
    val myReaction: String? = null,
)

data class UiConversation(
    val conversation: Conversation,
    val title: String,
    val peer: MemberInfo?,
    val preview: String,
)

data class SyncResult(
    val messages: List<UiMessage>,
    val state: SessionUiState,
    val peer: MemberInfo?,
    val notice: String?,
    val hasMore: Boolean = false,
    val peerTyping: Boolean = false,
    val peerLastSeenAt: String? = null,
    val peerLastSeenPublic: Boolean = true,
    val memberLastReadAt: Map<String, String> = emptyMap(),
)

data class PageResult(
    val messages: List<UiMessage>,
    val hasMore: Boolean,
)

/**
 * Sync + secret-chat engine.
 *
 * DM model (Telegram-like choice): every DM is a normal chat by default.
 * Activating the secret layer runs the signed ECDH exchange; from then on
 * new messages are end-to-end encrypted until either side ends it (control
 * envelope), after which the conversation falls back to plain messages.
 * History can therefore mix plain and 🔒 messages; each encrypted bubble
 * is marked in the UI.
 */
class ChatRepository(
    private val api: SupabaseApi,
    private val db: LocalDb,
    private val secure: SecureStore,
    private val identity: IdentityManager,
) {
    private val sessionLock = Any()
    private val sessionCache = mutableMapOf<String, SecretSession>()
    private val profileCache = mutableMapOf<String, Pair<Profile, Long>>()

    @Volatile
    private var lastPresenceTouchMs = 0L

    private val myId: String
        get() = api.myUserId ?: throw ApiException("وارد نشده‌اید")

    // ------------------------------------------------------------ public API
    fun ensureIdentityUploaded(callback: (Result<Profile>) -> Unit) {
        ioCall(callback) {
            val me = api.fetchProfile(myId) ?: throw ApiException("پروفایل یافت نشد")
            val myKey = identity.publicKeyB64()
            if (me.e2eIdentityKey != myKey) {
                api.updateIdentityKey(myId, myKey)
                me.copy(e2eIdentityKey = myKey)
            } else {
                me
            }
        }
    }

    fun conversations(callback: (Result<List<UiConversation>>) -> Unit) {
        ioCall(callback) {
            api.listConversations().map { conv ->
                val peer = peerOf(conv)
                UiConversation(conv, titleOf(conv, peer), peer, previewOf(conv))
            }
        }
    }

    fun localMessages(conversationId: String, callback: (Result<PageResult>) -> Unit) {
        ioCall(callback) {
            purgeExpired()
            latestPage(conversationId)
        }
    }

    fun olderMessages(
        conversationId: String,
        beforeCreatedAt: String,
        beforeLocalId: Long,
        callback: (Result<PageResult>) -> Unit,
    ) {
        ioCall(callback) {
            val rows = db.listVisibleBefore(conversationId, beforeCreatedAt, beforeLocalId, PAGE_SIZE)
            val hasMore = if (rows.isEmpty()) {
                false
            } else {
                db.listVisibleBefore(conversationId, rows.first().createdAt, rows.first().localId, 1).isNotEmpty()
            }
            PageResult(rows.map(::toUi), hasMore)
        }
    }

    fun allLocalMessages(conversationId: String, callback: (Result<List<UiMessage>>) -> Unit) {
        ioCall(callback) { db.listVisible(conversationId).map(::toUi) }
    }

    fun searchMessages(conversationId: String, query: String, callback: (Result<List<UiMessage>>) -> Unit) {
        ioCall(callback) {
            val q = query.trim()
            if (q.isEmpty()) {
                emptyList()
            } else {
                // Bound the scan (plaintext is encrypted at rest, so search
                // must decrypt rows; 1000 recent messages is the budget).
                db.listVisibleLatest(conversationId, 1000)
                    .filter { !it.deleted && it.plainText?.contains(q, ignoreCase = true) == true }
                    .map(::toUi)
            }
        }
    }

    fun syncChat(conv: Conversation, markReadWanted: Boolean, callback: (Result<SyncResult>) -> Unit) {
        ioCall(callback) { syncInternal(conv, markReadWanted) }
    }

    fun startSecretChat(conv: Conversation, callback: (Result<SyncResult>) -> Unit) {
        ioCall(callback) {
            val peer = peerOf(conv) ?: throw ApiException("مخاطب یافت نشد")
            val peerProfile = peerProfileCached(peer.id, force = true)
            val peerKeyB64 = peerProfile?.e2eIdentityKey ?: peer.e2eIdentityKey
            val blocked = evaluatePeerKey(peer.id, peerKeyB64)
            if (blocked == SessionUiState.PEER_NO_KEY) {
                throw ApiException("مخاطب هنوز اپ مموت را فعال نکرده است؛ چت امن وقتی او اپ را باز کند ممکن می‌شود.")
            }
            if (blocked == SessionUiState.PEER_KEY_CHANGED) {
                throw ApiException("کلید امنیتی مخاطب عوض شده است. از بخش امنیت، نشست را بازنشانی کنید.")
            }
            val session = loadSession(conv.id, peer.id)
            val envelope = synchronized(sessionLock) {
                val env = session.startExchange(identity.privateKey(), System.currentTimeMillis())
                saveSession(session)
                env
            }
            queueHiddenEnvelope(conv.id, envelope, UUID.randomUUID().toString())
            syncInternal(conv, false)
        }
    }

    fun endSecretChat(conv: Conversation, callback: (Result<SyncResult>) -> Unit) {
        ioCall(callback) {
            val peer = peerOf(conv) ?: throw ApiException("مخاطب یافت نشد")
            val session = loadSession(conv.id, peer.id)
            val clientId = UUID.randomUUID().toString()
            val endEnvelope = synchronized(sessionLock) {
                if (session.hasSession()) {
                    val env = session.encryptControlEnd()
                    saveSession(session)
                    env
                } else {
                    null
                }
            }
            if (endEnvelope != null) queueHiddenEnvelope(conv.id, endEnvelope, clientId)
            synchronized(sessionLock) {
                session.reset()
                saveSession(session)
            }
            insertSystemNotice(
                conv.id, "sys:end:mine:$clientId",
                "🔓 شما چت امن را پایان دادید؛ پیام‌های تازه عادی ارسال می‌شوند.",
            )
            syncInternal(conv, false)
        }
    }

    fun resetSecretChat(conv: Conversation, callback: (Result<SyncResult>) -> Unit) {
        ioCall(callback) {
            val peer = peerOf(conv) ?: throw ApiException("مخاطب یافت نشد")
            synchronized(sessionLock) {
                sessionCache.remove(conv.id)
                secure.removeSecret(sessionKey(conv.id))
            }
            identity.clearPeerPin(peer.id)
            val peerProfile = peerProfileCached(peer.id, force = true)
            val peerKeyB64 = peerProfile?.e2eIdentityKey ?: peer.e2eIdentityKey
            if (peerKeyB64 != null) identity.pinPeer(peer.id, peerKeyB64)
            val session = loadSession(conv.id, peer.id)
            if (peerKeyB64 != null) {
                val envelope = synchronized(sessionLock) {
                    val env = session.startExchange(identity.privateKey(), System.currentTimeMillis())
                    saveSession(session)
                    env
                }
                queueHiddenEnvelope(conv.id, envelope, UUID.randomUUID().toString())
            }
            syncInternal(conv, false)
        }
    }

    fun sendText(
        conv: Conversation,
        rawText: String,
        ttlSeconds: Int,
        replyTo: UiMessage? = null,
        forwarded: Boolean = false,
        callback: (Result<List<UiMessage>>) -> Unit,
    ) {
        ioCall(callback) { sendTextSync(conv, rawText, ttlSeconds, replyTo, forwarded) }
    }

    private fun sendTextSync(
        conv: Conversation,
        rawText: String,
        ttlSeconds: Int,
        replyTo: UiMessage?,
        forwarded: Boolean,
    ): List<UiMessage> {
            val text = rawText.trim()
            if (text.isEmpty()) throw ApiException("پیام خالی است")
            val replyToServerId = replyTo?.serverId
            if (conv.kind == "group") {
                if (text.length > MAX_PLAIN_CHARS) throw ApiException("پیام بیش از حد طولانی است (حداکثر $MAX_PLAIN_CHARS نویسه).")
                sendPlain(conv.id, text, replyToServerId, forwarded)
            } else {
                val peer = peerOf(conv) ?: throw ApiException("مخاطب یافت نشد")
                val session = loadSession(conv.id, peer.id)
                val secretActive = synchronized(sessionLock) { session.hasSession() }
                if (!secretActive) {
                    // Normal (non-secret) DM message.
                    if (text.length > MAX_PLAIN_CHARS) throw ApiException("پیام بیش از حد طولانی است (حداکثر $MAX_PLAIN_CHARS نویسه).")
                    sendPlain(conv.id, text, replyToServerId, forwarded)
                } else {
                    if (text.length > SecretSession.MAX_PLAINTEXT_CHARS) {
                        throw ApiException("پیام بیش از حد طولانی است (حداکثر ${SecretSession.MAX_PLAINTEXT_CHARS} نویسه در چت امن).")
                    }
                    var rekeyEnvelope: String? = null
                    val envelope = synchronized(sessionLock) {
                        if (session.needsRekey(System.currentTimeMillis())) {
                            rekeyEnvelope = session.startExchange(identity.privateKey(), System.currentTimeMillis())
                        }
                        val env = session.encrypt(text, ttlSeconds, replyToServerId, forwarded)
                        saveSession(session)
                        env
                    }
                    rekeyEnvelope?.let { queueHiddenEnvelope(conv.id, it, UUID.randomUUID().toString()) }
                    val clientId = UUID.randomUUID().toString()
                    val localId = db.insert(
                        serverId = null, clientId = clientId, conversationId = conv.id,
                        senderId = myId, kind = "e2ee", envelopeBody = envelope,
                        plainText = text, createdAt = Instant.now().toString(),
                        status = LocalDb.STATUS_SENDING, deleted = false,
                        ttlSeconds = ttlSeconds,
                        replyToServerId = replyToServerId, forwarded = forwarded,
                    )
                    db.getByLocalId(localId)?.let { postVisible(it) }
                }
            }
            return db.listVisibleLatest(conv.id, PAGE_SIZE).map(::toUi)
    }

    fun forwardMessage(source: UiMessage, target: Conversation, callback: (Result<Unit>) -> Unit) {
        ioCall(callback) {
            val text = source.text?.takeIf { it.isNotEmpty() }
                ?: throw ApiException("متن پیام در دسترس نیست")
            if (source.encrypted) {
                if (target.kind == "group") {
                    throw ApiException("بازارسال پیام امن به گروه ممکن نیست.")
                }
                val peer = peerOf(target) ?: throw ApiException("مخاطب مقصد یافت نشد")
                val session = loadSession(target.id, peer.id)
                val active = synchronized(sessionLock) { session.hasSession() }
                if (!active) {
                    throw ApiException("برای بازارسال پیام امن، در گفتگوی مقصد باید چت امن فعال باشد.")
                }
            }
            sendTextSync(target, text, 0, null, forwarded = true)
            Unit
        }
    }

    fun setReaction(conv: Conversation, msg: UiMessage, emoji: String?, callback: (Result<List<UiMessage>>) -> Unit) {
        ioCall(callback) {
            val serverId = msg.serverId ?: throw ApiException("پیام هنوز ارسال نشده است")
            if (msg.encrypted) {
                // Secret-chat reactions stay inside the E2EE layer (control
                // envelope) so the server learns no reaction metadata.
                val peer = peerOf(conv) ?: throw ApiException("مخاطب یافت نشد")
                val session = loadSession(conv.id, peer.id)
                val envelope = synchronized(sessionLock) {
                    if (!session.hasSession()) throw ApiException("نشست امن فعال نیست.")
                    val env = session.encryptReaction(serverId, emoji ?: "")
                    saveSession(session)
                    env
                }
                if (!queueHiddenEnvelope(conv.id, envelope, UUID.randomUUID().toString())) {
                    throw ApiException("ارسال واکنش ناموفق بود؛ اتصال را بررسی کنید.")
                }
            } else {
                if (emoji == null) api.deleteReaction(serverId) else api.upsertReaction(serverId, emoji)
            }
            applyReactionLocal(conv.id, serverId, myId, emoji ?: "")
            db.listVisibleLatest(conv.id, PAGE_SIZE).map(::toUi)
        }
    }

    fun editMessage(conv: Conversation, msg: UiMessage, newTextRaw: String, callback: (Result<List<UiMessage>>) -> Unit) {
        ioCall(callback) {
            val newText = newTextRaw.trim()
            if (newText.isEmpty()) throw ApiException("متن پیام خالی است")
            val serverId = msg.serverId ?: throw ApiException("پیام هنوز ارسال نشده است")
            if (msg.encrypted) {
                if (newText.length > SecretSession.MAX_PLAINTEXT_CHARS) {
                    throw ApiException("پیام بیش از حد طولانی است.")
                }
                val peer = peerOf(conv) ?: throw ApiException("مخاطب یافت نشد")
                val session = loadSession(conv.id, peer.id)
                val envelope = synchronized(sessionLock) {
                    if (!session.hasSession()) throw ApiException("نشست امن فعال نیست؛ ویرایش پیام امن ممکن نیست.")
                    val env = session.encryptRevise(serverId, newText)
                    saveSession(session)
                    env
                }
                if (!queueHiddenEnvelope(conv.id, envelope, UUID.randomUUID().toString())) {
                    throw ApiException("ارسال ویرایش ناموفق بود؛ اتصال را بررسی کنید.")
                }
                db.applyReviseByServerId(serverId, newText, myId, conv.id)
            } else {
                if (newText.length > MAX_PLAIN_CHARS) throw ApiException("پیام بیش از حد طولانی است.")
                api.updateMessageBody(serverId, newText)
                db.updateTextByServerId(serverId, newText)
            }
            db.listVisibleLatest(conv.id, PAGE_SIZE).map(::toUi)
        }
    }

    fun retryMessage(localId: Long, callback: (Result<List<UiMessage>>) -> Unit) {
        ioCall(callback) {
            val local = db.getByLocalId(localId) ?: throw ApiException("پیام یافت نشد")
            if (local.envelopeBody == null) throw ApiException("متن پیام در دسترس نیست")
            db.setStatusByLocalId(localId, LocalDb.STATUS_SENDING)
            postVisible(local.copy(retryCount = 0))
            db.listVisibleLatest(local.conversationId, PAGE_SIZE).map(::toUi)
        }
    }

    fun deleteMessage(msg: UiMessage, callback: (Result<Unit>) -> Unit) {
        ioCall(callback) {
            val serverId = msg.serverId
            if (serverId == null) {
                db.deleteByLocalId(msg.localId) // never reached the server
            } else {
                api.deleteMessage(serverId)
                db.markDeletedByServerId(serverId)
            }
            Unit
        }
    }

    fun safetyCode(conv: Conversation): String? {
        val peer = peerOf(conv) ?: return null
        val session = loadSession(conv.id, peer.id)
        val fingerprint = session.currentFingerprint() ?: return null
        val peerKeyB64 = identity.peerPin(peer.id) ?: peer.e2eIdentityKey ?: return null
        return Crypto.safetyCode(identity.publicKey().encoded, Crypto.unb64(peerKeyB64), fingerprint)
    }

    /**
     * Stable 60-digit safety number for this peer (Signal-style). CPU-bound
     * (5200x2 SHA-512); call from a background thread.
     */
    fun safetyNumber(conv: Conversation): String? {
        val peer = peerOf(conv) ?: return null
        val peerKeyB64 = identity.peerPin(peer.id) ?: peer.e2eIdentityKey ?: return null
        return try {
            Crypto.safetyNumber(myId, identity.publicKey().encoded, peer.id, Crypto.unb64(peerKeyB64))
        } catch (e: Exception) {
            null
        }
    }

    fun createEncryptedBackup(passphrase: String, callback: (Result<String>) -> Unit) {
        ioCall(callback) {
            if (passphrase.length < 8) {
                throw ApiException("گذرواژه پشتیبان باید حداقل ۸ نویسه باشد.")
            }
            SecureBackup.encrypt(buildBackupPayload(), passphrase)
        }
    }

    fun restoreEncryptedBackup(blob: String, passphrase: String, callback: (Result<Int>) -> Unit) {
        ioCall(callback) {
            val payload = try {
                SecureBackup.decrypt(blob, passphrase)
            } catch (e: BackupException) {
                throw ApiException("گذرواژه پشتیبان اشتباه است یا فایل پشتیبان خراب شده است.")
            }
            applyBackupPayload(payload)
        }
    }

    /** Human-readable data export (account info + readable local history). */
    fun exportMyData(callback: (Result<String>) -> Unit) {
        ioCall(callback) {
            val me = api.fetchProfile(myId)
            val sb = StringBuilder()
            sb.append("خروجی داده‌های مموت\n")
            sb.append("تاریخ: ").append(Instant.now()).append("\n")
            sb.append("شناسه کاربر: ").append(myId).append("\n")
            sb.append("نام کاربری: ").append(me?.username ?: "").append("\n")
            sb.append("نام نمایشی: ").append(me?.displayName ?: "").append("\n\n")
            val visible = db.listAllForBackup().filter {
                it.status != LocalDb.STATUS_KX && it.status != LocalDb.STATUS_KX_PENDING &&
                    it.status != LocalDb.STATUS_HIDDEN && it.plainText != null && !it.deleted
            }
            for ((convId, rows) in visible.groupBy { it.conversationId }) {
                sb.append("== گفتگو ").append(convId).append(" ==\n")
                for (row in rows) {
                    val who = when {
                        row.senderId.isEmpty() -> "سیستم"
                        row.senderId == myId -> "من"
                        else -> row.senderId
                    }
                    sb.append("[").append(row.createdAt).append("] ").append(who).append(": ")
                        .append(row.plainText).append("\n")
                }
                sb.append("\n")
            }
            sb.toString()
        }
    }

    /** Ends every active secret session (best-effort control notice to peers). */
    fun revokeAllSecretSessions(callback: (Result<Int>) -> Unit) {
        ioCall(callback) {
            var count = 0
            for (key in secure.keysWithPrefixes(listOf("session:"))) {
                val blob = secure.getSecret(key) ?: continue
                val session = try {
                    SecretSession.deserialize(blob)
                } catch (e: Exception) {
                    continue
                }
                val envelope = synchronized(sessionLock) {
                    if (session.hasSession()) {
                        val env = session.encryptControlEnd()
                        saveSession(session)
                        env
                    } else {
                        null
                    }
                }
                if (envelope != null) {
                    queueHiddenEnvelope(session.conversationId, envelope, UUID.randomUUID().toString())
                    count++
                }
                synchronized(sessionLock) {
                    session.reset()
                    saveSession(session)
                    sessionCache.remove(session.conversationId)
                }
            }
            count
        }
    }

    /**
     * Full identity rotation: new long-term keypair, all sessions and peer
     * pins dropped, new public key published. Existing read history stays
     * readable locally; peers will see a key-change warning.
     */
    fun rotateIdentityKey(callback: (Result<Unit>) -> Unit) {
        ioCall(callback) {
            synchronized(sessionLock) {
                sessionCache.clear()
            }
            secure.removeWithPrefixes(listOf("session:", "pin:", "kxaccept:"))
            identity.rotateIdentity()
            val myKey = identity.publicKeyB64()
            api.updateIdentityKey(myId, myKey)
            Unit
        }
    }

    private fun b64Field(value: String?): String =
        if (value == null) "-" else Crypto.b64(value.toByteArray(Charsets.UTF_8))

    private fun unb64Field(value: String): String? =
        if (value == "-") null else String(Crypto.unb64(value), Charsets.UTF_8)

    private fun buildBackupPayload(): String {
        val sb = StringBuilder()
        sb.append("MAMOOT-DATA-V1\n")
        sb.append("user=").append(myId).append("\n")
        sb.append("created=").append(System.currentTimeMillis()).append("\n")
        for (key in secure.keysWithPrefixes(BACKUP_SECURE_PREFIXES)) {
            val value = secure.getSecret(key) ?: continue
            sb.append("S\t").append(b64Field(key)).append("\t").append(b64Field(value)).append("\n")
        }
        for (m in db.listAllForBackup()) {
            val fields = listOf(
                m.serverId, m.clientId, m.conversationId, m.senderId, m.kind,
                m.envelopeBody, m.plainText, m.createdAt, m.status,
                if (m.deleted) "1" else "0", m.ttlSeconds.toString(),
                m.expiresAtMs?.toString(), if (m.expired) "1" else "0",
                if (m.edited) "1" else "0", m.replyToServerId,
                if (m.forwarded) "1" else "0", m.reactionsJson,
                m.retryCount.toString(), m.nextRetryMs?.toString(),
            )
            sb.append("M")
            for (field in fields) sb.append("\t").append(b64Field(field))
            sb.append("\n")
        }
        return sb.toString()
    }

    private fun applyBackupPayload(payload: String): Int {
        val lines = payload.split("\n")
        if (lines.isEmpty() || lines[0].trim() != "MAMOOT-DATA-V1") {
            throw ApiException("قالب پشتیبان پشتیبانی نمی‌شود.")
        }
        val owner = lines.firstOrNull { it.startsWith("user=") }?.removePrefix("user=")?.trim()
        if (owner != myId) {
            throw ApiException("این پشتیبان مربوط به حساب دیگری است و روی این حساب بازیابی نمی‌شود.")
        }
        var imported = 0
        var identityTouched = false
        for (line in lines) {
            when {
                line.startsWith("S\t") -> {
                    val parts = line.split("\t")
                    if (parts.size == 3) {
                        val key = unb64Field(parts[1])
                        val value = unb64Field(parts[2])
                        if (key != null && value != null &&
                            BACKUP_SECURE_PREFIXES.any { key.startsWith(it) }
                        ) {
                            secure.putSecretCommit(key, value)
                            if (key.startsWith("identity_")) identityTouched = true
                        }
                    }
                }
                line.startsWith("M\t") -> {
                    val parts = line.split("\t")
                    if (parts.size == 20) {
                        val f = parts.drop(1).map { unb64Field(it) }
                        val message = LocalMessage(
                            localId = 0,
                            serverId = f[0],
                            clientId = f[1] ?: continue,
                            conversationId = f[2] ?: continue,
                            senderId = f[3] ?: "",
                            kind = f[4] ?: "text",
                            envelopeBody = f[5],
                            plainText = f[6],
                            createdAt = f[7] ?: Instant.now().toString(),
                            status = f[8] ?: LocalDb.STATUS_SENT,
                            deleted = f[9] == "1",
                            ttlSeconds = f[10]?.toIntOrNull() ?: 0,
                            expiresAtMs = f[11]?.toLongOrNull(),
                            expired = f[12] == "1",
                            edited = f[13] == "1",
                            replyToServerId = f[14],
                            forwarded = f[15] == "1",
                            reactionsJson = f[16],
                            retryCount = f[17]?.toIntOrNull() ?: 0,
                            nextRetryMs = f[18]?.toLongOrNull(),
                        )
                        if (db.insertBackup(message)) imported++
                    }
                }
            }
        }
        if (identityTouched) identity.reloadIdentity()
        synchronized(sessionLock) { sessionCache.clear() }
        return imported
    }

    fun getTtlChoice(conversationId: String): Int =
        secure.getSecret("ttl:$conversationId")?.toIntOrNull() ?: 0

    fun setTtlChoice(conversationId: String, ttlSeconds: Int) {
        secure.putSecret("ttl:$conversationId", ttlSeconds.toString())
    }

    fun wipeLocal() {
        synchronized(sessionLock) { sessionCache.clear() }
        profileCache.clear()
        db.deleteAll()
        identity.wipe()
        secure.clearAll()
    }

    // ------------------------------------------------------------ sync engine
    private fun syncInternal(conv: Conversation, markReadWanted: Boolean): SyncResult {
        purgeExpired()
        touchPresenceThrottled()
        flushOutbox(conv.id)
        val isGroup = conv.kind == "group"
        var peer = peerOf(conv)
        var peerPublicKey: PublicKey? = null
        var peerKeyBlocked: SessionUiState? = null
        var session: SecretSession? = null

        if (!isGroup && peer != null) {
            val freshProfile = peerProfileCached(peer.id, force = false)
            if (freshProfile != null) {
                peer = peer.copy(
                    username = freshProfile.username,
                    displayName = freshProfile.displayName,
                    e2eIdentityKey = freshProfile.e2eIdentityKey,
                    lastSeenAt = freshProfile.lastSeenAt,
                    lastSeenPublic = freshProfile.lastSeenPublic,
                )
            }
            peerKeyBlocked = evaluatePeerKey(peer.id, peer.e2eIdentityKey)
            if (peerKeyBlocked == null && peer.e2eIdentityKey != null) {
                peerPublicKey = try {
                    Crypto.publicKeyFromB64(peer.e2eIdentityKey!!)
                } catch (e: Exception) {
                    peerKeyBlocked = SessionUiState.PEER_NO_KEY
                    null
                }
            }
            session = loadSession(conv.id, peer.id)
        }

        flushPendingKx(conv.id)

        // F8: rewind 30s — created_at defaults to transaction-start now(),
        // so a late commit can carry an older timestamp than our watermark.
        // processRow is idempotent (dedupe by server id), so re-fetching a
        // small overlap window is safe.
        val watermark = db.lastServerCreatedAt(conv.id)?.let { SyncGuards.rewindWatermark(it) }
        var incomingNew = 0
        if (watermark == null) {
            var page = api.fetchLatestMessages(conv.id, PAGE_SIZE)
            var oldest = page.firstOrNull()?.createdAt
            for (row in page) if (processRow(conv, row, session, peerPublicKey)) incomingNew++
            // Backfill older pages if the key exchange might sit outside the
            // first window (otherwise the secret session could never form).
            var rounds = 0
            while (rounds < 5 && page.size == PAGE_SIZE && oldest != null &&
                (session == null || !session.hasSession()) &&
                db.listPlaceholders(conv.id).any { it.senderId != myId }
            ) {
                page = api.fetchMessagesBefore(conv.id, oldest, PAGE_SIZE)
                if (page.isEmpty()) break
                oldest = page.firstOrNull()?.createdAt
                for (row in page) if (processRow(conv, row, session, peerPublicKey)) incomingNew++
                rounds++
            }
        } else {
            var wm: String = watermark
            var lastRowId: String? = null
            while (true) {
                val page = api.fetchMessagesAfter(conv.id, wm)
                for (row in page) if (processRow(conv, row, session, peerPublicKey)) incomingNew++
                if (page.size < 200) break
                // Guard against >200 rows sharing one timestamp: gte-cursor
                // would refetch the same page forever.
                if (page.last().id == lastRowId) break
                lastRowId = page.last().id
                wm = page.last().createdAt
            }
        }

        // Self-heal: retry envelopes that are stored but not yet decrypted
        // (session arrived later, process died mid-sync in an older version…).
        if (session != null) healPlaceholders(conv, session)

        reconcileEditsAndDeletes(conv, session)
        syncPlainReactions(conv.id)

        var peerTyping = false
        var peerLastSeenAt: String? = peer?.lastSeenAt
        var peerLastSeenPublic: Boolean = peer?.lastSeenPublic ?: true
        if (!isGroup && peer != null) {
            try {
                val until = api.fetchTypingUntil(conv.id, peer.id)?.let { UiUtil.parseInstant(it) }
                peerTyping = until != null && until.isAfter(Instant.now())
            } catch (e: Exception) {
                // Typing is best-effort decoration.
            }
            try {
                val presence = api.fetchPresence(peer.id)
                peerLastSeenAt = presence.lastSeenAt
                peerLastSeenPublic = presence.public
            } catch (e: Exception) {
                // Keep the (possibly stale) profile-copy values.
            }
        }

        if (markReadWanted && incomingNew > 0) {
            try {
                api.markRead(conv.id)
            } catch (e: Exception) {
                // Read receipts are best-effort.
            }
        }

        // Fresh read watermarks every sync: the Conversation object handed
        // in by the UI goes stale, which previously froze read receipts
        // (and timed-message countdowns) until the chat was reopened.
        val memberReads = try {
            api.fetchMemberReadStates(conv.id)
        } catch (e: Exception) {
            emptyMap()
        }
        if (peer != null) {
            startExpiryForReadOwnMessages(conv, memberReads[peer.id])
        }

        val state = computeState(isGroup, session, peerKeyBlocked)
        val notice = when {
            peerKeyBlocked == SessionUiState.PEER_KEY_CHANGED && state == SessionUiState.READY ->
                "⚠️ کلید امنیتی مخاطب عوض شده است (مثلاً نصب دوباره اپ). پیام‌های این نشست خوانا می‌مانند، اما برای نشست تازه از بخش امنیت بازنشانی کنید."
            else -> null
        }
        val page = latestPage(conv.id)
        return SyncResult(
            page.messages, state, peer, notice, page.hasMore,
            peerTyping = peerTyping,
            peerLastSeenAt = peerLastSeenAt,
            peerLastSeenPublic = peerLastSeenPublic,
            memberLastReadAt = memberReads,
        )
    }

    private fun latestPage(conversationId: String): PageResult {
        val rows = db.listVisibleLatest(conversationId, PAGE_SIZE)
        val hasMore = db.countVisible(conversationId) > rows.size
        return PageResult(rows.map(::toUi), hasMore)
    }

    private fun purgeExpired() {
        val doomed = db.purgeExpired(System.currentTimeMillis())
        for (row in doomed) {
            if (row.senderId == api.myUserId && row.serverId != null) {
                try {
                    api.deleteMessage(row.serverId)
                } catch (e: Exception) {
                    // Local copy is gone; server ciphertext cleanup is best effort.
                }
            }
        }
    }

    private fun startExpiryForReadOwnMessages(conv: Conversation, peerReadIso: String?) {
        val peerRead = UiUtil.parseInstant(peerReadIso ?: "") ?: return
        val now = System.currentTimeMillis()
        for (row in db.listAwaitingExpiry(conv.id, myId)) {
            val created = UiUtil.parseInstant(row.createdAt) ?: continue
            if (!created.isAfter(peerRead)) {
                db.setExpiresAt(row.localId, now + row.ttlSeconds * 1000L)
            }
        }
    }

    private fun reconcileEditsAndDeletes(conv: Conversation, session: SecretSession?) {
        val key = "editwm:${conv.id}"
        val wm = secure.getSecret(key) ?: "1970-01-01T00:00:00Z"
        val rows = try {
            api.fetchEditedOrDeletedSince(conv.id, SyncGuards.rewindWatermark(wm))
        } catch (e: Exception) {
            return
        }
        if (rows.isEmpty()) return
        var maxWm = wm
        var maxInstant = UiUtil.parseInstant(wm)
        for (row in rows) {
            listOfNotNull(row.editedAt, row.deletedAt).forEach { stamp ->
                val inst = UiUtil.parseInstant(stamp)
                if (inst != null && (maxInstant == null || inst.isAfter(maxInstant))) {
                    maxInstant = inst
                    maxWm = stamp
                }
            }
            val existing = db.getByServerId(row.id)
            if (existing == null) {
                processRow(conv, row, session, null)
            } else if (row.deletedAt != null) {
                if (!existing.deleted) {
                    // Consume the ratchet step for never-decrypted tombstones.
                    if (row.kind == "e2ee" && row.senderId != myId && session != null &&
                        existing.plainText == null && existing.envelopeBody != null
                    ) {
                        tryDecryptAndApply(conv, existing.serverId!!, existing, session, tombstone = true)
                    }
                    db.markDeletedByServerId(row.id)
                }
            } else if (row.editedAt != null && existing != null && row.kind == "text" &&
                !existing.deleted && existing.envelopeBody != row.body
            ) {
                db.updateTextByServerId(row.id, row.body)
            }
        }
        secure.putSecret(key, maxWm)
    }

    private fun healPlaceholders(conv: Conversation, session: SecretSession) {
        if (!session.hasSession()) return
        for (row in db.listPlaceholders(conv.id)) {
            if (row.senderId == myId || row.serverId == null) continue
            if (SecretSession.isKx(row.envelopeBody ?: "")) continue
            tryDecryptAndApply(conv, row.serverId, row, session, tombstone = false)
        }
    }

    /**
     * Decrypts one stored envelope and applies its payload (text / timed /
     * revise / end-of-secret), persisting plaintext BEFORE session state so
     * a process death can never lose a message whose key was consumed.
     */
    private fun tryDecryptAndApply(
        conv: Conversation,
        serverId: String,
        local: LocalMessage,
        session: SecretSession,
        tombstone: Boolean,
    ): Boolean {
        val body = local.envelopeBody ?: return false
        val plain = try {
            synchronized(sessionLock) { session.decrypt(body) }
        } catch (e: Exception) {
            return false
        }
        when {
            plain.endOfSecret -> {
                synchronized(sessionLock) {
                    session.reset()
                    saveSession(session)
                }
                insertSystemNotice(
                    conv.id, "sys:end:$serverId",
                    "🔓 مخاطب چت امن را پایان داد؛ پیام‌های تازه عادی ارسال می‌شوند.",
                )
            }
            plain.reactTargetId != null -> {
                // F1: control payloads carry peer-chosen target ids; only
                // react to rows of THIS conversation.
                applyReactionLocal(conv.id, plain.reactTargetId, local.senderId, plain.reaction ?: "")
                synchronized(sessionLock) { saveSession(session) }
            }
            plain.reviseTargetId != null -> {
                // F1: a peer may only revise its OWN messages in THIS
                // conversation; anything else is silently dropped (the
                // ratchet step is still consumed and saved below).
                val target = db.getByServerId(plain.reviseTargetId)
                if (target != null && !target.deleted &&
                    SyncGuards.canRevise(target.senderId, target.conversationId, local.senderId, conv.id)
                ) {
                    db.applyReviseByServerId(plain.reviseTargetId, plain.text, local.senderId, conv.id)
                }
                synchronized(sessionLock) { saveSession(session) }
            }
            tombstone -> {
                // Sequence consumed; plaintext intentionally discarded.
                synchronized(sessionLock) { saveSession(session) }
            }
            else -> {
                val expires = if (plain.ttlSeconds > 0) {
                    System.currentTimeMillis() + plain.ttlSeconds * 1000L
                } else {
                    null
                }
                db.updatePlainByServerId(
                    serverId, plain.text, plain.ttlSeconds, expires,
                    plain.replyTargetId, plain.forwarded,
                )
                synchronized(sessionLock) { saveSession(session) }
            }
        }
        return true
    }

    /** Returns true if a NEW incoming row (any kind) was stored. */
    private fun processRow(
        conv: Conversation,
        row: MessageRow,
        session: SecretSession?,
        peerPublicKey: PublicKey?,
    ): Boolean {
        val me = myId
        db.getByServerId(row.id)?.let { existing ->
            if (row.deletedAt != null && !existing.deleted) {
                if (row.kind == "e2ee" && row.senderId != me && session != null &&
                    existing.plainText == null && existing.envelopeBody != null
                ) {
                    tryDecryptAndApply(conv, row.id, existing, session, tombstone = true)
                }
                db.markDeletedByServerId(row.id)
            } else if (row.deletedAt == null && row.kind == "text" && row.editedAt != null &&
                !existing.deleted && existing.envelopeBody != row.body
            ) {
                db.updateTextByServerId(row.id, row.body)
            }
            return false
        }
        if (row.clientId.isNotEmpty()) {
            db.getByClientId(row.clientId)?.let { local ->
                if (local.serverId == null) {
                    if (local.status == LocalDb.STATUS_KX_PENDING) {
                        db.markKxSentByClientId(row.clientId, row.id, row.createdAt)
                    } else {
                        db.markSentByClientId(row.clientId, row.id, row.createdAt)
                    }
                    if (row.deletedAt != null) db.markDeletedByServerId(row.id)
                    return false
                }
            }
        }

        val incoming = row.senderId != me
        val deleted = row.deletedAt != null
        val clientId = row.clientId.ifEmpty { UUID.randomUUID().toString() }

        if (conv.kind == "group" || row.kind == "text") {
            db.insert(row.id, clientId, conv.id, row.senderId, row.kind,
                if (deleted) null else row.body, if (deleted) null else row.body,
                row.createdAt, LocalDb.STATUS_SENT, deleted,
                replyToServerId = row.replyToId, forwarded = row.forwarded)
            return incoming
        }

        // kind == e2ee
        if (SecretSession.isKx(row.body)) {
            handleKxRow(conv, row, session, peerPublicKey)
            db.insert(row.id, clientId, conv.id, row.senderId, "e2ee", row.body, null,
                row.createdAt, LocalDb.STATUS_KX, false)
            return incoming
        }

        if (!incoming) {
            // Own encrypted message authored elsewhere: placeholder.
            db.insert(row.id, clientId, conv.id, row.senderId, "e2ee", row.body, null,
                row.createdAt, LocalDb.STATUS_SENT, deleted)
            return false
        }

        val control = SecretSession.isControl(row.body)
        // Insert first (placeholder), decrypt second, persist session last —
        // crash-safe ordering: a consumed key always has its envelope stored.
        db.insert(
            row.id, clientId, conv.id, row.senderId, "e2ee", row.body, null,
            row.createdAt, if (control) LocalDb.STATUS_HIDDEN else LocalDb.STATUS_SENT, deleted,
        )
        if (session != null) {
            db.getByServerId(row.id)?.let { stored ->
                tryDecryptAndApply(conv, row.id, stored, session, tombstone = deleted)
            }
            if (deleted) db.markDeletedByServerId(row.id)
        }
        return true
    }

    private fun handleKxRow(conv: Conversation, row: MessageRow, session: SecretSession?, peerPublicKey: PublicKey?) {
        if (session == null || peerPublicKey == null) return
        if (row.senderId == myId) return
        val kx = SecretSession.parseKx(row.body) ?: return
        when (kx.stage) {
            "k1" -> {
                val hadSession = synchronized(sessionLock) { session.hasSession() }
                val storedKey = "kxaccept:${row.id}"
                var acceptEnvelope = secure.getSecret(storedKey)
                if (acceptEnvelope == null) {
                    acceptEnvelope = synchronized(sessionLock) {
                        val env = session.handleKxInit(
                            row.body, peerPublicKey, identity.privateKey(), System.currentTimeMillis(),
                        )
                        if (env != null) {
                            saveSession(session)
                            secure.putSecretCommit(storedKey, env)
                        }
                        env
                    }
                }
                if (acceptEnvelope != null) {
                    val deterministicClientId = UUID.nameUUIDFromBytes(
                        "accept:${row.id}".toByteArray(Charsets.UTF_8),
                    ).toString()
                    if (queueHiddenEnvelope(conv.id, acceptEnvelope, deterministicClientId)) {
                        secure.removeSecret(storedKey)
                    }
                    if (!hadSession) {
                        insertSystemNotice(conv.id, "sys:start:${row.id}", "🔒 چت امن فعال شد؛ پیام‌های تازه رمزنگاری سرتاسری می‌شوند.")
                    }
                }
            }
            "k2" -> {
                val hadSession = synchronized(sessionLock) { session.hasSession() }
                val accepted = synchronized(sessionLock) {
                    val ok = session.handleKxAccept(row.body, peerPublicKey, System.currentTimeMillis())
                    if (ok) saveSession(session)
                    ok
                }
                if (accepted && !hadSession) {
                    insertSystemNotice(conv.id, "sys:start:${row.id}", "🔒 چت امن فعال شد؛ پیام‌های تازه رمزنگاری سرتاسری می‌شوند.")
                }
            }
        }
    }

    private fun insertSystemNotice(conversationId: String, clientId: String, text: String) {
        if (db.getByClientId(clientId) != null) return
        db.insert(
            serverId = null, clientId = clientId, conversationId = conversationId,
            senderId = "", kind = "text", envelopeBody = null, plainText = text,
            createdAt = Instant.now().toString(), status = LocalDb.STATUS_SENT, deleted = false,
        )
    }

    /** Inserts (or reuses) a hidden local row for a kx/control envelope and posts it. */
    private fun queueHiddenEnvelope(conversationId: String, envelope: String, clientId: String): Boolean {
        if (db.getByClientId(clientId) == null) {
            db.insert(
                serverId = null, clientId = clientId, conversationId = conversationId,
                senderId = myId, kind = "e2ee", envelopeBody = envelope, plainText = null,
                createdAt = Instant.now().toString(), status = LocalDb.STATUS_KX_PENDING, deleted = false,
            )
        }
        return tryPostHidden(clientId)
    }

    private fun tryPostHidden(clientId: String): Boolean {
        val local = db.getByClientId(clientId) ?: return false
        if (local.status == LocalDb.STATUS_KX) return true
        val envelope = local.envelopeBody ?: return false
        return try {
            val row = api.insertMessage(local.conversationId, myId, envelope, "e2ee", clientId)
            db.markKxSentByClientId(clientId, row.id, row.createdAt)
            true
        } catch (e: ApiException) {
            e.code == "23505" // already on the server; echo reconciles later
        } catch (e: Exception) {
            false
        }
    }

    private fun flushPendingKx(conversationId: String) {
        for (pending in db.listPendingKx(conversationId)) {
            tryPostHidden(pending.clientId)
        }
    }

    private fun postVisible(local: LocalMessage) {
        val body = local.envelopeBody ?: throw ApiException("متن پیام در دسترس نیست")
        try {
            val row = api.insertMessage(
                local.conversationId, myId, body, local.kind, local.clientId,
                local.replyToServerId, local.forwarded,
            )
            db.markSentByClientId(local.clientId, row.id, row.createdAt)
        } catch (e: Exception) {
            if (e is ApiException && e.code == "23505") {
                db.setStatusByLocalId(local.localId, LocalDb.STATUS_SENT) // idempotent duplicate
            } else {
                scheduleRetryFor(local, e)
                throw e
            }
        }
    }

    private fun sendPlain(
        conversationId: String,
        text: String,
        replyToServerId: String?,
        forwarded: Boolean,
    ) {
        val clientId = UUID.randomUUID().toString()
        val localId = db.insert(
            serverId = null, clientId = clientId, conversationId = conversationId,
            senderId = myId, kind = "text", envelopeBody = text, plainText = text,
            createdAt = Instant.now().toString(), status = LocalDb.STATUS_SENDING, deleted = false,
            replyToServerId = replyToServerId, forwarded = forwarded,
        )
        db.getByLocalId(localId)?.let { postVisible(it) }
    }

    fun touchPresenceThrottled() {
        val now = System.currentTimeMillis()
        if (now - lastPresenceTouchMs < 60_000L) return
        lastPresenceTouchMs = now
        try {
            api.touchPresence()
        } catch (e: Exception) {
            lastPresenceTouchMs = 0L
        }
    }

    /** Fire-and-forget for lifecycle/connectivity callbacks. */
    fun flushAllOutboxAsync() {
        if (api.myUserId == null) return
        Threads.runIo {
            try {
                flushOutbox(null)
            } catch (e: Exception) {
                // Still offline; next sync/resume will retry.
            }
        }
    }

    private fun flushOutbox(conversationId: String?) {
        val due = db.listRetryableOutgoing(conversationId, System.currentTimeMillis(), 10)
        for (local in due) {
            try {
                postVisible(local)
            } catch (e: Exception) {
                // postVisible already scheduled the next backoff (or marked
                // a permanent failure); keep flushing the rest.
            }
        }
    }

    private fun isTransientFailure(e: Exception): Boolean = when (e) {
        is ApiException ->
            e.status == 0 || e.status == 408 || e.status == 425 || e.status == 429 ||
                e.status >= 500 ||
                (e.message ?: "").contains("سریع") ||
                (e.message ?: "").contains("درخواست‌ها زیاد")
        is IOException -> true
        else -> false
    }

    private fun backoffMs(retryCount: Int, clientId: String): Long {
        val exp = minOf(retryCount, 6)
        val base = minOf(5 * 60_000L, 5_000L shl exp)
        val jitter = (clientId.hashCode() and 1023).toLong()
        return base + jitter
    }

    private fun scheduleRetryFor(local: LocalMessage, e: Exception) {
        val count = local.retryCount + 1
        val next = if (isTransientFailure(e)) {
            System.currentTimeMillis() + backoffMs(count, local.clientId)
        } else {
            null // permanent failure (e.g. blocked): manual retry only
        }
        db.scheduleRetry(local.localId, count, next)
    }

    /** Server-side reaction sync for plain/group rows on the latest page. */
    private fun syncPlainReactions(conversationId: String) {
        val rows = db.listVisibleLatest(conversationId, PAGE_SIZE)
            .filter { it.kind == "text" && it.serverId != null }
        if (rows.isEmpty()) return
        val reactions = try {
            api.fetchReactions(rows.map { it.serverId!! })
        } catch (e: Exception) {
            return
        }
        val byMessage = reactions.groupBy { it.messageId }
        for (row in rows) {
            val list = byMessage[row.serverId].orEmpty()
            val json = if (list.isEmpty()) {
                null
            } else {
                JSONObject().apply { list.forEach { put(it.userId, it.reaction) } }.toString()
            }
            if (json != row.reactionsJson) {
                db.setReactionsJsonByServerId(row.serverId!!, conversationId, json)
            }
        }
    }

    private fun applyReactionLocal(conversationId: String, targetServerId: String, userId: String, emoji: String) {
        val target = db.getByServerId(targetServerId) ?: return
        if (!SyncGuards.canReact(target.conversationId, conversationId)) return
        val json = try {
            JSONObject(target.reactionsJson ?: "{}")
        } catch (e: Exception) {
            JSONObject()
        }
        if (emoji.isEmpty()) json.remove(userId) else json.put(userId, emoji)
        db.setReactionsJsonByServerId(
            targetServerId, conversationId,
            if (json.length() == 0) null else json.toString(),
        )
    }

    // ------------------------------------------------------------ helpers
    private fun peerProfileCached(peerId: String, force: Boolean): Profile? {
        val now = System.currentTimeMillis()
        val cached = profileCache[peerId]
        if (!force && cached != null && now - cached.second < PROFILE_TTL_MS) return cached.first
        val fresh = try {
            api.fetchProfile(peerId)
        } catch (e: Exception) {
            null
        }
        if (fresh != null) profileCache[peerId] = fresh to now
        return fresh ?: cached?.first
    }

    private fun computeState(isGroup: Boolean, session: SecretSession?, peerKeyBlocked: SessionUiState?): SessionUiState {
        if (isGroup) return SessionUiState.GROUP_PLAIN
        if (session?.hasSession() == true) return SessionUiState.READY
        if (peerKeyBlocked != null) return peerKeyBlocked
        if (session?.isWaitingForPeer() == true) return SessionUiState.WAITING_PEER
        return SessionUiState.NO_SESSION
    }

    /** Returns a blocking state, or null when the peer key is usable. Pins on first use. */
    private fun evaluatePeerKey(peerId: String, keyB64: String?): SessionUiState? {
        if (keyB64.isNullOrEmpty()) return SessionUiState.PEER_NO_KEY
        val pin = identity.peerPin(peerId)
        return when {
            pin == null -> {
                identity.pinPeer(peerId, keyB64)
                null
            }
            pin != keyB64 -> SessionUiState.PEER_KEY_CHANGED
            else -> null
        }
    }

    private fun loadSession(conversationId: String, peerId: String): SecretSession {
        synchronized(sessionLock) {
            sessionCache[conversationId]?.let { return it }
            val stored = secure.getSecret(sessionKey(conversationId))
            val session = if (stored != null) {
                try {
                    val parsed = SecretSession.deserialize(stored)
                    if (parsed.peerUserId == peerId && parsed.myUserId == myId) parsed
                    else SecretSession(myId, peerId, conversationId)
                } catch (e: Exception) {
                    SecretSession(myId, peerId, conversationId)
                }
            } else {
                SecretSession(myId, peerId, conversationId)
            }
            sessionCache[conversationId] = session
            return session
        }
    }

    private fun saveSession(session: SecretSession) {
        // Synchronous write: an async apply() could lose the newest ratchet
        // state on process death and roll the chains back.
        secure.putSecretCommit(sessionKey(session.conversationId), session.serialize())
    }

    private fun sessionKey(conversationId: String): String = "session:$conversationId"

    private fun peerOf(conv: Conversation): MemberInfo? =
        conv.members.firstOrNull { it.id != api.myUserId }

    private fun titleOf(conv: Conversation, peer: MemberInfo?): String =
        if (conv.kind == "group") conv.title?.ifBlank { "گروه" } ?: "گروه"
        else peer?.displayName?.ifBlank { "کاربر" } ?: "کاربر"

    private fun previewOf(conv: Conversation): String {
        val last = conv.lastMessage ?: return "گفتگو ایجاد شد"
        if (last.deletedAt != null) return "پیام حذف شد"
        val local = db.getByServerId(last.id)
        if (local?.deleted == true) return "پیام حذف شد"
        val prefix = if (last.senderId == api.myUserId) "شما: " else ""
        if (last.kind == "e2ee") {
            if (SecretSession.isKx(last.body) || SecretSession.isControl(last.body)) return "🔐 چت امن"
            val localText = local?.plainText
            return if (localText != null) prefix + localText else "🔒 پیام رمزنگاری‌شده"
        }
        return prefix + last.body
    }

    private fun toUi(local: LocalMessage): UiMessage {
        val reactions = parseReactions(local.reactionsJson)
        return UiMessage(
            localId = local.localId,
            serverId = local.serverId,
            senderId = local.senderId,
            mine = local.senderId == api.myUserId,
            text = local.plainText,
            createdAt = local.createdAt,
            status = local.status,
            deleted = local.deleted,
            encrypted = local.kind == "e2ee",
            ttlSeconds = local.ttlSeconds,
            expiresAtMs = local.expiresAtMs,
            expired = local.expired,
            edited = local.edited,
            system = local.senderId.isEmpty(),
            replyToServerId = local.replyToServerId,
            replySnippet = local.replyToServerId?.let { replySnippet(it) },
            forwarded = local.forwarded,
            reactions = reactions,
            myReaction = api.myUserId?.let { reactions[it] },
        )
    }

    private fun parseReactions(json: String?): Map<String, String> {
        if (json.isNullOrEmpty()) return emptyMap()
        return try {
            val obj = JSONObject(json)
            val out = LinkedHashMap<String, String>()
            val keys = obj.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                out[key] = obj.getString(key)
            }
            out
        } catch (e: Exception) {
            emptyMap()
        }
    }

    private fun replySnippet(serverId: String): String {
        val target = db.getByServerId(serverId) ?: return "پیام اصلی در دسترس نیست"
        return when {
            target.expired -> "⏱ پیام خودکار حذف شد"
            target.deleted -> "پیام حذف شد"
            target.plainText != null -> target.plainText.replace(Regex("\\s+"), " ").take(90)
            else -> "🔒 پیام رمزنگاری‌شده"
        }
    }

    private fun <T> ioCall(callback: (Result<T>) -> Unit, block: () -> T) {
        Threads.runIo {
            val result = runCatching(block)
            Threads.runUi { callback(result) }
        }
    }

    private companion object {
        val BACKUP_SECURE_PREFIXES = listOf("identity_", "session:", "pin:", "ttl:", "editwm:", "kxaccept:")
        const val PAGE_SIZE = 100
        const val MAX_PLAIN_CHARS = 4000
        const val PROFILE_TTL_MS = 5 * 60 * 1000L
    }
}
