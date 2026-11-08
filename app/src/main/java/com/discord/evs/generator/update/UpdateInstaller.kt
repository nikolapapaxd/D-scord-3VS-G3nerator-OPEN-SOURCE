package com.discord.evs.generator.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File

object UpdateInstaller {
    fun canRequestPackageInstalls(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else true
    }

    fun intentUnknownSources(context: Context): Intent {
        return Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}")
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /**
     * Install APK via FileProvider. Extra FLAG_GRANT for OEM strictness (Vivo/Oppo/Xiaomi).
     * Falls back to content URI view; never uses file:// on API 24+.
     */
    fun installApk(context: Context, apk: File): Boolean {
        if (!apk.exists() || apk.length() < 1024L) return false
        // Ensure world-readable is NOT required; grant via flags
        try {
            apk.setReadable(true, false)
        } catch (_: Exception) {}
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apk
        )
        // Primary: ACTION_VIEW package-archive
        val view = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            }
        }
        // Grant to all handlers that can install packages
        val resInfo = context.packageManager.queryIntentActivities(view, 0)
        for (ri in resInfo) {
            context.grantUriPermission(
                ri.activityInfo.packageName,
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        }
        return try {
            context.startActivity(view)
            true
        } catch (_: Exception) {
            // ACTION_VIEW with package-archive MIME is the supported path on modern Android.
            // Older ACTION_INSTALL_PACKAGE is deprecated and removed as a fallback.
            false
        }
    }
}
