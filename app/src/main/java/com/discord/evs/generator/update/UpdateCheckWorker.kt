package com.discord.evs.generator.update

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.discord.evs.generator.MainActivity
import com.discord.evs.generator.R
import com.discord.evs.generator.data.Prefs
import java.util.concurrent.TimeUnit

/**
 * Background poll of GitHub Releases.
 * GitHub cannot push to phones; this worker is the phone-side half:
 * periodically hits /releases/latest and posts a system notification when new.
 *
 * For near-instant delivery, also host the GitHub Action → ntfy topic path
 * (see scripts/release/github-update-notify.yml). Worker remains the fallback.
 */
class UpdateCheckWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        return try {
            val prefs = Prefs(applicationContext)
            if (!prefs.notifyUpdates) return Result.success()

            val info = UpdateChecker.checkForUpdate(applicationContext) ?: return Result.success()

            val notifyKey = info.tagName + ":" + info.apkSizeBytes
            val lastKey = prefs.lastNotifiedUpdateTag + ":" + prefs.lastNotifiedUpdateSize
            if (notifyKey == lastKey) return Result.success()

            postNotification(applicationContext, info)
            prefs.lastNotifiedUpdateTag = info.tagName
            prefs.lastNotifiedUpdateSize = info.apkSizeBytes
            Result.success()
        } catch (_: Exception) {
            Result.retry()
        }
    }

    companion object {
        const val UNIQUE_NAME = "evs_update_check_periodic"
        private const val CHANNEL = "evs_update_channel"
        private const val NOTIFY_ID = 5100

        /** Schedule every 6 hours (WorkManager minimum for Periodic is 15 min; 6h is sane). */
        fun schedule(context: Context) {
            val req = PeriodicWorkRequestBuilder<UpdateCheckWorker>(6, TimeUnit.HOURS)
                .addTag(UNIQUE_NAME)
                .build()
            WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
                UNIQUE_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                req
            )
        }

        fun scheduleNowAndPeriodic(context: Context) {
            schedule(context)
            // One-shot also runs soon so first install doesn't wait 6h
            val once = androidx.work.OneTimeWorkRequestBuilder<UpdateCheckWorker>()
                .addTag(UNIQUE_NAME + "_once")
                .build()
            WorkManager.getInstance(context.applicationContext).enqueue(once)
        }

        private fun postNotification(context: Context, info: UpdateInfo) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                    val ch = NotificationChannel(
                        CHANNEL,
                        "App updates",
                        NotificationManager.IMPORTANCE_HIGH
                    ).apply {
                        description = "EVS Generator update alerts"
                        enableVibration(true)
                        setShowBadge(true)
                    }
                    nm.createNotificationChannel(ch)
                }
                val intent = Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    putExtra("open_update", true)
                }
                val pi = PendingIntent.getActivity(
                    context, 0, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                val notes = info.releaseNotes.trim().take(120).ifBlank { "Tap to install." }
                val notif = NotificationCompat.Builder(context, CHANNEL)
                    .setSmallIcon(R.drawable.ic_update_download)
                    .setContentTitle("EVS Generator — New update available")
                    .setContentText("v${info.versionName} ready · Update now & create EVS accounts")
                    .setStyle(
                        NotificationCompat.BigTextStyle().bigText(
                            "Version ${info.versionName} just dropped.\n\n$notes\n\nTap to open and install."
                        )
                    )
                    .setContentIntent(pi)
                    .setAutoCancel(true)
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setCategory(NotificationCompat.CATEGORY_STATUS)
                    .build()
                val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                nm.notify(NOTIFY_ID, notif)
            } catch (_: Exception) {
            }
        }
    }
}
