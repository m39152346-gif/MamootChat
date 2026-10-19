package ir.mamoot.app.sync

import ir.mamoot.app.Threads
import ir.mamoot.app.crypto.Crypto
import ir.mamoot.app.crypto.DecryptException
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
import java.security.PublicKey
import java.time.Instant
import java.util.UUID

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
)

class ChatRepository(
    private val api: SupabaseApi,
    private val db: LocalDb,
    private val secure: SecureStore,
    private val identity: IdentityManager,
) {
    private val sessionLock = Any()
    private val sessionCache = mutableMapOf<String, SecretSession>()

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

    fun localMessages(conversationId: String, callback: (Result<List<UiMessage>>) -> Unit) {
        ioCall(callback) { db.listVisible(conversationId).map(::toUi) }
    }

    fun syncChat(conv: Conversation, markReadWanted: Boolean, callback: (Result<SyncResult>) -> Unit) {
        ioCall(callback) { syncInternal(conv, markReadWanted) }
    }

    fun startSecretChat(conv: Conversation, callback: (Result<SyncResult>) -> Unit) {
        ioCall(callback) {
            val peer = peerOf(conv) ?: throw ApiException("مخاطب یافت نشد")
            val peerProfile = api.fetchProfile(peer.id)
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
            queueKx(conv.id, envelope, UUID.randomUUID().toString())
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
            val peerProfile = api.fetchProfile(peer.id)
            val peerKeyB64 = peerProfile?.e2eIdentityKey ?: peer.e2eIdentityKey
            if (peerKeyB64 != null) identity.pinPeer(peer.id, peerKeyB64)
            val session = loadSession(conv.id, peer.id)
            if (peerKeyB64 != null) {
                val envelope = synchronized(sessionLock) {
                    val env = session.startExchange(identity.privateKey(), System.currentTimeMillis())
                    saveSession(session)
                    env
                }
                queueKx(conv.id, envelope, UUID.randomUUID().toString())
            }
            syncInternal(conv, false)
        }
    }

    fun sendText(conv: Conversation, rawText: String, callback: (Result<List<UiMessage>>) -> Unit) {
        ioCall(callback) {
            val text = rawText.trim()
            if (text.isEmpty()) throw ApiException("پیام خالی است")
            if (conv.kind == "group") {
                sendPlain(conv.id, text)
            } else {
                val peer = peerOf(conv) ?: throw ApiException("مخاطب یافت نشد")
                if (text.length > SecretSession.MAX_PLAINTEXT_CHARS) {
                    throw ApiException("پیام بیش از حد طولانی است (حداکثر ${SecretSession.MAX_PLAINTEXT_CHARS} نویسه در چت امن).")
                }
                val session = loadSession(conv.id, peer.id)
                var rekeyEnvelope: String? = null
                val envelope = synchronized(sessionLock) {
                    if (!session.hasSession()) throw ApiException("چت امن هنوز برقرار نشده است.")
                    if (session.needsRekey(System.currentTimeMillis())) {
                        rekeyEnvelope = session.startExchange(identity.privateKey(), System.currentTimeMillis())
                    }
                    val env = session.encrypt(text)
                    saveSession(session)
                    env
                }
                rekeyEnvelope?.let { queueKx(conv.id, it, UUID.randomUUID().toString()) }
                val clientId = UUID.randomUUID().toString()
                val localId = db.insert(
                    serverId = null, clientId = clientId, conversationId = conv.id,
                    senderId = myId, kind = "e2ee", envelopeBody = envelope,
                    plainText = text, createdAt = Instant.now().toString(),
                    status = LocalDb.STATUS_SENDING, deleted = false,
                )
                try {
                    val row = api.insertMessage(conv.id, myId, envelope, "e2ee", clientId)
                    db.markSentByClientId(clientId, row.id, row.createdAt)
                } catch (e: Exception) {
                    if (e is ApiException && e.code == "23505") {
                        // Idempotent duplicate: an earlier retry already landed.
                        db.setStatusByLocalId(localId, LocalDb.STATUS_SENT)
                    } else {
                        db.setStatusByLocalId(localId, LocalDb.STATUS_FAILED)
                        throw e
                    }
                }
            }
            db.listVisible(conv.id).map(::toUi)
        }
    }

    fun retryMessage(localId: Long, callback: (Result<List<UiMessage>>) -> Unit) {
        ioCall(callback) {
            val local = db.getByLocalId(localId) ?: throw ApiException("پیام یافت نشد")
            val body = local.envelopeBody ?: throw ApiException("متن پیام در دسترس نیست")
            db.setStatusByLocalId(localId, LocalDb.STATUS_SENDING)
            try {
                val row = api.insertMessage(local.conversationId, myId, body, local.kind, local.clientId)
                db.markSentByClientId(local.clientId, row.id, row.createdAt)
            } catch (e: Exception) {
                if (e is ApiException && e.code == "23505") {
                    db.setStatusByLocalId(localId, LocalDb.STATUS_SENT)
                } else {
                    db.setStatusByLocalId(localId, LocalDb.STATUS_FAILED)
                    throw e
                }
            }
            db.listVisible(local.conversationId).map(::toUi)
        }
    }

    fun deleteMessage(local: UiMessage, callback: (Result<Unit>) -> Unit) {
        ioCall(callback) {
            val serverId = local.serverId ?: throw ApiException("پیام هنوز ارسال نشده است")
            api.deleteMessage(serverId)
            db.markDeletedByServerId(serverId)
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

    fun wipeLocal() {
        synchronized(sessionLock) { sessionCache.clear() }
        db.deleteAll()
        identity.wipe()
        secure.clearAll()
    }

    fun totalUnread(callback: (Result<Int>) -> Unit) {
        ioCall(callback) { api.listConversations().sumOf { it.unreadCount } }
    }

    // ------------------------------------------------------------ sync engine
    private fun syncInternal(conv: Conversation, markReadWanted: Boolean): SyncResult {
        val isGroup = conv.kind == "group"
        var peer = peerOf(conv)
        var peerPublicKey: PublicKey? = null
        var peerKeyBlocked: SessionUiState? = null
        var session: SecretSession? = null

        if (!isGroup && peer != null) {
            val freshProfile = try {
                api.fetchProfile(peer.id)
            } catch (e: Exception) {
                null
            }
            if (freshProfile != null) {
                peer = peer.copy(
                    username = freshProfile.username,
                    displayName = freshProfile.displayName,
                    e2eIdentityKey = freshProfile.e2eIdentityKey,
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

        val watermark = db.lastServerCreatedAt(conv.id)
        val fetched: List<MessageRow> = if (watermark == null) {
            api.fetchLatestMessages(conv.id, 100)
        } else {
            val all = mutableListOf<MessageRow>()
            var wm: String = watermark
            while (true) {
                val page = api.fetchMessagesAfter(conv.id, wm)
                all.addAll(page)
                if (page.size < 200) break
                wm = page.last().createdAt
            }
            all
        }

        var incomingNew = 0
        for (row in fetched) {
            if (processRow(conv, row, session, peerPublicKey)) incomingNew++
        }

        if (markReadWanted && incomingNew > 0) {
            try {
                api.markRead(conv.id)
            } catch (e: Exception) {
                // Read receipts are best-effort.
            }
        }

        val state = computeState(isGroup, session, peerKeyBlocked)
        val notice = when {
            peerKeyBlocked == SessionUiState.PEER_KEY_CHANGED && state == SessionUiState.READY ->
                "⚠️ کلید امنیتی مخاطب عوض شده است (مثلاً نصب دوباره اپ). پیام‌های این نشست خوانا می‌مانند، اما برای نشست تازه از بخش امنیت بازنشانی کنید."
            else -> null
        }
        return SyncResult(db.listVisible(conv.id).map(::toUi), state, peer, notice)
    }

    /** Returns true if a new visible incoming message was stored. */
    private fun processRow(
        conv: Conversation,
        row: MessageRow,
        session: SecretSession?,
        peerPublicKey: PublicKey?,
    ): Boolean {
        val me = myId
        db.getByServerId(row.id)?.let { existing ->
            if (row.deletedAt != null && !existing.deleted) db.markDeletedByServerId(row.id)
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
        if (row.deletedAt != null) {
            db.insert(row.id, row.clientId.ifEmpty { UUID.randomUUID().toString() }, conv.id,
                row.senderId, row.kind, null, null, row.createdAt, LocalDb.STATUS_SENT, true)
            return incoming
        }

        if (conv.kind == "group" || row.kind == "text") {
            db.insert(row.id, row.clientId.ifEmpty { UUID.randomUUID().toString() }, conv.id,
                row.senderId, row.kind, row.body, row.body, row.createdAt, LocalDb.STATUS_SENT, false)
            return incoming
        }

        // kind == e2ee
        if (SecretSession.isKx(row.body)) {
            handleKxRow(conv, row, session, peerPublicKey)
            db.insert(row.id, row.clientId.ifEmpty { UUID.randomUUID().toString() }, conv.id,
                row.senderId, "e2ee", row.body, null, row.createdAt, LocalDb.STATUS_KX, false)
            return false
        }

        if (!incoming) {
            // Own encrypted message authored elsewhere (cannot be decrypted with
            // the receive chain); keep a placeholder.
            db.insert(row.id, row.clientId.ifEmpty { UUID.randomUUID().toString() }, conv.id,
                row.senderId, "e2ee", row.body, null, row.createdAt, LocalDb.STATUS_SENT, false)
            return false
        }

        var plain: String? = null
        if (session != null) {
            plain = try {
                synchronized(sessionLock) {
                    val text = session.decrypt(row.body)
                    saveSession(session)
                    text
                }
            } catch (e: DecryptException) {
                null
            } catch (e: Exception) {
                null
            }
        }
        db.insert(row.id, row.clientId.ifEmpty { UUID.randomUUID().toString() }, conv.id,
            row.senderId, "e2ee", row.body, plain, row.createdAt, LocalDb.STATUS_SENT, false)
        return true
    }

    private fun handleKxRow(conv: Conversation, row: MessageRow, session: SecretSession?, peerPublicKey: PublicKey?) {
        if (session == null || peerPublicKey == null) return
        if (row.senderId == myId) return
        val kx = SecretSession.parseKx(row.body) ?: return
        when (kx.stage) {
            "k1" -> {
                val storedKey = "kxaccept:${row.id}"
                var acceptEnvelope = secure.getSecret(storedKey)
                if (acceptEnvelope == null) {
                    acceptEnvelope = synchronized(sessionLock) {
                        val env = session.handleKxInit(
                            row.body, peerPublicKey, identity.privateKey(), System.currentTimeMillis(),
                        )
                        if (env != null) {
                            saveSession(session)
                            secure.putSecret(storedKey, env)
                        }
                        env
                    }
                }
                if (acceptEnvelope != null) {
                    val deterministicClientId = UUID.nameUUIDFromBytes(
                        "accept:${row.id}".toByteArray(Charsets.UTF_8),
                    ).toString()
                    if (queueKx(conv.id, acceptEnvelope, deterministicClientId)) {
                        secure.removeSecret(storedKey)
                    }
                }
            }
            "k2" -> {
                synchronized(sessionLock) {
                    if (session.handleKxAccept(row.body, peerPublicKey, System.currentTimeMillis())) {
                        saveSession(session)
                    }
                }
            }
        }
    }

    /** Inserts (or reuses) a local hidden row for a kx envelope and tries to post it. */
    private fun queueKx(conversationId: String, envelope: String, clientId: String): Boolean {
        if (db.getByClientId(clientId) == null) {
            db.insert(
                serverId = null, clientId = clientId, conversationId = conversationId,
                senderId = myId, kind = "e2ee", envelopeBody = envelope, plainText = null,
                createdAt = Instant.now().toString(), status = LocalDb.STATUS_KX_PENDING, deleted = false,
            )
        }
        return tryPostKx(clientId)
    }

    private fun tryPostKx(clientId: String): Boolean {
        val local = db.getByClientId(clientId) ?: return false
        if (local.status == LocalDb.STATUS_KX) return true
        val envelope = local.envelopeBody ?: return false
        return try {
            val row = api.insertMessage(local.conversationId, myId, envelope, "e2ee", clientId)
            db.markKxSentByClientId(clientId, row.id, row.createdAt)
            true
        } catch (e: ApiException) {
            if (e.code == "23505") {
                // Already on the server (ambiguous earlier retry); the echo in a
                // later sync will reconcile the server id by client_id.
                true
            } else {
                false
            }
        } catch (e: Exception) {
            false
        }
    }

    private fun flushPendingKx(conversationId: String) {
        for (pending in db.listPendingKx(conversationId)) {
            tryPostKx(pending.clientId)
        }
    }

    private fun sendPlain(conversationId: String, text: String) {
        val clientId = UUID.randomUUID().toString()
        val localId = db.insert(
            serverId = null, clientId = clientId, conversationId = conversationId,
            senderId = myId, kind = "text", envelopeBody = text, plainText = text,
            createdAt = Instant.now().toString(), status = LocalDb.STATUS_SENDING, deleted = false,
        )
        try {
            val row = api.insertMessage(conversationId, myId, text, "text", clientId)
            db.markSentByClientId(clientId, row.id, row.createdAt)
        } catch (e: Exception) {
            db.setStatusByLocalId(localId, LocalDb.STATUS_FAILED)
            throw e
        }
    }

    // ------------------------------------------------------------ helpers
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
        secure.putSecret(sessionKey(session.conversationId), session.serialize())
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
        val prefix = if (last.senderId == api.myUserId) "شما: " else ""
        if (last.kind == "e2ee") {
            if (SecretSession.isKx(last.body)) return "🔐 چت امن"
            val localText = db.getByServerId(last.id)?.plainText
            return if (localText != null) prefix + localText else "🔒 پیام رمزنگاری‌شده"
        }
        return prefix + last.body
    }

    private fun toUi(local: LocalMessage): UiMessage = UiMessage(
        localId = local.localId,
        serverId = local.serverId,
        senderId = local.senderId,
        mine = local.senderId == api.myUserId,
        text = local.plainText,
        createdAt = local.createdAt,
        status = local.status,
        deleted = local.deleted,
        encrypted = local.kind == "e2ee",
    )

    private fun <T> ioCall(callback: (Result<T>) -> Unit, block: () -> T) {
        Threads.runIo {
            val result = runCatching(block)
            Threads.runUi { callback(result) }
        }
    }
}
