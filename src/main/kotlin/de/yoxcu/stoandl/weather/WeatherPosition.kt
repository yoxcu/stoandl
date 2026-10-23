package de.yoxcu.stoandl.weather

import de.yoxcu.stoandl.dbus.GeoClueAccuracy
import de.yoxcu.stoandl.location.GeoClueFix
import de.yoxcu.stoandl.location.GeoClueLocationProvider
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** A new fix this close to the forecast position leaves it where it is: wider than the spread of GeoClue's
 *  Wi-Fi fixes for one spot (scrambled at weather's level: ±3 km each, landing up to 4 km apart), well inside
 *  a town. A fix whose own accuracy radius is wider still needs that much. */
internal const val STICKY_POSITION_RADIUS_KM = 5.0

/** How old a fix of the shared GeoClue provider may be and still place the forecast: every other sync at the
 *  default 30-min interval asks GeoClue, and a watchapp's fix in between answers it for free. This is the
 *  provider's positive cache; [RETRY_POSITION_MAX_AGE] is the negative one (see [StickyPosition]). */
internal val WEATHER_POSITION_MAX_AGE: Duration = 45.minutes

/** How long weather waits for a neighborhood fix (awake and wall-clock time): long enough for the cell
 *  tower's answer, short enough that a GeoClue client doesn't keep the phone waking for nothing. */
internal val WEATHER_POSITION_TIMEOUT: Duration = 10.seconds

/** The grid (degrees, about 2 km) weather's GPS position is rounded to before it places the forecast, so
 *  no finer position leaves the device (Open-Meteo, and Nominatim with `weather.reverse_geocode`) than
 *  weather's own neighborhood fix would give: GeoClue scrambles that one (latitude moved by up to 3 km), but a
 *  watchapp's GPS or Wi-Fi fix from the shared provider's cache is precise to metres. */
internal const val WEATHER_POSITION_GRID_DEG = 0.02

/** [fix] with its coordinates rounded to [WEATHER_POSITION_GRID_DEG]. */
internal fun coarsened(fix: GeoClueFix): GeoClueFix = fix.copy(
    latitude = roundToGrid(fix.latitude),
    longitude = roundToGrid(fix.longitude),
)

private fun roundToGrid(degrees: Double): Double =
    Math.round(degrees / WEATHER_POSITION_GRID_DEG) * WEATHER_POSITION_GRID_DEG

/** On a failed or partial sync, retry sooner than the configured interval, backing off each time, then
 *  wait the interval until a sync succeeds. Covers transient blips (cold-start DNS, a slow API). */
internal val WEATHER_RETRY_BACKOFF = listOf(30.seconds, 1.minutes, 5.minutes, 15.minutes)

/** How long after the last fix attempt a retry reuses the position instead of asking GeoClue again: the
 *  whole backoff plus the syncs themselves. A retry that fires later (it waits for a wake) asks for a fix. */
internal val RETRY_POSITION_MAX_AGE: Duration = WEATHER_RETRY_BACKOFF.reduce(Duration::plus) + 5.minutes

/** The wait after a sync that has failed [failStreak] times before this one (0 = the first failure). */
internal fun weatherRetryDelay(failStreak: Int, interval: Duration): Duration =
    minOf(WEATHER_RETRY_BACKOFF.getOrElse(failStreak) { interval }, interval)

/**
 * The position the GPS weather entry is forecast for. GeoClue's neighborhood fix moves on every request (by a
 * few hundred metres to a few km, at ±3 km accuracy), and Open-Meteo answers to the exact coordinate: sunrise and
 * sunset to the second, a neighbouring grid cell's temperatures. Forecasting at the raw fix rewrote all six
 * weather pins on nearly every sync. So the position moves only when a fix lands more than [radiusKm] (or
 * the fix's own accuracy radius, if wider) from it, and the forecast and the reverse-geocoded name stay put
 * while the phone does. A fix less accurate than [coarseM] (GeoClue's best one when no neighborhood fix came
 * in time, often a ±25 km IP guess dozens of km off) never moves an accurate position; it only stands in
 * until the next accurate fix replaces it.
 *
 * A retry after a failed sync ([resolve] with `retry`) reuses the position without asking for a fix, for up
 * to [RETRY_POSITION_MAX_AGE] after the last attempt, whether it got a fix or not: a new GeoClue client can
 * take its whole timeout before the network is even tried. Without any position yet, a retry asks (GeoClue
 * may just have been starting up).
 *
 * Two caches keep weather from asking GeoClue, and neither replaces the other. The provider's
 * ([WEATHER_POSITION_MAX_AGE], passed as `maxAge` by [fix]) is positive and shared with watchapps: it
 * answers only with a fix accurate enough for the level asked. This class's retry rule is negative: it
 * also covers an attempt that got a coarse fix or none, which the provider's cache never answers.
 */
internal class StickyPosition(
    /** The device's position now (GeoClue), or null without a fix. */
    private val fix: suspend () -> GeoClueFix?,
    private val clock: () -> Long = System::currentTimeMillis,
    private val radiusKm: Double = STICKY_POSITION_RADIUS_KM,
    private val coarseM: Double = GeoClueLocationProvider.radius(GeoClueAccuracy.CITY),
) {
    @Volatile private var position: Pair<Double, Double>? = null
    // Whether [position] came from a coarse fix, so any accurate one replaces it.
    @Volatile private var coarse = false
    @Volatile private var askedAtMs = 0L

    /** Where to forecast: the current position, moved to a new fix only beyond the radius; null without
     *  a fix (and no reusable position on a [retry]). */
    suspend fun resolve(retry: Boolean): Pair<Double, Double>? {
        val last = position
        if (retry && last != null && clock() - askedAtMs <= RETRY_POSITION_MAX_AGE.inWholeMilliseconds) return last
        val f = fix()
        askedAtMs = clock()
        if (f == null) return null
        // Unknown accuracy counts as coarse, as it does for GeoClueLocationProvider.
        val accuracyM = f.accuracy ?: Double.MAX_VALUE
        val fixCoarse = accuracyM > coarseM
        val move = when {
            last == null -> true
            // An accurate fix replaces a coarse position; a coarse fix never moves an accurate one.
            fixCoarse != coarse -> coarse
            else -> distanceKm(f.latitude, f.longitude, last.first, last.second) > maxOf(radiusKm, accuracyM / 1000)
        }
        if (!move) return last
        coarse = fixCoarse
        return (f.latitude to f.longitude).also { position = it }
    }
}

/** Great-circle distance in km. */
internal fun distanceKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
    return 2 * 6371.0 * asin(sqrt(a))
}
