package com.alert.watch

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.wear.compose.material.*
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.tasks.await
import me.pushy.sdk.Pushy
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.URL
import javax.net.ssl.HttpsURLConnection

// ─────────────────────────────────────────────────────────────────────────────
// DATA MODELS
// ─────────────────────────────────────────────────────────────────────────────

enum class Screen { STANDBY, ALERT, SETTINGS }

data class AlertState(
    val active: Boolean = false,
    val cities: List<String> = emptyList(),
    val threat: String = "",
    val countdown: Int = 0,
    val triggeredAt: Long = 0L
)

data class AppPrefs(
    val watchedCities: Set<String> = emptySet(),
    val pushyToken: String? = null,
    val gpsCity: String? = null,
    val useGps: Boolean = false
)

// רשימת ישובים – נטען מהשרת, ברירת מחדל בינתיים
val DEFAULT_CITIES = listOf(
    "תל אביב - מרכז העיר", "תל אביב - דרום העיר", "תל אביב - צפון העיר",
    "תל אביב - מזרח", "ירושלים", "חיפה - כרמל ועיר תחתית",
    "חיפה - נווה שאנן ורמות", "חיפה - קריית חיים ושמואל",
    "באר שבע - מזרח", "באר שבע - מערב", "באר שבע - צפון",
    "ראשון לציון - מזרח", "ראשון לציון - מערב",
    "פתח תקווה", "אשדוד - א,ב,ד,ה", "אשדוד - ו,ז",
    "אשקלון - צפון", "אשקלון - דרום",
    "נתניה", "רמת גן - גבעתיים",
    "בני ברק", "חולון", "בת ים",
    "הרצליה", "כפר סבא", "רעננה",
    "מודיעין - מכבים רעות", "רחובות", "נס ציונה",
    "עכו", "נהריה", "טבריה", "צפת",
    "קריית שמונה", "מטולה",
    "שדרות", "נתיבות", "אופקים",
    "דימונה", "אילת",
    "גבעת שמואל", "קריית אונו",
    "יבנה", "לוד", "רמלה",
    "כפר יונה", "טייבה", "טירה",
    "אריאל", "מעלה אדומים",
    "ביתר עילית", "בית שמש",
    "חדרה", "זיכרון יעקב",
    "עפולה", "נצרת עילית",
    "קריית ביאליק", "קריית ים",
    "קריית גת", "קריית מלאכי"
)

// ─────────────────────────────────────────────────────────────────────────────
// MAIN ACTIVITY
// ─────────────────────────────────────────────────────────────────────────────

class MainActivity : ComponentActivity() {

    private val prefs by lazy { AppPreferences(this) }

    companion object {
        val alertState = MutableStateFlow(AlertState())
        // ← שנה לכתובת השרת שלך ב-Render
        const val SERVER_URL = "https://redalert-pushy-server.onrender.com"
    }

    private val notifPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { startPushyRegistration() }

    private val locationPermission = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        if (granted.values.any { it }) detectCityFromGps()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // הפעל Pushy listener (ברקע)
        Pushy.listen(this)

        // בקש פרמישן התראות
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            startPushyRegistration()
        }

        setContent {
            val alert   by alertState.collectAsState()
            val appPrefs by prefs.state.collectAsState()
            var screen  by remember { mutableStateOf(Screen.STANDBY) }

            LaunchedEffect(alert.active) {
                if (alert.active) screen = Screen.ALERT
            }

            MaterialTheme {
                when (screen) {
                    Screen.STANDBY  -> StandbyScreen(prefs = appPrefs,
                        onSettings = { screen = Screen.SETTINGS })
                    Screen.ALERT    -> AlertScreen(alert = alert,
                        onDismiss = { alertState.value = AlertState(); screen = Screen.STANDBY })
                    Screen.SETTINGS -> SettingsScreen(
                        prefs        = appPrefs,
                        onRequestGps = { requestGps() },
                        onSave       = { updated ->
                            prefs.save(updated)
                            lifecycleScope.launch(Dispatchers.IO) {
                                updated.pushyToken?.let { token ->
                                    val cities = buildSet<String> {
                                        addAll(updated.watchedCities)
                                        updated.gpsCity?.let { add(it) }
                                    }
                                    updateCitiesOnServer(token, cities.toList())
                                }
                            }
                            screen = Screen.STANDBY
                        }
                    )
                }
            }
        }
    }

    // ── Pushy Registration ────────────────────────────────────────────────
    private fun startPushyRegistration() {
        lifecycleScope.launch(Dispatchers.IO) {
            var attempts = 0
            while (true) {
                try {
                    val token = Pushy.register(this@MainActivity)
                    Log.d("ALERT", "Pushy token: $token")
                    val p = prefs.state.value
                    prefs.save(p.copy(pushyToken = token))
                    val cities = buildSet<String> {
                        addAll(p.watchedCities)
                        p.gpsCity?.let { add(it) }
                    }
                    registerOnServer(token, cities.toList())
                    break
                } catch (e: Exception) {
                    Log.e("ALERT", "Pushy reg failed (attempt ${++attempts}): ${e.message}")
                    delay(5_000)
                }
            }
        }
    }

    // ── GPS ───────────────────────────────────────────────────────────────
    private fun requestGps() {
        val fine   = Manifest.permission.ACCESS_FINE_LOCATION
        val coarse = Manifest.permission.ACCESS_COARSE_LOCATION
        if (ContextCompat.checkSelfPermission(this, fine) == PackageManager.PERMISSION_GRANTED) {
            detectCityFromGps()
        } else {
            locationPermission.launch(arrayOf(fine, coarse))
        }
    }

    private fun detectCityFromGps() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val client = LocationServices.getFusedLocationProviderClient(this@MainActivity)
                val cts    = CancellationTokenSource()
                val loc: Location = client
                    .getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, cts.token)
                    .await() ?: return@launch

                // מצא ישוב קרוב מהרשימה לפי קואורדינטות (פשוט: Geocoder)
                val geocoder   = android.location.Geocoder(this@MainActivity)
                @Suppress("DEPRECATION")
                val addresses  = geocoder.getFromLocation(loc.latitude, loc.longitude, 1)
                val locality   = addresses?.firstOrNull()?.locality
                    ?: addresses?.firstOrNull()?.subAdminArea
                    ?: return@launch

                // מצא ישוב תואם ברשימה
                val matched = DEFAULT_CITIES.firstOrNull {
                    it.contains(locality, ignoreCase = true) ||
                    locality.contains(it.take(4), ignoreCase = true)
                } ?: locality

                val p = prefs.state.value
                prefs.save(p.copy(gpsCity = matched, useGps = true))
                Log.d("ALERT", "GPS city: $matched")

                // עדכן שרת
                p.pushyToken?.let { token ->
                    val cities = buildSet<String> {
                        addAll(p.watchedCities)
                        add(matched)
                    }
                    updateCitiesOnServer(token, cities.toList())
                }
            } catch (e: Exception) {
                Log.w("ALERT", "GPS failed: ${e.message}")
            }
        }
    }

    // ── Server API ────────────────────────────────────────────────────────
    private fun registerOnServer(token: String, cities: List<String>) {
        try {
            val conn = URL("$SERVER_URL/register")
                .openConnection() as java.net.HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput      = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.connectTimeout = 10_000
            conn.readTimeout    = 10_000
            val body = JSONObject().apply {
                put("token",  token)
                put("cities", JSONArray(cities))
            }.toString()
            OutputStreamWriter(conn.outputStream).use { it.write(body) }
            val code = conn.responseCode
            Log.d("ALERT", "Register → HTTP $code")
            conn.disconnect()
        } catch (e: Exception) {
            Log.w("ALERT", "Register failed: ${e.message}")
        }
    }

    private fun updateCitiesOnServer(token: String, cities: List<String>) {
        try {
            val conn = URL("$SERVER_URL/update-cities")
                .openConnection() as java.net.HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput      = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.connectTimeout = 8_000
            conn.readTimeout    = 8_000
            val body = JSONObject().apply {
                put("token",  token)
                put("cities", JSONArray(cities))
            }.toString()
            OutputStreamWriter(conn.outputStream).use { it.write(body) }
            conn.responseCode
            conn.disconnect()
        } catch (e: Exception) {
            Log.w("ALERT", "Update cities failed: ${e.message}")
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// PREFERENCES
// ─────────────────────────────────────────────────────────────────────────────

class AppPreferences(ctx: Context) {
    private val sp = ctx.getSharedPreferences("alert_prefs", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(load())
    val state: StateFlow<AppPrefs> = _state

    fun save(p: AppPrefs) {
        sp.edit()
            .putStringSet("cities",      p.watchedCities)
            .putString("pushy_token",    p.pushyToken)
            .putString("gps_city",       p.gpsCity)
            .putBoolean("use_gps",       p.useGps)
            .apply()
        _state.value = p
    }

    private fun load() = AppPrefs(
        watchedCities = sp.getStringSet("cities",   emptySet()) ?: emptySet(),
        pushyToken    = sp.getString("pushy_token", null),
        gpsCity       = sp.getString("gps_city",    null),
        useGps        = sp.getBoolean("use_gps",    false)
    )
}

// ─────────────────────────────────────────────────────────────────────────────
// SCREENS
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun StandbyScreen(prefs: AppPrefs, onSettings: () -> Unit) {
    val connected  = prefs.pushyToken != null
    val cityCount  = prefs.watchedCities.size + if (prefs.useGps && prefs.gpsCity != null) 1 else 0
    val inf = rememberInfiniteTransition(label = "pulse")
    val scale by inf.animateFloat(
        1f, 1.5f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "s"
    )
    Box(
        Modifier.fillMaxSize().background(Color(0xFF0A0A0A)).clickable { onSettings() },
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier.size(10.dp).scale(scale).clip(CircleShape)
                    .background(
                        when {
                            !connected    -> Color(0xFFFF5252)
                            cityCount > 0 -> Color(0xFF00E676)
                            else          -> Color(0xFFFFAB00)
                        }
                    )
            )
            Spacer(Modifier.height(10.dp))
            Text("ALERT", color = Color.White, fontSize = 16.sp,
                fontWeight = FontWeight.Black, letterSpacing = 2.sp)
            Spacer(Modifier.height(4.dp))
            Text(
                when {
                    !connected    -> "מתחבר..."
                    cityCount == 0 -> "לחץ לבחירת ישוב"
                    else          -> "$cityCount ישובים · פעיל"
                },
                color = Color.White.copy(0.45f), fontSize = 11.sp,
                textAlign = TextAlign.Center
            )
            if (prefs.useGps && prefs.gpsCity != null) {
                Spacer(Modifier.height(3.dp))
                Text("📍 ${prefs.gpsCity}",
                    color = Color(0xFF00E676).copy(0.7f), fontSize = 10.sp)
            }
        }
    }
}

@Composable
fun AlertScreen(alert: AlertState, onDismiss: () -> Unit) {
    var secsLeft by remember { mutableIntStateOf(alert.countdown) }
    LaunchedEffect(alert.triggeredAt) {
        secsLeft = alert.countdown
        while (secsLeft > 0) { delay(1_000); secsLeft-- }
    }
    val inf = rememberInfiniteTransition(label = "bg")
    val bgAlpha by inf.animateFloat(
        0.82f, 1f, infiniteRepeatable(tween(450), RepeatMode.Reverse), label = "a"
    )
    Box(
        Modifier.fillMaxSize().background(Color(0xFFBB0000).copy(bgAlpha))
            .clickable { onDismiss() },
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(10.dp)
        ) {
            Text("🚨", fontSize = 26.sp)
            Spacer(Modifier.height(2.dp))
            Text("צבע אדום", color = Color.White, fontSize = 15.sp,
                fontWeight = FontWeight.Black)
            Spacer(Modifier.height(2.dp))
            val citiesText = alert.cities.take(3).joinToString(" · ")
            Text(citiesText, color = Color.White.copy(0.9f), fontSize = 11.sp,
                textAlign = TextAlign.Center, maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 6.dp))
            Spacer(Modifier.height(6.dp))
            if (alert.countdown > 0) {
                CountdownRing(secsLeft, alert.countdown)
                Spacer(Modifier.height(6.dp))
            }
            Text(threatToInstruction(alert.threat),
                color = Color.White, fontSize = 10.sp,
                textAlign = TextAlign.Center, maxLines = 2, lineHeight = 14.sp)
            Spacer(Modifier.height(5.dp))
            Text("לחץ לסגור", color = Color.White.copy(0.3f), fontSize = 9.sp)
        }
    }
}

@Composable
fun CountdownRing(secsLeft: Int, total: Int) {
    val progress = if (total > 0) secsLeft.toFloat() / total else 0f
    Box(contentAlignment = Alignment.Center) {
        CircularProgressIndicator(
            progress = progress, modifier = Modifier.size(52.dp), strokeWidth = 3.dp,
            indicatorColor = Color.White, trackColor = Color.White.copy(0.15f)
        )
        Text(secsLeft.toString(), color = Color.White, fontSize = 19.sp,
            fontWeight = FontWeight.Bold)
    }
}

fun threatToInstruction(threat: String) = when (threat) {
    "1","2","13" -> "היכנס למרחב מוגן"
    "3"          -> "נעל דלתות · שכב על הרצפה"
    "4"          -> "עמוד בפתח דלת"
    "6"          -> "התרחק מהחוף"
    else         -> "היכנס למרחב מוגן"
}

// ─────────────────────────────────────────────────────────────────────────────
// SETTINGS SCREEN – עם חיפוש ישוב
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun SettingsScreen(
    prefs: AppPrefs,
    onRequestGps: () -> Unit,
    onSave: (AppPrefs) -> Unit
) {
    var selected  by remember { mutableStateOf(prefs.watchedCities.toMutableSet()) }
    var useGps    by remember { mutableStateOf(prefs.useGps) }
    var query     by remember { mutableStateOf("") }

    val filtered = remember(query) {
        if (query.isBlank()) DEFAULT_CITIES.take(30)
        else DEFAULT_CITIES.filter { it.contains(query.trim(), ignoreCase = true) }.take(20)
    }

    ScalingLazyColumn(
        modifier = Modifier.fillMaxSize().background(Color(0xFF0A0A0A)),
        contentPadding = PaddingValues(top = 26.dp, bottom = 24.dp, start = 8.dp, end = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        // כותרת
        item {
            Text("בחר ישובים", color = Color.White, fontSize = 13.sp,
                fontWeight = FontWeight.Bold, modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center)
        }

        // GPS
        item {
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                    .background(if (useGps) Color(0xFF00E676).copy(0.12f) else Color.White.copy(0.06f))
                    .clickable { useGps = !useGps; if (useGps) onRequestGps() }
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text("📍 GPS אוטומטי", color = Color.White, fontSize = 11.sp,
                        fontWeight = FontWeight.Medium)
                    if (prefs.gpsCity != null)
                        Text(prefs.gpsCity, color = Color(0xFF00E676).copy(0.8f), fontSize = 10.sp)
                    else
                        Text("מזהה ישוב קרוב", color = Color.White.copy(0.4f), fontSize = 10.sp)
                }
                Box(Modifier.size(14.dp).clip(CircleShape)
                    .background(if (useGps) Color(0xFF00E676) else Color.White.copy(0.2f)))
            }
        }

        // שדה חיפוש
        item {
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                    .background(Color.White.copy(0.08f))
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("🔍 ", fontSize = 12.sp)
                BasicTextField(
                    value = query,
                    onValueChange = { query = it },
                    textStyle = TextStyle(color = Color.White, fontSize = 11.sp),
                    cursorBrush = SolidColor(Color.White),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    decorationBox = { inner ->
                        if (query.isEmpty())
                            Text("חפש ישוב...", color = Color.White.copy(0.35f), fontSize = 11.sp)
                        inner()
                    }
                )
            }
        }

        // ישובים נבחרים
        if (selected.isNotEmpty()) {
            item {
                Text("נבחרו ${selected.size}:", color = Color(0xFF00E676).copy(0.7f),
                    fontSize = 10.sp, modifier = Modifier.padding(start = 4.dp))
            }
        }

        // רשימת ישובים
        items(filtered.chunked(2).size) { i ->
            val row = filtered.chunked(2)[i]
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                row.forEach { city ->
                    val active = city in selected
                    Box(
                        Modifier.weight(1f).clip(RoundedCornerShape(8.dp))
                            .background(
                                if (active) Color(0xFFCC0000).copy(0.85f)
                                else Color.White.copy(0.07f)
                            )
                            .clickable {
                                selected = selected.toMutableSet().also {
                                    if (active) it.remove(city) else it.add(city)
                                }
                            }
                            .padding(vertical = 6.dp, horizontal = 4.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(city,
                            color = if (active) Color.White else Color.White.copy(0.6f),
                            fontSize = 10.sp, textAlign = TextAlign.Center,
                            maxLines = 2, overflow = TextOverflow.Ellipsis,
                            lineHeight = 13.sp)
                    }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }

        // כפתור שמור
        item {
            Spacer(Modifier.height(4.dp))
            Box(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFFCC0000))
                    .clickable {
                        onSave(prefs.copy(watchedCities = selected, useGps = useGps))
                    }
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Text("שמור", color = Color.White, fontSize = 13.sp,
                    fontWeight = FontWeight.Bold)
            }
        }
    }
}
