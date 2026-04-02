package com.alert.watch

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.core.app.NotificationCompat

class PushReceiver : BroadcastReceiver() {

    companion object {
        const val CH_ALERT = "alert_ch"
        const val NOTIF_ID = 99
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        val cities   = intent.getStringExtra("cities")   ?: intent.getStringExtra("city") ?: "אזור לא ידוע"
        val threat   = intent.getStringExtra("threat")   ?: "1"
        val countdown = intent.getStringExtra("countdown")?.toIntOrNull() ?: 90

        Log.d("ALERT", "Push: $cities | $threat | ${countdown}s")

        // עדכן State
        MainActivity.alertState.value = AlertState(
            active      = true,
            cities      = cities.split(",").map { it.trim() },
            threat      = threat,
            countdown   = countdown,
            triggeredAt = System.currentTimeMillis()
        )

        wakeScreen(ctx)
        vibrate(ctx)
        showNotification(ctx, cities, threat, countdown)

        ctx.startActivity(
            Intent(ctx, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
        )
    }

    private fun wakeScreen(ctx: Context) {
        try {
            val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
            @Suppress("DEPRECATION")
            pm.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "alert:wake"
            ).acquire(20_000)
        } catch (e: Exception) { Log.w("ALERT", "Wake: ${e.message}") }
    }

    private fun vibrate(ctx: Context) {
        val pattern = longArrayOf(0, 700, 150, 700, 150, 700, 150, 700)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                ctx.getSystemService(VibratorManager::class.java)
                    .defaultVibrator
                    .vibrate(VibrationEffect.createWaveform(pattern, -1))
            } else {
                @Suppress("DEPRECATION")
                (ctx.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator)
                    .vibrate(pattern, -1)
            }
        } catch (e: Exception) { Log.w("ALERT", "Vibrate: ${e.message}") }
    }

    private fun showNotification(ctx: Context, cities: String, threat: String, countdown: Int) {
        createChannel(ctx)
        val pi = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notif = NotificationCompat.Builder(ctx, CH_ALERT)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("🚨 צבע אדום – ${cities.split(",").take(2).joinToString(", ")}")
            .setContentText("${threatToInstruction(threat)} • $countdown שנ'")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setFullScreenIntent(pi, true)
            .setColor(0xFFCC0000.toInt())
            .setAutoCancel(true)
            .build()
        ctx.getSystemService(NotificationManager::class.java).notify(NOTIF_ID, notif)
    }

    private fun createChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CH_ALERT) != null) return
        nm.createNotificationChannel(
            NotificationChannel(CH_ALERT, "צבע אדום", NotificationManager.IMPORTANCE_HIGH).apply {
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 700, 150, 700, 150, 700)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
        )
    }
}
