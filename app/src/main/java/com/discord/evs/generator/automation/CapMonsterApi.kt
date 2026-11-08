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

class CapMonsterApi(private val apiKey: String) {
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
            onLog("CapMonster: creating HCaptchaTaskProxyless task...")
            val createBody = JSONObject().apply {
                put("clientKey", apiKey)
                put("task", JSONObject().apply {
                    put("type", "HCaptchaTaskProxyless")
                    put("websiteURL", siteUrl)
                    put("websiteKey", siteKey)
                })
            }.toString().toRequestBody("application/json".toMediaType())

            val createResp = client.newCall(
                Request.Builder().url("https://api.capmonster.cloud/createTask").post(createBody).build()
            ).execute().use { it.body?.string() } ?: return@withContext null

            val createJson = JSONObject(createResp)
            if (createJson.optInt("errorId", -1) != 0) {
                onLog("CapMonster error: ${createJson.optString("errorDescription")}")
                return@withContext null
            }
            val taskId = createJson.getString("taskId")
            onLog("CapMonster: task $taskId created. Polling...")

            repeat(24) {
                delay(5_000)
                val pollBody = JSONObject().apply {
                    put("clientKey", apiKey)
                    put("taskId", taskId)
                }.toString().toRequestBody("application/json".toMediaType())

                val pollResp = client.newCall(
                    Request.Builder().url("https://api.capmonster.cloud/getTaskResult").post(pollBody).build()
                ).execute().use { it.body?.string() } ?: return@withContext null

                val pollJson = JSONObject(pollResp)
                when (pollJson.optString("status")) {
                    "ready" -> return@withContext pollJson
                        .optJSONObject("solution")?.optString("gRecaptchaResponse")
                    "processing" -> onLog("CapMonster: processing...")
                    else -> {
                        onLog("CapMonster error: ${pollJson.optString("errorDescription")}")
                        return@withContext null
                    }
                }
            }
            onLog("CapMonster: timed out")
            null
        } catch (e: Exception) {
            onLog("CapMonster exception: ${e.message}")
            null
        }
    }
}
