package ir.mamoot.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Handler
import android.os.Looper
import ir.mamoot.app.crypto.IdentityManager
import ir.mamoot.app.net.SupabaseApi
import ir.mamoot.app.storage.LocalDb
import ir.mamoot.app.storage.SecureStore
import ir.mamoot.app.sync.BackgroundCheckJob
import ir.mamoot.app.sync.ChatRepository
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

    override fun onCreate() {
        super.onCreate()
        secure = SecureStore(this)
        api = SupabaseApi(secure)
        db = LocalDb(this, secure)
        identity = IdentityManager(secure)
        repo = ChatRepository(api, db, secure, identity)
        createNotificationChannel()
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
    }
}
