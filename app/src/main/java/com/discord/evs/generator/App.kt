package com.discord.evs.generator

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Process
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.app.NotificationManagerCompat
import com.discord.evs.generator.data.Prefs
import com.discord.evs.generator.locale.LocaleHelper
import com.discord.evs.generator.automation.InstallCounter
import com.discord.evs.generator.remote.LocalBridgeServer
import com.discord.evs.generator.features.FeatureFlags
import com.discord.evs.generator.security.NativeGuard
import com.discord.evs.generator.security.SecretsManager
import kotlin.system.exitProcess

class App : Application() {

    override fun onCreate() {
        super.onCreate()
        try {
            com.discord.evs.generator.update.UpdateManager.ensureUpdateChannel(this)
        } catch (_: Exception) {}
        try {
            // Background GitHub poll → system notification while app is closed
            com.discord.evs.generator.update.UpdateCheckWorker.scheduleNowAndPeriodic(this)
            com.discord.evs.generator.update.NtfyUpdateWorker.schedule(this)
        } catch (_: Exception) {}
        LocaleHelper.setLocale(this)
        InstallCounter.start(this)

        try {
            // Seed rejected domains from operator report channel (t.me/excludedmaildomains)
            val prefs = com.discord.evs.generator.data.Prefs(this)
            val fromPrefs = prefs.rejectedMailDomains.split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }
            com.discord.evs.generator.automation.SharedRejectedDomains.addAll(fromPrefs)
            // Ensure telemetry-known bad domains persist in Prefs for future runs
            val seed = listOf(
                "mikethe.guru", "1secmail.org", "mizuhara.my.id",
                "moonstarsun.shop", "happyhorsespace.shop", "uberip.com",
                "guerrillamailblock.com"
            )
            com.discord.evs.generator.automation.SharedRejectedDomains.addAll(seed)
            val merged = (fromPrefs + seed).toSet()
            if (merged.isNotEmpty()) {
                prefs.rejectedMailDomains = merged.joinToString(",")
            }
        } catch (_: Exception) {}

        if (FeatureFlags.PC_BRIDGE_ENABLED) {
            try {
                val prefs = com.discord.evs.generator.data.Prefs(this)
                if (prefs.pcBridgeEnabled) {
                    val token = LocalBridgeServer.ensureToken(prefs)
                    val ok = LocalBridgeServer.start(this)
                    android.util.Log.i(
                        "EVS",
                        if (ok) "PC Bridge on 127.0.0.1:${prefs.pcBridgePort} token=${token.take(8)}…"
                        else "PC Bridge failed to bind"
                    )
                }
            } catch (_: Exception) {}
        }
        applyTheme()
        // Silent VaultCord backfill for stored accounts (cached so it only runs once per token/app)
        try {
            com.discord.evs.generator.automation.VaultCordStartupSync.start(this)
        } catch (_: Exception) {}
        createNotificationChannel()
        installCrashRestarter()
        // Force-load encrypted secrets early so a bad/missing secrets.dat is reported
        // at startup instead of silently failing when notifications are sent.
        try {
            SecretsManager.load(this)
        } catch (e: Exception) {
            android.util.Log.e("EVS", "Failed to load encrypted secrets — run node encrypt_secrets.js (or python3 encrypt_secrets.py) after filling secrets/secrets.txt", e)
        }

        // Anti-tampering / anti-analysis. Fail closed on release.
        try {
            if (BuildConfig.DEBUG) {
                // Only log signature in debug so you can pin EXPECTED_SIGNATURE_HASH for release.
                android.util.Log.i("EVS", "APK signature hash: ${NativeGuard.nativeGetSignatureHash(this)}")
            }
            // Integrity (package/signature) still hard-fails. Environment (root) is soft.
            if (!NativeGuard.verifyIntegrity(this)) {
                Process.killProcess(Process.myPid())
                exitProcess(1)
            }
        } catch (_: Exception) {
            if (!BuildConfig.DEBUG) {
                Process.killProcess(Process.myPid())
                exitProcess(1)
            }
        }
    }

    private fun applyTheme() {
        val prefs = Prefs(this)
        val mode = when (prefs.themeMode) {
            "light" -> AppCompatDelegate.MODE_NIGHT_NO
            "dark" -> AppCompatDelegate.MODE_NIGHT_YES
            else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }
        AppCompatDelegate.setDefaultNightMode(mode)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Account Notifications",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Notifications for generated accounts"
            }
            NotificationManagerCompat.from(this).createNotificationChannel(channel)
        }
    }

    private fun installCrashRestarter() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val intent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
                    addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
                    putExtra("crashed", true)
                }
                val pending = PendingIntent.getActivity(
                    this,
                    0,
                    intent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_ONE_SHOT
                )
                val alarmManager = getSystemService(ALARM_SERVICE) as android.app.AlarmManager
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    alarmManager.setExactAndAllowWhileIdle(
                        android.app.AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        android.os.SystemClock.elapsedRealtime() + 1500,
                        pending
                    )
                } else {
                    @Suppress("DEPRECATION")
                    alarmManager.setExact(
                        android.app.AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        android.os.SystemClock.elapsedRealtime() + 1500,
                        pending
                    )
                }
            } catch (_: Exception) { }
            defaultHandler?.uncaughtException(thread, throwable) ?: run {
                Process.killProcess(Process.myPid())
                exitProcess(1)
            }
        }
    }

    companion object {
        const val CHANNEL_ID = "evs_generator_channel"

        /** Call this any time the theme pref changes so the UI updates immediately. */
        fun applyTheme(app: Application) {
            val prefs = Prefs(app)
            val mode = when (prefs.themeMode) {
                "light" -> AppCompatDelegate.MODE_NIGHT_NO
                "dark" -> AppCompatDelegate.MODE_NIGHT_YES
                else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            }
            AppCompatDelegate.setDefaultNightMode(mode)
        }

        /** Call this after changing the language preference. */
        fun applyLanguage(context: Context) {
            LocaleHelper.setLocale(context)
        }
    }
}
