package com.discord.evs.generator.automation

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.URL
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLSocketFactory

/**
 * Self-hosted mailcow provider.
 * Creates mailboxes via API, reads Discord verify mail via IMAP (993).
 */
class MailcowApi(
    private val baseUrl: String,
    private val apiKey: String,
    private val domain: String
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(25, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .build()

    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    private fun root(): String = baseUrl.trim().trimEnd('/')

    private fun isApiSuccess(code: Int, body: String): Boolean {
        if (code !in 200..299) return false
        val lower = body.lowercase()
        if (lower.contains("\"type\":\"success\"") || lower.contains("mailbox_added") || lower.contains("\"success\"")) {
            return true
        }
        if (body.isBlank()) return true
        try {
            val arr = JSONArray(body)
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                if (o.optString("type").equals("success", true)) return true
            }
        } catch (_: Exception) {}
        return false
    }

    suspend fun createEmail(localPart: String): CyberTempApi.EmailResult = withContext(Dispatchers.IO) {
        if (root().isBlank() || apiKey.isBlank() || domain.isBlank()) {
            Log.w(TAG, "mailcow missing config url='${root()}' keyBlank=${apiKey.isBlank()} domain='$domain'")
            return@withContext CyberTempApi.EmailResult("", "", false)
        }
        val user = localPart.lowercase()
            .filter { it.isLetterOrDigit() || it == '.' || it == '_' }
            .take(32)
            .ifBlank { "u" + UUID.randomUUID().toString().take(8) }
        val password = UUID.randomUUID().toString().replace("-", "") + "Aa1!"
        val cleanDomain = domain.trim().lowercase()
        val address = "$user@$cleanDomain"

        val bodyJson = JSONObject()
            .put("local_part", user)
            .put("domain", cleanDomain)
            .put("name", user)
            .put("password", password)
            .put("password2", password)
            .put("active", "1")
            .put("force_pw_update", "0")
            .put("quota", "1024")
            .toString()

        try {
            val req = Request.Builder()
                .url(root() + "/api/v1/add/mailbox")
                .header("X-API-Key", apiKey)
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .post(bodyJson.toRequestBody(jsonMedia))
                .build()
            val resp = client.newCall(req).execute()
            val code = resp.code
            val respBody = resp.body?.string() ?: ""
            Log.i(TAG, "add/mailbox HTTP $code body=${respBody.take(300)}")
            if (isApiSuccess(code, respBody)) {
                mailboxPasswords[address.lowercase()] = password
                Log.i(TAG, "mailbox ready $address (IMAP password stored for poll)")
                return@withContext CyberTempApi.EmailResult(address, cleanDomain, true)
            }
            Log.w(TAG, "add/mailbox failed: HTTP $code $respBody")
        } catch (e: Exception) {
            Log.e(TAG, "add/mailbox exception: ${e.message}")
        }

        CyberTempApi.EmailResult("", "", false)
    }

    fun excludeDomain(@Suppress("UNUSED_PARAMETER") ignored: String) {}

    suspend fun getVerificationUrl(
        emailAddr: String,
        timeoutMs: Long = 180_000L,
        onLog: ((String) -> Unit)? = null
    ): String? = withContext(Dispatchers.IO) {
        val email = emailAddr.trim().lowercase()
        val password = mailboxPasswords[email]
        if (password.isNullOrBlank()) {
            onLog?.invoke("mailcow: no IMAP password for $email — mailbox create may have failed")
            // Still try log scrape as last resort
            return@withContext pollLogsForLink(timeoutMs, onLog)
        }

        val host = try {
            URL(if (root().startsWith("http")) root() else "https://${root()}").host
        } catch (_: Exception) {
            root().removePrefix("https://").removePrefix("http://").substringBefore('/')
        }

        onLog?.invoke("mailcow: IMAP poll $email @ $host:993 …")
        val start = System.currentTimeMillis()
        var attempt = 0
        while (System.currentTimeMillis() - start < timeoutMs) {
            attempt++
            try {
                val link = fetchDiscordLinkViaImap(host, email, password)
                if (link != null) {
                    onLog?.invoke("mailcow: found Discord verify link (IMAP)")
                    return@withContext link
                }
            } catch (e: Exception) {
                if (attempt <= 2 || attempt % 5 == 0) {
                    onLog?.invoke("mailcow: IMAP try $attempt — ${e.message ?: "no mail yet"}")
                }
                Log.w(TAG, "IMAP poll: ${e.message}")
            }
            // Also scrape logs occasionally
            if (attempt % 3 == 0) {
                val fromLogs = pollLogsOnce()
                if (fromLogs != null) {
                    onLog?.invoke("mailcow: found Discord verify link (logs)")
                    return@withContext fromLogs
                }
            }
            delay(4_000)
        }
        onLog?.invoke("mailcow: no verification link found — open SOGo for $email")
        null
    }

    private fun fetchDiscordLinkViaImap(host: String, user: String, password: String): String? {
        val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
        val socket = factory.createSocket()
        socket.soTimeout = 20_000
        socket.connect(InetSocketAddress(host, 993), 15_000)
        socket.use { sock ->
            val reader = BufferedReader(InputStreamReader(sock.getInputStream(), Charsets.UTF_8))
            val writer = BufferedWriter(OutputStreamWriter(sock.getOutputStream(), Charsets.UTF_8))

            fun readLine(): String? = reader.readLine()
            fun send(cmd: String) {
                writer.write(cmd)
                writer.write("\r\n")
                writer.flush()
            }

            // greeting
            val greet = readLine() ?: throw IllegalStateException("no IMAP greeting")
            if (!greet.startsWith("* OK", ignoreCase = true) && !greet.uppercase().contains("OK")) {
                throw IllegalStateException("bad greeting: $greet")
            }

            send("""A001 LOGIN "$user" "$password"""")
            val loginResp = readUntilTagged("A001", reader)
            if (!loginResp.uppercase().contains("A001 OK")) {
                throw IllegalStateException("LOGIN failed: ${loginResp.take(120)}")
            }

            send("A002 SELECT INBOX")
            val selectResp = readUntilTagged("A002", reader)
            if (!selectResp.uppercase().contains("A002 OK")) {
                throw IllegalStateException("SELECT failed")
            }

            // Search recent mail from Discord
            send("A003 SEARCH OR FROM discord SUBJECT verify")
            val searchResp = readUntilTagged("A003", reader)
            val ids = Regex("""\* SEARCH\s+([\d\s]*)""", RegexOption.IGNORE_CASE)
                .find(searchResp)
                ?.groupValues?.get(1)
                ?.trim()
                ?.split(Regex("\\s+"))
                ?.mapNotNull { it.toIntOrNull() }
                ?.filter { it > 0 }
                .orEmpty()

            val candidates = if (ids.isNotEmpty()) ids.takeLast(5) else {
                // Fallback: fetch last few messages
                val exists = Regex("""\* (\d+) EXISTS""", RegexOption.IGNORE_CASE).find(selectResp)
                val n = exists?.groupValues?.get(1)?.toIntOrNull() ?: 0
                if (n <= 0) emptyList() else ((maxOf(1, n - 4))..n).toList()
            }

            for (id in candidates.reversed()) {
                send("A004 FETCH $id (BODY.PEEK[TEXT] BODY.PEEK[HEADER.FIELDS (SUBJECT FROM)])")
                val fetch = readUntilTagged("A004", reader)
                val link = extractDiscordLink(fetch)
                if (link != null) {
                    send("A005 LOGOUT")
                    try { readUntilTagged("A005", reader) } catch (_: Exception) {}
                    return link
                }
            }

            send("A005 LOGOUT")
            try { readUntilTagged("A005", reader) } catch (_: Exception) {}
        }
        return null
    }

    private fun readUntilTagged(tag: String, reader: BufferedReader): String {
        val sb = StringBuilder()
        while (true) {
            val line = reader.readLine() ?: break
            sb.append(line).append('\n')
            if (line.startsWith(tag)) break
            // Literal size {123}
            val lit = Regex("""\{(\d+)\}$""").find(line)
            if (lit != null) {
                val size = lit.groupValues[1].toInt()
                val buf = CharArray(size)
                var read = 0
                while (read < size) {
                    val n = reader.read(buf, read, size - read)
                    if (n < 0) break
                    read += n
                }
                sb.append(buf, 0, read)
                sb.append(reader.readLine() ?: "").append('\n')
            }
        }
        return sb.toString()
    }

    private fun extractDiscordLink(text: String): String? {
        // Normalize quoted-printable / HTML entities / soft breaks from IMAP
        var raw = text
            .replace("=\r\n", "")
            .replace("=\n", "")
            .replace("=3D", "=")
            .replace("&amp;", "&")
            .replace("&#61;", "=")
            .replace("&quot;", "\"")
            .replace("&#x3D;", "=")

        val patterns = listOf(
            Regex("""href=["'](https?://[^"']*discord[^"']+)["']""", RegexOption.IGNORE_CASE),
            Regex("""https?://(?:click\.)?discord(?:app)?\.com/ls/click\?[^\s"'<>\\)]+""", RegexOption.IGNORE_CASE),
            Regex("""https?://(?:email\.)?discord(?:app)?\.com/[^\s"'<>\\)]*(?:verify|email|confirm)[^\s"'<>\\)]*""", RegexOption.IGNORE_CASE),
            Regex("""https?://(?:www\.)?discord(?:app)?\.com/api/v\d+/auth/verify[^\s"'<>\\)]*""", RegexOption.IGNORE_CASE),
            Regex("""https?://(?:www\.)?discord(?:app)?\.com/verify[^\s"'<>\\)]*""", RegexOption.IGNORE_CASE),
            Regex("""https?://[^\s"'<>\\)]*discord[^\s"'<>\\)]*(?:token|verify|email)[^\s"'<>\\)]*""", RegexOption.IGNORE_CASE),
        )
        for (p in patterns) {
            val m = p.find(raw) ?: continue
            var url = if (m.groups.size > 1 && m.groupValues.getOrNull(1)?.startsWith("http") == true) {
                m.groupValues[1]
            } else m.value
            url = url.trimEnd('.', ')', ']', '"', '\'', '>', '\\', ',', ';')
            url = url.replace("=\r\n", "").replace("=\n", "").replace("=3D", "=")
            while (url.isNotEmpty() && url.last() in ".,);]>\"'") url = url.dropLast(1)
            if (url.contains("discord", ignoreCase = true) && url.startsWith("http")) {
                return url
            }
        }
        return null
    }

    private fun pollLogsOnce(): String? {
        if (root().isBlank() || apiKey.isBlank()) return null
        val endpoints = listOf(
            root() + "/api/v1/get/logs/dovecot-mailinfo",
            root() + "/api/v1/get/logs/mailcow",
            root() + "/api/v1/get/quarantine/all"
        )
        for (ep in endpoints) {
            try {
                val req = Request.Builder()
                    .url(ep)
                    .header("X-API-Key", apiKey)
                    .header("Accept", "application/json")
                    .get()
                    .build()
                val resp = client.newCall(req).execute()
                val body = resp.body?.string() ?: ""
                if (!resp.isSuccessful) continue
                extractDiscordLink(body)?.let { return it }
            } catch (_: Exception) {}
        }
        return null
    }

    private suspend fun pollLogsForLink(timeoutMs: Long, onLog: ((String) -> Unit)?): String? {
        onLog?.invoke("mailcow: falling back to log/quarantine scrape…")
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < timeoutMs) {
            pollLogsOnce()?.let {
                onLog?.invoke("mailcow: found Discord verify link (logs)")
                return it
            }
            delay(5_000)
        }
        return null
    }

    companion object {
        private const val TAG = "MailcowApi"
        /** email(lowercase) -> mailbox password from createEmail */
        private val mailboxPasswords = ConcurrentHashMap<String, String>()
    }
}
