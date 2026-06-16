package de.yoxcu.stoandl.developer

import de.yoxcu.stoandl.util.connectedDevice
import io.github.oshai.kotlinlogging.KotlinLogging
import io.rebble.libpebblecommon.connection.ConnectedPebbleDevice
import io.rebble.libpebblecommon.connection.LibPebble
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicReference

private val log = KotlinLogging.logger {}

/**
 * Bridge libpebble3's developer connection so the Pebble SDK (`pebble install --phone <ip>` /
 * CloudPebble's "phone" mode) can install and live-debug watchapps through stoandl over BLE — the
 * same thing the official phone app's developer connection does.
 *
 * libpebble3 already implements the whole feature: every [ConnectedPebbleDevice] is a
 * `ConnectedPebble.DevConnection`, and `startDevConnection()` spins up a Ktor WebSocket server that
 * relays raw Pebble-protocol frames to/from the watch and handles `.pbw` installs + PKJS log
 * streaming. The transport picks the **LAN server (port 9000)** rather than the CloudPebble proxy
 * because stoandl pins `WatchConfig.lanDevConnection = true` (stoandl has no Rebble token, so the
 * proxy is useless). So this is pure wiring: drive the start/stop suspend calls and read the
 * `devConnectionActive` state.
 *
 * Each method returns a status-prefixed string mirroring the other control classes: `ok:<...>`,
 * `notready:<msg>` (no watch), or `error:<msg>`.
 *
 * Security note: libpebble3's LAN server binds `0.0.0.0:9000` (all interfaces) with no auth, so
 * anyone on the network can install apps and relay protocol traffic to the watch while it's running.
 * It's therefore off by default and started explicitly (`stoandl developer start`, or the opt-in
 * `developer.autostart` config); the CLI prints the warning.
 */
class DeveloperControl(
    private val libPebbleRef: AtomicReference<LibPebble?>,
) {
    private fun device(): ConnectedPebbleDevice? = libPebbleRef.connectedDevice()

    /**
     * Start the LAN developer-connection server (port 9000). The server lives in the watch's
     * per-connection scope, so it dies on disconnect — `developer.autostart` re-arms it on every
     * reconnect. Returns `ok:9000`, `notready:<msg>` (no watch), or `error:<msg>`.
     */
    fun start(): String {
        val dev = device() ?: return "notready:No watch connected"
        return try {
            runBlocking { dev.startDevConnection() }
            log.info { "Developer connection started (LAN WebSocket server on 0.0.0.0:9000)" }
            "ok:9000"
        } catch (e: Exception) {
            log.warn(e) { "startDevConnection failed" }
            "error:${e.message ?: "failed to start developer connection"}"
        }
    }

    /** Stop the developer-connection server. Returns `ok:`, `notready:<msg>`, or `error:<msg>`. */
    fun stop(): String {
        val dev = device() ?: return "notready:No watch connected"
        return try {
            runBlocking { dev.stopDevConnection() }
            log.info { "Developer connection stopped" }
            "ok:"
        } catch (e: Exception) {
            log.warn(e) { "stopDevConnection failed" }
            "error:${e.message ?: "failed to stop developer connection"}"
        }
    }

    /** Report whether the server is running. Returns `ok:active`/`ok:inactive` or `notready:<msg>`. */
    fun status(): String {
        val dev = device() ?: return "notready:No watch connected"
        return "ok:${if (dev.devConnectionActive.value) "active" else "inactive"}"
    }
}
