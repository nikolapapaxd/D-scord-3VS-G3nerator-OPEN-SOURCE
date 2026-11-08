package com.discord.evs.generator.automation

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit

/** Scores proxies for Discord use: latency, IP info, reachability. */
class ProxyQualityGate {
    data class Score(
        val proxy: String,
        val ok: Boolean,
        val score: Int,
        val ip: String = "",
        val country: String = "",
        val latencyMs: Long = -1,
        val discordOk: Boolean = false,
        val reason: String = ""
    )

    suspend fun evaluate(proxyLine: String, minScore: Int = 50): Score = withContext(Dispatchers.IO) {
        val parsed = parse(proxyLine) ?: return@withContext Score(proxyLine, false, 0, reason = "bad format")
        val start = System.currentTimeMillis()
        try {
            val client = OkHttpClient.Builder()
                .proxy(parsed)
                .connectTimeout(12, TimeUnit.SECONDS)
                .readTimeout(12, TimeUnit.SECONDS)
                .build()
            // IP probe
            val ipBody = client.newCall(Request.Builder().url("https://api.ipify.org?format=json").build())
                .execute().use { it.body?.string() ?: "" }
            val latency = System.currentTimeMillis() - start
            val ip = try { JSONObject(ipBody).optString("ip") } catch (_: Exception) { "" }
            // Discord reachability
            val discordOk = try {
                client.newCall(
                    Request.Builder().url("https://discord.com/api/v9/gateway")
                        .header("User-Agent", "Mozilla/5.0")
                        .build()
                ).execute().use { it.isSuccessful }
            } catch (_: Exception) { false }

            var score = 0
            if (ip.isNotBlank()) score += 30
            if (discordOk) score += 50
            if (latency in 1..800) score += 20
            else if (latency in 801..2000) score += 10
            Score(
                proxy = proxyLine,
                ok = score >= minScore && discordOk,
                score = score,
                ip = ip,
                latencyMs = latency,
                discordOk = discordOk,
                reason = if (discordOk) "ok" else "discord unreachable"
            )
        } catch (e: Exception) {
            Score(proxyLine, false, 0, reason = e.message ?: "error")
        }
    }

    private fun parse(line: String): Proxy? {
        // Prefer shared parser (supports user:pass@host:port and host:port:user:pass)
        ProxyManager.parseProxySpec(line)?.proxy?.let { return it }
        val parts = line.trim().split(":")
        if (parts.size < 2) return null
        val host = parts[0]
        val port = parts[1].toIntOrNull() ?: return null
        return Proxy(Proxy.Type.HTTP, InetSocketAddress(host, port))
    }
}
