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
 * Gated mass DM / friend request helper.
 * Hard delay floors exist to reduce instant bans; still high risk.
 */
class MassDmTool {
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    data class Result(val ok: Boolean, val detail: String)

    suspend fun openDmAndSend(token: String, userId: String, message: String): Result = withContext(Dispatchers.IO) {
        val clean = token.trim()
        try {
            // Create DM channel
            val body = JSONObject().put("recipient_id", userId).toString()
                .toRequestBody("application/json".toMediaType())
            val chReq = Request.Builder()
                .url("https://discord.com/api/v9/users/@me/channels")
                .post(body)
                .header("Authorization", clean)
                .header("Content-Type", "application/json")
                .header("User-Agent", UA)
                .header("X-Super-Properties", "eyJvcyI6IldpbmRvd3MiLCJicm93c2VyIjoiQ2hyb21lIiwiZGV2aWNlIjoiIiwic3lzdGVtX2xvY2FsZSI6ImVuLVVTIiwiYnJvd3Nlcl91c2VyX2FnZW50IjoiTW96aWxsYS81LjAgKFdpbmRvd3MgTlQgMTAuMDsgV2luNjQ7IHg2NCkgQXBwbGVXZWJLaXQvNTM3LjM2IChLSFRNTCwgbGlrZSBHZWNrbykgQ2hyb21lLzEyNi4wLjAuMCBTYWZhcmkvNTM3LjM2IiwiYnJvd3Nlcl92ZXJzaW9uIjoiMTI2LjAuMC4wIiwib3NfdmVyc2lvbiI6IjEwIiwicmVmZXJyZXIiOiIiLCJyZWZlcnJpbmdfZG9tYWluIjoiIiwicmVmZXJyZXJfY3VycmVudCI6IiIsInJlZmVycmluZ19kb21haW5fY3VycmVudCI6IiIsInJlbGVhc2VfY2hhbm5lbCI6InN0YWJsZSIsImNsaWVudF9idWlsZF9udW1iZXIiOjMwMTM3MCwiY2xpZW50X2V2ZW50X3NvdXJjZSI6bnVsbH0=")
                .build()
            val channelId = client.newCall(chReq).execute().use { resp ->
                val b = resp.body?.string() ?: ""
                if (!resp.isSuccessful) return@withContext Result(false, "DM open HTTP ${resp.code}: ${b.take(100)}")
                JSONObject(b).optString("id")
            }
            if (channelId.isBlank()) return@withContext Result(false, "no channel id")
            delay(1500)
            val msgBody = JSONObject().put("content", message).toString()
                .toRequestBody("application/json".toMediaType())
            val mReq = Request.Builder()
                .url("https://discord.com/api/v9/channels/$channelId/messages")
                .post(msgBody)
                .header("Authorization", clean)
                .header("Content-Type", "application/json")
                .header("User-Agent", UA)
                .header("X-Super-Properties", "eyJvcyI6IldpbmRvd3MiLCJicm93c2VyIjoiQ2hyb21lIiwiZGV2aWNlIjoiIiwic3lzdGVtX2xvY2FsZSI6ImVuLVVTIiwiYnJvd3Nlcl91c2VyX2FnZW50IjoiTW96aWxsYS81LjAgKFdpbmRvd3MgTlQgMTAuMDsgV2luNjQ7IHg2NCkgQXBwbGVXZWJLaXQvNTM3LjM2IChLSFRNTCwgbGlrZSBHZWNrbykgQ2hyb21lLzEyNi4wLjAuMCBTYWZhcmkvNTM3LjM2IiwiYnJvd3Nlcl92ZXJzaW9uIjoiMTI2LjAuMC4wIiwib3NfdmVyc2lvbiI6IjEwIiwicmVmZXJyZXIiOiIiLCJyZWZlcnJpbmdfZG9tYWluIjoiIiwicmVmZXJyZXJfY3VycmVudCI6IiIsInJlZmVycmluZ19kb21haW5fY3VycmVudCI6IiIsInJlbGVhc2VfY2hhbm5lbCI6InN0YWJsZSIsImNsaWVudF9idWlsZF9udW1iZXIiOjMwMTM3MCwiY2xpZW50X2V2ZW50X3NvdXJjZSI6bnVsbH0=")
                .build()
            client.newCall(mReq).execute().use { resp ->
                val b = resp.body?.string() ?: ""
                if (resp.isSuccessful) Result(true, "sent")
                else Result(false, "send HTTP ${resp.code}: ${b.take(120)}")
            }
        } catch (e: Exception) {
            Result(false, e.message ?: "error")
        }
    }

    companion object {
        private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
    }
}
