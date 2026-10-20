package ir.mamoot.app

import android.app.Activity
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.net.ConnectivityManager
import android.net.Network
import android.view.WindowManager
import ir.mamoot.app.crypto.IdentityManager
import ir.mamoot.app.net.SupabaseApi
import ir.mamoot.app.storage.LocalDb
import ir.mamoot.app.storage.SecureStore
import ir.mamoot.app.security.AppLock
import ir.mamoot.app.sync.BackgroundCheckJob
import ir.mamoot.app.sync.ChatRepository
import ir.mamoot.app.ui.LockActivity
import java.util.concurrent.Executors

object Threads {
    private val ioExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "mamoot-io").apply { isDaemon = true }
    }
    private val mainHandler = Handler(Looper.getMainLooper())

    fun runIo(block: () -> Unit) {
        ioExecutor.execute { block() }
    }

    fun runUi(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post { block() }
    }
}

class MamootApp : Application() {
    lateinit var secure: SecureStore
        private set
    lateinit var api: SupabaseApi
        private set
    lateinit var db: LocalDb
        private set
    lateinit var identity: IdentityManager
        private set
    lateinit var repo: ChatRepository
        private set
    lateinit var appLock: AppLock
        private set

    /** True after the user passes the app lock in this process run. */
    @Volatile
    var unlocked: Boolean = false

    private var startedActivities = 0
    private var backgroundAtMs = 0L

    override fun onCreate() {
        super.onCreate()
        secure = SecureStore(this)
        api = SupabaseApi(secure)
        db = LocalDb(this, secure)
        identity = IdentityManager(secure)
        repo = ChatRepository(api, db, secure, identity)
        appLock = AppLock(secure)
        createNotificationChannel()
        registerSecurityCallbacks()
        registerConnectivityCallback()
    }

    /**
     * When connectivity returns, flush the offline outbox immediately instead
     * of waiting for the next poll — messages feel instant again without any
     * persistent socket or Play Services.
     */
    private fun registerConnectivityCallback() {
        try {
            val cm = getSystemService(ConnectivityManager::class.java) ?: return
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    repo.flushAllOutboxAsync()
                }
            })
        } catch (e: Exception) {
            // Best effort only; periodic sync still covers delivery.
        }
    }

    /**
     * Applies FLAG_SECURE everywhere (no screenshots / recent-apps previews
     * of chats) and gates the UI behind the optional app lock.
     */
    private fun registerSecurityCallbacks() {
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                activity.window.setFlags(
                    WindowManager.LayoutParams.FLAG_SECURE,
                    WindowManager.LayoutParams.FLAG_SECURE,
                )
            }

            override fun onActivityStarted(activity: Activity) {
                val fromBackground = startedActivities == 0
                startedActivities++
                if (fromBackground && activity !is LockActivity && shouldLockNow()) {
                    activity.startActivity(Intent(activity, LockActivity::class.java))
                }
            }

            override fun onActivityStopped(activity: Activity) {
                startedActivities--
                if (startedActivities == 0) backgroundAtMs = System.currentTimeMillis()
            }

            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }

    private fun shouldLockNow(): Boolean {
        if (!appLock.isPinSet()) return false
        if (api.session == null) return false
        if (!unlocked) return true
        val auto = appLock.autoLockSeconds
        if (auto < 0) return false // only on cold start
        val elapsed = System.currentTimeMillis() - backgroundAtMs
        if (backgroundAtMs > 0 && elapsed >= auto * 1000L) {
            unlocked = false
            return true
        }
        return false
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_MESSAGES,
            "پیام‌های مموت",
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = "اعلان پیام جدید (بدون نمایش متن پیام)"
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager?.createNotificationChannel(channel)
    }

    fun scheduleBackgroundChecks() {
        BackgroundCheckJob.schedule(this)
    }

    fun cancelBackgroundChecks() {
        BackgroundCheckJob.cancel(this)
    }

    companion object {
        const val CHANNEL_MESSAGES = "mamoot_messages"
        const val EXTRA_OPEN_CONV = "open_conv"
        const val NOTIFICATION_ID = 1001
    }
}
