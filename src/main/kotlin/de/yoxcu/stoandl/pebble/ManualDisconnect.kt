package de.yoxcu.stoandl.pebble

import io.github.oshai.kotlinlogging.KotlinLogging
import io.rebble.libpebblecommon.connection.WatchConnector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

private val log = KotlinLogging.logger {}

/**
 * `stoandl watch disconnect` / `stoandl watch connect`: the user keeps the watch link off until they
 * resume it.
 *
 * The pause is a [WatchConnector.holdDisconnected], not `requestDisconnection()`: that one persists
 * connectGoal=false, and a launch after it never reconnects the watch on its own. The hold covers every
 * watch, which is what a disconnect means here: stoandl runs libpebble3 single-watch (one watch links at
 * a time, the one holding the connect goal). Holds stack in the fork, so a Quiet Time pause
 * (power.quiet_time_link_off) ending doesn't end this one, and resuming this one inside Quiet Time leaves the
 * link off until the window is over.
 *
 * [marker] records the pause; [persist] (connection.disconnect_persists, read live) decides whether a
 * daemon restart honours it ([restore]) — the official app's Disconnect lasts across restarts too.
 */
internal class ManualDisconnect(
    private val connector: WatchConnector,
    private val marker: File,
    private val persist: () -> Boolean,
) {
    private var hold: AutoCloseable? = null
    private val _paused = MutableStateFlow(false)
    val paused: StateFlow<Boolean> = _paused.asStateFlow()
    val held: Boolean get() = _paused.value

    /** Before libpebble3 connects anything: take the pause again if it outlived the last run. */
    @Synchronized
    fun restore() {
        if (!marker.exists()) return
        if (!persist()) {
            marker.delete()
            return
        }
        take()
        log.info { "Watch connection stays paused (stoandl watch disconnect before the restart; `stoandl watch connect` resumes it)" }
    }

    /** Pauses the link; false if it already was. */
    @Synchronized
    fun disconnect(): Boolean {
        if (hold != null) return false
        take()
        runCatching {
            marker.parentFile?.mkdirs()
            marker.writeText("${System.currentTimeMillis()}\n")
        }.onFailure { log.warn { "Couldn't record the paused watch connection in $marker: ${it.message}" } }
        log.info { "Watch connection paused by hand (`stoandl watch connect` resumes it)" }
        return true
    }

    /** Ends the pause; false if there was none. */
    @Synchronized
    fun resume(): Boolean {
        marker.delete()
        val current = hold ?: return false
        hold = null
        _paused.value = false
        current.close()
        log.info { "Watch connection resumed" }
        return true
    }

    private fun take() {
        hold = connector.holdDisconnected("stoandl watch disconnect")
        _paused.value = true
    }
}

/** A known watch as a bare `stoandl watch connect` sees it. */
internal data class GoalCandidate(val id: String, val lastConnectedMs: Long)

/**
 * The watch a bare `stoandl watch connect` gives the connect goal to, or null to leave the goal where it is.
 * Nothing moves while a watch holds it ([goalHeld]: libpebble3 shows one connecting or connected, which a
 * watch with the goal always is while Bluetooth is on — a new watch still pairing counts, known or not) or
 * while a pairing window is open: the window owns the goal, handing it to its new watch or, when it ends
 * without one, back to the watch that had it. Otherwise (a failed pairing cleared it, or the watch that held
 * it was unpaired) a lone watch gets it, and of several the most recently connected one, but only with
 * Bluetooth on: while it is off no watch shows its goal, so one may hold it unseen and must not lose it.
 */
internal fun resumeGoalTo(known: List<GoalCandidate>, goalHeld: Boolean, btOn: Boolean, pairingWindowOpen: Boolean): String? =
    when {
        goalHeld || pairingWindowOpen -> null
        known.size > 1 && !btOn -> null
        else -> known.maxByOrNull { it.lastConnectedMs }?.id
    }

/**
 * Why `stoandl watch connect <watch>` may not hand the goal to a watch that doesn't hold it yet, or null when
 * it may. Not during a firmware or language update: the flashing watch needs the link to confirm it. Not while
 * a pairing window is open: the window gives the goal back to the watch that had it when it ends without a new
 * watch, which would silently undo the switch.
 */
internal fun switchRefusal(updateRunning: Boolean, pairingWindowOpen: Boolean): String? = when {
    updateRunning -> "A firmware or language update is running — switch watches when it's done"
    pairingWindowOpen -> "A pairing window is open — switch watches when it's closed"
    else -> null
}
