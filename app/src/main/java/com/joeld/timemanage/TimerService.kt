package com.joeld.timemanage

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.tts.TextToSpeech
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.math.roundToInt

class TimerService : Service(), TextToSpeech.OnInitListener {
    private val handler = Handler(Looper.getMainLooper())
    private var targetTimeMillis = 0L
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var soundDisabledForSession = false
    private var soundSessionStarted = false
    private var sessionBluetoothDevices = emptySet<String>()
    private var lastSpokenMinute: Long? = null
    private var lastRouteInfoSpokenAt = 0L
    private var routeLocationListener: LocationListener? = null
    private var lastRouteDistanceFetchAt = 0L
    private var finalStopScheduled = false
    private var smoothedSpeedKmh: Double? = null
    private val speedSamples = mutableListOf<LocationSample>()
    private val finalStop = Runnable { stopTimer() }
    private val routeInfoTick = object : Runnable {
        override fun run() {
            if (!LocationStore.routeActive) return
            maybeSpeakRouteInfo(TimerStore.remainingMillis)
            handler.postDelayed(this, ROUTE_INFO_TICK_MS)
        }
    }

    private val tick = object : Runnable {
        override fun run() {
            val remaining = (targetTimeMillis - System.currentTimeMillis()).coerceAtLeast(0L)
            TimerStore.setRemaining(remaining)
            updateNotification(remaining)
            maybeSpeak(remaining)

            if (remaining == 0L) {
                finishTimer()
            } else {
                handler.postDelayed(this, 1000L)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel()
        tts = TextToSpeech(this, this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            TimerStore.markRouteEstimateTimer(false)
            LocationStore.clearSelection()
            stopRouteTracking(notify = false)
            stopTimer()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_ROUTE_START) {
            if (!startRouteForeground()) {
                stopSelf()
                return START_NOT_STICKY
            }
            beginSoundSessionIfNeeded()
            startRouteTracking()
            startRouteInfoLoop()
            return START_STICKY
        }
        if (intent?.action == ACTION_ROUTE_STOP) {
            LocationStore.clearSelection()
            stopRouteInfoLoop()
            stopRouteTracking(notify = false)
            return if (TimerStore.remainingMillis > 0L || LocationStore.routeActive) {
                updateNotification(TimerStore.remainingMillis)
                START_STICKY
            } else {
                stopForegroundNotification()
                resetSoundSession()
                stopSelf()
                START_NOT_STICKY
            }
        }
        if (intent?.action == ACTION_MUTE) {
            muteTimer()
            return START_STICKY
        }
        if (intent?.action == ACTION_UNMUTE) {
            unmuteTimer()
            return START_STICKY
        }
        if (intent?.action != ACTION_START) return START_NOT_STICKY

        targetTimeMillis = intent.getLongExtra(EXTRA_TARGET_TIME_MILLIS, 0L)
        if (!intent.getBooleanExtra(EXTRA_ROUTE_ESTIMATE_TIMER, false)) {
            TimerStore.markRouteEstimateTimer(false)
        }
        val requestedAudioMode = intent.getStringExtra(EXTRA_AUDIO_MODE)
            ?.let { name -> AudioMode.entries.firstOrNull { it.name == name } }
            ?: TimerStore.audioMode
        TimerStore.updateAudioMode(requestedAudioMode)
        beginSoundSession(reset = true)
        finalStopScheduled = false
        handler.removeCallbacks(finalStop)
        val initialRemainingMillis = targetTimeMillis - System.currentTimeMillis()
        lastSpokenMinute = if (startsOnMinuteBoundary(initialRemainingMillis)) {
            null
        } else {
            spokenMinuteBucket(initialRemainingMillis)
        }
        lastRouteInfoSpokenAt = 0L

        startTimerForeground(initialRemainingMillis)
        handler.removeCallbacks(tick)
        tick.run()
        if (LocationStore.routeActive) {
            startRouteTracking()
            startRouteInfoLoop()
        }
        return START_STICKY
    }

    override fun onInit(status: Int) {
        ttsReady = status == TextToSpeech.SUCCESS
        if (ttsReady) {
            tts?.language = Locale.ENGLISH
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        handler.removeCallbacks(tick)
        stopRouteInfoLoop()
        stopRouteTracking(notify = false)
        tts?.stop()
        tts?.shutdown()
        tts = null
        ttsReady = false
        super.onDestroy()
    }

    private fun startTimerForeground(remainingMillis: Long) {
        startForegroundForCurrentState(remainingMillis, includeLocation = false)
    }

    private fun startRouteForeground(): Boolean {
        if (!hasLocationPermission()) {
            LocationStore.setDistanceError("location denied")
            updateNotification(TimerStore.remainingMillis)
            return false
        }
        startForegroundForCurrentState(TimerStore.remainingMillis, includeLocation = true)
        updateNotification(TimerStore.remainingMillis)
        return true
    }

    private fun startForegroundForCurrentState(remainingMillis: Long, includeLocation: Boolean) {
        val notification = buildNotification(remainingMillis)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, foregroundServiceType(includeLocation))
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun foregroundServiceType(includeLocation: Boolean): Int {
        val baseType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        return if (includeLocation && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            baseType or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        } else {
            baseType
        }
    }

    @Suppress("MissingPermission")
    private fun startRouteTracking() {
        if (!LocationStore.routeActive) {
            stopRouteTracking()
            return
        }
        if (!hasLocationPermission()) {
            LocationStore.setDistanceError("location denied")
            return
        }

        stopRouteTracking(clearSpeed = false)
        speedSamples.clear()
        smoothedSpeedKmh = null
        lastRouteDistanceFetchAt = 0L

        val locationManager = getSystemService(LocationManager::class.java)
        val provider = bestEnabledProvider(locationManager)
        if (provider == null) {
            LocationStore.setDistanceError("location unavailable")
            updateNotification(TimerStore.remainingMillis)
            return
        }

        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                handleRouteLocation(location)
            }
        }
        routeLocationListener = listener

        runCatching {
            LocationStore.selectedLocation?.let { LocationStore.setDistanceLoading() }
            locationManager.requestLocationUpdates(provider, 2_000L, 1f, listener, Looper.getMainLooper())
            locationManager.getLastKnownLocation(provider)?.let(::handleRouteLocation)
        }.onFailure {
            stopRouteTracking()
            LocationStore.setDistanceError("location unavailable")
            updateNotification(TimerStore.remainingMillis)
        }
    }

    private fun stopRouteTracking(clearSpeed: Boolean = true, notify: Boolean = true) {
        routeLocationListener?.let { listener ->
            runCatching { getSystemService(LocationManager::class.java).removeUpdates(listener) }
        }
        routeLocationListener = null
        speedSamples.clear()
        smoothedSpeedKmh = null
        if (clearSpeed) {
            LocationStore.updateCurrentSpeedKmh(null)
        }
        if (notify) {
            updateNotification(TimerStore.remainingMillis)
        }
    }

    private fun handleRouteLocation(location: Location) {
        val now = System.currentTimeMillis()
        if (location.hasAccuracy() && location.accuracy > 25f) return

        val nativeSpeedKmh = if (location.hasSpeed()) location.speed * 3.6 else null

        speedSamples += LocationSample(now, location.latitude, location.longitude)
        speedSamples.removeAll { now - it.timeMillis > 5_000L }

        val oldest = speedSamples.firstOrNull()
        val newest = speedSamples.lastOrNull()
        var windowSpeedKmh: Double? = null
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
            windowSpeedKmh = distance[0] / 1000.0 / hours
        }
        val speedKmh = if (nativeSpeedKmh != null && nativeSpeedKmh < 1.0) nativeSpeedKmh else windowSpeedKmh
        if (speedKmh != null) {
            val smoothed = smoothSpeed(speedKmh)
            LocationStore.updateCurrentSpeedKmh(smoothed)
            updateNotification(TimerStore.remainingMillis)
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
                handler.post {
                    if (LocationStore.selectedLocationId == selected.id && LocationStore.travelMode == mode) {
                        applyRouteEstimate(estimate)
                    }
                }
            }.onFailure {
                handler.post {
                    LocationStore.setDistanceError("distance unavailable")
                    updateNotification(TimerStore.remainingMillis)
                }
            }
        }
    }

    private fun smoothSpeed(newSpeedKmh: Double): Double {
        val oldSpeedKmh = smoothedSpeedKmh
        val smoothed = if (oldSpeedKmh == null) {
            newSpeedKmh
        } else {
            newSpeedKmh * SPEED_SMOOTHING_NEW_WEIGHT + oldSpeedKmh * SPEED_SMOOTHING_OLD_WEIGHT
        }
        smoothedSpeedKmh = smoothed
        return smoothed
    }

    private fun applyRouteEstimate(estimate: RouteEstimate) {
        if (estimate.distanceMeters <= 50) {
            LocationStore.clearSelection()
            stopRouteInfoLoop()
            stopRouteTracking()
            return
        }
        startTimerFromRouteEstimateIfIdle(estimate.durationSeconds)
        LocationStore.setDistance(estimate.distanceMeters)
        updateNotification(TimerStore.remainingMillis)
    }

    private fun startTimerFromRouteEstimateIfIdle(durationSeconds: Int) {
        if (TimerStore.remainingMillis > 0L || durationSeconds <= 0) return
        TimerStore.markRouteEstimateTimer(true)
        targetTimeMillis = System.currentTimeMillis() + durationSeconds * 1000L
        lastSpokenMinute = spokenMinuteBucket(targetTimeMillis - System.currentTimeMillis())
        finalStopScheduled = false
        handler.removeCallbacks(finalStop)
        handler.removeCallbacks(tick)
        tick.run()
    }

    private fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
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

    private fun maybeSpeak(remainingMillis: Long) {
        val audioMode = effectiveAudioMode()
        if (audioMode == AudioMode.None || remainingMillis == 0L || !ttsReady) return

        updateBluetoothSessionMute(remainingMillis)
        if (soundDisabledForSession) return

        if (!TimerStore.repeatRemainingTime) return

        val minuteBucket = spokenMinuteBucket(remainingMillis)
        if (minuteBucket == lastSpokenMinute) return

        val remainingMinutes = spokenMinuteValue(remainingMillis)
        if (!shouldSpeakMinute(remainingMinutes, audioMode)) return

        lastSpokenMinute = minuteBucket
        speakRemainingTime(remainingMinutes)
    }

    private fun speakRemainingTime(remainingMinutes: Long) {
        if (!TimerStore.repeatRemainingTime || !ttsReady || soundDisabledForSession) return
        val phrase = if (TimerStore.repeatRemainingTimeThreeTimes) {
            "$remainingMinutes, $remainingMinutes, $remainingMinutes"
        } else {
            remainingMinutes.toString()
        }
        tts?.speak(phrase, TextToSpeech.QUEUE_FLUSH, null, "remaining-$remainingMinutes")
    }

    private fun finishTimer() {
        if (finalStopScheduled) return
        finalStopScheduled = true
        speakRemainingTime(0L)
        if (TimerStore.repeatRemainingTime && ttsReady && !soundDisabledForSession) {
            handler.postDelayed(finalStop, FINAL_SPEECH_STOP_DELAY_MS)
        } else {
            stopTimer()
        }
    }

    private fun maybeSpeakRouteInfo(remainingMillis: Long) {
        if (!ttsReady) return
        if (soundDisabledForSession) return
        if (!TimerStore.speakRouteInfo) return
        if (!LocationStore.routeActive) return
        if (TimerStore.routeInfoParts.isEmpty()) return

        updateBluetoothSessionMute(remainingMillis)
        if (soundDisabledForSession) return

        val now = System.currentTimeMillis()
        val intervalMillis = TimerStore.routeInfoIntervalSeconds.coerceAtLeast(5) * 1000L
        if (now - lastRouteInfoSpokenAt < intervalMillis) return

        val message = routeInfoMessage(remainingMillis)
        if (message.isBlank()) return

        lastRouteInfoSpokenAt = now
        tts?.speak(message, TextToSpeech.QUEUE_ADD, null, "route-info-$now")
    }

    private fun startRouteInfoLoop() {
        handler.removeCallbacks(routeInfoTick)
        routeInfoTick.run()
    }

    private fun stopRouteInfoLoop() {
        handler.removeCallbacks(routeInfoTick)
    }

    private fun shouldSpeakMinute(remainingMinutes: Long, audioMode: AudioMode): Boolean {
        return when (audioMode) {
            AudioMode.None -> false
            AudioMode.All -> remainingMinutes > 0
            AudioMode.Adaptive -> when {
                remainingMinutes <= 5 -> true
                remainingMinutes <= 15 -> remainingMinutes % 5L == 0L
                remainingMinutes <= 60 -> remainingMinutes % 15L == 0L
                else -> remainingMinutes % 30L == 0L
            }
        }
    }

    private fun effectiveAudioMode(): AudioMode {
        return if (TimerStore.useAllIfRouting && LocationStore.routeActive) {
            AudioMode.All
        } else {
            TimerStore.audioMode
        }
    }

    private fun spokenMinuteBucket(remainingMillis: Long): Long {
        return TimeUnit.MILLISECONDS.toMinutes(remainingMillis).coerceAtLeast(0L)
    }

    private fun spokenMinuteValue(remainingMillis: Long): Long {
        return ((remainingMillis.coerceAtLeast(0L) + TimeUnit.MINUTES.toMillis(1) - 1) /
            TimeUnit.MINUTES.toMillis(1)).coerceAtLeast(0L)
    }

    private fun startsOnMinuteBoundary(remainingMillis: Long): Boolean {
        val minuteMillis = TimeUnit.MINUTES.toMillis(1)
        return remainingMillis > 0L && remainingMillis % minuteMillis >= minuteMillis - 1000L
    }

    private fun beginSoundSessionIfNeeded() {
        if (!soundSessionStarted) {
            beginSoundSession(reset = true)
        }
    }

    private fun beginSoundSession(reset: Boolean) {
        if (!reset && soundSessionStarted) return
        soundSessionStarted = true
        soundDisabledForSession = false
        sessionBluetoothDevices = if (TimerStore.bluetoothFailsafeEnabled) {
            connectedBluetoothAudioDevices()
        } else {
            emptySet()
        }
    }

    private fun updateBluetoothSessionMute(remainingMillis: Long) {
        if (!TimerStore.bluetoothFailsafeEnabled) return
        if (soundDisabledForSession) return
        if (sessionBluetoothDevices.isEmpty()) return
        if (connectedBluetoothAudioDevices().containsAll(sessionBluetoothDevices)) return

        soundDisabledForSession = true
        tts?.stop()
        updateNotification(remainingMillis)
    }

    private fun connectedBluetoothAudioDevices(): Set<String> {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) {
            return emptySet()
        }

        val audioManager = getSystemService(AudioManager::class.java)
        return try {
            audioManager.getDevices(AudioManager.GET_DEVICES_ALL)
                .filter { it.isBluetoothAudioDevice() }
                .map { it.bluetoothDeviceKey() }
                .toSet()
        } catch (_: SecurityException) {
            emptySet()
        }
    }

    private fun AudioDeviceInfo.isBluetoothAudioDevice(): Boolean {
        return type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
            type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && type == AudioDeviceInfo.TYPE_BLE_HEADSET) ||
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && type == AudioDeviceInfo.TYPE_BLE_SPEAKER) ||
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && type == AudioDeviceInfo.TYPE_BLE_BROADCAST)
    }

    private fun AudioDeviceInfo.bluetoothDeviceKey(): String {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            "$type:${address.orEmpty()}"
        } else {
            "$type:$id"
        }
    }

    private fun updateNotification(remainingMillis: Long) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(remainingMillis))
    }

    private fun buildNotification(remainingMillis: Long): Notification {
        val openAppIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("Time")
            .setContentText(formatNotificationText(remainingMillis))
            .setStyle(NotificationCompat.BigTextStyle().bigText(formatNotificationText(remainingMillis)))
            .setContentIntent(openAppIntent)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "stop",
                serviceIntent(ACTION_STOP, REQUEST_STOP)
            )
            .addAction(
                if (soundDisabledForSession) android.R.drawable.ic_lock_silent_mode_off else android.R.drawable.ic_lock_silent_mode,
                if (soundDisabledForSession) "unmute" else "mute",
                serviceIntent(
                    if (soundDisabledForSession) ACTION_UNMUTE else ACTION_MUTE,
                    REQUEST_MUTE
                )
            )
            .setOngoing(remainingMillis > 0L || LocationStore.routeActive)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun serviceIntent(action: String, requestCode: Int): PendingIntent {
        return PendingIntent.getService(
            this,
            requestCode,
            Intent(this, TimerService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    private fun muteTimer() {
        soundDisabledForSession = true
        tts?.stop()
        updateNotification(targetTimeMillis - System.currentTimeMillis())
    }

    private fun unmuteTimer() {
        soundDisabledForSession = false
        sessionBluetoothDevices = if (TimerStore.bluetoothFailsafeEnabled) {
            connectedBluetoothAudioDevices()
        } else {
            emptySet()
        }
        updateNotification(targetTimeMillis - System.currentTimeMillis())
    }

    private fun stopTimer() {
        handler.removeCallbacks(tick)
        handler.removeCallbacks(finalStop)
        finalStopScheduled = false
        TimerStore.setRemaining(0L)
        LocationStore.clearSelection()
        stopRouteInfoLoop()
        stopRouteTracking(notify = false)
        stopForegroundNotification()
        resetSoundSession()
        stopSelf()
    }

    private fun resetSoundSession() {
        soundSessionStarted = false
        soundDisabledForSession = false
        sessionBluetoothDevices = emptySet()
    }

    private fun stopForegroundNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "timer",
            NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
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

    private fun formatNotificationText(remainingMillis: Long): String {
        val parts = TimerStore.notificationParts
        val selected = LocationStore.selectedLocation
        val distance = LocationStore.distanceMeters
        val targetPace = LocationStore.targetPaceKmh
        if (selected == null && targetPace == null) {
            return if (parts.contains(NotificationPart.Remaining)) formatRemaining(remainingMillis) else ""
        }

        val currentSpeed = LocationStore.currentSpeedKmh
        val targetSpeed = targetPace ?: distance?.let { requiredSpeedKmh(it, remainingMillis) }
        val improvement = speedImprovementText(currentSpeed, targetSpeed)
        val currentSpeedText = if (parts.contains(NotificationPart.Current)) {
            formatSpeed(currentSpeed)
        } else {
            ""
        }
        val targetSpeedText = if (parts.contains(NotificationPart.Required)) {
            formatSpeed(targetSpeed)
        } else {
            ""
        }
        val speedText = if (currentSpeedText.isNotBlank() && targetSpeedText.isNotBlank()) {
            "$currentSpeedText → $targetSpeedText"
        } else {
            listOf(currentSpeedText, targetSpeedText).filter(String::isNotBlank).joinToString("  ")
        }
        return listOf(
            if (parts.contains(NotificationPart.Remaining)) formatRemaining(remainingMillis) else "",
            speedText,
            if (parts.contains(NotificationPart.Relative)) improvement.orEmpty() else ""
        ).filter(String::isNotBlank).joinToString("  ")
    }

    private fun requiredSpeedKmh(distanceMeters: Int, remainingMillis: Long): Double? {
        if (remainingMillis <= 0L) return null
        return distanceMeters / 1000.0 / (remainingMillis / 3_600_000.0)
    }

    private fun speedImprovementText(currentSpeed: Double?, targetSpeed: Double?): String? {
        if (targetSpeed == null) return null
        if (targetSpeed <= 0.0) return null
        if (currentSpeed == null || currentSpeed <= 0.1) {
            return if (targetSpeed <= 0.1) "0%" else "+∞"
        }
        val percent = ((targetSpeed / currentSpeed) - 1.0) * 100.0
        val rounded = percent.roundToInt()
        return if (rounded > 0) "+$rounded%" else "$rounded%"
    }

    private fun routeInfoMessage(remainingMillis: Long): String {
        val parts = TimerStore.routeInfoParts
        val distance = LocationStore.distanceMeters
        val currentSpeed = LocationStore.currentSpeedKmh
        val requiredSpeed = LocationStore.targetPaceKmh ?: distance?.let { requiredSpeedKmh(it, remainingMillis) }

        return listOfNotNull(
            if (parts.contains(RouteInfoPart.Meter)) meterMessage(distance) else null,
            if (parts.contains(RouteInfoPart.Current) && currentSpeed.isSpeakingSpeed()) {
                speedMessage(currentSpeed, "current")
            } else {
                null
            },
            if (parts.contains(RouteInfoPart.Required)) speedMessage(requiredSpeed, "needed") else null,
            if (parts.contains(RouteInfoPart.Relative)) relativeMessage(currentSpeed, requiredSpeed) else null
        ).joinToString(" ")
    }

    private fun meterMessage(distanceMeters: Int?): String? {
        if (distanceMeters == null) return null
        val rounded = ((distanceMeters.toDouble() / 10).roundToInt() * 10).coerceAtLeast(0)
        return "$rounded meters."
    }

    private fun speedMessage(speed: Double?, suffix: String): String? {
        val formatted = formatSpeedForSpeech(speed) ?: return null
        return "$formatted $suffix."
    }

    private fun relativeMessage(currentSpeed: Double?, requiredSpeed: Double?): String? {
        if (!currentSpeed.isSpeakingSpeed() || requiredSpeed == null) return null
        if (requiredSpeed <= 0.0) return null
        val speakingSpeed = currentSpeed ?: return null
        val percent = (((requiredSpeed / speakingSpeed) - 1.0) * 100.0).roundToInt()
        return when {
            percent > 0 -> "$percent% faster."
            percent < 0 -> if (TimerStore.relativeOnlyIfPositive) null else "${-percent}% slower."
            else -> "0% faster."
        }
    }

    private fun Double?.isSpeakingSpeed(): Boolean {
        return this != null && this >= 1.0
    }

    private data class LocationSample(
        val timeMillis: Long,
        val latitude: Double,
        val longitude: Double
    )

    companion object {
        const val ACTION_START = "com.joeld.timemanage.START"
        const val ACTION_STOP = "com.joeld.timemanage.STOP"
        const val ACTION_MUTE = "com.joeld.timemanage.MUTE"
        const val ACTION_UNMUTE = "com.joeld.timemanage.UNMUTE"
        const val ACTION_ROUTE_START = "com.joeld.timemanage.ROUTE_START"
        const val ACTION_ROUTE_STOP = "com.joeld.timemanage.ROUTE_STOP"
        const val EXTRA_TARGET_TIME_MILLIS = "target_time_millis"
        const val EXTRA_AUDIO_MODE = "audio_mode"
        const val EXTRA_ROUTE_ESTIMATE_TIMER = "route_estimate_timer"
        private const val CHANNEL_ID = "timer"
        private const val NOTIFICATION_ID = 1
        private const val FINAL_SPEECH_STOP_DELAY_MS = 2500L
        private const val ROUTE_INFO_TICK_MS = 1000L
        private const val SPEED_SMOOTHING_NEW_WEIGHT = 0.3
        private const val SPEED_SMOOTHING_OLD_WEIGHT = 0.7
        private const val REQUEST_STOP = 2
        private const val REQUEST_MUTE = 3
    }
}
