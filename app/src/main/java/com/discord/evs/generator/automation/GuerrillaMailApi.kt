package com.discord.evs.generator.automation

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Guerrilla Mail free API (no key).
 *
 * As of 2026-09 the public API always issues @guerrillamailblock.com and ignores
 * domain / set_email_user rotation. That domain is Discord-blocked, so we refuse
 * it and return empty so AccountGenerator can rotate providers / report telemetry.
 *
 * API: https://api.guerrillamail.com/ajax.php
 * Verified: get_email_address, set_email_user, scramble, site Referers all return block domain.
 */
class GuerrillaMailApi {

    private val client = OkHttpClient.Builder()
        .connectTimeout(25, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    companion object {
        private const val BASE = "https://api.guerrillamail.com/ajax.php"
        private const val UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"

        /** Historical Guerrilla family domains (API no longer assigns them publicly). */
        val PREFERRED_DOMAINS = listOf(
            "grr.la",
            "sharklasers.com",
            "guerrillamail.com",
            "guerrillamail.info",
            "guerrillamail.net",
            "guerrillamail.org",
            "guerrillamail.biz",
            "pokemail.net",
            "spam4.me"
        )

        val BANNED_DOMAINS = setOf(
            "guerrillamailblock.com",
            "guerrillamailblock.org"
        )

        /** True when API only returns banned domains (current production state). */
        @Volatile
        var lastForcedBlockDomain: Boolean = false
            private set

        @Volatile
        var lastRawDomain: String = ""
            private set
    }

    private var sidToken: String? = null
    private var currentEmail: String? = null
    private val excludedDomains = mutableSetOf<String>().apply { addAll(BANNED_DOMAINS) }

    suspend fun createEmail(username: String): CyberTempApi.EmailResult = withContext(Dispatchers.IO) {
        try {
            val local = username.filter { it.isLetterOrDigit() || it == '_' || it == '.' }
                .ifBlank { "user${(10000..99999).random()}" }

            // Session
            val session = fetchJson("$BASE?f=get_email_address&ip=127.0.0.1&agent=Mozilla_foo")
                ?: return@withContext emptyAndFlag("empty get_email_address")
            sidToken = session.optString("sid_token", "")
            var email = session.optString("email_addr", "")
            var domain = email.substringAfter("@", "").lowercase()
            lastRawDomain = domain

            // Attempt domain rotation (API currently ignores this and stays on block domain)
            if (sidToken.isNullOrBlank().not()) {
                for (tryDomain in PREFERRED_DOMAINS) {
                    if (tryDomain in excludedDomains || tryDomain in BANNED_DOMAINS) continue
                    val set = fetchJson(
                        "$BASE?f=set_email_user&email_user=$local&sid_token=$sidToken&lang=en&domain=$tryDomain"
                    )
                    if (set != null) {
                        val e2 = set.optString("email_addr", "")
                        if (e2.isNotBlank()) {
                            email = e2
                            domain = e2.substringAfter("@", "").lowercase()
                            lastRawDomain = domain
                        }
                        val sid2 = set.optString("sid_token", "")
                        if (sid2.isNotBlank()) sidToken = sid2
                    }
                    if (domain.isNotBlank() && domain !in BANNED_DOMAINS && domain !in excludedDomains) {
                        lastForcedBlockDomain = false
                        currentEmail = email
                        return@withContext CyberTempApi.EmailResult(email, domain, true)
                    }
                }
            }

            // Only banned / empty → fail closed (do not hand Discord a block domain)
            if (domain in BANNED_DOMAINS || domain.isBlank()) {
                return@withContext emptyAndFlag(
                    if (domain.isBlank()) "no domain from API" else "forced @$domain"
                )
            }
            if (domain in excludedDomains) {
                return@withContext emptyAndFlag("excluded @$domain")
            }
            lastForcedBlockDomain = false
            currentEmail = email
            CyberTempApi.EmailResult(email, domain, true)
        } catch (_: Exception) {
            emptyAndFlag("exception")
        }
    }

    private fun emptyAndFlag(reason: String): CyberTempApi.EmailResult {
        lastForcedBlockDomain = true
        lastRawDomain = lastRawDomain.ifBlank { "none" }
        // reason stored only for logs via lastRawDomain suffix
        lastRawDomain = "$lastRawDomain|$reason"
        currentEmail = null
        return CyberTempApi.EmailResult("", "", false)
    }

    private fun emptyResult() = emptyAndFlag("empty")

    private fun fetchJson(url: String): JSONObject? {
        return try {
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", UA)
                .header("Accept", "application/json,text/javascript,*/*")
                .get()
                .build()
            val body = client.newCall(req).execute().use { it.body?.string().orEmpty() }
            if (body.isBlank()) null else JSONObject(body)
        } catch (_: Exception) {
            null
        }
    }

    fun excludeDomain(domain: String) {
        val d = domain.lowercase().removePrefix("@")
        if (d.isNotBlank()) excludedDomains.add(d)
    }

    suspend fun getVerificationUrl(
        maxWaitMs: Long = 90_000L,
        onLog: ((String) -> Unit)? = null
    ): String? = withContext(Dispatchers.IO) {
        val sid = sidToken ?: return@withContext null
        val deadline = System.currentTimeMillis() + maxWaitMs
        var seq = 0
        while (System.currentTimeMillis() < deadline) {
            try {
                val obj = fetchJson("$BASE?f=check_email&seq=$seq&sid_token=$sid") ?: run {
                    delay(3_000L)
                    return@run null
                }
                if (obj != null) {
                    val list = obj.optJSONArray("list")
                    if (list != null) {
                        for (i in 0 until list.length()) {
                            val m = list.optJSONObject(i) ?: continue
                            val mailId = m.optString("mail_id", "")
                            if (mailId.isBlank()) continue
                            val subject = m.optString("mail_subject", "").lowercase()
                            val from = m.optString("mail_from", "").lowercase()
                            if ("verify" !in subject && "discord" !in from && "discord" !in subject) continue
                            onLog?.invoke("Found mail: ${m.optString("mail_subject", "")}")
                            val full = fetchMail(sid, mailId)
                            val url = extractVerifyUrl(full)
                            if (url != null) return@withContext url
                        }
                    }
                    seq = obj.optInt("seq", seq)
                }
            } catch (e: Exception) {
                onLog?.invoke("Guerrilla poll error: ${e.message}")
            }
            delay(3_000L)
        }
        null
    }

    private fun fetchMail(sid: String, mailId: String): String {
        return try {
            val req = Request.Builder()
                .url("$BASE?f=fetch_email&email_id=$mailId&sid_token=$sid")
                .header("User-Agent", UA)
                .get()
                .build()
            client.newCall(req).execute().use { it.body?.string().orEmpty() }
        } catch (_: Exception) {
            ""
        }
    }

    private fun extractVerifyUrl(body: String): String? {
        if (body.isBlank()) return null
        val patterns = listOf(
            Regex("""https://click\.discord\.com/ls/click\?[^"'\s<>]+""", RegexOption.IGNORE_CASE),
            Regex("""https://discord\.com/verify[^"'\s<>]+""", RegexOption.IGNORE_CASE),
            Regex("""https://discord\.com/[^"'\s<>]*verify[^"'\s<>]*""", RegexOption.IGNORE_CASE)
        )
        for (p in patterns) {
            p.find(body)?.value?.let { return it.replace("&amp;", "&") }
        }
        return null
    }
}
