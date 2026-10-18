package de.yoxcu.stoandl.weather

import de.yoxcu.stoandl.config.StoandlConfig
import de.yoxcu.stoandl.config.StoandlConfig.WeatherLocation
import kotlinx.coroutines.withTimeoutOrNull
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Find a city by name and add it to `weather.locations`: the backend of `stoandl weather search` and the
 * GUI's location search. The lookup is an Open-Meteo geocoding call, so it is refused unless weather sync
 * is on and `weather.place_search` was turned on; adding a found place makes no request.
 */
class WeatherPlaces(
    private val search: PlaceSearch,
    private val config: () -> StoandlConfig,
    /** Persist a new `weather.locations` value (the SetConfig path); returns its status string. */
    private val writeLocations: (String) -> String,
) {
    /** `ok:` + one [placeRecord] per line, most likely first; `none:` / `disabled:` / `error:`. */
    suspend fun search(query: String): String {
        placeSearchRefusal(config())?.let { return "disabled:$it" }
        val q = query.trim()
        if (q.isEmpty()) return "error:Type a place name to search for"
        // Under the GUI's 10 s D-Bus call timeout, so a slow reply still answers the caller.
        val found = withTimeoutOrNull(SEARCH_TIMEOUT_MS) { search.search(q) }.orEmpty()
        if (found.isEmpty()) return "none:No place called \"$q\" found (or Open-Meteo couldn't be reached)"
        return "ok:" + found.joinToString("\n", transform = ::placeRecord)
    }

    /** Add [name] at [latitude]/[longitude], replacing a location of the same name. */
    fun add(name: String, latitude: Double, longitude: Double): String {
        val label = locationLabel(name) ?: return "error:The location needs a name"
        if (latitude.isNaN() || latitude !in -90.0..90.0) return "error:Latitude must be between -90 and 90"
        if (longitude.isNaN() || longitude !in -180.0..180.0) return "error:Longitude must be between -180 and 180"
        val existing = config().weatherLocations
        val replaced = existing.any { it.name.equals(label, ignoreCase = true) }
        val entry = WeatherLocation(label, roundCoordinate(latitude), roundCoordinate(longitude))
        val result = writeLocations(locationsValue(upsertLocation(existing, entry)))
        if (!result.startsWith("ok")) return result
        val coords = "${coordinateText(entry.latitude)}, ${coordinateText(entry.longitude)}"
        return "ok:${if (replaced) "Updated" else "Added"} $label ($coords)"
    }
}

private const val SEARCH_TIMEOUT_MS = 8_000L

/** Why a place search may not run now, or null when it may. */
internal fun placeSearchRefusal(c: StoandlConfig): String? = when {
    !c.weatherEnabled -> "Weather sync is off (weather.enabled)"
    !c.weatherPlaceSearch ->
        "Place search is off: it sends the name to Open-Meteo's geocoder. Turn on weather.place_search"
    else -> null
}

/** `name \t lat \t lon \t region \t country` — the name and the coordinates [WeatherPlaces.add] takes. */
internal fun placeRecord(p: FoundPlace): String = listOf(
    locationLabel(p.name).orEmpty(),
    coordinateText(roundCoordinate(p.latitude)),
    coordinateText(roundCoordinate(p.longitude)),
    p.region.orEmpty().clean(),
    p.country.orEmpty().clean(),
).joinToString("\t")

/** [locations] with [entry] in place of a same-named one (case-insensitive), else appended. */
internal fun upsertLocation(locations: List<WeatherLocation>, entry: WeatherLocation): List<WeatherLocation> {
    val i = locations.indexOfFirst { it.name.equals(entry.name, ignoreCase = true) }
    return if (i < 0) locations + entry else locations.toMutableList().also { it[i] = entry }
}

/** The `weather.locations` value: comma-separated `Name:lat:lon`, as the schema writes it. */
internal fun locationsValue(locations: List<WeatherLocation>): String =
    locations.joinToString(",") { "${it.name}:${coordinateText(it.latitude)}:${coordinateText(it.longitude)}" }

/** A name `weather.locations` can hold: no commas (the list separator), `#` (a stoandl.conf comment) or
 *  framing characters. */
internal fun locationLabel(raw: String): String? = raw.clean().replace(Regex("[,#]"), " ")
    .replace(Regex(" {2,}"), " ").trim().takeIf { it.isNotEmpty() }

private fun String.clean(): String = replace(Regex("[\\t\\r\\n]"), " ").trim()

/** Four decimals (about 11 m): plenty for a forecast, and a short conf line. */
internal fun roundCoordinate(v: Double): Double = BigDecimal(v).setScale(4, RoundingMode.HALF_UP).toDouble()

/** Plain decimal notation (never `1.0E-4`), trailing zeros dropped. */
internal fun coordinateText(v: Double): String = BigDecimal(v.toString()).stripTrailingZeros().toPlainString()
    .let { if (it == "-0") "0" else it }
