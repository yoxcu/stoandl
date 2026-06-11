@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package de.yoxcu.stoandl.weather

import de.yoxcu.stoandl.config.StoandlConfig.WeatherLocation
import de.yoxcu.stoandl.config.StoandlConfig.WeatherUnits
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.rebble.libpebblecommon.connection.ConnectedPebbleDevice
import io.rebble.libpebblecommon.connection.LibPebble
import io.rebble.libpebblecommon.weather.WeatherLocationData
import io.rebble.libpebblecommon.weather.WeatherType
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.security.MessageDigest
import kotlin.math.roundToInt
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

private val log = KotlinLogging.logger {}

// On a failed/partial sync, retry sooner than the configured interval, backing off each time before
// falling back to the normal cadence on success. Covers transient blips (cold-start DNS, a slow API).
private val WEATHER_RETRY_BACKOFF = listOf(30.seconds, 1.minutes, 5.minutes)

/**
 * Fetches weather for the configured fixed locations from Open-Meteo (free, no API key, no account —
 * a good fit for a headless daemon) and pushes it to the watch's system Weather app via libpebble3's
 * [LibPebble.updateWeatherData]. That call writes the Weather BlobDB locally and libpebble3 syncs it
 * to the watch on connect, so we can refresh on an interval regardless of connection state and also
 * fire an immediate sync the moment a watch connects.
 *
 * This deliberately bypasses libpebble3's own `WeatherFetcher`, which depends on Core Devices' account-
 * gated weather proxy, GPS and a geocoder — none of which exist in stoandl's headless, account-free model.
 */
class WeatherSync(
    private val libPebble: LibPebble,
    private val scope: CoroutineScope,
    private val locations: List<WeatherLocation>,
    private val units: WeatherUnits,
    private val intervalMinutes: Long,
    // When non-null, an additional "current location" entry is resolved from GeoClue each sync and
    // marked isCurrentLocation so the watch shows it prominently. [gpsFallbackName] labels it when
    // reverse geocoding yields nothing.
    private val gps: GeoClueLocationProvider? = null,
    private val gpsFallbackName: String = "Current location",
    // Opt-in: reverse-geocode GPS coordinates to a place name via OSM Nominatim. Off by default
    // because it discloses the device's coordinates to a third-party web service.
    private val reverseGeocodeEnabled: Boolean = false,
    // Optional source of additional fixed locations resolved each sync (e.g. imported from the DE).
    private val extraLocations: (suspend () -> List<WeatherLocation>)? = null,
) {
    private val client = HttpClient(CIO) {
        install(HttpTimeout) {
            requestTimeoutMillis = 30_000
            connectTimeoutMillis = 15_000
        }
    }
    private val json = Json { ignoreUnknownKeys = true }
    // Serialise syncs: the periodic loop and the on-connect trigger must never overlap.
    private val syncMutex = Mutex()
    // Whether the last sync populated every location; drives the periodic loop's retry backoff.
    @Volatile private var lastSyncOk = false

    // Stable BlobDB key for the GPS-tracked current location (its coordinates change, but it is one entry).
    private val currentLocationKey = keyFor("stoandl::current-location")
    // Cache the last reverse-geocode result keyed by coarse coordinates, to avoid hammering Nominatim
    // (whose usage policy asks for ≤1 req/s) when the device is stationary.
    private var reverseGeocodeCacheKey: String? = null
    private var reverseGeocodeCacheName: String? = null

    fun start() {
        // Immediate sync whenever a watch transitions into the connected state, so a freshly
        // connected watch gets up-to-date weather within seconds rather than waiting for the interval.
        libPebble.watches
            .map { devices -> devices.any { it is ConnectedPebbleDevice } }
            .distinctUntilChanged()
            .onEach { connected ->
                if (connected) {
                    log.info { "Watch connected — refreshing weather" }
                    runCatching { syncNow() }.onFailure { log.warn { "On-connect weather sync failed: ${it.message}" } }
                }
            }
            .launchIn(scope)

        // Periodic refresh so the Weather BlobDB stays current even across long connections. On a
        // failed/partial fetch, retry sooner with backoff instead of waiting the full interval.
        scope.launch {
            var failStreak = 0
            while (true) {
                runCatching { syncNow() }.onFailure { log.warn { "Periodic weather sync failed: ${it.message}" } }
                val wait = if (lastSyncOk) {
                    failStreak = 0
                    intervalMinutes.minutes
                } else {
                    val backoff = WEATHER_RETRY_BACKOFF.getOrElse(failStreak) { WEATHER_RETRY_BACKOFF.last() }
                    failStreak++
                    log.info { "Weather incomplete — retrying in $backoff" }
                    backoff
                }
                delay(wait)
            }
        }
    }

    /**
     * Fetch all configured locations and push them to the watch. Returns the number of locations that
     * were successfully populated. A location whose fetch fails is sent as [WeatherLocationData.WeatherLocationDataFailed]
     * so its last-known data on the watch is preserved (rather than wiped) on transient network errors.
     */
    suspend fun syncNow(): Int = syncMutex.withLock {
        val tasks = mutableListOf<Deferred<WeatherLocationData>>()

        // GPS current location first, so it sorts to the top of the watch's Weather app.
        val gpsProvider = gps
        if (gpsProvider != null) {
            tasks += scope.async {
                val coords = withContext(Dispatchers.IO) { gpsProvider.currentLatLon() }
                if (coords == null) {
                    // No fix yet: send a "failed" marker so the entry's last-known data is preserved
                    // on the watch rather than wiped.
                    log.info { "GPS enabled but no fix yet — keeping last-known current location" }
                    WeatherLocationData.WeatherLocationDataFailed(currentLocationKey)
                } else {
                    val (lat, lon) = coords
                    val name = (if (reverseGeocodeEnabled) reverseGeocode(lat, lon) else null) ?: gpsFallbackName
                    runCatching { fetchAt(currentLocationKey, name, lat, lon, isCurrentLocation = true) }
                        .getOrElse {
                            log.warn { "Weather fetch failed for current location: ${it.message}" }
                            WeatherLocationData.WeatherLocationDataFailed(currentLocationKey)
                        }
                }
            }
        }

        // Manual locations plus any imported from the DE/command source, de-duplicated by name.
        val deLocations = runCatching { extraLocations?.invoke() ?: emptyList() }
            .getOrElse { log.warn { "Importing DE locations failed: ${it.message}" }; emptyList() }
        val fixed = (locations + deLocations).distinctBy { it.name.lowercase() }
        fixed.forEach { location ->
            tasks += scope.async {
                runCatching { fetchAt(keyFor(location.name), location.name, location.latitude, location.longitude, false) }
                    .getOrElse {
                        log.warn { "Weather fetch failed for ${location.name}: ${it.message}" }
                        WeatherLocationData.WeatherLocationDataFailed(keyFor(location.name))
                    }
            }
        }

        val results = tasks.awaitAll()
        libPebble.updateWeatherData(results)
        val populated = results.count { it is WeatherLocationData.WeatherLocationDataPopulated }
        lastSyncOk = populated == results.size
        log.info { "Weather updated: $populated/${results.size} location(s) populated" }
        populated
    }

    private suspend fun fetchAt(
        key: Uuid,
        name: String,
        latitude: Double,
        longitude: Double,
        isCurrentLocation: Boolean,
    ): WeatherLocationData {
        val tempUnit = if (units == WeatherUnits.IMPERIAL) "fahrenheit" else "celsius"
        val body = client.get("https://api.open-meteo.com/v1/forecast") {
            parameter("latitude", latitude)
            parameter("longitude", longitude)
            parameter("current", "temperature_2m,weather_code")
            parameter("daily", "weather_code,temperature_2m_max,temperature_2m_min")
            parameter("temperature_unit", tempUnit)
            parameter("timezone", "auto")
            parameter("forecast_days", 2)
        }.bodyAsText()

        val resp = json.decodeFromString<OpenMeteoResponse>(body)
        val daily = resp.daily
        val currentTemp = resp.current?.temperature
        if (currentTemp == null || daily == null ||
            daily.tempMax.size < 2 || daily.tempMin.size < 2 || daily.weatherCode.size < 2
        ) {
            log.warn { "Incomplete Open-Meteo response for $name" }
            return WeatherLocationData.WeatherLocationDataFailed(key)
        }

        val currentCode = resp.current?.weatherCode ?: -1
        return WeatherLocationData.WeatherLocationDataPopulated(
            key = key,
            currentTemp = currentTemp.roundToInt().toShort(),
            currentWeatherType = wmoToWeatherType(currentCode),
            todayHighTemp = daily.tempMax[0].roundToInt().toShort(),
            todayLowTemp = daily.tempMin[0].roundToInt().toShort(),
            tomorrowWeatherType = wmoToWeatherType(daily.weatherCode[1]),
            tomorrowHighTemp = daily.tempMax[1].roundToInt().toShort(),
            tomorrowLowTemp = daily.tempMin[1].roundToInt().toShort(),
            lastUpdateTimeUtcSecs = Clock.System.now().epochSeconds,
            isCurrentLocation = isCurrentLocation,
            locationName = name,
            forecastShort = wmoToPhrase(currentCode),
        )
    }

    /** Resolve a place name from coordinates via OSM Nominatim (free, no key). Cached by coarse
     *  coordinates to respect Nominatim's usage policy. Returns null on any failure (caller falls
     *  back to the configured GPS name). */
    private suspend fun reverseGeocode(latitude: Double, longitude: Double): String? {
        // ~3 decimal places ≈ 100 m: stable enough to dedupe repeated lookups while standing still.
        val cacheKey = String.format(java.util.Locale.ROOT, "%.3f,%.3f", latitude, longitude)
        if (cacheKey == reverseGeocodeCacheKey) return reverseGeocodeCacheName
        return try {
            val body = client.get("https://nominatim.openstreetmap.org/reverse") {
                parameter("lat", latitude)
                parameter("lon", longitude)
                parameter("format", "json")
                parameter("zoom", 10) // city-level
                parameter("addressdetails", 1)
                // Nominatim's policy requires an identifying User-Agent.
                header("User-Agent", "stoandl/0.1 (Pebble companion daemon; https://github.com/yoxcu/stoandl)")
            }.bodyAsText()
            val address = json.parseToJsonElement(body).jsonObject["address"]?.jsonObject
            val name = listOf("city", "town", "village", "municipality", "county")
                .firstNotNullOfOrNull { address?.get(it)?.jsonPrimitive?.contentOrNull }
            reverseGeocodeCacheKey = cacheKey
            reverseGeocodeCacheName = name
            name
        } catch (e: Exception) {
            log.warn { "Reverse geocode failed for $cacheKey: ${e.message}" }
            null
        }
    }
}

/** Stable BlobDB key for a location, derived from its name so re-syncs replace rather than accumulate. */
private fun keyFor(name: String): Uuid {
    val digest = MessageDigest.getInstance("SHA-256").digest(name.lowercase().encodeToByteArray())
    return Uuid.fromByteArray(digest.copyOf(16))
}

@Serializable
private data class OpenMeteoResponse(
    val current: Current? = null,
    val daily: Daily? = null,
)

@Serializable
private data class Current(
    @SerialName("temperature_2m") val temperature: Double? = null,
    @SerialName("weather_code") val weatherCode: Int? = null,
)

@Serializable
private data class Daily(
    @SerialName("weather_code") val weatherCode: List<Int> = emptyList(),
    @SerialName("temperature_2m_max") val tempMax: List<Double> = emptyList(),
    @SerialName("temperature_2m_min") val tempMin: List<Double> = emptyList(),
)

/** Map a WMO weather-interpretation code (Open-Meteo) to the Pebble Weather app's icon type. */
internal fun wmoToWeatherType(code: Int): WeatherType = when (code) {
    0, 1 -> WeatherType.Sun                       // clear / mainly clear
    2 -> WeatherType.PartlyCloudy
    3 -> WeatherType.CloudyDay                     // overcast
    45, 48 -> WeatherType.Generic                  // fog
    51, 53 -> WeatherType.LightRain               // drizzle
    55 -> WeatherType.HeavyRain                    // dense drizzle
    56, 57 -> WeatherType.RainAndSnow              // freezing drizzle
    61 -> WeatherType.LightRain
    63, 65 -> WeatherType.HeavyRain
    66, 67 -> WeatherType.RainAndSnow              // freezing rain
    71, 77 -> WeatherType.LightSnow
    73, 75 -> WeatherType.HeavySnow
    80 -> WeatherType.LightRain                    // rain showers
    81, 82 -> WeatherType.HeavyRain
    85 -> WeatherType.LightSnow                    // snow showers
    86 -> WeatherType.HeavySnow
    95, 96, 99 -> WeatherType.HeavyRain            // thunderstorm
    else -> WeatherType.Generic
}

/** A short (<32 char) human label for a WMO code, shown as the Weather app's forecast line. */
internal fun wmoToPhrase(code: Int): String = when (code) {
    0 -> "Clear"
    1 -> "Mainly clear"
    2 -> "Partly cloudy"
    3 -> "Overcast"
    45, 48 -> "Fog"
    51, 53, 55 -> "Drizzle"
    56, 57 -> "Freezing drizzle"
    61 -> "Light rain"
    63 -> "Rain"
    65 -> "Heavy rain"
    66, 67 -> "Freezing rain"
    71 -> "Light snow"
    73 -> "Snow"
    75 -> "Heavy snow"
    77 -> "Snow grains"
    80, 81 -> "Rain showers"
    82 -> "Heavy showers"
    85, 86 -> "Snow showers"
    95, 96, 99 -> "Thunderstorm"
    else -> "—"
}
