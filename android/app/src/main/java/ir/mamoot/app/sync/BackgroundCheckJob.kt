package ir.mamoot.app.sync

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import ir.mamoot.app.MamootApp
import ir.mamoot.app.R
import ir.mamoot.app.Threads
import ir.mamoot.app.ui.MainActivity
import java.util.concurrent.TimeUnit

/**
 * Play-Services-free background checks (FCM is unreliable/unavailable for many
 * users in Iran). Runs at most every 15 minutes, performs ONE small request,
 * and posts a content-free notification. Decrypted text is never shown in
 * notifications.
 */
class BackgroundCheckJob : JobService() {
    override fun onStartJob(params: JobParameters?): Boolean {
        val app = application as? MamootApp
        if (app == null || app.api.session == null) {
            jobFinished(params, false)
            return false
        }
        Threads.runIo {
            try {
                val total = app.api.listConversations().sumOf { it.unreadCount }
                val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                val lastNotified = prefs.getInt(KEY_LAST_UNREAD, 0)
                if (total > lastNotified && total > 0) {
                    showNotification()
                }
                prefs.edit().putInt(KEY_LAST_UNREAD, total).apply()
            } catch (e: Exception) {
                // Offline etc.: try again next period.
            } finally {
                jobFinished(params, false)
            }
        }
        return true // work continues on our IO thread
    }

    override fun onStopJob(params: JobParameters?): Boolean = true

    private fun showNotification() {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pending = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = Notification.Builder(this, MamootApp.CHANNEL_MESSAGES)
            .setSmallIcon(R.drawable.ic_stat_mamoot)
            .setContentTitle("مموت")
            .setContentText("پیام جدید دارید")
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_MESSAGE)
            .build()
        val manager = getSystemService(NotificationManager::class.java)
        manager?.notify(1001, notification)
    }

    companion object {
        private const val JOB_ID = 4201
        private const val PREFS = "mamoot_bg"
        private const val KEY_LAST_UNREAD = "last_unread"

        fun schedule(context: Context) {
            val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
            if (scheduler.allPendingJobs.any { it.id == JOB_ID }) return
            val job = JobInfo.Builder(JOB_ID, ComponentName(context, BackgroundCheckJob::class.java))
                .setPeriodic(TimeUnit.MINUTES.toMillis(15))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPersisted(true)
                .build()
            scheduler.schedule(job)
        }
    }
}
