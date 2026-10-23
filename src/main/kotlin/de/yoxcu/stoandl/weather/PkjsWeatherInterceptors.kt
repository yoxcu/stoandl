@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package de.yoxcu.stoandl.weather

// The two interceptors, their request parsing and their response shapes are ported from the Core
// Devices Pebble app (github.com/coredevices/libpebble3, pebble/src/commonMain/kotlin/coredevices/pebble/
// weather/YahooWeatherInterceptor.kt and OpenWeather25Interceptor.kt), GPLv3 like stoandl. Changes: the
// data comes from Open-Meteo instead of Core's account-gated weather service, the condition codes map from
// the WMO code directly, and the fields upstream left as TODO zeros (wind, humidity, pressure, clouds,
// visibility, timezone) are filled.

import de.yoxcu.stoandl.config.StoandlConfig.WeatherLocation
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.rebble.libpebblecommon.js.HttpInterceptor
import io.rebble.libpebblecommon.js.InterceptResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.URLDecoder
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.uuid.Uuid

private val log = KotlinLogging.logger {}

/** The name a place gets when nothing better is known. */
private const val UNKNOWN_PLACE = "Unknown"

/** A configured location this close to the asked coordinates lends it its name. */
private const val KNOWN_LOCATION_RADIUS_KM = 10.0

// Responses mimic third-party JSON: every field present, nulls (an absent gust) left out as the APIs do.
private val ResponseJson = Json { encodeDefaults = true; explicitNulls = false }

/**
 * Where a PKJS weather request is, by name or by coordinates, for both interceptors. Lookups that leave
 * the host each sit behind their own opt-in: Nominatim reverse geocoding only when [reverseGeocodeEnabled]
 * (`weather.reverse_geocode`, which already gates it for the GPS weather entry); Open-Meteo place search
 * only runs for an interceptor that is itself switched on.
 */
class PkjsPlaces(
    /** The configured weather locations, read on each lookup so edits apply live. */
    private val knownLocations: () -> List<WeatherLocation>,
    private val reverseGeocodeEnabled: () -> Boolean,
    /** Coordinates → place name (Nominatim); called only when [reverseGeocodeEnabled]. */
    private val reverseGeocode: suspend (latitude: Double, longitude: Double) -> String?,
    private val search: PlaceSearch,
) {
    /** A name for the coordinates, or null when none is known. */
    suspend fun nameAt(latitude: Double, longitude: Double): String? {
        if (reverseGeocodeEnabled()) reverseGeocode(latitude, longitude)?.let { return it }
        return knownLocations()
            .map { it to distanceKm(latitude, longitude, it.latitude, it.longitude) }
            .filter { it.second <= KNOWN_LOCATION_RADIUS_KM }
            .minByOrNull { it.second }
            ?.first?.name
    }

    /**
     * Coordinates for a place name a watchface typed, often "District, City, Region, Country". A configured
     * location of that name answers without egress. Otherwise Open-Meteo, which matches bare place names
     * only, is searched for each comma-separated part in turn; a candidate whose region or country is one
     * of the other parts wins, and failing that the first part that found anything gives its top candidate.
     */
    suspend fun find(text: String): FoundPlace? {
        val parts = text.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        val first = parts.firstOrNull() ?: return null
        knownLocations().firstOrNull { it.name.equals(first, ignoreCase = true) }?.let {
            return FoundPlace(it.name, it.latitude, it.longitude, null, null, null)
        }
        if (parts.size == 1) return search.search(first).firstOrNull()
        var fallback: FoundPlace? = null
        // Three searches at most: a street or district, then the city, then the region.
        for (i in 0 until minOf(parts.size, 3)) {
            val candidates = search.search(parts[i])
            val qualifiers = parts.filterIndexed { j, _ -> j != i }
            candidates.firstOrNull { c ->
                listOfNotNull(c.region, c.country, c.countryCode).any { f -> qualifiers.any { it.equals(f, ignoreCase = true) } }
            }?.let { return it }
            if (fallback == null) fallback = candidates.firstOrNull()
        }
        return fallback
    }
}

/** The request leaves stoandl as is and the real service's reply comes back; null on a network failure. */
fun interface PassThrough {
    suspend fun get(url: String): InterceptResponse?
}

/** [PassThrough] over Ktor. Non-2xx replies are returned too: the caller decides what a 401 means. */
fun ktorPassThrough(client: HttpClient) = PassThrough { url ->
    try {
        val response = client.get(url)
        InterceptResponse(result = response.bodyAsText(), status = response.status.value)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.warn { "PKJS weather pass-through failed: ${e.message}" }
        null
    }
}

// ─────────────────────────────────────────────────────────────────────────────────────────────────
// OpenWeatherMap 2.5
// ─────────────────────────────────────────────────────────────────────────────────────────────────

/**
 * Answers `api.openweathermap.org/data/2.5/weather?lat=…&lon=…` for watchfaces whose embedded API key no
 * longer works. The request first goes to OpenWeatherMap unchanged — with the app's own key, the egress
 * the watchapp makes anyway — and a working key gets OWM's real reply. Only a 401 (the dead or revoked
 * key these old watchfaces ship) is answered from Open-Meteo, in OWM's 2.5 JSON shape and the units the
 * request asked for. City (`q=`), id and zip lookups and the XML mode are left alone.
 */
class OpenWeather25Interceptor(
    /** `weather.intercept_pkjs`, read on each request. */
    private val enabled: () -> Boolean,
    private val weather: CurrentWeatherSource,
    private val places: PkjsPlaces,
    private val passThrough: PassThrough,
) : HttpInterceptor {

    override fun shouldIntercept(url: String): Boolean = enabled() && parseOwm25Request(url) != null

    override suspend fun onIntercepted(url: String, method: String, body: String?, appUuid: Uuid): InterceptResponse {
        if (!enabled()) return InterceptResponse.ERROR
        val request = parseOwm25Request(url) ?: return InterceptResponse.ERROR
        val real = passThrough.get(url) ?: return InterceptResponse.ERROR
        if (real.status != 401) {
            log.debug { "OWM 2.5 for $appUuid answered by OpenWeatherMap (HTTP ${real.status})" }
            return real
        }
        log.info { "OWM 2.5 key of $appUuid rejected (401) — answering from Open-Meteo" }
        val now = weather.now(request.latitude, request.longitude, request.tempUnit, request.windUnit)
            ?: return InterceptResponse.ERROR
        val name = places.nameAt(request.latitude, request.longitude) ?: UNKNOWN_PLACE
        return InterceptResponse(ResponseJson.encodeToString(Owm25Response.serializer(), owm25Response(request, now, name)), 200)
    }
}

internal data class Owm25Request(
    val latitude: Double,
    val longitude: Double,
    /** OWM's `units`: `metric`, `imperial`, or anything else (absent, `standard`) for Kelvin. */
    val units: String?,
) {
    val kelvin: Boolean get() = units != "metric" && units != "imperial"
    val tempUnit: TempUnit get() = if (units == "imperial") TempUnit.FAHRENHEIT else TempUnit.CELSIUS
    val windUnit: WindUnit get() = if (units == "imperial") WindUnit.MPH else WindUnit.MS
}

/** A coordinates request for OWM 2.5 current weather in JSON, or null for anything else. */
internal fun parseOwm25Request(url: String): Owm25Request? {
    val u = looseUrl(url) ?: return null
    if (u.host != "api.openweathermap.org" || u.path != "/data/2.5/weather") return null
    val lat = u.params["lat"]?.trim()?.toDoubleOrNull() ?: return null
    val lon = u.params["lon"]?.trim()?.toDoubleOrNull() ?: return null
    val mode = u.params["mode"]?.lowercase()
    if (mode != null && mode != "json") return null
    return Owm25Request(lat, lon, u.params["units"]?.lowercase())
}

private fun owm25Response(request: Owm25Request, now: CurrentWeather, name: String): Owm25Response {
    fun temp(t: Double) = (if (request.kelvin) t + 273.15 else t).rounded(2)
    val today = now.days.first()
    val id = wmoToOwmId(now.weatherCode)
    val phrase = wmoToPhrase(now.weatherCode)
    return Owm25Response(
        coord = Owm25Response.Coord(lon = request.longitude, lat = request.latitude),
        weather = listOf(
            Owm25Response.Weather(
                id = id,
                main = owmGroup(id),
                description = if (phrase == "—") owmGroup(id).lowercase() else phrase.lowercase(),
                icon = owmIcon(id, now.isDay),
            ),
        ),
        base = "stations",
        main = Owm25Response.Main(
            temp = temp(now.temperature),
            feels_like = temp(now.apparentTemperature ?: now.temperature),
            temp_min = temp(minOf(today.tempMin, now.temperature)),
            temp_max = temp(maxOf(today.tempMax, now.temperature)),
            pressure = now.pressureMslHpa?.roundToInt() ?: 0,
            humidity = now.humidityPct ?: 0,
            sea_level = now.pressureMslHpa?.roundToInt(),
            grnd_level = now.surfacePressureHpa?.roundToInt(),
        ),
        // OWM caps visibility at 10 km.
        visibility = now.visibilityM?.coerceAtMost(10_000.0)?.roundToInt() ?: 10_000,
        wind = Owm25Response.Wind(
            speed = (now.windSpeed ?: 0.0).rounded(2),
            deg = now.windDirectionDeg ?: 0,
            gust = now.windGusts?.rounded(2),
        ),
        clouds = Owm25Response.Clouds(all = now.cloudCoverPct ?: 0),
        dt = now.timeSec,
        sys = Owm25Response.Sys(
            type = 1,
            id = 0,
            country = "",
            sunrise = today.sunriseSec ?: 0,
            sunset = today.sunsetSec ?: 0,
        ),
        timezone = now.utcOffsetSec,
        id = 0,
        name = name,
        cod = 200,
    )
}

/** WMO code → OWM condition id (openweathermap.org/weather-conditions). OWM has no "unknown"; clear it is. */
private fun wmoToOwmId(code: Int): Int = when (code) {
    0 -> 800
    1 -> 801
    2 -> 802
    3 -> 804
    45, 48 -> 741                       // fog
    51 -> 300
    53 -> 301
    55 -> 302
    56, 57, 66, 67 -> 511               // freezing drizzle / rain
    61 -> 500
    63 -> 501
    65 -> 502
    71, 77 -> 600
    73 -> 601
    75 -> 602
    80 -> 520
    81 -> 521
    82 -> 522
    85 -> 620
    86 -> 622
    95 -> 211
    96 -> 201                           // thunderstorm with (slight) hail: closest is "with rain"
    99 -> 202
    else -> 800
}

private fun owmGroup(id: Int): String = when (id / 100) {
    2 -> "Thunderstorm"
    3 -> "Drizzle"
    5 -> "Rain"
    6 -> "Snow"
    7 -> "Fog"
    else -> if (id == 800) "Clear" else "Clouds"
}

private fun owmIcon(id: Int, isDay: Boolean): String {
    val base = when {
        id / 100 == 2 -> "11"
        id / 100 == 3 || id in 520..531 -> "09"
        id == 511 || id / 100 == 6 -> "13"
        id / 100 == 5 -> "10"
        id / 100 == 7 -> "50"
        id == 800 -> "01"
        id == 801 -> "02"
        id == 802 -> "03"
        else -> "04"
    }
    return base + if (isDay) "d" else "n"
}

@Suppress("PropertyName") // OWM's own snake_case field names
@Serializable
private data class Owm25Response(
    val coord: Coord,
    val weather: List<Weather>,
    val base: String,
    val main: Main,
    val visibility: Int,
    val wind: Wind,
    val clouds: Clouds,
    val dt: Long,
    val sys: Sys,
    val timezone: Int,
    val id: Long,
    val name: String,
    val cod: Int,
) {
    @Serializable
    data class Coord(val lon: Double, val lat: Double)

    @Serializable
    data class Weather(val id: Int, val main: String, val description: String, val icon: String)

    @Serializable
    data class Main(
        val temp: Double,
        val feels_like: Double,
        val temp_min: Double,
        val temp_max: Double,
        val pressure: Int,
        val humidity: Int,
        val sea_level: Int?,
        val grnd_level: Int?,
    )

    @Serializable
    data class Wind(val speed: Double, val deg: Int, val gust: Double?)

    @Serializable
    data class Clouds(val all: Int)

    @Serializable
    data class Sys(val type: Int, val id: Int, val country: String, val sunrise: Long, val sunset: Long)
}

// ─────────────────────────────────────────────────────────────────────────────────────────────────
// Yahoo YQL
// ─────────────────────────────────────────────────────────────────────────────────────────────────

/**
 * Answers Yahoo's YQL weather API (`query.yahooapis.com/v1/public/yql`), shut down in 2019 but still
 * called by many 2014–2016 watchfaces. Two query kinds are understood, each for a place given as
 * `text="(lat,lon)"`, `text="place name"` or a `woeid`:
 *
 * - `… from geo.places …` → a place with a woeid. Yahoo's woeids are gone, so this hands out its own and
 *   remembers them (in memory, bounded) for the watchface's follow-up `where woeid=…` weather query.
 * - `… from weather.forecast …` → current conditions and a five-day forecast in `u="c"` or `u="f"`.
 *
 * A query without `limit 1` gets one channel per forecast day, the shape most of these watchfaces were
 * written against; the apps in [UUIDS_USE_STANDARD_RESPONSE] read Yahoo's documented single channel.
 */
class YahooWeatherInterceptor(
    /** `weather.intercept_pkjs`, read on each request. */
    private val enabled: () -> Boolean,
    private val weather: CurrentWeatherSource,
    private val places: PkjsPlaces,
) : HttpInterceptor {
    private val woeids = object : LinkedHashMap<Int, YahooPlace>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, YahooPlace>?) = size > WOEID_CACHE_SIZE
    }
    private var lastWoeid = 0
    private val woeidMutex = Mutex()

    override fun shouldIntercept(url: String): Boolean = enabled() && parseYahooRequest(url) != null

    override suspend fun onIntercepted(url: String, method: String, body: String?, appUuid: Uuid): InterceptResponse {
        if (!enabled()) return InterceptResponse.ERROR
        val request = parseYahooRequest(url) ?: return InterceptResponse.ERROR
        val place = resolve(request.place)
        if (place == null) {
            log.info { "Yahoo weather for $appUuid: place ${request.place} not found" }
            return InterceptResponse.ERROR
        }
        val json = when (request) {
            is YahooRequest.Location -> ResponseJson.encodeToString(YahooPlaceResponse.serializer(), yahooPlaceResponse(place))
            is YahooRequest.Weather -> {
                val now = weather.now(
                    place.latitude, place.longitude,
                    if (request.fahrenheit) TempUnit.FAHRENHEIT else TempUnit.CELSIUS,
                    if (request.fahrenheit) WindUnit.MPH else WindUnit.KMH,
                ) ?: return InterceptResponse.ERROR
                yahooWeatherJson(place, now, request.fahrenheit,
                    perDay = request.multiItems && appUuid !in UUIDS_USE_STANDARD_RESPONSE)
            }
        }
        log.debug { "Yahoo weather for $appUuid answered from Open-Meteo (${place.name})" }
        return InterceptResponse(json, 200)
    }

    private suspend fun resolve(params: YahooPlaceParams): YahooPlace? = woeidMutex.withLock {
        when (params) {
            is YahooPlaceParams.Woeid -> woeids[params.woeid]
            is YahooPlaceParams.LatLon ->
                woeids.values.firstOrNull { it.latitude == params.latitude && it.longitude == params.longitude }
                    ?: remember { woeid ->
                        YahooPlace(woeid, params.latitude, params.longitude,
                            places.nameAt(params.latitude, params.longitude) ?: UNKNOWN_PLACE, "", "", suppliedName = null)
                    }
            is YahooPlaceParams.PlaceName ->
                woeids.values.firstOrNull { it.suppliedName.equals(params.name, ignoreCase = true) }
                    ?: places.find(params.name)?.let { found ->
                        remember { woeid ->
                            YahooPlace(woeid, found.latitude, found.longitude, found.name, found.region.orEmpty(),
                                found.country.orEmpty(), suppliedName = params.name)
                        }
                    }
        }
    }

    private inline fun remember(make: (woeid: Int) -> YahooPlace): YahooPlace =
        make(++lastWoeid).also { woeids[it.woeid] = it }

    private companion object {
        const val WOEID_CACHE_SIZE = 64

        val UUIDS_USE_STANDARD_RESPONSE = setOf(
            Uuid.parse("1f0b0701-cc8f-47ec-86e7-7181397f9a25"), // Weather Land
            Uuid.parse("1f0b0701-cc8f-47ec-86e7-7181397f9a52"), // Real Weather
            Uuid.parse("1f0b0701-cc8f-47ec-86e7-7181397f8888"), // Love Weather
        )
    }
}

private data class YahooPlace(
    val woeid: Int,
    val latitude: Double,
    val longitude: Double,
    val name: String,
    val region: String,
    val country: String,
    /** The text a place-name query used, so a repeat finds this entry without another search. */
    val suppliedName: String?,
)

internal sealed interface YahooPlaceParams {
    data class LatLon(val latitude: Double, val longitude: Double) : YahooPlaceParams
    data class PlaceName(val name: String) : YahooPlaceParams
    data class Woeid(val woeid: Int) : YahooPlaceParams
}

internal sealed interface YahooRequest {
    val place: YahooPlaceParams

    data class Location(override val place: YahooPlaceParams) : YahooRequest

    /** [multiItems]: no `limit 1`, so Yahoo returned one channel per forecast day. */
    data class Weather(override val place: YahooPlaceParams, val fahrenheit: Boolean, val multiItems: Boolean) : YahooRequest
}

private val YQL_LAT_LON = Regex("""text\s*=\s*["']\(\s*(-?[\d.]+)\s*,\s*(-?[\d.]+)\s*\)["']""", RegexOption.IGNORE_CASE)
private val YQL_PLACE = Regex("""text\s*=\s*(?:"([^"]+)"|'([^']+)')""", RegexOption.IGNORE_CASE)
private val YQL_WOEID = Regex("""woeid\s*=\s*["']?(\d+)""", RegexOption.IGNORE_CASE)
private val YQL_UNITS = Regex("""\bu\s*=\s*["']([fc])["']""", RegexOption.IGNORE_CASE)
private val YQL_LIMIT = Regex("""\blimit\s+(\d+)""", RegexOption.IGNORE_CASE)

/**
 * A YQL place or weather query, or null for anything else.
 * web.archive.org/web/20160206195745/https://developer.yahoo.com/weather/documentation.html
 */
internal fun parseYahooRequest(url: String): YahooRequest? {
    val u = looseUrl(url) ?: return null
    if (u.host != "query.yahooapis.com" || u.path != "/v1/public/yql") return null
    val q = u.params["q"] ?: return null

    val latLon = YQL_LAT_LON.find(q)?.let { m ->
        val lat = m.groupValues[1].toDoubleOrNull()
        val lon = m.groupValues[2].toDoubleOrNull()
        if (lat != null && lon != null) YahooPlaceParams.LatLon(lat, lon) else null
    }
    val place = latLon
        ?: YQL_PLACE.find(q)?.let { m -> YahooPlaceParams.PlaceName(m.groupValues[1].ifEmpty { m.groupValues[2] }) }
        ?: YQL_WOEID.find(q)?.groupValues?.get(1)?.toIntOrNull()?.let { YahooPlaceParams.Woeid(it) }
        ?: return null

    // "select … from weather.forecast where woeid in (select woeid from geo.places …)" asks for weather;
    // whichever table the outer select reads decides the kind.
    val placesAt = q.indexOf("from geo.places", ignoreCase = true)
    val weatherAt = q.indexOf("from weather.forecast", ignoreCase = true)
    return when {
        placesAt != -1 && (weatherAt == -1 || placesAt < weatherAt) -> YahooRequest.Location(place)
        weatherAt != -1 -> YahooRequest.Weather(
            place = place,
            // Yahoo's default unit was Fahrenheit.
            fahrenheit = YQL_UNITS.find(q)?.groupValues?.get(1)?.lowercase() != "c",
            multiItems = YQL_LIMIT.find(q)?.groupValues?.get(1)?.toIntOrNull() != 1,
        )
        else -> null
    }
}

private fun yahooPlaceResponse(place: YahooPlace) = YahooPlaceResponse(
    YahooPlaceResponse.Query(
        YahooPlaceResponse.Results(
            YahooPlaceResponse.Place(
                woeid = place.woeid,
                name = place.name,
                admin1 = YahooContent(place.region.ifEmpty { place.country }.ifEmpty { UNKNOWN_PLACE }),
                locality1 = YahooContent(place.name),
                country = YahooContent(place.country),
            ),
        ),
    ),
)

private fun yahooWeatherJson(place: YahooPlace, now: CurrentWeather, fahrenheit: Boolean, perDay: Boolean): String {
    val condition = YahooCondition(
        temp = now.temperature.roundToInt(),
        code = wmoToYahooCode(now.weatherCode, now.isDay),
        text = wmoToPhrase(now.weatherCode),
        date = local(now.timeSec, now.utcOffsetSec).format(YAHOO_DATE),
    )
    val forecast = now.days.take(5).map { day ->
        val date = local(day.startSec, now.utcOffsetSec)
        YahooForecast(
            day = date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.US),
            date = date.format(YAHOO_DATE),
            low = day.tempMin.roundToInt(),
            high = day.tempMax.roundToInt(),
            text = wmoToPhrase(day.weatherCode),
            code = wmoToYahooCode(day.weatherCode, isDay = true),
        )
    }
    val today = now.days.first()
    val wind = YahooWind(
        chill = (now.apparentTemperature ?: now.temperature).roundToInt(),
        direction = now.windDirectionDeg ?: 0,
        speed = (now.windSpeed ?: 0.0).roundToInt(),
    )
    val atmosphere = YahooAtmosphere(
        humidity = now.humidityPct ?: 0,
        visibility = ((now.visibilityM ?: 0.0) / if (fahrenheit) 1609.344 else 1000.0).rounded(1),
        pressure = (now.pressureMslHpa ?: 0.0).let { if (fahrenheit) (it * 0.02953).rounded(2) else it.rounded(1) },
        rising = 0,
    )
    val location = YahooChannelLocation(city = place.name, region = place.region, country = place.country)
    val units = if (fahrenheit) YahooUnits("F", "mi", "in", "mph") else YahooUnits("C", "km", "mb", "km/h")
    val astronomy = YahooAstronomy(
        sunrise = today.sunriseSec?.let { yahooTime(local(it, now.utcOffsetSec)) }.orEmpty(),
        sunset = today.sunsetSec?.let { yahooTime(local(it, now.utcOffsetSec)) }.orEmpty(),
    )
    return if (perDay) {
        val channels = forecast.map { day ->
            YahooDayChannel(YahooDayItem(condition, day), wind, atmosphere, location, units, astronomy)
        }
        ResponseJson.encodeToString(YahooPerDayResponse.serializer(),
            YahooPerDayResponse(YahooPerDayResponse.Query(YahooPerDayResponse.Results(channels))))
    } else {
        val channel = YahooChannel(YahooItem(condition, forecast), wind, atmosphere, location, units, astronomy)
        ResponseJson.encodeToString(YahooWeatherResponse.serializer(),
            YahooWeatherResponse(YahooWeatherResponse.Query(YahooWeatherResponse.Results(channel))))
    }
}

/**
 * WMO code → Yahoo condition code (the table in the archived Yahoo docs). Direct rather than through
 * [wmoToWeatherType]: the watch's ten icon types would fold fog, thunder and night into rain or sun, and
 * the watchfaces draw Yahoo's own finer icon set. Forecast days are drawn as day.
 */
private fun wmoToYahooCode(code: Int, isDay: Boolean): Int = when (code) {
    0 -> if (isDay) 32 else 31          // sunny / clear (night)
    1 -> if (isDay) 34 else 33          // fair
    2 -> if (isDay) 30 else 29          // partly cloudy
    3 -> 26                             // cloudy
    45, 48 -> 20                        // foggy
    51, 53, 55 -> 9                     // drizzle
    56, 57 -> 8                         // freezing drizzle
    61 -> 11                            // showers
    63, 65 -> 12
    66, 67 -> 10                        // freezing rain
    71 -> 14                            // light snow showers
    73 -> 16                            // snow
    75 -> 41                            // heavy snow
    77 -> 13                            // snow flurries
    80 -> 40                            // scattered showers
    81, 82 -> 12
    85 -> 42                            // scattered snow showers
    86 -> 46                            // snow showers
    95 -> 4                             // thunderstorms
    96, 99 -> 3                         // severe thunderstorms
    else -> 3200                        // not available
}

private val YAHOO_DATE = DateTimeFormatter.ofPattern("yyyy/MM/dd", Locale.ROOT)

private fun local(epochSec: Long, utcOffsetSec: Int): OffsetDateTime =
    Instant.ofEpochSecond(epochSec).atOffset(ZoneOffset.ofTotalSeconds(utcOffsetSec))

/** Yahoo's astronomy time, e.g. "7:08 am". */
private fun yahooTime(t: OffsetDateTime): String {
    val hour12 = (t.hour % 12).let { if (it == 0) 12 else it }
    return "%d:%02d %s".format(Locale.ROOT, hour12, t.minute, if (t.hour < 12) "am" else "pm")
}

@Serializable private data class YahooContent(val content: String)

@Serializable
private data class YahooPlaceResponse(val query: Query) {
    @Serializable data class Query(val results: Results)
    @Serializable data class Results(val place: Place)
    @Serializable data class Place(
        val woeid: Int,
        val name: String,
        val admin1: YahooContent,
        val locality1: YahooContent,
        val country: YahooContent,
    )
}

@Serializable private data class YahooCondition(val temp: Int, val code: Int, val text: String, val date: String)
@Serializable private data class YahooForecast(
    val day: String, val date: String, val low: Int, val high: Int, val text: String, val code: Int,
)
@Serializable private data class YahooWind(val chill: Int, val direction: Int, val speed: Int)
@Serializable private data class YahooAtmosphere(val humidity: Int, val visibility: Double, val pressure: Double, val rising: Int)
@Serializable private data class YahooChannelLocation(val city: String, val region: String, val country: String)
@Serializable private data class YahooUnits(val temperature: String, val distance: String, val pressure: String, val speed: String)
@Serializable private data class YahooAstronomy(val sunrise: String, val sunset: String)

@Serializable private data class YahooItem(val condition: YahooCondition, val forecast: List<YahooForecast>)
@Serializable private data class YahooChannel(
    val item: YahooItem,
    val wind: YahooWind,
    val atmosphere: YahooAtmosphere,
    val location: YahooChannelLocation,
    val units: YahooUnits,
    val astronomy: YahooAstronomy,
)

@Serializable
private data class YahooWeatherResponse(val query: Query) {
    @Serializable data class Query(val results: Results)
    @Serializable data class Results(val channel: YahooChannel)
}

@Serializable private data class YahooDayItem(val condition: YahooCondition, val forecast: YahooForecast)
@Serializable private data class YahooDayChannel(
    val item: YahooDayItem,
    val wind: YahooWind,
    val atmosphere: YahooAtmosphere,
    val location: YahooChannelLocation,
    val units: YahooUnits,
    val astronomy: YahooAstronomy,
)

@Serializable
private data class YahooPerDayResponse(val query: Query) {
    @Serializable data class Query(val results: Results)
    @Serializable data class Results(val channel: List<YahooDayChannel>)
}

// ─────────────────────────────────────────────────────────────────────────────────────────────────
// Shared helpers
// ─────────────────────────────────────────────────────────────────────────────────────────────────

/** The parts of a URL the interceptors match on. Query keys are lower-cased (old apps send `APPID`). */
internal data class LooseUrl(val host: String, val path: String, val params: Map<String, String>)

/**
 * Split a URL leniently. PKJS hands over whatever string the app passed to `XMLHttpRequest.open`, and
 * YQL URLs in old watchfaces often carry raw spaces and quotes, which `java.net.URI` rejects.
 */
internal fun looseUrl(url: String): LooseUrl? {
    val rest = url.trim().substringAfter("://", "").substringBefore('#')
    if (rest.isEmpty()) return null
    val hostAndPath = rest.substringBefore('?')
    val host = hostAndPath.substringBefore('/').substringAfterLast('@').substringBefore(':').lowercase()
    val path = "/" + hostAndPath.substringAfter('/', "").trimEnd('/')
    val params = LinkedHashMap<String, String>()
    rest.substringAfter('?', "").split('&').filter { it.isNotEmpty() }.forEach { pair ->
        params.putIfAbsent(decodeQueryPart(pair.substringBefore('=')).lowercase(), decodeQueryPart(pair.substringAfter('=', "")))
    }
    return LooseUrl(host, path, params)
}

private fun decodeQueryPart(s: String): String =
    runCatching { URLDecoder.decode(s, Charsets.UTF_8) }.getOrDefault(s)

private fun Double.rounded(decimals: Int): Double {
    val f = 10.0.pow(decimals)
    return Math.round(this * f) / f
}
