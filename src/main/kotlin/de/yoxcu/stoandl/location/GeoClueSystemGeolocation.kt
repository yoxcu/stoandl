package de.yoxcu.stoandl.location

import io.github.oshai.kotlinlogging.KotlinLogging
import io.rebble.libpebblecommon.util.GeolocationPositionResult
import io.rebble.libpebblecommon.util.SystemGeolocation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlin.time.Clock
import kotlin.time.Duration

/**
 * Linux implementation of libpebble3's [SystemGeolocation] hook, backed by GeoClue2. This is what
 * makes watchapps' `navigator.geolocation` (PKJS) and location-aware sports/GPS apps work — the
 * shared `GeolocationInterface` in libpebble3 injects this and dispatches fixes back into the JS
 * context. Replaces the JVM module's no-op binding (which returns "Not supported on Linux").
 *
 * GeoClue already keeps the client's `Location` property current while active, so a "current
 * position" request is just an on-demand read ([GeoClueLocationProvider.currentFix]); we don't model
 * `maximumAge`/`timeout` separately (GeoClue owns freshness) and `highAccuracy` is implied by the
 * provider requesting EXACT accuracy. `watchPosition` polls on the requested interval.
 *
 * The GeoClue read is blocking D-Bus I/O, so it runs on [Dispatchers.IO].
 */
class GeoClueSystemGeolocation(
    private val provider: GeoClueLocationProvider,
) : SystemGeolocation {
    private val log = KotlinLogging.logger {}

    override suspend fun getCurrentPosition(
        maximumAge: Duration?,
        timeout: Duration?,
        highAccuracy: Boolean,
    ): GeolocationPositionResult {
        val fix = withContext(Dispatchers.IO) { provider.currentFix() }
        if (fix == null) {
            log.info { "navigator.geolocation: no GeoClue fix available" }
            return GeolocationPositionResult.Error("No location fix available")
        }
        return GeolocationPositionResult.Success(
            timestamp = Clock.System.now(),
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
            emit(getCurrentPosition(maximumAge = null, timeout = null, highAccuracy = highAccuracy))
            delay(interval)
        }
    }
}
