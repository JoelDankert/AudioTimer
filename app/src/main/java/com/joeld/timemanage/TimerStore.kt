package com.joeld.timemanage

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

enum class AudioMode(val label: String) {
    None("none"),
    Adaptive("adaptive"),
    All("all")
}

enum class RouteInfoPart(val label: String) {
    Meter("meter"),
    Current("current"),
    Required("required"),
    Relative("relative"),
}

object TimerStore {
    private const val PREFS = "timer_settings"
    private const val KEY_AUDIO_MODE = "audio_mode"
    private const val KEY_BLUETOOTH_FAILSAFE = "bluetooth_failsafe"
    private const val KEY_ROUTE_INFO_INTERVAL_SECONDS = "route_info_interval_seconds"
    private const val KEY_ROUTE_INFO_PARTS = "route_info_parts"
    private const val KEY_RELATIVE_ONLY_IF_POSITIVE = "relative_only_if_positive"
    private val DEFAULT_AUDIO_MODE = AudioMode.Adaptive
    private const val DEFAULT_BLUETOOTH_FAILSAFE = true
    private const val DEFAULT_ROUTE_INFO_INTERVAL_SECONDS = 10
    private val DEFAULT_ROUTE_INFO_PARTS = setOf(RouteInfoPart.Relative)

    var remainingMillis by mutableLongStateOf(0L)
        private set

    var audioMode by mutableStateOf(DEFAULT_AUDIO_MODE)
        private set

    var bluetoothFailsafeEnabled by mutableStateOf(DEFAULT_BLUETOOTH_FAILSAFE)
        private set

    var routeEstimateTimerActive by mutableStateOf(false)
        private set

    var routeInfoIntervalSeconds by mutableStateOf(DEFAULT_ROUTE_INFO_INTERVAL_SECONDS)
        private set

    var routeInfoParts by mutableStateOf(DEFAULT_ROUTE_INFO_PARTS)
        private set

    var relativeOnlyIfPositive by mutableStateOf(true)
        private set

    fun load(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val saved = prefs.getString(KEY_AUDIO_MODE, DEFAULT_AUDIO_MODE.name)
        audioMode = AudioMode.entries.firstOrNull { it.name == saved } ?: DEFAULT_AUDIO_MODE
        bluetoothFailsafeEnabled = prefs.getBoolean(KEY_BLUETOOTH_FAILSAFE, DEFAULT_BLUETOOTH_FAILSAFE)
        routeInfoIntervalSeconds = prefs.getInt(
            KEY_ROUTE_INFO_INTERVAL_SECONDS,
            DEFAULT_ROUTE_INFO_INTERVAL_SECONDS
        ).coerceAtLeast(5)
        val savedParts = prefs.getString(KEY_ROUTE_INFO_PARTS, null)
        routeInfoParts = savedParts
            ?.split(",")
            ?.mapNotNull { savedPart -> RouteInfoPart.entries.firstOrNull { it.name == savedPart } }
            ?.toSet()
            ?.takeIf { it.isNotEmpty() }
            ?: DEFAULT_ROUTE_INFO_PARTS
        relativeOnlyIfPositive = prefs.getBoolean(KEY_RELATIVE_ONLY_IF_POSITIVE, true)
    }

    fun updateAudioMode(mode: AudioMode) {
        audioMode = mode
        App.instance
            ?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            ?.edit()
            ?.putString(KEY_AUDIO_MODE, mode.name)
            ?.apply()
    }

    fun updateBluetoothFailsafe(enabled: Boolean) {
        bluetoothFailsafeEnabled = enabled
        App.instance
            ?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            ?.edit()
            ?.putBoolean(KEY_BLUETOOTH_FAILSAFE, enabled)
            ?.apply()
    }

    fun updateRouteInfoIntervalSeconds(seconds: Int) {
        routeInfoIntervalSeconds = seconds.coerceAtLeast(5)
        App.instance
            ?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            ?.edit()
            ?.putInt(KEY_ROUTE_INFO_INTERVAL_SECONDS, routeInfoIntervalSeconds)
            ?.apply()
    }

    fun updateRouteInfoPart(part: RouteInfoPart, enabled: Boolean) {
        val updated = if (enabled) routeInfoParts + part else routeInfoParts - part
        routeInfoParts = updated
        App.instance
            ?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            ?.edit()
            ?.putString(KEY_ROUTE_INFO_PARTS, routeInfoParts.joinToString(",") { it.name })
            ?.apply()
    }

    fun updateRelativeOnlyIfPositive(enabled: Boolean) {
        relativeOnlyIfPositive = enabled
        App.instance
            ?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            ?.edit()
            ?.putBoolean(KEY_RELATIVE_ONLY_IF_POSITIVE, enabled)
            ?.apply()
    }

    fun setRemaining(millis: Long) {
        remainingMillis = millis.coerceAtLeast(0L)
    }

    fun markRouteEstimateTimer(active: Boolean) {
        routeEstimateTimerActive = active
    }
}
