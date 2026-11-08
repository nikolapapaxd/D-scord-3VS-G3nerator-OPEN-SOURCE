package com.discord.evs.generator.automation

import android.content.Context
import org.json.JSONObject

/**
 * Persists in-progress generation so app kill / process death can resume.
 */
object GenCheckpoint {
    private const val PREF = "gen_checkpoint"
    private const val KEY = "active"

    data class State(
        val email: String,
        val password: String,
        val username: String,
        val displayName: String,
        val provider: String,
        val step: String, // register | captcha | email | phone | done
        val token: String = "",
        val sessionId: Int = 1,
        val savedAt: Long = System.currentTimeMillis()
    )

    fun save(context: Context, state: State) {
        val json = JSONObject()
            .put("email", state.email)
            .put("password", state.password)
            .put("username", state.username)
            .put("displayName", state.displayName)
            .put("provider", state.provider)
            .put("step", state.step)
            .put("token", state.token)
            .put("sessionId", state.sessionId)
            .put("savedAt", state.savedAt)
            .toString()
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit().putString(KEY, json).apply()
    }

    fun load(context: Context): State? {
        val raw = context.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(KEY, null) ?: return null
        return try {
            val o = JSONObject(raw)
            State(
                email = o.optString("email"),
                password = o.optString("password"),
                username = o.optString("username"),
                displayName = o.optString("displayName"),
                provider = o.optString("provider"),
                step = o.optString("step"),
                token = o.optString("token"),
                sessionId = o.optInt("sessionId", 1),
                savedAt = o.optLong("savedAt", 0L)
            )
        } catch (_: Exception) { null }
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().remove(KEY).apply()
    }
}
