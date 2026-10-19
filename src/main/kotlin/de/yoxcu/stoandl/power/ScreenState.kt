package de.yoxcu.stoandl.power

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import java.io.File
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Whether the host's display is on, read from the kernel's DRM connectors
 * (`/sys/class/drm/card*-<connector>/dpms`, world-readable). On a phone this follows the panel exactly:
 * Plasma Mobile / KWin turn the DSI panel's DPMS off when the screen goes dark, and the OnePlus 6
 * wake-on-notification scripts use the same file to decide whether the user is looking.
 *
 * Used to hold back watch traffic nobody is looking at (`power.pause_datalog_screen_off`) and to warn
 * about another app's Bluetooth discovery left running with the display off, which makes the controller
 * report (and, with links kept across suspend, wake the host for) every nearby advertiser. stoandl's own
 * pairing-window scans don't consult it: they are explicit requests.
 *
 * Returns null when it can't tell (no DRM device, a headless box, sysfs unreadable): callers treat
 * that as "on" so nothing is gated on machines without a local display.
 */
object ScreenState {
    private val DRM = File("/sys/class/drm")

    fun isOn(): Boolean? {
        // cardN-<connector>, minus writeback connectors (not a display; their DPMS says nothing).
        val connectors = DRM.listFiles { f ->
            f.name.startsWith("card") && f.name.contains('-') && !f.name.contains("Writeback")
        } ?: return null
        var sawOff = false
        for (c in connectors) {
            val status = read(File(c, "status")) ?: continue
            if (status == "disconnected") continue
            when (read(File(c, "dpms"))) {
                "On" -> return true
                "Off", "Standby", "Suspend" -> sawOff = true
            }
        }
        return if (sawOff) false else null
    }

    private fun read(f: File): String? = try { f.readText().trim() } catch (_: Exception) { null }
}

/**
 * The display's state ([ScreenState.isOn]) as one shared flow for everything that follows it (the datalog
 * pause, the Quiet Time link pause): sampled every [period] while at least one of them collects it, and not
 * at all otherwise, so a feature that is off costs no wakeups and two that are on cost one poll.
 */
class DisplayState(scope: CoroutineScope, period: Duration = 5.seconds) {
    val on: StateFlow<Boolean?> = flow {
        while (true) {
            emit(ScreenState.isOn())
            delay(period)
        }
    }.flowOn(Dispatchers.IO).stateIn(scope, SharingStarted.WhileSubscribed(), ScreenState.isOn())
}
