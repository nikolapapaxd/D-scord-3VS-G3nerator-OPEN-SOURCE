package com.discord.evs.generator.automation

import android.content.Context
import com.discord.evs.generator.data.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

/**
 * Custom mail pool — user-supplied addresses (Gmail, Outlook, own domains, etc.).
 * Emails are consumed from the pool (removed after use).
 * Verification polling is limited: Discord API status poll is done by AccountGenerator.
 * Inbox polling is best-effort for providers that expose public/IMAP-less links; for
 * real Gmail/Outlook the user must complete the email link manually or use app passwords
 * outside this free path. Status is marked unverified if not confirmed within timeout.
 */
class CustomMailApi(private val context: Context) {

    private val prefs = Prefs(context)
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    companion object {
        private val EMAIL_RE = Pattern.compile(
            "^[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}$",
            Pattern.CASE_INSENSITIVE
        )

        fun isValidEmail(s: String): Boolean = EMAIL_RE.matcher(s.trim()).matches()

        /** Parse lines from txt / paste; keep only valid unique emails. */
        fun parseEmails(raw: String): List<String> {
            return raw.lines()
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") }
                .filter { isValidEmail(it) }
                .distinctBy { it.lowercase() }
        }
    }

    data class EmailResult(
        val address: String,
        val domain: String,
        val domainFromApi: Boolean = false
    )

    fun remainingCount(): Int = loadPool().size

    fun loadPool(): MutableList<String> {
        return parseEmails(prefs.customMails).toMutableList()
    }

    fun savePool(list: List<String>) {
        prefs.customMails = list.joinToString("\n")
    }

    /** Import from multi-line text (manual or file contents). Returns how many new were added. */
    fun importEmails(raw: String): Int {
        val existing = loadPool().map { it.lowercase() }.toMutableSet()
        val incoming = parseEmails(raw)
        var added = 0
        val merged = loadPool()
        for (e in incoming) {
            if (e.lowercase() !in existing) {
                merged.add(e)
                existing.add(e.lowercase())
                added++
            }
        }
        savePool(merged)
        return added
    }

    /** Pop next email from pool. Returns null if empty. */
    suspend fun createEmail(@Suppress("UNUSED_PARAMETER") username: String = ""): EmailResult? = withContext(Dispatchers.IO) {
        val pool = loadPool()
        if (pool.isEmpty()) return@withContext null
        val addr = pool.removeAt(0)
        savePool(pool)
        val domain = addr.substringAfter("@", "")
        EmailResult(addr, domain, false)
    }

    /**
     * When IMAP is enabled in Tools → MORE, fetches Discord verify link from the real inbox.
     * Otherwise returns null so AccountGenerator falls back to Discord API polling.
     */
    suspend fun getVerificationUrl(
        emailAddr: String,
        timeoutMs: Long = 120_000L,
        onLog: ((String) -> Unit)? = null
    ): String? = withContext(Dispatchers.IO) {
        if (prefs.imapEnabled && prefs.imapPass.isNotBlank()) {
            onLog?.invoke("Custom mail: IMAP fetch for $emailAddr…")
            val fetcher = ImapMailFetcher(prefs)
            val url = fetcher.fetchVerificationUrl(emailAddr, timeoutMs, onLog)
            if (url != null) return@withContext url
            onLog?.invoke("Custom mail: IMAP found no link — Discord API poll will continue")
            return@withContext null
        }
        onLog?.invoke("Custom mail: IMAP off — waiting for Discord API verify status for $emailAddr…")
        null
    }

    fun excludeDomain(@Suppress("UNUSED_PARAMETER") domain: String) {
        // Custom mails are exact addresses; domain exclusion not applicable.
    }
}
