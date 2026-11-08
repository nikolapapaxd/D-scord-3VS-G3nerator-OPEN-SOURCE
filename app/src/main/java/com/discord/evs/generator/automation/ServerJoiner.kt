package com.discord.evs.generator.automation

import com.discord.evs.generator.data.Account
import com.discord.evs.generator.data.Prefs
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/**
 * Server Joiner — joins Discord servers using generated or manually supplied tokens.
 *
 * Login flow:
 *   1. Load discord.com/login  — establishes discord.com domain context in WebView
 *   2. Wait for page to finish loading  (via notifyPageFinished)
 *   3. Inject token into localStorage  — works because we are on the correct domain
 *   4. Load discord.com/channels/@me  — Discord reads token and authenticates
 *   5. Verify the URL changed to /channels/ (account is logged in)
 *   6. Use Discord's in-app guild UI to open the join dialog
 *   7. Paste the invite code and click Join Server
 *   8. Poll for join result, captcha, or success
 */
class ServerJoiner(private val prefs: Prefs) {

    enum class JoinResult { JOINED, ALREADY_MEMBER, CAPTCHA_NEEDED, FAILED, INVALID_LINK, INFO }

    interface JsListener {
        fun loadUrl(url: String)
        fun evaluateJs(js: String, onResult: ((String?) -> Unit)? = null)
        fun onStateChanged(newState: State)
        fun onLog(account: String, server: String, result: JoinResult, message: String)
        fun onProgress(done: Int, total: Int)
        fun onPageFinished(url: String)
        fun onCaptchaPrompt(onSolved: () -> Unit)
    }

    enum class State { IDLE, RUNNING, STOPPING }

    private var state = State.IDLE
    private var listener: JsListener? = null
    private var pageLoadContinuation: CancellableContinuation<String>? = null
    private var captchaSolvedCallback: (() -> Unit)? = null

    fun setJsListener(l: JsListener?) { listener = l }

    /** Called by JoinerFragment's WebViewClient.onPageFinished */
    fun notifyPageFinished(url: String) {
        pageLoadContinuation?.let { cont ->
            if (cont.isActive) cont.resume(url)
        }
        pageLoadContinuation = null
        listener?.onPageFinished(url)
    }

    /** Called by JoinerFragment when user taps "CAPTCHA SOLVED" */
    fun notifyCaptchaSolved() {
        captchaSolvedCallback?.invoke()
        captchaSolvedCallback = null
    }

    // ── Public entry points ───────────────────────────────────────────────────

    suspend fun joinAll(accounts: List<Account>, inviteCodes: List<String>) {
        if (state == State.RUNNING) return
        state = State.RUNNING
        listener?.onStateChanged(State.RUNNING)

        val total = accounts.size * inviteCodes.size
        var done = 0

        outer@ for (account in accounts) {
            if (state == State.STOPPING) break
            for (code in inviteCodes) {
                if (state == State.STOPPING) break@outer
                val result = joinWithToken(account.token, code)
                done++
                listener?.onLog(
                    account.email.ifEmpty { account.token.take(20) + "…" },
                    "discord.gg/$code",
                    result,
                    resultMessage(result)
                )
                listener?.onProgress(done, total)
                if (state != State.STOPPING) delay((prefs.joinerDelaySeconds.coerceAtLeast(1)) * 1000L)
            }
        }

        state = State.IDLE
        listener?.onStateChanged(State.IDLE)
    }

    suspend fun joinAllRaw(tokens: List<String>, inviteCodes: List<String>) {
        if (state == State.RUNNING) return
        state = State.RUNNING
        listener?.onStateChanged(State.RUNNING)

        val total = tokens.size * inviteCodes.size
        var done = 0

        outer@ for (token in tokens) {
            if (state == State.STOPPING) break
            for (code in inviteCodes) {
                if (state == State.STOPPING) break@outer
                val result = joinWithToken(token, code)
                done++
                listener?.onLog(
                    token.take(18) + "…",
                    "discord.gg/$code",
                    result,
                    resultMessage(result)
                )
                listener?.onProgress(done, total)
                if (state != State.STOPPING) delay((prefs.joinerDelaySeconds.coerceAtLeast(1)) * 1000L)
            }
        }

        state = State.IDLE
        listener?.onStateChanged(State.IDLE)
    }

    fun stop() {
        if (state == State.RUNNING) {
            state = State.STOPPING
            listener?.onStateChanged(State.STOPPING)
        }
    }

    fun getState() = state

    // ── Step logging helper ───────────────────────────────────────────────────

    private fun stepLog(msg: String) {
        listener?.onLog("", "", JoinResult.INFO, msg)
    }

    // ── Core join logic ───────────────────────────────────────────────────────

    private suspend fun joinWithToken(token: String, inviteCode: String): JoinResult {
        val code = extractCode(inviteCode) ?: run {
            stepLog("✗ Invalid invite link: $inviteCode")
            return JoinResult.INVALID_LINK
        }

        val clean = token.trim().removePrefix("Bearer ").trim()
        if (clean.length < 20) {
            stepLog("✗ Token empty / too short")
            return JoinResult.FAILED
        }

        // Step 1 — same as DC Tools Login Token: prove the token works via API
        stepLog("→ Validating token (GET /users/@me)…")
        val login = validateTokenLogin(clean)
        if (!login.ok) {
            stepLog("✗ Token login failed: ${login.message}")
            return JoinResult.FAILED
        }
        stepLog("✓ Token logged in as ${login.tag}")

        // Isolate sessions between tokens (WebView path)
        try {
            android.webkit.CookieManager.getInstance().removeAllCookies(null)
            android.webkit.CookieManager.getInstance().flush()
        } catch (_: Exception) {}

        val mode = prefs.joinerMode.lowercase().trim()
        when (mode) {
            "api" -> {
                stepLog("→ Using API joiner…")
                return joinViaApi(clean, code)
            }
            "webview", "web" -> {
                stepLog("→ Using WebView joiner…")
                return joinViaWebView(clean, code)
            }
            else -> {
                // hybrid (default): API first for speed, WebView fallback on captcha/fail
                stepLog("→ Hybrid joiner (API first)…")
                val apiResult = joinViaApi(clean, code)
                if (apiResult == JoinResult.JOINED || apiResult == JoinResult.ALREADY_MEMBER || apiResult == JoinResult.INVALID_LINK) {
                    stepLog("✓ Hybrid: API path finished with $apiResult (skipped WebView)")
                    return apiResult
                }
                if (apiResult == JoinResult.CAPTCHA_NEEDED) {
                    stepLog("  API hit captcha — falling back to WebView…")
                } else {
                    stepLog("  API join failed — falling back to WebView…")
                }
                return joinViaWebView(clean, code)
            }
        }
    }

    /** DC Tools Login Token equivalent — REST validate before any join. */
    private data class TokenLoginResult(val ok: Boolean, val tag: String = "", val message: String = "")

    private suspend fun validateTokenLogin(token: String): TokenLoginResult = withContext(Dispatchers.IO) {
        try {
            val client = OkHttpClient.Builder()
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .build()
            val ua = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) discord/1.0.9177 Chrome/130.0.6723.191 Electron/33.2.0 Safari/537.36"
            val superProps = "eyJvcyI6IldpbmRvd3MiLCJicm93c2VyIjoiRGlzY29yZCBDbGllbnQiLCJyZWxlYXNlX2NoYW5uZWwiOiJzdGFibGUiLCJjbGllbnRfdmVyc2lvbiI6IjEuMC45MTc3Iiwib3NfdmVyc2lvbiI6IjEwLjAuMjI2MzEiLCJvc19hcmNoIjoieDY0Iiwic3lzdGVtX2xvY2FsZSI6ImVuLVVTIiwiYnJvd3Nlcl91c2VyX2FnZW50IjoiTW96aWxsYS81LjAgKFdpbmRvd3MgTlQgMTAuMDsgV2luNjQ7IHg2NCkgQXBwbGVXZWJLaXQvNTM3LjM2IChLSFRNTCwgbGlrZSBHZWNrbykgZGlzY29yZC8xLjAuOTE3NyBDaHJvbWUvMTMwLjAuNjcyMy4xOTEgRWxlY3Ryb24vMzMuMi4wIFNhZmFyaS81MzcuMzYiLCJicm93c2VyX3ZlcnNpb24iOiIzMy4yLjAiLCJjbGllbnRfYnVpbGRfbnVtYmVyIjozNzE0NjAsImNsaWVudF9ldmVudF9zb3VyY2UiOm51bGx9"
            val req = Request.Builder()
                .url("https://discord.com/api/v10/users/@me")
                .get()
                .header("Authorization", token)
                .header("Content-Type", "application/json")
                .header("User-Agent", ua)
                .header("X-Super-Properties", superProps)
                .header("X-Discord-Locale", "en-US")
                .build()
            client.newCall(req).execute().use { resp ->
                if (resp.code == 401 || resp.code == 403) {
                    return@withContext TokenLoginResult(false, message = "HTTP ${resp.code} invalid/locked")
                }
                if (!resp.isSuccessful) {
                    return@withContext TokenLoginResult(false, message = "HTTP ${resp.code}")
                }
                val body = resp.body?.string().orEmpty()
                val obj = org.json.JSONObject(body)
                val name = obj.optString("username", "?")
                val disc = obj.optString("discriminator", "0")
                val id = obj.optString("id", "")
                val tag = if (disc.isNotBlank() && disc != "0") "$name#$disc" else "$name ($id)"
                TokenLoginResult(true, tag = tag)
            }
        } catch (e: Exception) {
            TokenLoginResult(false, message = e.message ?: "network error")
        }
    }

    /** Direct Discord API invite accept — often captcha/rate-limited. */
    private suspend fun joinViaApi(token: String, code: String): JoinResult = withContext(Dispatchers.IO) {
        if (state == State.STOPPING) return@withContext JoinResult.FAILED
        try {
            val client = OkHttpClient.Builder()
                .connectTimeout(25, TimeUnit.SECONDS)
                .readTimeout(25, TimeUnit.SECONDS)
                .followRedirects(true)
                .build()
            val cleanToken = token.trim().removePrefix("Bearer ").trim()
            stepLog("  Invite code: $code")
            stepLog("  Token prefix: ${cleanToken.take(12)}…")

            // 1) Resolve invite (optional, helps diagnose 404)
            try {
                val getReq = Request.Builder()
                    .url("https://discord.com/api/v9/invites/$code?with_counts=true")
                    .get()
                    .header("Authorization", cleanToken)
                    .header("Content-Type", "application/json")
                    .header("Accept", "*/*")
                    .header("Origin", "https://discord.com")
                    .header("Referer", "https://discord.com/channels/@me")
                    .header("X-Discord-Locale", "en-US")
                    .header("X-Debug-Options", "bugReporterEnabled")
                    .header("User-Agent", "Discord-Android/247012 (android; 14; 1080x2400; samsung; SM-S911B; ; en_US; 247012)")
                    .build()
                client.newCall(getReq).execute().use { gr ->
                    stepLog("  Invite lookup HTTP ${gr.code}")
                    val gb = gr.body?.string() ?: ""
                    if (gr.code == 404) {
                        stepLog("  Invite not found (404) — check the link/code")
                        return@withContext JoinResult.INVALID_LINK
                    }
                    if (gr.code == 200) {
                        stepLog("  Invite OK: ${gb.take(100)}")
                    }
                }
            } catch (e: Exception) {
                stepLog("  Invite lookup error: ${e.message}")
            }

            if (state == State.STOPPING) return@withContext JoinResult.FAILED

            // 2) Accept invite. Empty body works for most invites; session_id optional.
            val sessionId = java.util.UUID.randomUUID().toString().replace("-", "").take(16)
            val bodyJson = """{"session_id":"$sessionId"}"""
            val body = bodyJson.toRequestBody("application/json".toMediaType())
            val ua = "Discord-Android/247012 (android; 14; 1080x2400; samsung; SM-S911B; ; en_US; 247012)"
            val superProps = "eyJvcyI6IldpbmRvd3MiLCJicm93c2VyIjoiQ2hyb21lIiwiZGV2aWNlIjoiIiwic3lzdGVtX2xvY2FsZSI6ImVuLVVTIiwiYnJvd3Nlcl91c2VyX2FnZW50IjoiTW96aWxsYS81LjAgKFdpbmRvd3MgTlQgMTAuMDsgV2luNjQ7IHg2NCkgQXBwbGVXZWJLaXQvNTM3LjM2IChLSFRNTCwgbGlrZSBHZWNrbykgQ2hyb21lLzEyNi4wLjAuMCBTYWZhcmkvNTM3LjM2IiwiYnJvd3Nlcl92ZXJzaW9uIjoiMTI2LjAuMC4wIiwib3NfdmVyc2lvbiI6IjEwIiwicmVmZXJyZXIiOiIiLCJyZWZlcnJpbmdfZG9tYWluIjoiIiwicmVmZXJyZXJfY3VycmVudCI6IiIsInJlZmVycmluZ19kb21haW5fY3VycmVudCI6IiIsInJlbGVhc2VfY2hhbm5lbCI6InN0YWJsZSIsImNsaWVudF9idWlsZF9udW1iZXIiOjMwMTM3MCwiY2xpZW50X2V2ZW50X3NvdXJjZSI6bnVsbH0="
            fun buildJoinRequest(apiVer: String) = Request.Builder()
                .url("https://discord.com/api/$apiVer/invites/$code")
                .post(body)
                .header("Authorization", cleanToken)
                    .header("Content-Type", "application/json")
                    .header("Accept", "*/*")
                    .header("Origin", "https://discord.com")
                    .header("Referer", "https://discord.com/channels/@me")
                    .header("X-Discord-Locale", "en-US")
                    .header("X-Debug-Options", "bugReporterEnabled")
                .header("Content-Type", "application/json")
                .header("Accept", "*/*")
                .header("Accept-Language", "en-US,en;q=0.9")
                .header("Origin", "https://discord.com")
                .header("Referer", "https://discord.com/channels/@me")
                .header("User-Agent", ua)
                .header("X-Super-Properties", superProps)
                .header("X-Discord-Locale", "en-US")
                .header("X-Discord-Timezone", "America/New_York")
                .header("Sec-Fetch-Dest", "empty")
                .header("Sec-Fetch-Mode", "cors")
                .header("Sec-Fetch-Site", "same-origin")
                .build()

            // Prefer v9 (web client), fall back to v10 once
            var resp = client.newCall(buildJoinRequest("v9")).execute()
            var codeHttp = resp.code
            var respBody = resp.body?.string() ?: ""
            if (codeHttp == 404 || codeHttp == 405) {
                resp = client.newCall(buildJoinRequest("v10")).execute()
                codeHttp = resp.code
                respBody = resp.body?.string() ?: ""
            }
            stepLog("  API join HTTP $codeHttp")
            if (respBody.isNotBlank()) stepLog("  API body: ${respBody.take(220)}")
            val low = respBody.lowercase()
            when (codeHttp) {
                200, 204 -> {
                    stepLog("✓ Joined via API")
                    JoinResult.JOINED
                }
                400, 403 -> {
                    when {
                        "captcha" in low || "captcha_key" in low -> {
                            stepLog("  Captcha required for API join — use WebView mode")
                            JoinResult.CAPTCHA_NEEDED
                        }
                        "already" in low || "\"code\": 150001" in low || "150001" in low -> {
                            stepLog("= Already a member (API)")
                            JoinResult.ALREADY_MEMBER
                        }
                        "10006" in low || "unknown invite" in low -> JoinResult.INVALID_LINK
                        "10008" in low -> {
                            // Unknown Message — usually bad invite payload / wrong resource; treat as invalid
                            stepLog("  Discord code 10008 (unknown resource) — check invite code")
                            JoinResult.INVALID_LINK
                        }
                        "50013" in low || "missing permissions" in low -> JoinResult.FAILED
                        else -> {
                            stepLog("  API join failed: HTTP $codeHttp")
                            JoinResult.FAILED
                        }
                    }
                }
                401 -> {
                    stepLog("  Token rejected (401)")
                    JoinResult.FAILED
                }
                404 -> {
                    stepLog("  Invite 404 on join — invalid/expired code")
                    JoinResult.INVALID_LINK
                }
                429 -> {
                    stepLog("  Rate limited (429) — slow down joiner delay")
                    JoinResult.FAILED
                }
                else -> {
                    stepLog("  API join unexpected HTTP $codeHttp")
                    JoinResult.FAILED
                }
            }
        } catch (e: Exception) {
            stepLog("  API error: ${e.message}")
            JoinResult.FAILED
        }
    }

    /**
     * WebView path (recommended):
     *  1) Login via token on discord.com
     *  2) Navigate directly to /invite/{code} (more reliable than UI + button hunting)
     *  3) Click Accept/Join if shown
     *  4) Poll for success / already member / captcha
     */
    private suspend fun joinViaWebView(token: String, code: String): JoinResult = withContext(Dispatchers.Main) {
        if (state == State.STOPPING) return@withContext JoinResult.FAILED
        stepLog("→ Loading discord.com/login…")
        listener?.loadUrl("https://discord.com/login")
        val loginUrl = waitForPageLoad(14_000)
        stepLog("  Page: $loginUrl")
        delay(400)

        if (!ensureLogin(token)) {
            stepLog("✗ Login failed — token may be invalid/locked")
            return@withContext JoinResult.FAILED
        }
        stepLog("✓ Logged in")
        delay(400)

        // Direct invite page — skips fragile + / Add Server UI path
        val inviteUrl = "https://discord.com/invite/$code"
        stepLog("→ Opening invite: $inviteUrl")
        listener?.loadUrl(inviteUrl)
        waitForPageLoad(14_000)
        delay(900)

        // Try Accept / Join Server buttons (multiple attempts, broader selectors)
        stepLog("→ Looking for Accept / Join button…")
        var clicked = clickUiButton(buildClickInviteAcceptJs())
        if (!clicked) {
            stepLog("  Accept not found — reload invite once…")
            listener?.loadUrl(inviteUrl)
            waitForPageLoad(12_000)
            delay(700)
            clicked = clickUiButton(buildClickInviteAcceptJs())
        }
        if (!clicked) {
            // Fallback: old dialog flow if invite page didn't show a button
            stepLog("  No invite button — trying Add Server dialog flow…")
            listener?.loadUrl("https://discord.com/channels/@me")
            waitForPageLoad(12_000)
            delay(700)
            if (clickUiButton(buildClickAddServerJs())) {
                delay(600)
                if (clickUiButton(buildClickJoinServerDialogJs())) {
                    delay(600)
                    listener?.evaluateJs(buildPasteInviteCodeJs(code))
                    delay(600)
                    clickUiButton(buildClickSubmitJoinJs())
                }
            }
        }

        pollJoinResult()
    }

    private suspend fun ensureLogin(token: String): Boolean {
        if (state == State.STOPPING) return false
        stepLog("→ Logging in with Discord token (WebView)…")
        // Must be on discord.com before touching localStorage — wait longer for first paint
        listener?.loadUrl("about:blank")
        delay(400)
        listener?.loadUrl("https://discord.com/login")
        val loginPage = waitForPageLoad(22_000)
        if (state == State.STOPPING) return false
        stepLog("  Login page: $loginPage")
        if (!loginPage.contains("discord.com")) {
            stepLog("  Page is not discord.com — reloading once")
            listener?.loadUrl("https://discord.com/login")
            waitForPageLoad(18_000)
            delay(1500)
        }
        // Extra settle time so localStorage is available
        delay(1200)
        if (injectTokenAndVerify(token)) return true
        if (state == State.STOPPING) return false
        stepLog("  Retry login — app shell…")
        listener?.loadUrl("https://discord.com/app")
        waitForPageLoad(16_000)
        delay(1000)
        if (injectTokenAndVerify(token)) return true
        stepLog("  Retry login — channels/@me bare…")
        listener?.loadUrl("https://discord.com/channels/@me")
        waitForPageLoad(14_000)
        delay(900)
        return injectTokenAndVerify(token)
    }

    private suspend fun evalJsSuspend(js: String, timeoutMs: Long = 5_000L): String {
        return withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { cont ->
                var resumed = false
                listener?.evaluateJs(js) { r ->
                    if (!resumed && cont.isActive) {
                        resumed = true
                        cont.resume(r?.trim()?.trim('"') ?: "")
                    }
                } ?: run {
                    if (!resumed && cont.isActive) {
                        resumed = true
                        cont.resume("")
                    }
                }
            }
        } ?: ""
    }

    private suspend fun injectTokenAndVerify(token: String): Boolean {
        if (state == State.STOPPING) return false
        // DC Tools login-token flow:
        // inject token into localStorage (iframe + page) → reload → open /channels/@me
        var injectResult = evalJsSuspend(buildTokenInjectJs(token))
        stepLog("  Token inject result: $injectResult")
        if (injectResult.startsWith("ERROR") || injectResult == "NO_LOCALSTORAGE" || injectResult == "WRONG_HOST" || injectResult.isBlank()) {
            listener?.loadUrl("https://discord.com/login")
            waitForPageLoad(14_000)
            if (state == State.STOPPING) return false
            delay(1200)
            injectResult = evalJsSuspend(buildTokenInjectJs(token))
            stepLog("  Token inject retry: $injectResult")
            if (injectResult.startsWith("ERROR") || injectResult == "NO_LOCALSTORAGE" || injectResult == "WRONG_HOST" || injectResult.isBlank()) {
                stepLog("  Inject failed — token valid via API but WebView storage blocked")
                return false
            }
        }
        // Force Discord to re-read token (same as login-token setTimeout reload)
        stepLog("  Reloading after inject…")
        evalJsSuspend("try{location.reload();}catch(e){}")
        waitForPageLoad(16_000)
        if (state == State.STOPPING) return false
        delay(1500)
        listener?.loadUrl("https://discord.com/channels/@me")
        val appUrl = waitForPageLoad(20_000)
        if (state == State.STOPPING) return false
        stepLog("  App page: $appUrl")
        delay(1600)
        var currentUrl = evalJsSuspend("window.location.href")
        var loginStatus = evalJsSuspend(buildCheckLoginJs())
        stepLog("  URL: $currentUrl | login: $loginStatus")
        if (loginStatus == "LOGGED_IN") return true
        if (loginStatus == "TOKEN_BUT_NOT_LOADED" && currentUrl.contains("channels")) return true
        if (currentUrl.contains("/channels/") && !currentUrl.contains("/login")) return true
        // One more hard navigation + inject
        stepLog("  Second inject pass…")
        listener?.loadUrl("https://discord.com/login")
        waitForPageLoad(12_000)
        delay(1000)
        evalJsSuspend(buildTokenInjectJs(token))
        evalJsSuspend("try{location.reload();}catch(e){}")
        waitForPageLoad(14_000)
        delay(1200)
        listener?.loadUrl("https://discord.com/channels/@me")
        waitForPageLoad(16_000)
        delay(2500)
        currentUrl = evalJsSuspend("window.location.href")
        loginStatus = evalJsSuspend(buildCheckLoginJs())
        stepLog("  Final URL: $currentUrl | login: $loginStatus")
        return loginStatus == "LOGGED_IN" ||
            (loginStatus == "TOKEN_BUT_NOT_LOADED" && currentUrl.contains("channels")) ||
            (currentUrl.contains("/channels/") && !currentUrl.contains("/login"))
    }

    private suspend fun clickUiButton(js: String): Boolean {
        repeat(10) {
            val result = evalJsSuspend(js)
            stepLog("  UI click result: $result")
            if (result.startsWith("CLICKED")) return true
            delay(450)
        }
        return false
    }

    private suspend fun pollJoinResult(): JoinResult {
        var attempts = 0
        val maxAttempts = 60      // ~36s at 600ms
        while (attempts < maxAttempts && state == State.RUNNING) {
            delay(600)
            attempts++

            val pageState = evalJsSuspend(buildCheckJoinResultJs()).ifBlank { "LOADING" }

            if (attempts % 4 == 0 || pageState != "LOADING") {
                stepLog("  [attempt $attempts] result state: $pageState")
            }

            when {
                pageState.startsWith("JOINED") -> {
                    stepLog("✓ Joined server! $pageState")
                    return JoinResult.JOINED
                }
                pageState.startsWith("ALREADY") -> {
                    stepLog("= Already a member of this server")
                    return JoinResult.ALREADY_MEMBER
                }
                pageState.startsWith("INVALID") -> {
                    stepLog("✗ Invalid or expired invite link")
                    return JoinResult.INVALID_LINK
                }
                pageState.startsWith("RULES") -> {
                    stepLog("→ Accepting server rules…")
                    evalJsSuspend(buildAcceptRulesJs())
                    delay(900)
                    clickUiButton(buildClickInviteAcceptJs())
                    delay(600)
                }
                pageState.startsWith("CAPTCHA") -> {
                    val siteKey = readCaptchaSitekey()
                    if (siteKey.isBlank()) {
                        stepLog("⚠ Captcha detected but could not read sitekey — solve manually")
                        if (!waitForCaptchaSolve(prefs.captchaTimeoutSeconds * 1000L)) return JoinResult.CAPTCHA_NEEDED
                        delay(1200)
                        continue
                    }
                    stepLog("⚠ Captcha detected (sitekey ${siteKey.take(8)}…)")
                    val solved = solveJoinCaptcha(siteKey)
                    if (solved != null) {
                        stepLog("✓ Captcha solved automatically — injecting response")
                        listener?.evaluateJs(buildCaptchaInjectJs(solved))
                        delay(1500)
                    } else {
                        stepLog("  Auto-solvers failed — solve manually, then tap CAPTCHA SOLVED")
                        if (!waitForCaptchaSolve(prefs.captchaTimeoutSeconds * 1000L)) return JoinResult.CAPTCHA_NEEDED
                        delay(1200)
                    }
                }
            }
        }

        stepLog("✗ Timed out after $attempts attempts (${attempts * 0.8}s)")
        return JoinResult.FAILED
    }

    // ── Page-load waiter ──────────────────────────────────────────────────────

    private suspend fun waitForPageLoad(timeoutMs: Long): String {
        if (state == State.STOPPING) return ""
        /* timing removed */
        val result = withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { cont ->
                pageLoadContinuation = cont
                cont.invokeOnCancellation { pageLoadContinuation = null }
            }
        } ?: ""
        if (state == State.STOPPING) return ""
        return result
    }

    private suspend fun waitForCaptchaSolve(timeoutMs: Long): Boolean {
        listener?.onCaptchaPrompt {
            captchaSolvedCallback?.invoke()
            captchaSolvedCallback = null
        }
        return withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { cont ->
                captchaSolvedCallback = { if (cont.isActive) cont.resume(Unit) }
                cont.invokeOnCancellation { captchaSolvedCallback = null }
            }
            true
        } ?: false
    }

    // ── Captcha solving ───────────────────────────────────────────────────────

    private suspend fun readCaptchaSitekey(): String = suspendCancellableCoroutine { cont ->
        val l = listener
        if (l == null) { if (cont.isActive) cont.resume(""); return@suspendCancellableCoroutine }
        l.evaluateJs(buildReadSitekeyJs()) { r ->
            if (cont.isActive) cont.resume(r?.trim('"') ?: "")
        }
    }

    private suspend fun solveJoinCaptcha(siteKey: String): String? {
        if (!prefs.joinerCaptchaAutoSolve) return null

        // Advanced in-app solver (no API key)
        if (prefs.advancedInAppSolverEnabled) {
            stepLog("  Trying built-in in-app solver…")
            val builtIn = BuiltInCaptchaSolver().solve(siteKey) { msg -> stepLog("  [built-in] $msg") }
            if (builtIn != null) {
                stepLog("✓ Built-in solver succeeded")
                return builtIn
            }
            stepLog("  Built-in solver failed")
        }

        // API solvers in user-configured priority order
        if (!prefs.autoCaptchaEnabled) return null
        val priority = (prefs.captchaSolverPriority.split(",").map { it.trim().lowercase() } + listOf("nonecap", "nopecha", "2captcha")).distinct()
        for (solver in priority) {
            when (solver) {
                "nonecap", "none" -> if (prefs.nonecapApiKey.isNotBlank()) {
                    stepLog("  Trying NoneCap…")
                    val t = NoneCapApi(prefs.nonecapApiKey)
                        .solveHcaptcha(siteKey, "https://discord.com") { msg -> stepLog("  [NoneCap] $msg") }
                    if (t != null) { stepLog("✓ NoneCap succeeded"); return t }
                }
                "nopecha" -> if (prefs.nopechaApiKey.isNotBlank()) {
                    stepLog("  Trying NopeCHA…")
                    val t = NopechaApi(prefs.nopechaApiKey)
                        .solveHcaptcha(siteKey, "https://discord.com") { msg -> stepLog("  [NopeCHA] $msg") }
                    if (t != null) { stepLog("✓ NopeCHA succeeded"); return t }
                }
                "2captcha" -> if (prefs.captcha2Key.isNotBlank()) {
                    stepLog("  Trying 2Captcha…")
                    val t = TwoCaptchaApi(prefs.captcha2Key)
                        .solveHcaptcha(siteKey, "https://discord.com") { msg -> stepLog("  [2Captcha] $msg") }
                    if (t != null) { stepLog("✓ 2Captcha succeeded"); return t }
                }
                "capmonster" -> if (prefs.capmonsterKey.isNotBlank()) {
                    stepLog("  Trying CapMonster…")
                    val t = CapMonsterApi(prefs.capmonsterKey)
                        .solveHcaptcha(siteKey, "https://discord.com") { msg -> stepLog("  [CapMonster] $msg") }
                    if (t != null) { stepLog("✓ CapMonster succeeded"); return t }
                }
                "capsolver" -> if (prefs.capsolverKey.isNotBlank()) {
                    stepLog("  Trying CapSolver…")
                    val t = CapSolverApi(prefs.capsolverKey)
                        .solveHcaptcha(siteKey, "https://discord.com") { msg -> stepLog("  [CapSolver] $msg") }
                    if (t != null) { stepLog("✓ CapSolver succeeded"); return t }
                }
                "az" -> if (prefs.azcaptchaKey.isNotBlank()) {
                    stepLog("  Trying AZCaptcha…")
                    val t = AZCaptchaApi(prefs.azcaptchaKey)
                        .solveHcaptcha(siteKey, "https://discord.com") { msg -> stepLog("  [AZCaptcha] $msg") }
                    if (t != null) { stepLog("✓ AZCaptcha succeeded"); return t }
                }
            }
        }
        return null
    }

    // ── JavaScript builders ───────────────────────────────────────────────────

    private fun buildTokenInjectJs(token: String): String {
        // Escape for embedding inside a JS single-quoted string via JSON.stringify style
        val escaped = token
            .replace("\\", "\\\\")
            .replace("'", "\\'")
            .replace("\n", "\\n")
            .replace("\r", "")
        // Same approach as Discord Tools login-token:
        // 1) iframe localStorage (survives some CSP/sandbox quirks)
        // 2) page localStorage with JSON-stringified token (Discord expects "\"token\"")
        // 3) also set without extra quotes as fallback
        // Caller navigates to /channels/@me after inject; we also schedule a soft reload.
        return """
(function() {
  try {
    if (!location || !location.hostname || location.hostname.indexOf('discord.com') < 0) {
      return 'WRONG_HOST';
    }
    var tok = '$escaped';
    if (!tok || tok.length < 20) return 'ERROR:empty_token';
    // Discord stores token as a JSON string value, i.e. "\"actualToken\""
    var stored = JSON.stringify(tok);
    try {
      var iframe = document.createElement('iframe');
      iframe.style.display = 'none';
      document.body.appendChild(iframe);
      iframe.contentWindow.localStorage.setItem('token', stored);
      document.body.removeChild(iframe);
    } catch (e1) {}
    try {
      if (typeof localStorage !== 'undefined' && localStorage) {
        localStorage.setItem('token', stored);
      } else {
        return 'NO_LOCALSTORAGE';
      }
    } catch (e2) {
      return 'ERROR:' + (e2 && e2.message ? e2.message : e2);
    }
    // Clear webpack/session leftovers that can block token auth
    try { sessionStorage.clear(); } catch (e3) {}
    return 'OK';
  } catch (e) {
    return 'ERROR:' + (e && e.message ? e.message : e);
  }
})();
        """.trimIndent()
    }

    private fun buildCheckLoginJs(): String = """
(function() {
  try {
    var token = null;
    try { token = localStorage.getItem('token'); } catch (e0) {}
    if (!token || token === 'null' || token === 'undefined') return 'NO_TOKEN';
    // UI signals Discord finished hydrating the account
    var guilds = document.querySelectorAll('[data-list-item-id*="guildsnav"], nav[aria-label*="Servers"] div[class*="listItem"]');
    if (guilds && guilds.length > 0) return 'LOGGED_IN';
    var avatar = document.querySelector('img[src*="cdn.discordapp.com/avatars"], img[src*="cdn.discordapp.com/embed/avatars"]');
    if (avatar) return 'LOGGED_IN';
    var userArea = document.querySelector('section[class*="panels"], div[class*="nameTag"], div[class*="accountProfile"]');
    if (userArea) return 'LOGGED_IN';
    // Not on login form = likely app shell loading
    if (location.pathname.indexOf('/channels/') === 0) return 'TOKEN_BUT_NOT_LOADED';
    if (location.pathname.indexOf('/login') >= 0) return 'NO_TOKEN';
    return 'TOKEN_BUT_NOT_LOADED';
  } catch(e) { return 'ERROR:' + e.message; }
})();
    """.trimIndent()

    /** Accept / Join on discord.com/invite/{code} page */
        private fun buildClickInviteAcceptJs(): String = """
(function() {
  try {
    function visible(el) {
      if (!el) return false;
      var s = window.getComputedStyle(el);
      if (s.display === 'none' || s.visibility === 'hidden' || s.opacity === '0') return false;
      var r = el.getBoundingClientRect();
      return r.width > 0 && r.height > 0;
    }
    function clickEl(el) {
      try {
        el.focus();
        el.click();
        el.dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true, view: window }));
        return true;
      } catch (e) { return false; }
    }
    var texts = /^(accept invite|accept|join server|join|continue to discord|continue)$/i;
    var soft = /accept|join server|join this server|continue to discord/i;
    var nodes = Array.from(document.querySelectorAll('button,[role="button"],a,div[class*="button"],div[class*="Button"]'));
    // Prefer exact text matches
    for (var i = 0; i < nodes.length; i++) {
      var el = nodes[i];
      if (el.disabled) continue;
      if (!visible(el)) continue;
      var txt = ((el.innerText || el.textContent || '')).replace(/\s+/g, ' ').trim();
      if (texts.test(txt)) {
        if (clickEl(el)) return 'CLICKED:' + txt;
      }
    }
    for (var j = 0; j < nodes.length; j++) {
      var el2 = nodes[j];
      if (el2.disabled) continue;
      if (!visible(el2)) continue;
      var t2 = ((el2.innerText || el2.textContent || '')).replace(/\s+/g, ' ').trim();
      if (soft.test(t2) && t2.length < 40 && !/login|register|download/i.test(t2)) {
        if (clickEl(el2)) return 'CLICKED:' + t2;
      }
    }
    // React primary button near invite panel
    var primary = document.querySelector('button[type="submit"], button[class*="lookFilled"], button[class*="colorBrand"]');
    if (primary && visible(primary) && !primary.disabled) {
      if (clickEl(primary)) return 'CLICKED_PRIMARY';
    }
    return 'NOT_FOUND';
  } catch (e) { return 'ERROR:' + (e && e.message ? e.message : e); }
})();
    """.trimIndent()

    private fun buildClickAddServerJs(): String = """
(function() {
  try {
    var els = Array.from(document.querySelectorAll('button,[role="button"],div,span'));
    for (var i = 0; i < els.length; i++) {
      var el = els[i];
      if (el.disabled) continue;
      var txt = ((el.innerText || el.textContent || '')).trim().toLowerCase();
      var aria = ((el.getAttribute && el.getAttribute('aria-label')) || '').trim().toLowerCase();
      if (aria === 'add a server' || /add a server/i.test(aria) || txt === '+' || /add a server/i.test(txt)) {
        el.click();
        return 'CLICKED_ADD_SERVER';
      }
    }
    return 'NOT_FOUND';
  } catch(e) { return 'ERROR:' + e.message; }
})();
    """.trimIndent()

    private fun buildClickJoinServerDialogJs(): String = """
(function() {
  try {
    var nodes = Array.from(document.querySelectorAll('button,[role="button"],div,span,h2,h3'));
    for (var i = 0; i < nodes.length; i++) {
      var el = nodes[i];
      if (el.disabled) continue;
      var txt = ((el.innerText || el.textContent || '')).trim().toLowerCase();
      if (/already have an invite/i.test(txt) || /join a server/i.test(txt)) {
        // The text may be on a container; click the closest button/role=button parent
        var target = el;
        while (target && target.tagName !== 'BUTTON' && target.getAttribute('role') !== 'button') {
          target = target.parentElement;
        }
        if (target && !target.disabled) { target.click(); return 'CLICKED_JOIN_DIALOG'; }
        el.click();
        return 'CLICKED_JOIN_DIALOG';
      }
    }
    return 'NOT_FOUND';
  } catch(e) { return 'ERROR:' + e.message; }
})();
    """.trimIndent()

    private fun buildPasteInviteCodeJs(code: String): String {
        val escaped = code.replace("\\", "\\\\").replace("\"", "\\\"").replace("'", "\\'")
        return """
(function() {
  try {
    var val = '$escaped';
    var inputs = Array.from(document.querySelectorAll('input,textarea,[contenteditable="true"]'));
    var target = null;
    for (var i = 0; i < inputs.length; i++) {
      var el = inputs[i];
      var name = ((el.getAttribute('name') || '') + ' ' + (el.getAttribute('placeholder') || '') + ' ' + (el.getAttribute('aria-label') || '')).toLowerCase();
      if (/invite|code/.test(name)) { target = el; break; }
    }
    if (!target) {
      // Fallback: first visible input in the modal
      for (var j = 0; j < inputs.length; j++) {
        if (inputs[j].offsetParent !== null) { target = inputs[j]; break; }
      }
    }
    if (!target) return 'NOT_FOUND';
    if (target.tagName === 'INPUT' || target.tagName === 'TEXTAREA') {
      target.focus();
      target.value = '';
      target.value = val;
      target.dispatchEvent(new Event('input', { bubbles: true }));
      target.dispatchEvent(new Event('change', { bubbles: true }));
      target.dispatchEvent(new KeyboardEvent('keydown', { bubbles: true }));
      target.dispatchEvent(new KeyboardEvent('keyup', { bubbles: true }));
      return 'PASTED';
    }
    target.focus();
    document.execCommand('insertText', false, val);
    return 'PASTED';
  } catch(e) { return 'ERROR:' + e.message; }
})();
        """.trimIndent()
    }

    private fun buildClickSubmitJoinJs(): String = """
(function() {
  try {
    var buttons = Array.from(document.querySelectorAll('button,[role="button"]'));
    for (var i = 0; i < buttons.length; i++) {
      var btn = buttons[i];
      if (btn.disabled) continue;
      var txt = ((btn.innerText || btn.textContent || '')).trim().toLowerCase();
      var aria = ((btn.getAttribute('aria-label') || '')).trim().toLowerCase();
      if (/^join server${'$'}/.test(txt) || /^join${'$'}/.test(txt) || /join server/i.test(aria) || (/^join/i.test(txt) && !/leave/i.test(txt))) {
        btn.click();
        return 'CLICKED_JOIN:' + txt;
      }
    }
    return 'NOT_FOUND';
  } catch(e) { return 'ERROR:' + e.message; }
})();
    """.trimIndent()

    private fun buildCheckJoinResultJs(): String = """
(function() {
  try {
    var url = window.location.href;
    if (/\/channels\/\d+\/\d+/.test(url)) return 'JOINED:channels';
    if (url.includes('/channels/@me') && !url.includes('/invite/')) {
      var guilds = document.querySelectorAll('[data-list-item-id*="guildsnav"], nav[aria-label*="Servers" i] [class*="listItem"]');
      if (guilds.length > 1) return 'JOINED:app';
    }
    // Invite page disappeared and we landed in app shell after accept
    if (!url.includes('/invite/') && /discord\.com\/(channels|app)/.test(url)) {
      return 'JOINED:navigated';
    }
    var body = document.body ? document.body.innerText : '';
    if (/you are already a member|already in this server|already a member of/i.test(body)) return 'ALREADY_MEMBER';
    if (/invalid invite|invite is invalid|invite invalid|invite has expired|expired invite|unable to accept invite/i.test(body)) return 'INVALID';
    if (/captcha|verify you are human|i am not a robot|security check|please verify|hcaptcha/i.test(body)) return 'CAPTCHA';
    // Rules / community screen — not failure, keep polling after click
    if (/server rules|community guidelines|by joining|agree to the rules/i.test(body)) return 'RULES';
    if (document.querySelector('iframe[src*="hcaptcha"],iframe[src*="recaptcha"],iframe[src*="arkoselabs"],iframe[src*="funcaptcha"]') ||
        document.querySelector('[id*="captcha" i],[class*="captcha" i],[data-sitekey]')) return 'CAPTCHA';
    return 'LOADING';
  } catch(e) { return 'ERROR:' + e.message; }
})();
    """.trimIndent()

    private fun buildReadSitekeyJs(): String = """
(function() {
  try {
    var hc = document.querySelector('iframe[src*="hcaptcha.com"]');
    if (hc && hc.src) {
      var m = hc.src.match(/sitekey=([^&]+)/);
      if (m) return m[1];
    }
    var rc = document.querySelector('iframe[src*="recaptcha"],iframe[src*="google.com/recaptcha"]');
    if (rc && rc.src) {
      var m2 = rc.src.match(/k=([^&]+)/);
      if (m2) return m2[1];
    }
    var widget = document.querySelector('[data-sitekey]');
    if (widget) return widget.getAttribute('data-sitekey') || '';
    return '';
  } catch(e) { return ''; }
})();
    """.trimIndent()

    private fun buildCaptchaInjectJs(solution: String): String {
        val esc = solution.replace("'", "\\'")
        return """
(function() {
  var injected = false;
  var names = ['h-captcha-response','g-recaptcha-response','fc-captcha-response','arkose-captcha-response'];
  names.forEach(function(n) {
    var tas = document.querySelectorAll('textarea[name="' + n + '"]');
    tas.forEach(function(t){ t.value='$esc'; t.dispatchEvent(new Event('input',{bubbles:true})); t.dispatchEvent(new Event('change',{bubbles:true})); injected = true; });
  });
  var inputs = document.querySelectorAll('input[type="hidden"]');
  inputs.forEach(function(inp) {
    if (inp.name && /captcha|token|response/i.test(inp.name)) { inp.value='$esc'; injected = true; }
  });
  setTimeout(function() {
    var btn = document.querySelector('button[type="submit"]');
    if (!btn) {
      var btns = document.querySelectorAll('button,div[role="button"]');
      for (var i = 0; i < btns.length; i++) {
        var t = (btns[i].textContent || '').toLowerCase();
        if (t.includes('continue') || t.includes('verify') || t.includes('submit') || t.includes('confirm') || t.includes('next') || t.includes('join')) {
          btn = btns[i]; break;
        }
      }
    }
    if (btn) { btn.click(); Android.onLog('Form submitted after captcha injection', 'SUCCESS'); }
    else { Android.onLog('Captcha token injected but submit button not found', 'WARNING'); }
  }, 600);
  if (injected) Android.onLog('Captcha token injected', 'SUCCESS');
  else Android.onLog('Captcha token injected (no visible textarea)', 'INFO');
})();
        """.trimIndent()
    }

    private fun buildNopechaJs(apiKey: String): String = """
(function() {
  if (document.getElementById('nopecha-sdk')) return;
  var s = document.createElement('script');
  s.id = 'nopecha-sdk';
  s.src = 'https://nopecha.com/f/b.js';
  s.onload = function() { if (typeof NopeCHA !== 'undefined') NopeCHA.setup({ key: '$apiKey' }); };
  document.head.appendChild(s);
})();
    """.trimIndent()


    private fun buildAcceptRulesJs(): String = """
(function() {
  try {
    var boxes = Array.from(document.querySelectorAll('input[type="checkbox"], div[role="checkbox"], [class*="checkbox"]'));
    for (var i = 0; i < boxes.length; i++) {
      var el = boxes[i];
      try {
        if (el.tagName === 'INPUT') {
          if (!el.checked) { el.click(); el.checked = true; el.dispatchEvent(new Event('change', { bubbles: true })); }
        } else {
          var aria = (el.getAttribute('aria-checked') || '');
          if (aria !== 'true') el.click();
        }
      } catch (e) {}
    }
    var btns = Array.from(document.querySelectorAll('button,[role="button"]'));
    for (var j = 0; j < btns.length; j++) {
      var b = btns[j];
      if (b.disabled) continue;
      var t = ((b.innerText || b.textContent || '')).trim().toLowerCase();
      if (/submit|agree|continue|i agree|accept/i.test(t) && t.length < 32) {
        b.click();
        return 'RULES_ACCEPTED:' + t;
      }
    }
    return 'RULES_NO_BUTTON';
  } catch (e) { return 'ERROR:' + (e && e.message ? e.message : e); }
})();
    """.trimIndent()

    // ── Utility ───────────────────────────────────────────────────────────────

    fun extractCode(input: String): String? {
        val trimmed = input.trim().removePrefix("@")
        if (trimmed.matches(Regex("[A-Za-z0-9-_]{4,32}"))) return trimmed
        val patterns = listOf(
            Regex("""(?:discord\.gg|discordapp\.com/invite|discord\.com/invite)/([A-Za-z0-9-_]+)""", RegexOption.IGNORE_CASE)
        )
        for (re in patterns) {
            val m = re.find(trimmed)
            if (m != null) return m.groupValues[1]
        }
        val seg = trimmed.trimEnd('/').substringAfterLast('/')
        if (seg.matches(Regex("[A-Za-z0-9-_]{4,32}"))) return seg
        return null
    }

    private fun resultMessage(result: JoinResult) = when (result) {
        JoinResult.JOINED         -> "Joined successfully"
        JoinResult.ALREADY_MEMBER -> "Already a member"
        JoinResult.CAPTCHA_NEEDED -> "Captcha required (manual solve timed out)"
        JoinResult.FAILED         -> "Failed / timed out"
        JoinResult.INVALID_LINK   -> "Invalid or expired invite"
        JoinResult.INFO           -> ""
    }
}
