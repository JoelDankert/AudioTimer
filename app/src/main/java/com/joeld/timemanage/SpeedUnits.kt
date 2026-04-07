package com.joeld.timemanage

import java.util.Locale
import kotlin.math.roundToInt

fun speedValueFromKmh(kmh: Double, unit: SpeedUnit = TimerStore.speedUnit): Double? {
    if (kmh <= 0.0) return null
    return when (unit) {
        SpeedUnit.Kmh -> kmh
        SpeedUnit.MinPerKm -> 60.0 / kmh
        SpeedUnit.Mps -> kmh / 3.6
    }
}

fun kmhFromSpeedValue(value: Double, unit: SpeedUnit = TimerStore.speedUnit): Double? {
    if (value < 0.0) return null
    if (value == 0.0) return 0.0
    return when (unit) {
        SpeedUnit.Kmh -> value
        SpeedUnit.MinPerKm -> 60.0 / value
        SpeedUnit.Mps -> value * 3.6
    }
}

fun formatSpeed(speedKmh: Double?, unit: SpeedUnit = TimerStore.speedUnit): String {
    val value = speedKmh?.let { speedValueFromKmh(it, unit) } ?: return ""
    return when (unit) {
        SpeedUnit.Kmh -> "${formatOneDecimal(value)} km/h"
        SpeedUnit.MinPerKm -> "${formatOneDecimal(value)} min/km"
        SpeedUnit.Mps -> "${formatOneDecimal(value)} m/s"
    }
}

fun formatSpeedForSpeech(speedKmh: Double?, unit: SpeedUnit = TimerStore.speedUnit): String? {
    val value = speedKmh?.let { speedValueFromKmh(it, unit) } ?: return null
    val spokenValue = formatOneDecimal(value)
    return "$spokenValue ${unit.speechLabel}"
}

private fun formatOneDecimal(value: Double): String {
    return String.format(Locale.US, "%.1f", (value * 10.0).roundToInt() / 10.0)
}
