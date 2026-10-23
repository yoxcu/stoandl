package de.yoxcu.stoandl.location

import de.yoxcu.stoandl.dbus.GeoClueAccuracy
import io.github.oshai.kotlinlogging.KotlinLogging
import io.rebble.libpebblecommon.util.GeolocationPositionResult
import io.rebble.libpebblecommon.util.SystemGeolocation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * Linux implementation of libpebble3's [SystemGeolocation] hook, backed by GeoClue2. This is what makes
 * watchapps' `navigator.geolocation` (PKJS) and location-aware sports/GPS apps work — the shared
 * `GeolocationInterface` in libpebble3 injects this and dispatches fixes back into the JS context. Replaces
 * the JVM module's no-op binding (which returns "Not supported on Linux").
 *
 * [enabled] (`geolocation.enabled`) is read **live** on every request and every watch tick: when off, the
 * request gets [DisabledGeolocation]'s opt-in error. That lets the Settings toggle take effect without a
 * daemon restart — the Koin binding is fixed (cached single), but the behaviour behind it is not.
 * [provider] is [GeoClueProviders.current], which builds the provider on first use (so nothing is created
 * until a watchapp actually asks for location while enabled) and rebuilds it when `weather.gps_desktop_id`
 * changes. This class never stops a provider: the holder owns it.
 *
 * The request's options map onto GeoClue as a browser maps them onto the phone's radios:
 * `enableHighAccuracy` asks for EXACT (the GPS), otherwise STREET (Wi-Fi and cell towers); `timeout` bounds
 * the wait for a fix that accurate, after which the best one so far answers ([DEFAULT_TIMEOUT] when unset);
 * `maximumAge` lets a recent fix answer without starting GeoClue. `getCurrentPosition` stops GeoClue right
 * after its answer; `watchPosition` polls on the requested interval and keeps the client between ticks when
 * they come at most a minute apart ([watchLinger]).
 */
class LiveGeolocation(
    private val enabled: () -> Boolean,
    private val provider: () -> GeoClueLocationProvider,
) : SystemGeolocation {
    private val log = KotlinLogging.logger {}

    override suspend fun getCurrentPosition(
        maximumAge: Duration?,
        timeout: Duration?,
        highAccuracy: Boolean,
    ): GeolocationPositionResult = position(maximumAge, timeout, highAccuracy, linger = Duration.ZERO)

    // The one watch loop: each tick is a fix at most [interval] old that keeps the client for the next
    // tick, and re-checks enabled() so disabling mid-watch starts erroring.
    override suspend fun watchPosition(
        interval: Duration,
        highAccuracy: Boolean,
    ): Flow<GeolocationPositionResult> = flow {
        while (true) {
            emit(position(maximumAge = interval, timeout = null, highAccuracy = highAccuracy, linger = watchLinger(interval)))
            delay(interval)
        }
    }

    private suspend fun position(
        maximumAge: Duration?,
        timeout: Duration?,
        highAccuracy: Boolean,
        linger: Duration,
    ): GeolocationPositionResult {
        if (!enabled()) return DisabledGeolocation.getCurrentPosition(maximumAge, timeout, highAccuracy)
        val level = if (highAccuracy) GeoClueAccuracy.EXACT else GeoClueAccuracy.STREET
        val fix = provider().fix(level, timeout ?: DEFAULT_TIMEOUT, maximumAge, linger)
        if (fix == null) {
            log.info { "navigator.geolocation: no GeoClue fix available" }
            return GeolocationPositionResult.Error("No location fix available")
        }
        return GeolocationPositionResult.Success(
            timestamp = Instant.fromEpochMilliseconds(fix.timeMs),
            latitude = fix.latitude,
            longitude = fix.longitude,
            accuracy = fix.accuracy,
            altitude = fix.altitude,
            heading = fix.heading,
            speed = fix.speed,
        )
    }

    companion object {
        val DEFAULT_TIMEOUT = 30.seconds

        /** Watch ticks this far apart keep the client between them: restarting it (a new bus peer, and at
         *  EXACT a cold GPS) costs more than holding it. Slower ticks start a client each time. */
        val WATCH_HOLD_MAX_INTERVAL = 60.seconds

        /** How long a watch tick keeps the client after its answer: past the next tick, or not at all. */
        fun watchLinger(interval: Duration): Duration =
            if (interval <= WATCH_HOLD_MAX_INTERVAL) interval + 5.seconds else Duration.ZERO
    }
}
