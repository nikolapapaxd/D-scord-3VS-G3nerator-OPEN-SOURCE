package com.discord.evs.generator.locale

import android.app.Application
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import com.discord.evs.generator.data.Prefs
import java.util.Locale

object LocaleHelper {

    /** BCP-47 tags we accept. */
    val SUPPORTED = listOf(
        "en", "es", "ru", "de", "fr", "hi", "pt", "tr", "ar",
        "zh", "ja", "ko", "it", "pl", "nl", "id", "vi", "th"
    )

    fun setLocale(context: Context): Context {
        val prefs = Prefs(context)
        return updateBaseContext(context, prefs.appLanguage)
    }

    fun setLocale(context: Context, lang: String): Context {
        val code = lang.ifBlank { "en" }
        Prefs(context).appLanguage = code
        return updateBaseContext(context, code)
    }

    fun applyLanguage(app: Application) {
        val prefs = Prefs(app)
        updateBaseContext(app, prefs.appLanguage)
    }

    private fun updateBaseContext(context: Context, lang: String): Context {
        val code = when (lang.lowercase()) {
            "hinglish", "hi-en" -> "hi"
            else -> lang.ifBlank { "en" }
        }
        val locale = if (code.contains("-")) {
            val parts = code.split("-", limit = 2)
            Locale(parts[0], parts.getOrElse(1) { "" })
        } else {
            Locale(code)
        }
        Locale.setDefault(locale)
        val config = Configuration(context.resources.configuration)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            config.setLocale(locale)
            config.setLayoutDirection(locale)
            val localized = context.createConfigurationContext(config)
            // Also push into the context resources for older view inflation paths
            @Suppress("DEPRECATION")
            context.resources.updateConfiguration(config, context.resources.displayMetrics)
            return localized
        }
        @Suppress("DEPRECATION")
        config.locale = locale
        @Suppress("DEPRECATION")
        context.resources.updateConfiguration(config, context.resources.displayMetrics)
        return context
    }
}
