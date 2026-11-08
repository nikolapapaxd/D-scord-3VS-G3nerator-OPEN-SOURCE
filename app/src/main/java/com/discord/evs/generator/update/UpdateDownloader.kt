package com.discord.evs.generator.update

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

object UpdateDownloader {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    /**
     * Downloads APK to app-specific external files. [onProgress] 0..100.
     */
    suspend fun download(
        context: Context,
        info: UpdateInfo,
        onProgress: (Int) -> Unit
    ): File = withContext(Dispatchers.IO) {
        val dir = File(context.getExternalFilesDir(null), "updates").apply { mkdirs() }
        val out = File(dir, info.apkName.replace(Regex("[^a-zA-Z0-9._\\- ]"), "_"))
        if (out.exists()) out.delete()
        val req = Request.Builder()
            .url(info.apkUrl)
            .header("User-Agent", UpdateConfig.USER_AGENT)
            .get()
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("Download failed HTTP ${resp.code}")
            val body = resp.body ?: error("Empty body")
            val total = body.contentLength()
            body.byteStream().use { input ->
                FileOutputStream(out).use { output ->
                    val buf = ByteArray(8192)
                    var read: Int
                    var done = 0L
                    var lastPct = -1
                    while (input.read(buf).also { read = it } != -1) {
                        output.write(buf, 0, read)
                        done += read
                        if (total > 0) {
                            val pct = ((done * 100) / total).toInt().coerceIn(0, 100)
                            if (pct != lastPct) {
                                lastPct = pct
                                onProgress(pct)
                            }
                        }
                    }
                    output.flush()
                }
            }
        }
        onProgress(100)
        out
    }
}
