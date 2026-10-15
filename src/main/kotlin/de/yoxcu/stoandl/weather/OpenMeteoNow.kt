package de.yoxcu.stoandl.weather

import de.yoxcu.stoandl.util.LenientJson
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

private val log = KotlinLogging.logger {}

/** Temperature unit as Open-Meteo's `temperature_unit` takes it. */
enum class TempUnit(val param: String) { CELSIUS("celsius"), FAHRENHEIT("fahrenheit") }

/** Wind-speed unit as Open-Meteo's `wind_speed_unit` takes it. */
enum class WindUnit(val param: String) { KMH("kmh"), MS("ms"), MPH("mph") }

/**
 * Conditions now at one point plus a few days ahead, in the units asked for. All times are epoch
 * seconds; [utcOffsetSec] is the point's own UTC offset, so local dates and clock times are derived for
 * the place asked about rather than for the host. Optional fields are null when the model has no value.
 */
data class CurrentWeather(
    val timeSec: Long,
    val utcOffsetSec: Int,
    val isDay: Boolean,
    val temperature: Double,
    val apparentTemperature: Double?,
    /** WMO weather-interpretation code; map it with [wmoToWeatherType] / [wmoToPhrase]. */
    val weatherCode: Int,
    val humidityPct: Int?,
    val pressureMslHpa: Double?,
    val surfacePressureHpa: Double?,
    val windSpeed: Double?,
    val windDirectionDeg: Int?,
    val windGusts: Double?,
    val cloudCoverPct: Int?,
    val visibilityM: Double?,
    /** Today first. Never empty. */
    val days: List<Day>,
) {
    data class Day(
        /** The day's local midnight. */
        val startSec: Long,
        val weatherCode: Int,
        val tempMax: Double,
        val tempMin: Double,
        val sunriseSec: Long?,
        val sunsetSec: Long?,
    )
}

/** Weather now at a point. Null means nothing usable came back (network, HTTP or a partial reply). */
fun interface CurrentWeatherSource {
    suspend fun now(latitude: Double, longitude: Double, temp: TempUnit, wind: WindUnit): CurrentWeather?
}

/** A place found by name: what it is called and where it is. */
data class FoundPlace(
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val region: String?,
    val country: String?,
    val countryCode: String?,
)

/** Place name → candidate places, most likely first. Empty when nothing matches or the lookup fails. */
fun interface PlaceSearch {
    suspend fun search(name: String): List<FoundPlace>
}

/**
 * On-demand Open-Meteo lookups for an arbitrary point or place name, as the PKJS weather interceptors
 * need them. [WeatherSync] fetches a richer series for fixed, configured locations on a schedule; this
 * answers one watchapp request at a time, for coordinates or a name the watchapp chose, so it is its own
 * small client rather than a mode of the scheduled sync. Both endpoints are free and keyless.
 */
class OpenMeteoNow(private val client: HttpClient) : CurrentWeatherSource, PlaceSearch {

    override suspend fun now(latitude: Double, longitude: Double, temp: TempUnit, wind: WindUnit): CurrentWeather? =
        fetching("weather at %.3f,%.3f".format(java.util.Locale.ROOT, latitude, longitude)) {
            val response = client.get("https://api.open-meteo.com/v1/forecast") {
                parameter("latitude", latitude)
                parameter("longitude", longitude)
                parameter("current", CURRENT_FIELDS)
                parameter("daily", "weather_code,temperature_2m_max,temperature_2m_min,sunrise,sunset")
                parameter("temperature_unit", temp.param)
                parameter("wind_speed_unit", wind.param)
                // The point's own zone, so the daily rows are its local days and utc_offset_seconds is set.
                parameter("timezone", "auto")
                parameter("timeformat", "unixtime")
                // Yahoo's forecast list carries five days.
                parameter("forecast_days", 5)
            }
            if (!response.status.isSuccess()) {
                log.warn { "Open-Meteo returned HTTP ${response.status}" }
                return@fetching null
            }
            parseOpenMeteoNow(response.bodyAsText())
        }

    override suspend fun search(name: String): List<FoundPlace> = fetching("place \"$name\"") {
        val response = client.get("https://geocoding-api.open-meteo.com/v1/search") {
            parameter("name", name)
            parameter("count", 10)
            parameter("format", "json")
        }
        if (!response.status.isSuccess()) {
            log.warn { "Open-Meteo geocoding returned HTTP ${response.status}" }
            return@fetching null
        }
        // Open-Meteo ranks the candidates by population.
        LenientJson.decodeFromString<SearchResponse>(response.bodyAsText()).results.map {
            FoundPlace(it.name, it.latitude, it.longitude, it.admin1, it.country, it.countryCode)
        }
    }.orEmpty()

    private suspend fun <T> fetching(what: String, block: suspend () -> T?): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.warn { "Open-Meteo lookup of $what failed: ${e.message}" }
        null
    }

    private companion object {
        const val CURRENT_FIELDS = "temperature_2m,relative_humidity_2m,apparent_temperature,is_day," +
            "weather_code,cloud_cover,pressure_msl,surface_pressure,wind_speed_10m,wind_direction_10m," +
            "wind_gusts_10m,visibility"
    }
}

/** Decode an Open-Meteo forecast reply; null unless it has the current temperature and code and at least
 *  one complete daily row. */
internal fun parseOpenMeteoNow(body: String): CurrentWeather? =
    LenientJson.decodeFromString<NowResponse>(body).toCurrentWeather()

private fun NowResponse.toCurrentWeather(): CurrentWeather? {
    val cur = current ?: return null
    val temperature = cur.temperature ?: return null
    val code = cur.weatherCode ?: return null
    val d = daily ?: return null
    val count = minOf(d.time.size, d.weatherCode.size, d.tempMax.size, d.tempMin.size)
    // A model can leave a far day's values null; keep the days that are complete.
    val days = (0 until count).mapNotNull { i ->
        CurrentWeather.Day(
            startSec = d.time[i],
            weatherCode = d.weatherCode[i] ?: return@mapNotNull null,
            tempMax = d.tempMax[i] ?: return@mapNotNull null,
            tempMin = d.tempMin[i] ?: return@mapNotNull null,
            sunriseSec = d.sunrise.getOrNull(i),
            sunsetSec = d.sunset.getOrNull(i),
        )
    }
    if (days.isEmpty()) return null
    return CurrentWeather(
        timeSec = cur.time ?: days[0].startSec,
        utcOffsetSec = utcOffsetSeconds,
        isDay = cur.isDay != 0,
        temperature = temperature,
        apparentTemperature = cur.apparentTemperature,
        weatherCode = code,
        humidityPct = cur.humidity,
        pressureMslHpa = cur.pressureMsl,
        surfacePressureHpa = cur.surfacePressure,
        windSpeed = cur.windSpeed,
        windDirectionDeg = cur.windDirection,
        windGusts = cur.windGusts,
        cloudCoverPct = cur.cloudCover,
        visibilityM = cur.visibility,
        days = days,
    )
}

@Serializable
private data class NowResponse(
    @SerialName("utc_offset_seconds") val utcOffsetSeconds: Int = 0,
    val current: NowCurrent? = null,
    val daily: NowDaily? = null,
)

@Serializable
private data class NowCurrent(
    val time: Long? = null,
    @SerialName("is_day") val isDay: Int = 1,
    @SerialName("temperature_2m") val temperature: Double? = null,
    @SerialName("apparent_temperature") val apparentTemperature: Double? = null,
    @SerialName("weather_code") val weatherCode: Int? = null,
    @SerialName("relative_humidity_2m") val humidity: Int? = null,
    @SerialName("pressure_msl") val pressureMsl: Double? = null,
    @SerialName("surface_pressure") val surfacePressure: Double? = null,
    @SerialName("wind_speed_10m") val windSpeed: Double? = null,
    @SerialName("wind_direction_10m") val windDirection: Int? = null,
    @SerialName("wind_gusts_10m") val windGusts: Double? = null,
    @SerialName("cloud_cover") val cloudCover: Int? = null,
    val visibility: Double? = null,
)

@Serializable
private data class NowDaily(
    val time: List<Long> = emptyList(),
    @SerialName("weather_code") val weatherCode: List<Int?> = emptyList(),
    @SerialName("temperature_2m_max") val tempMax: List<Double?> = emptyList(),
    @SerialName("temperature_2m_min") val tempMin: List<Double?> = emptyList(),
    val sunrise: List<Long?> = emptyList(),
    val sunset: List<Long?> = emptyList(),
)

@Serializable
private data class SearchResponse(val results: List<SearchResult> = emptyList())

@Serializable
private data class SearchResult(
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val country: String? = null,
    @SerialName("country_code") val countryCode: String? = null,
    val admin1: String? = null,
)
