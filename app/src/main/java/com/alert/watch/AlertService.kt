package com.alert.watch

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.*
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * AlertService – Foreground Service שרץ תמיד ברקע.
 *
 * • מציג נוטיפיקציה קטנה תמידית = Wear OS לא יהרוג אותו
 * • Polling ל-api.tzevaadom.co.il כל 3 שניות
 * • START_STICKY = קם מחדש אם נהרג
 * • מסנן לפי ישובים שנבחרו
 */
class AlertService : Service() {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var lastAlertId = ""

    companion object {
        const val CH_ONGOING = "alert_ongoing"
        const val CH_ALERT   = "alert_fire"
        const val NOTIF_ONGOING = 1
        const val NOTIF_ALERT   = 2
        const val POLL_URL = "https://api.tzevaadom.co.il/notifications"

        val alertState = MutableStateFlow(AlertState())

        fun start(ctx: Context) {
            val i = Intent(ctx, AlertService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                ctx.startForegroundService(i)
            else
                ctx.startService(i)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIF_ONGOING, buildOngoingNotif())
        startPolling()
        return START_STICKY
    }

    override fun onBind(intent: Intent?) = null

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    // ── Polling ───────────────────────────────────────────────────────────

    private fun startPolling() {
        scope.launch {
            while (isActive) {
                try { poll() }
                catch (e: Exception) { Log.w("AlertService", e.message ?: "") }
                delay(3_000)
            }
        }
    }

    private suspend fun poll() {
        val conn = URL(POLL_URL).openConnection() as HttpURLConnection
        conn.requestMethod  = "GET"
        conn.connectTimeout = 4_000
        conn.readTimeout    = 4_000
        conn.setRequestProperty("User-Agent", "AlertWatch/6.0")

        val body: String
        try {
            body = conn.inputStream.readBytes()
                .toString(Charsets.UTF_8)
                .trim()
        } finally {
            conn.disconnect()
        }

        // אין התראות
        if (body.isBlank() || body == "[]" || body == "null") {
            if (alertState.value.active) {
                alertState.emit(AlertState())
                updateOngoingNotif(active = false)
            }
            return
        }

        // parse JSON array
        val arr = org.json.JSONArray(body)
        if (arr.length() == 0) {
            if (alertState.value.active) {
                alertState.emit(AlertState())
                updateOngoingNotif(active = false)
            }
            return
        }

        // קח את ההתראה הראשונה
        val alert     = arr.getJSONObject(0)
        val alertId   = alert.optString("notificationId", "")
        val citiesArr = alert.optJSONArray("cities")
        val threat    = alert.optInt("cat", 1).toString()
        val isDrill   = alert.optBoolean("isDrill", false)
        val countdown = countdownForThreat(threat)

        if (isDrill) return
        if (alertId == lastAlertId && alertState.value.active) return
        lastAlertId = alertId

        // כל הערים מהתראה
        val allCities = (0 until (citiesArr?.length() ?: 0))
            .map { citiesArr!!.getString(it) }

        // סנן לפי ישובים שנבחרו
        val prefs   = AppPreferences(this)
        val p       = prefs.state.value
        val watched = buildSet<String> {
            addAll(p.watchedCities)
            if (p.useGps && p.gpsCity != null) add(p.gpsCity)
        }

        val relevant = if (watched.isEmpty()) allCities
        else allCities.filter { city ->
            watched.any { w ->
                city.contains(w, ignoreCase = true) ||
                w.contains(city.take(4), ignoreCase = true)
            }
        }

        if (relevant.isEmpty()) return

        Log.d("AlertService", "🚨 ${relevant.joinToString()} | threat=$threat")

        // עדכן state
        alertState.emit(AlertState(
            active      = true,
            cities      = relevant,
            threat      = threat,
            countdown   = countdown,
            triggeredAt = System.currentTimeMillis()
        ))

        wakeScreen()
        vibrate()
        showAlertNotif(relevant, threat, countdown)
        updateOngoingNotif(active = true, city = relevant.firstOrNull())

        // פתח Activity
        startActivity(Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        })
    }

    private fun countdownForThreat(threat: String) = when (threat) {
        "1","2","13" -> 90
        "3"          -> 0
        "4"          -> 0
        "6"          -> 0
        else         -> 60
    }

    // ── Notifications ─────────────────────────────────────────────────────

    private fun buildOngoingNotif(active: Boolean = false, city: String? = null): Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CH_ONGOING)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(if (active) "🚨 צבע אדום!" else "ALERT")
            .setContentText(if (active && city != null) city else "פעיל · ממתין להתראות")
            .setContentIntent(pi)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setColor(if (active) 0xFFCC0000.toInt() else 0xFF333333.toInt())
            .build()
    }

    private fun updateOngoingNotif(active: Boolean, city: String? = null) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIF_ONGOING, buildOngoingNotif(active, city))
    }

    private fun showAlertNotif(cities: List<String>, threat: String, countdown: Int) {
        val pi = PendingIntent.getActivity(
            this, 1, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notif = NotificationCompat.Builder(this, CH_ALERT)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("🚨 צבע אדום – ${cities.take(2).joinToString(", ")}")
            .setContentText("${threatToInstruction(threat)} • $countdown שנ'")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setFullScreenIntent(pi, true)
            .setColor(0xFFCC0000.toInt())
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java).notify(NOTIF_ALERT, notif)
    }

    // ── Wake + Vibrate ─────────────────────────────────────────────────────

    private fun wakeScreen() {
        try {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            @Suppress("DEPRECATION")
            pm.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "alert:wake"
            ).acquire(15_000)
        } catch (e: Exception) { Log.w("AlertService", "Wake: ${e.message}") }
    }

    private fun vibrate() {
        val pattern = longArrayOf(0, 700, 150, 700, 150, 700, 150, 700)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                getSystemService(VibratorManager::class.java)
                    .defaultVibrator
                    .vibrate(VibrationEffect.createWaveform(pattern, -1))
            } else {
                @Suppress("DEPRECATION")
                (getSystemService(VIBRATOR_SERVICE) as Vibrator).vibrate(pattern, -1)
            }
        } catch (e: Exception) { Log.w("AlertService", "Vibrate: ${e.message}") }
    }

    // ── Channels ───────────────────────────────────────────────────────────

    private fun createChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_ONGOING, "ALERT פעיל",
                NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) }
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_ALERT, "צבע אדום",
                NotificationManager.IMPORTANCE_HIGH).apply {
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 700, 150, 700, 150, 700)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
        )
    }
}
