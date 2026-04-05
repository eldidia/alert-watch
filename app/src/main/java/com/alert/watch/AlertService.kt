package com.alert.watch

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.*
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL

class AlertService : Service() {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var lastAlertId = ""
    private var autoDismissJob: Job? = null
    private lateinit var wakeLock: PowerManager.WakeLock

    companion object {
        const val CH_ONGOING = "alert_ongoing"
        const val CH_ALERT   = "alert_fire"
        const val NOTIF_ONGOING = 1
        const val NOTIF_ALERT   = 2
        const val POLL_URL = "https://api.tzevaadom.co.il/notifications"
        const val AUTO_DISMISS_MS = 10 * 60 * 1000L // 10 דקות
        var allCities: List<String> = emptyList()

        val alertState = MutableStateFlow(AlertState())

        fun syncCities(ctx: Context) {
    CoroutineScope(Dispatchers.IO).launch {
        try {
            val sp = ctx.getSharedPreferences("alert_cities", Context.MODE_PRIVATE)
            
            // טען מקומי תמיד
            if (allCities.isEmpty()) {
                val stored = sp.getString("cities_list", null)
                if (stored != null) {
                    val arr = org.json.JSONArray(stored)
                    allCities = (0 until arr.length()).map { arr.getString(it) }
                }
            }

            // בדוק פעם ביום בלבד
            val lastSync = sp.getLong("last_sync", 0)
            if (System.currentTimeMillis() - lastSync < 86_400_000) return@launch

            // משוך מפיקוד העורף ישירות
            val conn = URL("https://www.oref.org.il/Shared/Ajax/GetCitiesMix.aspx?lang=he")
                .openConnection() as HttpURLConnection
            conn.connectTimeout = 5000
            conn.readTimeout    = 5000
            conn.setRequestProperty("Referer", "https://www.oref.org.il/")
            conn.setRequestProperty("X-Requested-With", "XMLHttpRequest")

            val body = conn.inputStream.readBytes().toString(Charsets.UTF_8).trim()
            conn.disconnect()

            val arr   = org.json.JSONArray(body)
            val count = arr.length()

            // זהה לכמות המקומית – אין צורך לעדכן
            if (count == sp.getInt("cities_count", 0)) {
                sp.edit().putLong("last_sync", System.currentTimeMillis()).apply()
                Log.d("AlertService", "ישובים עדכניים ($count)")
                return@launch
            }

            // שונה – עדכן
            val cities = (0 until count)
                .map { arr.getJSONObject(it).optString("label", "") }
                .filter { it.isNotBlank() }

            allCities = cities
            sp.edit()
                .putString("cities_list", org.json.JSONArray(cities).toString())
                .putInt("cities_count",   cities.size)
                .putLong("last_sync",     System.currentTimeMillis())
                .apply()

            Log.d("AlertService", "ישובים עודכנו: ${cities.size}")

        } catch (e: Exception) {
            Log.w("AlertService", "syncCities: ${e.message}")
        }
    }
        }

        fun start(ctx: Context) {
            val i = Intent(ctx, AlertService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                ctx.startForegroundService(i)
            else
                ctx.startService(i)
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, AlertService::class.java))
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannels()
        // מונע מה-CPU להירדם – מאפשר פולינג גם עם מסך כבוי
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
        PowerManager.PARTIAL_WAKE_LOCK,
        "alert:polling"
         )
         wakeLock.acquire()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIF_ONGOING, buildOngoingNotif())
        startPolling()
        return START_STICKY
    }

    override fun onBind(intent: Intent?) = null

    override fun onDestroy() {
        super.onDestroy()
        if (wakeLock.isHeld) wakeLock.release()
        scope.cancel()
    }

    fun fetchCities(ctx: Context) {
    CoroutineScope(Dispatchers.IO).launch {
        try {
            val sp = ctx.getSharedPreferences("alert_cities", Context.MODE_PRIVATE)
            val storedCount = sp.getInt("cities_count", 0)
            val storedList  = sp.getString("cities_list", null)

            // טען מהזיכרון המקומי
            if (storedList != null && allCities.isEmpty()) {
                val arr = org.json.JSONArray(storedList)
                allCities = (0 until arr.length()).map { arr.getString(it) }
            }

            // בדוק אם יש שינוי בשרת
            val versionConn = URL("$SERVER_URL/cities/version")
                .openConnection() as HttpURLConnection
            versionConn.connectTimeout = 4000
            versionConn.readTimeout    = 4000
            val versionBody = versionConn.inputStream.readBytes().toString(Charsets.UTF_8)
            versionConn.disconnect()

            val serverCount = org.json.JSONObject(versionBody).optInt("count", 0)

            // אם הכמות זהה – אין צורך להוריד שוב
            if (serverCount > 0 && serverCount == storedCount) {
                Log.d("AlertService", "ישובים עדכניים ($serverCount) – אין צורך להוריד")
                return@launch
            }

            // יש שינוי – הורד מחדש
            Log.d("AlertService", "מוריד ישובים: שרת=$serverCount מקומי=$storedCount")
            val conn = URL("$SERVER_URL/cities")
                .openConnection() as HttpURLConnection
            conn.connectTimeout = 6000
            conn.readTimeout    = 6000
            val body = conn.inputStream.readBytes().toString(Charsets.UTF_8)
            conn.disconnect()

            val arr = org.json.JSONArray(body)
            if (arr.length() > 0) {
                allCities = (0 until arr.length()).map { arr.getString(it) }
                // שמור מקומית
                sp.edit()
                    .putString("cities_list",  body)
                    .putInt("cities_count",    arr.length())
                    .apply()
                Log.d("AlertService", "ישובים עודכנו: ${allCities.size}")
            }
        } catch (e: Exception) {
            Log.w("AlertService", "fetchCities: ${e.message}")
        }
    }
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
            body = conn.inputStream.readBytes().toString(Charsets.UTF_8).trim()
        } finally {
            conn.disconnect()
        }

        if (body.isBlank() || body == "[]" || body == "null") {
            if (alertState.value.active) {
                alertState.emit(AlertState())
                updateOngoingNotif(active = false)
            }
            return
        }

        val arr = JSONArray(body)
        if (arr.length() == 0) {
            if (alertState.value.active) {
                alertState.emit(AlertState())
                updateOngoingNotif(active = false)
            }
            return
        }

        val alert     = arr.getJSONObject(0)
        val alertId   = alert.optString("notificationId", "")
        val citiesArr = alert.optJSONArray("cities")
        val threat    = alert.optInt("cat", 1).toString()
        val isDrill   = alert.optBoolean("isDrill", false)
        val countdown = countdownForThreat(threat)

        if (isDrill) return
        if (alertId == lastAlertId && alertState.value.active) return
        lastAlertId = alertId

        val allCities = (0 until (citiesArr?.length() ?: 0))
            .map { citiesArr!!.getString(it) }

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

        // מחיקה אוטומטית אחרי 10 דקות
        autoDismissJob?.cancel()
        autoDismissJob = scope.launch {
            delay(AUTO_DISMISS_MS)
            alertState.emit(AlertState())
            updateOngoingNotif(active = false)
            getSystemService(NotificationManager::class.java).cancel(NOTIF_ALERT)
        }

        startActivity(Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        })
    }

    private fun countdownForThreat(threat: String) = when (threat) {
        "1","2","13" -> 90
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
            .setContentTitle(if (active) "ALERT 🚨 – ${city ?: ""}" else "ALERT")
            .setContentText(if (active) "צבע אדום פעיל" else "פעיל · ממתין להתראות")
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
        // כותרת: ALERT – עיר1, עיר2
        val cityTitle = cities.take(2).joinToString(", ")
        val notif = NotificationCompat.Builder(this, CH_ALERT)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("ALERT – $cityTitle")
            .setContentText("${threatToInstruction(threat)} • $countdown שנ'")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setFullScreenIntent(pi, true)
            .setColor(0xFFCC0000.toInt())
            .setAutoCancel(true)
            .setTimeoutAfter(AUTO_DISMISS_MS) // מחיקה אוטומטית ב-10 דקות
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
