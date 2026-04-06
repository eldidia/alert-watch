package com.alert.watch

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Geocoder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import androidx.wear.compose.material.CircularProgressIndicator
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.ScalingLazyColumn
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.items
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

// ── Data models ───────────────────────────────────────────────────

enum class Screen { STANDBY, ALERT, SETTINGS }

data class AlertState(
    val active:      Boolean      = false,
    val cities:      List<String> = emptyList(),
    val threat:      String       = "1",
    val countdown:   Int          = 0,
    val triggeredAt: Long         = 0L
)

data class AppPrefs(
    val watchedCities: Set<String> = emptySet(),
    val gpsCity:       String?     = null,
    val useGps:        Boolean     = false
)

// ── MainActivity ──────────────────────────────────────────────────

class MainActivity : ComponentActivity() {

    private val TAG = "MainActivity"

    private val prefs           by lazy { AppPreferences(this) }
    private var batteryOptAsked = false

    // Permission launchers
    private val notifPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) AlertService.start(this)
    }

    private val locationPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.values.any { it }) detectAndSaveGpsCity()
    }

    // ── Lifecycle ─────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val alert    by AlertService.alertState.collectAsState()
            val appPrefs by prefs.state.collectAsState()
            var screen   by remember { mutableStateOf(Screen.STANDBY) }
            val allCities by AlertService.allCitiesFlow.collectAsState()

            // Navigate to alert screen when alert becomes active
            LaunchedEffect(alert.active) {
                if (alert.active) screen = Screen.ALERT
            }

            MaterialTheme {
                when (screen) {
                    Screen.STANDBY  -> StandbyScreen(
                        prefs      = appPrefs,
                        onSettings = { screen = Screen.SETTINGS }
                    )
                    Screen.ALERT    -> AlertScreen(
                        alert     = alert,
                        onDismiss = {
                            AlertService.alertState.value = AlertState()
                            screen = Screen.STANDBY
                        }
                    )
                    Screen.SETTINGS -> SettingsScreen(
                        prefs         = appPrefs,
                        allCities     = allCities,  // ← הוסף פרמטר
                        onRequestGps  = { requestLocationPermission() },
                        onBack        = { screen = Screen.STANDBY },
                        onSave        = { updated ->
                            prefs.save(updated)
                            screen = Screen.STANDBY
                        },
                        onStopService = {
                            AlertService.stop(this)
                            finishAffinity()
                        }
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        startServiceWithPermission()
        AlertService.syncCities(this)
        requestIgnoreBatteryOptimizations()
    }

    // ── Service startup ───────────────────────────────────────────

    private fun startServiceWithPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED) {
            notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            AlertService.start(this)
        }
    }

    // ── Battery optimization ──────────────────────────────────────

    private fun requestIgnoreBatteryOptimizations() {
        if (batteryOptAsked) return
        val pm = getSystemService(PowerManager::class.java)
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            batteryOptAsked = true
            runCatching {
                startActivity(Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:$packageName")
                ))
            }
        }
    }

    // ── GPS ───────────────────────────────────────────────────────

    private fun requestLocationPermission() {
        val fine = Manifest.permission.ACCESS_FINE_LOCATION
        if (ContextCompat.checkSelfPermission(this, fine) == PackageManager.PERMISSION_GRANTED) {
            detectAndSaveGpsCity()
        } else {
            locationPermLauncher.launch(arrayOf(
                fine,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ))
        }
    }

    private fun detectAndSaveGpsCity() {
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching {
                val client = LocationServices.getFusedLocationProviderClient(this@MainActivity)
                val location = client.getCurrentLocation(
                    Priority.PRIORITY_BALANCED_POWER_ACCURACY,
                    CancellationTokenSource().token
                ).await() ?: return@launch

                @Suppress("DEPRECATION")
                val addresses = Geocoder(this@MainActivity)
                    .getFromLocation(location.latitude, location.longitude, 1)

                val locality = addresses?.firstOrNull()?.locality
                    ?: addresses?.firstOrNull()?.subAdminArea
                    ?: return@launch

                // Match against the official Pikud HaOref city list
                val source  = AlertService.allCities
                val matched = source.firstOrNull {
                    it.contains(locality, ignoreCase = true) ||
                    locality.contains(it.take(4), ignoreCase = true)
                } ?: locality

                val current = prefs.state.value
                prefs.save(current.copy(gpsCity = matched, useGps = true))
                Log.d(TAG, "GPS city detected: $matched")

            }.onFailure { Log.w(TAG, "GPS detection failed: ${it.message}") }
        }
    }
}

// ── Preferences ───────────────────────────────────────────────────

class AppPreferences(ctx: Context) {

    private val sp     = ctx.getSharedPreferences("alert_prefs", Context.MODE_PRIVATE)
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
        watchedCities = sp.getStringSet("cities",   emptySet()) ?: emptySet(),
        gpsCity       = sp.getString("gps_city",    null),
        useGps        = sp.getBoolean("use_gps",    false)
    )
}

// ── Threat instruction mapping ────────────────────────────────────

fun threatToInstruction(threat: String): String = when (threat) {
    "1", "2", "13" -> "היכנס למרחב מוגן"
    "3"             -> "נעל דלתות · שכב על הרצפה"
    "4"             -> "עמוד בפתח דלת"
    "6"             -> "התרחק מהחוף"
    else            -> "היכנס למרחב מוגן"
}

// ── Standby Screen ────────────────────────────────────────────────

@Composable
fun StandbyScreen(prefs: AppPrefs, onSettings: () -> Unit) {
    val cityCount = prefs.watchedCities.size +
        if (prefs.useGps && prefs.gpsCity != null) 1 else 0

    val inf   = rememberInfiniteTransition(label = "standby_pulse")
    val scale by inf.animateFloat(
        initialValue   = 1f,
        targetValue    = 1.5f,
        animationSpec  = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label          = "dot_scale"
    )

    Box(
        modifier            = Modifier.fillMaxSize().background(Color(0xFF0A0A0A))
                                      .clickable { onSettings() },
        contentAlignment    = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            // Status dot
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .scale(scale)
                    .clip(CircleShape)
                    .background(
                        if (cityCount > 0) Color(0xFF00E676) else Color(0xFFFFAB00)
                    )
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text       = "ALERT",
                color      = Color.White,
                fontSize   = 16.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 2.sp
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text      = if (cityCount == 0) "לחץ לבחירת ישוב"
                            else "$cityCount ישובים · פעיל",
                color     = Color.White.copy(alpha = 0.45f),
                fontSize  = 11.sp,
                textAlign = TextAlign.Center
            )
            if (prefs.useGps && prefs.gpsCity != null) {
                Spacer(Modifier.height(3.dp))
                Text(
                    text     = "📍 ${prefs.gpsCity}",
                    color    = Color(0xFF00E676).copy(alpha = 0.7f),
                    fontSize = 10.sp
                )
            }
        }
    }
}

// ── Alert Screen ──────────────────────────────────────────────────

@Composable
fun AlertScreen(alert: AlertState, onDismiss: () -> Unit) {
    var secsLeft by remember { mutableIntStateOf(alert.countdown) }

    LaunchedEffect(alert.triggeredAt) {
        secsLeft = alert.countdown
        while (secsLeft > 0) {
            delay(1_000L)
            secsLeft--
        }
    }

    val inf     = rememberInfiniteTransition(label = "alert_bg")
    val bgAlpha by inf.animateFloat(
        initialValue  = 0.82f,
        targetValue   = 1f,
        animationSpec = infiniteRepeatable(tween(450), RepeatMode.Reverse),
        label         = "bg_alpha"
    )

    // Allow hardware back button to dismiss
    BackHandler { onDismiss() }

    Box(
        modifier         = Modifier
            .fillMaxSize()
            .background(Color(0xFFBB0000).copy(alpha = bgAlpha))
            .clickable { onDismiss() },
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier            = Modifier.padding(10.dp)
        ) {
            Text(text = "🚨", fontSize = 26.sp)
            Spacer(Modifier.height(2.dp))
            Text(
                text          = "ALERT",
                color         = Color.White,
                fontSize      = 15.sp,
                fontWeight    = FontWeight.Black,
                letterSpacing = 2.sp
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text      = alert.cities.take(3).joinToString(" · "),
                color     = Color.White.copy(alpha = 0.9f),
                fontSize  = 11.sp,
                textAlign = TextAlign.Center,
                maxLines  = 2,
                overflow  = TextOverflow.Ellipsis,
                modifier  = Modifier.padding(horizontal = 6.dp)
            )
            if (alert.countdown > 0) {
                Spacer(Modifier.height(6.dp))
                CountdownRing(secsLeft = secsLeft, total = alert.countdown)
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text      = threatToInstruction(alert.threat),
                color     = Color.White,
                fontSize  = 10.sp,
                textAlign = TextAlign.Center,
                maxLines  = 2,
                lineHeight = 14.sp
            )
            Spacer(Modifier.height(5.dp))
            Text(
                text    = "לחץ לסגור",
                color   = Color.White.copy(alpha = 0.3f),
                fontSize = 9.sp
            )
        }
    }
}

@Composable
fun CountdownRing(secsLeft: Int, total: Int) {
    val progress = if (total > 0) secsLeft.toFloat() / total.toFloat() else 0f
    Box(contentAlignment = Alignment.Center) {
        CircularProgressIndicator(
            progress       = progress,
            modifier       = Modifier.size(52.dp),
            strokeWidth    = 3.dp,
            indicatorColor = Color.White,
            trackColor     = Color.White.copy(alpha = 0.15f)
        )
        Text(
            text       = secsLeft.toString(),
            color      = Color.White,
            fontSize   = 19.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

// ── Settings Screen ───────────────────────────────────────────────

private const val MAX_DISPLAY_CITIES = 30

@Composable
fun SettingsScreen(
    prefs: AppPrefs,
    allCities: List<String>,  // ← מגיע מ-MainActivity
    onRequestGps: () -> Unit,
    onBack: () -> Unit,
    onSave: (AppPrefs) -> Unit,
    onStopService: () -> Unit
) {
    var selected  by remember { mutableStateOf(prefs.watchedCities.toMutableSet()) }
    var useGps    by remember { mutableStateOf(prefs.useGps) }
    var query     by remember { mutableStateOf("") }

    // Reactive city source – updates when allCities is populated after sync
    val searchSource = allCities.distinctBy { it }
    }

    // Display list: selected first, then fill to MAX_DISPLAY_CITIES from source
    var displayList by remember {
        val sel  = searchSource.filter { it in prefs.watchedCities }
        val rest = searchSource.filter { it !in prefs.watchedCities }
            .take(MAX_DISPLAY_CITIES - sel.size)
        mutableStateOf((sel + rest).toMutableList())
    }

    // Smart search: matches any word fragment across multiple words
    val searchResults by remember(query) {
        derivedStateOf {
            val q = query.trim()
            if (q.length < 2) return@derivedStateOf emptyList()
            val words = q.split(" ", "־", "-").filter { it.length >= 2 }
            searchSource.filter { city ->
                words.all { word -> city.contains(word, ignoreCase = true) } ||
                city.contains(q, ignoreCase = true)
            }.take(15)
        }
    }

    val showSearch = searchResults.isNotEmpty() && query.length >= 2

    // Add a city from search results into the display list
    fun addCityFromSearch(city: String) {
        if (city in displayList) {
            selected = selected.toMutableSet().also { it.add(city) }
            query    = ""
            return
        }
        val updated = displayList.toMutableList()
        if (updated.size >= MAX_DISPLAY_CITIES) {
            // Remove the last unselected city to make room
            val evict = updated.lastOrNull { it !in selected } ?: return
            updated.remove(evict)
        }
        // Insert after currently selected items
        val insertAt = updated.count { it in selected }.coerceAtMost(updated.size)
        updated.add(insertAt, city)
        displayList = updated
        selected    = selected.toMutableSet().also { it.add(city) }
        query       = ""
    }

    fun toggleCity(city: String) {
        selected = selected.toMutableSet().also {
            if (city in it) it.remove(city) else it.add(city)
        }
    }

    BackHandler { onBack() }

    ScalingLazyColumn(
        modifier        = Modifier.fillMaxSize().background(Color(0xFF0A0A0A)),
        contentPadding  = PaddingValues(top = 26.dp, bottom = 24.dp, start = 8.dp, end = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {

        // ── Title ─────────────────────────────────────────────────
        item {
            Text(
                text       = "ALERT · ישובים",
                color      = Color.White,
                fontSize   = 13.sp,
                fontWeight = FontWeight.Bold,
                modifier   = Modifier.fillMaxWidth(),
                textAlign  = TextAlign.Center
            )
        }

        // ── GPS toggle ────────────────────────────────────────────
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(
                        if (useGps) Color(0xFF00E676).copy(0.12f)
                        else Color.White.copy(0.06f)
                    )
                    .clickable { useGps = !useGps; if (useGps) onRequestGps() }
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment    = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        text       = "📍 GPS אוטומטי",
                        color      = Color.White,
                        fontSize   = 11.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text     = prefs.gpsCity ?: "מזהה ישוב קרוב",
                        color    = if (prefs.gpsCity != null) Color(0xFF00E676).copy(0.8f)
                                   else Color.White.copy(0.4f),
                        fontSize = 10.sp
                    )
                }
                Box(
                    modifier = Modifier
                        .size(14.dp)
                        .clip(CircleShape)
                        .background(
                            if (useGps) Color(0xFF00E676)
                            else Color.White.copy(0.2f)
                        )
                )
            }
        }

        // ── Search field ──────────────────────────────────────────
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color.White.copy(if (query.isNotEmpty()) 0.12f else 0.08f))
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(text = "🔍 ", fontSize = 12.sp)
                BasicTextField(
                    value         = query,
                    onValueChange = { query = it },
                    textStyle     = TextStyle(color = Color.White, fontSize = 11.sp),
                    cursorBrush   = SolidColor(Color.White),
                    singleLine    = true,
                    modifier      = Modifier.weight(1f),
                    decorationBox = { inner ->
                        if (query.isEmpty()) {
                            Text(
                                text     = "חפש ישוב...",
                                color    = Color.White.copy(0.35f),
                                fontSize = 11.sp
                            )
                        }
                        inner()
                    }
                )
                if (query.isNotEmpty()) {
                    Text(
                        text     = "✕",
                        color    = Color.White.copy(0.5f),
                        fontSize = 12.sp,
                        modifier = Modifier
                            .clickable { query = "" }
                            .padding(start = 6.dp)
                    )
                }
            }
        }

        // ── Search results ────────────────────────────────────────
        if (showSearch) {
            item {
                Text(
                    text     = "תוצאות:",
                    color    = Color(0xFFFFAB00).copy(0.8f),
                    fontSize = 10.sp,
                    modifier = Modifier.padding(start = 4.dp)
                )
            }
            items(searchResults.chunked(2)) { row ->
                Row(
                    modifier              = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    row.forEach { city ->
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .background(
                                    when {
                                        city in selected    -> Color(0xFFCC0000).copy(0.85f)
                                        city in displayList -> Color.White.copy(0.12f)
                                        else                -> Color(0xFFFFAB00).copy(0.15f)
                                    }
                                )
                                .clickable { addCityFromSearch(city) }
                                .padding(vertical = 6.dp, horizontal = 4.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text       = city,
                                color      = if (city in selected) Color.White
                                             else Color.White.copy(0.8f),
                                fontSize   = 10.sp,
                                textAlign  = TextAlign.Center,
                                maxLines   = 2,
                                overflow   = TextOverflow.Ellipsis,
                                lineHeight = 13.sp
                            )
                        }
                    }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(Color.White.copy(0.1f))
                )
            }
        }

        // ── Display list header ───────────────────────────────────
        item {
            Row(
                modifier              = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment     = Alignment.CenterVertically
            ) {
                Text(
                    text     = "ישובים (${displayList.size}/$MAX_DISPLAY_CITIES)",
                    color    = Color.White.copy(0.4f),
                    fontSize = 9.sp
                )
                if (selected.isNotEmpty()) {
                    Text(
                        text       = "✓ ${selected.size} נבחרו",
                        color      = Color(0xFF00E676).copy(0.8f),
                        fontSize   = 9.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }

        // ── Display list ──────────────────────────────────────────
        items(displayList.chunked(2)) { row ->
            Row(
                modifier              = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                row.forEach { city ->
                    val active = city in selected
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(
                                if (active) Color(0xFFCC0000).copy(0.85f)
                                else Color.White.copy(0.07f)
                            )
                            .clickable { toggleCity(city) }
                            .padding(vertical = 6.dp, horizontal = 4.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text       = city,
                            color      = if (active) Color.White else Color.White.copy(0.6f),
                            fontSize   = 10.sp,
                            textAlign  = TextAlign.Center,
                            maxLines   = 2,
                            overflow   = TextOverflow.Ellipsis,
                            lineHeight = 13.sp
                        )
                    }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }

        // ── Save button ───────────────────────────────────────────
        item {
            Spacer(Modifier.height(4.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFFCC0000))
                    .clickable {
                        onSave(prefs.copy(watchedCities = selected, useGps = useGps))
                    }
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text       = "שמור",
                    color      = Color.White,
                    fontSize   = 13.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        // ── Stop service button ───────────────────────────────────
        item {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.White.copy(0.08f))
                    .clickable { onStopService() }
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text     = "כבה שירות",
                    color    = Color.White.copy(0.5f),
                    fontSize = 12.sp
                )
            }
        }
    }
}
