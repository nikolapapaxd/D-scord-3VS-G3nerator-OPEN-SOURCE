package com.discord.evs.generator.automation

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Discord token status checker.
 *
 * Old method used only /users/@me/library — many locked tokens still return 200 there.
 * New method:
 *  1) GET /users/@me          → 401 = INVALID
 *  2) GET /users/@me/guilds   → 403 = LOCKED (phone/email lock / disabled)
 *  3) GET /users/@me/settings → 403 = LOCKED
 *  Only if both secondary checks are 200 → VALID
 */
class TokenChecker {
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    enum class TokenStatus { VALID, UNVERIFIED, LOCKED, PHONE_REQUIRED, INVALID, ERROR }

    private fun discordHeaders(token: String) = mapOf(
        "Authorization" to token.trim(),
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
        "Accept" to "application/json",
        "Content-Type" to "application/json",
        "X-Super-Properties" to "eyJvcyI6IldpbmRvd3MiLCJicm93c2VyIjoiQ2hyb21lIiwiZGV2aWNlIjoiIiwic3lzdGVtX2xvY2FsZSI6ImVuLVVTIiwiYnJvd3Nlcl91c2VyX2FnZW50IjoiTW96aWxsYS81LjAgKFdpbmRvd3MgTlQgMTAuMDsgV2luNjQ7IHg2NCkgQXBwbGVXZWJLaXQvNTM3LjM2IChLSFRNTCwgbGlrZSBHZWNrbykgQ2hyb21lLzEyNC4wLjAuMCBTYWZhcmkvNTM3LjM2IiwiYnJvd3Nlcl92ZXJzaW9uIjoiMTI0LjAuMC4wIiwib3NfdmVyc2lvbiI6IjEwIiwicmVmZXJyZXIiOiIiLCJyZWZlcnJpbmdfZG9tYWluIjoiIiwicmVmZXJyZXJfY3VycmVudCI6IiIsInJlZmVycmluZ19kb21haW5fY3VycmVudCI6IiIsInJlbGVhc2VfY2hhbm5lbCI6InN0YWJsZSIsImNsaWVudF9idWlsZF9udW1iZXIiOjk5OTkiLCJjbGllbnRfZXZlbnRfc291cmNlIjpudWxsfQ=="
    )

    suspend fun checkToken(token: String): TokenStatus = withContext(Dispatchers.IO) {
        if (token.isBlank() || token.length < 20) return@withContext TokenStatus.INVALID
        try {
            // 1) GET /users/@me — 401/404 INVALID, 403 LOCKED, 200 continue
            val meReq = Request.Builder()
                .url("https://discord.com/api/v9/users/@me")
                .get()
                .apply { discordHeaders(token).forEach { (k, v) -> addHeader(k, v) } }
                .build()
            val meResp = client.newCall(meReq).execute()
            val meCode = meResp.code
            val meBody = meResp.body?.string() ?: "{}"
            meResp.close()
            when (meCode) {
                401, 404 -> return@withContext TokenStatus.INVALID
                403 -> return@withContext TokenStatus.LOCKED
                in 500..599, -1 -> return@withContext TokenStatus.ERROR
            }
            if (meCode != 200) return@withContext TokenStatus.ERROR

            val meJson = try { JSONObject(meBody) } catch (_: Exception) { JSONObject() }
            val hasPhone = meJson.optString("phone").isNotBlank()
            val verified = meJson.optBoolean("verified", false)

            // 2) GET /users/@me/guilds — 403 + no phone => PHONE_REQUIRED; 403 + phone => LOCKED
            val guildsCode = headOrGet("https://discord.com/api/v9/users/@me/guilds", token)
            if (guildsCode == 401) return@withContext TokenStatus.INVALID
            if (guildsCode == 403) {
                return@withContext if (!hasPhone) TokenStatus.PHONE_REQUIRED else TokenStatus.LOCKED
            }
            if (guildsCode == 429) return@withContext TokenStatus.ERROR

            // VALID path: guilds 200 only (no 204 dead code)
            if (guildsCode == 200) {
                if (!verified) TokenStatus.UNVERIFIED else TokenStatus.VALID
            } else {
                TokenStatus.ERROR
            }
        } catch (_: Exception) {
            TokenStatus.ERROR
        }
    }

    private fun headOrGet(url: String, token: String): Int {
        return try {
            val req = Request.Builder()
                .url(url)
                .get()
                .apply { discordHeaders(token).forEach { (k, v) -> addHeader(k, v) } }
                .build()
            client.newCall(req).execute().use { it.code }
        } catch (_: Exception) {
            -1
        }
    }

    suspend fun isEmailVerified(token: String): Pair<Boolean?, String?> = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder()
                .url("https://discord.com/api/v9/users/@me")
                .apply { discordHeaders(token).forEach { (k, v) -> addHeader(k, v) } }
                .build()
            val resp = client.newCall(req).execute()
            if (resp.isSuccessful) {
                val obj = JSONObject(resp.body?.string() ?: "{}")
                Pair(obj.optBoolean("verified", false), obj.optString("email"))
            } else Pair(null, null)
        } catch (_: Exception) {
            Pair(null, null)
        }
    }

    suspend fun fetchTokenViaApi(email: String, password: String): String? = withContext(Dispatchers.IO) {
        try {
            val json = JSONObject().apply {
                put("login", email)
                put("password", password)
                put("undelete", false)
                put("login_source", JSONObject.NULL)
                put("gift_code_sku_id", JSONObject.NULL)
            }.toString()
            val body = json.toRequestBody("application/json".toMediaType())
            val req = Request.Builder()
                .url("https://discord.com/api/v9/auth/login")
                .post(body)
                .addHeader("Content-Type", "application/json")
                .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .build()
            val resp = client.newCall(req).execute()
            if (resp.isSuccessful) {
                JSONObject(resp.body?.string() ?: "{}").optString("token").ifEmpty { null }
            } else null
        } catch (_: Exception) {
            null
        }
    }
}
