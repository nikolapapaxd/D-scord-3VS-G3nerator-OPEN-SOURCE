package com.discord.evs.generator.ui

import android.annotation.SuppressLint
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceError
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.discord.evs.generator.automation.AccountGenerator
import com.discord.evs.generator.automation.MultiGenSession
import com.discord.evs.generator.automation.FingerprintManager
import com.discord.evs.generator.automation.LogEntry
import com.discord.evs.generator.automation.LogLevel
import com.discord.evs.generator.automation.GenerationListener
import com.discord.evs.generator.data.Account
import com.discord.evs.generator.data.AccountDatabase
import com.discord.evs.generator.data.Prefs
import com.discord.evs.generator.R
import com.discord.evs.generator.databinding.FragmentGeneratorBinding
import com.discord.evs.generator.databinding.ItemLogBinding
import java.text.SimpleDateFormat
import java.util.*

class GeneratorFragment : Fragment(), GenerationListener {

    private var _binding: FragmentGeneratorBinding? = null
    private val binding get() = _binding!!

    private lateinit var prefs: Prefs
    private lateinit var generator: AccountGenerator
    private var multiSessions: MutableList<MultiGenSession> = mutableListOf()
    private var activeSessionIndex: Int = 0
    private var multiMode: Boolean = false
    private val logAdapter = LogAdapter { onContinueClicked() }

    // true while captcha or stuck forces the WebView visible (overrides showWebView=OFF)
    private var webViewForced = false
    private val pageRetryHandler = Handler(Looper.getMainLooper())
    private var delayedPageRetry: Runnable? = null

    companion object {
        // Survives tab switches so generation keeps running in background
        @Volatile private var sharedGenerator: AccountGenerator? = null
        val sharedMultiSessions = mutableListOf<MultiGenSession>()
        @Volatile var activeSessionIndex: Int = 0
        @SuppressLint("StaticFieldLeak")
        private var sharedWebView: WebView? = null
        val persistedLogs = mutableListOf<LogEntry>()
        /** These entries are intentionally outside the clearable runtime log. */
        val welcomeEntries = listOf(
            LogEntry(
                "Welcome to EVS Generator",
                LogLevel.SUCCESS,
                timestamp = 0L,
                isWelcome = true
            ),
            LogEntry(
                "Thank you for using this app. Keep supporting & loving.",
                LogLevel.INFO,
                timestamp = 0L,
                isWelcome = true
            ),
            LogEntry(
                "Made with ♥ by User",
                LogLevel.INFO,
                timestamp = 0L,
                isWelcome = true
            )
        )

        /**
         * Injected on any discord.com page that is not /register or /login.
         * Detects the phone-verification page that Discord navigates to after
         * registration — the buildFillJs startPolling() interval is dead at this
         * point because the page navigated, so we re-detect here via onPageFinished.
         */
        private const val PHONE_DETECT_JS = """
(function() {
  if (window.__phoneDetected) return;
  function txt() {
    try { return ((document.body && document.body.innerText) || ''); } catch (e) { return ''; }
  }
  var text = txt();
  // Email-only identity gate: has "Verify by Email" and NO phone input → not a phone page
  var hasVerifyByEmail = false;
  var hasVerifyByPhone = false;
  try {
    var nodes = document.querySelectorAll('button, a, div[role="button"], span');
    for (var i = 0; i < nodes.length; i++) {
      var s = (nodes[i].innerText || nodes[i].textContent || '').replace(/\s+/g,' ').trim();
      if (/verify by email/i.test(s)) hasVerifyByEmail = true;
      if (/verify by phone/i.test(s)) hasVerifyByPhone = true;
    }
  } catch (e) {}
  var phoneInput = document.querySelector(
    'input[type="tel"],input[name="phone"],input[autocomplete="tel"],' +
    'input[placeholder*="Phone" i],input[placeholder*="phone" i],input[aria-label*="phone" i]'
  );
  // Strong email-identity signals without phone UI
  var emailIdentity = hasVerifyByEmail && !phoneInput && !hasVerifyByPhone;
  if (emailIdentity || (/verification required|confirm your identity|account stays secure/i.test(text) && /verify by email/i.test(text) && !phoneInput)) {
    if (!window.__emailIdentityDetected) {
      window.__emailIdentityDetected = true;
      try { Android.onLog('Email identity verification page detected', 'WARNING'); } catch (e) {}
      try { Android.onIdentityVerificationRequired(); } catch (e) {}
    }
    return;
  }
  // Require real phone UI: tel input or explicit "Verify by Phone".
  // Do NOT fire on register form, generic "verification required", or leftover copy.
  var hit = !!(phoneInput && (hasVerifyByPhone || /phone number|sms|mobile|text.*code|enter your phone|add a phone/i.test(text)));
  if (!hit && hasVerifyByPhone && !hasVerifyByEmail) hit = true;
  if (!hit && phoneInput && /verify by phone|phone number required|enter your phone|add a phone number|confirm your phone/i.test(text)) hit = true;
  if (hit) {
    window.__phoneDetected = true;
    try { Android.onLog('Phone verification page detected', 'WARNING'); } catch (e) {}
    try { Android.onPhoneVerificationRequired(); } catch (e) {}
  }
})();
        """


        /** SPA-safe: re-run phone detection every 1.5s until hit. */
        private const val PHONE_POLL_INSTALL_JS = """
(function() {
  if (window.__phonePollInstalled) return;
  window.__phonePollInstalled = true;
  setInterval(function() {
    try {
      if (window.__phoneDetected || window.__emailIdentityDetected) return;
      var text = '';
      try { text = ((document.body && document.body.innerText) || ''); } catch (e) {}
      var hasVerifyByEmail = false, hasVerifyByPhone = false;
      try {
        var nodes = document.querySelectorAll('button, a, div[role="button"], span');
        for (var i = 0; i < nodes.length; i++) {
          var s = (nodes[i].innerText || nodes[i].textContent || '').replace(/\s+/g,' ').trim();
          if (/verify by email/i.test(s)) hasVerifyByEmail = true;
          if (/verify by phone/i.test(s)) hasVerifyByPhone = true;
        }
      } catch (e) {}
      var phoneInput = document.querySelector(
        'input[type="tel"],input[name="phone"],input[autocomplete="tel"],' +
        'input[placeholder*="Phone" i],input[placeholder*="phone" i],input[aria-label*="phone" i]'
      );
      if ((hasVerifyByEmail && !phoneInput && !hasVerifyByPhone) ||
          (/verification required|confirm your identity|account stays secure/i.test(text) && /verify by email/i.test(text) && !phoneInput)) {
        if (!window.__emailIdentityDetected) {
          window.__emailIdentityDetected = true;
          try { Android.onLog('Email identity verification page detected', 'WARNING'); } catch (e) {}
          try { Android.onIdentityVerificationRequired(); } catch (e) {}
        }
        return;
      }
      var hit = !!(phoneInput && (hasVerifyByPhone || /phone number|sms|mobile|text.*code|enter your phone|add a phone/i.test(text)));
      if (!hit && hasVerifyByPhone && !hasVerifyByEmail) hit = true;
      if (!hit && phoneInput && /verify by phone|phone number required|enter your phone|add a phone number|confirm your phone/i.test(text)) hit = true;
      if (hit) {
        window.__phoneDetected = true;
        try { Android.onLog('Phone verification page detected', 'WARNING'); } catch (e) {}
        try { Android.onPhoneVerificationRequired(); } catch (e) {}
      }
    } catch (e) {}
  }, 1500);
})();
        """

        private const val TOKEN_EXTRACT_JS = """
(function() {
  var isToken = function(t) { return t&&typeof t==='string'&&t.length>50&&t.split('.').length===3; };
  try {
    var t = window.localStorage.getItem('token');
    if (t) { t=t.replace(/"/g,''); if(isToken(t)){ Android.onTokenFound(t); } }
  } catch(e){}
})();
        """
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentGeneratorBinding.inflate(inflater, container, false)
        return binding.root
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // If multi-gen was running while user left tab, restore chips + WebViews
        view.post { try { restoreMultiSessionUi() } catch (_: Exception) {} }

        prefs = Prefs(requireContext())
        UiMotion.enter(binding.root)
        UiMotion.staggerChildren(binding.root)
        val dao = AccountDatabase.getInstance(requireContext()).accountDao()

        val existing = sharedGenerator
        if (existing != null && existing.isRunning()) {
            existing.listener = this
            generator = existing
        } else {
            sharedGenerator = AccountGenerator(requireContext().applicationContext, prefs, dao, this)
            generator = sharedGenerator!!
        }

        setupWebView()
        setupConsole()
        setupControls()

        // Restore runtime logs while always ensuring the non-clearable welcome block
        // remains at the top after tab switches and after CLEAR.
        synchronized(persistedLogs) {
            if (persistedLogs.none { it.isWelcome }) {
                persistedLogs.addAll(0, welcomeEntries)
            }
            logAdapter.replaceAll(persistedLogs)
            binding.logRecycler.scrollToPosition(logAdapter.itemCount - 1)
        }

        updateContinueButton(false)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        // Re-use the same WebView across tab switches so automation keeps running
        val existingWv = sharedWebView
        if (existingWv != null) {
            (existingWv.parent as? ViewGroup)?.removeView(existingWv)
            val placeholder = binding.webViewContainer
            placeholder.removeAllViews()
            placeholder.addView(existingWv, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            ))
        } else {
            val wv = binding.webView
            wv.apply {
                try {
                    com.discord.evs.generator.automation.ProxyManager.applyToWebView(requireContext(), this, prefs)
                } catch (_: Exception) {}
                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    databaseEnabled = true
                    cacheMode = WebSettings.LOAD_DEFAULT
                    userAgentString = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"
                    allowFileAccess = false
                    javaScriptCanOpenWindowsAutomatically = true
                    mediaPlaybackRequiresUserGesture = false
                }
                addJavascriptInterface(AndroidBridge(), "Android")
                webViewClient = object : WebViewClient() {
                    override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                        super.onPageStarted(view, url, favicon)
                        updateStatusBadge(url)
                    }

                    override fun onPageFinished(view: WebView, url: String) {
                        super.onPageFinished(view, url)
                        cancelDelayedPageRetry()
                        updateStatusBadge(url)
                        if (generator.isRunning()) {
                            val isChannels = url.contains("channels/@me") || url.contains("/channels/") || url.contains("%40me")
                            val isRegister = url.contains("/register")
                            val isLogin    = url.contains("discord.com/login")
                            // Auto-extract on channels/@me (primary success path) OR any
                            // discord.com redirect that is NOT the register/login page —
                            // e.g. Discord may route through /app, /verify, or a checkpoint
                            // page before finally landing on channels/@me.
                            if (isChannels || (!isRegister && !isLogin && url.contains("discord.com"))) {
                                view.evaluateJavascript(TOKEN_EXTRACT_JS, null)
                            }
                            // Phone detect only off register/login, and only while this account
                            // still needs a possible phone gate. After account is finished and
                            // cooldown loads /register again, injecting detect caused false
                            // "Phone verification page detected" / "Phone verification required".
                            val allowPhoneDetect = !isRegister && !isLogin &&
                                (url.contains("discord.com") || url.contains("discordapp.com")) &&
                                generator.shouldAcceptPhoneGate()
                            if (allowPhoneDetect) {
                                view.evaluateJavascript(PHONE_DETECT_JS, null)
                                view.evaluateJavascript(PHONE_POLL_INSTALL_JS, null)
                            }
                            try {
                                if (com.discord.evs.generator.automation.ExtensionHost.isEnabled()) {
                                    com.discord.evs.generator.automation.ExtensionHost.injectInto(view) { }
                                }
                            } catch (_: Exception) {}
                            view.evaluateJavascript(
                                "(function(){var t=((document.body&&document.body.innerText)||'').toLowerCase();" +
                                    "return /webpage not available|this site can't be reached|network error|connection timed out|" +
                                    "connection refused|unable to connect|offline/.test(t);})()"
                            ) { result ->
                                if (result == "true") {
                                    handleWebPageUnavailable(url, "error page text")
                                }
                            }
                        }
                    }

                    override fun onReceivedError(
                        view: WebView,
                        request: WebResourceRequest,
                        error: WebResourceError
                    ) {
                        super.onReceivedError(view, request, error)
                        if (request.isForMainFrame) {
                            handleWebPageUnavailable(
                                request.url.toString(),
                                "network error ${error.errorCode}: ${error.description}"
                            )
                        }
                    }

                    override fun onReceivedHttpError(
                        view: WebView,
                        request: WebResourceRequest,
                        errorResponse: android.webkit.WebResourceResponse
                    ) {
                        super.onReceivedHttpError(view, request, errorResponse)
                        if (request.isForMainFrame &&
                            (errorResponse.statusCode == 408 || errorResponse.statusCode == 429 ||
                                errorResponse.statusCode >= 500)
                        ) {
                            handleWebPageUnavailable(
                                request.url.toString(),
                                "HTTP ${errorResponse.statusCode}"
                            )
                        }
                    }

                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                        return false
                    }
                }
                loadUrl("about:blank")
            }
            sharedWebView = wv
        }

        // Apply per-account fingerprint randomization if enabled
        sharedWebView?.let { FingerprintManager.apply(requireContext().applicationContext, prefs, it) }
    }

    private fun setupConsole() {
        binding.logRecycler.apply {
            adapter = logAdapter
            layoutManager = LinearLayoutManager(context).also { it.stackFromEnd = true }
        }
        binding.clearConsoleBtn.setOnClickListener {
            synchronized(persistedLogs) {
                persistedLogs.removeAll { !it.isWelcome }
                logAdapter.replaceAll(persistedLogs)
            }
            binding.logRecycler.scrollToPosition(logAdapter.itemCount - 1)
        }
    }

    private fun setupControls() {
        binding.startStopButton.setOnClickListener {
            if (multiMode && multiSessions.any { it.running || it.generator.isRunning() }) {
                stopMultiGeneration()
                updateContinueButton(false)
            } else if (generator.isRunning()) {
                generator.stop()
                updateContinueButton(false)
            } else {
                // NopeCHA Extension mode: install in-WebView extension host (Kiwi-style content scripts)
                try {
                    if (prefs.captchaSolverProvider.equals("nopecha", true) &&
                        prefs.nopechaMode.equals("extension", true)
                    ) {
                        com.discord.evs.generator.automation.ExtensionHost.setup(requireContext().applicationContext) { msg ->
                            onLog(LogEntry(msg, LogLevel.INFO))
                        }
                        sharedWebView?.let {
                            com.discord.evs.generator.automation.ExtensionHost.injectInto(it) { }
                        }
                        binding.webView.let {
                            com.discord.evs.generator.automation.ExtensionHost.injectInto(it) { }
                        }
                    } else {
                        com.discord.evs.generator.automation.ExtensionHost.teardown()
                    }
                } catch (_: Exception) {}
                val n = prefs.concurrentGenerations.coerceIn(1, 5)
                if (n > 1) {
                    startMultiGeneration(n)
                } else {
                    multiMode = false
                    hideSessionChips()
                    generator.start(prefs.targetCount)
                }
            }
        }
        binding.pauseButton.setOnClickListener {
            if (multiMode && multiSessions.isNotEmpty()) {
                val s = multiSessions.getOrNull(activeSessionIndex) ?: multiSessions.first()
                if (s.generator.isPaused()) {
                    s.generator.resume()
                    binding.pauseButton.text = "PAUSE"
                } else {
                    s.generator.pause()
                    binding.pauseButton.text = "RESUME"
                }
            } else if (generator.isPaused()) {
                generator.resume()
                binding.pauseButton.text = "PAUSE"
            } else {
                generator.pause()
                binding.pauseButton.text = "RESUME"
            }
        }
        binding.refreshButton.setOnClickListener {
            if (multiMode && multiSessions.isNotEmpty()) {
                multiSessions.getOrNull(activeSessionIndex)?.generator?.refreshPage()
            } else {
                generator.refreshPage()
            }
        }
        binding.continueButton.setOnClickListener {
            onContinueClicked()
        }
        updateButtonState(generator.isRunning())
        updateWebViewOverlay()   // sync overlay on tab restore while generator is running
    }

    private fun onContinueClicked() {
        updateContinueButton(false)
        // Re-hide the WebView overlay once the user has handled the captcha/stuck step
        webViewForced = false
        updateWebViewOverlay()
        // onContinuePressed injects SMART_CONTINUE_JS which detects the current URL,
        // logs "Already logged in" if on channels/@me, or "Resuming" otherwise,
        // and extracts the token — no separate injection needed here.
        if (multiMode && multiSessions.isNotEmpty()) {
            val s = multiSessions.getOrNull(activeSessionIndex)
                ?: multiSessions.firstOrNull { it.needsHuman }
                ?: multiSessions.firstOrNull()
            s?.continuePressed()
        } else {
            generator.onContinuePressed()
        }
    }

    /**
     * Updates the black overlay that covers the WebView.
     * Overlay is visible when:
     *   - generator is NOT running (shows "Start Generation first")
     *   - OR generator running + showWebView=OFF + not forced visible
     * Forced visible = captcha detected or automation stuck (user must interact).
     */
    private fun updateWebViewOverlay() {
        activity?.runOnUiThread {
            val b = _binding ?: return@runOnUiThread
            val isRunning = if (multiMode) multiSessions.any { it.generator.isRunning() } else generator.isRunning()
            val overlayVisible = !isRunning || (!prefs.showWebViewEnabled && !webViewForced)
            b.webViewOverlay.visibility = if (overlayVisible) View.VISIBLE else View.GONE
            if (overlayVisible) {
                if (!isRunning) {
                    b.overlayEmoji.text = "⚙️"
                    b.overlayTitle.text = "Start Generation first"
                    b.overlaySubtitle.text = "Press START to begin generating accounts"
                } else {
                    b.overlayEmoji.text = "🤖"
                    b.overlayTitle.text = "Running in background"
                    b.overlaySubtitle.text = "WebView hidden to save resources"
                }
            }
        }
    }

    private fun updateContinueButton(show: Boolean) {
        _binding?.continueButton?.visibility = if (show) View.VISIBLE else View.GONE
    }

    private fun updateStatusBadge(url: String) {
        _binding?.webviewStatusText?.text = when {
            url.contains("channels/@me") || url.contains("/channels/") -> "LOGGED IN"
            url.contains("discord.com/register") -> "REGISTER"
            url.contains("discord.com/login")    -> "LOGIN"
            url.contains("discord.com/verify")   -> "VERIFYING"
            else -> "LOADING"
        }
    }

    private fun updateButtonState(running: Boolean) {
        _binding?.startStopButton?.apply {
            text = if (running) "STOP" else "START"
            setBackgroundColor(
                if (running) Color.parseColor("#ed4245")
                else Color.parseColor("#5865F2")
            )
        }
        _binding?.pauseButton?.apply {
            visibility = if (running) View.VISIBLE else View.GONE
            text = "PAUSE" // reset text when visibility changes
        }
        _binding?.refreshButton?.visibility = if (running) View.VISIBLE else View.GONE
    }

    // ── GenerationListener callbacks (all null-safe) ──────────────────────────

    override fun onLog(entry: LogEntry) {
        if (prefs.quietConsole && entry.level == LogLevel.INFO && !entry.showContinue && !entry.isWelcome) {
            return
        }
        synchronized(persistedLogs) {
            persistedLogs.add(entry)
            while (persistedLogs.count { !it.isWelcome } > 500) {
                val firstRuntimeIndex = persistedLogs.indexOfFirst { !it.isWelcome }
                if (firstRuntimeIndex < 0) break
                persistedLogs.removeAt(firstRuntimeIndex)
            }
        }
        activity?.runOnUiThread {
            val b = _binding ?: return@runOnUiThread
            logAdapter.add(entry)
            b.logRecycler.scrollToPosition(logAdapter.itemCount - 1)
            if (entry.showContinue) updateContinueButton(true)
        }
    }

    override fun onAccountSaved(account: Account) {
        activity?.runOnUiThread {
            onLog(LogEntry("✓ Saved → ${account.email}:${account.password}:${account.token.take(24)}...", LogLevel.SUCCESS))
        }
    }

    override fun onCounterUpdate(created: Int, target: Int) {
        activity?.runOnUiThread {
            _binding?.counterText?.text = "Generated: $created${if (target > 0) "/$target" else ""}"
        }
    }

    override fun onStateChange(running: Boolean) {
        if (!running) hideSessionChips()
        activity?.runOnUiThread {
            if (!running) cancelDelayedPageRetry()
            updateButtonState(running)
            if (!running) {
                updateContinueButton(false)
                webViewForced = false
                // Navigate to blank immediately so any injected JS polling stops dead.
                val wv = sharedWebView ?: _binding?.webView
                wv?.loadUrl("about:blank")
            }
            _binding?.statusDot?.setBackgroundColor(
                if (running) Color.parseColor("#57f287") else Color.parseColor("#b5bac1")
            )
            _binding?.statusText?.text = if (running) "RUNNING" else "IDLE"
            updateWebViewOverlay()
        }
    }

    override fun getWebView(): android.webkit.WebView? {
        return sharedWebView ?: _binding?.webView
    }

    override fun injectJs(js: String) {
        activity?.runOnUiThread {
            val wv = sharedWebView ?: _binding?.webView
            wv?.evaluateJavascript(js, null)
        }
    }

    override fun clearWebViewData() {
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
        WebStorage.getInstance().deleteAllData()
        val wv = sharedWebView ?: _binding?.webView ?: return
        wv.clearCache(true)
        wv.clearHistory()
        wv.clearFormData()
        wv.evaluateJavascript(
            "try{window.localStorage.clear();window.sessionStorage.clear();}catch(e){}", null
        )
    }

    override fun navigateTo(url: String) {
        activity?.runOnUiThread {
            val wv = sharedWebView ?: _binding?.webView
            // Force English Accept-Language so Discord register UI (DOB labels etc.)
            // stays in English even when the app language is localized.
            if (url.contains("discord.com", ignoreCase = true)) {
                wv?.loadUrl(url, mapOf("Accept-Language" to "en-US,en;q=0.9"))
            } else {
                wv?.loadUrl(url)
            }
        }
    }

    override fun onCaptchaDetected(siteKey: String) {
        // Force WebView visible so the user can solve the captcha
        try { com.discord.evs.generator.service.GenForegroundService.notifyCaptcha(requireContext().applicationContext) } catch (_: Exception) {}

        webViewForced = true
        updateWebViewOverlay()
        generator.onBridgeCaptchaDetected(siteKey)
    }

    override fun onCaptchaSolved(token: String) {
        // injection handled by generator
    }

    override fun onStuck(reason: String) {
        // Force WebView visible so the user can see and handle the stuck state
        webViewForced = true
        updateWebViewOverlay()
        activity?.runOnUiThread {
            updateContinueButton(true)
        }
    }

    override fun onAutoResumed() {
        // Captcha solved and login auto-detected; hide the continue button and overlay
        webViewForced = false
        updateWebViewOverlay()
        activity?.runOnUiThread {
            updateContinueButton(false)
        }
    }

    // ── JS Bridge ─────────────────────────────────────────────────────────────

    inner class AndroidBridge {
        @JavascriptInterface
        fun onLog(message: String, level: String) {
            val lvl = when (level.uppercase()) {
                "SUCCESS" -> LogLevel.SUCCESS
                "WARNING" -> LogLevel.WARNING
                "ERROR"   -> LogLevel.ERROR
                "CAPTCHA" -> LogLevel.CAPTCHA
                else      -> LogLevel.INFO
            }
            this@GeneratorFragment.onLog(LogEntry(message, lvl))
        }

        @JavascriptInterface
        fun onTokenFound(token: String) {
            generator.onBridgeTokenFound(token)
        }

        @JavascriptInterface
        fun onCaptchaDetected(siteKey: String) {
            generator.onBridgeCaptchaDetected(siteKey)
        }

        @JavascriptInterface
        fun onStepVerified(success: Boolean) {
            generator.onStepVerified(success)
        }

        @JavascriptInterface
        fun onIdentityVerificationRequired() {
            generator.onBridgeIdentityVerificationRequired()
        }

        @JavascriptInterface
        fun onPhoneVerificationRequired() {
            generator.onBridgePhoneVerificationRequired()
        }

        @JavascriptInterface
        fun onCaptchaAutoSolved() {
            generator.onBridgeCaptchaAutoSolved()
        }

        @JavascriptInterface
        fun onEmailRejected() {
            generator.onBridgeEmailRejected()
        }

        @JavascriptInterface
        fun onEmailRejectedReason(reason: String) {
            generator.onBridgeEmailRejectedReason(reason)
        }

        @JavascriptInterface
        fun onAccountLocked() {
            generator.onBridgeAccountLocked()
        }

        @JavascriptInterface
        fun onUsernameRejected() {
            generator.onBridgeUsernameRejected()
        }

        @JavascriptInterface
        fun onPasswordRejected() {
            generator.onBridgePasswordRejected()
        }

        @JavascriptInterface
        fun onDisplayNameRejected() {
            generator.onBridgeDisplayNameRejected()
        }

        @JavascriptInterface
        fun onDobRejected() {
            generator.onBridgeDobRejected()
        }

        @JavascriptInterface
        fun onAgeRestrictionDetected() {
            generator.onAgeRestrictionDetected()
        }

        @JavascriptInterface
        fun onRegisterPageError() {
            generator.onBridgeRegisterPageError()
        }
    }

    override fun onDestroyView() {
        cancelDelayedPageRetry()
        // Detach shared WebView from layout without destroying it while generation runs
        try {
            val wv = sharedWebView
            if (wv != null) {
                (wv.parent as? ViewGroup)?.removeView(wv)
            }
            val gen = try { generator } catch (_: Exception) { null }
            val stillRunning = gen?.isRunning() == true
            if (!stillRunning) {
                try { sharedWebView?.destroy() } catch (_: Exception) {}
                sharedWebView = null
                sharedGenerator = null
            }
            // Multi sessions: detach WebViews from this fragment layout only.
            // Keep sharedMultiSessions alive while any generator is still running so
            // chips / progress survive tab switches (Settings → Generate).
            for (s in sharedMultiSessions) {
                try {
                    val v = s.webView
                    (v.parent as? ViewGroup)?.removeView(v)
                } catch (_: Exception) {}
            }
            val multiRun = sharedMultiSessions.any { it.generator.isRunning() }
            if (!multiRun && sharedMultiSessions.isNotEmpty()) {
                sharedMultiSessions.toList().forEach { it.destroy() }
                sharedMultiSessions.clear()
            try { com.discord.evs.generator.service.GenForegroundService.stop(requireContext().applicationContext) } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
        _binding = null
        super.onDestroyView()
    }

    private fun handleWebPageUnavailable(url: String, reason: String) {
        if (!::generator.isInitialized || !generator.isRunning()) return
        onLog(LogEntry(
            "WebView page unavailable ($reason) — refreshing now and waiting for recovery",
            LogLevel.WARNING
        ))
        _binding?.webviewStatusText?.text = "RETRYING"
        generator.refreshPage()
        scheduleDelayedPageRetry(url)
    }

    private fun scheduleDelayedPageRetry(url: String) {
        delayedPageRetry?.let(pageRetryHandler::removeCallbacks)
        val retry = Runnable {
            if (!::generator.isInitialized || !generator.isRunning()) return@Runnable
            onLog(LogEntry(
                "WebView still unavailable — retrying again after 5 minutes: $url",
                LogLevel.WARNING
            ))
            generator.refreshPage()
            scheduleDelayedPageRetry(url)
        }
        delayedPageRetry = retry
        pageRetryHandler.postDelayed(retry, 5 * 60 * 1000L)
        onLog(LogEntry(
            "WebView retry scheduled in 5 minutes if the page stays unavailable",
            LogLevel.INFO
        ))
    }

    private fun cancelDelayedPageRetry() {
        delayedPageRetry?.let(pageRetryHandler::removeCallbacks)
        delayedPageRetry = null
    }

    private val multiHost = object : MultiGenSession.Host {
        override fun onSessionLog(sessionId: Int, entry: LogEntry) {
            if (sessionId == activeSessionIndex + 1 || multiSessions.size <= 1) {
                this@GeneratorFragment.onLog(entry)
            } else if (entry.level == LogLevel.SUCCESS || entry.level == LogLevel.ERROR || entry.showContinue) {
                this@GeneratorFragment.onLog(entry)
            }
        }
        override fun onSessionAccountSaved(account: Account) {
            this@GeneratorFragment.onAccountSaved(account)
        }
        override fun onSessionCounter(sessionId: Int, created: Int, target: Int) {
            activity?.runOnUiThread {
                val totalCreated = multiSessions.sumOf { it.created }
                val totalTarget = multiSessions.sumOf { it.target }
                _binding?.counterText?.text = "Generated: $totalCreated${if (totalTarget > 0) "/$totalTarget" else ""}"
                updateSessionChips(multiSessions.size, activeSessionIndex)
            }
        }
        override fun onSessionState(sessionId: Int, running: Boolean) {
            activity?.runOnUiThread {
                val any = multiSessions.any { it.generator.isRunning() }
                updateButtonState(any)
                if (!any) {
                    multiMode = false
                    hideSessionChips()
                }
                updateWebViewOverlay()
            }
        }
        override fun onSessionCaptcha(sessionId: Int, siteKey: String) {
            activity?.runOnUiThread {
                activeSessionIndex = (sessionId - 1).coerceIn(0, (multiSessions.size - 1).coerceAtLeast(0))
                showSessionWebView(activeSessionIndex)
                webViewForced = true
                updateWebViewOverlay()
                updateContinueButton(true)
                updateSessionChips(multiSessions.size, activeSessionIndex)
            }
        }
        override fun onSessionStuck(sessionId: Int, reason: String) {
            onSessionCaptcha(sessionId, "")
        }
        override fun onSessionContinueNeeded(sessionId: Int) {
            onSessionCaptcha(sessionId, "")
        }
        override fun onSessionAutoResumed(sessionId: Int) {
            activity?.runOnUiThread {
                webViewForced = false
                updateContinueButton(false)
                updateWebViewOverlay()
            }
        }
    }

    private fun maxConcurrentByRam(): Int {
        return try {
            val am = requireContext().getSystemService(android.content.Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            val memClass = am.memoryClass // MB approx per app
            // Respect user concurrent setting (1–5). Only hard-cap on very low-RAM devices.
            when {
                memClass >= 128 -> 5
                memClass >= 96 -> 3
                memClass >= 64 -> 2
                else -> 1
            }
        } catch (_: Exception) {
            5
        }
    }

    private fun startMultiGeneration(nIn: Int) {
        stopMultiGeneration()
        multiMode = true
        val ramMax = maxConcurrentByRam()
        val n = nIn.coerceIn(1, ramMax)
        if (n < nIn) {
            onLog(LogEntry("RAM guard: concurrent capped $nIn → $n (memoryClass limit)", LogLevel.WARNING))
        }
        val ctx = requireContext().applicationContext
        val dao = AccountDatabase.getInstance(requireContext()).accountDao()
        val total = prefs.targetCount.coerceAtLeast(1)
        val per = ((total + n - 1) / n).coerceAtLeast(1)
        val container = binding.webViewContainer
        try { binding.webView.visibility = android.view.View.GONE } catch (_: Exception) {}
        multiSessions.clear()
        sharedMultiSessions.clear()
        for (i in 1..n) {
            val session = MultiGenSession(i, ctx, prefs, dao, multiHost)
            multiSessions.add(session)
            sharedMultiSessions.add(session)
            container.addView(session.webView)
        }
        activeSessionIndex = 0
        showSessionWebView(0)
        updateSessionChips(n, 0)
        try { if (prefs.genFgServiceEnabled) com.discord.evs.generator.service.GenForegroundService.start(requireContext().applicationContext) } catch (_: Exception) {}
        onLog(LogEntry("Multi-WebView generation — $n parallel sessions × ~$per accounts", LogLevel.SUCCESS))
        multiSessions.forEach { it.start(per) }
        updateButtonState(true)
        updateWebViewOverlay()
    }

    private fun stopMultiGeneration() {
        multiSessions.forEach { it.stop() }
        multiSessions.forEach { it.destroy() }
        multiSessions.clear()
        sharedMultiSessions.clear()
        multiMode = false
        try { binding.webView.visibility = android.view.View.VISIBLE } catch (_: Exception) {}
        hideSessionChips()
        updateButtonState(false)
    }

    private fun showSessionWebView(index: Int) {
        activeSessionIndex = index.coerceIn(0, (multiSessions.size - 1).coerceAtLeast(0))
        multiSessions.forEachIndexed { i, s ->
            s.webView.visibility = if (i == activeSessionIndex) android.view.View.VISIBLE else android.view.View.GONE
        }
        activity?.runOnUiThread {
            try {
                val s = multiSessions.getOrNull(activeSessionIndex) ?: return@runOnUiThread
                val merged = welcomeEntries + s.logs.takeLast(200)
                logAdapter.replaceAll(merged)
                binding.logRecycler.scrollToPosition(logAdapter.itemCount - 1)
            } catch (_: Exception) {}
        }
    }

    private fun hideSessionChips() {
        activity?.runOnUiThread {
            try {
                binding.root.findViewById<android.view.View>(R.id.session_chips_scroll)?.visibility = android.view.View.GONE
            } catch (_: Exception) {}
        }
    }

    private fun updateSessionChips(count: Int, activeIndex: Int) {
        val n = count.coerceIn(1, 5)
        activity?.runOnUiThread {
            try {
                val scroll = binding.root.findViewById<android.widget.HorizontalScrollView>(R.id.session_chips_scroll)
                val row = binding.root.findViewById<android.widget.LinearLayout>(R.id.session_chips) ?: return@runOnUiThread
                scroll?.visibility = android.view.View.VISIBLE
                row.removeAllViews()
                val density = resources.displayMetrics.density
                for (i in 0 until n) {
                    val tv = android.widget.TextView(requireContext()).apply {
                        text = "S${i + 1}"
                        setPadding((12 * density).toInt(), (6 * density).toInt(), (12 * density).toInt(), (6 * density).toInt())
                        setTextColor(if (i == activeIndex) android.graphics.Color.WHITE else android.graphics.Color.parseColor("#B9BBBE"))
                        setBackgroundColor(if (i == activeIndex) android.graphics.Color.parseColor("#5865F2") else android.graphics.Color.parseColor("#2F3136"))
                        textSize = 12f
                        val lp = android.widget.LinearLayout.LayoutParams(
                            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                        )
                        lp.marginEnd = (6 * density).toInt()
                        layoutParams = lp
                        setOnClickListener {
                            if (multiMode && multiSessions.isNotEmpty()) {
                                showSessionWebView(i)
                                updateSessionChips(multiSessions.size, i)
                            }
                        }
                    }
                    row.addView(tv)
                }
            } catch (_: Exception) {}
        }
    }


    /** Show only the active session WebView; hide the others. */
    private fun switchToSession(index: Int) {
        if (sharedMultiSessions.isEmpty()) return
        val idx = index.coerceIn(0, sharedMultiSessions.lastIndex)
        activeSessionIndex = idx
        val container = binding.webViewContainer
        sharedMultiSessions.forEachIndexed { i, s ->
            try {
                val v = s.webView
                if (v.parent == null) {
                    (v.parent as? android.view.ViewGroup)?.removeView(v)
                    container.addView(v)
                }
                v.visibility = if (i == idx) android.view.View.VISIBLE else android.view.View.GONE
            } catch (_: Exception) {}
        }
        rebuildSessionChips()
        try {
            val active = sharedMultiSessions[idx]
            onLog(LogEntry("Switched to multi-gen session #${active.id} (running=${active.generator.isRunning()})", LogLevel.INFO))
        } catch (_: Exception) {}
    }

    /** Rebuild #1 #2 session chips from sharedMultiSessions after tab return. */
    private fun rebuildSessionChips() {
        try {
            val scroll = binding.root.findViewById<android.widget.HorizontalScrollView>(R.id.session_chips_scroll)
            val row = binding.root.findViewById<android.widget.LinearLayout>(R.id.session_chips) ?: return
            row.removeAllViews()
            scroll?.visibility = if (sharedMultiSessions.size > 1) android.view.View.VISIBLE else android.view.View.GONE
            sharedMultiSessions.forEachIndexed { idx, s ->
                val running = try { s.generator.isRunning() } catch (_: Exception) { false }
                val density = resources.displayMetrics.density
                val tv = android.widget.TextView(requireContext()).apply {
                    text = "#${s.id} ${if (running) "RUN" else "IDLE"}"
                    setPadding((12 * density).toInt(), (6 * density).toInt(), (12 * density).toInt(), (6 * density).toInt())
                    setTextColor(if (idx == activeSessionIndex) android.graphics.Color.WHITE else android.graphics.Color.parseColor("#B9BBBE"))
                    setBackgroundColor(if (idx == activeSessionIndex) android.graphics.Color.parseColor("#5865F2") else android.graphics.Color.parseColor("#2F3136"))
                    textSize = 12f
                    setOnClickListener {
                        switchToSession(idx)
                    }
                }
                row.addView(tv)
            }
        } catch (_: Exception) {}
    }

    /** Re-attach multi session WebViews + rebuild chips after returning from another tab. */
    private fun restoreMultiSessionUi() {
        if (sharedMultiSessions.isEmpty()) return
        if (_binding == null) return
        val container = binding.webViewContainer
        multiSessions.clear()
        multiSessions.addAll(sharedMultiSessions)
        for (s in sharedMultiSessions) {
            try {
                val v = s.webView
                (v.parent as? android.view.ViewGroup)?.removeView(v)
                container.addView(v)
            } catch (_: Exception) {}
        }
        // Show only the active session so chips can switch
        val idx = activeSessionIndex.coerceIn(0, (sharedMultiSessions.size - 1).coerceAtLeast(0))
        sharedMultiSessions.forEachIndexed { i, s ->
            try {
                s.webView.visibility = if (i == idx) android.view.View.VISIBLE else android.view.View.GONE
            } catch (_: Exception) {}
        }
        try { rebuildSessionChips() } catch (_: Exception) {}
        val active = sharedMultiSessions.getOrNull(idx)
        if (active != null) {
            try {
                onLog(LogEntry("Restored multi-gen session #${active.id} (still running=${active.generator.isRunning()})", LogLevel.INFO))
            } catch (_: Exception) {}
        }
    }


}

// ─── Log Adapter ──────────────────────────────────────────────────────────────

class LogAdapter(
    private val onContinue: () -> Unit
) : RecyclerView.Adapter<LogAdapter.VH>() {
    private val entries = mutableListOf<LogEntry>()
    private val sdf = SimpleDateFormat("HH:mm:ss", Locale.US)

    fun add(entry: LogEntry) { entries.add(entry); notifyItemInserted(entries.size - 1) }
    fun replaceAll(newEntries: List<LogEntry>) {
        entries.clear()
        entries.addAll(newEntries)
        notifyDataSetChanged()
    }
    fun clear() {
        entries.clear()
        notifyDataSetChanged()
    }

    inner class VH(val binding: ItemLogBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        return VH(ItemLogBinding.inflate(LayoutInflater.from(parent.context), parent, false))
    }

    override fun getItemCount() = entries.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val entry = entries[position]
        val color = when (entry.level) {
            LogLevel.SUCCESS -> "#57f287"
            LogLevel.WARNING -> "#fee75c"
            LogLevel.ERROR   -> "#ed4245"
            LogLevel.CAPTCHA -> "#fee75c"
            LogLevel.INFO    -> "#f2f3f5"
        }
        val prefix = when (entry.level) {
            LogLevel.SUCCESS -> "[+]"
            LogLevel.WARNING -> "[!]"
            LogLevel.ERROR   -> "[-]"
            LogLevel.CAPTCHA -> "[!]"
            LogLevel.INFO    -> "[i]"
        }
        holder.binding.logTime.text = if (entry.timestamp == 0L) "NI:KO:LA" else sdf.format(Date(entry.timestamp))
        holder.binding.logText.text = "$prefix ${entry.message}"
        holder.binding.logText.setTextColor(Color.parseColor(color))

        if (entry.showContinue) {
            holder.binding.continueLogBtn.visibility = View.VISIBLE
            holder.binding.continueLogBtn.setOnClickListener { onContinue() }
        } else {
            holder.binding.continueLogBtn.visibility = View.GONE
        }
    }
}
