package de.yoxcu.stoandl.location

import io.rebble.libpebblecommon.util.GeolocationPositionResult
import io.rebble.libpebblecommon.util.SystemGeolocation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.delay
import kotlin.time.Duration

/**
 * A [SystemGeolocation] that consults `geolocation.enabled` **live** on every request: when on it
 * delegates to a (lazily-created, cached) [GeoClueSystemGeolocation]; when off it returns the same
 * opt-in error as [DisabledGeolocation]. This is what lets the Settings toggle take effect without a
 * daemon restart — the Koin binding is fixed (cached single), but the behaviour behind it is not.
 *
 * [enabled] reads the live config; [provider] builds the GeoClue provider on first use (so nothing is
 * created until a watchapp actually asks for location while enabled).
 */
class LiveGeolocation(
    private val enabled: () -> Boolean,
    private val provider: () -> GeoClueLocationProvider,
) : SystemGeolocation {
    @Volatile private var delegate: GeoClueSystemGeolocation? = null

    private fun active(): SystemGeolocation =
        if (enabled()) (delegate ?: GeoClueSystemGeolocation(provider()).also { delegate = it })
        else DisabledGeolocation

    override suspend fun getCurrentPosition(
        maximumAge: Duration?,
        timeout: Duration?,
        highAccuracy: Boolean,
    ): GeolocationPositionResult = active().getCurrentPosition(maximumAge, timeout, highAccuracy)

    // Re-check enabled() each tick (via getCurrentPosition) so disabling mid-watch starts erroring.
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
