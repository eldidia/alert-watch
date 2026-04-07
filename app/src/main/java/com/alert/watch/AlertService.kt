package com.alert.watch

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

class AlertService : Service() {

    // ── Private state ─────────────────────────────────────────────
    private val scope          = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var lastAlertId    = ""
    private var autoDismissJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    // ── Companion ─────────────────────────────────────────────────
    companion object {

        private const val TAG = "AlertService"

        // Notification channels
        const val CH_ONGOING    = "alert_ongoing"
        const val CH_ALERT      = "alert_fire"
        const val NOTIF_ONGOING = 1
        const val NOTIF_ALERT   = 2

        // Endpoints
        const val POLL_URL      = "https://api.tzevaadom.co.il/notifications"
        const val CITIES_URL    = "https://www.oref.org.il/Shared/Ajax/GetCitiesMix.aspx?lang=he"

        // Timing
        const val POLL_INTERVAL_MS    = 3_000L
        const val AUTO_DISMISS_MS     = 10 * 60 * 1_000L   // 10 minutes
        const val CITIES_SYNC_INTERVAL= 24 * 60 * 60_000L  // 24 hours
        const val WAKE_LOCK_TIMEOUT_MS= 12 * 60 * 60_000L  // 12 hours

        // Observable alert state
        val alertState = MutableStateFlow(AlertState())

        // Full cities list – loaded from oref or local cache
        private val _allCities = MutableStateFlow<List<String>>(emptyList())
        val allCitiesFlow: StateFlow<List<String>> = _allCities
        var allCities: List<String>
            get() = _allCities.value
            private set(value) { _allCities.value = value }

        // ── Service control ───────────────────────────────────────

        fun start(ctx: Context) {
            val intent = Intent(ctx, AlertService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                ctx.startForegroundService(intent)
            else
                ctx.startService(intent)
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, AlertService::class.java))
        }

        // ── Cities sync ───────────────────────────────────────────
        /**
         * Syncs the city list from Pikud HaOref.
         * - Always loads from local cache on first call.
         * - Fetches from the official API once per day.
         * - Compares count to avoid unnecessary downloads.
         */
        fun syncCities(ctx: Context) {
            CoroutineScope(Dispatchers.IO).launch {
                val sp = ctx.getSharedPreferences("alert_cities", Context.MODE_PRIVATE)

                // Always restore from local cache first
                if (allCities.isEmpty()) {
                    sp.getString("cities_list", null)?.let { cached ->
                        try {
                            val arr = JSONArray(cached)
                            allCities = (0 until arr.length()).map { arr.getString(it) }
                            Log.d(TAG, "Cities loaded from cache: ${allCities.size}")
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to parse cached cities: ${e.message}")
                        }
                    }
                }

                // Check if daily sync is due
                val lastSync = sp.getLong("cities_last_sync", 0L)
                if (System.currentTimeMillis() - lastSync < CITIES_SYNC_INTERVAL) return@launch

                // Fetch from Pikud HaOref
                try {
                    val conn = (URL(CITIES_URL).openConnection() as HttpURLConnection).apply {
                        connectTimeout = 6_000
                        readTimeout    = 6_000
                        setRequestProperty("Referer",          "https://www.oref.org.il/")
                        setRequestProperty("X-Requested-With", "XMLHttpRequest")
                    }
                    val body  = conn.inputStream.bufferedReader().readText().trim()
                    conn.disconnect()

                    if (body.isBlank() || body == "null") {
                        Log.w(TAG, "Empty response from cities API")
                        return@launch
                    }

                    val arr   = JSONArray(body)
                    val count = arr.length()

                    // No change – just update the sync timestamp
                    if (count == sp.getInt("cities_count", 0)) {
                        sp.edit().putLong("cities_last_sync", System.currentTimeMillis()).apply()
                        Log.d(TAG, "Cities up to date ($count)")
                        return@launch
                    }

                    // Parse and save updated list
                    val cities = (0 until count)
                        .mapNotNull { arr.getJSONObject(it).optString("label", "").takeIf { s -> s.isNotBlank() } }

                    allCities = cities
                    sp.edit()
                        .putString("cities_list",       JSONArray(cities).toString())
                        .putInt("cities_count",         cities.size)
                        .putLong("cities_last_sync",    System.currentTimeMillis())
                        .apply()

                    Log.d(TAG, "Cities updated: ${cities.size}")

                } catch (e: Exception) {
                    Log.w(TAG, "syncCities failed: ${e.message}")
                }
            }
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
        acquireWakeLock()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIF_ONGOING, buildOngoingNotification())
        startPolling()
        return START_STICKY
    }

    override fun onBind(intent: Intent?) = null

    override fun onDestroy() {
        super.onDestroy()
        releaseWakeLock()
        scope.cancel()
    }

    // ── Wake lock ─────────────────────────────────────────────────

    private fun acquireWakeLock() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "alert:polling"
        ).apply { acquire(WAKE_LOCK_TIMEOUT_MS) }
    }

    private fun releaseWakeLock() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
    }

    // ── Polling ───────────────────────────────────────────────────

    private fun startPolling() {
        scope.launch {
            while (isActive) {
                runCatching { poll() }
                    .onFailure { Log.w(TAG, "Poll error: ${it.message}") }
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    private suspend fun poll() {
        val conn = (URL(POLL_URL).openConnection() as HttpURLConnection).apply {
            requestMethod  = "GET"
            connectTimeout = 4_000
            readTimeout    = 4_000
            setRequestProperty("User-Agent", "AlertWatch/6.0")
        }

        val body = try {
            conn.inputStream.bufferedReader().readText().trim()
        } finally {
            conn.disconnect()
        }

        // No active alerts
        if (body.isBlank() || body == "[]" || body == "null") {
            if (alertState.value.active) {
                alertState.emit(AlertState())
                updateOngoingNotification(active = false)
            }
            return
        }

        val arr = JSONArray(body)
        if (arr.length() == 0) {
            if (alertState.value.active) {
                alertState.emit(AlertState())
                updateOngoingNotification(active = false)
            }
            return
        }

        val alert   = arr.getJSONObject(0)
        val alertId = alert.optString("notificationId", "")

        // Skip drills
        if (alert.optBoolean("isDrill", false)) return

        // Skip already-processed alert (unless it cleared and came back)
        if (alertId == lastAlertId && alertState.value.active) return
        lastAlertId = alertId

        val citiesArr = alert.optJSONArray("cities")
        val threat    = alert.optInt("cat", 1).toString()
        val countdown = countdownForThreat(threat)
        val incoming  = (0 until (citiesArr?.length() ?: 0))
            .map { citiesArr!!.getString(it) }

        // Filter to user's watched locations
        val prefs   = AppPreferences(this)
        val p       = prefs.state.value
        val watched = buildSet<String> {
            addAll(p.watchedCities)
            if (p.useGps && p.gpsCity != null) add(p.gpsCity)
        }

        val relevant = when {
            watched.isEmpty() -> incoming
            else -> incoming.filter { city ->
                watched.any { w ->
                    city.contains(w, ignoreCase = true) ||
                    w.contains(city.take(4), ignoreCase = true)
                }
            }
        }

        if (relevant.isEmpty()) return

        Log.i(TAG, "🚨 Alert: ${relevant.joinToString()} | threat=$threat | countdown=${countdown}s")

        // Emit new alert state
        alertState.emit(AlertState(
            active      = true,
            cities      = relevant,
            threat      = threat,
            countdown   = countdown,
            triggeredAt = System.currentTimeMillis()
        ))

        wakeScreen()
        vibrate()
        showAlertNotification(relevant, threat, countdown)
        updateOngoingNotification(active = true, city = relevant.first())

        // Auto-dismiss after timeout
        autoDismissJob?.cancel()
        autoDismissJob = scope.launch {
            delay(AUTO_DISMISS_MS)
            alertState.emit(AlertState())
            updateOngoingNotification(active = false)
            getSystemService(NotificationManager::class.java).cancel(NOTIF_ALERT)
        }

        // Bring app to foreground
        startActivity(Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        })
    }

    private fun countdownForThreat(threat: String) = when (threat) {
        "1", "2", "13" -> 90
        else            -> 60
    }

    // ── Notifications ─────────────────────────────────────────────

    private fun buildOngoingNotification(
        active: Boolean = false,
        city: String? = null
    ): Notification {
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CH_ONGOING)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(if (active) "ALERT 🚨 – ${city.orEmpty()}" else "ALERT")
            .setContentText(if (active) "צבע אדום פעיל" else "פעיל · ממתין להתראות")
            .setContentIntent(pi)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setColor(if (active) 0xFFCC0000.toInt() else 0xFF333333.toInt())
            .build()
    }

    private fun updateOngoingNotification(active: Boolean, city: String? = null) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIF_ONGOING, buildOngoingNotification(active, city))
    }

    private fun showAlertNotification(
        cities: List<String>,
        threat: String,
        countdown: Int
    ) {
        val pi = PendingIntent.getActivity(
            this, 1,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notif = NotificationCompat.Builder(this, CH_ALERT)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("ALERT – ${cities.take(2).joinToString(", ")}")
            .setContentText("${threatToInstruction(threat)} • $countdown שנ'")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setFullScreenIntent(pi, true)
            .setColor(0xFFCC0000.toInt())
            .setAutoCancel(true)
            .setTimeoutAfter(AUTO_DISMISS_MS)
            .build()
        getSystemService(NotificationManager::class.java).notify(NOTIF_ALERT, notif)
    }

    // ── Screen wake ───────────────────────────────────────────────

    private fun wakeScreen() {
        runCatching {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            @Suppress("DEPRECATION")
            pm.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "alert:wake"
            ).acquire(15_000L)
        }.onFailure { Log.w(TAG, "wakeScreen: ${it.message}") }
    }

    // ── Vibration ─────────────────────────────────────────────────

    private fun vibrate() {
        val pattern = longArrayOf(0, 700, 150, 700, 150, 700, 150, 700)
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                getSystemService(VibratorManager::class.java)
                    .defaultVibrator
                    .vibrate(VibrationEffect.createWaveform(pattern, -1))
            } else {
                @Suppress("DEPRECATION")
                (getSystemService(VIBRATOR_SERVICE) as Vibrator).vibrate(pattern, -1)
            }
        }.onFailure { Log.w(TAG, "vibrate: ${it.message}") }
    }

    // ── Notification channels ─────────────────────────────────────

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java)

        nm.createNotificationChannel(
            NotificationChannel(
                CH_ONGOING,
                "ALERT פעיל",
                NotificationManager.IMPORTANCE_LOW
            ).apply { setShowBadge(false) }
        )

        nm.createNotificationChannel(
            NotificationChannel(
                CH_ALERT,
                "צבע אדום",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                enableVibration(true)
                vibrationPattern    = longArrayOf(0, 700, 150, 700, 150, 700)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
        )
    }
}
