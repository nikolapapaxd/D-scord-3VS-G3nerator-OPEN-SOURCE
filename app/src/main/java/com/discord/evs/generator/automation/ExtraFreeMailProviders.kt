package com.discord.evs.generator.automation

import com.discord.evs.generator.features.FeatureFlags
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
 * Additional free / no-key temp-mail backends.
 * Active when [FeatureFlags.EXTRA_FREE_MAIL_PROVIDERS] is true.
 */
object ExtraFreeMailProviders {

    private val JSON = "application/json; charset=utf-8".toMediaType()

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    /** address -> session/token for inbox polling */
    private val sessions = ConcurrentHashMap<String, String>()

    fun isEnabled(): Boolean = FeatureFlags.EXTRA_FREE_MAIL_PROVIDERS

    fun providerIds(): List<String> =
        if (isEnabled()) listOf("dropmail", "maildrop", "tempmail_org") else emptyList()

    suspend fun create(provider: String, localPart: String): CyberTempApi.EmailResult =
        withContext(Dispatchers.IO) {
            if (!isEnabled()) return@withContext empty()
            when (provider.lowercase()) {
                "dropmail" -> createDropMail()
                "maildrop" -> createMailDrop(localPart)
                "tempmail_org", "tempmail.org" -> createTempMailOrg()
                else -> empty()
            }
        }

    suspend fun pollVerificationLink(
        provider: String,
        address: String,
        token: String,
        timeoutMs: Long,
        onLog: ((String) -> Unit)?
    ): String? = withContext(Dispatchers.IO) {
        if (!isEnabled()) return@withContext null
        val deadline = System.currentTimeMillis() + timeoutMs.coerceAtLeast(5_000L)
        val sid = token.ifBlank { sessions[address.lowercase()].orEmpty() }
        onLog?.invoke("Extra mail poll ($provider) for $address…")
        while (System.currentTimeMillis() < deadline) {
            val link = when (provider.lowercase()) {
                "dropmail" -> pollDropMail(address, sid, onLog)
                "maildrop" -> pollMailDrop(address, onLog)
                "tempmail_org", "tempmail.org" -> pollTempMailOrg(address, sid, onLog)
                else -> null
            }
            if (!link.isNullOrBlank()) return@withContext link
            delay(4_000L)
        }
        onLog?.invoke("Extra mail poll timed out ($provider)")
        null
    }

    private fun empty() = CyberTempApi.EmailResult("", "", false)

    private fun createDropMail(): CyberTempApi.EmailResult {
        return try {
            val query =
                """{"query":"mutation { introduceSession { id expiresAt addresses { address } } }"}"""
            val endpoints = listOf(
                "https://dropmail.me/api/graphql/web-test",
                "https://dropmail.me/api/graphql/web"
            )
            for (ep in endpoints) {
                try {
                    val req = Request.Builder()
                        .url(ep)
                        .header("Content-Type", "application/json")
                        .header("Accept", "application/json")
                        .header("User-Agent", "Mozilla/5.0")
                        .post(query.toRequestBody(JSON))
                        .build()
                    client.newCall(req).execute().use { resp ->
                        if (!resp.isSuccessful) return@use
                        val text = resp.body?.string().orEmpty()
                        val data = JSONObject(text).optJSONObject("data")
                            ?.optJSONObject("introduceSession")
                        val id = data?.optString("id").orEmpty()
                        val arr = data?.optJSONArray("addresses")
                        val addr = arr?.optJSONObject(0)?.optString("address").orEmpty()
                        if (addr.isBlank()) return@use
                        if (id.isNotBlank()) sessions[addr.lowercase()] = id
                        return CyberTempApi.EmailResult(addr, addr.substringAfter('@', ""), true)
                    }
                } catch (_: Exception) {}
            }
            empty()
        } catch (_: Exception) {
            empty()
        }
    }

    private fun pollDropMail(address: String, sessionId: String, onLog: ((String) -> Unit)?): String? {
        val sid = sessionId.ifBlank { sessions[address.lowercase()].orEmpty() }
        if (sid.isBlank()) {
            onLog?.invoke("Dropmail: no session id")
            return null
        }
        return try {
            val query =
                """{"query":"query { session(id:\"$sid\") { mails { text html headerSubject } } }"}"""
            val req = Request.Builder()
                .url("https://dropmail.me/api/graphql/web-test")
                .header("Content-Type", "application/json")
                .post(query.toRequestBody(JSON))
                .build()
            client.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                val mails = JSONObject(text).optJSONObject("data")
                    ?.optJSONObject("session")
                    ?.optJSONArray("mails") ?: return null
                for (i in 0 until mails.length()) {
                    val m = mails.optJSONObject(i) ?: continue
                    val body = (m.optString("text") + "\n" + m.optString("html") + "\n" + m.optString("headerSubject"))
                    extractDiscordLink(body)?.let { return it }
                }
                null
            }
        } catch (e: Exception) {
            onLog?.invoke("Dropmail poll: ${e.message}")
            null
        }
    }

    private fun createMailDrop(localPart: String): CyberTempApi.EmailResult {
        val user = localPart.ifBlank { "u${(100000..999999).random()}" }
            .lowercase()
            .filter { it.isLetterOrDigit() }
            .take(20)
            .ifBlank { "user${(1000..9999).random()}" }
        val domain = "maildrop.cc"
        val addr = "$user@$domain"
        sessions[addr.lowercase()] = user
        return CyberTempApi.EmailResult(addr, domain, true)
    }

    private fun pollMailDrop(address: String, onLog: ((String) -> Unit)?): String? {
        val mailbox = address.substringBefore("@").lowercase()
        return try {
            // Public maildrop GraphQL inbox
            val query =
                """{"query":"query { inbox(mailbox:\"$mailbox\") { id headerSubject html text } }"}"""
            val req = Request.Builder()
                .url("https://api.maildrop.cc/graphql")
                .header("Content-Type", "application/json")
                .post(query.toRequestBody(JSON))
                .build()
            client.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                val inbox = JSONObject(text).optJSONObject("data")?.optJSONArray("inbox")
                    ?: return null
                for (i in 0 until inbox.length()) {
                    val m = inbox.optJSONObject(i) ?: continue
                    val body = m.optString("text") + "\n" + m.optString("html") + "\n" + m.optString("headerSubject")
                    extractDiscordLink(body)?.let { return it }
                }
                null
            }
        } catch (e: Exception) {
            onLog?.invoke("MailDrop poll: ${e.message}")
            null
        }
    }

    private fun createTempMailOrg(): CyberTempApi.EmailResult {
        return try {
            val req = Request.Builder()
                .url("https://api.internal.temp-mail.io/api/v3/email/new")
                .header("Content-Type", "application/json")
                .post("{}".toRequestBody(JSON))
                .build()
            client.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                val json = JSONObject(text)
                val email = json.optString("email").ifBlank { json.optString("address") }
                val token = json.optString("token").ifBlank { json.optString("id") }
                if (email.isBlank()) return empty()
                if (token.isNotBlank()) sessions[email.lowercase()] = token
                CyberTempApi.EmailResult(email, email.substringAfter('@', ""), true)
            }
        } catch (_: Exception) {
            empty()
        }
    }

    private fun pollTempMailOrg(address: String, token: String, onLog: ((String) -> Unit)?): String? {
        val t = token.ifBlank { sessions[address.lowercase()].orEmpty() }
        return try {
            // List messages for the address
            val url = if (t.isNotBlank()) {
                "https://api.internal.temp-mail.io/api/v3/email/$address/messages"
            } else {
                "https://api.internal.temp-mail.io/api/v3/email/$address/messages"
            }
            val req = Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .apply { if (t.isNotBlank()) header("Authorization", "Bearer $t") }
                .get()
                .build()
            client.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                val arr = try {
                    JSONArray(text)
                } catch (_: Exception) {
                    JSONObject(text).optJSONArray("messages") ?: return null
                }
                for (i in 0 until arr.length()) {
                    val m = arr.optJSONObject(i) ?: continue
                    val body = buildString {
                        append(m.optString("body_text"))
                        append('\n')
                        append(m.optString("body_html"))
                        append('\n')
                        append(m.optString("subject"))
                        append('\n')
                        append(m.optString("body"))
                    }
                    extractDiscordLink(body)?.let { return it }
                    // Fetch full message if only id present
                    val id = m.optString("id").ifBlank { m.optString("_id") }
                    if (id.isNotBlank()) {
                        val full = fetchTempMailMessage(address, id, t)
                        extractDiscordLink(full)?.let { return it }
                    }
                }
                null
            }
        } catch (e: Exception) {
            onLog?.invoke("temp-mail.org poll: ${e.message}")
            null
        }
    }

    private fun fetchTempMailMessage(address: String, id: String, token: String): String {
        return try {
            val req = Request.Builder()
                .url("https://api.internal.temp-mail.io/api/v3/email/$address/messages/$id")
                .header("Accept", "application/json")
                .apply { if (token.isNotBlank()) header("Authorization", "Bearer $token") }
                .get()
                .build()
            client.newCall(req).execute().use { it.body?.string().orEmpty() }
        } catch (_: Exception) {
            ""
        }
    }

    private fun extractDiscordLink(body: String): String? {
        if (body.isBlank()) return null
        val patterns = listOf(
            Regex("""https?://(?:click\.)?discord\.com/verify[^\s"'<>]+""", RegexOption.IGNORE_CASE),
            Regex("""https?://discord\.com/[^\s"'<>]*verify[^\s"'<>]*""", RegexOption.IGNORE_CASE),
            Regex("""https?://discordapp\.com/[^\s"'<>]*verify[^\s"'<>]*""", RegexOption.IGNORE_CASE),
            Regex("""https?://[^\s"'<>]*discord[^\s"'<>]*email[^\s"'<>]*""", RegexOption.IGNORE_CASE),
        )
        for (p in patterns) {
            val m = p.find(body) ?: continue
            var url = m.value.trimEnd('.', ',', ')', ']', '"', '\'')
            url = url.replace("&amp;", "&")
            if (url.contains("discord", ignoreCase = true)) return url
        }
        return null
    }
}
