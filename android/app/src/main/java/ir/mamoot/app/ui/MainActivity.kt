package ir.mamoot.app.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import ir.mamoot.app.MamootApp
import ir.mamoot.app.R

class MainActivity : Activity() {
    private val app: MamootApp get() = application as MamootApp

    private lateinit var adapter: ConversationAdapter
    private lateinit var tvEmpty: View
    private lateinit var lvConversations: ListView
    private val handler = Handler(Looper.getMainLooper())
    private var errorShown = false
    private var refreshing = false
    private var resumed = false
    private var pollFailures = 0
    private var pollDelayMs = POLL_MS
    private var pendingOpenConvId: String? = null

    private val pollRunnable = object : Runnable {
        override fun run() {
            refresh()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.AppTheme)
        super.onCreate(savedInstanceState)
        if (app.api.session == null) {
            startActivity(Intent(this, AuthActivity::class.java))
            finish()
            return
        }
        setContentView(R.layout.activity_main)
        // Audit F5: if the stored identity key can no longer be read, stop
        // at the recovery screen. ensureIdentityUploaded below can never
        // auto-publish a fresh identity in that state (it throws instead).
        ir.mamoot.app.Threads.runIo {
            if (app.repo.identityNeedsRecovery()) {
                ir.mamoot.app.Threads.runUi {
                    if (!isFinishing && !isDestroyed) {
                        startActivity(Intent(this, RecoveryActivity::class.java))
                        finish()
                    }
                }
            }
        }
        app.scheduleBackgroundChecks()
        maybeRequestNotificationPermission()

        adapter = ConversationAdapter(this)
        tvEmpty = findViewById(R.id.tvEmpty)
        lvConversations = findViewById(R.id.lvConversations)
        lvConversations.adapter = adapter
        lvConversations.setOnItemClickListener { _, _, position, _ ->
            val item = adapter.getItem(position)
            startActivity(ChatActivity.intentFor(this, item))
        }
        findViewById<View>(R.id.btnNewChat).setOnClickListener {
            startActivity(Intent(this, NewChatActivity::class.java))
        }
        findViewById<View>(R.id.btnSettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        app.repo.ensureIdentityUploaded { /* publish/refresh identity key silently */ }
        pendingOpenConvId = intent?.getStringExtra(MamootApp.EXTRA_OPEN_CONV)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingOpenConvId = intent?.getStringExtra(MamootApp.EXTRA_OPEN_CONV)
        refresh()
    }

    override fun onResume() {
        super.onResume()
        if (app.api.session == null) return
        resumed = true
        pollFailures = 0
        pollDelayMs = POLL_MS
        getSystemService(android.app.NotificationManager::class.java)?.cancel(MamootApp.NOTIFICATION_ID)
        refresh()
    }

    override fun onPause() {
        super.onPause()
        resumed = false
        handler.removeCallbacks(pollRunnable)
    }

    private fun scheduleNextPoll() {
        if (!resumed) return
        handler.removeCallbacks(pollRunnable)
        handler.postDelayed(pollRunnable, pollDelayMs)
    }

    private fun refresh() {
        if (refreshing) return
        refreshing = true
        app.repo.conversations { result ->
            refreshing = false
            result.onSuccess { items ->
                pollFailures = 0
                pollDelayMs = POLL_MS
                adapter.submit(items)
                // Re-baseline the background notifier so it only fires for
                // messages that arrive after this visit.
                getSharedPreferences("mamoot_bg", MODE_PRIVATE).edit()
                    .putInt("last_unread", items.sumOf { it.conversation.unreadCount }).apply()
                pendingOpenConvId?.let { targetId ->
                    items.firstOrNull { it.conversation.id == targetId }?.let { item ->
                        pendingOpenConvId = null
                        startActivity(ChatActivity.intentFor(this, item))
                    }
                }
                tvEmpty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
                lvConversations.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
                errorShown = false
            }.onFailure { e ->
                pollFailures++
                pollDelayMs = minOf(60_000L, POLL_MS shl minOf(pollFailures, 3))
                if (!errorShown) {
                    errorShown = true
                    Toast.makeText(this, e.message ?: "خطا در دریافت گفتگوها", Toast.LENGTH_SHORT).show()
                }
                if (adapter.count == 0) {
                    tvEmpty.visibility = View.VISIBLE
                    lvConversations.visibility = View.GONE
                }
            }
            scheduleNextPoll()
        }
    }

    private fun maybeRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1007)
        }
    }

    private companion object {
        const val POLL_MS = 12_000L
    }
}
