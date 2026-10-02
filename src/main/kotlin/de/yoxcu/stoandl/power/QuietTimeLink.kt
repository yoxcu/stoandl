package de.yoxcu.stoandl.power

import io.github.oshai.kotlinlogging.KotlinLogging
import io.rebble.libpebblecommon.connection.ActiveDevice
import io.rebble.libpebblecommon.connection.ConnectedPebbleDevice
import io.rebble.libpebblecommon.connection.LibPebble
import io.rebble.libpebblecommon.connection.WatchConnector
import io.rebble.libpebblecommon.database.dao.WatchPreference
import io.rebble.libpebblecommon.database.entity.AlertMask
import io.rebble.libpebblecommon.database.entity.BoolWatchPref
import io.rebble.libpebblecommon.database.entity.EnumWatchPref
import io.rebble.libpebblecommon.database.entity.QuietTimeSchedule
import io.rebble.libpebblecommon.database.entity.ScheduleWatchPref
import io.rebble.libpebblecommon.database.entity.WatchPref
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.time.DayOfWeek
import java.time.LocalDateTime
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

private val log = KotlinLogging.logger {}

/**
 * `power.quiet_time_link_off`: no watch link during the watch's scheduled Quiet Time.
 *
 * An idle BLE link still costs the phone a radio exchange every 30–45 ms (the watch's own idle set;
 * the central transmits at every connection event), all night. At night the watch mutes almost
 * everything anyway, so the link is paused instead: stoandl takes a [WatchConnector.holdDisconnected]
 * (in memory, the persisted connect goal is untouched) and releases it when Quiet Time is over.
 *
 * The hold is wanted while all of these are true, and taken once no watch traffic is owed:
 *  - the switch is on (read live);
 *  - the watch's *scheduled* window is active ([scheduledQuietTime]; manual and calendar Quiet Time are
 *    usually short and in the day, and the watch-local "turned off by hand" override isn't synced);
 *  - calls can't interrupt Quiet Time ([callsMayInterrupt]): if they can, the watch would buzz for a
 *    call, so the link stays (said once per window);
 *  - the display has been off for [SCREEN_OFF_MIN] (someone using the phone at 23:00 keeps the link,
 *    and the link doesn't flap with the screen); a display that is off when stoandl starts counts as off
 *    long enough, so a restart inside the window stays paused;
 *  - nothing else needs the link ([mustStayOn]: a pairing window, a firmware or language update, a call).
 * Any of them turning false releases the hold: the window ends, the display comes on, the switch goes off.
 *
 * Evaluated every [TICK] while awake and on every resume. On a phone that sleeps through the window's
 * edges, the hold starts and ends at the first wake after them. The disconnect and, for a short while,
 * the reconnect after a release are reported as pending work ([pendingDescription]), so the sleep guard
 * lets them finish before a suspend. Notifications posted while paused reach the watch on reconnect:
 * paused time doesn't count towards the catch-up window (libpebble3 NotificationCatchUp).
 */
class QuietTimeLink(
    private val scope: CoroutineScope,
    private val libPebble: LibPebble,
    private val watchConnector: WatchConnector,
    private val enabled: () -> Boolean,
    /** Why the link must stay up right now regardless of Quiet Time, or null. */
    private val mustStayOn: () -> String?,
    /** Watch traffic still owed (not counting this class's own); the hold waits until it is null. */
    private val pendingWork: () -> String?,
    private val wakeups: Flow<Unit>,
) {
    @Volatile private var hold: AutoCloseable? = null
    @Volatile private var releasedAtMs: Long? = null
    private var screenOffSinceMs: Long? = null
    private var callsNoted = false
    @Volatile private var prefs: List<WatchPreference<*>> = emptyList()

    /** Whether the link is paused right now. */
    val held: Boolean get() = hold != null

    /**
     * Decides once before libpebble3 connects anything (a restart inside the window stays paused), then
     * follows the watch prefs and re-evaluates every [TICK] and on every resume.
     */
    fun start() {
        prefs = runBlocking { libPebble.watchPrefs.first() }
        if (ScreenState.isOn() == false) screenOffSinceMs = 0L
        evaluate()
        scope.launch { libPebble.watchPrefs.collect { prefs = it } }
        scope.launch {
            while (true) {
                withTimeoutOrNull(TICK) { wakeups.first() }
                evaluate()
            }
        }
    }

    /** For the sleep guard: the pause's own disconnect, or the reconnect right after it, still running. */
    fun pendingDescription(): String? {
        val active = libPebble.watches.value.any { it is ActiveDevice }
        if (hold != null) return if (active) "Quiet Time disconnect" else null
        val released = releasedAtMs ?: return null
        if (System.currentTimeMillis() - released > RECONNECT_GRACE.inWholeMilliseconds ||
            libPebble.watches.value.any { it is ConnectedPebbleDevice }
        ) {
            releasedAtMs = null
            return null
        }
        return "reconnect after Quiet Time"
    }

    @Synchronized
    private fun evaluate() {
        val nowMs = System.currentTimeMillis()
        val screenOn = ScreenState.isOn() != false // unknown counts as on: never pause blind
        screenOffSinceMs = if (screenOn) null else screenOffSinceMs ?: nowMs
        val window = scheduledQuietTime(prefs, LocalDateTime.now())
        if (window == null) callsNoted = false

        val stayReason: String? = when {
            !enabled() -> "switched off"
            window == null -> "Quiet Time over"
            callsMayInterrupt(prefs) -> {
                if (!callsNoted) {
                    callsNoted = true
                    log.info { "Quiet Time: watch connection stays on — calls may interrupt Quiet Time (dndInterruptionsMask)" }
                }
                "calls may interrupt Quiet Time"
            }
            screenOn -> "display on"
            nowMs - (screenOffSinceMs ?: nowMs) < SCREEN_OFF_MIN.inWholeMilliseconds -> "display off only briefly"
            else -> mustStayOn()
        }

        val current = hold
        if (current != null && stayReason != null) {
            hold = null
            releasedAtMs = nowMs
            current.close()
            log.info { "Quiet Time: watch connection back on ($stayReason)" }
        } else if (current == null && stayReason == null && window != null) {
            pendingWork()?.let { owed ->
                log.debug { "Quiet Time: pausing the watch connection once this is delivered: $owed" }
                return
            }
            hold = watchConnector.holdDisconnected("Quiet Time")
            releasedAtMs = null
            log.info { "Quiet Time: watch connection paused until ${window.toTime()}" }
        }
    }

    private companion object {
        private val TICK = 5.seconds
        private val SCREEN_OFF_MIN = 10.minutes
        // After a release, suspends wait (each up to the sleep guard's cap) for the reconnect this long.
        private val RECONNECT_GRACE = 20.seconds
    }
}

private fun <T> List<WatchPreference<*>>.valueOf(pref: WatchPref<T>): T {
    @Suppress("UNCHECKED_CAST")
    return (firstOrNull { it.pref == pref } as WatchPreference<T>?)?.valueOrDefault() ?: pref.defaultValue
}

/**
 * The watch's active *scheduled* Quiet Time at [now] (watch-local time = host time, the watch's clock
 * is synced from the host), or null. The PebbleOS rule (`do_not_disturb.c` `prv_is_in_schedule_period`):
 * today's schedule decides — Saturday and Sunday the weekend one, other days the weekday one — and only if
 * it is enabled; a window whose end is before its start runs overnight; start = end is never. So at 01:00
 * on a Saturday the weekend schedule applies, even if Friday's weekday window started at 22:00.
 */
internal fun scheduledQuietTime(prefs: List<WatchPreference<*>>, now: LocalDateTime): QuietTimeSchedule? {
    val weekend = now.dayOfWeek == DayOfWeek.SATURDAY || now.dayOfWeek == DayOfWeek.SUNDAY
    val enabled = prefs.valueOf(
        if (weekend) BoolWatchPref.QuietTimeWeekendScheduleEnabled else BoolWatchPref.QuietTimeWeekdayScheduleEnabled,
    )
    if (!enabled) return null
    val schedule = prefs.valueOf(
        if (weekend) ScheduleWatchPref.QuietTimeWeekendSchedule else ScheduleWatchPref.QuietTimeWeekdaySchedule,
    )
    val from = schedule.fromHour * 60 + schedule.fromMinute
    val to = schedule.toHour * 60 + schedule.toMinute
    val minute = now.hour * 60 + now.minute
    val inside = if (from < to) minute in from until to else from != to && (minute >= from || minute < to)
    return schedule.takeIf { inside }
}

/** Whether calls may interrupt Quiet Time (`dndInterruptionsMask` includes phone calls). */
internal fun callsMayInterrupt(prefs: List<WatchPreference<*>>): Boolean =
    (prefs.valueOf(EnumWatchPref.QuietTimeInterruptions).code.toInt() and AlertMask.PhoneCalls.code.toInt()) != 0
