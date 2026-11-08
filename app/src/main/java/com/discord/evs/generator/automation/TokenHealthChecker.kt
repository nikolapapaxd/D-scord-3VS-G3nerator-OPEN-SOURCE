package com.discord.evs.generator.automation

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Deep token health: VALID/LOCKED/PHONE/EMAIL/DISABLED + flags + guilds + nitro. */
class TokenHealthChecker {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    data class Health(
        val status: String,
        val username: String = "",
        val id: String = "",
        val email: String = "",
        val emailVerified: Boolean = false,
        val phone: String = "",
        val mfa: Boolean = false,
        val nitro: String = "NONE",
        val guildCount: Int = -1,
        val flags: Int = 0,
        val locale: String = "",
        val rawError: String = ""
    )

    suspend fun check(token: String): Health = withContext(Dispatchers.IO) {
        val clean = token.trim().removePrefix("Bearer ").trim()
        if (clean.isBlank()) return@withContext Health("INVALID")
        try {
            val me = getJson("https://discord.com/api/v9/users/@me", clean)
            if (me == null) return@withContext Health("INVALID", rawError = "null body")
            if (me.has("message") && me.optInt("code") == 0) {
                return@withContext Health("INVALID", rawError = me.optString("message"))
            }
            val id = me.optString("id")
            if (id.isBlank()) return@withContext Health("INVALID", rawError = me.toString().take(120))

            val emailVerified = me.optBoolean("verified", false)
            val phone = me.optString("phone", "")
            val username = me.optString("username", "")
            val mfa = me.optBoolean("mfa_enabled", false)
            val flags = me.optInt("flags", 0)
            val locale = me.optString("locale", "")
            val premium = when (me.optInt("premium_type", 0)) {
                1 -> "NITRO_CLASSIC"
                2 -> "NITRO"
                3 -> "NITRO_BASIC"
                else -> "NONE"
            }

            // Guilds probe — 403 often means locked / phone gate
            var guildCount = -1
            var locked = false
            try {
                val gReq = Request.Builder()
                    .url("https://discord.com/api/v9/users/@me/guilds")
                    .header("Authorization", clean)
                    .header("User-Agent", UA)
                    .build()
                client.newCall(gReq).execute().use { resp ->
                    when (resp.code) {
                        200 -> {
                            val body = resp.body?.string() ?: "[]"
                            guildCount = org.json.JSONArray(body).length()
                        }
                        403 -> locked = true
                        401 -> return@withContext Health("INVALID", rawError = "401 on guilds")
                    }
                }
            } catch (_: Exception) { }

            val status = when {
                locked -> "LOCKED"
                !emailVerified && phone.isBlank() -> "EMAIL"
                phone.isBlank() && locked -> "PHONE"
                else -> "VALID"
            }
            // refine phone-required heuristic
            val finalStatus = if (locked && phone.isBlank()) "PHONE" else status

            Health(
                status = finalStatus,
                username = username,
                id = id,
                email = me.optString("email", ""),
                emailVerified = emailVerified,
                phone = phone,
                mfa = mfa,
                nitro = premium,
                guildCount = guildCount,
                flags = flags,
                locale = locale
            )
        } catch (e: Exception) {
            Health("ERROR", rawError = e.message ?: "error")
        }
    }

    private fun getJson(url: String, token: String): JSONObject? {
        val req = Request.Builder().url(url)
            .header("Authorization", token)
            .header("User-Agent", UA)
            .header("Accept", "application/json")
            .build()
        client.newCall(req).execute().use { resp ->
            val body = resp.body?.string() ?: return null
            return try { JSONObject(body) } catch (_: Exception) { null }
        }
    }

    companion object {
        private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
    }
}
