package com.discord.evs.generator.automation

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Resolve invite codes and filter joinable ones. */
class InviteScraper {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    data class InviteInfo(
        val code: String,
        val valid: Boolean,
        val guildName: String = "",
        val members: Int = 0,
        val verificationLevel: Int = 0,
        val reason: String = ""
    )

    suspend fun resolve(codeOrUrl: String, token: String? = null): InviteInfo = withContext(Dispatchers.IO) {
        val code = extract(codeOrUrl) ?: return@withContext InviteInfo(codeOrUrl, false, reason = "bad code")
        try {
            val url = "https://discord.com/api/v9/invites/$code?with_counts=true&with_expiration=true"
            val req = Request.Builder().url(url)
                .header("User-Agent", "Mozilla/5.0")
                .apply { if (!token.isNullOrBlank()) header("Authorization", token.trim()) }
                .build()
            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string() ?: ""
                if (resp.code == 404) return@withContext InviteInfo(code, false, reason = "not found")
                if (!resp.isSuccessful) return@withContext InviteInfo(code, false, reason = "HTTP ${resp.code}")
                val o = JSONObject(body)
                val g = o.optJSONObject("guild")
                InviteInfo(
                    code = code,
                    valid = true,
                    guildName = g?.optString("name") ?: "",
                    members = o.optInt("approximate_member_count"),
                    verificationLevel = g?.optInt("verification_level") ?: 0
                )
            }
        } catch (e: Exception) {
            InviteInfo(code, false, reason = e.message ?: "error")
        }
    }

    fun extract(raw: String): String? {
        val t = raw.trim()
        val m = Regex("""(?:discord(?:app)?\.com/invite/|discord\.gg/)([A-Za-z0-9-]+)""").find(t)
        if (m != null) return m.groupValues[1]
        if (t.matches(Regex("""[A-Za-z0-9-_]{4,32}"""))) return t
        return null
    }
}
