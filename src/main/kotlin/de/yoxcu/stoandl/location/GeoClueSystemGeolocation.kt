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
 * Linux implementation of libpebble3's [SystemGeolocation] hook, backed by GeoClue2. This is what
 * makes watchapps' `navigator.geolocation` (PKJS) and location-aware sports/GPS apps work — the
 * shared `GeolocationInterface` in libpebble3 injects this and dispatches fixes back into the JS
 * context. Replaces the JVM module's no-op binding (which returns "Not supported on Linux").
 *
 * The request's options map onto GeoClue as a browser maps them onto the phone's radios:
 * `enableHighAccuracy` asks for EXACT (the GPS), otherwise STREET (Wi-Fi and cell towers);
 * `timeout` bounds the wait for a fix that accurate, after which the best one so far answers
 * ([DEFAULT_TIMEOUT] when unset); `maximumAge` lets a recent fix answer without starting GeoClue. [provider] stops GeoClue again once requests stop.
 * `watchPosition` polls on the requested interval.
 */
class GeoClueSystemGeolocation(
    internal val provider: GeoClueLocationProvider,
) : SystemGeolocation {
    private val log = KotlinLogging.logger {}

    override suspend fun getCurrentPosition(
        maximumAge: Duration?,
        timeout: Duration?,
        highAccuracy: Boolean,
    ): GeolocationPositionResult {
        val level = if (highAccuracy) GeoClueAccuracy.EXACT else GeoClueAccuracy.STREET
        val fix = provider.fix(level, timeout ?: DEFAULT_TIMEOUT, maximumAge)
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

    override suspend fun watchPosition(
        interval: Duration,
        highAccuracy: Boolean,
    ): Flow<GeolocationPositionResult> = flow {
        while (true) {
            emit(getCurrentPosition(maximumAge = interval, timeout = null, highAccuracy = highAccuracy))
            delay(interval)
        }
    }

    companion object {
        val DEFAULT_TIMEOUT = 30.seconds
    }
}
