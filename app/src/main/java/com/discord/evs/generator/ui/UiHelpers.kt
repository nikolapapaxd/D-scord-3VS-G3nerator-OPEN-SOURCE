package com.discord.evs.generator.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.widget.TextView
import android.widget.Toast
import com.discord.evs.generator.data.Prefs

object UiHelpers {
    fun copy(context: Context, label: String, value: String) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(label, value))
        Toast.makeText(context, "Copied $label", Toast.LENGTH_SHORT).show()
    }

    fun haptic(context: Context, prefs: Prefs) {
        if (!prefs.uxHaptics) return
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                val vm = context.getSystemService(VibratorManager::class.java)
                vm?.defaultVibrator?.vibrate(VibrationEffect.createOneShot(35, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                val v = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                if (Build.VERSION.SDK_INT >= 26) v?.vibrate(VibrationEffect.createOneShot(35, VibrationEffect.DEFAULT_AMPLITUDE))
                else @Suppress("DEPRECATION") v?.vibrate(35)
            }
        } catch (_: Exception) { }
    }

    fun statusColor(status: String): Int = when (status.uppercase()) {
        "VALID" -> Color.parseColor("#57F287")
        "PHONE", "PHONE_REQUIRED", "EMAIL" -> Color.parseColor("#FEE75C")
        "LOCKED", "INVALID" -> Color.parseColor("#ED4245")
        "PENDING" -> Color.parseColor("#B5BAC1")
        else -> Color.parseColor("#B5BAC1")
    }

    fun applyStatusPill(tv: TextView, status: String, enabled: Boolean) {
        if (!enabled) {
            tv.text = status
            return
        }
        tv.text = "  ${status.uppercase()}  "
        tv.setTextColor(Color.BLACK)
        tv.setBackgroundColor(statusColor(status))
        tv.setPadding(16, 6, 16, 6)
    }
}
