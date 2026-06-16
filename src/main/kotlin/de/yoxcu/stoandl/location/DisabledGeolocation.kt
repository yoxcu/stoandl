package de.yoxcu.stoandl.location

import io.rebble.libpebblecommon.util.GeolocationPositionResult
import io.rebble.libpebblecommon.util.SystemGeolocation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlin.time.Duration

/**
 * The [SystemGeolocation] bound when `geolocation.enabled` is off (the default). It exists purely to
 * give a clearer error than libpebble3's JVM no-op, which reports "Not supported on Linux" — implying
 * the platform *can't* do it. stoandl can (via GeoClue2, see [GeoClueSystemGeolocation]); it's just
 * opt-in. So the watchapp's PKJS `navigator.geolocation` error tells the user how to turn it on rather
 * than that it's impossible.
 */
object DisabledGeolocation : SystemGeolocation {
    private const val MESSAGE =
        "Geolocation is disabled — set geolocation.enabled=true in stoandl.conf to share your location with watchapps"

    override suspend fun getCurrentPosition(
        maximumAge: Duration?,
        timeout: Duration?,
        highAccuracy: Boolean,
    ): GeolocationPositionResult = GeolocationPositionResult.Error(MESSAGE)

    // Emit the error once (then complete), mirroring AndroidSystemGeolocation's permission-denied path;
    // GeolocationInterface dispatches it to the watchapp's watchPosition error callback.
    override suspend fun watchPosition(
        interval: Duration,
        highAccuracy: Boolean,
    ): Flow<GeolocationPositionResult> = flowOf(GeolocationPositionResult.Error(MESSAGE))
}
