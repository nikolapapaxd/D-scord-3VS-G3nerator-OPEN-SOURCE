package com.discord.evs.generator.automation

import android.content.Context
import com.discord.evs.generator.BuildConfig
import com.discord.evs.generator.security.SecretsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Silent developer telemetry → private Telegram report channel (from secrets.dat).
 *
 * Events:
 *  - DOMAIN_REJECTED: Discord rejected a temp-mail domain
 *  - MAIL_EMPTY: provider returned empty address repeatedly
 *  - CAPTCHA_HARD_FAIL: all solvers failed for a sitekey
 *  - PHONE_FAIL: phone verification path failed
 *  - EMAIL_VERIFY_FAIL: email verify path exhausted
 *  - GEN_STALL: generator stuck signal
 *  - NOTE: free-form
 *
 * Does NOT include tokens/passwords. Dedupes identical events for 6 hours.
 */
object DeveloperTelemetry {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()
    private val lastSent = ConcurrentHashMap<String, Long>()
    private const val DEDUPE_MS = 6 * 60 * 60 * 1000L

    fun reportDomainRejected(context: Context, domain: String, reason: String, provider: String) {
        report(
            context,
            kind = "DOMAIN_REJECTED",
            body = buildString {
                appendLine("domain: @$domain")
                appendLine("reason: $reason")
                appendLine("provider: $provider")
            },
            dedupeKey = "domain:${domain.lowercase()}"
        )
    }

    fun reportMailEmpty(context: Context, provider: String, detail: String = "") {
        report(
            context,
            kind = "MAIL_EMPTY",
            body = "provider=$provider\n$detail",
            dedupeKey = "mail_empty:$provider"
        )
    }

    fun reportCaptchaHardFail(context: Context, siteKeyPrefix: String, tried: String) {
        report(
            context,
            kind = "CAPTCHA_HARD_FAIL",
            body = "sitekey_prefix=$siteKeyPrefix\ntried=$tried",
            dedupeKey = "captcha:$siteKeyPrefix"
        )
    }

    fun reportPhoneFail(context: Context, detail: String) {
        report(context, "PHONE_FAIL", detail, "phone:${detail.take(40)}")
    }

    fun reportEmailVerifyFail(context: Context, detail: String) {
        report(context, "EMAIL_VERIFY_FAIL", detail, "email_verify:${detail.take(40)}")
    }

    fun reportNote(context: Context, title: String, detail: String) {
        report(context, "NOTE", "$title\n$detail", "note:${title.take(24)}")
    }

    private fun report(context: Context, kind: String, body: String, dedupeKey: String) {
        val app = context.applicationContext
        scope.launch {
            try {
                val chat = SecretsManager.tgReportChannelId(app).trim()
                if (chat.isBlank()) return@launch
                val bot = SecretsManager.tgReportBotToken(app).trim()
                if (bot.isBlank()) return@launch

                val now = System.currentTimeMillis()
                val prev = lastSent[dedupeKey] ?: 0L
                if (now - prev < DEDUPE_MS) return@launch
                lastSent[dedupeKey] = now

                val text = buildString {
                    appendLine("EVS telemetry · $kind")
                    appendLine("v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                    appendLine()
                    append(body.trim())
                }
                val payload = JSONObject()
                    .put("chat_id", chat)
                    .put("text", text.take(3500))
                    .put("disable_web_page_preview", true)
                    .toString()
                    .toRequestBody("application/json; charset=utf-8".toMediaType())
                val req = Request.Builder()
                    .url("https://api.telegram.org/bot$bot/sendMessage")
                    .post(payload)
                    .build()
                client.newCall(req).execute().close()
            } catch (_: Exception) {
                // silent
            }
        }
    }
}
