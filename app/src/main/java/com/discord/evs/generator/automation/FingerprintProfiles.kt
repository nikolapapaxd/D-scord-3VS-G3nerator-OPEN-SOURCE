package com.discord.evs.generator.automation

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Named fingerprint presets rotated per session.
 */
object FingerprintProfiles {
    data class Profile(
        val name: String,
        val userAgent: String,
        val width: Int,
        val height: Int,
        val timezone: String,
        val language: String
    )

    private val builtins = listOf(
        Profile("Pixel 7", "Mozilla/5.0 (Linux; Android 14; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36", 1080, 2400, "America/New_York", "en-US"),
        Profile("Samsung S23", "Mozilla/5.0 (Linux; Android 14; SM-S911B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36", 1080, 2340, "Europe/Berlin", "en-US"),
        Profile("Xiaomi 13", "Mozilla/5.0 (Linux; Android 13; 2211133C) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/119.0.0.0 Mobile Safari/537.36", 1080, 2400, "Asia/Shanghai", "en-US"),
        Profile("OnePlus 11", "Mozilla/5.0 (Linux; Android 14; CPH2449) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36", 1440, 3216, "America/Los_Angeles", "en-US"),
        Profile("Vivo X90", "Mozilla/5.0 (Linux; Android 13; V2241A) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/118.0.0.0 Mobile Safari/537.36", 1260, 2800, "Asia/Kolkata", "en-US")
    )

    fun all(context: Context): List<Profile> {
        val custom = loadCustom(context)
        return builtins + custom
    }

    fun pick(context: Context, sessionId: Int): Profile {
        val list = all(context)
        if (list.isEmpty()) return builtins.first()
        return list[sessionId % list.size]
    }

    private fun loadCustom(context: Context): List<Profile> {
        val raw = context.getSharedPreferences("fp_profiles", Context.MODE_PRIVATE).getString("custom", "[]") ?: "[]"
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                Profile(
                    o.optString("name", "custom"),
                    o.optString("ua"),
                    o.optInt("w", 1080),
                    o.optInt("h", 2400),
                    o.optString("tz", "UTC"),
                    o.optString("lang", "en-US")
                )
            }
        } catch (_: Exception) { emptyList() }
    }

    fun saveCustom(context: Context, profiles: List<Profile>) {
        val arr = JSONArray()
        profiles.forEach { p ->
            arr.put(JSONObject()
                .put("name", p.name)
                .put("ua", p.userAgent)
                .put("w", p.width)
                .put("h", p.height)
                .put("tz", p.timezone)
                .put("lang", p.language))
        }
        context.getSharedPreferences("fp_profiles", Context.MODE_PRIVATE)
            .edit().putString("custom", arr.toString()).apply()
    }
}
