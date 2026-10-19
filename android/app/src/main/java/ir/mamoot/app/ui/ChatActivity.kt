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
    private lateinit var tvChatTitle: TextView
    private lateinit var tvChatStatus: TextView
    private lateinit var layoutBanner: LinearLayout
    private lateinit var tvBannerText: TextView
    private lateinit var btnBannerAction: Button

    private val handler = Handler(Looper.getMainLooper())
    private var syncing = false
    private var pollIntervalMs = 2_500L
    private var lastMessageCount = -1
    private var currentState: SessionUiState = SessionUiState.NO_SESSION
    private var firstLoadDone = false

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
        tvChatTitle = findViewById(R.id.tvChatTitle)
        tvChatStatus = findViewById(R.id.tvChatStatus)
        layoutBanner = findViewById(R.id.layoutBanner)
        tvBannerText = findViewById(R.id.tvBannerText)
        btnBannerAction = findViewById(R.id.btnBannerAction)

        val avatar = findViewById<TextView>(R.id.tvChatAvatar)
        val peer = conv.members.firstOrNull { it.id != app.api.myUserId }
        val title = intent.getStringExtra(EXTRA_TITLE) ?: peer?.displayName ?: conv.title ?: "گفتگو"
        tvChatTitle.text = title
        avatar.text = UiUtil.initials(title)
        avatar.background.mutate().setTint(UiUtil.avatarColor(peer?.id ?: conv.id))
        tvChatStatus.text = "…"

        adapter = MessageAdapter(this)
        adapter.isGroup = conv.kind == "group"
        lvMessages.adapter = adapter
        lvMessages.setOnItemLongClickListener { _, _, position, _ ->
            showMessageActions(adapter.getItem(position))
            true
        }

        findViewById<Button>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<Button>(R.id.btnSecurity).setOnClickListener { showSecurityDialog() }
        btnSend.setOnClickListener { sendCurrentText() }

        app.repo.localMessages(conv.id) { result ->
            result.onSuccess { messages ->
                applyMessages(messages, forceScroll = true)
                firstLoadDone = true
            }
        }
        enrichConversationFromServer()
    }

    override fun onResume() {
        super.onResume()
        pollIntervalMs = 2_500L
        doSync()
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(pollRunnable)
    }

    private fun enrichConversationFromServer() {
        Threads.runIo {
            try {
                val fresh = app.api.listConversations().firstOrNull { it.id == conv.id }
                if (fresh != null) {
                    Threads.runUi {
                        conv = fresh
                        adapter.memberNames = fresh.members.associate { it.id to it.displayName }
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
            result.onSuccess { sync ->
                applyMessages(sync.messages, forceScroll = false)
                adapter.peerLastReadAt = sync.peer?.lastReadAt ?: adapter.peerLastReadAt
                updateSessionUi(sync.state, sync.notice)
                val grew = sync.messages.size != lastMessageCount
                pollIntervalMs = if (grew) 2_500L else minOf(pollIntervalMs * 3 / 2, 10_000L)
            }.onFailure {
                tvChatStatus.text = "در انتظار اتصال…"
                pollIntervalMs = 6_000L
            }
            scheduleNextPoll()
        }
    }

    private fun scheduleNextPoll() {
        handler.removeCallbacks(pollRunnable)
        handler.postDelayed(pollRunnable, pollIntervalMs)
    }

    private fun applyMessages(messages: List<UiMessage>, forceScroll: Boolean) {
        val wasAtBottom = lvMessages.lastVisiblePosition >= adapter.count - 2
        adapter.submit(messages)
        lastMessageCount = messages.size
        if (messages.isNotEmpty() && (forceScroll || wasAtBottom || !firstLoadDone)) {
            lvMessages.post { lvMessages.setSelection(adapter.count - 1) }
        }
    }

    private fun updateSessionUi(state: SessionUiState, notice: String?) {
        currentState = state
        val canSend = state == SessionUiState.READY || state == SessionUiState.GROUP_PLAIN
        etMessage.isEnabled = canSend
        btnSend.isEnabled = canSend

        tvChatStatus.text = when (state) {
            SessionUiState.READY -> "🔒 رمزنگاری سرتاسری فعال"
            SessionUiState.GROUP_PLAIN -> "گروه — بدون رمزنگاری سرتاسری"
            SessionUiState.WAITING_PEER -> "در انتظار پذیرش چت امن…"
            SessionUiState.NO_SESSION -> "چت امن برقرار نیست"
            SessionUiState.PEER_NO_KEY -> "مخاطب اپ مموت را فعال نکرده است"
            SessionUiState.PEER_KEY_CHANGED -> "⚠️ کلید مخاطب عوض شده است"
        }

        btnBannerAction.setOnClickListener(null)
        when {
            notice != null -> {
                layoutBanner.visibility = View.VISIBLE
                tvBannerText.text = notice
                btnBannerAction.visibility = View.GONE
            }
            state == SessionUiState.NO_SESSION -> {
                layoutBanner.visibility = View.VISIBLE
                tvBannerText.text = "برای این گفتگو هنوز نشست امن ساخته نشده است."
                btnBannerAction.visibility = View.VISIBLE
                btnBannerAction.text = "شروع چت امن"
                btnBannerAction.setOnClickListener { startSecret() }
            }
            state == SessionUiState.WAITING_PEER -> {
                layoutBanner.visibility = View.VISIBLE
                tvBannerText.text = "درخواست چت امن ارسال شد؛ وقتی مخاطب اپ را باز کند، نشست خودکار برقرار می‌شود."
                btnBannerAction.visibility = View.GONE
            }
            state == SessionUiState.PEER_NO_KEY -> {
                layoutBanner.visibility = View.VISIBLE
                tvBannerText.text = "این مخاطب هنوز اپ اندروید مموت را نصب و فعال نکرده است؛ چت امن ممکن نیست."
                btnBannerAction.visibility = View.GONE
            }
            state == SessionUiState.PEER_KEY_CHANGED -> {
                layoutBanner.visibility = View.VISIBLE
                tvBannerText.text = "کلید امنیتی مخاطب عوض شده است (مثلاً نصب دوباره). فقط اگر مطمئن هستید بازنشانی کنید."
                btnBannerAction.visibility = View.VISIBLE
                btnBannerAction.text = "بازنشانی نشست امن"
                btnBannerAction.setOnClickListener { confirmResetSecret() }
            }
            else -> layoutBanner.visibility = View.GONE
        }
    }

    private fun sendCurrentText() {
        val text = etMessage.text.toString().trim()
        if (text.isEmpty()) return
        etMessage.text.clear()
        pollIntervalMs = 2_500L
        app.repo.sendText(conv, text) { result ->
            result.onSuccess { messages ->
                applyMessages(messages, forceScroll = true)
            }.onFailure { e ->
                etMessage.setText(text)
                Toast.makeText(this, e.message ?: "ارسال ناموفق بود", Toast.LENGTH_LONG).show()
                // Reload local state so the failed bubble (with retry) is visible.
                app.repo.localMessages(conv.id) { local ->
                    local.onSuccess { applyMessages(it, forceScroll = true) }
                }
            }
        }
    }

    private fun startSecret() {
        btnBannerAction.isEnabled = false
        app.repo.startSecretChat(conv) { result ->
            btnBannerAction.isEnabled = true
            result.onSuccess { sync ->
                applyMessages(sync.messages, forceScroll = false)
                updateSessionUi(sync.state, sync.notice)
            }.onFailure { e ->
                Toast.makeText(this, e.message ?: "برقراری چت امن ناموفق بود", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun confirmResetSecret() {
        AlertDialog.Builder(this)
            .setTitle("بازنشانی نشست امن؟")
            .setMessage("نشست فعلی کنار گذاشته و تبادل کلید تازه شروع می‌شود. پیام‌های رمز شده قبلی روی سرور دیگر با کلید تازه خوانا نخواهند بود (نسخه خوانده‌شده روی گوشی می‌ماند). فقط وقتی ادامه دهید که از هویت مخاطب مطمئن هستید.")
            .setNegativeButton("لغو", null)
            .setPositiveButton("بازنشانی") { _, _ ->
                app.repo.resetSecretChat(conv) { result ->
                    result.onSuccess { sync ->
                        applyMessages(sync.messages, forceScroll = false)
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
        if (conv.kind == "group") {
            AlertDialog.Builder(this)
                .setTitle("امنیت این گروه")
                .setMessage("گروه‌ها مثل تلگرام رمزنگاری سرتاسری ندارند؛ پیام گروه روی سرور با TLS و کنترل دسترسی محافظت می‌شود، اما سرور می‌تواند آن را بخواند. برای محرمانگی کامل از چت خصوصی امن استفاده کنید.")
                .setPositiveButton("بستن", null)
                .show()
            return
        }
        val code = try {
            app.repo.safetyCode(conv)
        } catch (e: Exception) {
            null
        }
        val message = if (code != null) {
            "این گفتگو رمزنگاری سرتاسری دارد؛ کلیدها فقط روی گوشی شما و مخاطب است.\n\n" +
                "کد امنیتی این نشست:\n$code\n\n" +
                "اگر این کد در گوشی هر دو نفر یکی باشد، هیچ واسطه‌ای بین شما نیست. (مثل اثر انگشت کلید در چت مخفی تلگرام)"
        } else {
            "برای این گفتگو هنوز نشست امن فعالی وجود ندارد، پس کد امنیتی هم نیست. از دکمه «شروع چت امن» استفاده کنید."
        }
        val builder = AlertDialog.Builder(this)
            .setTitle("امنیت گفتگو 🔒")
            .setMessage(message)
            .setPositiveButton("بستن", null)
        if (currentState == SessionUiState.READY || currentState == SessionUiState.PEER_KEY_CHANGED) {
            builder.setNegativeButton("بازنشانی نشست امن") { _, _ -> confirmResetSecret() }
        }
        builder.show()
    }

    private fun showMessageActions(msg: UiMessage) {
        val options = mutableListOf<String>()
        if (msg.status == "failed") options.add("تلاش دوباره")
        if (msg.text != null && !msg.deleted) options.add("کپی متن")
        if (msg.mine && msg.serverId != null && !msg.deleted) options.add("حذف برای همه")
        if (options.isEmpty()) return

        AlertDialog.Builder(this)
            .setItems(options.toTypedArray()) { _, which ->
                when (options[which]) {
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
                                        local.onSuccess { applyMessages(it, forceScroll = true) }
                                    }
                                }
                        }
                    }
                    "حذف برای همه" -> {
                        app.repo.deleteMessage(msg) { result ->
                            result.onSuccess {
                                app.repo.localMessages(conv.id) { local ->
                                    local.onSuccess { applyMessages(it, forceScroll = false) }
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
    }
}
