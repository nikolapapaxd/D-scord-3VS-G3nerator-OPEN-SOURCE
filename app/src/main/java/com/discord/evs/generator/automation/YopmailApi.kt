package com.discord.evs.generator.automation

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentHashMap

/**
 * YOPmail free temp inbox (no API key).
 * Uses login cookie + inbox / mail read pages to extract Discord verify links.
 */
class YopmailApi {
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private val domains = listOf("yopmail.com", "yopmail.fr", "yopmail.net")
    private val used = ConcurrentHashMap.newKeySet<String>()

    suspend fun createEmail(localPart: String): CyberTempApi.EmailResult = withContext(Dispatchers.IO) {
        val base = localPart.lowercase().filter { it.isLetterOrDigit() || it == '.' || it == '_' }
            .ifBlank { "user" + (1000..9999).random() }
        // Prefer yopmail.com — most reliable for Discord delivery
        val domain = "yopmail.com"
        var addr = "$base@$domain"
        var n = 0
        while (addr in used && n < 20) {
            addr = "${base}${(100..999).random()}@$domain"
            n++
        }
        used.add(addr)
        CyberTempApi.EmailResult(addr, domain, true)
    }

    suspend fun getVerificationUrl(
        emailAddr: String,
        timeoutMs: Long = 120_000L,
        onLog: ((String) -> Unit)? = null
    ): String? = withContext(Dispatchers.IO) {
        val local = emailAddr.substringBefore("@").lowercase()
        val start = System.currentTimeMillis()
        onLog?.invoke("Polling YOPmail inbox for: $emailAddr")
        val ua = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120.0.0.0 Mobile Safari/537.36"
        val enc = URLEncoder.encode(local, "UTF-8")
        var tick = 0
        while (System.currentTimeMillis() - start < timeoutMs) {
            tick++
            try {
                // 1) Atom/RSS feed — stable for new mail
                val rssReq = Request.Builder()
                    .url("https://yopmail.com/en/rss?login=$enc&format=atom")
                    .header("User-Agent", ua)
                    .header("Accept", "application/atom+xml, application/xml, text/xml, */*")
                    .get()
                    .build()
                client.newCall(rssReq).execute().use { rss ->
                    if (rss.isSuccessful) {
                        val body = rss.body?.string().orEmpty()
                        val link = extractDiscordLink(body)
                        if (link != null) {
                            onLog?.invoke("YOPmail: verify link from RSS")
                            return@withContext link
                        }
                    }
                }
                // 2) Inbox HTML
                val inboxReq = Request.Builder()
                    .url("https://yopmail.com/en/inbox?login=$enc&p=1&d=&ctrl=&scrl=&spam=true&yf=HZj0&yp=GZj&yj=RZH0&v=5.2&r_info=")
                    .header("User-Agent", ua)
                    .header("Accept", "text/html,application/xhtml+xml")
                    .header("Referer", "https://yopmail.com/en/")
                    .get()
                    .build()
                client.newCall(inboxReq).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val body = resp.body?.string().orEmpty()
                        val link = extractDiscordLink(body)
                        if (link != null) {
                            onLog?.invoke("YOPmail: verify link from inbox")
                            return@withContext link
                        }
                        // mail ids in inbox → fetch mail
                        val idRx = Regex("""mail\.php\?[^"']*b=([^"'&]+)[^"']*id=([a-zA-Z0-9]+)""")
                        val ids = idRx.findAll(body).map { it.groupValues[2] }.distinct().take(5).toList()
                        for (id in ids) {
                            val mailReq = Request.Builder()
                                .url("https://yopmail.com/en/mail?b=$enc&id=$id")
                                .header("User-Agent", ua)
                                .header("Referer", "https://yopmail.com/en/inbox?login=$enc")
                                .get()
                                .build()
                            client.newCall(mailReq).execute().use { mr ->
                                if (mr.isSuccessful) {
                                    val mb = mr.body?.string().orEmpty()
                                    val ml = extractDiscordLink(mb)
                                    if (ml != null) {
                                        onLog?.invoke("YOPmail: verify link from mail $id")
                                        return@withContext ml
                                    }
                                }
                            }
                        }
                    }
                }
                if (tick % 4 == 0) onLog?.invoke("YOPmail empty so far… ($tick)")
            } catch (e: Exception) {
                if (tick % 5 == 0) onLog?.invoke("YOPmail poll error: ${e.message}")
            }
            delay(3000L)
        }
        onLog?.invoke("YOPmail: no verify link within timeout")
        null
    }

    private fun extractDiscordLink(body: String): String? {
        val patterns = listOf(
            Regex("""https?://(?:click\.)?discord(?:app)?\.com/(?:ls/click|verify)[^\s"'<>\\]+""", RegexOption.IGNORE_CASE),
            Regex("""https?://discord\.com/verify[^\s"'<>\\]*""", RegexOption.IGNORE_CASE)
        )
        for (rx in patterns) {
            val m = rx.find(body) ?: continue
            return m.value.replace("&amp;", "&").trimEnd('.', ',', ')', ';', '\'', '"')
        }
        return null
    }
}
