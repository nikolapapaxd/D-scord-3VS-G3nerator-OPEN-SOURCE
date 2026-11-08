package com.discord.evs.generator.automation

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class AZCaptchaApi(private val apiKey: String) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    suspend fun solveHcaptcha(
        siteKey: String,
        siteUrl: String = "https://discord.com",
        onLog: (String) -> Unit = {}
    ): String? = withContext(Dispatchers.IO) {
        try {
            onLog("AZCaptcha: submitting hCaptcha task...")
            val submitUrl = "https://azcaptcha.com/in.php" +
                "?key=$apiKey&method=hcaptcha&sitekey=$siteKey&pageurl=$siteUrl&json=1"
            val resp = client.newCall(Request.Builder().url(submitUrl).build())
                .execute().use { it.body?.string() } ?: return@withContext null
            val json = JSONObject(resp)
            if (json.optInt("status", 0) != 1) {
                onLog("AZCaptcha submit error: ${json.optString("request")}")
                return@withContext null
            }
            val taskId = json.getString("request")
            onLog("AZCaptcha: task $taskId submitted. Polling...")
            repeat(24) {
                delay(5_000)
                val pollUrl = "https://azcaptcha.com/res.php" +
                    "?key=$apiKey&action=get&id=$taskId&json=1"
                val pollResp = client.newCall(Request.Builder().url(pollUrl).build())
                    .execute().use { it.body?.string() } ?: return@withContext null
                val pollJson = JSONObject(pollResp)
                when {
                    pollJson.optInt("status", 0) == 1 ->
                        return@withContext pollJson.getString("request")
                    pollJson.optString("request") == "CAPCHA_NOT_READY" ->
                        onLog("AZCaptcha: still processing...")
                    else -> {
                        onLog("AZCaptcha error: ${pollJson.optString("request")}")
                        return@withContext null
                    }
                }
            }
            onLog("AZCaptcha: timed out waiting for solution")
            null
        } catch (e: Exception) {
            onLog("AZCaptcha exception: ${e.message}")
            null
        }
    }
}
