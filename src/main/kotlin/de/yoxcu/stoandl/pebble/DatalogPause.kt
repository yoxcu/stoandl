package de.yoxcu.stoandl.pebble

import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

/** Data held back at least this long is asked for at the next resume: PebbleOS's own "empty" datalog
 *  interval, so data held for less is less than one flush late. Also how long sends must stay on for the
 *  watch's own empty flush to have run (three enabled 5-minute ticks). */
internal val DATALOG_FLUSH_MIN_HELD = 15.minutes

/** How long sends must stay on after a backlog request for the backlog to count as sent: a dark night's
 *  ~36 KB leaves in ~15 s at 0.23 s per 600 B chunk, and a pause stops it after the chunk in flight. */
internal val DATALOG_FLUSH_SEND_TIME = 1.minutes

/**
 * Whether a resume of the datalog sends (`power.pause_datalog_screen_off`) should ask the watch for the
 * backlog.
 *
 * PebbleOS's DataLogging SetSendEnabled(true) only sets a flag. Its session timer doesn't advance while
 * sends are off, so the spooled data leaves only on a later *enabled* tick — the 5-minute one for a session
 * of 8000 B or more, else the "empty" one every third enabled tick — which short screen-ons rarely reach.
 * A health-sync request (endpoint 911) makes the watch send every session at once.
 *
 * So the held time runs from the first pause since the watch last sent everything, across short screen-ons
 * in between, not per pause: several short dark periods add up. It ends only when the watch has sent
 * everything: sends stayed on [DATALOG_FLUSH_SEND_TIME] after a backlog request, or [DATALOG_FLUSH_MIN_HELD]
 * without one (its own empty flush). A resume once the held time reaches [DATALOG_FLUSH_MIN_HELD] asks for
 * the backlog; a request cut short by the next pause is asked for again at the following resume.
 *
 * Wall-clock times: a pause spans suspends, which a monotonic clock doesn't count.
 */
internal class DatalogPause {
    /** Start of the first pause since the watch last sent everything; null when nothing is held. */
    private var heldSinceMs: Long? = null
    /** Start of the current stretch with sends on; null while paused. */
    private var enabledSinceMs: Long? = null
    /** Whether the current stretch began with a backlog request. */
    private var requested = false

    /**
     * Record the send state pushed at [nowMs] (epoch ms). Returns how long data has been held when this
     * resume should ask for the backlog, else null. A repeated state changes nothing but may end the held
     * time; a clock set back past a start counts as too short.
     */
    @Synchronized
    fun update(enabled: Boolean, nowMs: Long): Duration? {
        enabledSinceMs?.let { since ->
            val sent = (nowMs - since).milliseconds
            if (sent >= DATALOG_FLUSH_MIN_HELD || (requested && sent >= DATALOG_FLUSH_SEND_TIME)) heldSinceMs = null
        }
        if (!enabled) {
            enabledSinceMs = null
            if (heldSinceMs == null) heldSinceMs = nowMs
            return null
        }
        if (enabledSinceMs != null) return null
        enabledSinceMs = nowMs
        val held = heldSinceMs?.let { (nowMs - it).milliseconds }?.takeIf { it >= DATALOG_FLUSH_MIN_HELD }
        requested = held != null
        return held
    }
}
