package com.discord.evs.generator.automation

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * DuckMail temp-mail API (https://api.duckmail.sbs).
 *
 * Two modes:
 *  - apiKey blank  → public domains (no key) — create account + poll mailbox
 *  - apiKey set    → private domains / higher limits (Bearer / X-API-Key)
 *
 * Multi-gen safe: mailbox sessions stored per-address in [sessions].
 */
class DuckMailApi(private val apiKey: String = "") {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()
        val DEFAULT_PUBLIC_DOMAINS = listOf(
            "duckmail.sbs",
            "niceground.shop",
            "stoneground.shop",
            "lakeground.shop",
            "canvaspace.shop",
            "vercelspace.shop",
            "seedancespace.shop",
            "happyhorsespace.shop",
            "bananaspace.shop",
            "sunstarmoon.shop",
            "moonstarsun.shop",
            "sunmoonlight.shop",
            "makesomestone.shop",
            "mikesomelike.shop",
            "somestoneair.shop",
            "hubaiclass.org",
            "markaihub.shop",
            "markstonehub.org",
            "glasswhitehub.com"
        )
    }

    private data class MailboxSession(
        @Volatile var token: String,
        val password: String,
        val address: String
    )

    private fun baseUrl(): String = com.discord.evs.generator.security.StringCipher.duckMailBase()

    private val domains = mutableListOf<String>()
    private val sessions = ConcurrentHashMap<String, MailboxSession>()

    private fun domainHeaders(): Map<String, String> {
        val h = mutableMapOf(
            "Accept" to "application/json",
            "User-Agent" to "Mozilla/5.0"
        )
        if (apiKey.isNotBlank()) {
            h["Authorization"] = "Bearer $apiKey"
            h["X-API-Key"] = apiKey
        }
        return h
    }

    private fun fetchDomains() {
        try {
            val req = Request.Builder()
                .url("${baseUrl()}/domains")
                .apply { domainHeaders().forEach { (k, v) -> addHeader(k, v) } }
                .get()
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return
                val body = resp.body?.string().orEmpty()
                val parsed = parseDomains(body)
                if (parsed.isNotEmpty()) {
                    domains.clear()
                    domains.addAll(parsed)
                }
            }
        } catch (_: Exception) { }
        if (domains.isEmpty()) {
            domains.clear()
            domains.addAll(DEFAULT_PUBLIC_DOMAINS)
        }
    }

    private fun parseDomains(body: String): List<String> {
        val out = mutableListOf<String>()
        val trimmed = body.trim()
        try {
            when {
                trimmed.startsWith("{") -> {
                    val root = JSONObject(trimmed)
                    val arr = root.optJSONArray("hydra:member")
                        ?: root.optJSONArray("domains")
                        ?: root.optJSONArray("items")
                        ?: JSONArray()
                    for (i in 0 until arr.length()) {
                        val obj = arr.optJSONObject(i) ?: continue
                        val active = when {
                            obj.has("isActive") -> {
                                val v = obj.get("isActive")
                                when (v) {
                                    is Boolean -> v
                                    is Number -> v.toInt() == 1
                                    else -> v.toString().equals("true", true) || v.toString() == "1"
                                }
                            }
                            else -> true
                        }
                        if (!active) continue
                        val d = obj.optString("domain", "").trim()
                        if (d.isNotBlank()) out.add(d)
                    }
                }
                trimmed.startsWith("[") -> {
                    val arr = JSONArray(trimmed)
                    for (i in 0 until arr.length()) {
                        when (val item = arr.get(i)) {
                            is String -> out.add(item)
                            is JSONObject -> {
                                val d = item.optString("domain", item.optString("name", ""))
                                if (d.isNotBlank()) out.add(d)
                            }
                        }
                    }
                }
            }
        } catch (_: Exception) { }
        return out
    }

    private fun loginToken(address: String, password: String): String? {
        return try {
            val tokenBody = JSONObject()
                .put("address", address)
                .put("password", password)
                .toString()
                .toRequestBody(JSON)
            val tokenReq = Request.Builder()
                .url("${baseUrl()}/token")
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .post(tokenBody)
                .build()
            client.newCall(tokenReq).execute().use { tokenResp ->
                if (!tokenResp.isSuccessful) return null
                val tok = JSONObject(tokenResp.body?.string().orEmpty()).optString("token", "")
                tok.ifBlank { null }
            }
        } catch (_: Exception) {
            null
        }
    }

    suspend fun createEmail(username: String): CyberTempApi.EmailResult = withContext(Dispatchers.IO) {
        if (domains.isEmpty()) fetchDomains()
        if (domains.isEmpty()) {
            domains.clear()
            domains.addAll(DEFAULT_PUBLIC_DOMAINS)
        }
        val domain = if (domains.isNotEmpty()) domains.random() else "duckmail.sbs"
        val address = "$username@$domain"
        val password = UUID.randomUUID().toString().replace("-", "").take(16)

        val createBody = JSONObject()
            .put("address", address)
            .put("password", password)
            .toString()
            .toRequestBody(JSON)

        try {
            val createReq = Request.Builder()
                .url("${baseUrl()}/accounts")
                .header("Content-Type", "application/json")
                .apply { domainHeaders().forEach { (k, v) -> addHeader(k, v) } }
                .post(createBody)
                .build()
            client.newCall(createReq).execute().close()
        } catch (_: Exception) { }

        val token = loginToken(address, password)
        if (token.isNullOrBlank()) {
            return@withContext CyberTempApi.EmailResult(
                address = "",
                domain = "",
                domainFromApi = false
            )
        }
        sessions[address.lowercase()] = MailboxSession(token = token, password = password, address = address)
        CyberTempApi.EmailResult(address, domain, domains.isNotEmpty())
    }

    fun excludeDomain(domain: String) {
        domains.removeAll { it.equals(domain, ignoreCase = true) }
    }

    private fun resolveToken(emailAddr: String, onLog: ((String) -> Unit)?): String? {
        val key = emailAddr.lowercase()
        val session = sessions[key]
        if (session != null && session.token.isNotBlank()) {
            return session.token
        }
        if (session != null && session.password.isNotBlank()) {
            onLog?.invoke("DuckMail: re-login for $emailAddr…")
            val tok = loginToken(emailAddr, session.password)
            if (!tok.isNullOrBlank()) {
                sessions[key] = session.copy(token = tok)
                return tok
            }
            onLog?.invoke("DuckMail: re-login failed")
        }
        onLog?.invoke("DuckMail: no token for $emailAddr — cannot poll inbox")
        return null
    }

    suspend fun getVerificationUrl(
        emailAddr: String,
        timeoutMs: Long = 120_000L,
        onLog: ((String) -> Unit)? = null
    ): String? = withContext(Dispatchers.IO) {
        var token = resolveToken(emailAddr, onLog) ?: return@withContext null
        val start = System.currentTimeMillis()
        onLog?.invoke("Polling DuckMail inbox for: $emailAddr (up to ${timeoutMs / 1000}s)...")

        while (System.currentTimeMillis() - start < timeoutMs) {
            try {
                val req = Request.Builder()
                    .url("${baseUrl()}/messages")
                    .header("Authorization", "Bearer $token")
                    .header("Accept", "application/json")
                    .build()
                val resp = client.newCall(req).execute()
                if (resp.code == 401 || resp.code == 403) {
                    sessions[emailAddr.lowercase()]?.let { s ->
                        val fresh = loginToken(emailAddr, s.password)
                        if (!fresh.isNullOrBlank()) {
                            sessions[emailAddr.lowercase()] = s.copy(token = fresh)
                            token = fresh
                            onLog?.invoke("DuckMail: token refreshed after ${resp.code}")
                        }
                    }
                    resp.close()
                    delay(2_000)
                    continue
                }
                if (!resp.isSuccessful) {
                    resp.close()
                    delay(3_000)
                    continue
                }
                val body = resp.body?.string().orEmpty()
                resp.close()
                val messages = parseMessages(body)
                for (msg in messages) {
                    val id = msg.optString("id", msg.optString("@id", "")).substringAfterLast("/")
                    if (id.isBlank()) continue
                    val full = fetchMessageBody(id, token)
                    val url = extractVerifyUrl(full, onLog)
                    if (url != null) {
                        onLog?.invoke("✓ DuckMail verification link found")
                        return@withContext url
                    }
                    val intro = msg.optString("intro", "") + " " + msg.optString("subject", "")
                    val quick = extractVerifyUrl(intro, onLog)
                    if (quick != null) {
                        onLog?.invoke("✓ DuckMail verification link found (list)")
                        return@withContext quick
                    }
                }
            } catch (e: Exception) {
                onLog?.invoke("DuckMail poll error: ${e.message}")
            }
            delay(3_500)
        }
        onLog?.invoke("DuckMail: timeout — no verification mail")
        null
    }

    private fun parseMessages(body: String): List<JSONObject> {
        val out = mutableListOf<JSONObject>()
        try {
            val trimmed = body.trim()
            when {
                trimmed.startsWith("{") -> {
                    val root = JSONObject(trimmed)
                    val arr = root.optJSONArray("hydra:member")
                        ?: root.optJSONArray("messages")
                        ?: root.optJSONArray("items")
                        ?: JSONArray()
                    for (i in 0 until arr.length()) {
                        arr.optJSONObject(i)?.let { out.add(it) }
                    }
                }
                trimmed.startsWith("[") -> {
                    val arr = JSONArray(trimmed)
                    for (i in 0 until arr.length()) {
                        arr.optJSONObject(i)?.let { out.add(it) }
                    }
                }
            }
        } catch (_: Exception) { }
        return out
    }

    private fun fetchMessageBody(id: String, token: String): String {
        return try {
            val req = Request.Builder()
                .url("${baseUrl()}/messages/$id")
                .header("Authorization", "Bearer $token")
                .header("Accept", "application/json")
                .build()
            val resp = client.newCall(req).execute()
            if (!resp.isSuccessful) return ""
            val body = resp.body?.string() ?: return ""
            val obj = JSONObject(body)
            val html = obj.optJSONArray("html")?.let { arr ->
                (0 until arr.length()).joinToString("\n") { arr.optString(it) }
            } ?: obj.optString("html", "")
            val text = obj.optString("text", obj.optString("intro", ""))
            unescapeHtml("$html\n$text")
        } catch (_: Exception) {
            ""
        }
    }

    private fun extractVerifyUrl(body: String, onLog: ((String) -> Unit)? = null): String? {
        val regex = Regex(
            """https?://(?:click\.)?discord\.com/(?:ls/click|verify)[^\s"'<>]+"""
        )
        val links = regex.findAll(body)
            .map { it.value.trimEnd('.', ')', ']', ',', '"', '>', '\\', '\'') }
            .filter { it.length > 30 }
            .toList()
        for (link in links) {
            when {
                "click.discord.com" in link || "/ls/click" in link -> {
                    try {
                        onLog?.invoke("Following redirect: ${link.take(50)}...")
                        val req = Request.Builder()
                            .url(link)
                            .header("User-Agent", "Mozilla/5.0")
                            .build()
                        val resp = client.newCall(req).execute()
                        val finalUrl = resp.request.url.toString()
                        if ("discord.com/verify" in finalUrl || "token=" in finalUrl) return finalUrl
                        val respBody = resp.body?.string() ?: ""
                        val nested = Regex("""https://discord\.com/verify[^\s"'<>]*""")
                            .find(respBody)?.value
                        if (nested != null) return nested
                    } catch (_: Exception) { }
                }
                "discord.com/verify" in link -> return link
            }
        }
        return null
    }

    private fun unescapeHtml(input: String): String {
        return input
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&apos;", "'")
            .replace("&#x2F;", "/")
            .replace("&#x3D;", "=")
    }
}
