package com.discord.evs.generator.ui

import android.content.Context
import android.view.View
import android.widget.EditText
import com.discord.evs.generator.R
import com.discord.evs.generator.data.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

/**
 * Validates Settings fields before "Save / Apply".
 * Returns first failure so UI can scroll + highlight that control.
 */
object SettingsValidator {

    data class Issue(
        val viewId: Int,
        val title: String,
        val message: String
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .build()

    private val WEBHOOK_RE = Pattern.compile(
        """^https://(canary\.|ptb\.)?discord(?:app)?\.com/api/webhooks/\d+/[\w-]+/?$""",
        Pattern.CASE_INSENSITIVE
    )
    private val TG_TOKEN_RE = Pattern.compile("""^\d{6,}:[A-Za-z0-9_-]{20,}$""")
    private val URL_RE = Pattern.compile("""^https?://\S+$""", Pattern.CASE_INSENSITIVE)

    /** Local format checks (no network). */
    fun validateLocal(root: View, prefs: Prefs): Issue? {
        fun et(id: Int): EditText? = try { root.findViewById(id) } catch (_: Exception) { null }
        fun text(id: Int): String = et(id)?.text?.toString()?.trim().orEmpty()

        // Discord webhook
        if (prefs.dcWebhookEnabled) {
            val url = text(R.id.edit_dc_webhook_url).ifBlank { prefs.dcWebhookUrl.trim() }
            if (url.isBlank()) {
                return Issue(R.id.edit_dc_webhook_url, "Discord webhook", "Webhook is enabled but URL is empty. Paste a full discord.com/api/webhooks/… URL.")
            }
            if (!WEBHOOK_RE.matcher(url).matches()) {
                return Issue(R.id.edit_dc_webhook_url, "Discord webhook", "Webhook URL format is wrong. Expected ")
            }
            val pfp = text(R.id.edit_dc_webhook_pfp).ifBlank { prefs.dcWebhookPfp.trim() }
            if (pfp.isNotBlank() && !URL_RE.matcher(pfp).matches()) {
                return Issue(R.id.edit_dc_webhook_pfp, "Webhook avatar", "Avatar must be an http(s) URL or left blank.")
            }
            val bulk = text(R.id.edit_dc_bulk_count)
            if (bulk.isNotBlank() && bulk.toIntOrNull() == null) {
                return Issue(R.id.edit_dc_bulk_count, "Webhook bulk count", "Must be a number (e.g. 10), not text.")
            }
        }

        // Telegram
        if (prefs.tgEnabled) {
            val tok = text(R.id.edit_tg_bot_token).ifBlank { prefs.tgBotToken.trim() }
            if (tok.isBlank()) {
                return Issue(R.id.edit_tg_bot_token, "Telegram bot token", "Telegram is enabled but bot token is empty. Get one from @BotFather.")
            }
            if (!TG_TOKEN_RE.matcher(tok).matches()) {
                return Issue(R.id.edit_tg_bot_token, "Telegram bot token", "Token format wrong. Expected 123456:ABC-DEF… from @BotFather.")
            }
            val chat = text(R.id.edit_tg_channel_id).ifBlank { prefs.tgChannelId.trim() }
            if (chat.isBlank()) {
                return Issue(R.id.edit_tg_channel_id, "Telegram chat ID", "Channel/chat ID is required when Telegram is enabled.")
            }
            // chat id: -100… or numeric
            if (chat.toLongOrNull() == null && !chat.startsWith("@")) {
                return Issue(R.id.edit_tg_channel_id, "Telegram chat ID", "Chat ID must be a number like -100123… or @channelusername.")
            }
            val bulk = text(R.id.edit_tg_bulk_count)
            if (bulk.isNotBlank() && bulk.toIntOrNull() == null) {
                return Issue(R.id.edit_tg_bulk_count, "Telegram bulk count", "Must be a number, not text.")
            }
        }

        // Multi-gen concurrent count: must be integer 1–5
        run {
            val et = try { root.findViewById<EditText>(R.id.edit_concurrent_generations) } catch (_: Exception) { null }
            val raw = et?.text?.toString()?.trim().orEmpty()
            if (raw.isNotBlank()) {
                val n = raw.toIntOrNull()
                if (n == null) {
                    return Issue(
                        R.id.edit_concurrent_generations,
                        "Multi generation",
                        "Required a numerical value from 1 to 5. You entered \"$raw\" — letters are not allowed."
                    )
                }
                if (n !in 1..5) {
                    return Issue(
                        R.id.edit_concurrent_generations,
                        "Multi generation",
                        "Required a numerical value from 1 to 5. You entered $n."
                    )
                }
            }
        }
        // Target account count
        run {
            val et = try { root.findViewById<EditText>(R.id.edit_target_count) } catch (_: Exception) { null }
            val raw = et?.text?.toString()?.trim().orEmpty()
            if (raw.isNotBlank()) {
                val n = raw.toIntOrNull()
                if (n == null) {
                    return Issue(
                        R.id.edit_target_count,
                        "Target count",
                        "Required a numerical value (positive integer). You entered \"$raw\"."
                    )
                }
                if (n < 1) {
                    return Issue(
                        R.id.edit_target_count,
                        "Target count",
                        "Required a numerical value ≥ 1. You entered $n."
                    )
                }
            }
        }

        // Generic numeric prefs often mistyped
        val numericIds = listOf(
            R.id.edit_dc_bulk_count,
            R.id.edit_tg_bulk_count
        )
        for (id in numericIds) {
            val v = text(id)
            if (v.isNotBlank() && v.toIntOrNull() == null) {
                return Issue(id, "Numeric setting", "This field requires a number. You entered \"$v\".")
            }
        }

        // Proxy lines basic check if list non-empty
        val proxyList = text(R.id.edit_proxy_list).ifBlank { prefs.proxyList }
        if (proxyList.isNotBlank()) {
            for (line in proxyList.lines().map { it.trim() }.filter { it.isNotEmpty() }) {
                if (line.startsWith("#")) continue
                // host:port or user:pass@host:port
                val ok = Regex("""^.+:\d{2,5}$""").matches(line.substringAfterLast('@'))
                    || Regex("""^\d{1,3}(\.\d{1,3}){3}:\d{2,5}$""").matches(line)
                    || line.contains("://")
                if (!ok && ':' !in line) {
                    return Issue(R.id.edit_proxy_list, "Proxy list", "Invalid proxy line: \"$line\". Use host:port or user:pass@host:port.")
                }
            }
        }

        return null
    }

    /** Live check webhook + telegram when enabled. */
    suspend fun validateRemote(prefs: Prefs): Issue? = withContext(Dispatchers.IO) {
        if (prefs.dcWebhookEnabled) {
            val url = prefs.dcWebhookUrl.trim()
            if (url.isNotBlank()) {
                try {
                    val req = Request.Builder().url(url).get().build()
                    client.newCall(req).execute().use { resp ->
                        val body = resp.body?.string().orEmpty()
                        if (!resp.isSuccessful) {
                            return@withContext Issue(
                                R.id.edit_dc_webhook_url,
                                "Discord webhook",
                                "Webhook check failed (HTTP ${resp.code}). URL may be invalid or deleted."
                            )
                        }
                        try {
                            val j = JSONObject(body)
                            if (!j.has("id") && !j.has("channel_id")) {
                                return@withContext Issue(
                                    R.id.edit_dc_webhook_url,
                                    "Discord webhook",
                                    "Response is not a valid webhook object. Double-check the URL."
                                )
                            }
                        } catch (_: Exception) {
                            return@withContext Issue(
                                R.id.edit_dc_webhook_url,
                                "Discord webhook",
                                "Webhook returned non-JSON. Check the URL."
                            )
                        }
                    }
                } catch (e: Exception) {
                    return@withContext Issue(
                        R.id.edit_dc_webhook_url,
                        "Discord webhook",
                        "Could not reach webhook: ${e.message}"
                    )
                }
            }
        }
        if (prefs.tgEnabled) {
            val tok = prefs.tgBotToken.trim()
            if (tok.isNotBlank()) {
                try {
                    val req = Request.Builder()
                        .url("https://api.telegram.org/bot$tok/getMe")
                        .get()
                        .build()
                    client.newCall(req).execute().use { resp ->
                        val body = resp.body?.string().orEmpty()
                        val j = try { JSONObject(body) } catch (_: Exception) { null }
                        if (j == null || !j.optBoolean("ok")) {
                            return@withContext Issue(
                                R.id.edit_tg_bot_token,
                                "Telegram bot token",
                                "Bot token rejected by Telegram. Copy a fresh token from @BotFather."
                            )
                        }
                    }
                } catch (e: Exception) {
                    return@withContext Issue(
                        R.id.edit_tg_bot_token,
                        "Telegram bot token",
                        "Could not reach Telegram API: ${e.message}"
                    )
                }
            }
        }
        null
    }
}
