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
 * Native Kotlin NopeCHA Token API client.
 *
 * WHY NATIVE: Discord's Content Security Policy (CSP) blocks fetch() calls to
 * third-party domains (including nopecha.com) when JavaScript is injected into
 * the Discord WebView. The NopeCHA browser extension bypasses CSP via extension
 * privileges — injected JS does not have that privilege. Making the API calls
 * here (OkHttp on the Android side) completely avoids CSP restrictions.
 */
class NopechaApi(private val apiKey: String) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /**
     * Submits an hCaptcha task and polls until the token is ready.
     *
     * @param siteKey  The hCaptcha sitekey found on the page.
     * @param pageUrl  The URL of the page hosting the captcha.
     * @param log      Optional log callback forwarded to the UI.
     * @return         The solved captcha token, or null on failure.
     */
    suspend fun solveHcaptcha(
        siteKey: String,
        pageUrl: String = "https://discord.com/register",
        log: ((String) -> Unit)? = null
    ): String? = withContext(Dispatchers.IO) {
        val taskId = submitTask(siteKey, pageUrl, log) ?: return@withContext null
        pollForToken(taskId, log)
    }

    private fun submitTask(siteKey: String, pageUrl: String, log: ((String) -> Unit)?): String? {
        val body = JSONObject().apply {
            put("key", apiKey)
            put("type", "hcaptcha")
            put("sitekey", siteKey)
            put("url", pageUrl)
        }.toString().toRequestBody("application/json".toMediaType())

        // NopeCHA has moved/duplicated the token endpoint across domains. Try them in order.
        val endpoints = listOf(
            "https://api.nopecha.com/token",
            "https://nopecha.com/api/token",
            "https://nopecha.com/api/v1/token"
        )

        for (url in endpoints) {
            try {
                log?.invoke("[NopeCHA] Trying submit endpoint: $url")
                val req = Request.Builder()
                    .url(url)
                    .post(body)
                    .header("Content-Type", "application/json")
                    .build()

                val resp = http.newCall(req).execute()
                val raw = resp.body?.string()
                log?.invoke("[NopeCHA] Submit HTTP ${resp.code}: ${raw?.take(160)}")

                if (resp.code == 404) {
                    log?.invoke("[NopeCHA] Endpoint $url returned 404 — trying next fallback")
                    continue
                }

                if (!resp.isSuccessful || raw == null) {
                    log?.invoke("[NopeCHA] Submit failed — HTTP ${resp.code}")
                    return null
                }

                val json = JSONObject(raw)
                val error = json.optInt("error", -1)
                if (error != 0) {
                    log?.invoke("[NopeCHA] Task rejected — error $error. Check your API key and plan.")
                    return null
                }

                val taskId = json.optString("data", "")
                if (taskId.isEmpty()) {
                    log?.invoke("[NopeCHA] No task ID in response: $raw")
                    return null
                }

                log?.invoke("[NopeCHA] Task queued via $url: ${taskId.take(20)}… polling every 3 s (max 3 min)…")
                return taskId
            } catch (e: Exception) {
                log?.invoke("[NopeCHA] Submit error for $url: ${e.message}")
            }
        }
        log?.invoke("[NopeCHA] All submit endpoints failed.")
        return null
    }

    private suspend fun pollForToken(taskId: String, log: ((String) -> Unit)?): String? {
        val endpoints = listOf(
            "https://api.nopecha.com/token?id=${encode(taskId)}&key=${encode(apiKey)}",
            "https://nopecha.com/api/token?id=${encode(taskId)}&key=${encode(apiKey)}",
            "https://nopecha.com/api/v1/token?id=${encode(taskId)}&key=${encode(apiKey)}"
        )

        repeat(60) { attempt ->
            delay(3_000)
            try {
                // Poll the same endpoint family that accepted the submit. Try fallbacks too.
                for (url in endpoints) {
                    try {
                        val req = Request.Builder().url(url).get().build()
                        val resp = http.newCall(req).execute()
                        val raw = resp.body?.string() ?: continue
                        if (!resp.isSuccessful) continue

                        val json = JSONObject(raw)
                        val error = json.optInt("error", -1)

                        if ((attempt + 1) % 5 == 0) {
                            log?.invoke("[NopeCHA] Poll ${attempt + 1}/60 — error=$error")
                        }

                        when {
                            error == 0 -> {
                                val data = json.optJSONArray("data")
                                val token = data?.optString(0, "")
                                if (!token.isNullOrEmpty()) {
                                    log?.invoke("[NopeCHA] Solved! Token: ${token.take(24)}…")
                                    return token
                                }
                            }
                            error == 7 || error == 9 -> {
                                // Still processing — keep polling, no need to try other endpoints
                            }
                            else -> {
                                log?.invoke("[NopeCHA] Fatal poll error $error: ${raw.take(120)}")
                                return null
                            }
                        }
                        break // successfully parsed this endpoint, go to next attempt
                    } catch (e: Exception) {
                        // try next endpoint
                    }
                }
            } catch (e: Exception) {
                log?.invoke("[NopeCHA] Poll error: ${e.message}")
            }
        }
        log?.invoke("[NopeCHA] Timed out after 3 min — no token received.")
        return null
    }

    private fun encode(s: String): String = java.net.URLEncoder.encode(s, "UTF-8")
}
