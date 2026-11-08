package com.discord.evs.generator.ui

import android.annotation.SuppressLint
import android.os.Bundle
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/**
 * Web controller: Discord WebView with token injected into localStorage.
 */
class TokenWebControllerActivity : AppCompatActivity() {

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val token = intent.getStringExtra(EXTRA_TOKEN)?.trim().orEmpty()
        if (token.isBlank()) {
            Toast.makeText(this, "No token", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        val esc = token
            .replace("\\", "\\\\")
            .replace("'", "\\'")
            .replace("\"", "\\\"")
            .replace("\n", "")
            .replace("\r", "")

        val web = WebView(this)
        setContentView(web)
        title = "Token Web Controller"
        val settings = web.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        settings.userAgentString =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"
        web.webChromeClient = WebChromeClient()
        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                val js = buildString {
                    append("(function(){")
                    append("var t='")
                    append(esc)
                    append("';")
                    append("try{localStorage.setItem('token','\"'+t+'\"');}catch(e){}")
                    append("try{localStorage.setItem('Token','\"'+t+'\"');}catch(e){}")
                    append("})();")
                }
                view?.evaluateJavascript(js, null)
            }
        }
        web.loadUrl("https://discord.com/app")
        web.postDelayed({
            val js = buildString {
                append("(function(){")
                append("var t='")
                append(esc)
                append("';")
                append("try{localStorage.setItem('token','\"'+t+'\"');}catch(e){}")
                append("})();")
            }
            web.evaluateJavascript(js, null)
        }, 2500)
    }

    companion object {
        const val EXTRA_TOKEN = "token"
    }
}
