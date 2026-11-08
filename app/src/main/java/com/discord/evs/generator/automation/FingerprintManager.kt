package com.discord.evs.generator.automation

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.util.DisplayMetrics
import android.webkit.WebSettings
import android.webkit.WebView
import com.discord.evs.generator.data.Prefs
import java.util.Locale
import java.util.TimeZone

/**
 * Randomizes WebView fingerprint attributes to make each account look like it comes
 * from a different device / browser session.
 */
object FingerprintManager {

    private val USER_AGENTS = listOf(
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36",
        "Mozilla/5.0 (Linux; Android 13; SM-S918B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Mobile Safari/537.36",
        "Mozilla/5.0 (Linux; Android 12; Pixel 6) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36",
        "Mozilla/5.0 (iPhone; CPU iPhone OS 17_5 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) CriOS/125.0.6423.80 Mobile/15E148 Safari/604.1",
        "Mozilla/5.0 (Linux; Android 11; moto g power) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/123.0.0.0 Mobile Safari/537.36",
        "Mozilla/5.0 (Linux; Android 10; VOG-L29) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Mobile Safari/537.36"
    )

    private val LANGUAGES = listOf("en-US,en", "en-GB,en", "es-ES,es", "ru-RU,ru", "de-DE,de", "fr-FR,fr", "pt-BR,pt")

    /** Apply randomized fingerprint to a WebView. Must be called on the main thread. */
    @SuppressLint("SetJavaScriptEnabled")
    fun apply(context: Context, prefs: Prefs, webView: WebView) {
        val enabled = prefs.fingerprintRandomizationEnabled
        val ua = when {
            prefs.customUserAgent.isNotBlank() -> prefs.customUserAgent
            enabled || prefs.userAgentRotationEnabled -> USER_AGENTS.random()
            else -> "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"
        }

        webView.settings.apply {
            userAgentString = ua
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            cacheMode = WebSettings.LOAD_DEFAULT
            setSupportMultipleWindows(false)
        }

        if (!enabled) return

        // Random screen metrics
        val screens = prefs.fingerprintScreenSizes.split(",").map { it.trim() }.filter { it.isNotBlank() }
        val chosenScreen = screens.randomOrNull() ?: "360x640"
        val (width, height) = chosenScreen.split("x").map { it.toIntOrNull() ?: 360 }

        val density = (2.0 + Math.random() * 1.5).toFloat()
        webView.setInitialScale((100 * density / DisplayMetrics.DENSITY_DEFAULT).toInt())

        // Random timezone
        val zones = prefs.fingerprintTimezones.split(",").map { it.trim() }.filter { it.isNotBlank() }
        val tz = zones.randomOrNull() ?: TimeZone.getDefault().id

        // Locale
        val locale = Locale.forLanguageTag(LANGUAGES.random().substringBefore(","))
        Locale.setDefault(locale)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            context.resources.configuration.setLocale(locale)
        }

        // Inject anti-fingerprint JS (disable WebRTC, spoof canvas, etc.)
        val js = buildAntiFingerprintJs(width, height, density, tz, prefs)
        webView.evaluateJavascript(js, null)
    }

    private fun buildAntiFingerprintJs(width: Int, height: Int, density: Float, tz: String, prefs: Prefs): String {
        val webgl = if (prefs.fingerprintWebglDisabled) "true" else "false"
        val canvas = if (prefs.fingerprintCanvasSpoof) "true" else "false"
        val webrtc = if (prefs.webRtcDisabled) "true" else "false"
        return """
(function() {
  try {
    // Screen size
    Object.defineProperty(window.screen, 'width', { value: $width, configurable: true });
    Object.defineProperty(window.screen, 'height', { value: $height, configurable: true });
    Object.defineProperty(window.screen, 'availWidth', { value: $width, configurable: true });
    Object.defineProperty(window.screen, 'availHeight', { value: $height, configurable: true });
    Object.defineProperty(window, 'devicePixelRatio', { value: $density, configurable: true });
    // Timezone
    Object.defineProperty(Intl.DateTimeFormat.prototype, 'resolvedOptions', {
      value: function() { return { timeZone: '$tz', locale: 'en-US' }; }
    });
    // WebRTC
    if ($webrtc && window.RTCPeerConnection) {
      window.RTCPeerConnection = undefined;
      window.webkitRTCPeerConnection = undefined;
    }
    // WebGL disable
    if ($webgl) {
      var canvas = document.createElement('canvas');
      var proto = canvas.getContext('webgl') || canvas.getContext('experimental-webgl');
      if (proto) {
        HTMLCanvasElement.prototype.getContext = function(type) {
          if (type === 'webgl' || type === 'experimental-webgl') return null;
          return proto.constructor.apply(this, arguments);
        };
      }
    }
    // Canvas spoof
    if ($canvas) {
      var origToDataURL = HTMLCanvasElement.prototype.toDataURL;
      HTMLCanvasElement.prototype.toDataURL = function(type) {
        var ctx = this.getContext('2d');
        if (ctx) {
          var txt = 'EVS' + Math.random().toString(36).slice(2, 6);
          ctx.fillStyle = '#000';
          ctx.fillText(txt, 2, 10);
        }
        return origToDataURL.apply(this, arguments);
      };
    }
  } catch(e) {}
})();
        """.trimIndent()
    }
}
