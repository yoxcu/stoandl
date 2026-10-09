package de.yoxcu.stoandl.location

import de.yoxcu.stoandl.dbus.GeoClueAccuracy
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** A provider that leaves GeoClue running keeps the phone's GPS and Wi-Fi scans on for nothing. */
class GeoClueLocationProviderTest {
    /** Each read after a start returns a fix of the next radius in [accuracies] (the last one
     *  repeats); a null radius means no fix yet. */
    private class FakeSession(var accuracies: List<Double?> = listOf(10.0)) : GeoClueSession {
        val log = mutableListOf<String>()
        var running: Int? = null
        private var reads = 0
        var fail = false

        override fun start(accuracy: Int) {
            if (fail) error("access denied")
            log += "start $accuracy"
            running = accuracy
            reads = 0
        }

        override fun read(): GeoClueFix? =
            accuracies[minOf(reads++, accuracies.lastIndex)]?.let { GeoClueFix(48.1, 11.6, it, null, null, null) }

        override fun stop() {
            log += "stop"
            running = null
        }
    }

    private var now = 1_000_000L
    private fun provider(session: GeoClueSession, idle: kotlin.time.Duration = 1.minutes) =
        GeoClueLocationProvider(session, idle = idle, clock = { now })

    @Test
    fun `waits for the first fix, then stops once idle`() = runBlocking {
        val session = FakeSession(listOf(null, null, 10.0))
        val fix = provider(session, idle = 50.milliseconds).fix(GeoClueAccuracy.CITY, timeout = 5.seconds)
        assertEquals(now, assertNotNull(fix).timeMs)
        assertEquals(GeoClueAccuracy.CITY, session.running)
        delay(1_500) // wall-clock slices: the idle stop lands within a second or so
        assertEquals(listOf("start ${GeoClueAccuracy.CITY}", "stop"), session.log)
        assertNull(session.running)
    }

    @Test
    fun `a recent enough fix answers without starting GeoClue`() = runBlocking {
        val session = FakeSession(listOf(1000.0))
        val p = provider(session, idle = 1.milliseconds)
        p.fix(GeoClueAccuracy.STREET, timeout = 5.seconds)
        delay(1_200)
        session.log.clear()
        now += 4.minutes.inWholeMilliseconds
        assertNotNull(p.fix(GeoClueAccuracy.CITY, timeout = 5.seconds, maxAge = 5.minutes))
        assertEquals(emptyList(), session.log, "cached street fix serves a city request")
        session.accuracies = listOf(10.0)
        assertNotNull(p.fix(GeoClueAccuracy.EXACT, timeout = 5.seconds, maxAge = 5.minutes))
        assertEquals(listOf("start ${GeoClueAccuracy.EXACT}"), session.log, "a street fix can't serve an exact request")
        p.stop()
    }

    @Test
    fun `a higher accuracy restarts the client, a lower one reuses it`() = runBlocking {
        val session = FakeSession()
        val p = provider(session)
        p.fix(GeoClueAccuracy.STREET, timeout = 5.seconds)
        p.fix(GeoClueAccuracy.CITY, timeout = 5.seconds)
        p.fix(GeoClueAccuracy.EXACT, timeout = 5.seconds)
        assertEquals(
            listOf("start ${GeoClueAccuracy.STREET}", "stop", "start ${GeoClueAccuracy.EXACT}"),
            session.log,
        )
        p.stop()
        assertNull(session.running)
    }

    @Test
    fun `no fix in time or a refusal returns null and leaves nothing running`() = runBlocking {
        val slow = FakeSession(listOf(null))
        val p = provider(slow, idle = 1.milliseconds)
        assertNull(p.fix(GeoClueAccuracy.CITY, timeout = 1.seconds))
        delay(1_200)
        assertNull(slow.running)

        val denied = FakeSession().apply { fail = true }
        assertNull(provider(denied).fix(GeoClueAccuracy.CITY, timeout = 1.seconds))
        assertNull(denied.running)
        assertEquals(listOf("stop"), denied.log, "a failed start still releases the session's connection")
    }

    @Test
    fun `a coarse first fix doesn't answer a request it isn't accurate enough for`() = runBlocking {
        // GeoClue's first location at EXACT is often a ±25 km cell or IP guess; the GPS follows.
        val session = FakeSession(listOf(25_000.0, 25_000.0, 8.0))
        val p = provider(session)
        assertEquals(8.0, assertNotNull(p.fix(GeoClueAccuracy.EXACT, timeout = 5.seconds)).accuracy)
        p.stop()
    }

    @Test
    fun `without a fix that accurate in time, the best one answers`() = runBlocking {
        val session = FakeSession(listOf(25_000.0, 3_000.0, 25_000.0))
        val p = provider(session)
        assertEquals(3_000.0, assertNotNull(p.fix(GeoClueAccuracy.EXACT, timeout = 2.seconds)).accuracy)
        p.stop()
    }

    @Test
    fun `the fix GeoClue refined to before stopping answers later requests`() = runBlocking {
        val session = FakeSession(listOf(3_000.0, 8.0))
        val p = provider(session, idle = 1.milliseconds)
        assertEquals(3_000.0, assertNotNull(p.fix(GeoClueAccuracy.CITY, timeout = 5.seconds)).accuracy)
        delay(1_200)
        assertNull(session.running)
        session.log.clear()
        assertEquals(8.0, assertNotNull(p.fix(GeoClueAccuracy.EXACT, timeout = 5.seconds, maxAge = 5.minutes)).accuracy)
        assertEquals(emptyList(), session.log, "the GPS fix read at the idle stop serves an exact request")
    }
}
