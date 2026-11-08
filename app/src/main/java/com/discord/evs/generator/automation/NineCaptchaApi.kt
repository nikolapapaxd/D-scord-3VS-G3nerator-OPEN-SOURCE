package com.discord.evs.generator.automation

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 9Captcha (9captcha.com style) hCaptcha solver — optional paid/key API.
 * Same create+poll pattern as 2captcha-compatible endpoints.
 */
class NineCaptchaApi(private val apiKey: String) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()
    private val JSON = "application/json; charset=utf-8".toMediaType()
    private val base = "https://api.9captcha.com"

    suspend fun solveHcaptcha(siteKey: String, pageUrl: String): String? = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) return@withContext null
        try {
            val createBody = JSONObject()
                .put("clientKey", apiKey)
                .put("task", JSONObject()
                    .put("type", "HCaptchaTaskProxyless")
                    .put("websiteURL", pageUrl)
                    .put("websiteKey", siteKey)
                ).toString().toRequestBody(JSON)
            val createReq = Request.Builder().url("$base/createTask").post(createBody).build()
            val createRaw = http.newCall(createReq).execute().use { it.body?.string().orEmpty() }
            val createJson = JSONObject(createRaw)
            if (createJson.optInt("errorId", 1) != 0) return@withContext null
            val taskId = createJson.optLong("taskId", 0L)
            if (taskId == 0L) return@withContext null
            repeat(40) {
                delay(3000)
                val pollBody = JSONObject()
                    .put("clientKey", apiKey)
                    .put("taskId", taskId)
                    .toString().toRequestBody(JSON)
                val pollReq = Request.Builder().url("$base/getTaskResult").post(pollBody).build()
                val pollRaw = http.newCall(pollReq).execute().use { it.body?.string().orEmpty() }
                val poll = JSONObject(pollRaw)
                if (poll.optInt("errorId", 1) != 0) return@withContext null
                when (poll.optString("status")) {
                    "ready" -> {
                        val sol = poll.optJSONObject("solution")
                        val token = sol?.optString("gRecaptchaResponse")
                            ?: sol?.optString("token")
                            ?: ""
                        if (token.isNotBlank()) return@withContext token
                    }
                    "processing" -> {}
                    else -> return@withContext null
                }
            }
            null
        } catch (_: Exception) {
            null
        }
    }
}
