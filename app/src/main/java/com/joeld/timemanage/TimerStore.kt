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

enum class NotificationPart(val label: String) {
    Remaining("remaining"),
    Current("current speed"),
    Required("needed speed"),
    Relative("relative"),
}

enum class SpeedUnit(val label: String, val speechLabel: String) {
    Kmh("km/h", "kmh"),
    MinPerKm("min/km", "minutes per km"),
    Mps("m/s", "meters per second")
}

object TimerStore {
    private const val PREFS = "timer_settings"
    private const val KEY_AUDIO_MODE = "audio_mode"
    private const val KEY_SPEED_UNIT = "speed_unit"
    private const val KEY_REPEAT_REMAINING_TIME = "repeat_remaining_time"
    private const val KEY_REPEAT_REMAINING_TIME_THREE_TIMES = "repeat_remaining_time_three_times"
    private const val KEY_SPEAK_ROUTE_INFO = "speak_route_info"
    private const val KEY_USE_ALL_IF_ROUTING = "use_all_if_routing"
    private const val KEY_BLUETOOTH_FAILSAFE = "bluetooth_failsafe"
    private const val KEY_ROUTE_INFO_INTERVAL_SECONDS = "route_info_interval_seconds"
    private const val KEY_ROUTE_INFO_PARTS = "route_info_parts"
    private const val KEY_RELATIVE_ONLY_IF_POSITIVE = "relative_only_if_positive"
    private const val KEY_NOTIFICATION_PARTS = "notification_parts"
    private val DEFAULT_AUDIO_MODE = AudioMode.Adaptive
    private val DEFAULT_SPEED_UNIT = SpeedUnit.Kmh
    private const val DEFAULT_REPEAT_REMAINING_TIME = true
    private const val DEFAULT_REPEAT_REMAINING_TIME_THREE_TIMES = true
    private const val DEFAULT_SPEAK_ROUTE_INFO = true
    private const val DEFAULT_USE_ALL_IF_ROUTING = true
    private const val DEFAULT_BLUETOOTH_FAILSAFE = true
    private const val DEFAULT_ROUTE_INFO_INTERVAL_SECONDS = 10
    private const val DEFAULT_RELATIVE_ONLY_IF_POSITIVE = true
    private val DEFAULT_ROUTE_INFO_PARTS = setOf(RouteInfoPart.Relative)
    private val DEFAULT_NOTIFICATION_PARTS = setOf(
        NotificationPart.Remaining,
        NotificationPart.Current,
        NotificationPart.Required,
        NotificationPart.Relative
    )

    var remainingMillis by mutableLongStateOf(0L)
        private set

    var audioMode by mutableStateOf(DEFAULT_AUDIO_MODE)
        private set

    var speedUnit by mutableStateOf(DEFAULT_SPEED_UNIT)
        private set

    var repeatRemainingTime by mutableStateOf(DEFAULT_REPEAT_REMAINING_TIME)
        private set

    var repeatRemainingTimeThreeTimes by mutableStateOf(DEFAULT_REPEAT_REMAINING_TIME_THREE_TIMES)
        private set

    var speakRouteInfo by mutableStateOf(DEFAULT_SPEAK_ROUTE_INFO)
        private set

    var useAllIfRouting by mutableStateOf(DEFAULT_USE_ALL_IF_ROUTING)
        private set

    var bluetoothFailsafeEnabled by mutableStateOf(DEFAULT_BLUETOOTH_FAILSAFE)
        private set

    var routeEstimateTimerActive by mutableStateOf(false)
        private set

    var routeInfoIntervalSeconds by mutableStateOf(DEFAULT_ROUTE_INFO_INTERVAL_SECONDS)
        private set

    var routeInfoParts by mutableStateOf(DEFAULT_ROUTE_INFO_PARTS)
        private set

    var relativeOnlyIfPositive by mutableStateOf(DEFAULT_RELATIVE_ONLY_IF_POSITIVE)
        private set

    var notificationParts by mutableStateOf(DEFAULT_NOTIFICATION_PARTS)
        private set

    fun load(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val saved = prefs.getString(KEY_AUDIO_MODE, DEFAULT_AUDIO_MODE.name)
        audioMode = AudioMode.entries.firstOrNull { it.name == saved } ?: DEFAULT_AUDIO_MODE
        val savedSpeedUnit = prefs.getString(KEY_SPEED_UNIT, DEFAULT_SPEED_UNIT.name)
        speedUnit = SpeedUnit.entries.firstOrNull { it.name == savedSpeedUnit } ?: DEFAULT_SPEED_UNIT
        repeatRemainingTime = prefs.getBoolean(KEY_REPEAT_REMAINING_TIME, DEFAULT_REPEAT_REMAINING_TIME)
        repeatRemainingTimeThreeTimes = prefs.getBoolean(
            KEY_REPEAT_REMAINING_TIME_THREE_TIMES,
            DEFAULT_REPEAT_REMAINING_TIME_THREE_TIMES
        )
        speakRouteInfo = prefs.getBoolean(KEY_SPEAK_ROUTE_INFO, DEFAULT_SPEAK_ROUTE_INFO)
        useAllIfRouting = prefs.getBoolean(KEY_USE_ALL_IF_ROUTING, DEFAULT_USE_ALL_IF_ROUTING)
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
        relativeOnlyIfPositive = prefs.getBoolean(
            KEY_RELATIVE_ONLY_IF_POSITIVE,
            DEFAULT_RELATIVE_ONLY_IF_POSITIVE
        )
        val savedNotificationParts = prefs.getString(KEY_NOTIFICATION_PARTS, null)
        notificationParts = savedNotificationParts
            ?.split(",")
            ?.mapNotNull { savedPart -> NotificationPart.entries.firstOrNull { it.name == savedPart } }
            ?.toSet()
            ?.takeIf { it.isNotEmpty() }
            ?: DEFAULT_NOTIFICATION_PARTS
    }

    fun updateAudioMode(mode: AudioMode) {
        audioMode = mode
        App.instance
            ?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            ?.edit()
            ?.putString(KEY_AUDIO_MODE, mode.name)
            ?.apply()
    }

    fun updateSpeedUnit(unit: SpeedUnit) {
        speedUnit = unit
        App.instance
            ?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            ?.edit()
            ?.putString(KEY_SPEED_UNIT, unit.name)
            ?.apply()
    }

    fun updateRepeatRemainingTime(enabled: Boolean) {
        repeatRemainingTime = enabled
        App.instance
            ?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            ?.edit()
            ?.putBoolean(KEY_REPEAT_REMAINING_TIME, enabled)
            ?.apply()
    }

    fun updateRepeatRemainingTimeThreeTimes(enabled: Boolean) {
        repeatRemainingTimeThreeTimes = enabled
        App.instance
            ?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            ?.edit()
            ?.putBoolean(KEY_REPEAT_REMAINING_TIME_THREE_TIMES, enabled)
            ?.apply()
    }

    fun updateSpeakRouteInfo(enabled: Boolean) {
        speakRouteInfo = enabled
        App.instance
            ?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            ?.edit()
            ?.putBoolean(KEY_SPEAK_ROUTE_INFO, enabled)
            ?.apply()
    }

    fun updateUseAllIfRouting(enabled: Boolean) {
        useAllIfRouting = enabled
        App.instance
            ?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            ?.edit()
            ?.putBoolean(KEY_USE_ALL_IF_ROUTING, enabled)
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

    fun updateNotificationPart(part: NotificationPart, enabled: Boolean) {
        val updated = if (enabled) notificationParts + part else notificationParts - part
        notificationParts = updated
        App.instance
            ?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            ?.edit()
            ?.putString(KEY_NOTIFICATION_PARTS, notificationParts.joinToString(",") { it.name })
            ?.apply()
    }

    fun setRemaining(millis: Long) {
        remainingMillis = millis.coerceAtLeast(0L)
    }

    fun markRouteEstimateTimer(active: Boolean) {
        routeEstimateTimerActive = active
    }
}
