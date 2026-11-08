package com.discord.evs.generator.automation

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Draxono / DraxonMails-style private mail API (stock tool pattern).
 * Key + optional private domain from Prefs.
 */
class DraxonoMailApi(
    private val apiKey: String = "",
    private val privateDomain: String = ""
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val bases = listOf(
        "https://api.draxono.com",
        "https://draxono.com/api",
        "https://mail.draxono.com/api"
    )

    private fun get(url: String): String? {
        if (apiKey.isBlank()) return null
        val req = Request.Builder()
            .url(url)
            .get()
            .header("Authorization", "Bearer $apiKey")
            .header("X-API-Key", apiKey)
            .header("User-Agent", "EVS-Generator/6.5")
            .build()
        return try {
            client.newCall(req).execute().use { r ->
                if (r.isSuccessful) r.body?.string() else null
            }
        } catch (_: Exception) {
            null
        }
    }

    suspend fun createEmail(localPart: String): CyberTempApi.EmailResult = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) {
            return@withContext CyberTempApi.EmailResult("", "", false)
        }
        val domainQ = if (privateDomain.isNotBlank()) "&domain=$privateDomain" else ""
        for (base in bases) {
            for (path in listOf(
                "/v1/mailbox/create?key=$apiKey&user=$localPart$domainQ",
                "/mailbox?api_key=$apiKey&local=$localPart$domainQ",
                "/api/email/new?key=$apiKey$domainQ"
            )) {
                val body = get(base + path) ?: continue
                val addr = parseAddr(body)
                if (addr != null) {
                    return@withContext CyberTempApi.EmailResult(
                        addr,
                        addr.substringAfter("@", privateDomain),
                        true
                    )
                }
            }
        }
        // last resort: synthesize private domain address if user set domain (panel may accept IMAP elsewhere)
        if (privateDomain.isNotBlank()) {
            val addr = "$localPart@$privateDomain"
            return@withContext CyberTempApi.EmailResult(addr, privateDomain, true)
        }
        CyberTempApi.EmailResult("", "", false)
    }

    private fun parseAddr(body: String): String? {
        val t = body.trim()
        return try {
            when {
                t.startsWith("{") -> {
                    val o = JSONObject(t)
                    sequenceOf("email", "address", "mail")
                        .map { o.optString(it, "") }
                        .firstOrNull { it.contains("@") }
                        ?: o.optJSONObject("data")?.optString("email")?.takeIf { it.contains("@") }
                }
                t.startsWith("[") -> {
                    val a = JSONArray(t)
                    if (a.length() == 0) null else a.optString(0).takeIf { it.contains("@") }
                }
                "@" in t -> t.lineSequence().firstOrNull { it.contains("@") }?.trim()
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }

    suspend fun getVerificationUrl(
        email: String,
        timeoutMs: Long = 60_000L,
        onLog: ((String) -> Unit)? = null
    ): String? = withContext(Dispatchers.IO) {
        if (apiKey.isBlank() || email.isBlank()) return@withContext null
        onLog?.invoke("Polling Draxono for Discord verify mail…")
        val start = System.currentTimeMillis()
        val enc = java.net.URLEncoder.encode(email, "UTF-8")
        while (System.currentTimeMillis() - start < timeoutMs) {
            for (base in bases) {
                for (path in listOf(
                    "/v1/mailbox/messages?key=$apiKey&email=$enc",
                    "/messages?api_key=$apiKey&email=$enc"
                )) {
                    val body = get(base + path) ?: continue
                    val url = Regex(
                        """https?://(?:click\.)?discord\.com/[^"'\s<>]+""",
                        RegexOption.IGNORE_CASE
                    ).find(body)?.value?.replace("&amp;", "&")
                    if (url != null) return@withContext url
                }
            }
            delay(3000)
        }
        null
    }
}
