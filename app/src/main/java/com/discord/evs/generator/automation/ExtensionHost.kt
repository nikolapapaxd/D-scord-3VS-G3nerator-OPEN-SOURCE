package com.discord.evs.generator.automation

import android.content.Context
import android.webkit.WebView
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Installs the real **NopeCHA** extension (unpacked from Chrome Web Store CRX)
 * into the app’s private files and injects its content scripts into the generator
 * WebView — the practical equivalent of Kiwi “Load unpacked”.
 *
 * Limits of Android system WebView:
 *  - No chrome.runtime native APIs → we polyfill messaging/storage.
 *  - Cross-origin iframes (hcaptcha.com) cannot be scripted from the parent page;
 *    scripts that target those frames only run when that frame’s document is the
 *    main frame (rare). Parent-page scripts + EVS captcha inject still apply tokens.
 *  - Full CRX APIs (debugger, declarativeNetRequest) are unavailable in WebView.
 *
 * Still: setup copies the real extension, injects matching content scripts on
 * discord.com pages, and enables Extension-mode solving without an API key.
 */
object ExtensionHost {

    private val ready = AtomicBoolean(false)
    @Volatile private var enabled = false
    @Volatile private var profileDir: File? = null
    private val scriptCache = mutableMapOf<String, String>()

    fun isEnabled(): Boolean = enabled

    fun setup(context: Context, log: (String) -> Unit = {}) {
        try {
            val root = File(context.filesDir, "extensions/nopecha")
            copyAssetTree(context, "extensions/nopecha", root)
            // Remove Chrome private metadata if present
            File(root, "_metadata").deleteRecursively()
            profileDir = root
            scriptCache.clear()
            // Preload JS listed in manifest content_scripts
            val manifest = File(root, "manifest.json")
            if (!manifest.exists()) {
                log("⚠ NopeCHA manifest missing after install")
                enabled = false
                return
            }
            val mj = JSONObject(manifest.readText())
            val ver = mj.optString("version", "?")
            val scripts = mj.optJSONArray("content_scripts") ?: JSONArray()
            var loaded = 0
            for (i in 0 until scripts.length()) {
                val entry = scripts.getJSONObject(i)
                val jsArr = entry.optJSONArray("js") ?: continue
                for (j in 0 until jsArr.length()) {
                    val rel = jsArr.getString(j)
                    if (loadScript(root, rel) != null) loaded++
                }
            }
            enabled = true
            ready.set(true)
            log("✓ NopeCHA extension installed v$ver ($loaded content scripts)")
            log("✓ Profile: ${root.absolutePath}")
            log("✓ Extension mode active — content scripts inject on page load")
        } catch (e: Exception) {
            enabled = false
            log("⚠ NopeCHA extension setup failed: ${e.message}")
        }
    }

    fun teardown() {
        enabled = false
    }

    fun injectInto(webView: WebView?, scheduleFollowUp: Boolean = true, log: (String) -> Unit = {}) {
        if (!enabled || webView == null) return
        val root = profileDir ?: return
        try {
            webView.evaluateJavascript(POLYFILL, null)
            val manifest = File(root, "manifest.json")
            if (!manifest.exists()) return
            val scripts = JSONObject(manifest.readText()).optJSONArray("content_scripts") ?: return
            // Inject scripts whose matches cover discord / all_urls (parent page)
            for (i in 0 until scripts.length()) {
                val entry = scripts.getJSONObject(i)
                val matches = entry.optJSONArray("matches") ?: continue
                if (!matchesParentPage(matches)) continue
                val jsArr = entry.optJSONArray("js") ?: continue
                for (j in 0 until jsArr.length()) {
                    val rel = jsArr.getString(j)
                    val code = loadScript(root, rel) ?: continue
                    // Wrap so one script failure doesn’t kill the page
                    val wrapped = "(function(){try{\n$code\n}catch(e){try{Android.onLog('ext script $rel: '+e.message,'WARNING');}catch(_e){}}})();"
                    webView.evaluateJavascript(wrapped, null)
                }
            }
            // Always ensure our token applicator exists
            webView.evaluateJavascript(TOKEN_HELPER, null)
            log("NopeCHA content scripts injected")
            if (scheduleFollowUp) {
                webView.postDelayed({
                    try { injectInto(webView, scheduleFollowUp = false) } catch (_: Exception) {}
                }, 2500L)
                webView.postDelayed({
                    try { injectInto(webView, scheduleFollowUp = false) } catch (_: Exception) {}
                }, 6000L)
            }
        } catch (e: Exception) {
            log("Extension inject error: ${e.message}")
        }
    }

    private fun matchesParentPage(matches: JSONArray): Boolean {
        for (i in 0 until matches.length()) {
            val m = matches.getString(i)
            if (m == "<all_urls>" || m.contains("discord.com") || m.contains("*://*/*")) return true
        }
        return false
    }

    private fun loadScript(root: File, rel: String): String? {
        scriptCache[rel]?.let { return it }
        val f = File(root, rel)
        if (!f.exists()) return null
        return try {
            val s = f.readText()
            scriptCache[rel] = s
            s
        } catch (_: Exception) {
            null
        }
    }

    private fun copyAssetTree(context: Context, assetPath: String, dest: File) {
        val am = context.assets
        val children = try { am.list(assetPath) } catch (_: Exception) { null }
        if (children.isNullOrEmpty()) {
            // file
            dest.parentFile?.mkdirs()
            am.open(assetPath).use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            }
            return
        }
        dest.mkdirs()
        for (name in children) {
            copyAssetTree(context, "$assetPath/$name", File(dest, name))
        }
    }

    private val POLYFILL = """
(function(){
  if (window.__evsNopechaPolyfill) return;
  window.__evsNopechaPolyfill = true;
  window.chrome = window.chrome || {};
  var listeners = [];
  window.chrome.runtime = {
    id: 'dknlfmjaanfblgfdfebhijalfmhmjjjo',
    getManifest: function(){ return { name: 'NopeCHA: CAPTCHA Solver', version: '0.6.1' }; },
    getURL: function(p){ return p; },
    sendMessage: function(msg, cb){
      try {
        if (window.Android && Android.onLog) Android.onLog('NopeCHA runtime.sendMessage', 'INFO');
        if (typeof cb === 'function') setTimeout(function(){ cb({}); }, 0);
      } catch(e) {}
      return Promise.resolve({});
    },
    onMessage: {
      addListener: function(fn){ listeners.push(fn); },
      removeListener: function(fn){ listeners = listeners.filter(function(x){ return x!==fn; }); }
    },
    connect: function(){ return { postMessage: function(){}, onMessage: { addListener: function(){} }, disconnect: function(){} }; }
  };
  window.chrome.storage = {
    local: {
      get: function(keys, cb){
        var out = {};
        try {
          var raw = localStorage.getItem('__nopecha_store');
          var store = raw ? JSON.parse(raw) : {};
          if (!keys) out = store;
          else if (typeof keys === 'string') out[keys] = store[keys];
          else if (Array.isArray(keys)) keys.forEach(function(k){ out[k]=store[k]; });
          else if (typeof keys === 'object') { out = Object.assign({}, keys, store); }
        } catch(e) {}
        if (typeof cb === 'function') cb(out);
        return Promise.resolve(out);
      },
      set: function(obj, cb){
        try {
          var raw = localStorage.getItem('__nopecha_store');
          var store = raw ? JSON.parse(raw) : {};
          Object.keys(obj||{}).forEach(function(k){ store[k]=obj[k]; });
          localStorage.setItem('__nopecha_store', JSON.stringify(store));
        } catch(e) {}
        if (typeof cb === 'function') cb();
        return Promise.resolve();
      }
    },
    sync: { get: function(k,cb){ if(cb)cb({}); }, set: function(o,cb){ if(cb)cb(); } }
  };
  window.chrome.scripting = window.chrome.scripting || { executeScript: function(){ return Promise.resolve([]); } };
  try { Android.onLog('NopeCHA chrome.* polyfill ready', 'SUCCESS'); } catch(e) {}
})();
""".trimIndent()

    private val TOKEN_HELPER = """
(function(){
  window.__evsExtSolve = function(token){
    if (!token) return;
    try {
      window.__lastInjectedCaptchaToken = token;
      document.querySelectorAll('textarea[name="h-captcha-response"],textarea[name="g-recaptcha-response"],[name="h-captcha-response"]').forEach(function(el){
        el.value = token; el.innerHTML = token;
        el.dispatchEvent(new Event('input',{bubbles:true}));
        el.dispatchEvent(new Event('change',{bubbles:true}));
      });
      if (window.hcaptcha) {
        try { window.hcaptcha.setResponse(token); } catch(e) {}
        try {
          document.querySelectorAll('[data-hcaptcha-widget-id],iframe[src*="hcaptcha"]').forEach(function(n){
            var id = n.getAttribute('data-hcaptcha-widget-id');
            if (id) try { window.hcaptcha.setResponse(id, token); } catch(e) {}
          });
        } catch(e) {}
      }
      try { window.parent && window.parent.postMessage({source:'hcaptcha',response:token,'h-captcha-response':token},'*'); } catch(e) {}
      try { Android.onLog('NopeCHA host applied token len='+token.length, 'SUCCESS'); } catch(e) {}
    } catch(e) {}
  };
})();
""".trimIndent()
}
