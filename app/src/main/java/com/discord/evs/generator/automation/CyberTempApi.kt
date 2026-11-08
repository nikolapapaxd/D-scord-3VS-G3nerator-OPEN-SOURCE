package com.discord.evs.generator.automation

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * CyberTemp email API.
 * Base host reconstructed via StringCipher (api.cybertemp.xyz).
 */
class CyberTempApi(private val apiKey: String) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    companion object {
        const val FALLBACK_DOMAIN = "cybertemp.xyz"
    }

    private fun baseUrl(): String {
        return com.discord.evs.generator.security.StringCipher.cyberTempBase()
    }

    private val domains = mutableListOf<String>()

    private fun headers(): Map<String, String> {
        return mapOf(
            "User-Agent" to "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120.0.0.0 Mobile Safari/537.36",
            "Accept" to "application/json, text/plain, */*",
            "X-API-KEY" to apiKey
        )
    }

    suspend fun fetchDomains(): List<String> = withContext(Dispatchers.IO) {
        val endpoints = listOf(
            baseUrl() + "/getDomains?type=discord&limit=100&offset=0",
            baseUrl() + "/getDomains?limit=100&offset=0"
        )
        for (url in endpoints) {
            for (attempt in 0 until 3) {
                try {
                    val reqBuilder = Request.Builder().url(url).get()
                    headers().forEach { (k, v) -> reqBuilder.addHeader(k, v) }
                    val resp = client.newCall(reqBuilder.build()).execute()
                    resp.use {
                        if (it.isSuccessful) {
                            val body = it.body?.string().orEmpty()
                            val list = parseDomainList(body)
                            if (list.isNotEmpty()) {
                                domains.clear()
                                domains.addAll(list)
                                return@withContext list
                            }
                        }
                    }
                } catch (_: Exception) {
                    if (attempt < 2) delay(2000L)
                }
            }
        }
        emptyList()
    }

    private fun parseDomainList(body: String): List<String> {
        val trimmed = body.trim()
        try {
            if (trimmed.startsWith("[")) {
                val arr = JSONArray(trimmed)
                val out = mutableListOf<String>()
                for (i in 0 until arr.length()) {
                    val v = arr.opt(i)
                    val s = when (v) {
                        is String -> v
                        is JSONObject -> {
                            val d = v.optString("domain")
                            if (d.isNotBlank()) d else v.optString("name")
                        }
                        else -> ""
                    }
                    if (s.contains(".") && s.lowercase() !in setOf("diddyricky.com", "mail.tm", "mail.gw")) out.add(s)
                }
                return out
            }
            if (trimmed.startsWith("{")) {
                val obj = JSONObject(trimmed)
                val arr = obj.optJSONArray("domains") ?: obj.optJSONArray("data") ?: return emptyList()
                val out = mutableListOf<String>()
                for (i in 0 until arr.length()) {
                    val v = arr.opt(i)
                    val s = when (v) {
                        is String -> v
                        is JSONObject -> {
                            val d = v.optString("domain")
                            if (d.isNotBlank()) d else v.optString("name")
                        }
                        else -> ""
                    }
                    if (s.contains(".") && s.lowercase() !in setOf("diddyricky.com", "mail.tm", "mail.gw")) out.add(s)
                }
                return out
            }
        } catch (_: Exception) {
            return emptyList()
        }
        return emptyList()
    }

    suspend fun createEmail(username: String): EmailResult {
        if (domains.isEmpty()) {
            fetchDomains()
        }
        val domain = if (domains.isNotEmpty()) domains.random() else FALLBACK_DOMAIN
        val address = username + "@" + domain
        return EmailResult(address, domain, domains.isNotEmpty())
    }

    data class EmailResult(
        val address: String,
        val domain: String,
        val domainFromApi: Boolean,
        val mailPassword: String = ""
    )

    fun excludeDomain(domain: String) {
        domains.removeAll { it.equals(domain, ignoreCase = true) }
    }

    fun purgeRejected(rejected: Collection<String>) {
        if (rejected.isEmpty()) return
        val lower = rejected.map { it.lowercase().removePrefix("@") }.toSet()
        domains.removeAll { it.lowercase().removePrefix("@") in lower }
    }

    suspend fun getVerificationUrl(
        emailAddr: String,
        timeoutMs: Long = 120_000L,
        onLog: ((String) -> Unit)? = null
    ): String? = withContext(Dispatchers.IO) {
        val start = System.currentTimeMillis()
        val encoded = URLEncoder.encode(emailAddr, "UTF-8")
        onLog?.invoke("Polling CyberTemp inbox for: " + emailAddr + " (up to " + (timeoutMs / 1000) + "s)...")
        onLog?.invoke("  API: " + baseUrl() + " | key=" + apiKey.take(8) + "...")

        var poll = 0
        while (System.currentTimeMillis() - start < timeoutMs) {
            poll++
            try {
                val url = baseUrl() + "/getMail?email=" + encoded + "&limit=20&offset=0"
                val reqBuilder = Request.Builder().url(url).get()
                headers().forEach { (k, v) -> reqBuilder.addHeader(k, v) }
                val resp = client.newCall(reqBuilder.build()).execute()
                resp.use {
                    when (it.code) {
                        401, 403 -> {
                            onLog?.invoke("CyberTemp auth failed (HTTP " + it.code + ") — check API key")
                            return@withContext null
                        }
                        429 -> {
                            onLog?.invoke("CyberTemp rate limit — waiting 10s...")
                            delay(10_000L)
                        }
                        200 -> {
                            val body = it.body?.string().orEmpty()
                            if (body.isBlank()) {
                                if (poll % 5 == 0) {
                                    onLog?.invoke("CyberTemp empty inbox so far... (" + poll + ")")
                                }
                            } else {
                                val msgs = parseMessages(body)
                                if (msgs.isEmpty() && poll % 5 == 0) {
                                    onLog?.invoke("CyberTemp inbox empty (" + poll + ") body=" + body.take(60))
                                }
                                for (msg in msgs) {
                                    val subject = msg.optString("subject", "")
                                    val from = msg.optString("from", msg.optString("sender", ""))
                                    val subL = subject.lowercase()
                                    val fromL = from.lowercase()
                                    val looksDiscord =
                                        subL.contains("verify") ||
                                            fromL.contains("discord") ||
                                            subL.contains("discord") ||
                                            subL.contains("confirm") ||
                                            fromL.contains("noreply")
                                    if (!looksDiscord) continue

                                    onLog?.invoke("Found email — from=\"" + from + "\" subject=\"" + subject + "\"")

                                    val htmlRaw = msg.optString("html", "")
                                    val htmlAlt = if (htmlRaw.isBlank()) msg.optString("htmlBody", "") else htmlRaw
                                    val textRaw = msg.optString("text", "")
                                    val textAlt = if (textRaw.isBlank()) {
                                        val b = msg.optString("body", "")
                                        if (b.isBlank()) msg.optString("textBody", "") else b
                                    } else textRaw
                                    val combined = unescapeHtml(htmlAlt) + "\n" + unescapeHtml(textAlt) + "\n" + subject

                                    val verifyUrl = extractVerifyUrl(combined, onLog)
                                    if (verifyUrl != null) {
                                        onLog?.invoke("Verification URL found: " + verifyUrl.take(70) + "...")
                                        return@withContext verifyUrl
                                    } else {
                                        onLog?.invoke("Discord-looking mail but no verify link parsed — will keep polling")
                                    }
                                }
                            }
                        }
                        else -> {
                            if (poll % 5 == 0) {
                                when (it.code) {
                                    402 -> {
                                        onLog?.invoke("CyberTemp HTTP 402 — quota/payment required")
                                        return@withContext null
                                    }
                                    else -> onLog?.invoke("CyberTemp HTTP " + it.code + " — retrying")
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                if (poll % 5 == 0) {
                    onLog?.invoke("CyberTemp poll error: " + (e.message ?: "unknown"))
                }
            }
            delay(3_000L)
        }

        onLog?.invoke("Verification link not received within " + (timeoutMs / 1000) + "s")
        null
    }

    private fun parseMessages(body: String): List<JSONObject> {
        val trimmed = body.trim()
        val out = mutableListOf<JSONObject>()
        try {
            if (trimmed.startsWith("[")) {
                val arr = JSONArray(trimmed)
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i)
                    if (o != null) out.add(o)
                }
                return out
            }
            if (trimmed.startsWith("{")) {
                val obj = JSONObject(trimmed)
                val arr = obj.optJSONArray("mails")
                    ?: obj.optJSONArray("messages")
                    ?: obj.optJSONArray("data")
                    ?: obj.optJSONArray("emails")
                    ?: JSONArray()
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i)
                    if (o != null) out.add(o)
                }
                return out
            }
        } catch (_: Exception) {
            return emptyList()
        }
        return emptyList()
    }

    private fun extractVerifyUrl(body: String, onLog: ((String) -> Unit)? = null): String? {
        val patternStrings = listOf(
            "https?://(?:click\\.)?discord\\.com/(?:ls/click|verify)[^\\s\"'<>\\\\]+",
            "https?://(?:click\\.)?discordapp\\.com/(?:ls/click|verify)[^\\s\"'<>\\\\]+",
            "https?://discord\\.com/verify[^\\s\"'<>\\\\]+",
            "https?://[^\\s\"'<>\\\\]*discord[^\\s\"'<>\\\\]*/verify[^\\s\"'<>\\\\]*"
        )
        val links = LinkedHashSet<String>()
        for (ps in patternStrings) {
            val rx = Regex(ps, RegexOption.IGNORE_CASE)
            for (m in rx.findAll(body)) {
                val cleaned = m.value
                    .trimEnd('.', ')', ']', ',', '"', '>', '\\', '\'', '`')
                    .replace("&amp;", "&")
                if (cleaned.length > 25) links.add(cleaned)
            }
        }
        val hrefRx = Regex("href=[\"'](https?://[^\"']*discord[^\"']*)[\"']", RegexOption.IGNORE_CASE)
        for (m in hrefRx.findAll(body)) {
            val u = m.groupValues[1].replace("&amp;", "&")
            val ul = u.lowercase()
            if (ul.contains("verify") || ul.contains("click")) links.add(u)
        }

        for (link in links) {
            val lower = link.lowercase()
            if (lower.contains("click.discord") || lower.contains("/ls/click")) {
                try {
                    onLog?.invoke("Following redirect: " + link.take(55) + "...")
                    val req = Request.Builder()
                        .url(link)
                        .header("User-Agent", "Mozilla/5.0")
                        .get()
                        .build()
                    val resp = client.newCall(req).execute()
                    resp.use {
                        val finalUrl = it.request.url.toString()
                        if (finalUrl.contains("discord.com/verify") || finalUrl.contains("token=")) {
                            return finalUrl
                        }
                        val respBody = it.body?.string().orEmpty()
                        val nestedRx = Regex(
                            "https://(?:www\\.)?discord\\.com/verify[^\\s\"'<>]*",
                            RegexOption.IGNORE_CASE
                        )
                        val nested = nestedRx.find(respBody)?.value
                        if (nested != null) return nested.replace("&amp;", "&")
                    }
                } catch (_: Exception) {
                    // keep trying other links
                }
            } else if (lower.contains("discord.com/verify") || lower.contains("discordapp.com/verify")) {
                return link
            }
        }
        return null
    }

    private fun unescapeHtml(input: String): String {
        if (input.isEmpty()) return input
        return input
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&apos;", "'")
            .replace("&#x2F;", "/")
            .replace("&#x3D;", "=")
            .replace("&#61;", "=")
            .replace("&#x27;", "'")
    }
}
