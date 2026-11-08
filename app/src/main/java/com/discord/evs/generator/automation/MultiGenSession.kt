package com.discord.evs.generator.automation

import android.annotation.SuppressLint
import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.discord.evs.generator.data.Account
import com.discord.evs.generator.data.AccountDao
import com.discord.evs.generator.data.Prefs
import java.util.concurrent.CopyOnWriteArrayList

/**
 * One concurrent generation slot: dedicated WebView + AccountGenerator + log buffer.
 */
class MultiGenSession(
    val id: Int,
    context: Context,
    prefs: Prefs,
    dao: AccountDao,
    private val host: Host
) {
    interface Host {
        fun onSessionLog(sessionId: Int, entry: LogEntry)
        fun onSessionAccountSaved(account: Account)
        fun onSessionCounter(sessionId: Int, created: Int, target: Int)
        fun onSessionState(sessionId: Int, running: Boolean)
        fun onSessionCaptcha(sessionId: Int, siteKey: String)
        fun onSessionStuck(sessionId: Int, reason: String)
        fun onSessionContinueNeeded(sessionId: Int)
        fun onSessionAutoResumed(sessionId: Int)
    }

    val logs = CopyOnWriteArrayList<LogEntry>()
    @Volatile var created = 0
    @Volatile var target = 0
    @Volatile var running = false
    @Volatile var needsHuman = false

    @SuppressLint("SetJavaScriptEnabled")
    val webView: WebView = WebView(context.applicationContext).apply {
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        visibility = View.GONE
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.cacheMode = WebSettings.LOAD_DEFAULT
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
        try { ProxyManager.applyToWebView(context, this, prefs) } catch (_: Exception) {}
        try { FingerprintManager.apply(context, prefs, this) } catch (_: Exception) {}
    }

    private val listener = object : GenerationListener {
        override fun onLog(entry: LogEntry) {
            val tagged = if (entry.message.startsWith("[S")) entry
            else entry.copy(message = "[S$id] ${entry.message}")
            logs.add(tagged)
            while (logs.size > 400) {
                try { logs.remove(logs.first()) } catch (_: Exception) { break }
            }
            if (entry.showContinue) {
                needsHuman = true
                host.onSessionContinueNeeded(id)
            }
            host.onSessionLog(id, tagged)
        }
        override fun onAccountSaved(account: Account) {
            created++
            host.onSessionAccountSaved(account)
        }
        override fun onCounterUpdate(created: Int, target: Int) {
            this@MultiGenSession.created = created
            this@MultiGenSession.target = target
            host.onSessionCounter(id, created, target)
        }
        override fun onStateChange(running: Boolean) {
            this@MultiGenSession.running = running
            host.onSessionState(id, running)
        }
        override fun injectJs(js: String) {
            webView.post { try { webView.evaluateJavascript(js, null) } catch (_: Exception) {} }
        }
        override fun navigateTo(url: String) {
            webView.post { try { webView.loadUrl(url) } catch (_: Exception) {} }
        }
        override fun onCaptchaDetected(siteKey: String) {
            needsHuman = true
            host.onSessionCaptcha(id, siteKey)
        }
        override fun onCaptchaSolved(token: String) { needsHuman = false }
        override fun onStuck(reason: String) {
            needsHuman = true
            host.onSessionStuck(id, reason)
        }
        override fun onAutoResumed() {
            needsHuman = false
            host.onSessionAutoResumed(id)
        }
        override fun clearWebViewData() {
            webView.post {
                try {
                    // Per-WebView storage wipe (cookies are process-global — still clear so parallel
                    // sessions don't keep a logged-in Discord session from another slot).
                    CookieManager.getInstance().removeAllCookies(null)
                    CookieManager.getInstance().flush()
                    webView.clearCache(true)
                    webView.clearFormData()
                    webView.clearHistory()
                    webView.evaluateJavascript(
                        """
                        (function(){
                          try{localStorage.clear();sessionStorage.clear();}catch(e){}
                          try{
                            if(window.indexedDB && indexedDB.databases){
                              indexedDB.databases().then(function(dbs){
                                (dbs||[]).forEach(function(d){ if(d&&d.name) indexedDB.deleteDatabase(d.name); });
                              });
                            }
                          }catch(e){}
                        })();
                        """.trimIndent(),
                        null
                    )
                } catch (_: Exception) {}
            }
        }
        override fun getWebView(): WebView = webView
    }

    val generator: AccountGenerator = AccountGenerator(context, prefs, dao, listener)

    private inner class Bridge {
        @JavascriptInterface fun onLog(message: String, level: String) {
            val lvl = when (level.uppercase()) {
                "SUCCESS" -> LogLevel.SUCCESS
                "WARNING" -> LogLevel.WARNING
                "ERROR" -> LogLevel.ERROR
                "CAPTCHA" -> LogLevel.CAPTCHA
                else -> LogLevel.INFO
            }
            listener.onLog(LogEntry(message, lvl))
        }
        @JavascriptInterface fun onTokenFound(token: String) = generator.onBridgeTokenFound(token)
        @JavascriptInterface fun onCaptchaDetected(siteKey: String) = generator.onBridgeCaptchaDetected(siteKey)
        @JavascriptInterface fun onStepVerified(success: Boolean) { generator.onStepVerified(success) }
        @JavascriptInterface fun onIdentityVerificationRequired() = generator.onBridgeIdentityVerificationRequired()
        @JavascriptInterface fun onPhoneVerificationRequired() = generator.onBridgePhoneVerificationRequired()
        @JavascriptInterface fun onCaptchaAutoSolved() = generator.onBridgeCaptchaAutoSolved()
        @JavascriptInterface fun onEmailRejected() = generator.onBridgeEmailRejected()
        @JavascriptInterface fun onEmailRejectedReason(reason: String) = generator.onBridgeEmailRejectedReason(reason)
        @JavascriptInterface fun onAccountLocked() = generator.onBridgeAccountLocked()
        @JavascriptInterface fun onUsernameRejected() = generator.onBridgeUsernameRejected()
        @JavascriptInterface fun onPasswordRejected() = generator.onBridgePasswordRejected()
        @JavascriptInterface fun onDisplayNameRejected() = generator.onBridgeDisplayNameRejected()
        @JavascriptInterface fun onDobRejected() = generator.onBridgeDobRejected()
        @JavascriptInterface fun onAgeRestrictionDetected() = generator.onAgeRestrictionDetected()
        @JavascriptInterface fun onRegisterPageError() = generator.onBridgeRegisterPageError()
    }

    init {
        webView.addJavascriptInterface(Bridge(), "Android")
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean = false
            override fun onPageFinished(view: WebView?, url: String?) {
                val u = url.orEmpty()
                if (u.isBlank()) return
                val isRegister = u.contains("/register")
                val isLogin = u.contains("/login")
                // Token scrape on app pages
                if (!isRegister && !isLogin && (u.contains("discord.com") || u.contains("discordapp.com"))) {
                    view?.evaluateJavascript(
                        "(function(){try{var t=localStorage.getItem('token');if(t){t=String(t).replace(/^\"|\"$/g,'');if(t.length>20){Android.onTokenFound(t);}}}catch(e){}})();",
                        null
                    )
                }
                // Phone detect only when generator accepts phone gate
                val allowPhone = !isRegister && !isLogin &&
                    (u.contains("discord.com") || u.contains("discordapp.com")) &&
                    generator.shouldAcceptPhoneGate()
                if (allowPhone) {
                    view?.evaluateJavascript(
                        """
                        (function(){
                          if(window.__phoneDetected)return;
                          var phoneInput=document.querySelector('input[type="tel"],input[name="phone"],input[autocomplete="tel"]');
                          var hasPhone=false,hasEmail=false;
                          try{
                            var nodes=document.querySelectorAll('button,a,div[role="button"],span');
                            for(var i=0;i<nodes.length;i++){
                              var s=(nodes[i].innerText||nodes[i].textContent||'').toLowerCase();
                              if(/verify by phone/.test(s))hasPhone=true;
                              if(/verify by email/.test(s))hasEmail=true;
                            }
                          }catch(e){}
                          if(phoneInput||(hasPhone&&!hasEmail)){
                            window.__phoneDetected=true;
                            try{Android.onPhoneVerificationRequired();}catch(e){}
                          }
                        })();
                        """.trimIndent(),
                        null
                    )
                }
                try {
                    if (ExtensionHost.isEnabled()) {
                        ExtensionHost.injectInto(view) { }
                    }
                } catch (_: Exception) {}
            }
            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {}
        }
    }

    fun start(targetCount: Int) {
        target = targetCount
        created = 0
        needsHuman = false
        generator.start(targetCount)
    }

    fun stop() {
        try { generator.stop() } catch (_: Exception) {}
        running = false
    }

    fun continuePressed() {
        needsHuman = false
        generator.onContinuePressed()
    }

    fun destroy() {
        stop()
        try {
            webView.post {
                try {
                    webView.loadUrl("about:blank")
                    webView.removeJavascriptInterface("Android")
                    (webView.parent as? ViewGroup)?.removeView(webView)
                    webView.destroy()
                } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
    }
}
