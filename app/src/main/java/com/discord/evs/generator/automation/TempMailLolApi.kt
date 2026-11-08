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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * TempMail.lol free API (no key required for basic inbox).
 * Create: POST https://api.tempmail.lol/v2/inbox/create
 * Check:  GET  https://api.tempmail.lol/v2/inbox?token=...
 */
class TempMailLolApi {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    companion object {
        private const val BASE = "https://api.tempmail.lol"
        private val JSON = "application/json; charset=utf-8".toMediaType()
    }

    private var inboxToken: String? = null
    private var currentEmail: String? = null
    private val sessions = ConcurrentHashMap<String, String>()
    private val excludedDomains = mutableSetOf<String>()

    suspend fun createEmail(username: String): CyberTempApi.EmailResult = withContext(Dispatchers.IO) {
        try {
            // v2 create — returns { address, token }
            val req = Request.Builder()
                .url("$BASE/v2/inbox/create")
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .header("User-Agent", "Mozilla/5.0")
                .post("{}".toRequestBody(JSON))
                .build()
            val resp = client.newCall(req).execute()
            val body = resp.body?.string() ?: return@withContext emptyResult()
            if (!resp.isSuccessful) return@withContext emptyResult()
            val obj = JSONObject(body)
            val address = obj.optString("address", obj.optString("email", ""))
            val token = obj.optString("token", "")
            if (address.isBlank() || token.isBlank()) return@withContext emptyResult()
            val domain = address.substringAfter("@", "")
            if (domain.lowercase() in excludedDomains) {
                return@withContext emptyResult()
            }
            inboxToken = token
            currentEmail = address
            sessions[address.lowercase()] = token
            CyberTempApi.EmailResult(address, domain, true)
        } catch (_: Exception) {
            emptyResult()
        }
    }

    private fun emptyResult() = CyberTempApi.EmailResult("", "", false)

    fun excludeDomain(domain: String) {
        excludedDomains.add(domain.lowercase())
    }

    suspend fun getVerificationUrl(
        emailAddr: String,
        timeoutMs: Long = 120_000L,
        onLog: ((String) -> Unit)? = null
    ): String? = withContext(Dispatchers.IO) {
        val token = sessions[emailAddr.lowercase()]
            ?: inboxToken?.takeIf { currentEmail.equals(emailAddr, ignoreCase = true) }
        if (token.isNullOrBlank()) {
            onLog?.invoke("TempMail.lol: no inbox token for $emailAddr")
            return@withContext null
        }
        val start = System.currentTimeMillis()
        onLog?.invoke("Polling TempMail.lol for: $emailAddr ...")
        while (System.currentTimeMillis() - start < timeoutMs) {
            try {
                val req = Request.Builder()
                    .url("$BASE/v2/inbox?token=$token")
                    .header("Accept", "application/json")
                    .header("User-Agent", "Mozilla/5.0")
                    .build()
                val resp = client.newCall(req).execute()
                val body = resp.body?.string() ?: ""
                if (resp.isSuccessful && body.isNotBlank()) {
                    val emails = parseEmails(body)
                    for (msg in emails) {
                        val subject = msg.optString("subject", "").lowercase()
                        val from = msg.optString("from", msg.optString("sender", "")).lowercase()
                        val html = msg.optString("html", "") + "\n" +
                            msg.optString("body", "") + "\n" +
                            msg.optString("text", "") + "\n" +
                            msg.optString("content", "")
                        if ("verify" !in subject && "discord" !in from && "discord" !in html.lowercase()) continue
                        onLog?.invoke("Found Discord-related mail: ${msg.optString("subject", "")}")
                        val url = extractVerifyUrl(html, onLog)
                        if (url != null) return@withContext url
                    }
                }
            } catch (_: Exception) { }
            delay(3_000L)
        }
        onLog?.invoke("TempMail.lol: no verification link in time")
        null
    }

    private fun parseEmails(body: String): List<JSONObject> {
        val out = mutableListOf<JSONObject>()
        try {
            val trimmed = body.trim()
            when {
                trimmed.startsWith("{") -> {
                    val root = JSONObject(trimmed)
                    val arr = root.optJSONArray("emails")
                        ?: root.optJSONArray("messages")
                        ?: root.optJSONArray("mail")
                    if (arr != null) {
                        for (i in 0 until arr.length()) out.add(arr.getJSONObject(i))
                    }
                }
                trimmed.startsWith("[") -> {
                    val arr = JSONArray(trimmed)
                    for (i in 0 until arr.length()) out.add(arr.getJSONObject(i))
                }
            }
        } catch (_: Exception) { }
        return out
    }

    private fun extractVerifyUrl(body: String, onLog: ((String) -> Unit)?): String? {
        val regex = Regex("""https?://(?:click\.)?discord\.com/(?:ls/click|verify)[^\s"'<>]+""")
        for (m in regex.findAll(body)) {
            val link = m.value.trimEnd('.', ')', ']', ',', '"', '>', '\\', '\'')
            if (link.length < 30) continue
            if ("discord.com/verify" in link) return link
            if ("click.discord.com" in link || "/ls/click" in link) {
                try {
                    onLog?.invoke("Following redirect...")
                    val req = Request.Builder().url(link).header("User-Agent", "Mozilla/5.0").build()
                    val resp = client.newCall(req).execute()
                    val finalUrl = resp.request.url.toString()
                    if ("discord.com/verify" in finalUrl || "token=" in finalUrl) return finalUrl
                } catch (_: Exception) {}
            }
        }
        return null
    }
}
