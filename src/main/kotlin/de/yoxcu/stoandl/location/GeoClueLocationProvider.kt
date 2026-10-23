package de.yoxcu.stoandl.location

import de.yoxcu.stoandl.dbus.GEOCLUE_BUS_NAME
import de.yoxcu.stoandl.dbus.GEOCLUE_CLIENT_IFACE
import de.yoxcu.stoandl.dbus.GEOCLUE_LOCATION_IFACE
import de.yoxcu.stoandl.dbus.GEOCLUE_MANAGER_PATH
import de.yoxcu.stoandl.dbus.GeoClueAccuracy
import de.yoxcu.stoandl.dbus.GeoClueClient
import de.yoxcu.stoandl.dbus.GeoClueManager
import de.yoxcu.stoandl.power.NoWakeups
import de.yoxcu.stoandl.power.delayWallClock
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.freedesktop.dbus.DBusPath
import de.yoxcu.stoandl.util.openPrivateSystemBus
import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.interfaces.Properties
import org.freedesktop.dbus.types.UInt32
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import java.util.Locale

/** A GeoClue position fix, read at [timeMs] (epoch ms). [accuracy] (metres), [altitude] (metres),
 *  [speed] (m/s) and [heading] (degrees clockwise from north) are null when GeoClue reports them as
 *  unknown. */
data class GeoClueFix(
    val latitude: Double,
    val longitude: Double,
    val accuracy: Double?,
    val altitude: Double?,
    val speed: Double?,
    val heading: Double?,
    val timeMs: Long = 0,
)

/**
 * Resolves the device's current position from GeoClue2 — the standard Linux geolocation service
 * (system bus), which aggregates modem GPS, Wi-Fi, cell towers and IP. One provider per GeoClue identity
 * ([GeoClueProviders]) backs both weather's "current location" entry and the watch-facing
 * `navigator.geolocation` hook (see [LiveGeolocation]), so they share one client and one cache.
 *
 * GeoClue keeps a started client's location current, and the radios that takes powered (EXACT: the
 * modem GPS; STREET: Wi-Fi scans), until the client stops; while one runs, the modem's location
 * indications and GeoClue's network re-queries keep waking the phone. So the client runs only while
 * someone wants a fix: [fix] starts it at the requested accuracy level (or raises a running one), waits
 * for a location within that level's radius ([radius]), and stops it right after the answer unless the
 * request asks it to linger (a `watchPosition` flow between its ticks). A linger belongs to the client it was
 * granted on: only a request at the client's own level gets one (a street watch tick answered by a running
 * GPS client doesn't keep the GPS on), and a new client starts with none. Within a client the stop time only
 * ever moves later, so a request that wants no linger (or is cancelled) never cuts short a client another
 * request asked to keep, and the stop runs in the provider's own scope, never on the caller's thread.
 * Lingers count wall-clock time, so a suspend can't stretch them. GeoClue answers first with whatever it
 * has (often a ±25 km cell or IP guess, even at EXACT) and refines it, so the first location alone isn't the
 * answer. While the client runs, its live location answers every request; while it is stopped, the cached
 * fix answers a request it is accurate enough for, if its `maxAge` allows it — whoever asked for that fix.
 * A newer fix replaces the cached one only if it is no less accurate, or the cached one is older than
 * [KEEP_BETTER_FIX]: a timed-out request's coarse best guess mustn't turn the next cache hit into a miss.
 *
 * GeoClue authorises a client by its `DesktopId`: through the user's location agent, or, for a
 * headless daemon without one, an allow-list entry in `/etc/geoclue/geoclue.conf` (or `conf.d/`):
 * ```
 * [stoandl]
 * allowed=true
 * system=true
 * users=
 * ```
 */
class GeoClueLocationProvider internal constructor(
    private val session: GeoClueSession,
    private val wakeups: Flow<Unit> = NoWakeups,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    constructor(desktopId: String, wakeups: Flow<Unit> = NoWakeups) : this(DbusGeoClueSession(desktopId), wakeups)

    private val log = KotlinLogging.logger {}
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    // Accuracy level the running client was started at; null while it is stopped.
    private var runningLevel: Int? = null
    // When (clock(), epoch ms) the running client started, for the stop line.
    private var startedAtMs = 0L
    // The cached fix; only [remember] writes it.
    private var last: GeoClueFix? = null
    // The latest stop time a request at the running client's level asked for (epoch ms); only ever moves
    // later while that client runs, and starts at 0 with each new one (the old client's lingers die with it).
    private var stopAtMs = 0L
    private var stopJob: Job? = null

    /** The current position within [accuracy]'s radius (a [GeoClueAccuracy] level), or the best one
     *  GeoClue gives within [timeout] (awake and wall-clock time) if none is that accurate; null if it
     *  gives none (or refuses: not running, not authorised). With the client stopped, a fix that accurate
     *  and at most [maxAge] old is returned without starting it. The client stops right after the answer,
     *  or [linger] later (wall-clock) for a caller that will ask again soon, unless another request asked
     *  to keep it longer. Only a client at [accuracy] lingers for this request: one running above it (started
     *  for a more accurate request) answers, and stops when its own requests let it. */
    suspend fun fix(
        accuracy: Int,
        timeout: Duration,
        maxAge: Duration? = null,
        linger: Duration = Duration.ZERO,
    ): GeoClueFix? = mutex.withLock {
        // A pending stop (waiting out a linger, or queued on the mutex after this request: it is fair, first
        // come first served) is dropped, so this request reuses the running client; its finally reschedules it.
        stopJob?.cancel()
        // The linger this request adds: none if it ends without an answer (cancelled: a cleared watch, a
        // closed PKJS session, weather turned off), or if the client runs above its level.
        var holdMs = 0L
        try {
            val cached = last
            if (runningLevel == null && cached != null && maxAge != null &&
                meets(cached, accuracy) && clock() - cached.timeMs <= maxAge.inWholeMilliseconds
            ) {
                log.debug {
                    "GeoClue: cached ${levelName(accuracy)} fix answers (age ${ageText(clock() - cached.timeMs)}, ${accuracyText(cached)})"
                }
                return cached
            }
            withContext(Dispatchers.IO) { awaitFix(accuracy, timeout) }.also {
                if (runningLevel == accuracy) holdMs = linger.inWholeMilliseconds
            }
        } finally {
            if (runningLevel != null) {
                stopAtMs = maxOf(stopAtMs, clock() + holdMs)
                scheduleStop()
            }
        }
    }

    /** Stop the client and release the provider (its identity changed, daemon shutdown). Only its owner,
     *  [GeoClueProviders], calls this: a stopped provider can't schedule stops any more. */
    fun stop() {
        scope.cancel()
        // Not under the mutex: a fix() in progress gets its session pulled and returns null.
        if (runningLevel != null) runCatching { session.stop() }
        runningLevel = null
    }

    // The one stop path, launched under the mutex at the end of every request that leaves a client
    // running; the next request cancels it.
    private fun scheduleStop() {
        if (!scope.isActive) {
            // Released while this request ran (its identity changed): nothing would stop the client later.
            stopClient()
            return
        }
        stopJob = scope.launch {
            val wait = stopAtMs - clock()
            if (wait > 0) delayWallClock(wait.milliseconds, wakeups)
            mutex.withLock {
                // How far GeoClue refined the fix while it ran (e.g. whether the GPS locked).
                val final = runCatching { session.read() }.getOrNull()
                log.debug { "GeoClue last fix before stopping: ${accuracyText(final)}" }
                if (final != null) remember(final.copy(timeMs = clock()))
                stopClient()
            }
        }
    }

    private suspend fun awaitFix(accuracy: Int, timeout: Duration): GeoClueFix? = try {
        val level = runningLevel
        if (level == null || level < accuracy) {
            // GeoClue reads the level at Start(), so raising it means a new client.
            if (level != null) stopClient()
            session.start(accuracy)
            runningLevel = accuracy
            startedAtMs = clock()
            stopAtMs = 0L
            log.debug { "GeoClue client started at ${levelName(accuracy)}" }
        }
        val askedAt = System.nanoTime()
        val askedAtMs = clock()
        var best: GeoClueFix? = null
        // Both clocks bound the wait: withTimeoutOrNull counts awake time only, so on a phone that
        // suspends between wakes the poll also checks the wall clock (no extra timer: it runs on each wake).
        withTimeoutOrNull(timeout) {
            while (true) {
                val f = session.read()
                val b = best
                if (f != null && (b == null || metres(f) < metres(b))) best = f
                if (best?.let { meets(it, accuracy) } == true) break
                if (clock() - askedAtMs >= timeout.inWholeMilliseconds) break
                delay(POLL)
            }
        }
        val fix = best?.copy(timeMs = clock())
        when {
            fix == null -> log.info { "GeoClue: no ${levelName(accuracy)} fix within $timeout" }
            !meets(fix, accuracy) -> log.info {
                "GeoClue: no ${levelName(accuracy)} fix within $timeout, using the best one: ${accuracyText(fix)}"
            }
            else -> log.debug {
                "GeoClue ${levelName(accuracy)} fix: ${accuracyText(fix)} after ${(System.nanoTime() - askedAt) / 1_000_000} ms"
            }
        }
        if (fix != null) remember(fix)
        fix
    } catch (e: CancellationException) {
        // The caller went away (also an Exception, so it would land below): not GeoClue's fault, and the
        // client stops through [fix]'s one stop path, not here.
        throw e
    } catch (e: Exception) {
        // Also reached when GeoClue hangs while starting (no reply to GetClient), so name both causes.
        log.warn {
            "GeoClue didn't answer or refused (${e.message}): it may be hung starting up, or not allow " +
                "'stoandl' (a location agent, or an entry in /etc/geoclue/geoclue.conf)."
        }
        // The session may hold a half-set-up connection even though runningLevel was never set.
        runCatching { session.stop() }
        runningLevel = null
        null
    }

    // The one cache rule, for the answer and the last read before a stop alike.
    private fun remember(fix: GeoClueFix) {
        val cached = last
        if (cached == null || metres(fix) <= metres(cached) ||
            fix.timeMs - cached.timeMs > KEEP_BETTER_FIX.inWholeMilliseconds
        ) last = fix
    }

    private fun stopClient() {
        val level = runningLevel ?: return
        runCatching { session.stop() }
        runningLevel = null
        log.debug {
            "GeoClue client stopped (${levelName(level)}, ran ${"%.1f".format(Locale.ROOT, (clock() - startedAtMs) / 1000.0)} s)"
        }
    }

    companion object {
        private val POLL = 500.milliseconds

        /** How long a cached fix stands against a less accurate newer one. Longer than any `maxAge` stoandl
         *  asks itself (weather's 45 min), so a watchapp's timed-out ±25 km guess doesn't push weather's
         *  neighborhood fix out; short enough that a phone that has moved isn't placed by an old fix for long
         *  (a request still gets it only within its own `maxAge`). */
        val KEEP_BETTER_FIX = 1.hours

        /** How close a fix must be (its accuracy radius, metres) to count as [level]: GeoClue's own
         *  scale (street 1 km, city 15 km, country 300 km), with EXACT meaning a GPS or good Wi-Fi fix. */
        fun radius(level: Int): Double = when {
            level >= GeoClueAccuracy.EXACT -> 100.0
            level >= GeoClueAccuracy.STREET -> 1_000.0
            level >= GeoClueAccuracy.NEIGHBORHOOD -> 5_000.0
            level >= GeoClueAccuracy.CITY -> 15_000.0
            else -> 300_000.0
        }

        // A fix with unknown accuracy ranks last and meets no level.
        private fun metres(fix: GeoClueFix) = fix.accuracy ?: Double.MAX_VALUE
        private fun meets(fix: GeoClueFix, level: Int) = metres(fix) <= radius(level)

        private fun ageText(ms: Long) = if (ms < 120_000) "${ms / 1000} s" else "${ms / 60_000} min"

        private fun accuracyText(fix: GeoClueFix?) = when {
            fix == null -> "none"
            fix.accuracy == null -> "accuracy unknown"
            else -> "±${fix.accuracy.toLong()} m"
        }

        fun levelName(level: Int) = when (level) {
            GeoClueAccuracy.COUNTRY -> "country"
            GeoClueAccuracy.CITY -> "city"
            GeoClueAccuracy.NEIGHBORHOOD -> "neighborhood"
            GeoClueAccuracy.STREET -> "street"
            GeoClueAccuracy.EXACT -> "exact"
            else -> "level $level"
        }
    }
}

/** One GeoClue client: [start] creates and starts it at an accuracy level, [read] returns its
 *  location (null until the first fix), [stop] stops and drops it. Blocking. */
internal interface GeoClueSession {
    fun start(accuracy: Int)
    fun read(): GeoClueFix?
    fun stop()
}

/** [GeoClueSession] over the system bus. Each [start] opens its own private connection (not the shared
 *  one ModemManager uses): GeoClue hands out one client per D-Bus peer, so a fresh peer means a fresh
 *  client whose `Location` starts at `/` (no fix yet) rather than holding the last client's, and
 *  closing the peer in [stop] drops the client in GeoClue. */
private class DbusGeoClueSession(private val desktopId: String) : GeoClueSession {
    private var conn: DBusConnection? = null
    private var clientPath: String? = null

    override fun start(accuracy: Int) {
        stop()
        val c = openPrivateSystemBus()
        conn = c
        val manager = c.getRemoteObject(GEOCLUE_BUS_NAME, GEOCLUE_MANAGER_PATH, GeoClueManager::class.java)
        val path = manager.GetClient().path
        val props = c.getRemoteObject(GEOCLUE_BUS_NAME, path, Properties::class.java)
        props.Set(GEOCLUE_CLIENT_IFACE, "DesktopId", desktopId)
        props.Set(GEOCLUE_CLIENT_IFACE, "RequestedAccuracyLevel", UInt32(accuracy.toLong()))
        c.getRemoteObject(GEOCLUE_BUS_NAME, path, GeoClueClient::class.java).Start()
        clientPath = path
    }

    override fun read(): GeoClueFix? {
        val c = conn ?: error("GeoClue client not started")
        val cp = clientPath ?: error("GeoClue client not started")
        val locPath = c.getRemoteObject(GEOCLUE_BUS_NAME, cp, Properties::class.java)
            .Get<DBusPath>(GEOCLUE_CLIENT_IFACE, "Location")
        if (locPath == null || locPath.path == "/" || locPath.path.isEmpty()) return null
        val p = c.getRemoteObject(GEOCLUE_BUS_NAME, locPath.path, Properties::class.java)
        val lat = readDouble(p, "Latitude") ?: return null
        val lon = readDouble(p, "Longitude") ?: return null
        return GeoClueFix(
            latitude = lat,
            longitude = lon,
            // GeoClue uses sentinels for "unknown": negative accuracy, a hugely negative altitude
            // (-G_MAXDOUBLE), and -1 for speed/heading. Map those to null.
            accuracy = readDouble(p, "Accuracy")?.takeIf { it >= 0 },
            altitude = readDouble(p, "Altitude")?.takeIf { it > -1.0e308 },
            speed = readDouble(p, "Speed")?.takeIf { it >= 0 },
            heading = readDouble(p, "Heading")?.takeIf { it >= 0 },
        )
    }

    override fun stop() {
        val c = conn ?: return
        clientPath?.let { path ->
            runCatching { c.getRemoteObject(GEOCLUE_BUS_NAME, path, GeoClueClient::class.java).Stop() }
        }
        runCatching { c.disconnect() }
        conn = null
        clientPath = null
    }

    private fun readDouble(p: Properties, name: String): Double? =
        try { (p.Get<Any>(GEOCLUE_LOCATION_IFACE, name) as? Number)?.toDouble() } catch (_: Exception) { null }
}
