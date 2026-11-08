package com.discord.evs.generator.automation

import android.content.Context
import java.security.MessageDigest

/**
 * Persistent cache of tokens already authorized to the configured VaultCord OAuth app.
 * Stored in app private SharedPreferences — survives restarts, not exported.
 */
object VaultCordVerifiedStore {
    private const val PREF = "vaultcord_verified_cache"
    private const val KEY_TOKENS = "verified_token_fp"
    private const val KEY_CLIENT = "bound_client_id"

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    /** Stable fingerprint of a user token (never store raw token in the set). */
    fun fingerprint(token: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        val dig = md.digest(token.trim().toByteArray(Charsets.UTF_8))
        return dig.joinToString("") { "%02x".format(it) }
    }

    fun isVerified(ctx: Context, token: String, clientId: String): Boolean {
        if (token.isBlank() || clientId.isBlank()) return false
        val p = prefs(ctx)
        val bound = p.getString(KEY_CLIENT, "") ?: ""
        if (bound.isNotBlank() && bound != clientId) {
            // Auth app changed — previous marks are for a different client
            return false
        }
        val set = p.getStringSet(KEY_TOKENS, emptySet()) ?: emptySet()
        return fingerprint(token) in set
    }

    fun markVerified(ctx: Context, token: String, clientId: String) {
        if (token.isBlank() || clientId.isBlank()) return
        val p = prefs(ctx)
        val bound = p.getString(KEY_CLIENT, "") ?: ""
        val editor = p.edit()
        if (bound != clientId) {
            editor.putString(KEY_CLIENT, clientId)
            editor.putStringSet(KEY_TOKENS, mutableSetOf(fingerprint(token)))
        } else {
            val set = p.getStringSet(KEY_TOKENS, emptySet())?.toMutableSet() ?: mutableSetOf()
            set.add(fingerprint(token))
            editor.putStringSet(KEY_TOKENS, set)
        }
        editor.apply()
    }

    fun verifiedCount(ctx: Context): Int =
        prefs(ctx).getStringSet(KEY_TOKENS, emptySet())?.size ?: 0

    fun clear(ctx: Context) {
        prefs(ctx).edit().remove(KEY_TOKENS).remove(KEY_CLIENT).apply()
    }
}
