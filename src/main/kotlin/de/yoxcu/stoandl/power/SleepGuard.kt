package de.yoxcu.stoandl.power

import io.github.oshai.kotlinlogging.KotlinLogging
import io.rebble.libpebblecommon.connection.WatchLinkActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import org.freedesktop.dbus.matchrules.DBusMatchRuleBuilder
import org.freedesktop.dbus.messages.DBusSignal
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

private val log = KotlinLogging.logger {}

private const val LOGIN1_PATH = "/org/freedesktop/login1"
private const val LOGIN1_MANAGER = "org.freedesktop.login1.Manager"

/**
 * Makes stoandl a good citizen on a host that suspends aggressively — a phone that wakes for a push
 * message, stays up a few seconds and suspends again (s2idle), possibly with the watch link kept alive
 * by the Bluetooth controller across the suspend ("Mode B" on the OnePlus 6).
 *
 * logind protocol: a process holding a *delay* inhibitor on "sleep" gets `PrepareForSleep(true)` before
 * the system suspends, and logind waits until it releases the lock (at most `InhibitDelayMaxSec`, 5 s
 * by default). We hold one permanently and on `PrepareForSleep(true)`:
 *  1. tell libpebble3 the host is going down ([WatchLinkActivity.setHostSuspending] — a link boosted to
 *     fast connection parameters drops to its idle set, so it never sleeps fast);
 *  2. wait while watch traffic is still owed or awaited — the notification the push that woke us
 *     produced, a PPoG ACK ([pendingWork]) — for at most [maxHold];
 *  3. release the lock, so the suspend proceeds.
 * On `PrepareForSleep(false)` we clear the hint, emit [resumed] (overdue wall-clock work runs on it,
 * see [delayWallClock]) and take the lock again — logind refuses new delay locks for a moment right
 * after resume ("The operation inhibition has been requested for is already running"), so retry.
 *
 * A delay lock never makes a suspend *fail* — unlike a block lock, which makes a one-shot
 * `systemctl suspend` from a helper (the phone's fast-resuspend) give up and leaves the phone awake.
 * Worst case it postpones a suspend by [maxHold]. If the same pending item hits [maxHold] three times
 * in a row, it is treated as stuck and ignored until it changes, so a bookkeeping bug can't add
 * [maxHold] to every suspend forever.
 *
 * The lock is held by a `systemd-inhibit --mode=delay … cat` child (or `elogind-inhibit`), because
 * dbus-java's native-unixsocket transport can't receive the file descriptor logind's `Inhibit()`
 * returns. `cat` blocks on a pipe from us: if the JVM dies the pipe closes, cat exits and the lock goes
 * with it — no orphaned inhibitor delaying every suspend. Destroying systemd-inhibit releases the lock
 * at once (it keeps the fd to itself and its child gets SIGTERM with it). polkit allows delay locks
 * on sleep for any subject (`org.freedesktop.login1.inhibit-delay-sleep`: allow_any=yes), so this works
 * from the systemd user service.
 *
 * The PrepareForSleep subscription (and [resumed]) works even with the lock disabled
 * (`power.sleep_guard = false`); if the system bus is unavailable the guard is inert.
 */
class SleepGuard(
    private val scope: CoroutineScope,
    private val linkActivity: WatchLinkActivity,
    private val holdLock: Boolean,
    private val maxHold: Duration,
    /** What is still pending towards the watch, or null when nothing is (polled while draining). */
    private val pendingWork: () -> String?,
    /** Runs first on every PrepareForSleep(true), before the drain (e.g. a last watch message whose
     *  delivery the drain then waits for). Errors are logged, never block the suspend. Bounded by
     *  [maxHold] but never below [BEFORE_SLEEP_MIN], so `power.sleep_guard_max_ms = 0` ("don't wait for
     *  watch traffic") still runs it. */
    private val beforeSleep: (suspend () -> Unit)? = null,
) {
    private val _resumed = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** Emits after every resume (logind `PrepareForSleep(false)`). */
    val resumed: SharedFlow<Unit> = _resumed.asSharedFlow()

    // Everything that touches the lock runs on ONE consumer of this channel, strictly in order: a quick
    // true→false pair when a suspend is aborted must not race the drain against the re-acquire, and two
    // concurrent acquires could orphan an inhibitor that then delays every suspend.
    private sealed interface Event {
        data object Sleep : Event
        data object Resume : Event
        data class Acquire(val attempt: Int, val generation: Int) : Event
        data class Lost(val process: Process) : Event
    }
    private val events = Channel<Event>(Channel.UNLIMITED)
    @Volatile private var conn: DBusConnection? = null
    // Consumer-owned state (only touched from the consumer coroutine, except stop() at shutdown).
    private var lock: Process? = null
    private var generation = 0 // bumped on every sleep/resume edge; stale Acquire retries are dropped
    private var inhibitCmd: String? = null
    private var stuckWhat: String? = null
    private var stuckCount = 0
    private var resumedAt: TimeSource.Monotonic.ValueTimeMark? = null

    fun start() {
        val c = try {
            DBusConnectionBuilder.forSystemBus().withShared(false).build()
        } catch (e: Exception) {
            log.info { "Sleep guard unavailable (no system bus): ${e.message}" }
            return
        }
        conn = c
        try {
            val rule = DBusMatchRuleBuilder.create()
                .withType("signal").withInterface(LOGIN1_MANAGER)
                .withMember("PrepareForSleep").withPath(LOGIN1_PATH).build()
            c.addGenericSigHandler(rule) { msg: DBusSignal ->
                (msg.getParameters()?.getOrNull(0) as? Boolean)?.let { goingDown ->
                    events.trySend(if (goingDown) Event.Sleep else Event.Resume)
                }
            }
        } catch (e: Exception) {
            log.info { "Sleep guard unavailable (cannot subscribe to logind PrepareForSleep): ${e.message}" }
            c.disconnect()
            conn = null
            return
        }
        scope.launch {
            for (event in events) {
                when (event) {
                    Event.Sleep -> onPrepareForSleep()
                    Event.Resume -> onResumed()
                    is Event.Acquire -> acquire(event)
                    is Event.Lost -> if (lock === event.process) {
                        lock = null
                        log.warn { "logind delay lock lost (inhibitor exited ${event.process.exitValue()}) — re-acquiring" }
                        acquire(Event.Acquire(0, generation))
                    }
                }
            }
        }
        if (holdLock) events.trySend(Event.Acquire(0, generation))
        log.info {
            if (holdLock) "Sleep guard on: logind delay lock, suspends wait up to ${maxHold.inWholeMilliseconds} ms for watch deliveries"
            else "Sleep guard: lock off (power.sleep_guard=false); tracking suspend/resume only"
        }
    }

    fun stop() {
        events.close()
        release()
        conn?.disconnect()
        conn = null
    }

    private suspend fun onPrepareForSleep() {
        generation++
        val t0 = TimeSource.Monotonic.markNow()
        linkActivity.setHostSuspending(true)
        beforeSleep?.let { hook ->
            // withTimeoutOrNull(0) returns without running the block at all.
            runCatching { withTimeoutOrNull(maxHold.coerceAtLeast(BEFORE_SLEEP_MIN)) { hook() } }
                .onFailure { log.warn { "before-sleep hook failed: ${it.message}" } }
        }
        val awakeFor = resumedAt?.elapsedNow()
        var what = pendingWork()
        val first = what
        val ignored = what != null && what == stuckWhat && stuckCount >= STUCK_AFTER
        if (!ignored) {
            while (what != null && t0.elapsedNow() < maxHold) {
                delay(DRAIN_POLL)
                what = pendingWork()
            }
        }
        val held = t0.elapsedNow().inWholeMilliseconds
        when {
            first == null -> {
                stuckWhat = null; stuckCount = 0
                log.debug { "PrepareForSleep: nothing pending (awake ${awakeFor?.inWholeMilliseconds ?: "?"} ms)" }
            }
            ignored -> log.debug { "PrepareForSleep: ignoring '$first' (hit the ${maxHold.inWholeMilliseconds} ms cap $stuckCount times in a row)" }
            what == null -> {
                stuckWhat = null; stuckCount = 0
                log.info { "PrepareForSleep: held the suspend $held ms until the watch traffic was done ($first)" }
            }
            else -> {
                if (what == stuckWhat) stuckCount++ else { stuckWhat = what; stuckCount = 1 }
                log.warn { "PrepareForSleep: still pending after $held ms ($what) — letting the system sleep; it continues on the next wake" }
            }
        }
        release()
    }

    private suspend fun onResumed() {
        generation++
        resumedAt = TimeSource.Monotonic.markNow()
        linkActivity.setHostSuspending(false)
        _resumed.tryEmit(Unit)
        log.debug { "resumed" }
        if (holdLock) acquire(Event.Acquire(0, generation))
    }

    /** One attempt to take the delay lock; schedules the next one while logind still refuses it. */
    private suspend fun acquire(event: Event.Acquire) {
        if (event.generation != generation || lock?.isAlive == true) return
        val p = spawn() ?: return
        // systemd-inhibit exits at once when logind refuses the lock; still alive = we hold it.
        val exited = withContext(Dispatchers.IO) { p.waitFor(ACQUIRE_SETTLE.inWholeMilliseconds, TimeUnit.MILLISECONDS) }
        if (!exited) {
            lock = p
            // Died on its own later (logind restarted, killed by someone)? Take it again.
            p.onExit().thenAccept { dead -> events.trySend(Event.Lost(dead)) }
            if (event.attempt > 0) log.debug { "delay lock taken after ${event.attempt + 1} attempts" }
            return
        }
        val err = runCatching { p.errorStream.bufferedReader().readText().trim() }.getOrDefault("")
        log.debug { "delay lock refused (attempt ${event.attempt + 1}): ${err.ifEmpty { "exit ${p.exitValue()}" }}" }
        if (event.attempt + 1 >= ACQUIRE_ATTEMPTS) {
            log.warn { "Could not take the logind delay lock — suspends won't wait for watch deliveries until the next resume" }
            return
        }
        val backoff = (ACQUIRE_BACKOFF * (1 shl event.attempt)).coerceAtMost(5.seconds)
        scope.launch {
            delay(backoff)
            events.trySend(Event.Acquire(event.attempt + 1, event.generation))
        }
    }

    private fun spawn(): Process? {
        val candidates = inhibitCmd?.let { listOf(it) } ?: listOf("systemd-inhibit", "elogind-inhibit")
        for (cmd in candidates) {
            try {
                val p = ProcessBuilder(
                    cmd, "--what=sleep", "--who=stoandl",
                    "--why=Deliver pending notifications to the watch before suspend",
                    "--mode=delay", "cat",
                ).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
                inhibitCmd = cmd
                return p
            } catch (_: IOException) {
                // not installed — try the next one
            }
        }
        log.warn { "Sleep guard: neither systemd-inhibit nor elogind-inhibit found — no delay lock" }
        return null
    }

    private fun release() {
        val p = lock ?: return
        lock = null
        runCatching { p.outputStream.close() } // cat sees EOF…
        p.destroy()                             // …and systemd-inhibit drops the lock right away
    }

    private companion object {
        private val DRAIN_POLL = 50.milliseconds
        // The before-sleep hook's floor: it stops our own discovery (non-suspending calls) and may queue
        // one watch message, both far quicker than this.
        private val BEFORE_SLEEP_MIN = 500.milliseconds
        private val ACQUIRE_SETTLE = 300.milliseconds
        private val ACQUIRE_BACKOFF = 300.milliseconds
        private const val ACQUIRE_ATTEMPTS = 8
        private const val STUCK_AFTER = 3
    }
}
