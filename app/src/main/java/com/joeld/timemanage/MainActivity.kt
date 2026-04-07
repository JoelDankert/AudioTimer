package com.joeld.timemanage

import android.Manifest
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.NumberPicker
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.core.content.ContextCompat
import com.joeld.timemanage.ui.theme.TimemanageTheme
import java.util.Calendar
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    private var pendingTargetTimeMillis: Long? = null
    private var pendingLocationRefresh = false
    private var pendingRouteTracking = false
    private var routeLocationListener: LocationListener? = null
    private var lastRouteDistanceFetchAt = 0L
    private val speedSamples = mutableListOf<LocationSample>()
    private val timerPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            pendingTargetTimeMillis?.let(::startTimerNow)
            pendingTargetTimeMillis = null
        }
    private val locationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            if (grants.values.any { it } && pendingLocationRefresh) {
                pendingLocationRefresh = false
                refreshSelectedDistance()
                if (pendingRouteTracking) {
                    pendingRouteTracking = false
                    startRouteTracking()
                }
            } else {
                pendingLocationRefresh = false
                pendingRouteTracking = false
                LocationStore.setDistanceError("location denied")
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            TimemanageTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    TimerScreen(modifier = Modifier.padding(innerPadding))
                }
            }
        }
    }

    fun startTimerWithPermissionPrompt(targetTimeMillis: Long) {
        val missingPermissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !hasPermission(Manifest.permission.POST_NOTIFICATIONS)) {
            missingPermissions += Manifest.permission.POST_NOTIFICATIONS
        }
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            TimerStore.audioMode != AudioMode.None &&
            TimerStore.bluetoothFailsafeEnabled &&
            !hasPermission(Manifest.permission.BLUETOOTH_CONNECT)
        ) {
            missingPermissions += Manifest.permission.BLUETOOTH_CONNECT
        }

        if (missingPermissions.isEmpty()) {
            startTimerNow(targetTimeMillis)
        } else {
            pendingTargetTimeMillis = targetTimeMillis
            timerPermissionLauncher.launch(missingPermissions.toTypedArray())
        }
    }

    private fun hasPermission(permission: String): Boolean {
        return checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    }

    private fun startTimerNow(targetTimeMillis: Long) {
        TimerStore.markRouteEstimateTimer(false)
        startTimer(this, targetTimeMillis)
    }

    private fun startRouteEstimateTimer(targetTimeMillis: Long) {
        TimerStore.markRouteEstimateTimer(true)
        startTimer(this, targetTimeMillis, routeEstimateTimer = true)
    }

    fun startRouteTracking() {
        val selected = LocationStore.selectedLocation
        if (selected == null) {
            stopRouteTracking()
            return
        }
        if (!hasLocationPermission()) {
            pendingLocationRefresh = true
            pendingRouteTracking = true
            locationPermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
            return
        }

        stopRouteTracking(clearSpeed = false)
        speedSamples.clear()
        lastRouteDistanceFetchAt = 0L

        val locationManager = getSystemService(LocationManager::class.java)
        val provider = bestEnabledProvider(locationManager)
        if (provider == null) {
            LocationStore.setDistanceError("location unavailable")
            return
        }

        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                handleRouteLocation(location)
            }
        }
        routeLocationListener = listener

        @Suppress("MissingPermission")
        runCatching {
            locationManager.requestLocationUpdates(provider, 2_000L, 1f, listener, Looper.getMainLooper())
            locationManager.getLastKnownLocation(provider)?.let(::handleRouteLocation)
        }.onFailure {
            stopRouteTracking()
            LocationStore.setDistanceError("location unavailable")
        }
    }

    fun stopRouteTracking(clearSpeed: Boolean = true) {
        routeLocationListener?.let { listener ->
            runCatching { getSystemService(LocationManager::class.java).removeUpdates(listener) }
        }
        routeLocationListener = null
        speedSamples.clear()
        if (clearSpeed) {
            LocationStore.updateCurrentSpeedKmh(null)
        }
    }

    fun refreshSelectedDistance() {
        val selected = LocationStore.selectedLocation ?: return
        val mode = LocationStore.travelMode
        if (!hasLocationPermission()) {
            pendingLocationRefresh = true
            locationPermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
            return
        }

        LocationStore.setDistanceLoading()
        currentLocation { current ->
            if (current == null) {
                LocationStore.setDistanceError("location unavailable")
                return@currentLocation
            }

            thread {
                runCatching {
                    LocationApi.routeEstimate(
                        currentLatitude = current.latitude,
                        currentLongitude = current.longitude,
                        destination = selected,
                        mode = mode
                    )
                }.onSuccess { estimate ->
                    runOnUiThread {
                        if (LocationStore.selectedLocationId == selected.id && LocationStore.travelMode == mode) {
                            applyRouteEstimate(estimate)
                        }
                    }
                }.onFailure {
                    runOnUiThread {
                        LocationStore.setDistanceError("distance unavailable")
                    }
                }
            }
        }
    }

    private fun handleRouteLocation(location: Location) {
        val now = System.currentTimeMillis()
        speedSamples += LocationSample(now, location.latitude, location.longitude)
        speedSamples.removeAll { now - it.timeMillis > 5_000L }

        val oldest = speedSamples.firstOrNull()
        val newest = speedSamples.lastOrNull()
        if (oldest != null && newest != null && newest.timeMillis > oldest.timeMillis) {
            val distance = FloatArray(1)
            Location.distanceBetween(
                oldest.latitude,
                oldest.longitude,
                newest.latitude,
                newest.longitude,
                distance
            )
            val hours = (newest.timeMillis - oldest.timeMillis) / 3_600_000.0
            LocationStore.updateCurrentSpeedKmh(distance[0] / 1000.0 / hours)
        }

        val selected = LocationStore.selectedLocation ?: return
        val mode = LocationStore.travelMode
        if (now - lastRouteDistanceFetchAt < 15_000L) return
        lastRouteDistanceFetchAt = now

        thread {
            runCatching {
                LocationApi.routeEstimate(
                    currentLatitude = location.latitude,
                    currentLongitude = location.longitude,
                    destination = selected,
                    mode = mode
                )
            }.onSuccess { estimate ->
                runOnUiThread {
                    if (LocationStore.selectedLocationId == selected.id && LocationStore.travelMode == mode) {
                        applyRouteEstimate(estimate)
                    }
                }
            }
        }
    }

    private fun applyRouteEstimate(estimate: RouteEstimate) {
        if (estimate.distanceMeters <= 50) {
            LocationStore.clearSelection()
            stopRouteTracking()
            return
        }
        startTimerFromRouteEstimateIfIdle(estimate.durationSeconds)
        LocationStore.setDistance(estimate.distanceMeters)
    }

    private fun startTimerFromRouteEstimateIfIdle(durationSeconds: Int) {
        if (TimerStore.remainingMillis > 0L || durationSeconds <= 0) return
        startRouteEstimateTimer(System.currentTimeMillis() + durationSeconds * 1000L)
    }

    private fun hasLocationPermission(): Boolean {
        return hasPermission(Manifest.permission.ACCESS_FINE_LOCATION) ||
            hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
    }

    @Suppress("MissingPermission")
    private fun currentLocation(callback: (Location?) -> Unit) {
        val locationManager = getSystemService(LocationManager::class.java)
        val providers = enabledProviders(locationManager)

        val lastKnown = providers
            .mapNotNull { provider -> runCatching { locationManager.getLastKnownLocation(provider) }.getOrNull() }
            .maxByOrNull { it.time }
        if (lastKnown != null) {
            callback(lastKnown)
            return
        }

        val provider = providers.firstOrNull()
        if (provider == null) {
            callback(null)
            return
        }

        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                callback(location)
                locationManager.removeUpdates(this)
            }
        }
        runCatching {
            locationManager.requestSingleUpdate(provider, listener, Looper.getMainLooper())
        }.onFailure {
            callback(null)
        }
    }

    private fun bestEnabledProvider(locationManager: LocationManager): String? {
        return enabledProviders(locationManager).firstOrNull()
    }

    private fun enabledProviders(locationManager: LocationManager): List<String> {
        return listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            LocationManager.PASSIVE_PROVIDER
        ).filter { provider ->
            runCatching { locationManager.isProviderEnabled(provider) }.getOrDefault(false)
        }
    }

    override fun onDestroy() {
        stopRouteTracking()
        super.onDestroy()
    }

    private data class LocationSample(
        val timeMillis: Long,
        val latitude: Double,
        val longitude: Double
    )
}

@Composable
fun TimerScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val activity = context as? MainActivity
    var settingsOpen by remember { mutableStateOf(false) }
    var durationOpen by remember { mutableStateOf(false) }
    var locationEditorOpen by remember { mutableStateOf(false) }
    var editingLocation by remember { mutableStateOf<SavedLocation?>(null) }

    LaunchedEffect(LocationStore.selectedLocationId, LocationStore.travelMode) {
        if (LocationStore.selectedLocation != null) {
            activity?.refreshSelectedDistance()
            activity?.startRouteTracking()
        } else {
            activity?.stopRouteTracking()
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Top
    ) {
        Spacer(modifier = Modifier.height(48.dp))
        TimerHeader()
        LocationDistanceText()
        Spacer(modifier = Modifier.height(48.dp))
        Button(
            onClick = {
                stopTimer(context)
                LocationStore.clearSelection()
                activity?.stopRouteTracking()
            },
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
        ) {
            Text("stop")
        }
        Spacer(modifier = Modifier.height(12.dp))
        Button(onClick = { pickTimeOfDay(context) }) {
            Text("time of day")
        }
        Spacer(modifier = Modifier.height(12.dp))
        Button(onClick = { durationOpen = true }) {
            Text("absolute time")
        }
        Spacer(modifier = Modifier.height(20.dp))
        IconButton(onClick = { settingsOpen = true }) {
            Text(
                text = "⚙",
                fontSize = 28.sp,
                color = MaterialTheme.colorScheme.primary
            )
        }
        LocationSelector(
            onRefreshDistance = { activity?.refreshSelectedDistance() },
            onClearSelection = {
                if (TimerStore.routeEstimateTimerActive) {
                    stopTimer(context)
                    TimerStore.markRouteEstimateTimer(false)
                }
                LocationStore.clearSelection()
                activity?.stopRouteTracking()
            },
            onAddLocation = {
                editingLocation = null
                locationEditorOpen = true
            },
            onEditLocation = { location ->
                editingLocation = location
                locationEditorOpen = true
            }
        )
    }

    if (settingsOpen) {
        SettingsDialog(onDismiss = { settingsOpen = false })
    }
    if (durationOpen) {
        DurationDialog(
            onDismiss = { durationOpen = false },
            onStart = { durationMillis ->
                durationOpen = false
                startTimerWithPermissionPrompt(context, System.currentTimeMillis() + durationMillis)
            }
        )
    }
    if (locationEditorOpen) {
        LocationEditorDialog(
            location = editingLocation,
            onDismiss = { locationEditorOpen = false },
            onAdded = {
                locationEditorOpen = false
                activity?.refreshSelectedDistance()
            }
        )
    }
}

@Composable
private fun TimerHeader() {
    if (LocationStore.selectedLocation == null) {
        Text(
            text = formatRemaining(TimerStore.remainingMillis),
            fontSize = 48.sp,
            fontWeight = FontWeight.Light
        )
        return
    }

    val currentSpeed = LocationStore.currentSpeedKmh
    val targetSpeed = requiredSpeedKmh()
    val improvement = speedImprovementText(currentSpeed, targetSpeed)

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = improvement.orEmpty(),
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.secondary
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SpeedStat(
                text = formatSpeed(currentSpeed),
                modifier = Modifier.weight(1f)
            )
            Text(
                text = formatRemaining(TimerStore.remainingMillis),
                fontSize = 44.sp,
                fontWeight = FontWeight.Light
            )
            SpeedStat(
                text = formatSpeed(targetSpeed),
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun SpeedStat(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        modifier = modifier,
        fontSize = 13.sp,
        color = MaterialTheme.colorScheme.secondary,
        textAlign = TextAlign.Center
    )
}

@Composable
private fun LocationDistanceText() {
    val selected = LocationStore.selectedLocation
    val text = when {
        selected == null -> null
        LocationStore.distanceMeters != null -> "${LocationStore.distanceMeters} m"
        LocationStore.distanceStatus != null -> LocationStore.distanceStatus
        else -> "distance pending"
    }
    if (text != null) {
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = text,
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.secondary,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun LocationSelector(
    onRefreshDistance: () -> Unit,
    onClearSelection: () -> Unit,
    onAddLocation: () -> Unit,
    onEditLocation: (SavedLocation) -> Unit
) {
    val locationRowHeight = 40.dp
    val locationRowGap = 6.dp
    val visibleLocationRows = 4
    val locationListHeight = locationRowHeight * visibleLocationRows + locationRowGap * (visibleLocationRows - 1)

    Spacer(modifier = Modifier.height(8.dp))
    IconButton(onClick = { LocationStore.updateExpanded(!LocationStore.expanded) }) {
        CollapseArrow(expanded = LocationStore.expanded)
    }
    if (!LocationStore.expanded) return

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        TravelModeToggle(onRefreshDistance)
        Spacer(modifier = Modifier.width(10.dp))
        RoundAction(
            text = "×",
            background = MaterialTheme.colorScheme.error,
            onClick = onClearSelection
        )
    }

    Spacer(modifier = Modifier.height(14.dp))
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        LazyColumn(
            modifier = Modifier.height(locationListHeight),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(locationRowGap)
        ) {
            items(LocationStore.locations, key = { it.id }) { location ->
                LocationButton(
                    location = location,
                    selected = LocationStore.selectedLocationId == location.id,
                    onClick = {
                        LocationStore.selectLocation(location)
                        onRefreshDistance()
                    },
                    onLongClick = { onEditLocation(location) },
                    modifier = Modifier.height(locationRowHeight)
                )
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        RoundAction(
            text = "+",
            background = MaterialTheme.colorScheme.primary,
            onClick = onAddLocation
        )
    }
}

@Composable
private fun CollapseArrow(expanded: Boolean) {
    val color = MaterialTheme.colorScheme.primary
    Canvas(modifier = Modifier.size(28.dp)) {
        val strokeWidth = 2.5.dp.toPx()
        val centerY = size.height / 2f
        val leftX = size.width * 0.28f
        val centerX = size.width / 2f
        val rightX = size.width * 0.72f
        val topY = centerY - size.height * 0.14f
        val bottomY = centerY + size.height * 0.14f
        if (expanded) {
            drawLine(color, start = androidx.compose.ui.geometry.Offset(leftX, bottomY), end = androidx.compose.ui.geometry.Offset(centerX, topY), strokeWidth = strokeWidth, cap = StrokeCap.Round)
            drawLine(color, start = androidx.compose.ui.geometry.Offset(centerX, topY), end = androidx.compose.ui.geometry.Offset(rightX, bottomY), strokeWidth = strokeWidth, cap = StrokeCap.Round)
        } else {
            drawLine(color, start = androidx.compose.ui.geometry.Offset(leftX, topY), end = androidx.compose.ui.geometry.Offset(centerX, bottomY), strokeWidth = strokeWidth, cap = StrokeCap.Round)
            drawLine(color, start = androidx.compose.ui.geometry.Offset(centerX, bottomY), end = androidx.compose.ui.geometry.Offset(rightX, topY), strokeWidth = strokeWidth, cap = StrokeCap.Round)
        }
    }
}

@Composable
private fun TravelModeToggle(onRefreshDistance: () -> Unit) {
    Row(
        modifier = Modifier
            .width(160.dp)
            .height(40.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        verticalAlignment = Alignment.CenterVertically
    ) {
        TravelModeSegment(
            text = "walk",
            selected = LocationStore.travelMode == TravelMode.Walk,
            modifier = Modifier.weight(1f),
            onClick = {
                LocationStore.updateTravelMode(TravelMode.Walk)
                onRefreshDistance()
            }
        )
        TravelModeSegment(
            text = "car",
            selected = LocationStore.travelMode == TravelMode.Car,
            modifier = Modifier.weight(1f),
            onClick = {
                LocationStore.updateTravelMode(TravelMode.Car)
                onRefreshDistance()
            }
        )
    }
}

@Composable
private fun TravelModeSegment(
    text: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) MaterialTheme.colorScheme.primary else Color.Transparent)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(text = text, color = Color.Black)
    }
}

@Composable
private fun RoundAction(text: String, background: Color, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(background)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(text = text, color = Color.Black, fontSize = 24.sp)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LocationButton(
    location: SavedLocation,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = location.name,
            color = if (selected) Color.Black else MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun LocationEditorDialog(location: SavedLocation?, onDismiss: () -> Unit, onAdded: () -> Unit) {
    var name by remember(location) { mutableStateOf(location?.name ?: "") }
    var locationInput by remember(location) { mutableStateOf(location?.location ?: "") }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val activity = LocalContext.current as? MainActivity

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank() && locationInput.isNotBlank() && !loading,
                onClick = {
                    loading = true
                    error = null
                    val cleanName = name.trim()
                    val cleanLocation = locationInput.trim()
                    thread {
                        runCatching {
                            LocationApi.createLocation(
                                name = cleanName,
                                location = cleanLocation,
                                id = location?.id ?: System.currentTimeMillis()
                            )
                        }.onSuccess { savedLocation ->
                            activity?.runOnUiThread {
                                loading = false
                                if (savedLocation == null) {
                                    error = "not found"
                                } else {
                                    if (location == null) {
                                        LocationStore.addLocation(savedLocation)
                                    } else {
                                        LocationStore.updateLocation(savedLocation)
                                    }
                                    onAdded()
                                }
                            }
                        }.onFailure {
                            activity?.runOnUiThread {
                                loading = false
                                error = "lookup failed"
                            }
                        }
                    }
                }
            ) {
                Text(if (loading) "saving" else "save")
            }
        },
        dismissButton = {
            Row {
                if (location != null) {
                    TextButton(
                        onClick = {
                            LocationStore.deleteLocation(location)
                            onDismiss()
                        }
                    ) {
                        Text("delete", color = MaterialTheme.colorScheme.error)
                    }
                }
                TextButton(onClick = onDismiss) {
                    Text("cancel")
                }
            }
        },
        title = { Text("location") },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    label = { Text("name") }
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = locationInput,
                    onValueChange = { locationInput = it },
                    singleLine = true,
                    label = { Text("location") }
                )
                if (error != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(error ?: "", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    )
}

@Composable
private fun SettingsDialog(onDismiss: () -> Unit) {
    var routeIntervalText by remember { mutableStateOf(TimerStore.routeInfoIntervalSeconds.toString()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("done")
            }
        },
        title = { Text("settings") },
        text = {
            Column {
                SettingsSectionTitle("audio")
                AudioMode.entries.forEach { mode ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(
                            selected = TimerStore.audioMode == mode,
                            onClick = { TimerStore.updateAudioMode(mode) }
                        )
                        Text(mode.label)
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = TimerStore.bluetoothFailsafeEnabled,
                        onCheckedChange = { TimerStore.updateBluetoothFailsafe(it) }
                    )
                    Text("mute if bluetooth disconnects")
                }
                Spacer(modifier = Modifier.height(12.dp))
                SettingsSectionTitle("route info")
                OutlinedTextField(
                    value = routeIntervalText,
                    onValueChange = { value ->
                        routeIntervalText = value.filter(Char::isDigit).take(4)
                        routeIntervalText.toIntOrNull()?.let(TimerStore::updateRouteInfoIntervalSeconds)
                    },
                    singleLine = true,
                    label = { Text("seconds") }
                )
                RouteInfoPart.entries.forEach { part ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = TimerStore.routeInfoParts.contains(part),
                            onCheckedChange = { TimerStore.updateRouteInfoPart(part, it) }
                        )
                        Text(part.label)
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = TimerStore.relativeOnlyIfPositive,
                        onCheckedChange = { TimerStore.updateRelativeOnlyIfPositive(it) }
                    )
                    Text("relative only if positive")
                }
            }
        }
    )
}

@Composable
private fun SettingsSectionTitle(text: String) {
    Text(
        text = text,
        modifier = Modifier.fillMaxWidth(),
        textAlign = TextAlign.Center
    )
}

@Composable
private fun DurationDialog(onDismiss: () -> Unit, onStart: (Long) -> Unit) {
    var hours by remember { mutableStateOf(0) }
    var minutes by remember { mutableStateOf(5) }
    val durationMillis = (hours * 60L + minutes) * 60_000L

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = { onStart(durationMillis) },
                enabled = durationMillis > 0L
            ) {
                Text("start")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("cancel")
            }
        },
        title = { Text("absolute time") },
        text = {
            DurationPickers(
                hours = hours,
                minutes = minutes,
                onHoursChange = { hours = it },
                onMinutesChange = { minutes = it }
            )
        }
    )
}

@Composable
private fun DurationPickers(
    hours: Int,
    minutes: Int,
    onHoursChange: (Int) -> Unit,
    onMinutesChange: (Int) -> Unit
) {
    AndroidView(
        modifier = Modifier.fillMaxWidth(),
        factory = { context ->
            fun pickerSlot(picker: NumberPicker, label: String): LinearLayout {
                return LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    addView(
                        picker,
                        LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                        )
                    )
                    addView(
                        android.widget.TextView(context).apply {
                            text = label
                            setTextColor(android.graphics.Color.WHITE)
                            textSize = 14f
                        },
                        LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                        )
                    )
                }
            }

            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                addView(
                    pickerSlot(
                        NumberPicker(context).apply {
                            minValue = 0
                            maxValue = 23
                            value = hours
                            setOnValueChangedListener { _, _, newValue -> onHoursChange(newValue) }
                        },
                        " h"
                    )
                )
                addView(
                    pickerSlot(
                        NumberPicker(context).apply {
                            minValue = 0
                            maxValue = 59
                            value = minutes
                            setOnValueChangedListener { _, _, newValue -> onMinutesChange(newValue) }
                        },
                        " min"
                    )
                )
            }
        },
        update = { layout ->
            ((layout.getChildAt(0) as LinearLayout).getChildAt(0) as NumberPicker).value = hours
            ((layout.getChildAt(1) as LinearLayout).getChildAt(0) as NumberPicker).value = minutes
        }
    )
}

private fun pickTimeOfDay(context: Context) {
    val now = Calendar.getInstance()
    TimePickerDialog(
        context,
        { _, hour, minute ->
            val target = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, hour)
                set(Calendar.MINUTE, minute)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
                if (timeInMillis <= System.currentTimeMillis()) {
                    add(Calendar.DAY_OF_MONTH, 1)
                }
            }
            startTimerWithPermissionPrompt(context, target.timeInMillis)
        },
        now.get(Calendar.HOUR_OF_DAY),
        now.get(Calendar.MINUTE),
        true
    ).show()
}

private fun startTimerWithPermissionPrompt(context: Context, targetTimeMillis: Long) {
    val activity = context as? MainActivity ?: return
    activity.startTimerWithPermissionPrompt(targetTimeMillis)
}

private fun startTimer(context: Context, targetTimeMillis: Long, routeEstimateTimer: Boolean = false) {
    val intent = Intent(context, TimerService::class.java)
        .setAction(TimerService.ACTION_START)
        .putExtra(TimerService.EXTRA_TARGET_TIME_MILLIS, targetTimeMillis)
        .putExtra(TimerService.EXTRA_AUDIO_MODE, TimerStore.audioMode.name)
        .putExtra(TimerService.EXTRA_ROUTE_ESTIMATE_TIMER, routeEstimateTimer)
    ContextCompat.startForegroundService(context, intent)
}

private fun stopTimer(context: Context) {
    context.startService(
        Intent(context, TimerService::class.java)
            .setAction(TimerService.ACTION_STOP)
    )
}

private fun requiredSpeedKmh(): Double? {
    val distanceMeters = LocationStore.distanceMeters ?: return null
    val remainingMillis = TimerStore.remainingMillis
    if (remainingMillis <= 0L) return null
    return distanceMeters / 1000.0 / (remainingMillis / 3_600_000.0)
}

private fun speedImprovementText(currentSpeed: Double?, targetSpeed: Double?): String? {
    if (targetSpeed == null) return null
    if (currentSpeed == null || currentSpeed <= 0.1) {
        return if (targetSpeed <= 0.1) "0%" else "+∞"
    }
    val percent = ((targetSpeed / currentSpeed) - 1.0) * 100.0
    val rounded = percent.roundToInt()
    return if (rounded > 0) "+$rounded%" else "$rounded%"
}

private fun formatSpeed(speed: Double?): String {
    return if (speed == null) "" else "${speed.roundToInt()} km/h"
}

private fun formatRemaining(millis: Long): String {
    val safeMillis = millis.coerceAtLeast(0L)
    val hours = TimeUnit.MILLISECONDS.toHours(safeMillis)
    val minutes = TimeUnit.MILLISECONDS.toMinutes(safeMillis) % 60
    val seconds = TimeUnit.MILLISECONDS.toSeconds(safeMillis) % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%02d:%02d".format(minutes, seconds)
    }
}

@Preview(showBackground = true)
@Composable
fun TimerScreenPreview() {
    TimemanageTheme {
        TimerScreen()
    }
}
