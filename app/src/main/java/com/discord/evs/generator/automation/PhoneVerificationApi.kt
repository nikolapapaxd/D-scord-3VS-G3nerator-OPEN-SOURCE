package com.discord.evs.generator.automation

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Free public temp-number sources (receive SMS only).
 *
 * Active (2026):
 *  1. smsdock.net
 *  2. instant-sms.com
 *  3. anonymsms.com
 *  4. temp-number.com
 *
 * Dead / blocked (removed):
 *  - receive-smss.com
 *  - smsreceivefree.com
 *
 * These are shared public numbers — Discord often blocks them.
 * If no SMS arrives the user can verify manually and tap CONTINUE.
 */
class PhoneVerificationApi {

    private val client = OkHttpClient.Builder()
        .connectTimeout(18, TimeUnit.SECONDS)
        .readTimeout(18, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val ua = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

    data class TempNumber(
        val e164: String,
        val digits: String,
        val source: String,
        val inboxUrl: String
    )

    private var active: TempNumber? = null

    /** Returns E.164 phone string (e.g. "+12025551234") or null. */
    suspend fun getAvailableNumber(onLog: (String) -> Unit): String? = withContext(Dispatchers.IO) {
        onLog("⚠ Free public numbers are shared inboxes — Discord usually blocks them. Prefer paid SMS.")
        val candidates = mutableListOf<TempNumber>()

        // 1) SMSDock
        try {
            onLog("Fetching numbers from smsdock.net...")
            val html = get("https://smsdock.net/")
            val nums = Regex("""/number/(\d{10,15})""").findAll(html).map { it.groupValues[1] }.distinct().toList()
            nums.forEach { d ->
                candidates += TempNumber(toE164(d), d, "smsdock", "https://smsdock.net/number/$d")
            }
            onLog("  smsdock: ${nums.size} numbers")
        } catch (e: Exception) {
            onLog("smsdock error: ${e.message}")
        }

        // 2) Instant SMS
        try {
            onLog("Fetching numbers from instant-sms.com...")
            val html = get("https://instant-sms.com/")
            val nums = Regex("""/numbers/(\d{10,15})""").findAll(html).map { it.groupValues[1] }.distinct().toList()
            nums.forEach { d ->
                candidates += TempNumber(toE164(d), d, "instant-sms", "https://instant-sms.com/numbers/$d")
            }
            onLog("  instant-sms: ${nums.size} numbers")
        } catch (e: Exception) {
            onLog("instant-sms error: ${e.message}")
        }

        // 3) AnonymSMS
        try {
            onLog("Fetching numbers from anonymsms.com...")
            val html = get("https://anonymsms.com/")
            val nums = Regex("""/number/(\d{10,15})/?""").findAll(html).map { it.groupValues[1] }.distinct().toList()
            nums.forEach { d ->
                candidates += TempNumber(toE164(d), d, "anonymsms", "https://anonymsms.com/number/$d/")
            }
            onLog("  anonymsms: ${nums.size} numbers")
        } catch (e: Exception) {
            onLog("anonymsms error: ${e.message}")
        }

        // 4) Temp Number
        try {
            onLog("Fetching numbers from temp-number.com...")
            val html = get("https://temp-number.com/temporary-numbers")
            val found = Regex("""/temporary-numbers/([a-z\-]+)/(\d{10,15})""", RegexOption.IGNORE_CASE)
                .findAll(html)
                .map { it.groupValues[1] to it.groupValues[2] }
                .distinctBy { it.second }
                .toList()
            found.forEach { (country, d) ->
                candidates += TempNumber(
                    toE164(d),
                    d,
                    "temp-number",
                    "https://temp-number.com/temporary-numbers/$country/$d"
                )
            }
            onLog("  temp-number: ${found.size} numbers")
        } catch (e: Exception) {
            onLog("temp-number error: ${e.message}")
        }

        // Extra free SMS sources (FeatureFlags.EXTRA_FREE_SMS_PROVIDERS)
        if (ExtraFreeSmsProviders.isEnabled()) {
            try {
                val extra = ExtraFreeSmsProviders.collectNumbers(onLog)
                extra.forEach { n ->
                    candidates += TempNumber(n.e164, n.digits, n.source, n.inboxUrl)
                }
                onLog("  extra free SMS: ${extra.size} numbers")
            } catch (e: Exception) {
                onLog("extra free SMS error: ${e.message}")
            }
        }

        if (candidates.isEmpty()) {
            onLog("No temp number available from any free source")
            active = null
            return@withContext null
        }

        // Prefer US/CA (+1) — Discord is slightly less harsh than random overseas VoIP
        val preferred = candidates.filter {
            it.digits.startsWith("1") && it.digits.length == 11
        }.ifEmpty {
            candidates.filter { it.digits.startsWith("1") }
        }.ifEmpty { candidates }

        val pick = preferred.random()
        active = pick
        onLog("Got temp number: ${pick.e164} (${pick.source})")
        pick.e164
    }

    /**
     * Polls the inbox for [number] until a Discord-looking code appears
     * or [timeoutMs] elapses. Returns the 5–8 digit code or null.
     */
    suspend fun waitForDiscordCode(
        number: String,
        timeoutMs: Long = 90_000L,
        onLog: (String) -> Unit
    ): String? = withContext(Dispatchers.IO) {
        val start = System.currentTimeMillis()
        val digits = number.removePrefix("+").filter { it.isDigit() }
        val inboxUrls = buildList {
            active?.takeIf { it.digits == digits || it.e164.removePrefix("+") == digits }?.let {
                add(it.inboxUrl)
            }
            add("https://smsdock.net/number/$digits")
            add("https://instant-sms.com/numbers/$digits")
            add("https://anonymsms.com/number/$digits/")
            listOf("united-states", "united-kingdom", "canada", "germany", "france", "netherlands").forEach { c ->
                add("https://temp-number.com/temporary-numbers/$c/$digits")
            }
        }.distinct()

        onLog("Polling SMS inbox for $number (up to ${timeoutMs / 1000}s)…")
        var attempt = 0
        while (System.currentTimeMillis() - start < timeoutMs) {
            attempt++
            for (url in inboxUrls) {
                try {
                    val html = get(url)
                    if (html.length < 200) continue
                    val code = extractDiscordCode(html)
                    if (code != null) {
                        onLog("✓ Discord SMS code: $code")
                        return@withContext code
                    }
                } catch (_: Exception) { }
            }
            if (attempt % 3 == 0) {
                onLog("  still waiting… (${(System.currentTimeMillis() - start) / 1000}s)")
            }
            delay(2_500)
        }
        onLog("No Discord SMS code received in time")
        null
    }

    private fun extractDiscordCode(html: String): String? {
        val lower = html.lowercase()
        // Discord messages often: "Your Discord verification code is: 123456" or app-style 6-digit
        val patterns = listOf(
            Regex("""discord[^\d]{0,80}?(\d{5,8})""", RegexOption.IGNORE_CASE),
            Regex("""(\d{5,8})[^\d]{0,80}?discord""", RegexOption.IGNORE_CASE),
            Regex("""(?:verification|confirm|security)\s*code[^\d]{0,40}(\d{5,8})""", RegexOption.IGNORE_CASE),
            Regex("""code\s*(?:is|:)?\s*(\d{5,8})""", RegexOption.IGNORE_CASE),
            Regex("""(?<!\d)(\d{6})(?!\d)""")
        )
        for (re in patterns) {
            val hits = re.findAll(html).mapNotNull { m ->
                m.groupValues.drop(1).firstOrNull { it.isNotBlank() } ?: m.groupValues.getOrNull(1)
            }.filter { it.length in 5..8 }.toList()
            if (hits.isNotEmpty()) {
                // Prefer codes that appear near "discord" text
                val near = hits.firstOrNull { c ->
                    val i = lower.indexOf(c)
                    if (i < 0) false else {
                        val window = lower.substring(maxOf(0, i - 100), minOf(lower.length, i + 100))
                        "discord" in window || "verification" in window || "code" in window
                    }
                }
                return near ?: hits.first()
            }
        }
        return null
    }

    private fun toE164(digits: String): String {
        val d = digits.filter { it.isDigit() }
        return "+$d"
    }

    private fun get(url: String): String {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", ua)
            .header("Accept", "text/html,application/xhtml+xml")
            .header("Accept-Language", "en-US,en;q=0.9")
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}")
            return resp.body?.string() ?: ""
        }
    }
}
