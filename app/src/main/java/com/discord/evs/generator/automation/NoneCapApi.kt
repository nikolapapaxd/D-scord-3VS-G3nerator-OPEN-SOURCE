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
 * NoneCap hCaptcha solver API — free credits on signup (100), pay-per-success after.
 *
 * Docs: https://nonecap.com/api-reference/
 * Dashboard: https://dashboard.nonecap.com
 *
 * Flow:
 *  POST https://api.nonecap.com/v1/solves?wait=90
 *  Authorization: Bearer nc_live_...
 *  Body: { "type": "hcaptcha", "sitekey": "...", "url": "..." }
 *  → token on success; poll GET /v1/solves/{id} if status still pending/solving.
 */
class NoneCapApi(private val apiKey: String) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()
    }

    private fun base(): String = com.discord.evs.generator.security.StringCipher.nonecapBase()

    /**
     * Solve Discord-style hCaptcha. Returns the P1_ token or null.
     */
    suspend fun solveHcaptcha(
        siteKey: String,
        pageUrl: String = com.discord.evs.generator.security.StringCipher.discordRegister(),
        log: ((String) -> Unit)? = null
    ): String? = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) {
            log?.invoke("[NoneCap] No API key — get free credits at dashboard.nonecap.com")
            return@withContext null
        }

        log?.invoke("[NoneCap] Submitting hCaptcha (free-tier friendly)...")
        try {
            val body = JSONObject().apply {
                put("type", "hcaptcha")
                put("sitekey", siteKey)
                put("url", pageUrl)
            }.toString().toRequestBody(JSON)

            val req = Request.Builder()
                .url("${base()}/solves?wait=90")
                .header("Authorization", "Bearer $apiKey")
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .post(body)
                .build()

            val (code, raw) = http.newCall(req).execute().use { resp ->
                (resp.code to (resp.body?.string() ?: ""))
            }
            log?.invoke("[NoneCap] HTTP $code: ${raw.take(180)}")

            if (raw.isBlank()) {
                log?.invoke("[NoneCap] Empty response")
                return@withContext null
            }

            val obj = try { JSONObject(raw) } catch (_: Exception) {
                log?.invoke("[NoneCap] Invalid JSON")
                return@withContext null
            }

            // Immediate success
            val tokenDirect = obj.optString("token", "")
            val status = obj.optString("status", "")
            if (status == "solved" && tokenDirect.isNotBlank()) {
                log?.invoke("[NoneCap] ✓ Token received")
                return@withContext tokenDirect
            }

            // Still in flight → poll
            val id = obj.optString("id", "")
            if (id.isBlank()) {
                val err = obj.optString("error", obj.optString("message", "unknown"))
                log?.invoke("[NoneCap] Failed: $err")
                return@withContext null
            }

            log?.invoke("[NoneCap] Solve $id in progress — polling...")
            val deadline = System.currentTimeMillis() + 120_000L
            while (System.currentTimeMillis() < deadline) {
                delay(3_000L)
                val pollReq = Request.Builder()
                    .url("${base()}/solves/$id?wait=30")
                    .header("Authorization", "Bearer $apiKey")
                    .header("Accept", "application/json")
                    .get()
                    .build()
                val pollRaw = http.newCall(pollReq).execute().use { pollResp ->
                    pollResp.body?.string() ?: ""
                }
                if (pollRaw.isBlank()) continue
                val pollObj = try { JSONObject(pollRaw) } catch (_: Exception) { continue }
                val st = pollObj.optString("status", "")
                when (st) {
                    "solved" -> {
                        val t = pollObj.optString("token", "")
                        if (t.isNotBlank()) {
                            log?.invoke("[NoneCap] ✓ Token received")
                            return@withContext t
                        }
                    }
                    "failed", "cancelled", "expired" -> {
                        log?.invoke("[NoneCap] Solve ended with status=$st")
                        return@withContext null
                    }
                    else -> log?.invoke("[NoneCap] status=$st — waiting...")
                }
            }
            log?.invoke("[NoneCap] Timed out waiting for token")
            null
        } catch (e: Exception) {
            log?.invoke("[NoneCap] Exception: ${e.message}")
            null
        }
    }
}
