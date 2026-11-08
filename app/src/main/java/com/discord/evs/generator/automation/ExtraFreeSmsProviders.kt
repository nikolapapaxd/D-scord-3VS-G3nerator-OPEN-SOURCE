package com.discord.evs.generator.automation

import com.discord.evs.generator.features.FeatureFlags
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Additional free public SMS / temp-number sources.
 * Completely inert unless [FeatureFlags.EXTRA_FREE_SMS_PROVIDERS] is true.
 *
 * Primary sources live in [PhoneVerificationApi]. These are experimental extras.
 * Public shared numbers are frequently blocked by Discord.
 */
object ExtraFreeSmsProviders {

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    private val ua = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

    data class TempNumber(
        val e164: String,
        val digits: String,
        val source: String,
        val inboxUrl: String
    )

    fun isEnabled(): Boolean = FeatureFlags.EXTRA_FREE_SMS_PROVIDERS

    suspend fun collectNumbers(onLog: (String) -> Unit): List<TempNumber> =
        withContext(Dispatchers.IO) {
            if (!isEnabled()) return@withContext emptyList()
            val out = mutableListOf<TempNumber>()

            // quackr.io style pages (best-effort scrape)
            try {
                onLog("Extra SMS: probing quackr.io...")
                val html = get("https://quackr.io/")
                Regex("""(?:tel:|phone[=:]\s*|/)(\+?\d{10,15})""", RegexOption.IGNORE_CASE)
                    .findAll(html)
                    .map { it.groupValues[1].filter { ch -> ch.isDigit() || ch == '+' } }
                    .filter { it.filter(Char::isDigit).length in 10..15 }
                    .distinct()
                    .take(8)
                    .forEach { raw ->
                        val digits = raw.filter { it.isDigit() }
                        out += TempNumber(
                            e164 = if (raw.startsWith("+")) raw else toE164(digits),
                            digits = digits,
                            source = "quackr",
                            inboxUrl = "https://quackr.io/"
                        )
                    }
            } catch (e: Exception) {
                onLog("Extra SMS quackr: ${e.message}")
            }

            // receive-sms-online.info style (often blocked / flaky)
            try {
                onLog("Extra SMS: probing receive-sms-online.info...")
                val html = get("https://www.receive-sms-online.info/")
                Regex("""(\+?\d{10,15})""")
                    .findAll(html)
                    .map { it.groupValues[1].filter { ch -> ch.isDigit() } }
                    .filter { it.length in 10..15 }
                    .distinct()
                    .take(5)
                    .forEach { digits ->
                        out += TempNumber(
                            e164 = toE164(digits),
                            digits = digits,
                            source = "receive-sms-online",
                            inboxUrl = "https://www.receive-sms-online.info/"
                        )
                    }
            } catch (e: Exception) {
                onLog("Extra SMS receive-sms-online: ${e.message}")
            }

            onLog("Extra SMS: ${out.size} candidate numbers")
            out
        }

    /**
     * Poll inbox for a Discord SMS code. Returns the 6-digit code or null.
     * Not fully wired while feature flag is off.
     */
    @Suppress("UNUSED_PARAMETER")
    suspend fun pollSmsCode(
        number: TempNumber,
        timeoutMs: Long,
        onLog: (String) -> Unit
    ): String? = withContext(Dispatchers.IO) {
        if (!isEnabled()) return@withContext null
        onLog("Extra SMS poll for ${number.source} not fully wired (gated)")
        null
    }

    private fun get(url: String): String {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", ua)
            .header("Accept", "text/html,application/xhtml+xml")
            .get()
            .build()
        client.newCall(req).execute().use { resp ->
            return resp.body?.string().orEmpty()
        }
    }

    private fun toE164(digits: String): String {
        val d = digits.filter { it.isDigit() }
        return when {
            d.startsWith("1") && d.length == 11 -> "+$d"
            d.length == 10 -> "+1$d"
            d.startsWith("44") -> "+$d"
            else -> "+$d"
        }
    }
}
