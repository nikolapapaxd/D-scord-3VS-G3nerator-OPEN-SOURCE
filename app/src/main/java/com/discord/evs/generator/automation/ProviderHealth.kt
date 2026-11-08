package com.discord.evs.generator.automation

import java.util.concurrent.ConcurrentHashMap

/**
 * Tracks mail / captcha provider success+fail so dead providers can be auto-skipped.
 */
object ProviderHealth {
    data class Stats(
        var successes: Int = 0,
        var failures: Int = 0,
        var lastSuccessMs: Long = 0L,
        var lastFailMs: Long = 0L,
        var lastError: String = "",
        var cooldownUntilMs: Long = 0L
    ) {
        val total: Int get() = successes + failures
        val successRate: Double get() = if (total == 0) 1.0 else successes.toDouble() / total
    }

    private val mail = ConcurrentHashMap<String, Stats>()
    private val captcha = ConcurrentHashMap<String, Stats>()

    private const val COOLDOWN_MS = 15 * 60 * 1000L
    private const val FAIL_STREAK_SKIP = 3

    fun mailStats(): Map<String, Stats> = mail.toMap()
    fun captchaStats(): Map<String, Stats> = captcha.toMap()

    fun markMailOk(provider: String) {
        val s = mail.getOrPut(provider.lowercase()) { Stats() }
        synchronized(s) {
            s.successes++
            s.lastSuccessMs = System.currentTimeMillis()
            s.cooldownUntilMs = 0L
            s.lastError = ""
        }
    }

    fun markMailFail(provider: String, error: String = "") {
        val key = provider.lowercase()
        val s = mail.getOrPut(key) { Stats() }
        synchronized(s) {
            s.failures++
            s.lastFailMs = System.currentTimeMillis()
            s.lastError = error.take(120)
            // consecutive-ish: if recent fails dominate, cool down
            if (s.failures >= FAIL_STREAK_SKIP && s.successes == 0) {
                s.cooldownUntilMs = System.currentTimeMillis() + COOLDOWN_MS
            } else if (s.failures > s.successes + FAIL_STREAK_SKIP) {
                s.cooldownUntilMs = System.currentTimeMillis() + COOLDOWN_MS
            }
        }
    }

    fun markCaptchaOk(provider: String) {
        val s = captcha.getOrPut(provider.lowercase()) { Stats() }
        synchronized(s) {
            s.successes++
            s.lastSuccessMs = System.currentTimeMillis()
            s.cooldownUntilMs = 0L
            s.lastError = ""
        }
    }

    fun markCaptchaFail(provider: String, error: String = "") {
        val key = provider.lowercase()
        val s = captcha.getOrPut(key) { Stats() }
        synchronized(s) {
            s.failures++
            s.lastFailMs = System.currentTimeMillis()
            s.lastError = error.take(120)
            if (s.failures > s.successes + FAIL_STREAK_SKIP) {
                s.cooldownUntilMs = System.currentTimeMillis() + COOLDOWN_MS
            }
        }
    }

    fun isMailAvailable(provider: String): Boolean {
        val s = mail[provider.lowercase()] ?: return true
        return System.currentTimeMillis() >= s.cooldownUntilMs
    }

    fun isCaptchaAvailable(provider: String): Boolean {
        val s = captcha[provider.lowercase()] ?: return true
        return System.currentTimeMillis() >= s.cooldownUntilMs
    }

    fun summaryText(): String {
        val mb = mail.entries.joinToString("\n") { (k, v) ->
            val cd = if (System.currentTimeMillis() < v.cooldownUntilMs) " COOLDOWN" else ""
            "mail/$k: ok=${v.successes} fail=${v.failures}$cd ${v.lastError}"
        }
        val cb = captcha.entries.joinToString("\n") { (k, v) ->
            val cd = if (System.currentTimeMillis() < v.cooldownUntilMs) " COOLDOWN" else ""
            "captcha/$k: ok=${v.successes} fail=${v.failures}$cd ${v.lastError}"
        }
        return listOf(mb, cb).filter { it.isNotBlank() }.joinToString("\n").ifBlank { "No provider stats yet." }
    }
}
