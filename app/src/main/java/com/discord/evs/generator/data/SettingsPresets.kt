package com.discord.evs.generator.data

object SettingsPresets {
    fun apply(prefs: Prefs, name: String) {
        when (name) {
            "safe_slow" -> {
                prefs.concurrentGenerations = 1
                prefs.humanDelayEnabled = true
                prefs.settingsPreset = "safe_slow"
                prefs.quietConsole = true
            }
            "fast_multi" -> {
                prefs.concurrentGenerations = 3
                prefs.humanDelayEnabled = false
                prefs.settingsPreset = "fast_multi"
                prefs.quietConsole = false
            }
            "phone_heavy" -> {
                prefs.concurrentGenerations = 2
                prefs.settingsPreset = "phone_heavy"
            }
            else -> prefs.settingsPreset = "custom"
        }
    }
}
