package com.alert.watch

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
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

private var batteryOptAsked = false

// ── Models ────────────────────────────────────────────────────────────────────

enum class Screen { STANDBY, ALERT, SETTINGS }

data class AlertState(
    val active: Boolean = false,
    val cities: List<String> = emptyList(),
    val threat: String = "1",
    val countdown: Int = 0,
    val triggeredAt: Long = 0L
)

data class AppPrefs(
    val watchedCities: Set<String> = emptySet(),
    val gpsCity: String? = null,
    val useGps: Boolean = false
)

val cities = remember { 
    AlertService.allCities.ifEmpty { CITIES } 
}


// ── MainActivity ──────────────────────────────────────────────────────────────

class MainActivity : ComponentActivity() {

    private val prefs by lazy { AppPreferences(this) }

    private val notifPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { AlertService.start(this) }

    private val locationPermission = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted -> if (granted.values.any { it }) detectGpsCity() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            val alert    by AlertService.alertState.collectAsState()
            val appPrefs by prefs.state.collectAsState()
            var screen   by remember { mutableStateOf(Screen.STANDBY) }

            LaunchedEffect(alert.active) {
                if (alert.active) screen = Screen.ALERT
            }

            MaterialTheme {
                when (screen) {
                    Screen.STANDBY  -> StandbyScreen(
                        prefs = appPrefs,
                        onSettings = { screen = Screen.SETTINGS }
                    )
                    Screen.ALERT    -> AlertScreen(
                        alert = alert,
                        onDismiss = {
                            AlertService.alertState.value = AlertState()
                            screen = Screen.STANDBY
                        }
                    )
                    Screen.SETTINGS -> SettingsScreen(
                        prefs        = appPrefs,
                        onRequestGps = { requestGps() },
                        onBack       = { screen = Screen.STANDBY }, // חזרה ללא שמירה
                        onSave       = { updated ->
                            prefs.save(updated)
                            screen = Screen.STANDBY
                        },
                        onStopService = {
                            AlertService.stop(this)
                            finishAffinity() // סגור אפליקציה לחלוטין
                        }
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            AlertService.start(this)
        }
        AlertService.fetchCities(this)

        // בקש ביטול אופטימיזציית סוללה
        val pm = getSystemService(PowerManager::class.java)
        if (!batteryOptAsked && !pm.isIgnoringBatteryOptimizations(packageName)) {
            batteryOptAsked = true
            startActivity(Intent(
            android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            android.net.Uri.parse("package:$packageName")
           ))
        }
    }

    private fun requestGps() {
        val fine = Manifest.permission.ACCESS_FINE_LOCATION
        if (ContextCompat.checkSelfPermission(this, fine) == PackageManager.PERMISSION_GRANTED)
            detectGpsCity()
        else
            locationPermission.launch(arrayOf(fine, Manifest.permission.ACCESS_COARSE_LOCATION))
    }

    private fun detectGpsCity() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val client = LocationServices.getFusedLocationProviderClient(this@MainActivity)
                val loc: Location = client
                    .getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY,
                        CancellationTokenSource().token).await() ?: return@launch

                @Suppress("DEPRECATION")
                val addresses = Geocoder(this@MainActivity)
                    .getFromLocation(loc.latitude, loc.longitude, 1)
                val locality  = addresses?.firstOrNull()?.locality
                    ?: addresses?.firstOrNull()?.subAdminArea
                    ?: return@launch

                val matched = CITIES.firstOrNull {
                    it.contains(locality, ignoreCase = true) ||
                    locality.contains(it.take(4), ignoreCase = true)
                } ?: locality

                prefs.save(prefs.state.value.copy(gpsCity = matched, useGps = true))
                Log.d("ALERT", "GPS: $matched")
            } catch (e: Exception) {
                Log.w("ALERT", "GPS failed: ${e.message}")
            }
        }
    }
}

// ── Preferences ───────────────────────────────────────────────────────────────

class AppPreferences(ctx: Context) {
    private val sp = ctx.getSharedPreferences("alert_prefs", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(load())
    val state: StateFlow<AppPrefs> = _state

    fun save(p: AppPrefs) {
        sp.edit()
            .putStringSet("cities",  p.watchedCities)
            .putString("gps_city",   p.gpsCity)
            .putBoolean("use_gps",   p.useGps)
            .apply()
        _state.value = p
    }

    private fun load() = AppPrefs(
        watchedCities = sp.getStringSet("cities", emptySet()) ?: emptySet(),
        gpsCity       = sp.getString("gps_city",  null),
        useGps        = sp.getBoolean("use_gps",   false)
    )
}

// ── Screens ───────────────────────────────────────────────────────────────────

@Composable
fun StandbyScreen(prefs: AppPrefs, onSettings: () -> Unit) {
    val cityCount = prefs.watchedCities.size + if (prefs.useGps && prefs.gpsCity != null) 1 else 0
    val inf = rememberInfiniteTransition(label = "p")
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
                    .background(if (cityCount > 0) Color(0xFF00E676) else Color(0xFFFFAB00))
            )
            Spacer(Modifier.height(10.dp))
            Text("ALERT", color = Color.White, fontSize = 16.sp,
                fontWeight = FontWeight.Black, letterSpacing = 2.sp)
            Spacer(Modifier.height(4.dp))
            Text(
                if (cityCount == 0) "לחץ לבחירת ישוב" else "$cityCount ישובים · פעיל",
                color = Color.White.copy(0.45f), fontSize = 11.sp, textAlign = TextAlign.Center
            )
            if (prefs.useGps && prefs.gpsCity != null) {
                Spacer(Modifier.height(3.dp))
                Text("📍 ${prefs.gpsCity}", color = Color(0xFF00E676).copy(0.7f), fontSize = 10.sp)
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

    // כפתור חזרה סוגר את ההתראה
    BackHandler { onDismiss() }

    Box(
        Modifier.fillMaxSize().background(Color(0xFFBB0000).copy(bgAlpha))
            .clickable { onDismiss() },
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(10.dp)) {
            Text("🚨", fontSize = 26.sp)
            Spacer(Modifier.height(2.dp))
            Text("ALERT", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Black,
                letterSpacing = 2.sp)
            Spacer(Modifier.height(2.dp))
            Text(alert.cities.take(3).joinToString(" · "),
                color = Color.White.copy(0.9f), fontSize = 11.sp,
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
        Text(secsLeft.toString(), color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.Bold)
    }
}

fun threatToInstruction(threat: String) = when (threat) {
    "1","2","13" -> "היכנס למרחב מוגן"
    "3"          -> "נעל דלתות · שכב על הרצפה"
    "4"          -> "עמוד בפתח דלת"
    "6"          -> "התרחק מהחוף"
    else         -> "היכנס למרחב מוגן"
}


// ═══════════════════════════════════════════════════════════════════
//  Settings Screen 
// ═══════════════════════════════════════════════════════════════════

@Composable
fun SettingsScreen(
    prefs: AppPrefs,
    onRequestGps: () -> Unit,
    onBack: () -> Unit,
    onSave: (AppPrefs) -> Unit,
    onStopService: () -> Unit
) {
    // ── State ─────────────────────────────────────────────────────
    val MAX_DISPLAY = 30

    // ערים נבחרות
    var selected by remember { mutableStateOf(prefs.watchedCities.toMutableSet()) }

    // רשימת תצוגה – תמיד 30 ערים
    // נבחרות ראשונות, אחר כך ברירת מחדל לפי הרשימה
    var displayList by remember {
        val sel  = CITIES.filter { it in prefs.watchedCities }.toMutableList()
        val rest = CITIES.filter { it !in prefs.watchedCities }
        val combined = (sel + rest).take(MAX_DISPLAY).toMutableList()
        mutableStateOf(combined)
    }

    var useGps  by remember { mutableStateOf(prefs.useGps) }
    var query   by remember { mutableStateOf("") }
    var showSearchResults by remember { mutableStateOf(false) }

    // מקור נתונים לחיפוש – המאגר המלא (+ ישובים שהורדו מהשרת)
    val searchSource = remember {
        (AlertService.allCities.ifEmpty { CITIES }).distinctBy { it }
    }

    // תוצאות חיפוש – מסנן מהמאגר המלא, לא כולל מה שכבר ברשימה
    val searchResults = remember(query) {
    val q = query.trim()
    if (q.length < 2) emptyList()
    else {
        // פצל את שאילתת החיפוש למילים
        val words = q.split(" ", "־", "-").filter { it.length >= 2 }
        searchSource.filter { city ->
            // כל מילה בשאילתה חייבת להופיע איפשהו בשם העיר
            words.all { word ->
                city.contains(word, ignoreCase = true)
            }
            // או: השאילתה המלאה מופיעה כחלק מהשם
            || city.contains(q, ignoreCase = true)
        }.take(15)
    }
    }
    

    // כשיש תוצאות חיפוש – הצג אותן
    LaunchedEffect(searchResults) {
        showSearchResults = searchResults.isNotEmpty()
    }

    // ── פונקציה: הוסף עיר לרשימת התצוגה ────────────────────────
    fun addToDisplayList(city: String) {
        if (city in displayList) {
            // כבר ברשימה – פשוט סמן
            selected = selected.toMutableSet().also { it.add(city) }
            return
        }

        val newDisplay = displayList.toMutableList()
        if (newDisplay.size >= MAX_DISPLAY) {
            // מצא עיר לא-בחורה מהסוף להסרה
            val toRemove = newDisplay.lastOrNull { it !in selected }
            if (toRemove != null) {
                newDisplay.remove(toRemove)
            } else {
                // כל 30 בחורות – לא ניתן להוסיף עוד
                return
            }
        }
        // הוסף עיר חדשה בהתחלה (אחרי הבחורות)
        val insertAt = newDisplay.count { it in selected }
        newDisplay.add(insertAt.coerceAtMost(newDisplay.size), city)
        displayList = newDisplay
        selected = selected.toMutableSet().also { it.add(city) }
        query = ""
    }

    // ── פונקציה: הסר/הוסף בחירה ─────────────────────────────────
    fun toggleCity(city: String) {
        val newSelected = selected.toMutableSet()
        if (city in selected) {
            newSelected.remove(city)
        } else {
            newSelected.add(city)
        }
        selected = newSelected
    }

    BackHandler { onBack() }

    // ── UI ────────────────────────────────────────────────────────
    ScalingLazyColumn(
        modifier = Modifier.fillMaxSize().background(Color(0xFF0A0A0A)),
        contentPadding = PaddingValues(top = 26.dp, bottom = 24.dp, start = 8.dp, end = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {

        // כותרת
        item {
            Text("ALERT · ישובים",
                color = Color.White, fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center)
        }

        // GPS
        item {
            Row(
                Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (useGps) Color(0xFF00E676).copy(0.12f) else Color.White.copy(0.06f))
                    .clickable { useGps = !useGps; if (useGps) onRequestGps() }
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text("📍 GPS אוטומטי", color = Color.White,
                        fontSize = 11.sp, fontWeight = FontWeight.Medium)
                    Text(prefs.gpsCity ?: "מזהה ישוב קרוב",
                        color = if (prefs.gpsCity != null) Color(0xFF00E676).copy(0.8f)
                                else Color.White.copy(0.4f),
                        fontSize = 10.sp)
                }
                Box(Modifier.size(14.dp).clip(CircleShape)
                    .background(if (useGps) Color(0xFF00E676) else Color.White.copy(0.2f)))
            }
        }

        // שדה חיפוש
        item {
            Row(
                Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color.White.copy(if (query.isNotEmpty()) 0.12f else 0.08f))
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
                    modifier = Modifier.weight(1f),
                    decorationBox = { inner ->
                        if (query.isEmpty())
                            Text("חפש מכל הישובים...",
                                color = Color.White.copy(0.35f), fontSize = 11.sp)
                        inner()
                    }
                )
                if (query.isNotEmpty()) {
                    Text("✕", color = Color.White.copy(0.5f), fontSize = 12.sp,
                        modifier = Modifier.clickable { query = "" }.padding(start = 6.dp))
                }
            }
        }

        // תוצאות חיפוש – מופיעות מעל הרשימה הרגילה
        if (showSearchResults && query.length >= 2) {
            item {
                Text("תוצאות חיפוש:",
                    color = Color(0xFFFFAB00).copy(0.8f), fontSize = 10.sp,
                    modifier = Modifier.padding(start = 4.dp, top = 2.dp))
            }
            items(searchResults.chunked(2).size) { i ->
                val row = searchResults.chunked(2)[i]
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    row.forEach { city ->
                        val inDisplay = city in displayList
                        val isSelected = city in selected
                        Box(
                            Modifier.weight(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .background(
                                    when {
                                        isSelected  -> Color(0xFFCC0000).copy(0.85f)
                                        inDisplay   -> Color.White.copy(0.12f)
                                        else        -> Color(0xFFFFAB00).copy(0.15f)
                                    }
                                )
                                .clickable { addToDisplayList(city) }
                                .padding(vertical = 6.dp, horizontal = 4.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(city,
                                color = if (isSelected) Color.White else Color.White.copy(0.8f),
                                fontSize = 10.sp, textAlign = TextAlign.Center,
                                maxLines = 2, overflow = TextOverflow.Ellipsis,
                                lineHeight = 13.sp)
                        }
                    }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }

            // מפריד
            item {
                Box(Modifier.fillMaxWidth().height(1.dp)
                    .background(Color.White.copy(0.1f)))
            }
        }

        // כותרת רשימה קבועה
        item {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("רשימת ישובים (${displayList.size}/$MAX_DISPLAY)",
                    color = Color.White.copy(0.4f), fontSize = 9.sp)
                if (selected.isNotEmpty())
                    Text("✓ ${selected.size} נבחרו",
                        color = Color(0xFF00E676).copy(0.8f), fontSize = 9.sp,
                        fontWeight = FontWeight.Medium)
            }
        }

        // רשימת תצוגה קבועה – 30 ערים
        items(displayList.chunked(2).size) { i ->
            val row = displayList.chunked(2)[i]
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                row.forEach { city ->
                    val active = city in selected
                    Box(
                        Modifier.weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(
                                if (active) Color(0xFFCC0000).copy(0.85f)
                                else Color.White.copy(0.07f)
                            )
                            .clickable { toggleCity(city) }
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
                Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFFCC0000))
                    .clickable {
                        onSave(prefs.copy(
                            watchedCities = selected,
                            useGps = useGps
                        ))
                    }
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Text("שמור", color = Color.White, fontSize = 13.sp,
                    fontWeight = FontWeight.Bold)
            }
        }

        // כפתור כיבוי
        item {
            Box(
                Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.White.copy(0.08f))
                    .clickable { onStopService() }
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Text("כבה שירות", color = Color.White.copy(0.5f), fontSize = 12.sp)
            }
        }
    }
}


