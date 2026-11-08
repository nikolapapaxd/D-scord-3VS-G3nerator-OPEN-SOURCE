package com.discord.evs.generator.automation

import android.content.Context
import android.os.Build
import android.webkit.WebView
import com.discord.evs.generator.data.Prefs
import okhttp3.Authenticator
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Proxy pool with health tracking and auth support.
 * Formats: host:port | user:pass@host:port | host:port:user:pass | http://... | socks5://...
 */
object ProxyManager {
    private val index = AtomicInteger(0)
    private val deadProxies = mutableSetOf<String>()
    private val proxyStats = mutableMapOf<String, ProxyStats>()

    data class ProxyStats(
        var uses: Int = 0,
        var failures: Int = 0,
        var lastSuccess: Long = 0L
    )

    data class ProxySpec(
        val proxy: Proxy,
        val username: String? = null,
        val password: String? = null,
        val raw: String = ""
    )

    fun resetHealth() {
        deadProxies.clear()
        proxyStats.clear()
    }

    fun markFailed(raw: String) {
        val normalized = normalize(raw)
        val stats = proxyStats.getOrPut(normalized) { ProxyStats() }
        stats.failures++
        if (stats.failures >= 3) deadProxies.add(normalized)
    }

    fun markSuccess(raw: String) {
        val normalized = normalize(raw)
        proxyStats.getOrPut(normalized) { ProxyStats() }.apply {
            uses++
            lastSuccess = System.currentTimeMillis()
        }
    }

    fun getProxySpec(prefs: Prefs): ProxySpec? {
        if (!prefs.proxyEnabled) return null
        val candidates = allCandidates(prefs)
        if (candidates.isEmpty()) return null
        val live = candidates.filter { normalize(it) !in deadProxies }
        val pool = live.ifEmpty { candidates }
        // rotation: sequential | random | weighted | round_robin
        val raw = when (prefs.proxyRotationMode) {
            "random" -> pool.random()
            "sequential" -> pool[index.getAndIncrement() % pool.size]
            "weighted" -> pool.minByOrNull { proxyStats[normalize(it)]?.failures ?: 0 } ?: pool.first()
            "round_robin" -> {
                val idx = index.getAndIncrement().let { if (it < 0) -it else it } % pool.size
                pool[idx]
            }
            else -> pool.first()
        }
        return parseProxySpec(raw)
    }

    fun getProxy(prefs: Prefs): Proxy? = getProxySpec(prefs)?.proxy

    fun getFallback(prefs: Prefs, current: Proxy?): Proxy? {
        if (!prefs.proxyEnabled) return null
        val candidates = allCandidates(prefs)
        if (candidates.size <= 1) return null
        for (raw in candidates.shuffled()) {
            val spec = parseProxySpec(raw) ?: continue
            if (spec.proxy != current) return spec.proxy
        }
        return null
    }

    /** OkHttp client that uses current proxy + auth. */
    fun buildClient(prefs: Prefs, timeoutSec: Long = 20): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(timeoutSec, TimeUnit.SECONDS)
            .readTimeout(timeoutSec, TimeUnit.SECONDS)
            .writeTimeout(timeoutSec, TimeUnit.SECONDS)
        val spec = getProxySpec(prefs)
        if (spec != null) {
            builder.proxy(spec.proxy)
            if (!spec.username.isNullOrBlank()) {
                val user = spec.username
                val pass = spec.password ?: ""
                builder.proxyAuthenticator(object : Authenticator {
                    override fun authenticate(route: Route?, response: Response): Request? {
                        if (response.request.header("Proxy-Authorization") != null) return null
                        val credential = Credentials.basic(user, pass)
                        return response.request.newBuilder()
                            .header("Proxy-Authorization", credential)
                            .build()
                    }
                })
            }
        }
        return builder.build()
    }

    fun testProxy(proxy: Proxy, timeoutSec: Int = 8): Boolean {
        return try {
            val client = OkHttpClient.Builder()
                .proxy(proxy)
                .connectTimeout(timeoutSec.toLong(), TimeUnit.SECONDS)
                .readTimeout(timeoutSec.toLong(), TimeUnit.SECONDS)
                .build()
            val resp = client.newCall(
                Request.Builder().url("https://discord.com/api/v9/gateway").header("User-Agent", "Mozilla/5.0").build()
            ).execute()
            resp.isSuccessful || resp.code in listOf(401, 403)
        } catch (_: Exception) { false }
    }

    fun testProxyRaw(raw: String, timeoutSec: Int = 10): Boolean {
        val spec = parseProxySpec(raw) ?: return false
        return try {
            val builder = OkHttpClient.Builder()
                .proxy(spec.proxy)
                .connectTimeout(timeoutSec.toLong(), TimeUnit.SECONDS)
                .readTimeout(timeoutSec.toLong(), TimeUnit.SECONDS)
            if (!spec.username.isNullOrBlank()) {
                val user = spec.username
                val pass = spec.password ?: ""
                builder.proxyAuthenticator { _, response ->
                    if (response.request.header("Proxy-Authorization") != null) null
                    else response.request.newBuilder()
                        .header("Proxy-Authorization", Credentials.basic(user, pass))
                        .build()
                }
            }
            val resp = builder.build().newCall(
                Request.Builder().url("https://discord.com/api/v9/gateway").header("User-Agent", "Mozilla/5.0").build()
            ).execute()
            resp.isSuccessful || resp.code in listOf(401, 403)
        } catch (_: Exception) { false }
    }

    /**
     * Best-effort WebView proxy. Android WebView proxy support is limited;
     * on API 30+ we use ProxyController when possible. Auth proxies may still
     * only work for OkHttp traffic (token check, mail APIs).
     */
    /**
     * Best-effort WebView proxy binding.
     * Android does not give apps a clean per-WebView authenticated proxy API.
     * We set JVM proxy system properties (some WebView builds honor these) and
     * on API 30+ attempt ProxyController when the platform allows it.
     * Auth proxies remain most reliable for OkHttp (mail/token/API); WebView may
     * still leak direct on locked OEMs — caller should log when proxyEnabled.
     */
    fun applyToWebView(context: Context, @Suppress("UNUSED_PARAMETER") webView: WebView, prefs: Prefs) {
        if (!prefs.proxyEnabled) {
            // Clear previous process-wide proxy props so disabled state is real
            try {
                System.clearProperty("http.proxyHost")
                System.clearProperty("http.proxyPort")
                System.clearProperty("https.proxyHost")
                System.clearProperty("https.proxyPort")
                System.clearProperty("http.proxyUser")
                System.clearProperty("http.proxyPassword")
                System.clearProperty("https.proxyUser")
                System.clearProperty("https.proxyPassword")
            } catch (_: Exception) {}
            return
        }
        val spec = getProxySpec(prefs) ?: return
        val addr = spec.proxy.address() as? InetSocketAddress ?: return
        try {
            System.setProperty("http.proxyHost", addr.hostString)
            System.setProperty("http.proxyPort", addr.port.toString())
            System.setProperty("https.proxyHost", addr.hostString)
            System.setProperty("https.proxyPort", addr.port.toString())
            System.setProperty("http.nonProxyHosts", "localhost|127.*|[::1]")
            if (!spec.username.isNullOrBlank()) {
                System.setProperty("http.proxyUser", spec.username)
                System.setProperty("http.proxyPassword", spec.password ?: "")
                System.setProperty("https.proxyUser", spec.username)
                System.setProperty("https.proxyPassword", spec.password ?: "")
                // Authenticator for code paths that honor java.net
                java.net.Authenticator.setDefault(object : java.net.Authenticator() {
                    override fun getPasswordAuthentication(): java.net.PasswordAuthentication? {
                        if (requestorType != RequestorType.PROXY) return null
                        return java.net.PasswordAuthentication(
                            spec.username,
                            (spec.password ?: "").toCharArray()
                        )
                    }
                })
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                try {
                    // ProxyController is process-wide; only set if we can resolve ConnectivityManager
                    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE)
                    if (cm != null) {
                        // Reflective optional bind — avoids hard dependency when stripped
                        val pcClass = Class.forName("androidx.webkit.ProxyController")
                        val getInstance = pcClass.getMethod("getInstance")
                        val controller = getInstance.invoke(null)
                        val configClass = Class.forName("androidx.webkit.ProxyConfig")
                        val builderClass = Class.forName("androidx.webkit.ProxyConfig\$Builder")
                        val builder = builderClass.getConstructor().newInstance()
                        val proxyRule = "${addr.hostString}:${addr.port}"
                        builderClass.getMethod("addProxyRule", String::class.java).invoke(builder, proxyRule)
                        builderClass.getMethod("addDirect").invoke(builder) // fallback if proxy dies
                        val config = builderClass.getMethod("build").invoke(builder)
                        val exec = java.util.concurrent.Executors.newSingleThreadExecutor()
                        pcClass.getMethod(
                            "setProxyOverride",
                            configClass,
                            java.util.concurrent.Executor::class.java,
                            Runnable::class.java
                        ).invoke(controller, config, exec, Runnable { })
                    }
                } catch (_: Exception) {
                    // OEM / missing webkit — system properties remain
                }
            }
        } catch (_: Exception) { }
        // Note: authenticated proxies (user:pass) apply to OkHttp paths reliably.
        // WebView on many OEMs ignores JVM Authenticator for HTTPS — expect partial coverage.
    }

    private fun allCandidates(prefs: Prefs): List<String> {
        val rawList = prefs.proxyList.trim().lines().filter { it.isNotBlank() }
        val single = prefs.proxyAddress.trim().takeIf { it.isNotBlank() }
        return ((single?.let { listOf(it) } ?: emptyList()) + rawList).distinct()
    }

    private fun normalize(raw: String): String =
        raw.trim().lowercase()
            .removePrefix("http://").removePrefix("https://")
            .removePrefix("socks5://").removePrefix("socks4://")

    fun parseProxySpec(raw: String): ProxySpec? {
        return try {
            val s = raw.trim()
            if (s.isEmpty()) return null
            val type = when {
                s.startsWith("socks5://", true) || s.startsWith("socks4://", true) -> Proxy.Type.SOCKS
                else -> Proxy.Type.HTTP
            }
            var rest = s
                .removePrefix("socks5://").removePrefix("socks4://")
                .removePrefix("http://").removePrefix("https://")
            var user: String? = null
            var pass: String? = null
            if ("@" in rest) {
                val (cred, hostpart) = rest.split("@", limit = 2)
                val cp = cred.split(":", limit = 2)
                user = cp[0]
                pass = cp.getOrElse(1) { "" }
                rest = hostpart
            }
            val parts = rest.split(":")
            val host: String
            val port: Int
            when {
                parts.size >= 4 && user == null -> {
                    // host:port:user:pass
                    host = parts[0]
                    port = parts[1].toInt()
                    user = parts[2]
                    pass = parts[3]
                }
                parts.size >= 2 -> {
                    host = parts[0]
                    port = parts[1].substringBefore("/").toInt()
                }
                else -> return null
            }
            ProxySpec(Proxy(type, InetSocketAddress(host, port)), user, pass, s)
        } catch (_: Exception) {
            null
        }
    }

    private fun parseProxy(raw: String): Proxy? = parseProxySpec(raw)?.proxy

    suspend fun refreshFromScraper(prefs: com.discord.evs.generator.data.Prefs, onLog: (String) -> Unit = {}): Int {
        if (!prefs.proxyScraperEnabled) return 0
        val scraped = ProxyScraper.scrape(80, onLog)
        val working = ProxyScraper.filterWorking(scraped, 40, onLog)
        if (working.isEmpty()) return 0
        val existing = prefs.proxyList.split("\n").map { it.trim() }.filter { it.isNotEmpty() }.toMutableList()
        for (px in working) {
            val line = px.toString()
            if (line !in existing) existing.add(line)
        }
        prefs.proxyList = existing.joinToString("\n")
        onLog("ProxyManager: added ${working.size} working proxies")
        return working.size
    }

}
