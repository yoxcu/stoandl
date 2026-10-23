@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package de.yoxcu.stoandl.weather

import de.yoxcu.stoandl.location.GeoClueFix
import io.rebble.libpebblecommon.weather.WeatherLocationData
import io.rebble.libpebblecommon.weather.WeatherType
import kotlinx.coroutines.runBlocking
import java.io.IOException
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.uuid.Uuid

class WeatherPositionTest {
    private var now = 1_792_000_000_000L
    private val fixes = ArrayDeque<GeoClueFix?>()
    private var fixCalls = 0
    private val position = StickyPosition({ fixCalls++; fixes.removeFirst() }, clock = { now })

    // A GeoClue CITY fix (they come at ±3 km), or with the given accuracy in metres.
    private fun at(lat: Double, lon: Double, accuracy: Double? = 3_100.0) = GeoClueFix(lat, lon, accuracy, null, null, null)
    private fun fixesAt(vararg coords: Pair<Double, Double>) = coords.forEach { (lat, lon) -> fixes += at(lat, lon) }

    // GeoClue CITY fixes around one spot, each within the radius of the first (0.01° lat ≈ 1.1 km).
    private val jittered = listOf(
        48.2470 to 12.5220, 48.2532 to 12.5301, 48.2405 to 12.5150, 48.2511 to 12.5099, 48.2440 to 12.5348,
    )

    // Open-Meteo answers to the exact coordinate: sunrise to the second, the grid cell's temperatures.
    private fun forecastAt(name: String, lat: Double, lon: Double): LocationForecast {
        val shift = ((lat + lon) * 1000).roundToLong()
        val days = (0 until 3).map { i ->
            DayForecast(
                Instant.fromEpochSeconds(1_792_561_100L + i * 86_400L + shift), Instant.fromEpochSeconds(1_792_600_000L + i * 86_400L + shift),
                DayPart((lat * 10).roundToInt() - 470, WeatherType.Sun, "Clear"), DayPart(3, WeatherType.Sun, "Clear"),
            )
        }
        return LocationForecast(WeatherLocationData.WeatherLocationDataFailed(Uuid.random()), name, true, days)
    }

    private fun place(name: String) = """{"address":{"town":"$name"}}"""

    @Test
    fun `jittered fixes within the radius write no pins after the first sync`() = runBlocking {
        var lookups = 0
        val nominatim = NominatimReverseGeocoder { _, _ -> if (lookups++ == 0) place("Mühldorf") else throw IOException("Connection reset") }
        val written = mutableListOf<Any>()
        val pins = WeatherPins({ written += it }, { written += it })
        fixesAt(*jittered.toTypedArray())
        repeat(jittered.size) {
            val (lat, lon) = position.resolve(retry = false)!!
            pins.sync(listOf(forecastAt(nominatim.nameAt(lat, lon) ?: "Current location", lat, lon)))
        }
        assertEquals(jittered.size, fixCalls)
        assertEquals(1, lookups, "the same position needs no second reverse geocode")
        assertEquals(6, written.size, "only the first sync writes the six pins")
    }

    @Test
    fun `the raw fixes would rewrite the pins`() {
        // The baseline the sticky position fixes: every jittered fix gives other pin content.
        val written = mutableListOf<Any>()
        val pins = WeatherPins({ written += it }, { written += it })
        jittered.forEach { (lat, lon) -> pins.sync(listOf(forecastAt("Mühldorf", lat, lon))) }
        assertEquals(6 * jittered.size, written.size)
    }

    @Test
    fun `a fix beyond the radius moves the position`() = runBlocking {
        fixesAt(48.2470 to 12.5220, 48.2470 to 12.6020, 48.2480 to 12.6030)
        assertEquals(48.2470 to 12.5220, position.resolve(retry = false))
        // 0.08° of longitude at 48° N ≈ 5.9 km.
        assertEquals(48.2470 to 12.6020, position.resolve(retry = false))
        assertEquals(48.2470 to 12.6020, position.resolve(retry = false))
    }

    @Test
    fun `fixes from both clusters of one resting spot keep the position`() = runBlocking {
        // The phone's CITY fixes for one spot came in two clusters 4 km apart, at ±3 km each.
        fixesAt(48.236 to 12.545, 48.272 to 12.545, 48.240 to 12.545, 48.270 to 12.545)
        repeat(4) { assertEquals(48.236 to 12.545, position.resolve(retry = false)) }
    }

    @Test
    fun `a fix only moves the position beyond its own accuracy`() = runBlocking {
        // 7 km away, but ±10 km: no evidence the phone moved.
        fixes += listOf(at(48.2470, 12.5220), at(48.3100, 12.5220, accuracy = 10_000.0), at(48.3100, 12.5220))
        assertEquals(48.2470 to 12.5220, position.resolve(retry = false))
        assertEquals(48.2470 to 12.5220, position.resolve(retry = false))
        // The same spot at ±3 km is beyond the radius.
        assertEquals(48.3100 to 12.5220, position.resolve(retry = false))
    }

    @Test
    fun `a coarse fallback fix between CITY fixes changes nothing`() = runBlocking {
        // GeoClue's best one when no CITY fix came within 30 s: ±25 km, 45 km off.
        var lookups = 0
        val nominatim = NominatimReverseGeocoder { _, _ -> lookups++; place("Mühldorf") }
        val written = mutableListOf<Any>()
        val pins = WeatherPins({ written += it }, { written += it })
        fixes += listOf(at(48.2500, 12.5400), at(47.9090, 12.2980, accuracy = 25_000.0), at(48.2520, 12.5420), at(47.9090, 12.2980, accuracy = null))
        repeat(4) {
            val (lat, lon) = position.resolve(retry = false)!!
            assertEquals(48.2500 to 12.5400, lat to lon)
            pins.sync(listOf(forecastAt(nominatim.nameAt(lat, lon) ?: "Current location", lat, lon)))
        }
        assertEquals(1, lookups)
        assertEquals(6, written.size)
    }

    @Test
    fun `a CITY fix replaces a coarse first position`() = runBlocking {
        fixes += listOf(at(48.2700, 12.5600, accuracy = 25_000.0), at(48.2500, 12.5400))
        assertEquals(48.2700 to 12.5600, position.resolve(retry = false))
        assertEquals(48.2500 to 12.5400, position.resolve(retry = false))
    }

    @Test
    fun `no fix gives no position`() = runBlocking {
        fixes += listOf(null)
        assertNull(position.resolve(retry = false))
    }

    @Test
    fun `a retry reuses the position without a fix until it is too old`() = runBlocking {
        fixesAt(48.2470 to 12.5220, 48.3000 to 12.6000)
        position.resolve(retry = false)
        WEATHER_RETRY_BACKOFF.forEach { step ->
            now += step.inWholeMilliseconds
            assertEquals(48.2470 to 12.5220, position.resolve(retry = true))
        }
        assertEquals(1, fixCalls)
        now += 10.minutes.inWholeMilliseconds
        assertEquals(48.3000 to 12.6000, position.resolve(retry = true))
        assertEquals(2, fixCalls)
    }

    @Test
    fun `retries after a sync without a fix reuse the older position`() = runBlocking {
        // Offline: the periodic sync one interval after the last fix gets none, and its retries don't ask again.
        fixes += listOf(at(48.2470, 12.5220), null)
        position.resolve(retry = false)
        now += 30.minutes.inWholeMilliseconds
        assertNull(position.resolve(retry = false))
        WEATHER_RETRY_BACKOFF.forEach { step ->
            now += step.inWholeMilliseconds
            assertEquals(48.2470 to 12.5220, position.resolve(retry = true))
        }
        assertEquals(2, fixCalls)
    }

    @Test
    fun `a retry without any position asks for a fix`() = runBlocking {
        fixes += listOf(null, at(48.2470, 12.5220))
        assertNull(position.resolve(retry = false))
        now += 30.seconds.inWholeMilliseconds
        assertEquals(48.2470 to 12.5220, position.resolve(retry = true))
        assertEquals(2, fixCalls)
    }

    @Test
    fun `a sync that isn't a retry always asks for a fix`() = runBlocking {
        fixesAt(48.2470 to 12.5220, 48.2471 to 12.5221)
        position.resolve(retry = false)
        position.resolve(retry = false)
        assertEquals(2, fixCalls)
    }

    @Test
    fun `a failed reverse geocode keeps the last name nearby`() = runBlocking {
        val answers = ArrayDeque<() -> String>(listOf({ place("Mühldorf") }, { throw IOException("Connection reset") }, { throw IOException("Connection reset") }))
        val nominatim = NominatimReverseGeocoder { _, _ -> answers.removeFirst()() }
        assertEquals("Mühldorf", nominatim.nameAt(48.2470, 12.5220))
        // 3 km away, the lookup fails: the forecast keeps its name instead of "Current location".
        assertEquals("Mühldorf", nominatim.nameAt(48.2470, 12.5620))
        // 100 km away a kept name would be wrong.
        assertNull(nominatim.nameAt(49.1470, 12.5220))
    }

    @Test
    fun `a failed lookup is asked again`() = runBlocking {
        val answers = ArrayDeque<() -> String>(listOf({ throw IOException("Connection reset") }, { place("Mühldorf") }))
        val nominatim = NominatimReverseGeocoder { _, _ -> answers.removeFirst()() }
        assertNull(nominatim.nameAt(48.2470, 12.5220))
        assertEquals("Mühldorf", nominatim.nameAt(48.2470, 12.5220))
    }

    @Test
    fun `a precise fix is rounded to the grid before it places the forecast`() {
        // A watchapp's GPS fix (±8 m) from the shared cache: weather sends no finer position than its own
        // scrambled neighborhood fix would.
        val c = coarsened(at(48.14957, 11.56723, accuracy = 8.0))
        assertEquals(48.14, c.latitude, 1e-9)
        assertEquals(11.56, c.longitude, 1e-9)
        assertEquals(8.0, c.accuracy)
        val south = coarsened(at(-33.8689, 151.2093))
        assertEquals(-33.86, south.latitude, 1e-9)
        assertEquals(151.20, south.longitude, 1e-9)
        assertTrue(distanceKm(48.14957, 11.56723, c.latitude, c.longitude) < 1.5)
    }

    @Test
    fun `the retry backoff ends at the interval`() {
        val interval = 30.minutes
        assertEquals(
            listOf(30.seconds, 1.minutes, 5.minutes, 15.minutes, interval, interval),
            (0..5).map { weatherRetryDelay(it, interval) },
        )
        // A shorter interval caps the backoff.
        assertEquals(listOf(30.seconds, 1.minutes, 5.minutes, 10.minutes, 10.minutes), (0..4).map { weatherRetryDelay(it, 10.minutes) })
    }
}
