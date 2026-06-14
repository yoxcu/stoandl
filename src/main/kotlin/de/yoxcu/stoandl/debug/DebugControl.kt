package de.yoxcu.stoandl.debug

import io.github.oshai.kotlinlogging.KotlinLogging
import io.rebble.libpebblecommon.connection.ConnectedPebbleDevice
import io.rebble.libpebblecommon.connection.LibPebble
import java.util.concurrent.atomic.AtomicReference

private val log = KotlinLogging.logger {}

/**
 * Watch reset operations off libpebble3's [io.rebble.libpebblecommon.connection.ConnectedPebble.Debug]:
 * a **factory reset** (wipe the watch back to out-of-box state) and a **reboot into recovery (PRF)
 * firmware**. The natural companion to the firmware tooling — for un-bricking a bad flash and for a
 * clean handoff.
 *
 * Both are fire-and-forget: libpebble3 sends a single RESET-endpoint packet and the watch acts on it
 * (and drops the BLE link as it wipes/reboots), so there's no completion ack to await — the call
 * returns as soon as the packet is queued. Pure wiring: these methods live on every
 * [ConnectedPebbleDevice] (no fork change, no JVM stub, unlike the screenshot path).
 *
 * Each method returns a status-prefixed string as the other controls do (`ok:<msg>` /
 * `notready:<msg>` / `error:<msg>`). The destructive confirmation for a factory reset lives in the
 * CLI, not here — the daemon just executes.
 */
class DebugControl(
    private val libPebbleRef: AtomicReference<LibPebble?>,
) {
    private fun device(): ConnectedPebbleDevice? =
        libPebbleRef.get()?.watches?.value?.filterIsInstance<ConnectedPebbleDevice>()?.firstOrNull()

    /**
     * Wipe the connected watch back to factory state (clears bonds, installed apps, settings — the
     * whole user data store) and reboot. Irreversible. The watch will drop the BLE link and, once it
     * comes back, be in its out-of-box state, so it needs re-pairing afterwards.
     */
    fun factoryReset(): String {
        libPebbleRef.get() ?: return "notready:libPebble not ready"
        val dev = device() ?: return "notready:No watch connected"
        return try {
            dev.factoryReset()
            log.info { "Sent factory-reset to ${dev.displayName()}" }
            "ok:Factory reset sent to ${dev.displayName()} — the watch will wipe and reboot"
        } catch (e: Exception) {
            log.warn(e) { "factoryReset failed" }
            "error:${e.message ?: "factory reset failed"}"
        }
    }

    /**
     * Reboot the connected watch into its recovery (PRF) firmware. Used to recover from a bad normal
     * firmware. The watch drops the BLE link and reboots into PRF; reflash a normal firmware
     * (`stoandl firmware …`) from there.
     */
    fun resetIntoRecovery(): String {
        libPebbleRef.get() ?: return "notready:libPebble not ready"
        val dev = device() ?: return "notready:No watch connected"
        return try {
            dev.resetIntoPrf()
            log.info { "Sent reset-into-recovery (PRF) to ${dev.displayName()}" }
            "ok:Recovery reboot sent to ${dev.displayName()} — the watch will reboot into PRF"
        } catch (e: Exception) {
            log.warn(e) { "resetIntoPrf failed" }
            "error:${e.message ?: "recovery reset failed"}"
        }
    }
}
