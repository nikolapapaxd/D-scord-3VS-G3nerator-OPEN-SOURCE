package com.discord.evs.generator.automation

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Stores captcha challenge metadata + operator solutions for on-device policy training
 * and optional upload to a self-hosted collector (Vercel/Fly URL in prefs).
 *
 * This is NOT a claim of solving all captchas offline — it feeds MovementCaptchaPolicy
 * and remote datasets you control.
 */
object CaptchaTrainCollector {
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    data class Sample(
        val sitekey: String,
        val pageUrl: String,
        val kind: String,
        val solution: String,
        val ts: Long = System.currentTimeMillis()
    )

    private fun file(ctx: Context) = File(ctx.filesDir, "captcha_train_samples.jsonl")

    fun recordLocal(ctx: Context, sample: Sample) {
        try {
            val line = JSONObject()
                .put("sitekey", sample.sitekey)
                .put("pageUrl", sample.pageUrl)
                .put("kind", sample.kind)
                .put("solution", sample.solution)
                .put("ts", sample.ts)
                .toString()
            file(ctx).appendText(line + "\n")
        } catch (_: Exception) {}
    }

    suspend fun uploadRemote(endpoint: String, sample: Sample, onLog: (String) -> Unit = {}): Boolean =
        withContext(Dispatchers.IO) {
            if (endpoint.isBlank()) return@withContext false
            try {
                val body = JSONObject()
                    .put("sitekey", sample.sitekey)
                    .put("pageUrl", sample.pageUrl)
                    .put("kind", sample.kind)
                    .put("solution", sample.solution)
                    .put("ts", sample.ts)
                    .toString()
                    .toRequestBody("application/json".toMediaType())
                val req = Request.Builder().url(endpoint).post(body)
                    .header("Content-Type", "application/json")
                    .build()
                client.newCall(req).execute().use { resp ->
                    onLog("CaptchaTrain upload HTTP ${resp.code}")
                    resp.isSuccessful
                }
            } catch (e: Exception) {
                onLog("CaptchaTrain upload fail: ${e.message}")
                false
            }
        }

    fun localCount(ctx: Context): Int = try {
        if (!file(ctx).exists()) 0 else file(ctx).readLines().count { it.isNotBlank() }
    } catch (_: Exception) { 0 }
}
