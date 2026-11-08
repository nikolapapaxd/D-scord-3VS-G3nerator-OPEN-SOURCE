package com.discord.evs.generator.automation

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class DiscordApi {
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val defaultBio: String get() = brandingHeader()

    private val shortBios = listOf(
        "User",
        "built different",
        "just vibing",
        "discord only",
        "no bio",
        "new here",
        "say hi",
        "lowkey",
        "online",
        "🚀"
    )

    fun randomShortBio(): String = shortBios.random()

    private fun discordHeaders(token: String) = mapOf(
        "Authorization" to token,
        "User-Agent" to "Discord-Android/247012 (android; 14; 1080x2400; samsung; SM-S911B; ; en_US; 247012)",
        "Content-Type" to "application/json",
        "X-Super-Properties" to "eyJvcyI6IkFuZHJvaWQiLCJicm93c2VyIjoiQW5kcm9pZCBBcHAifQ=="
    )

    private suspend fun patchMe(token: String, json: JSONObject): Boolean = withContext(Dispatchers.IO) {
        try {
            val body = json.toString().toRequestBody("application/json".toMediaType())
            val req = Request.Builder()
                .url("https://discord.com/api/v9/users/@me")
                .patch(body)
                .apply { discordHeaders(token).forEach { (k, v) -> addHeader(k, v) } }
                .build()
            client.newCall(req).execute().use { it.isSuccessful }
        } catch (_: Exception) { false }
    }

    suspend fun setBio(token: String, bio: String? = null): Boolean {
        val text = (bio?.takeIf { it.isNotBlank() } ?: defaultBio).take(BIO_MAX)
        return patchMe(token, JSONObject().put("bio", text))
    }

    companion object {
        /** Discord About Me hard limit */
        const val BIO_MAX = 190

        fun brandingHeader(): String = listOf(
            "User",
            "Subscribe to ",
            "Join https://discord.gg/your-invite"
        ).joinToString("\n")

        /**
         * Branding block, then humanizer bio on the next section.
         * Layout:
         *   User
         *   Subscribe to ...
         *   Join ...
         *   <blank line>
         *   humanizer bio (trimmed to fit ≤ BIO_MAX)
         */
        fun composeBrandedBio(body: String?): String {
            val head = brandingHeader()
            val rest = (body ?: "").trim()
            if (rest.isBlank()) return head.take(BIO_MAX)
            val sep = "\n\n"
            val budget = BIO_MAX - head.length - sep.length
            if (budget <= 0) return head.take(BIO_MAX)
            // keep humanizer bio as one block on the next lines; never drop branding
            val trimmed = rest.replace(Regex("""\n+"""), " ").trim().take(budget)
            return (head + sep + trimmed).take(BIO_MAX)
        }
    }

    suspend fun setDisplayName(token: String, name: String): Boolean {
        if (name.isBlank()) return false
        return patchMe(token, JSONObject().put("global_name", name.take(32)))
    }

    suspend fun setPronouns(token: String, pronouns: String): Boolean {
        if (pronouns.isBlank()) return false
        return patchMe(token, JSONObject().put("pronouns", pronouns.take(40)))
    }

    suspend fun setAvatar(token: String, base64: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val clean = base64.trim()
            // Always present as JPEG data-URI. Humanizer/Pinterest may hand PNG bytes;
            // Discord rejects mismatched format. Encoders in AccountGenerator now JPEG-only.
            val dataUri = when {
                clean.startsWith("data:image/") -> clean
                clean.startsWith("data:") -> clean.replaceFirst(Regex("^data:[^;]*"), "data:image/jpeg")
                else -> "data:image/jpeg;base64,$clean"
            }
            if (dataUri.length < 200) return@withContext false
            val body = JSONObject().put("avatar", dataUri).toString().toRequestBody("application/json".toMediaType())
            val req = Request.Builder()
                .url("https://discord.com/api/v9/users/@me")
                .patch(body)
                .apply { discordHeaders(token).forEach { (k, v) -> addHeader(k, v) } }
                .header("Accept", "*/*")
                .build()
            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) return@withContext true
                // 401/403 = dead token; 400 often image size/format
                false
            }
        } catch (_: Exception) { false }
    }

    suspend fun setCustomStatus(token: String, text: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val payload = JSONObject().put(
                "custom_status",
                JSONObject().put("text", text.take(128)).put("expires_at", JSONObject.NULL)
            )
            val body = payload.toString().toRequestBody("application/json".toMediaType())
            val req = Request.Builder()
                .url("https://discord.com/api/v9/users/@me/settings")
                .patch(body)
                .apply { discordHeaders(token).forEach { (k, v) -> addHeader(k, v) } }
                .build()
            client.newCall(req).execute().use { it.isSuccessful }
        } catch (_: Exception) { false }
    }

    /** houseId: 1 Bravery, 2 Brilliance, 3 Balance */
    suspend fun setHypeSquad(token: String, houseId: Int = listOf(1, 2, 3).random()): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val body = JSONObject().put("house_id", houseId)
                    .toString().toRequestBody("application/json".toMediaType())
                val req = Request.Builder()
                    .url("https://discord.com/api/v9/hypesquad/online")
                    .post(body)
                    .apply { discordHeaders(token).forEach { (k, v) -> addHeader(k, v) } }
                    .build()
                client.newCall(req).execute().use { it.isSuccessful }
            } catch (_: Exception) { false }
        }

    /**
     * Post-register reliability: exchange email+password for a token via
     * Discord's login API (same path Shinchan uses). Returns null on MFA/captcha/fail.
     */
    suspend fun login(email: String, password: String): String? = withContext(Dispatchers.IO) {
        try {
            val payload = JSONObject()
                .put("login", email.trim())
                .put("password", password)
                .put("undelete", false)
                .put("login_source", JSONObject.NULL)
                .put("gift_code_sku_id", JSONObject.NULL)
            val body = payload.toString().toRequestBody("application/json".toMediaType())
            // Login has no user token yet — build minimal browser-like headers
            val req = Request.Builder()
                .url("https://discord.com/api/v9/auth/login")
                .post(body)
                .header("Content-Type", "application/json")
                .header("Accept", "*/*")
                .header("Origin", "https://discord.com")
                .header("Referer", "https://discord.com/login")
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36")
                .header("X-Discord-Locale", "en-US")
                .build()
            client.newCall(req).execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) return@withContext null
                val json = JSONObject(raw)
                val token = json.optString("token", "")
                if (token.isNotBlank()) return@withContext token
                // MFA / captcha — caller may fall back to web extraction
                null
            }
        } catch (_: Exception) { null }
    }

}
