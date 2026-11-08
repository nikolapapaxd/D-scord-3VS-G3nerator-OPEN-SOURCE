package com.discord.evs.generator.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.discord.evs.generator.MainActivity
import com.discord.evs.generator.R

/**
 * Keeps process alive while multi-gen runs so Doze does not freeze WebViews.
 */
class GenForegroundService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val channelId = "gen_fg"
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(channelId, "Generation", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Discord EVS Generator")
            .setContentText("Multi-account generation running…")
            .setSmallIcon(R.drawable.ic_terminal)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
        startForeground(42, n)
        return START_STICKY
    }

    companion object {
        fun start(ctx: Context) {
            val i = Intent(ctx, GenForegroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i)
            else ctx.startService(i)
        }
        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, GenForegroundService::class.java))
        }

        fun notifyCaptcha(ctx: Context, text: String = "CAPTCHA needs attention — tap to open") {
            val channelId = "gen_captcha"
            val nm = ctx.getSystemService(NotificationManager::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                nm.createNotificationChannel(
                    NotificationChannel(channelId, "Captcha", NotificationManager.IMPORTANCE_HIGH)
                )
            }
            val pi = PendingIntent.getActivity(
                ctx, 1, Intent(ctx, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val n = NotificationCompat.Builder(ctx, channelId)
                .setContentTitle("Discord EVS Generator")
                .setContentText(text)
                .setSmallIcon(R.drawable.ic_terminal)
                .setContentIntent(pi)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .build()
            nm.notify(43, n)
        }
    }
}
