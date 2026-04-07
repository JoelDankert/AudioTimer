package com.joeld.timemanage

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject

enum class TravelMode(val label: String, val osrmEndpoint: String, val osrmProfile: String) {
    Walk("walk", "routed-foot", "walking"),
    Car("car", "routed-car", "driving")
}

data class SavedLocation(
    val id: Long,
    val name: String,
    val location: String,
    val latitude: Double,
    val longitude: Double,
    val useCount: Int = 0,
    val lastUsedAt: Long = 0L
)

object LocationStore {
    private const val PREFS = "locations"
    private const val KEY_LOCATIONS = "locations"
    private const val KEY_SELECTED_ID = "selected_id"
    private const val KEY_TRAVEL_MODE = "travel_mode"

    val locations = mutableStateListOf<SavedLocation>()
    var selectedLocationId by mutableStateOf<Long?>(null)
        private set
    var travelMode by mutableStateOf(TravelMode.Walk)
        private set
    var expanded by mutableStateOf(false)
        private set
    var distanceMeters by mutableStateOf<Int?>(null)
        private set
    var distanceStatus by mutableStateOf<String?>(null)
        private set
    var currentSpeedKmh by mutableStateOf<Double?>(null)
        private set

    val selectedLocation: SavedLocation?
        get() = locations.firstOrNull { it.id == selectedLocationId }

    fun load(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        expanded = false
        travelMode = prefs.getString(KEY_TRAVEL_MODE, TravelMode.Walk.name)
            ?.let { saved -> TravelMode.entries.firstOrNull { it.name == saved } }
            ?: TravelMode.Walk
        selectedLocationId = prefs.getLong(KEY_SELECTED_ID, -1L).takeIf { it != -1L }

        locations.clear()
        val savedLocations = prefs.getString(KEY_LOCATIONS, "[]") ?: "[]"
        val array = JSONArray(savedLocations)
        for (index in 0 until array.length()) {
            val item = array.getJSONObject(index)
            locations += SavedLocation(
                id = item.getLong("id"),
                name = item.getString("name"),
                location = item.optString("location", item.optString("address", "")),
                latitude = item.getDouble("latitude"),
                longitude = item.getDouble("longitude"),
                useCount = item.optInt("use_count", 0),
                lastUsedAt = item.optLong("last_used_at", 0L)
            )
        }
        if (selectedLocation == null) {
            selectedLocationId = null
        }
    }

    fun updateExpanded(value: Boolean) {
        expanded = value
    }

    fun updateTravelMode(mode: TravelMode) {
        travelMode = mode
        distanceMeters = null
        save()
    }

    fun addLocation(location: SavedLocation) {
        locations += location.copy(useCount = 1, lastUsedAt = System.currentTimeMillis())
        selectedLocationId = location.id
        distanceMeters = null
        distanceStatus = null
        sortLocations()
        save()
    }

    fun updateLocation(location: SavedLocation) {
        val index = locations.indexOfFirst { it.id == location.id }
        if (index == -1) return
        val current = locations[index]
        locations[index] = location.copy(
            useCount = current.useCount,
            lastUsedAt = current.lastUsedAt
        )
        if (selectedLocationId == location.id) {
            distanceMeters = null
            distanceStatus = null
            currentSpeedKmh = null
        }
        sortLocations()
        save()
    }

    fun deleteLocation(location: SavedLocation) {
        locations.removeAll { it.id == location.id }
        if (selectedLocationId == location.id) {
            selectedLocationId = null
            distanceMeters = null
            distanceStatus = null
        }
        save()
    }

    fun selectLocation(location: SavedLocation) {
        selectedLocationId = location.id
        val index = locations.indexOfFirst { it.id == location.id }
        if (index != -1) {
            val current = locations[index]
            locations[index] = current.copy(
                useCount = current.useCount + 1,
                lastUsedAt = System.currentTimeMillis()
            )
            sortLocations()
        }
        distanceMeters = null
        distanceStatus = null
        currentSpeedKmh = null
        save()
    }

    fun clearSelection() {
        selectedLocationId = null
        distanceMeters = null
        distanceStatus = null
        save()
    }

    fun setDistanceLoading() {
        distanceMeters = null
        distanceStatus = "loading"
    }

    fun setDistance(meters: Int) {
        distanceMeters = meters
        distanceStatus = null
    }

    fun setDistanceError(message: String) {
        distanceMeters = null
        distanceStatus = message
    }

    fun updateCurrentSpeedKmh(speed: Double?) {
        currentSpeedKmh = speed
    }

    private fun save() {
        val array = JSONArray()
        locations.forEach { location ->
            array.put(
                JSONObject()
                    .put("id", location.id)
                    .put("name", location.name)
                    .put("location", location.location)
                    .put("latitude", location.latitude)
                    .put("longitude", location.longitude)
                    .put("use_count", location.useCount)
                    .put("last_used_at", location.lastUsedAt)
            )
        }
        App.instance
            ?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            ?.edit()
            ?.putString(KEY_LOCATIONS, array.toString())
            ?.putLong(KEY_SELECTED_ID, selectedLocationId ?: -1L)
            ?.putString(KEY_TRAVEL_MODE, travelMode.name)
            ?.apply()
    }

    private fun sortLocations() {
        val sorted = locations.sortedWith(
            compareByDescending<SavedLocation> { it.useCount }
                .thenByDescending { it.lastUsedAt }
                .thenBy { it.name.lowercase() }
        )
        locations.clear()
        locations.addAll(sorted)
    }
}
