package de.yoxcu.stoandl.location

import de.yoxcu.stoandl.dbus.GeoClueAccuracy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** A provider that leaves GeoClue running keeps the phone's GPS and Wi-Fi scans on, and the phone waking
 *  for GeoClue's network re-queries, for nothing. */
class GeoClueLocationProviderTest {
    /** Each read after a start returns a fix of the next radius in [accuracies] (the last one
     *  repeats); a null radius means no fix yet. [onRead] runs before each read. */
    internal class FakeSession(
        @Volatile var accuracies: List<Double?> = listOf(10.0),
        private val onRead: () -> Unit = {},
    ) : GeoClueSession {
        val log: MutableList<String> = Collections.synchronizedList(mutableListOf())
        @Volatile var running: Int? = null
        @Volatile private var reads = 0
        var fail = false

        override fun start(accuracy: Int) {
            if (fail) error("access denied")
            log += "start $accuracy"
            running = accuracy
            reads = 0
        }

        override fun read(): GeoClueFix? {
            onRead()
            return accuracies[minOf(reads++, accuracies.lastIndex)]?.let { GeoClueFix(48.1, 11.6, it, null, null, null) }
        }

        override fun stop() {
            log += "stop"
            running = null
        }
    }

    private var now = 1_000_000L
    private fun provider(session: GeoClueSession) = GeoClueLocationProvider(session, clock = { now })

    private suspend fun awaitStopped(session: FakeSession, within: Duration = 1.seconds) =
        withTimeout(within) { while (session.running != null) delay(5) }

    @Test
    fun `a one-shot request stops the client right after the fix`() = runBlocking {
        val session = FakeSession(listOf(null, null, 10.0))
        val fix = provider(session).fix(GeoClueAccuracy.CITY, timeout = 5.seconds)
        assertEquals(now, assertNotNull(fix).timeMs)
        awaitStopped(session, within = 500.milliseconds)
        assertEquals(listOf("start ${GeoClueAccuracy.CITY}", "stop"), session.log)
    }

    @Test
    fun `a request queued during a fix reuses the running client`() = runBlocking {
        // The first fix takes about a second of polls; the second request waits on the mutex meanwhile and
        // gets it before the first one's stop job.
        val session = FakeSession(listOf(null, null, 10.0))
        val p = provider(session)
        coroutineScope {
            val first = async { p.fix(GeoClueAccuracy.STREET, timeout = 5.seconds) }
            delay(200)
            val second = async { p.fix(GeoClueAccuracy.CITY, timeout = 5.seconds) }
            assertNotNull(first.await())
            assertNotNull(second.await())
        }
        awaitStopped(session)
        assertEquals(listOf("start ${GeoClueAccuracy.STREET}", "stop"), session.log)
    }

    @Test
    fun `linger keeps the client between watch ticks`() = runBlocking {
        val session = FakeSession()
        val p = provider(session)
        p.fix(GeoClueAccuracy.STREET, timeout = 5.seconds, maxAge = 1.seconds, linger = 1.seconds)
        delay(300)
        assertEquals(GeoClueAccuracy.STREET, session.running, "still running for the next tick")
        p.fix(GeoClueAccuracy.STREET, timeout = 5.seconds, maxAge = 1.seconds, linger = 1.seconds)
        assertEquals(listOf("start ${GeoClueAccuracy.STREET}"), session.log, "the next tick reuses the client")
        awaitStopped(session, within = 3.seconds)
        assertEquals(listOf("start ${GeoClueAccuracy.STREET}", "stop"), session.log, "stops once the ticks stop")
    }

    @Test
    fun `a request without linger doesn't stop a client another request asked to keep`() = runBlocking {
        val session = FakeSession()
        val p = provider(session)
        p.fix(GeoClueAccuracy.STREET, timeout = 5.seconds, linger = 2.seconds)
        p.fix(GeoClueAccuracy.NEIGHBORHOOD, timeout = 5.seconds)
        delay(500)
        assertEquals(GeoClueAccuracy.STREET, session.running)
        assertEquals(listOf("start ${GeoClueAccuracy.STREET}"), session.log)
        awaitStopped(session, within = 4.seconds)
    }

    @Test
    fun `a higher accuracy restarts the client, a lower one reuses it`() = runBlocking {
        val session = FakeSession()
        val p = provider(session)
        p.fix(GeoClueAccuracy.STREET, timeout = 5.seconds, linger = 10.seconds)
        p.fix(GeoClueAccuracy.CITY, timeout = 5.seconds)
        p.fix(GeoClueAccuracy.EXACT, timeout = 5.seconds)
        // The street client's linger died with it: the one-shot's GPS stops right after its answer.
        awaitStopped(session, within = 500.milliseconds)
        assertEquals(
            listOf("start ${GeoClueAccuracy.STREET}", "stop", "start ${GeoClueAccuracy.EXACT}", "stop"),
            session.log,
        )
        p.stop()
    }

    @Test
    fun `a street watch doesn't keep the GPS a one-shot started`() = runBlocking {
        val session = FakeSession()
        val p = provider(session)
        // Watch tick, then getCurrentPosition(enableHighAccuracy) raises the client, then the next tick.
        p.fix(GeoClueAccuracy.STREET, timeout = 5.seconds, linger = 5.seconds)
        p.fix(GeoClueAccuracy.EXACT, timeout = 5.seconds)
        awaitStopped(session, within = 500.milliseconds)
        p.fix(GeoClueAccuracy.STREET, timeout = 5.seconds, linger = 5.seconds)
        assertEquals(GeoClueAccuracy.STREET, session.running, "the watch holds a street client again")
        assertEquals(
            listOf(
                "start ${GeoClueAccuracy.STREET}", "stop",
                "start ${GeoClueAccuracy.EXACT}", "stop",
                "start ${GeoClueAccuracy.STREET}",
            ),
            session.log,
        )
        p.stop()
    }

    @Test
    fun `a lower-level tick answered by a lingering GPS client doesn't extend it`() = runBlocking {
        val session = FakeSession()
        val p = provider(session)
        p.fix(GeoClueAccuracy.EXACT, timeout = 5.seconds, linger = 300.milliseconds)
        p.fix(GeoClueAccuracy.STREET, timeout = 5.seconds, linger = 10.seconds)
        assertEquals(listOf("start ${GeoClueAccuracy.EXACT}"), session.log, "the street tick reuses the GPS client")
        awaitStopped(session, within = 1500.milliseconds)
    }

    @Test
    fun `a cancelled request is no refusal and cuts no linger short`() = runBlocking {
        val session = FakeSession()
        val p = provider(session)
        p.fix(GeoClueAccuracy.STREET, timeout = 5.seconds, linger = 2.seconds)
        // The next request polls a client without a fix and is cancelled (clearWatch, the PKJS session
        // ends, weather turned off).
        session.accuracies = listOf(null)
        coroutineScope {
            val pending = async { p.fix(GeoClueAccuracy.STREET, timeout = 5.seconds, linger = 2.seconds) }
            delay(300)
            pending.cancel()
            assertTrue(runCatching { pending.await() }.exceptionOrNull() is CancellationException)
        }
        delay(300)
        assertEquals(GeoClueAccuracy.STREET, session.running, "still held by the first request's linger")
        assertEquals(listOf("start ${GeoClueAccuracy.STREET}"), session.log, "no stop on the refusal path")
        awaitStopped(session, within = 3.seconds)
        assertEquals(listOf("start ${GeoClueAccuracy.STREET}", "stop"), session.log)
    }

    @Test
    fun `a recent enough fix answers without starting GeoClue`() = runBlocking {
        val session = FakeSession(listOf(1000.0))
        val p = provider(session)
        p.fix(GeoClueAccuracy.STREET, timeout = 5.seconds)
        awaitStopped(session)
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
    fun `an exact fix answers a later neighborhood request within maxAge`() = runBlocking {
        // A watchapp's GPS fix places weather's forecast: one provider, one cache.
        val session = FakeSession(listOf(8.0))
        val p = provider(session)
        p.fix(GeoClueAccuracy.EXACT, timeout = 10.seconds, maxAge = 0.seconds)
        awaitStopped(session)
        session.log.clear()
        now += 30.minutes.inWholeMilliseconds
        val fix = p.fix(GeoClueAccuracy.NEIGHBORHOOD, timeout = 10.seconds, maxAge = 45.minutes)
        assertEquals(8.0, assertNotNull(fix).accuracy)
        assertEquals(emptyList(), session.log, "no new client")
    }

    @Test
    fun `no fix in time or a refusal returns null and leaves nothing running`() = runBlocking {
        val slow = FakeSession(listOf(null))
        assertNull(provider(slow).fix(GeoClueAccuracy.CITY, timeout = 1.seconds))
        awaitStopped(slow)

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
    fun `the wait ends on the wall clock too`() = runBlocking {
        // The phone suspends between wakes: 4 s of wall-clock time pass per poll, but almost no awake time.
        val session = FakeSession(listOf(25_000.0, 3_000.0, 25_000.0)) { now += 4_000 }
        val p = provider(session)
        val started = System.nanoTime()
        val fix = p.fix(GeoClueAccuracy.EXACT, timeout = 10.seconds)
        assertEquals(3_000.0, assertNotNull(fix).accuracy, "the best fix by the deadline answers")
        assertTrue((System.nanoTime() - started) / 1_000_000 < 5_000, "didn't wait the 10 s of awake time")
        p.stop()
    }

    @Test
    fun `the fix GeoClue refined to before stopping answers later requests`() = runBlocking {
        val session = FakeSession(listOf(3_000.0, 8.0))
        val p = provider(session)
        assertEquals(3_000.0, assertNotNull(p.fix(GeoClueAccuracy.CITY, timeout = 5.seconds)).accuracy)
        awaitStopped(session)
        session.log.clear()
        assertEquals(8.0, assertNotNull(p.fix(GeoClueAccuracy.EXACT, timeout = 5.seconds, maxAge = 5.minutes)).accuracy)
        assertEquals(emptyList(), session.log, "the GPS fix read at the stop serves an exact request")
    }

    @Test
    fun `a coarse last read doesn't replace a better cached fix`() = runBlocking {
        // Time passes between the answer and the stop's read, and the request passed no maxAge.
        val session = FakeSession(listOf(8.0, 25_000.0)) { now += 5 }
        val p = provider(session)
        p.fix(GeoClueAccuracy.EXACT, timeout = 5.seconds)
        awaitStopped(session)
        session.log.clear()
        assertEquals(8.0, assertNotNull(p.fix(GeoClueAccuracy.EXACT, timeout = 5.seconds, maxAge = 5.minutes)).accuracy)
        assertEquals(emptyList(), session.log)
    }

    @Test
    fun `a timed-out coarse answer doesn't push a better fix out of the cache`() = runBlocking {
        // Weather's neighborhood fix, then a watchapp's high-accuracy request that gets only a ±25 km guess.
        val session = FakeSession(listOf(3_000.0))
        val p = provider(session)
        p.fix(GeoClueAccuracy.NEIGHBORHOOD, timeout = 5.seconds, maxAge = 45.minutes)
        awaitStopped(session)
        now += 5.minutes.inWholeMilliseconds
        session.accuracies = listOf(25_000.0)
        assertEquals(25_000.0, assertNotNull(p.fix(GeoClueAccuracy.EXACT, timeout = 1.seconds)).accuracy, "the best guess answers")
        awaitStopped(session)
        session.log.clear()
        now += 25.minutes.inWholeMilliseconds
        val fix = p.fix(GeoClueAccuracy.NEIGHBORHOOD, timeout = 5.seconds, maxAge = 45.minutes)
        assertEquals(3_000.0, assertNotNull(fix).accuracy)
        assertEquals(emptyList(), session.log, "weather's next lookup is still a cache hit")
    }

    @Test
    fun `a coarse last read replaces a cached fix held too long`() = runBlocking {
        val session = FakeSession(listOf(8.0, 25_000.0))
        val p = provider(session)
        // The client lingers, and by the stop the precise answer is older than KEEP_BETTER_FIX.
        p.fix(GeoClueAccuracy.EXACT, timeout = 5.seconds, linger = 200.milliseconds)
        now += GeoClueLocationProvider.KEEP_BETTER_FIX.inWholeMilliseconds + 1
        awaitStopped(session)
        session.log.clear()
        val fix = p.fix(GeoClueAccuracy.COUNTRY, timeout = 5.seconds, maxAge = 1.minutes)
        assertEquals(25_000.0, assertNotNull(fix).accuracy)
        assertEquals(emptyList(), session.log)
    }
}
