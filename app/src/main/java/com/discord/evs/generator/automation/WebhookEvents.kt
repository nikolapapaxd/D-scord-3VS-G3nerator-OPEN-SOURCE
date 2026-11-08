package com.discord.evs.generator.automation

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object WebhookEvents {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()
    private val JSON = "application/json".toMediaType()

    suspend fun post(url: String, title: String, body: String): Boolean = withContext(Dispatchers.IO) {
        if (url.isBlank()) return@withContext false
        try {
            val payload = JSONObject()
                .put("content", null)
                .put("embeds", org.json.JSONArray().put(
                    JSONObject()
                        .put("title", title.take(200))
                        .put("description", body.take(1800))
                        .put("color", 0x5865F2)
                ))
            val req = Request.Builder().url(url).post(payload.toString().toRequestBody(JSON)).build()
            client.newCall(req).execute().use { it.isSuccessful }
        } catch (_: Exception) { false }
    }
}
