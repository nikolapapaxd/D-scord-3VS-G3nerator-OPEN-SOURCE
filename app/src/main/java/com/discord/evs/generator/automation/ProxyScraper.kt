package com.discord.evs.generator.automation

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Built-in free proxy list scraper + health check.
 * Sources: public proxy lists (HTTP). Results filtered by CONNECT test to discord.com.
 */
object ProxyScraper {
    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val sources = listOf(
        "https://api.proxyscrape.com/v2/?request=displayproxies&protocol=http&timeout=5000&country=all&ssl=all&anonymity=all",
        "https://api.proxyscrape.com/v2/?request=displayproxies&protocol=socks5&timeout=5000&country=all",
        "https://raw.githubusercontent.com/TheSpeedX/PROXY-List/master/http.txt",
        "https://raw.githubusercontent.com/TheSpeedX/PROXY-List/master/socks5.txt",
        "https://raw.githubusercontent.com/monosans/proxy-list/main/proxies/http.txt",
        "https://raw.githubusercontent.com/monosans/proxy-list/main/proxies/socks5.txt",
        "https://raw.githubusercontent.com/clarketm/proxy-list/master/proxy-list-raw.txt",
        "https://raw.githubusercontent.com/jetkai/proxy-list/main/online-proxies/txt/proxies-http.txt",
        "https://www.proxy-list.download/api/v1/get?type=http"
    )

    data class Proxy(val host: String, val port: Int) {
        fun asUrl(): String = "http://$host:$port"
        override fun toString() = "$host:$port"
    }

    suspend fun scrape(limit: Int = 80, onLog: (String) -> Unit = {}): List<Proxy> = withContext(Dispatchers.IO) {
        val found = linkedSetOf<String>()
        for (url in sources) {
            try {
                val req = Request.Builder().url(url).get()
                    .header("User-Agent", "Mozilla/5.0")
                    .build()
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@use
                    val body = resp.body?.string().orEmpty()
                    body.lineSequence().forEach { line ->
                        val s = line.trim()
                        if (s.matches(Regex("""\d{1,3}(\.\d{1,3}){3}:\d{2,5}"""))) {
                            found.add(s)
                        }
                    }
                }
                onLog("ProxyScraper: ${found.size} unique so far from sources")
            } catch (e: Exception) {
                onLog("ProxyScraper source fail: ${e.message}")
            }
        }
        found.take(limit).mapNotNull {
            val p = it.split(":")
            if (p.size == 2) Proxy(p[0], p[1].toIntOrNull() ?: return@mapNotNull null) else null
        }
    }

    suspend fun filterWorking(proxies: List<Proxy>, maxCheck: Int = 40, onLog: (String) -> Unit = {}): List<Proxy> =
        withContext(Dispatchers.IO) {
            val ok = mutableListOf<Proxy>()
            for (px in proxies.take(maxCheck)) {
                try {
                    val proxy = java.net.Proxy(java.net.Proxy.Type.HTTP, java.net.InetSocketAddress(px.host, px.port))
                    val c = OkHttpClient.Builder()
                        .proxy(proxy)
                        .connectTimeout(6, TimeUnit.SECONDS)
                        .readTimeout(6, TimeUnit.SECONDS)
                        .build()
                    val req = Request.Builder().url("https://discord.com/api/v9/experiments").get().build()
                    c.newCall(req).execute().use { resp ->
                        if (resp.code in 200..499) {
                            ok.add(px)
                            onLog("Proxy OK: $px")
                        }
                    }
                } catch (_: Exception) {
                }
                if (ok.size >= 15) break
            }
            onLog("ProxyScraper: ${ok.size} working")
            ok
        }
}
