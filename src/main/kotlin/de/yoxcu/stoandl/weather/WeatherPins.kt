@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package de.yoxcu.stoandl.weather

import io.github.oshai.kotlinlogging.KotlinLogging
import io.rebble.libpebblecommon.SystemAppIDs.WEATHER_APP_UUID
import io.rebble.libpebblecommon.connection.LibPebble
import io.rebble.libpebblecommon.database.entity.buildTimelinePin
import io.rebble.libpebblecommon.packets.blobdb.TimelineIcon
import io.rebble.libpebblecommon.packets.blobdb.TimelineItem
import io.rebble.libpebblecommon.weather.WeatherLocationData
import io.rebble.libpebblecommon.weather.WeatherType
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Instant
import kotlin.uuid.Uuid

private val log = KotlinLogging.logger {}

/** One half of a day's forecast (daytime or overnight): a representative [temp], condition [type]
 *  and a short human [phrase] for that period. */
internal data class DayPart(val temp: Int, val type: WeatherType, val phrase: String)

/** A single day's forecast: its sunrise/sunset instants plus the [day] and [night] parts. */
internal data class DayForecast(
    val sunrise: Instant,
    val sunset: Instant,
    val day: DayPart,
    val night: DayPart,
)

/** A location's full fetch result: the [appData] pushed to the watch Weather app (always present,
 *  possibly a "failed" marker) plus up to three [days] of forecast used to build timeline pins
 *  (empty when the fetch failed or returned no hourly data). */
internal data class LocationForecast(
    val appData: WeatherLocationData,
    val name: String,
    val isCurrentLocation: Boolean,
    val days: List<DayForecast>,
)

/**
 * Emits weather timeline pins, replicating the original Core companion app's `WeatherFetcher`
 * behaviour in an account-free way: for each of today/tomorrow/day-after it inserts a **Sunrise**
 * pin (at sunrise) and a **Sunset** pin (at sunset) for the primary location, with the other
 * locations folded into the pin's detail view. Each slot has a fixed UUID so re-syncs replace the
 * pin in place; a slot with no data is deleted so stale pins don't linger.
 *
 * Pins are written via the public [LibPebble] timeline API ([LibPebble.insertOrReplace] /
 * [LibPebble.delete]); libpebble3 syncs them to the watch's Pin BlobDB.
 */
internal class WeatherPins(private val libPebble: LibPebble) {

    /** Insert/refresh pins from the latest fetch. The primary location is the first one that has
     *  forecast data (the GPS "current location", when enabled, sorts first), so a transient failure
     *  of one location doesn't wipe the timeline as long as another has data. */
    fun sync(forecasts: List<LocationForecast>) {
        val primary = forecasts.firstOrNull { it.days.isNotEmpty() }
        val others = forecasts.filter { it !== primary && it.days.isNotEmpty() }
        val now = Clock.System.now()
        var pins = 0
        for (slot in PinDay.entries) {
            pins += buildForDay(primary, others, slot, now)
        }
        log.info { "Weather pins updated: $pins pin(s) across ${PinDay.entries.size} day(s)" }
    }

    /** Remove all weather pins (used when pins are disabled, or weather is turned off). */
    fun clear() {
        for (slot in PinDay.entries) {
            libPebble.delete(slot.dayUuid)
            libPebble.delete(slot.nightUuid)
        }
        log.info { "Weather pins cleared" }
    }

    /** Returns the number of pins inserted for this day (0 if its slot was deleted for lack of data). */
    private fun buildForDay(
        primary: LocationForecast?,
        others: List<LocationForecast>,
        slot: PinDay,
        now: Instant,
    ): Int {
        val forecast = primary?.days?.getOrNull(slot.ordinal)
        if (primary == null || forecast == null) {
            libPebble.delete(slot.dayUuid)
            libPebble.delete(slot.nightUuid)
            return 0
        }
        // "high°/low°" — the same daily span shown on both the sunrise and sunset pins.
        val subtitle = "${forecast.day.temp}°/${forecast.night.temp}°"
        val otherDays = others.mapNotNull { it.days.getOrNull(slot.ordinal)?.let { d -> it.name to d } }
        // Each other-location detail line: "<name>\n<high>°/<low>°, <phrase for this period>".
        fun otherLines(part: (DayForecast) -> DayPart) =
            otherDays.map { (name, d) -> "$name\n${d.day.temp}°/${d.night.temp}°, ${part(d).phrase}" }

        insertPin(
            uuid = slot.dayUuid,
            title = "Sunrise",
            subtitle = subtitle,
            part = forecast.day,
            timestamp = forecast.sunrise,
            location = primary.name,
            now = now,
            otherLocations = otherLines { it.day },
        )
        insertPin(
            uuid = slot.nightUuid,
            title = "Sunset",
            subtitle = subtitle,
            part = forecast.night,
            timestamp = forecast.sunset,
            location = primary.name,
            now = now,
            otherLocations = otherLines { it.night },
        )
        return 2
    }

    private fun insertPin(
        uuid: Uuid,
        title: String,
        subtitle: String,
        part: DayPart,
        timestamp: Instant,
        location: String,
        now: Instant,
        otherLocations: List<String>,
    ) {
        val pin = buildTimelinePin(parentId = WEATHER_APP_UUID, timestamp = timestamp) {
            itemID = uuid
            duration = Duration.ZERO
            layout = TimelineItem.Layout.WeatherPin
            attributes {
                title { title }
                subtitle { subtitle }
                body { part.phrase }
                tinyIcon { part.type.toWeatherIcon() }
                largeIcon { part.type.toWeatherIcon() }
                lastUpdated { now }
                location { location }
                if (otherLocations.isNotEmpty()) {
                    headings { listOf(" ") }
                    paragraphs { listOf(otherLocations.joinToString("\n—\n")) }
                }
            }
            actions {
                action(TimelineItem.Action.Type.OpenWatchapp) {
                    attributes { title { "More" } }
                }
            }
        }
        libPebble.insertOrReplace(pin)
    }
}

/** The three timeline-pin day slots, each with a stable day/night pin UUID so re-syncs replace
 *  the existing pins rather than accumulating new ones. */
private enum class PinDay(val dayUuid: Uuid, val nightUuid: Uuid) {
    Today(
        Uuid.parse("a1c0ffee-0001-4d01-8a01-7e6b5c4d3e21"),
        Uuid.parse("a1c0ffee-0002-4d02-8a02-7e6b5c4d3e22"),
    ),
    Tomorrow(
        Uuid.parse("a1c0ffee-0003-4d03-8a03-7e6b5c4d3e23"),
        Uuid.parse("a1c0ffee-0004-4d04-8a04-7e6b5c4d3e24"),
    ),
    DayAfterTomorrow(
        Uuid.parse("a1c0ffee-0005-4d05-8a05-7e6b5c4d3e25"),
        Uuid.parse("a1c0ffee-0006-4d06-8a06-7e6b5c4d3e26"),
    ),
}

/** Map a Pebble [WeatherType] to the matching system timeline icon (same mapping the Core app uses). */
private fun WeatherType.toWeatherIcon(): TimelineIcon = when (this) {
    WeatherType.PartlyCloudy -> TimelineIcon.PartlyCloudy
    WeatherType.CloudyDay -> TimelineIcon.CloudyDay
    WeatherType.LightSnow -> TimelineIcon.LightSnow
    WeatherType.LightRain -> TimelineIcon.LightRain
    WeatherType.HeavyRain -> TimelineIcon.HeavyRain
    WeatherType.HeavySnow -> TimelineIcon.HeavySnow
    WeatherType.Generic -> TimelineIcon.TimelineWeather
    WeatherType.Sun -> TimelineIcon.TimelineSun
    WeatherType.RainAndSnow -> TimelineIcon.RainingAndSnowing
    WeatherType.Unknown -> TimelineIcon.TimelineWeather
}
