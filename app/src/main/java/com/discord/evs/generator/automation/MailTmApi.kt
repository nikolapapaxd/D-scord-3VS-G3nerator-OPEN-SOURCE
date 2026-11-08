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
 * mail.tm free temp-mail API (no API key).
 *
 * IMPORTANT: Never use the literal domain "mail.tm" — Discord blocks it.
 * Always pick an active domain from GET /domains?page=1 (e.g. emalupe.com).
 */
class MailTmApi {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()
        /** Domains Discord is known to reject — never use these. */
        private val BLOCKED_DOMAINS = setOf(
            "mail.tm", "mail.gw", "mailgw.com", "diddyricky.com"
        )
    }

    private fun baseUrl(): String = com.discord.evs.generator.security.StringCipher.mailTmBase()

    private val domains = mutableListOf<String>()
    private data class MailboxSession(
        @Volatile var token: String,
        val password: String,
        val address: String
    )
    /** address.lowercase() → session. Multi-gen safe. */
    private val sessions = ConcurrentHashMap<String, MailboxSession>()

    suspend fun fetchDomains(): List<String> = withContext(Dispatchers.IO) {
        val list = mutableListOf<String>()
        // Try page=1 first (user-confirmed shape), then bare /domains
        val urls = listOf(
            "${baseUrl()}/domains?page=1",
            "${baseUrl()}/domains"
        )
        for (url in urls) {
            try {
                val req = Request.Builder()
                    .url(url)
                    .header("Accept", "application/json")
                    .header("User-Agent", "Mozilla/5.0")
                    .build()
                val resp = client.newCall(req).execute()
                if (!resp.isSuccessful) continue
                val body = resp.body?.string() ?: continue
                parseDomains(body).forEach { d ->
                    if (d.isNotBlank() && d.lowercase() !in BLOCKED_DOMAINS && d !in list) {
                        list.add(d)
                    }
                }
                if (list.isNotEmpty()) break
            } catch (_: Exception) { }
        }
        if (list.isNotEmpty()) {
            domains.clear()
            domains.addAll(list)
        }
        list
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
                        // isActive may be boolean true or int 1
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

    suspend fun createEmail(username: String, preferredPassword: String? = null): CyberTempApi.EmailResult = withContext(Dispatchers.IO) {
        if (domains.isEmpty()) fetchDomains()
        // NEVER fall back to mail.tm — Discord blocks it
        if (domains.isEmpty()) {
            return@withContext CyberTempApi.EmailResult(
                address = "",
                domain = "",
                domainFromApi = false
            )
        }

        var lastError: String? = null
        // Try up to 3 different domains if account creation fails
        val tried = mutableSetOf<String>()
        repeat(3) {
            val domain = domains.filter { it !in tried }.randomOrNull() ?: domains.random()
            tried.add(domain)
            val address = "$username@$domain"
            val password = preferredPassword?.takeIf { it.length >= 8 } ?: UUID.randomUUID().toString().replace("-", "").take(16)

            val createBody = JSONObject()
                .put("address", address)
                .put("password", password)
                .toString()
                .toRequestBody(JSON)

            var created = false
            try {
                val createReq = Request.Builder()
                    .url("${baseUrl()}/accounts")
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .header("User-Agent", "Mozilla/5.0")
                    .post(createBody)
                    .build()
                val createResp = client.newCall(createReq).execute()
                val code = createResp.code
                val cBody = createResp.body?.string() ?: ""
                // 201 created, 200 ok, 422 may mean already exists
                created = code in listOf(200, 201, 422)
                if (!created) lastError = "create HTTP $code $cBody"
            } catch (e: Exception) {
                lastError = e.message
            }

            // Token
            try {
                val tokenBody = JSONObject()
                    .put("address", address)
                    .put("password", password)
                    .toString()
                    .toRequestBody(JSON)
                val tokenReq = Request.Builder()
                    .url("${baseUrl()}/token")
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .header("User-Agent", "Mozilla/5.0")
                    .post(tokenBody)
                    .build()
                val tokenResp = client.newCall(tokenReq).execute()
                if (tokenResp.isSuccessful) {
                    val tBody = tokenResp.body?.string() ?: ""
                    val tObj = JSONObject(tBody)
                    val tok = tObj.optString("token", "")
                    if (tok.isNotBlank()) {
                        sessions[address.lowercase()] = MailboxSession(token = tok, password = password, address = address)
                        return@withContext CyberTempApi.EmailResult(address, domain, true, password)
                    }
                } else {
                    lastError = "token HTTP ${tokenResp.code}"
                }
            } catch (e: Exception) {
                lastError = e.message
            }

            // Prefer success only when we have a usable token. Returning address without
            // token caused "mail.tm: no token for email — cannot poll inbox" during verify.
            if (created) {
                val existing = sessions[address.lowercase()]
                if (existing != null && existing.token.isNotBlank()) {
                    return@withContext CyberTempApi.EmailResult(address, domain, true)
                }
                // keep password session so poll can re-login even without token yet
                sessions[address.lowercase()] = MailboxSession(token = "", password = password, address = address)
            }
        }

        // Last resort: any session with password can still re-login on poll
        val any = sessions.values.lastOrNull { it.password.isNotBlank() }
        if (any != null) {
            return@withContext CyberTempApi.EmailResult(
                any.address,
                any.address.substringAfter("@", ""),
                true
            )
        }
        CyberTempApi.EmailResult("", lastError ?: "no domain", false)
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
                .header("User-Agent", "Mozilla/5.0")
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

    private fun resolveToken(emailAddr: String, onLog: ((String) -> Unit)?): String? {
        val key = emailAddr.lowercase()
        val session = sessions[key]
        if (session != null && session.token.isNotBlank()) return session.token
        if (session != null && session.password.isNotBlank()) {
            onLog?.invoke("mail.tm: re-login for $emailAddr…")
            val tok = loginToken(emailAddr, session.password)
            if (!tok.isNullOrBlank()) {
                sessions[key] = session.copy(token = tok)
                return tok
            }
            onLog?.invoke("mail.tm: re-login failed")
        }
        onLog?.invoke("mail.tm: no token for $emailAddr — cannot poll inbox")
        return null
    }

    fun excludeDomain(domain: String) {
        domains.removeAll { it.equals(domain, ignoreCase = true) }
    }

    suspend fun getVerificationUrl(
        emailAddr: String,
        timeoutMs: Long = 120_000L,
        onLog: ((String) -> Unit)? = null
    ): String? = withContext(Dispatchers.IO) {
        var token = resolveToken(emailAddr, onLog) ?: return@withContext null
        val start = System.currentTimeMillis()
        onLog?.invoke("Polling mail.tm inbox for: $emailAddr (up to ${timeoutMs / 1000}s)...")

        while (System.currentTimeMillis() - start < timeoutMs) {
            try {
                val req = Request.Builder()
                    .url("${baseUrl()}/messages")
                    .header("Authorization", "Bearer $token")
                    .header("Accept", "application/json")
                    .header("User-Agent", "Mozilla/5.0")
                    .build()
                val resp = client.newCall(req).execute()
                if (resp.code == 200) {
                    val body = resp.body?.string() ?: ""
                    val root = JSONObject(body)
                    val arr = root.optJSONArray("hydra:member") ?: root.optJSONArray("member") ?: root.optJSONArray("messages") ?: JSONArray()
                    for (i in 0 until arr.length()) {
                        val msg = arr.getJSONObject(i)
                        val subject = msg.optString("subject", "").lowercase()
                        val fromObj = msg.optJSONObject("from")
                        val from = (fromObj?.optString("address") ?: msg.optString("from", "")).lowercase()
                        if ("verify" !in subject && "discord" !in from) continue
                        onLog?.invoke("Found Discord email — subject: \"${msg.optString("subject", "")}\"")
                        val msgId = msg.optString("id", "")
                        if (msgId.isNotBlank()) {
                            val full = fetchMessage(msgId, token)
                            val verifyUrl = extractVerifyUrl(full, onLog)
                            if (verifyUrl != null) {
                                onLog?.invoke("✓ Verification URL found: ${verifyUrl.take(60)}...")
                                return@withContext verifyUrl
                            }
                        }
                    }
                } else if (resp.code == 401) {
                    onLog?.invoke("mail.tm token expired/invalid")
                    return@withContext null
                }
            } catch (_: Exception) { }
            delay(3_000L)
        }
        onLog?.invoke("Verification link not received within ${timeoutMs / 1000}s")
        null
    }

    private fun fetchMessage(id: String, token: String): String {
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
                        val req = Request.Builder().url(link).header("User-Agent", "Mozilla/5.0").build()
                        val resp = client.newCall(req).execute()
                        val finalUrl = resp.request.url.toString()
                        if ("discord.com/verify" in finalUrl || "token=" in finalUrl) return finalUrl
                        val respBody = resp.body?.string() ?: ""
                        val nested = Regex("""https://discord\.com/verify[^\s"'<>]*""").find(respBody)?.value
                        if (nested != null) return nested
                    } catch (_: Exception) {}
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
