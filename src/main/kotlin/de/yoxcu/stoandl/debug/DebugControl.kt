package de.yoxcu.stoandl.debug

import de.yoxcu.stoandl.util.connectedDevice
import io.github.oshai.kotlinlogging.KotlinLogging
import io.rebble.libpebblecommon.connection.ConnectedPebbleDevice
import io.rebble.libpebblecommon.connection.LibPebble
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicReference
import kotlin.random.Random

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
    private fun device(): ConnectedPebbleDevice? = libPebbleRef.connectedDevice()

    /**
     * Wipe the connected watch back to factory state (clears bonds, installed apps, settings — the
     * whole user data store) and reboot. Irreversible. The watch will drop the BLE link and, once it
     * comes back, be in its out-of-box state, so it needs re-pairing afterwards.
     */
    fun factoryReset(): String {
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

    /**
     * Round-trip liveness check: send a PING with a random cookie and await the echoed cookie,
     * reporting latency. Unlike the BLE link state (up/down), this exercises the full PPoG/protocol
     * path end-to-end — useful to tell a healthy link from a wedged one, and the latency is handy in
     * the support bundle. Blocks briefly on the round-trip.
     */
    fun ping(): String {
        val dev = device() ?: return "notready:No watch connected"
        val cookie = Random.nextInt().toUInt()
        return try {
            val startNs = System.nanoTime()
            val echoed = runBlocking { dev.sendPing(cookie) }
            val ms = (System.nanoTime() - startNs) / 1_000_000
            if (echoed == cookie) "ok:Ping round-trip $ms ms (${dev.displayName()})"
            else "error:Ping cookie mismatch (sent $cookie, got $echoed)"
        } catch (e: Exception) {
            log.warn(e) { "sendPing failed" }
            "error:${e.message ?: "ping failed"}"
        }
    }

    /**
     * Ask the watch to capture a fresh core dump — a snapshot of its RAM for crash diagnosis. The
     * watch writes the dump to flash and reboots to do so (it drops the BLE link like a reset), after
     * which the dump can be pulled with `stoandl support --coredump` ([GetCoreDump]). Fire-and-forget:
     * libpebble3 sends a single RESET-endpoint packet and there's no completion ack.
     */
    fun forceCoreDump(): String {
        val dev = device() ?: return "notready:No watch connected"
        return try {
            dev.createCoreDump()
            log.info { "Requested core-dump capture on ${dev.displayName()}" }
            "ok:Core-dump requested on ${dev.displayName()} — the watch reboots to capture it; fetch with 'stoandl support --coredump' once it reconnects"
        } catch (e: Exception) {
            log.warn(e) { "createCoreDump failed" }
            "error:${e.message ?: "core-dump request failed"}"
        }
    }
}
