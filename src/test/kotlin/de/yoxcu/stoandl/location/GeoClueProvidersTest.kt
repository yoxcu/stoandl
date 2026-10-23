package de.yoxcu.stoandl.location

import de.yoxcu.stoandl.dbus.GeoClueAccuracy
import de.yoxcu.stoandl.location.GeoClueLocationProviderTest.FakeSession
import de.yoxcu.stoandl.weather.WeatherSync
import io.rebble.libpebblecommon.connection.LibPebble
import io.rebble.libpebblecommon.util.GeolocationPositionResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** Weather and watchapps share one GeoClue provider; only its holder may stop it, because a stopped provider
 *  can't stop its client any more. */
class GeoClueProvidersTest {
    private var id = "stoandl"
    private val sessions = mutableMapOf<String, FakeSession>()
    private val holder = GeoClueProviders({ id }) { id ->
        GeoClueLocationProvider(FakeSession(listOf(500.0)).also { sessions[id] = it })
    }

    @Test
    fun `one provider per identity, the old one stopped when it changes`() = runBlocking {
        val first = holder.current()
        assertSame(first, holder.current())
        first.fix(GeoClueAccuracy.STREET, timeout = 5.seconds, linger = 1.minutes)
        assertEquals(GeoClueAccuracy.STREET, sessions.getValue("stoandl").running)

        id = "other"
        assertNotSame(first, holder.current())
        assertNull(sessions.getValue("stoandl").running, "the old identity's client is stopped")
        holder.stop()
    }

    @Test
    fun `turning weather off and on doesn't stop the provider watchapps use`() = runBlocking {
        val live = LiveGeolocation(enabled = { true }, provider = holder::current)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val provider = holder.current()
        try {
            // Weather off, then on and off again: neither touches the shared provider.
            WeatherSync(fakeLibPebble(), scope, emptyList(), 30, gps = holder::current).stop()
            WeatherSync(fakeLibPebble(), scope, emptyList(), 30, gps = holder::current).also {
                holder.current().fix(GeoClueAccuracy.NEIGHBORHOOD, timeout = 5.seconds)
            }.stop()
            assertSame(provider, holder.current())

            // A watch tick after the cached fix is older than its interval: a street client that lingers.
            delay(300)
            assertIs<GeolocationPositionResult.Success>(
                live.watchPosition(interval = 200.milliseconds, highAccuracy = false).first(),
            )
        } finally {
            scope.cancel()
        }
        val session = sessions.getValue("stoandl")
        assertEquals(GeoClueAccuracy.STREET, session.running, "the tick's linger holds the street client")
        withTimeout(10.seconds) { while (session.running != null) delay(20) }
        assertEquals(
            listOf("start ${GeoClueAccuracy.NEIGHBORHOOD}", "stop", "start ${GeoClueAccuracy.STREET}", "stop"),
            session.log,
            "the provider still stops its client after the linger",
        )
        holder.stop()
    }

    private fun fakeLibPebble(): LibPebble =
        Proxy.newProxyInstance(LibPebble::class.java.classLoader, arrayOf(LibPebble::class.java)) { proxy, method, args ->
            when (method.name) {
                "toString" -> "fake LibPebble"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.firstOrNull()
                else -> throw UnsupportedOperationException("LibPebble.${method.name}")
            }
        } as LibPebble
}
