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
 * 1secmail free temp-mail API (no API key).
 *
 * Docs: https://www.1secmail.com/api/
 *  - getDomainList
 *  - custom address = {login}@{domain} (any login on listed domains)
 *  - getMessages / readMessage
 *
 * Multiple rotating domains — good when a single domain is blocked by Discord.
 */
class OneSecMailApi {

    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val bases = listOf(
        "https://www.1secmail.com/api/v1/",
        "https://1secmail.com/api/v1/"
    )

    private val domains = mutableListOf<String>()
    private val excluded = mutableSetOf<String>()
    private var currentLogin: String? = null
    private var currentDomain: String? = null

    private fun get(url: String): String {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36")
            .header("Accept", "application/json")
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}")
            return resp.body?.string() ?: ""
        }
    }

    private fun tryBases(query: String): String {
        var last: Exception? = null
        for (base in bases) {
            try {
                return get(base + query)
            } catch (e: Exception) {
                last = e
            }
        }
        throw last ?: IllegalStateException("1secmail unreachable")
    }

    suspend fun fetchDomains(): List<String> = withContext(Dispatchers.IO) {
        try {
            val body = tryBases("?action=getDomainList")
            val arr = JSONArray(body)
            val list = mutableListOf<String>()
            for (i in 0 until arr.length()) {
                val d = arr.optString(i).trim().lowercase()
                if (d.isNotBlank() && d !in excluded) list.add(d)
            }
            if (list.isNotEmpty()) {
                domains.clear()
                domains.addAll(list)
            }
            domains.toList()
        } catch (_: Exception) {
            domains.toList()
        }
    }

    suspend fun createEmail(localPart: String): CyberTempApi.EmailResult = withContext(Dispatchers.IO) {
        try {
            var pool = domains.toList()
            if (pool.isEmpty()) pool = fetchDomains()
            if (pool.isEmpty()) {
                // hard fallback domains commonly seen on 1secmail
                pool = listOf("1secmail.com", "1secmail.org", "1secmail.net", "wwjmp.com", "kzccv.com")
                    .filter { it !in excluded }
            }
            if (pool.isEmpty()) return@withContext CyberTempApi.EmailResult("", "", false)

            val login = localPart.lowercase()
                .filter { it.isLetterOrDigit() || it == '.' || it == '_' || it == '-' }
                .take(24)
                .ifBlank { "u" + (100000..999999).random() }

            // Prefer a random domain from the live list (multi-domain resilience)
            val domain = pool.random()
            currentLogin = login
            currentDomain = domain
            val email = "$login@$domain"
            CyberTempApi.EmailResult(email, domain, true)
        } catch (_: Exception) {
            CyberTempApi.EmailResult("", "", false)
        }
    }

    fun excludeDomain(domain: String) {
        excluded.add(domain.lowercase())
        domains.removeAll { it.equals(domain, ignoreCase = true) }
    }

    suspend fun getVerificationUrl(
        emailAddr: String,
        timeoutMs: Long = 180_000L,
        onLog: ((String) -> Unit)? = null
    ): String? = withContext(Dispatchers.IO) {
        val login = emailAddr.substringBefore("@").ifBlank { currentLogin ?: return@withContext null }
        val domain = emailAddr.substringAfter("@").ifBlank { currentDomain ?: return@withContext null }
        onLog?.invoke("1secmail: polling $login@$domain …")

        val linkRegex = Regex(
            """https?://(?:click\.)?discord\.com/(?:ls/click|verify)[^\s"'<>\\)]+""",
            RegexOption.IGNORE_CASE
        )
        val codeRegex = Regex(
            """(?:discord|verification|verify|code)[^\d]{0,40}(\d{5,8})|(\d{5,8})[^\d]{0,40}(?:discord|verification)""",
            RegexOption.IGNORE_CASE
        )
        val start = System.currentTimeMillis()
        var attempt = 0
        while (System.currentTimeMillis() - start < timeoutMs) {
            attempt++
            try {
                val listBody = tryBases("?action=getMessages&login=$login&domain=$domain")
                val arr = JSONArray(listBody)
                for (i in 0 until arr.length()) {
                    val msg = arr.optJSONObject(i) ?: continue
                    val id = msg.optLong("id", -1L)
                    if (id < 0) continue
                    val subject = msg.optString("subject")
                    val from = msg.optString("from")
                    // Read full body
                    val fullBody = tryBases("?action=readMessage&login=$login&domain=$domain&id=$id")
                    val full = try { JSONObject(fullBody) } catch (_: Exception) { null }
                    val text = buildString {
                        append(subject).append('\n')
                        append(from).append('\n')
                        if (full != null) {
                            append(full.optString("textBody")).append('\n')
                            append(full.optString("htmlBody")).append('\n')
                            append(full.optString("body")).append('\n')
                        }
                        append(fullBody)
                    }
                    val link = linkRegex.find(text)?.value
                    if (link != null) {
                        onLog?.invoke("1secmail: found Discord verify link")
                        return@withContext link.trimEnd('.', ')', '"')
                    }
                    // Some flows only send a code — still useful to log
                    val code = codeRegex.find(text)?.groupValues?.drop(1)?.firstOrNull { it.isNotBlank() }
                    if (code != null) {
                        onLog?.invoke("1secmail: found possible code $code (no link)")
                    }
                }
            } catch (e: Exception) {
                if (attempt % 3 == 0) onLog?.invoke("1secmail poll: ${e.message}")
            }
            delay(4_000)
        }
        onLog?.invoke("1secmail: no Discord mail within timeout")
        null
    }
}
