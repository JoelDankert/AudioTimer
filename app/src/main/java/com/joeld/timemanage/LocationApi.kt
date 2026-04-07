package com.joeld.timemanage

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.roundToInt

data class RouteEstimate(
    val distanceMeters: Int,
    val durationSeconds: Int
)

object LocationApi {
    fun createLocation(name: String, location: String, id: Long = System.currentTimeMillis()): SavedLocation? {
        parseCoordinates(location)?.let { (latitude, longitude) ->
            return SavedLocation(
                id = id,
                name = name,
                location = location,
                latitude = latitude,
                longitude = longitude
            )
        }

        val encodedQuery = URLEncoder.encode(location, "UTF-8")
        val url = URL("https://nominatim.openstreetmap.org/search?format=jsonv2&limit=1&q=$encodedQuery")
        val response = readUrl(url)
        val results = JSONArray(response)
        if (results.length() == 0) return null
        val item = results.getJSONObject(0)
        return SavedLocation(
            id = id,
            name = name,
            location = location,
            latitude = item.getString("lat").toDouble(),
            longitude = item.getString("lon").toDouble()
        )
    }

    fun routeEstimate(
        currentLatitude: Double,
        currentLongitude: Double,
        destination: SavedLocation,
        mode: TravelMode
    ): RouteEstimate {
        val url = URL(
            "https://routing.openstreetmap.de/${mode.osrmEndpoint}/route/v1/${mode.osrmProfile}/" +
                "$currentLongitude,$currentLatitude;${destination.longitude},${destination.latitude}" +
                "?overview=false&alternatives=false&steps=false"
        )
        val response = readUrl(url)
        val route = JSONObject(response).getJSONArray("routes").getJSONObject(0)
        return RouteEstimate(
            distanceMeters = route.getDouble("distance").roundToInt(),
            durationSeconds = route.getDouble("duration").roundToInt()
        )
    }

    private fun readUrl(url: URL): String {
        val connection = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 10_000
            requestMethod = "GET"
            setRequestProperty("User-Agent", "timemanage/1.0")
            setRequestProperty("Accept", "application/json")
        }
        return try {
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream.bufferedReader().use { it.readText() }
            if (code !in 200..299) error("HTTP $code")
            body
        } finally {
            connection.disconnect()
        }
    }

    private fun parseCoordinates(value: String): Pair<Double, Double>? {
        val parts = value
            .trim()
            .split(",", " ")
            .mapNotNull { it.trim().takeIf(String::isNotBlank)?.toDoubleOrNull() }
        if (parts.size != 2) return null
        val latitude = parts[0]
        val longitude = parts[1]
        if (latitude !in -90.0..90.0 || longitude !in -180.0..180.0) return null
        return latitude to longitude
    }
}
