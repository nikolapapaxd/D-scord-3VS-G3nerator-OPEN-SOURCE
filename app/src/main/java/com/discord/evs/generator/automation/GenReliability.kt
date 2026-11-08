package com.discord.evs.generator.automation

import com.discord.evs.generator.data.Prefs
import kotlinx.coroutines.delay
import org.json.JSONObject

/** Adaptive delays, 429 backoff, session budget, sitekey cache, timeline. */
class GenReliability(private val prefs: Prefs) {
    private var phoneLocks = 0
    private var lastDelayMs = 900L
    private val sitekeys = mutableMapOf<String, Pair<String, Long>>()
    private val timeline = mutableListOf<String>()

    fun event(msg: String) {
        if (!prefs.genTimelineDebug) return
        timeline += "${System.currentTimeMillis()}: $msg"
        if (timeline.size > 200) timeline.removeAt(0)
    }

    fun timelineJson(): String = JSONObject().put("events", timeline).toString()

    fun notePhoneLock(): Boolean {
        phoneLocks++
        event("phone_lock count=$phoneLocks")
        val budget = prefs.genSessionBudget
        return budget > 0 && phoneLocks >= budget
    }

    suspend fun afterRateLimit(retryAfterSec: Long = 0) {
        if (!prefs.genBackoff429) {
            delay(3000); return
        }
        val wait = if (retryAfterSec > 0) retryAfterSec * 1000L else (lastDelayMs * 2).coerceAtMost(60_000L)
        lastDelayMs = wait
        event("backoff_429 waitMs=$wait")
        delay(wait)
    }

    suspend fun adaptivePause() {
        if (!prefs.genAdaptiveDelay) {
            delay(800); return
        }
        val jitter = (200..900).random()
        delay(lastDelayMs.coerceIn(500, 5000) + jitter)
        lastDelayMs = (lastDelayMs * 0.92).toLong().coerceAtLeast(600)
    }

    fun cacheSitekey(host: String, key: String) {
        if (!prefs.genSitekeyCache) return
        sitekeys[host] = key to System.currentTimeMillis()
    }

    fun getSitekey(host: String, ttlMs: Long = 30 * 60 * 1000L): String? {
        if (!prefs.genSitekeyCache) return null
        val p = sitekeys[host] ?: return null
        if (System.currentTimeMillis() - p.second > ttlMs) {
            sitekeys.remove(host); return null
        }
        return p.first
    }

    fun resetSession() {
        phoneLocks = 0
        lastDelayMs = 900L
        timeline.clear()
    }
}
