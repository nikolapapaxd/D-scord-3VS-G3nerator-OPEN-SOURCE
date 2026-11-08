package com.discord.evs.generator.update

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.discord.evs.generator.data.Prefs
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Optional faster path: poll public ntfy topic that GitHub Action posts to on release.
 * Topic default: evs-generator-updates @ ntfy.sh (no account).
 * Combined with UpdateCheckWorker this approximates "GitHub pings the phone".
 */
class NtfyUpdateWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        return try {
            val prefs = Prefs(applicationContext)
            if (!prefs.notifyUpdates) return Result.success()
            val topic = prefs.ntfyUpdateTopic.ifBlank { DEFAULT_TOPIC }
            val base = prefs.ntfyUpdateServer.ifBlank { DEFAULT_SERVER }
            val client = OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .build()
            // json=true returns last message metadata
            val url = "$base/$topic/json?poll=1"
            val req = Request.Builder().url(url).get().header("User-Agent", "EVS-Updater").build()
            val body = client.newCall(req).execute().use { it.body?.string().orEmpty() }
            if (body.isBlank()) return Result.success()
            // ntfy poll may return one JSON object per line
            val line = body.lineSequence().lastOrNull { it.isNotBlank() } ?: return Result.success()
            val obj = JSONObject(line)
            val event = obj.optString("event", "")
            if (event != "message") return Result.success()
            val title = obj.optString("title", "")
            val message = obj.optString("message", "")
            val id = obj.optString("id", title + message.take(40))
            if (id.isNotBlank() && id == prefs.lastNtfyMessageId) return Result.success()
            // Trigger standard GitHub check so notification uses real APK metadata
            val info = UpdateChecker.checkForUpdate(applicationContext)
            if (info != null) {
                val notifyKey = info.tagName + ":" + info.apkSizeBytes
                val lastKey = prefs.lastNotifiedUpdateTag + ":" + prefs.lastNotifiedUpdateSize
                if (notifyKey != lastKey) {
                    // reuse UpdateCheckWorker companion notifier via check path
                    UpdateCheckWorker.scheduleNowAndPeriodic(applicationContext)
                    // direct notify: run one-shot worker immediately already enqueued
                }
            }
            prefs.lastNtfyMessageId = id
            Result.success()
        } catch (_: Exception) {
            Result.retry()
        }
    }

    companion object {
        const val UNIQUE_NAME = "evs_ntfy_update_poll"
        const val DEFAULT_TOPIC = "evs-generator-updates"
        const val DEFAULT_SERVER = "https://ntfy.sh"

        fun schedule(context: Context) {
            val req = PeriodicWorkRequestBuilder<NtfyUpdateWorker>(15, TimeUnit.MINUTES)
                .addTag(UNIQUE_NAME)
                .build()
            WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
                UNIQUE_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                req
            )
        }
    }
}
