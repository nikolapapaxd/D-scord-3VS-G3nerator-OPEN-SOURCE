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
 * CrowMail — temp-mail.io public API (api.internal.temp-mail.io / v3).
 * Provider id stays "crowmail".
 */
class CrowMailApi {
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val jsonMedia = "application/json; charset=utf-8".toMediaType()
    private val base = "https://api.internal.temp-mail.io/api/v3"

    private data class Session(val email: String, val token: String)
    private val sessions = ConcurrentHashMap<String, Session>()

    suspend fun createEmail(localPart: String): CyberTempApi.EmailResult = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder()
                .url("$base/email/new")
                .post("{}".toRequestBody(jsonMedia))
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36")
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext CyberTempApi.EmailResult("", "", false)
                val body = resp.body?.string().orEmpty()
                val obj = JSONObject(body)
                val email = obj.optString("email", "").trim()
                val token = obj.optString("token", "").trim()
                if (email.isBlank() || !email.contains("@") || token.isBlank()) {
                    return@withContext CyberTempApi.EmailResult("", "", false)
                }
                sessions[email.lowercase()] = Session(email, token)
                CyberTempApi.EmailResult(email, email.substringAfter("@"), true)
            }
        } catch (_: Exception) {
            CyberTempApi.EmailResult("", "", false)
        }
    }

    suspend fun getVerificationUrl(
        emailAddr: String,
        timeoutMs: Long = 120_000L,
        onLog: ((String) -> Unit)? = null
    ): String? = withContext(Dispatchers.IO) {
        val session = sessions[emailAddr.lowercase()]
        if (session == null) {
            onLog?.invoke("CrowMail: no session for $emailAddr")
            return@withContext null
        }
        val start = System.currentTimeMillis()
        onLog?.invoke("Polling CrowMail (temp-mail.io) for: $emailAddr")
        val seen = mutableSetOf<String>()
        while (System.currentTimeMillis() - start < timeoutMs) {
            try {
                val req = Request.Builder()
                    .url("$base/email/${session.email}/messages")
                    .get()
                    .header("Accept", "application/json")
                    .header("Authorization", "Bearer ${session.token}")
                    .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36")
                    .build()
                client.newCall(req).execute().use { resp ->
                    if (resp.code == 401 || resp.code == 403) {
                        onLog?.invoke("CrowMail: auth failed (${resp.code})")
                        return@withContext null
                    }
                    if (resp.isSuccessful) {
                        val body = resp.body?.string().orEmpty()
                        val messages = parseMessageList(body)
                        for ((id, preview) in messages) {
                            if (id in seen) continue
                            seen.add(id)
                            val content = fetchMessageBody(session, id) ?: preview
                            val link = extractVerifyUrl(content, onLog)
                            if (!link.isNullOrBlank()) {
                                onLog?.invoke("CrowMail verify link found")
                                return@withContext link
                            }
                        }
                    }
                }
            } catch (_: Exception) {
            }
            delay(4_000L)
        }
        onLog?.invoke("CrowMail: no link within timeout")
        null
    }

    private fun parseMessageList(body: String): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        val trimmed = body.trim()
        try {
            if (trimmed.startsWith("[")) {
                val arr = JSONArray(trimmed)
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val id = o.optString("_id", o.optString("id", o.optString("message_id", "")))
                    val subject = o.optString("subject", "")
                    val preview = o.optString("body_text", o.optString("body_html", o.optString("body", "")))
                    if (id.isNotBlank()) out.add(id to "$subject\n$preview")
                }
            } else if (trimmed.startsWith("{")) {
                val root = JSONObject(trimmed)
                val arr = root.optJSONArray("messages")
                    ?: root.optJSONArray("hydra:member")
                    ?: root.optJSONArray("data")
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        val o = arr.optJSONObject(i) ?: continue
                        val id = o.optString("_id", o.optString("id", ""))
                        val preview = o.optString("body_text", o.optString("subject", ""))
                        if (id.isNotBlank()) out.add(id to preview)
                    }
                }
            }
        } catch (_: Exception) {
        }
        return out
    }

    private fun fetchMessageBody(session: Session, id: String): String? {
        return try {
            val req = Request.Builder()
                .url("$base/email/${session.email}/messages/$id")
                .get()
                .header("Accept", "application/json")
                .header("Authorization", "Bearer ${session.token}")
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36")
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val obj = JSONObject(resp.body?.string().orEmpty())
                val html = obj.optString("body_html", obj.optString("html", ""))
                val text = obj.optString("body_text", obj.optString("text", obj.optString("body", "")))
                unescapeHtml("$html\n$text")
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun extractVerifyUrl(body: String, onLog: ((String) -> Unit)? = null): String? {
        val regex = Regex(
            "https?://(?:click\\.)?discord\\.com/(?:ls/click|verify)[^\\s\"'<>]+",
            RegexOption.IGNORE_CASE
        )
        val links = regex.findAll(body)
            .map { it.value.trimEnd('.', ')', ']', ',', '"', '>', '\\', '\'') }
            .filter { it.length > 30 }
            .toList()
        for (link in links) {
            if ("click.discord.com" in link || "/ls/click" in link) {
                try {
                    onLog?.invoke("Following redirect: ${link.take(50)}...")
                    val req = Request.Builder().url(link).header("User-Agent", "Mozilla/5.0").build()
                    client.newCall(req).execute().use { resp ->
                        val finalUrl = resp.request.url.toString()
                        if ("discord.com/verify" in finalUrl || "token=" in finalUrl) return finalUrl
                        val respBody = resp.body?.string().orEmpty()
                        val nested = Regex("https://discord\\.com/verify[^\\s\"'<>]*").find(respBody)?.value
                        if (nested != null) return nested
                    }
                } catch (_: Exception) {
                }
            } else if ("discord.com/verify" in link) {
                return link.replace("&amp;", "&")
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

    fun excludeDomain(_domain: String) { /* reserved */ }
}
