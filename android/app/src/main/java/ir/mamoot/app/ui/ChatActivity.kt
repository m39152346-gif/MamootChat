package ir.mamoot.app.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import ir.mamoot.app.MamootApp
import ir.mamoot.app.R
import ir.mamoot.app.Threads
import ir.mamoot.app.net.Conversation
import ir.mamoot.app.net.MemberInfo
import ir.mamoot.app.sync.PageResult
import ir.mamoot.app.sync.SessionUiState
import ir.mamoot.app.sync.UiConversation
import ir.mamoot.app.sync.UiMessage
import ir.mamoot.app.util.UiUtil

class ChatActivity : Activity() {
    private val app: MamootApp get() = application as MamootApp

    private lateinit var conv: Conversation
    private lateinit var adapter: MessageAdapter
    private lateinit var lvMessages: ListView
    private lateinit var etMessage: EditText
    private lateinit var btnSend: Button
    private lateinit var btnTtl: Button
    private lateinit var tvChatTitle: TextView
    private lateinit var tvChatStatus: TextView
    private lateinit var layoutBanner: LinearLayout
    private lateinit var tvBannerText: TextView
    private lateinit var btnBannerAction: Button
    private lateinit var btnLoadOlder: Button
    private lateinit var layoutSearch: LinearLayout
    private lateinit var etChatSearch: EditText
    private lateinit var layoutReply: LinearLayout
    private lateinit var tvReplyQuote: TextView
    private var replyTarget: UiMessage? = null
    private var lastTypingSentMs = 0L
    private var typingActive = false
    private var peerTypingNow = false
    private var peerLastSeenAtStr: String? = null
    private var peerLastSeenPublicFlag = true
    private var consecutiveFailures = 0

    private val handler = Handler(Looper.getMainLooper())
    private var syncing = false
    private var pollIntervalMs = 2_500L
    private var lastMessageCount = -1
    private var currentState: SessionUiState = SessionUiState.NO_SESSION
    private var lastNotice: String? = null
    private var firstLoadDone = false
    private var resumed = false
    private var hasMoreOlder = false
    private var loadingOlder = false
    private var ttlChoiceSeconds = 0
    private var iBlockedPeer = false
    private var searchMatchIds: List<Long> = emptyList()
    private var searchIndex = -1
    private var searchSeq = 0

    private val pollRunnable = object : Runnable {
        override fun run() {
            doSync()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_chat)

        conv = conversationFromIntent()

        lvMessages = findViewById(R.id.lvMessages)
        etMessage = findViewById(R.id.etMessage)
        btnSend = findViewById(R.id.btnSend)
        btnTtl = findViewById(R.id.btnTtl)
        tvChatTitle = findViewById(R.id.tvChatTitle)
        tvChatStatus = findViewById(R.id.tvChatStatus)
        layoutBanner = findViewById(R.id.layoutBanner)
        tvBannerText = findViewById(R.id.tvBannerText)
        btnBannerAction = findViewById(R.id.btnBannerAction)
        btnLoadOlder = findViewById(R.id.btnLoadOlder)
        layoutSearch = findViewById(R.id.layoutSearch)
        etChatSearch = findViewById(R.id.etChatSearch)
        layoutReply = findViewById(R.id.layoutReply)
        tvReplyQuote = findViewById(R.id.tvReplyQuote)
        findViewById<Button>(R.id.btnCancelReply).setOnClickListener { clearReply() }
        etMessage.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(text: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(text: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun afterTextChanged(text: Editable?) {
                maybeSendTyping(text?.isNotEmpty() == true)
            }
        })

        val avatar = findViewById<TextView>(R.id.tvChatAvatar)
        val peer = conv.members.firstOrNull { it.id != app.api.myUserId }
        val title = intent.getStringExtra(EXTRA_TITLE) ?: peer?.displayName ?: conv.title ?: "گفتگو"
        tvChatTitle.text = title
        avatar.text = UiUtil.initials(title)
        avatar.background.mutate().setTint(UiUtil.avatarColor(peer?.id ?: conv.id))
        tvChatStatus.text = "…"

        ttlChoiceSeconds = app.repo.getTtlChoice(conv.id)
        updateTtlButton()

        adapter = MessageAdapter(this)
        adapter.isGroup = conv.kind == "group"
        lvMessages.adapter = adapter
        lvMessages.setOnItemLongClickListener { _, _, position, _ ->
            showMessageActions(adapter.getItem(position))
            true
        }
        lvMessages.setOnItemClickListener { _, _, position, _ ->
            val msg = adapter.getItem(position)
            if (msg.replyToServerId != null) jumpToServerMessage(msg.replyToServerId)
        }

        findViewById<Button>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<Button>(R.id.btnSecurity).setOnClickListener {
            if (conv.kind == "group") showGroupInfo() else showSecurityDialog()
        }
        findViewById<LinearLayout>(R.id.layoutChatHeader).setOnClickListener {
            if (conv.kind == "group") showGroupInfo() else showPeerProfile()
        }
        btnSend.setOnClickListener { sendCurrentText() }
        btnTtl.setOnClickListener { showTtlPicker() }
        btnLoadOlder.setOnClickListener { loadOlder() }
        findViewById<Button>(R.id.btnChatSearch).setOnClickListener { toggleSearch(true) }
        findViewById<Button>(R.id.btnSearchClose).setOnClickListener { toggleSearch(false) }
        findViewById<Button>(R.id.btnSearchNext).setOnClickListener { jumpToMatch(+1) }
        findViewById<Button>(R.id.btnSearchPrev).setOnClickListener { jumpToMatch(-1) }
        etChatSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                val q = s?.toString()?.trim().orEmpty()
                val seq = ++searchSeq
                handler.removeCallbacksAndMessages(SEARCH_TOKEN)
                if (q.isEmpty()) {
                    searchMatchIds = emptyList()
                    searchIndex = -1
                    return
                }
                handler.postDelayed({ runSearch(q, seq) }, SEARCH_TOKEN, 350)
            }
        })

        app.repo.localMessages(conv.id) { result ->
            result.onSuccess { page ->
                applyPage(page, forceScroll = true)
                firstLoadDone = true
            }
        }
        enrichConversationFromServer()
        refreshBlockedState()
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        pollIntervalMs = 2_500L
        doSync()
    }

    override fun onPause() {
        super.onPause()
        resumed = false
        handler.removeCallbacks(pollRunnable)
        sendTyping(false)
    }

    private fun refreshBlockedState() {
        val peerId = conv.members.firstOrNull { it.id != app.api.myUserId }?.id ?: return
        if (conv.kind == "group") return
        Threads.runIo {
            val blocked = try {
                peerId in app.api.myBlockedIds()
            } catch (e: Exception) {
                false
            }
            Threads.runUi {
                if (!isFinishing && blocked != iBlockedPeer) {
                    iBlockedPeer = blocked
                    updateSessionUi(currentState, lastNotice)
                }
            }
        }
    }

    private fun enrichConversationFromServer() {
        Threads.runIo {
            try {
                val fresh = app.api.listConversations().firstOrNull { it.id == conv.id }
                if (fresh != null) {
                    Threads.runUi {
                        if (isFinishing) return@runUi
                        conv = fresh
                        adapter.memberNames = fresh.members.associate { it.id to it.displayName }
                        adapter.groupMemberIds = fresh.members
                            .filter { it.id != app.api.myUserId }
                            .map { it.id }
                        val peer = fresh.members.firstOrNull { it.id != app.api.myUserId }
                        adapter.peerLastReadAt = peer?.lastReadAt ?: ""
                        if (fresh.kind == "dm" && peer != null) {
                            tvChatTitle.text = peer.displayName
                        } else if (!fresh.title.isNullOrBlank()) {
                            tvChatTitle.text = fresh.title
                        }
                        adapter.notifyDataSetChanged()
                    }
                }
            } catch (e: Exception) {
                // Extras-based conversation is enough to continue.
            }
        }
    }

    private fun doSync() {
        if (syncing) {
            scheduleNextPoll()
            return
        }
        syncing = true
        app.repo.syncChat(conv, markReadWanted = true) { result ->
            syncing = false
            if (!isFinishing) {
                result.onSuccess { sync ->
                    consecutiveFailures = 0
                    applyPage(PageResult(sync.messages, sync.hasMore), forceScroll = false)
                    adapter.memberReadAt = sync.memberLastReadAt
                    adapter.peerLastReadAt = sync.peer?.let { peer ->
                        sync.memberLastReadAt[peer.id] ?: peer.lastReadAt
                    } ?: adapter.peerLastReadAt
                    peerTypingNow = sync.peerTyping
                    peerLastSeenAtStr = sync.peerLastSeenAt
                    peerLastSeenPublicFlag = sync.peerLastSeenPublic
                    updateSessionUi(sync.state, sync.notice)
                    val grew = sync.messages.size != lastMessageCount
                    pollIntervalMs = if (grew) 2_500L else minOf(pollIntervalMs * 3 / 2, 10_000L)
                }.onFailure {
                    consecutiveFailures++
                    tvChatStatus.text = "در انتظار اتصال…"
                    // Exponential backoff with deterministic jitter so a dead
                    // network cannot drain the battery with fixed fast polls.
                    val shift = minOf(consecutiveFailures, 4)
                    pollIntervalMs = minOf(60_000L, 5_000L shl shift) +
                        (conv.id.hashCode() and 1023)
                }
            }
            scheduleNextPoll()
        }
    }

    private fun scheduleNextPoll() {
        if (!resumed) return
        handler.removeCallbacks(pollRunnable)
        handler.postDelayed(pollRunnable, pollIntervalMs)
    }

    private fun applyPage(page: PageResult, forceScroll: Boolean) {
        hasMoreOlder = page.hasMore
        btnLoadOlder.visibility = if (hasMoreOlder) View.VISIBLE else View.GONE
        applyMessages(page.messages, forceScroll)
    }

    private fun applyMessages(messages: List<UiMessage>, forceScroll: Boolean) {
        val wasAtBottom = lvMessages.lastVisiblePosition >= adapter.count - 2
        adapter.submit(messages)
        lastMessageCount = messages.size
        if (messages.isNotEmpty() && (forceScroll || wasAtBottom || !firstLoadDone)) {
            lvMessages.post { lvMessages.setSelection(adapter.count - 1) }
        }
    }

    private fun loadOlder() {
        if (loadingOlder || adapter.count == 0) return
        loadingOlder = true
        val first = adapter.getItem(0)
        val firstVisible = lvMessages.firstVisiblePosition
        app.repo.olderMessages(conv.id, first.createdAt, first.localId) { result ->
            loadingOlder = false
            if (isFinishing) return@olderMessages
            result.onSuccess { page ->
                val before = adapter.count
                adapter.prepend(page.messages)
                val added = adapter.count - before
                hasMoreOlder = page.hasMore
                btnLoadOlder.visibility = if (hasMoreOlder) View.VISIBLE else View.GONE
                if (added > 0) lvMessages.post { lvMessages.setSelection(added + firstVisible) }
            }
        }
    }

    private fun updateSessionUi(state: SessionUiState, notice: String?) {
        currentState = state
        lastNotice = notice
        // The composer always works now: without a secret session this is a
        // normal chat; with one, messages are end-to-end encrypted.
        etMessage.isEnabled = true
        btnSend.isEnabled = true
        btnTtl.visibility = if (state == SessionUiState.READY) View.VISIBLE else View.GONE
        etMessage.hint = if (state == SessionUiState.READY) "پیام امن بنویسید… 🔒" else "پیام بنویسید…"

        refreshStatusLine()

        btnBannerAction.setOnClickListener(null)
        when {
            iBlockedPeer -> {
                layoutBanner.visibility = View.VISIBLE
                tvBannerText.text = "این کاربر را مسدود کرده‌اید؛ پیام‌های او به شما نمی‌رسد."
                btnBannerAction.visibility = View.VISIBLE
                btnBannerAction.text = "رفع مسدودیت"
                btnBannerAction.setOnClickListener { setBlocked(false) }
            }
            notice != null -> {
                layoutBanner.visibility = View.VISIBLE
                tvBannerText.text = notice
                btnBannerAction.visibility = View.GONE
            }
            state == SessionUiState.NO_SESSION -> {
                layoutBanner.visibility = View.VISIBLE
                tvBannerText.text = "این گفتگوی عادی است. برای اینکه فقط خودتان دو نفر پیام‌ها را بخوانید، چت امن را فعال کنید."
                btnBannerAction.visibility = View.VISIBLE
                btnBannerAction.text = "🔐 فعال‌سازی چت امن"
                btnBannerAction.setOnClickListener { startSecret() }
            }
            state == SessionUiState.WAITING_PEER -> {
                layoutBanner.visibility = View.VISIBLE
                tvBannerText.text = "درخواست چت امن ارسال شد؛ وقتی مخاطب اپ را باز کند فعال می‌شود. پیام‌های فعلی عادی ارسال می‌شوند."
                btnBannerAction.visibility = View.GONE
            }
            state == SessionUiState.PEER_NO_KEY -> {
                layoutBanner.visibility = View.VISIBLE
                tvBannerText.text = "این مخاطب هنوز اپ اندروید مموت را نصب و فعال نکرده است؛ چت امن ممکن نیست، اما چت عادی برقرار است."
                btnBannerAction.visibility = View.GONE
            }
            state == SessionUiState.PEER_KEY_CHANGED -> {
                layoutBanner.visibility = View.VISIBLE
                tvBannerText.text = "کلید امنیتی مخاطب عوض شده است (مثلاً نصب دوباره). فقط اگر مطمئن هستید بازنشانی کنید؛ تا آن موقع پیام‌ها عادی ارسال می‌شوند."
                btnBannerAction.visibility = View.VISIBLE
                btnBannerAction.text = "بازنشانی نشست امن"
                btnBannerAction.setOnClickListener { confirmResetSecret() }
            }
            else -> layoutBanner.visibility = View.GONE
        }
    }

    private fun sessionStatusText(): String = when (currentState) {
        SessionUiState.READY -> "🔒 چت امن فعال — رمزنگاری سرتاسری"
        SessionUiState.GROUP_PLAIN -> "گروه — بدون رمزنگاری سرتاسری"
        SessionUiState.WAITING_PEER -> "در انتظار پذیرش چت امن… (فعلاً عادی)"
        SessionUiState.NO_SESSION -> "گفتگوی عادی"
        SessionUiState.PEER_NO_KEY -> "گفتگوی عادی — مخاطب اپ را فعال نکرده است"
        SessionUiState.PEER_KEY_CHANGED -> "⚠️ کلید مخاطب عوض شده است"
    }

    private fun refreshStatusLine() {
        val base = sessionStatusText()
        tvChatStatus.text = when {
            conv.kind == "group" -> base
            peerTypingNow -> "در حال نوشتن…" +
                if (currentState == SessionUiState.READY) " · 🔒" else ""
            else -> {
                val presence = UiUtil.presenceText(peerLastSeenAtStr, peerLastSeenPublicFlag)
                if (presence.isEmpty()) base else "$base · $presence"
            }
        }
    }

    private fun maybeSendTyping(hasText: Boolean) {
        if (hasText) {
            if (System.currentTimeMillis() - lastTypingSentMs >= 4_000L) sendTyping(true)
        } else if (typingActive) {
            sendTyping(false)
        }
    }

    private fun sendTyping(typing: Boolean) {
        typingActive = typing
        if (typing) lastTypingSentMs = System.currentTimeMillis()
        val conversationId = conv.id
        Threads.runIo {
            try {
                app.api.setTyping(conversationId, typing)
            } catch (e: Exception) {
                // Typing is best-effort decoration.
            }
        }
    }

    private fun setReplyTarget(msg: UiMessage) {
        replyTarget = msg
        layoutReply.visibility = View.VISIBLE
        val snippet = msg.text?.replace(Regex("\\s+"), " ")?.take(90) ?: ""
        tvReplyQuote.text = "↩ پاسخ به: $snippet"
        etMessage.requestFocus()
    }

    private fun clearReply() {
        replyTarget = null
        layoutReply.visibility = View.GONE
    }

    private fun jumpToServerMessage(serverId: String) {
        val index = adapter.snapshot().indexOfFirst { it.serverId == serverId }
        if (index >= 0) {
            lvMessages.post { lvMessages.setSelection(index) }
            return
        }
        app.repo.allLocalMessages(conv.id) { result ->
            result.onSuccess { all ->
                applyMessages(all, forceScroll = false)
                val target = adapter.snapshot().indexOfFirst { it.serverId == serverId }
                if (target >= 0) {
                    lvMessages.post { lvMessages.setSelection(target) }
                } else {
                    Toast.makeText(this, "پیام اصلی روی این دستگاه نیست", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun showReactionPicker(msg: UiMessage) {
        val emojis = listOf("❤️", "👍", "😂", "😮", "😢", "🙏")
        val labels = (emojis + listOfNotNull(if (msg.myReaction != null) "حذف واکنش من" else null))
            .toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("واکنش")
            .setItems(labels) { _, which ->
                val emoji = if (which < emojis.size) emojis[which] else null
                app.repo.setReaction(conv, msg, emoji) { result ->
                    result.onSuccess { applyMessages(it, forceScroll = false) }
                        .onFailure { e ->
                            Toast.makeText(this, e.message ?: "ثبت واکنش ناموفق بود", Toast.LENGTH_LONG).show()
                        }
                }
            }
            .show()
    }

    private fun showForwardPicker(msg: UiMessage) {
        app.repo.conversations { result ->
            result.onSuccess { items ->
                val targets = items.filter { it.conversation.id != conv.id }
                if (targets.isEmpty()) {
                    Toast.makeText(this, "گفتگوی دیگری برای بازارسال ندارید.", Toast.LENGTH_SHORT).show()
                    return@onSuccess
                }
                AlertDialog.Builder(this)
                    .setTitle("بازارسال به…")
                    .setItems(targets.map { it.title }.toTypedArray()) { _, which ->
                        val target = targets[which]
                        app.repo.forwardMessage(msg, target.conversation) { forward ->
                            forward.onSuccess {
                                Toast.makeText(this, "به «${target.title}» بازارسال شد.", Toast.LENGTH_SHORT).show()
                            }.onFailure { e ->
                                Toast.makeText(this, e.message ?: "بازارسال ناموفق بود", Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                    .setNegativeButton("لغو", null)
                    .show()
            }.onFailure { e ->
                Toast.makeText(this, e.message ?: "خطا در بارگذاری گفتگوها", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun showReportDialog(msg: UiMessage) {
        val reasons = arrayOf("اسپم یا تبلیغات", "آزار و توهین", "محتوای نامناسب", "جعل هویت", "سایر")
        AlertDialog.Builder(this)
            .setTitle("گزارش این کاربر")
            .setItems(reasons) { _, which ->
                Threads.runIo {
                    try {
                        app.api.reportUser(msg.senderId, conv.id, msg.serverId, reasons[which])
                        Threads.runUi {
                            if (!isFinishing) {
                                Toast.makeText(this, "گزارش ثبت شد؛ ممنون از همکاری‌تان.", Toast.LENGTH_LONG).show()
                            }
                        }
                    } catch (e: Exception) {
                        Threads.runUi {
                            if (!isFinishing) {
                                Toast.makeText(this, e.message ?: "ثبت گزارش ناموفق بود", Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                }
            }
            .setNegativeButton("لغو", null)
            .show()
    }

    private fun sendCurrentText() {
        val text = etMessage.text.toString().trim()
        if (text.isEmpty()) return
        etMessage.text.clear()
        sendTyping(false)
        pollIntervalMs = 2_500L
        val ttl = if (currentState == SessionUiState.READY) ttlChoiceSeconds else 0
        val reply = replyTarget
        app.repo.sendText(conv, text, ttl, reply) { result ->
            result.onSuccess { messages ->
                clearReply()
                applyMessages(messages, forceScroll = true)
            }.onFailure { e ->
                etMessage.setText(text)
                Toast.makeText(this, e.message ?: "ارسال ناموفق بود", Toast.LENGTH_LONG).show()
                app.repo.localMessages(conv.id) { local ->
                    local.onSuccess { applyPage(it, forceScroll = true) }
                }
            }
        }
    }

    private fun startSecret() {
        btnBannerAction.isEnabled = false
        app.repo.startSecretChat(conv) { result ->
            btnBannerAction.isEnabled = true
            result.onSuccess { sync ->
                applyPage(PageResult(sync.messages, sync.hasMore), forceScroll = false)
                updateSessionUi(sync.state, sync.notice)
            }.onFailure { e ->
                Toast.makeText(this, e.message ?: "برقراری چت امن ناموفق بود", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun confirmEndSecret() {
        AlertDialog.Builder(this)
            .setTitle("پایان چت امن؟")
            .setMessage("از این به بعد پیام‌های تازه این گفتگو عادی (بدون رمزنگاری سرتاسری) ارسال می‌شوند و مخاطب هم مطلع می‌شود. پیام‌های امن قبلی روی همین گوشی خوانا می‌مانند.")
            .setNegativeButton("لغو", null)
            .setPositiveButton("پایان چت امن") { _, _ ->
                app.repo.endSecretChat(conv) { result ->
                    result.onSuccess { sync ->
                        applyPage(PageResult(sync.messages, sync.hasMore), forceScroll = false)
                        updateSessionUi(sync.state, sync.notice)
                    }.onFailure { e ->
                        Toast.makeText(this, e.message ?: "پایان چت امن ناموفق بود", Toast.LENGTH_LONG).show()
                    }
                }
            }
            .show()
    }

    private fun confirmResetSecret() {
        AlertDialog.Builder(this)
            .setTitle("بازنشانی نشست امن؟")
            .setMessage("نشست فعلی کنار گذاشته و تبادل کلید تازه شروع می‌شود. پیام‌های رمز شده قبلی روی سرور دیگر با کلید تازه خوانا نخواهند بود (نسخه خوانده‌شده روی گوشی می‌ماند). فقط وقتی ادامه دهید که از هویت مخاطب مطمئن هستید.")
            .setNegativeButton("لغو", null)
            .setPositiveButton("بازنشانی") { _, _ ->
                app.repo.resetSecretChat(conv) { result ->
                    result.onSuccess { sync ->
                        applyPage(PageResult(sync.messages, sync.hasMore), forceScroll = false)
                        updateSessionUi(sync.state, sync.notice)
                        Toast.makeText(this, "درخواست نشست امن تازه ارسال شد.", Toast.LENGTH_SHORT).show()
                    }.onFailure { e ->
                        Toast.makeText(this, e.message ?: "بازنشانی ناموفق بود", Toast.LENGTH_LONG).show()
                    }
                }
            }
            .show()
    }

    private fun showSecurityDialog() {
        Threads.runIo {
            val code = try {
                app.repo.safetyCode(conv)
            } catch (e: Exception) {
                null
            }
            val number = try {
                app.repo.safetyNumber(conv)
            } catch (e: Exception) {
                null
            }
            Threads.runUi {
                if (!isFinishing) showSecurityDialogLoaded(code, number)
            }
        }
    }

    private fun showSecurityDialogLoaded(code: String?, number: String?) {
        val baseMessage = if (code != null) {
            "این گفتگو در حالت امن است؛ پیام‌های تازه با رمزنگاری سرتاسری ارسال می‌شوند و کلیدها فقط روی گوشی شما و مخاطب‌اند.\n\n" +
                "کد امنیتی این نشست:\n$code\n\n" +
                "اگر این کد روی گوشی هر دو نفر یکی باشد، هیچ واسطه‌ای بین شما نیست؛ آن را با صدای بلند یا رو‌در‌رو مقایسه کنید."
        } else {
            "این گفتگو فعلاً عادی است و رمزنگاری سرتاسری ندارد (سرور از نظر فنی می‌تواند پیام‌های عادی را بخواند).\n\n" +
                "با فعال‌سازی چت امن، هر دو گوشی یک تبادل کلید امضاشده انجام می‌دهند و از آن به بعد پیام‌ها فقط روی دو گوشی خواناست — درست مثل چت مخفی تلگرام."
        }
        val message = baseMessage + if (number != null) {
            "\n\nشماره امنیتی هویت‌ها (Safety Number):\n$number\n\n" +
                "این عدد ۶۰ رقمی از کلید هویتی هر دو گوشی ساخته می‌شود و تا وقتی هیچ‌کدام اپ را از نو نصب نکرده‌اید ثابت می‌ماند؛ برای اطمینان کامل، آن را رو‌در‌رو یا در یک تماس صوتی مقایسه کنید."
        } else {
            ""
        }
        val builder = AlertDialog.Builder(this)
            .setTitle("امنیت گفتگو 🔒")
            .setMessage(message)
            .setPositiveButton("بستن", null)
        when (currentState) {
            SessionUiState.READY -> {
                builder.setNeutralButton("بازنشانی نشست امن") { _, _ -> confirmResetSecret() }
                builder.setNegativeButton("پایان چت امن") { _, _ -> confirmEndSecret() }
            }
            SessionUiState.PEER_KEY_CHANGED -> {
                builder.setNegativeButton("بازنشانی نشست امن") { _, _ -> confirmResetSecret() }
            }
            SessionUiState.NO_SESSION -> {
                builder.setNegativeButton("🔐 فعال‌سازی چت امن") { _, _ -> startSecret() }
            }
            else -> Unit
        }
        builder.show()
    }

    private fun showPeerProfile() {
        val peer = conv.members.firstOrNull { it.id != app.api.myUserId } ?: return
        Threads.runIo {
            val profile = try {
                app.api.fetchProfile(peer.id)
            } catch (e: Exception) {
                null
            }
            val blocked = try {
                peer.id in app.api.myBlockedIds()
            } catch (e: Exception) {
                iBlockedPeer
            }
            Threads.runUi {
                if (isFinishing) return@runUi
                iBlockedPeer = blocked
                val name = profile?.displayName ?: peer.displayName
                val username = profile?.username ?: peer.username
                val hasKey = (profile?.e2eIdentityKey ?: peer.e2eIdentityKey) != null
                val presence = UiUtil.presenceText(
                    profile?.lastSeenAt ?: peer.lastSeenAt,
                    profile?.lastSeenPublic ?: peer.lastSeenPublic,
                )
                val text = buildString {
                    append("$name\n@$username\n\n")
                    if (presence.isNotEmpty()) append("$presence\n")
                    append(if (hasKey) "🔒 این کاربر از چت امن مموت پشتیبانی می‌کند.\n" else "این کاربر هنوز اپ اندروید مموت را فعال نکرده است.\n")
                    append(if (blocked) "\n🚫 شما این کاربر را مسدود کرده‌اید." else "")
                }
                AlertDialog.Builder(this)
                    .setTitle("پروفایل مخاطب")
                    .setMessage(text)
                    .setPositiveButton("بستن", null)
                    .setNeutralButton("امنیت گفتگو 🔒") { _, _ -> showSecurityDialog() }
                    .setNegativeButton(if (blocked) "رفع مسدودیت" else "مسدود کردن") { _, _ ->
                        setBlocked(!blocked)
                    }
                    .show()
            }
        }
    }

    private fun setBlocked(block: Boolean) {
        val peer = conv.members.firstOrNull { it.id != app.api.myUserId } ?: return
        Threads.runIo {
            try {
                if (block) app.api.blockUser(peer.id) else app.api.unblockUser(peer.id)
                Threads.runUi {
                    if (!isFinishing) {
                        iBlockedPeer = block
                        updateSessionUi(currentState, lastNotice)
                        Toast.makeText(this, if (block) "کاربر مسدود شد." else "مسدودیت رفع شد.", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                Threads.runUi {
                    if (!isFinishing) {
                        Toast.makeText(this, e.message ?: "خطا رخ داد", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    private fun showGroupInfo() {
        val me = app.api.myUserId
        val iAmOwner = conv.members.firstOrNull { it.id == me }?.role == "owner"
        val names = conv.members.joinToString("\n") { m ->
            "• ${m.displayName} (@${m.username})" + if (m.id == me) " — شما" else ""
        }
        val text = "گروه‌ها مثل تلگرام رمزنگاری سرتاسری ندارند؛ پیام گروه با TLS و کنترل دسترسی محافظت می‌شود اما سرور می‌تواند آن را بخواند.\n\nاعضا (${UiUtil.faNum(conv.members.size)}):\n$names"
        val builder = AlertDialog.Builder(this)
            .setTitle(tvChatTitle.text.toString())
            .setMessage(text)
            .setPositiveButton("بستن", null)
            .setNegativeButton("ترک گروه") { _, _ -> confirmLeaveGroup() }
        // Server-side only the owner may add/remove members (migration 6).
        if (iAmOwner) {
            builder.setNeutralButton("افزودن / حذف عضو") { _, _ -> promptManageMembers() }
        }
        builder.show()
    }

    private fun promptManageMembers() {
        val me = app.api.myUserId
        val removable = conv.members.filter { it.id != me && it.role != "owner" }
        val labels = arrayOf("➕ افزودن عضو جدید") +
            removable.map { "❌ حذف ${it.displayName} (@${it.username})" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("مدیریت اعضا")
            .setItems(labels) { _, which ->
                if (which == 0) promptAddMember() else confirmRemoveMember(removable[which - 1])
            }
            .setNegativeButton("انصراف", null)
            .show()
    }

    private fun confirmRemoveMember(member: ir.mamoot.app.net.MemberInfo) {
        AlertDialog.Builder(this)
            .setTitle("حذف از گروه؟")
            .setMessage("«${member.displayName}» از گروه حذف می‌شود و دیگر به پیام‌ها دسترسی ندارد.")
            .setPositiveButton("حذف") { _, _ ->
                Threads.runIo {
                    val result = runCatching { app.api.removeGroupMember(conv.id, member.id) }
                    Threads.runUi {
                        if (isFinishing) return@runUi
                        result.onSuccess {
                            Toast.makeText(this, "عضو حذف شد", Toast.LENGTH_SHORT).show()
                            enrichConversationFromServer()
                        }.onFailure { e ->
                            Toast.makeText(this, e.message ?: "خطا در حذف عضو", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
            .setNegativeButton("انصراف", null)
            .show()
    }

    private fun promptAddMember() {
        promptText("افزودن عضو", "", "نام کاربری یا بخشی از نام") { query ->
            if (query.trim().length < 2) {
                Toast.makeText(this, "عبارت جستجو خیلی کوتاه است.", Toast.LENGTH_SHORT).show()
                return@promptText
            }
            Threads.runIo {
                try {
                    val currentIds = conv.members.map { it.id }.toSet()
                    val results = app.api.searchUsers(query.trim()).filter { it.id !in currentIds }
                    Threads.runUi {
                        if (isFinishing) return@runUi
                        if (results.isEmpty()) {
                            Toast.makeText(this, "کاربر تازه‌ای پیدا نشد.", Toast.LENGTH_SHORT).show()
                            return@runUi
                        }
                        val labels = results.map { "${it.displayName} (@${it.username})" }.toTypedArray()
                        AlertDialog.Builder(this)
                            .setTitle("انتخاب عضو")
                            .setItems(labels) { _, which -> addMember(results[which].id, results[which].displayName) }
                            .setNegativeButton("لغو", null)
                            .show()
                    }
                } catch (e: Exception) {
                    Threads.runUi {
                        if (!isFinishing) Toast.makeText(this, e.message ?: "خطا در جستجو", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    private fun addMember(userId: String, displayName: String) {
        Threads.runIo {
            try {
                app.api.addGroupMembers(conv.id, listOf(userId))
                Threads.runUi {
                    if (!isFinishing) {
                        Toast.makeText(this, "«$displayName» به گروه اضافه شد.", Toast.LENGTH_SHORT).show()
                        enrichConversationFromServer()
                    }
                }
            } catch (e: Exception) {
                Threads.runUi {
                    if (!isFinishing) Toast.makeText(this, e.message ?: "افزودن عضو ناموفق بود", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun confirmLeaveGroup() {
        AlertDialog.Builder(this)
            .setTitle("ترک گروه؟")
            .setMessage("دیگر پیام‌های این گروه را دریافت نمی‌کنید.")
            .setNegativeButton("لغو", null)
            .setPositiveButton("ترک گروه") { _, _ ->
                Threads.runIo {
                    try {
                        app.api.leaveConversation(conv.id)
                        Threads.runUi {
                            Toast.makeText(this, "گروه را ترک کردید.", Toast.LENGTH_SHORT).show()
                            finish()
                        }
                    } catch (e: Exception) {
                        Threads.runUi {
                            if (!isFinishing) Toast.makeText(this, e.message ?: "ترک گروه ناموفق بود", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
            .show()
    }

    private fun showTtlPicker() {
        val options = listOf(
            0 to "خاموش",
            10 to "۱۰ ثانیه بعد از خواندن",
            60 to "۱ دقیقه بعد از خواندن",
            3600 to "۱ ساعت بعد از خواندن",
            86400 to "۱ روز بعد از خواندن",
            604800 to "۱ هفته بعد از خواندن",
        )
        val labels = options.map { it.second }.toTypedArray()
        val checked = options.indexOfFirst { it.first == ttlChoiceSeconds }.coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle("حذف خودکار پیام‌های امن ⏱")
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                ttlChoiceSeconds = options[which].first
                app.repo.setTtlChoice(conv.id, ttlChoiceSeconds)
                updateTtlButton()
                dialog.dismiss()
                Toast.makeText(
                    this,
                    if (ttlChoiceSeconds == 0) "حذف خودکار خاموش شد."
                    else "پیام‌های امن تازه ${options[which].second} پاک می‌شوند.",
                    Toast.LENGTH_SHORT,
                ).show()
            }
            .setNegativeButton("لغو", null)
            .show()
    }

    private fun updateTtlButton() {
        btnTtl.setTextColor(
            getColor(if (ttlChoiceSeconds > 0) R.color.brand else R.color.text2),
        )
    }

    private fun toggleSearch(show: Boolean) {
        layoutSearch.visibility = if (show) View.VISIBLE else View.GONE
        if (show) {
            etChatSearch.requestFocus()
        } else {
            etChatSearch.text.clear()
            searchMatchIds = emptyList()
            searchIndex = -1
        }
    }

    private fun runSearch(query: String, seq: Int) {
        app.repo.searchMessages(conv.id, query) { result ->
            if (seq != searchSeq || isFinishing) return@searchMessages
            result.onSuccess { matches ->
                searchMatchIds = matches.map { it.localId }
                searchIndex = -1
                if (matches.isEmpty()) {
                    Toast.makeText(this, "چیزی پیدا نشد.", Toast.LENGTH_SHORT).show()
                } else {
                    ensureAllLoadedForSearch(matches.size) { jumpToMatch(+1) }
                }
            }
        }
    }

    private fun ensureAllLoadedForSearch(matchCount: Int, then: () -> Unit) {
        if (!hasMoreOlder) {
            then()
            return
        }
        Toast.makeText(this, "${UiUtil.faNum(matchCount)} نتیجه — در حال بارگذاری تاریخچه…", Toast.LENGTH_SHORT).show()
        app.repo.allLocalMessages(conv.id) { result ->
            if (isFinishing) return@allLocalMessages
            result.onSuccess { all ->
                adapter.submit(all)
                hasMoreOlder = false
                btnLoadOlder.visibility = View.GONE
                then()
            }
        }
    }

    private fun jumpToMatch(direction: Int) {
        if (searchMatchIds.isEmpty()) return
        searchIndex = ((searchIndex + direction) % searchMatchIds.size + searchMatchIds.size) % searchMatchIds.size
        val targetId = searchMatchIds[searchIndex]
        val items = adapter.snapshot()
        val pos = items.indexOfFirst { it.localId == targetId }
        if (pos >= 0) {
            lvMessages.post { lvMessages.setSelection(pos) }
            Toast.makeText(
                this,
                "نتیجه ${UiUtil.faNum(searchIndex + 1)} از ${UiUtil.faNum(searchMatchIds.size)}",
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    private fun showMessageActions(msg: UiMessage) {
        if (msg.system) return
        val options = mutableListOf<String>()
        if (msg.status == "failed") options.add("تلاش دوباره")
        val interactive = !msg.deleted && !msg.expired && msg.serverId != null
        if (interactive && msg.text != null) options.add("↩ پاسخ")
        if (interactive && (!msg.encrypted || currentState == SessionUiState.READY)) options.add("واکنش ❤")
        if (!msg.deleted && !msg.expired && !msg.text.isNullOrEmpty()) options.add("↪ بازارسال")
        if (!msg.mine && msg.senderId.isNotEmpty()) options.add("گزارش کاربر 🚩")
        if (msg.mine && !msg.deleted && !msg.expired && msg.serverId != null &&
            (!msg.encrypted || currentState == SessionUiState.READY)
        ) {
            options.add("ویرایش")
        }
        if (msg.text != null && !msg.deleted && !msg.expired) options.add("کپی متن")
        if (msg.mine && msg.serverId != null && !msg.deleted && !msg.expired) options.add("حذف برای همه")
        if (msg.mine && msg.serverId == null) options.add("حذف از این دستگاه")
        if (options.isEmpty()) return

        AlertDialog.Builder(this)
            .setItems(options.toTypedArray()) { _, which ->
                when (options[which]) {
                    "↩ پاسخ" -> setReplyTarget(msg)
                    "واکنش ❤" -> showReactionPicker(msg)
                    "↪ بازارسال" -> showForwardPicker(msg)
                    "گزارش کاربر 🚩" -> showReportDialog(msg)
                    "کپی متن" -> {
                        val clipboard = getSystemService(ClipboardManager::class.java)
                        clipboard?.setPrimaryClip(ClipData.newPlainText("mamoot", msg.text ?: ""))
                        Toast.makeText(this, "کپی شد.", Toast.LENGTH_SHORT).show()
                    }
                    "تلاش دوباره" -> {
                        app.repo.retryMessage(msg.localId) { result ->
                            result.onSuccess { applyMessages(it, forceScroll = true) }
                                .onFailure { e ->
                                    Toast.makeText(this, e.message ?: "ارسال ناموفق بود", Toast.LENGTH_LONG).show()
                                    app.repo.localMessages(conv.id) { local ->
                                        local.onSuccess { applyPage(it, forceScroll = true) }
                                    }
                                }
                        }
                    }
                    "ویرایش" -> {
                        promptText("ویرایش پیام", msg.text ?: "", "متن تازه") { newText ->
                            app.repo.editMessage(conv, msg, newText) { result ->
                                result.onSuccess { applyMessages(it, forceScroll = false) }
                                    .onFailure { e ->
                                        Toast.makeText(this, e.message ?: "ویرایش ناموفق بود", Toast.LENGTH_LONG).show()
                                    }
                            }
                        }
                    }
                    "حذف برای همه", "حذف از این دستگاه" -> {
                        app.repo.deleteMessage(msg) { result ->
                            result.onSuccess {
                                app.repo.localMessages(conv.id) { local ->
                                    local.onSuccess { applyPage(it, forceScroll = false) }
                                }
                            }.onFailure { e ->
                                Toast.makeText(this, e.message ?: "حذف ناموفق بود", Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                }
            }
            .show()
    }

    private fun promptText(title: String, initial: String, hint: String, onOk: (String) -> Unit) {
        val input = EditText(this).apply {
            setText(initial)
            this.hint = hint
            setSelection(text?.length ?: 0)
        }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(input)
            .setNegativeButton("لغو", null)
            .setPositiveButton("تأیید") { _, _ -> onOk(input.text.toString()) }
            .show()
    }

    private fun conversationFromIntent(): Conversation {
        val convId = intent.getStringExtra(EXTRA_CONV_ID) ?: ""
        val kind = intent.getStringExtra(EXTRA_KIND) ?: "dm"
        val title = intent.getStringExtra(EXTRA_TITLE)
        val peerId = intent.getStringExtra(EXTRA_PEER_ID) ?: ""
        val members = mutableListOf<MemberInfo>()
        app.api.myUserId?.let { me ->
            members.add(MemberInfo(me, "", "", "", null))
        }
        if (peerId.isNotEmpty()) {
            members.add(
                MemberInfo(
                    id = peerId,
                    username = intent.getStringExtra(EXTRA_PEER_USERNAME) ?: "",
                    displayName = intent.getStringExtra(EXTRA_PEER_NAME) ?: "",
                    lastReadAt = "",
                    e2eIdentityKey = intent.getStringExtra(EXTRA_PEER_KEY),
                ),
            )
        }
        return Conversation(convId, kind, title, "", "", members, null, 0)
    }

    companion object {
        private const val EXTRA_CONV_ID = "conv_id"
        private const val EXTRA_KIND = "conv_kind"
        private const val EXTRA_TITLE = "conv_title"
        private const val EXTRA_PEER_ID = "peer_id"
        private const val EXTRA_PEER_NAME = "peer_name"
        private const val EXTRA_PEER_USERNAME = "peer_username"
        private const val EXTRA_PEER_KEY = "peer_key"
        private val SEARCH_TOKEN = Any()

        fun intentFor(context: Context, item: UiConversation): Intent {
            return Intent(context, ChatActivity::class.java).apply {
                putExtra(EXTRA_CONV_ID, item.conversation.id)
                putExtra(EXTRA_KIND, item.conversation.kind)
                putExtra(EXTRA_TITLE, item.title)
                putExtra(EXTRA_PEER_ID, item.peer?.id ?: "")
                putExtra(EXTRA_PEER_NAME, item.peer?.displayName ?: "")
                putExtra(EXTRA_PEER_USERNAME, item.peer?.username ?: "")
                putExtra(EXTRA_PEER_KEY, item.peer?.e2eIdentityKey)
            }
        }

        fun intentForDm(
            context: Context,
            conversationId: String,
            peerId: String,
            peerName: String,
            peerUsername: String,
            peerKey: String?,
        ): Intent {
            return Intent(context, ChatActivity::class.java).apply {
                putExtra(EXTRA_CONV_ID, conversationId)
                putExtra(EXTRA_KIND, "dm")
                putExtra(EXTRA_TITLE, peerName)
                putExtra(EXTRA_PEER_ID, peerId)
                putExtra(EXTRA_PEER_NAME, peerName)
                putExtra(EXTRA_PEER_USERNAME, peerUsername)
                putExtra(EXTRA_PEER_KEY, peerKey)
            }
        }

        fun intentForGroup(context: Context, conversationId: String, title: String): Intent {
            return Intent(context, ChatActivity::class.java).apply {
                putExtra(EXTRA_CONV_ID, conversationId)
                putExtra(EXTRA_KIND, "group")
                putExtra(EXTRA_TITLE, title)
            }
        }
    }
}
