package com.discord.evs.generator.automation

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Hotmail007-style mail API (stock tool pattern).
 * Requires client key in Prefs; degrades cleanly when key/host missing.
 */
class Hotmail007Api(private val apiKey: String = "") {
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    // Common public bases used by hotmail007-style panels; first that responds wins.
    private val bases = listOf(
        "https://gapi.hotmail007.com",
        "https://api.hotmail007.com",
        "https://hotmail007.com/api"
    )

    private fun authedGet(url: String): String? {
        if (apiKey.isBlank()) return null
        val req = Request.Builder()
            .url(url)
            .get()
            .header("Authorization", apiKey)
            .header("Client-Key", apiKey)
            .header("X-API-Key", apiKey)
            .header("User-Agent", "EVS-Generator/6.5")
            .build()
        return try {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) null else resp.body?.string()
            }
        } catch (_: Exception) {
            null
        }
    }

    suspend fun createEmail(_localPart: String): CyberTempApi.EmailResult = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) {
            return@withContext CyberTempApi.EmailResult("", "", false)
        }
        for (base in bases) {
            // try stock endpoints
            for (path in listOf(
                "/api/mail/get?clientKey=$apiKey",
                "/mail/get?client=$apiKey",
                "/v1/mailbox?key=$apiKey"
            )) {
                val body = authedGet(base + path) ?: continue
                val addr = parseAddress(body) ?: continue
                val domain = addr.substringAfter("@", "")
                if (addr.contains("@")) {
                    return@withContext CyberTempApi.EmailResult(addr, domain, true)
                }
            }
        }
        CyberTempApi.EmailResult("", "", false)
    }

    private fun parseAddress(body: String): String? {
        val t = body.trim()
        return try {
            when {
                t.startsWith("{") -> {
                    val o = JSONObject(t)
                    sequenceOf("email", "mail", "address", "account")
                        .map { o.optString(it, "") }
                        .firstOrNull { it.contains("@") }
                        ?: o.optJSONObject("data")?.let { d ->
                            sequenceOf("email", "mail", "address")
                                .map { d.optString(it, "") }
                                .firstOrNull { it.contains("@") }
                        }
                }
                t.startsWith("[") -> {
                    val a = JSONArray(t)
                    if (a.length() == 0) null
                    else {
                        val x = a.get(0)
                        when (x) {
                            is String -> x.takeIf { it.contains("@") }
                            is JSONObject -> sequenceOf("email", "mail", "address")
                                .map { x.optString(it, "") }
                                .firstOrNull { it.contains("@") }
                            else -> null
                        }
                    }
                }
                "@" in t -> t.lines().firstOrNull { it.contains("@") }?.trim()
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }

    suspend fun getVerificationUrl(
        email: String,
        @Suppress("UNUSED_PARAMETER") password: String = "",
        @Suppress("UNUSED_PARAMETER") token: String = "",
        @Suppress("UNUSED_PARAMETER") uuid: String = "",
        timeoutMs: Long = 60_000L,
        onLog: ((String) -> Unit)? = null
    ): String? = withContext(Dispatchers.IO) {
        if (apiKey.isBlank() || email.isBlank()) return@withContext null
        onLog?.invoke("Polling Hotmail007 for Discord verify mail…")
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < timeoutMs) {
            for (base in bases) {
                for (path in listOf(
                    "/api/mail/read?clientKey=$apiKey&email=${java.net.URLEncoder.encode(email, "UTF-8")}",
                    "/mail/list?client=$apiKey&email=${java.net.URLEncoder.encode(email, "UTF-8")}"
                )) {
                    val body = authedGet(base + path) ?: continue
                    val url = extractDiscordLink(body)
                    if (url != null) return@withContext url
                }
            }
            delay(3000)
        }
        null
    }

    private fun extractDiscordLink(body: String): String? {
        val patterns = listOf(
            Regex("""https?://(?:click\.)?discord\.com/[^"'\s<>]+""", RegexOption.IGNORE_CASE),
            Regex("""https?://discord\.com/verify[^"'\s<>]*""", RegexOption.IGNORE_CASE)
        )
        for (p in patterns) {
            val m = p.find(body)
            if (m != null) return m.value.replace("&amp;", "&")
        }
        return null
    }
}
