package de.yoxcu.stoandl.pebble

import io.github.oshai.kotlinlogging.KotlinLogging
import org.freedesktop.dbus.DBusPath
import org.freedesktop.dbus.annotations.DBusInterfaceName
import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import org.freedesktop.dbus.exceptions.DBusExecutionException
import org.freedesktop.dbus.interfaces.DBusInterface
import org.freedesktop.dbus.types.UInt16
import org.freedesktop.dbus.types.UInt32

private const val AGENT_PATH = "/io/stoandl/agent"
// DisplayYesNo => MITM pairing resolves to Numeric Comparison: the watch shows a 6-digit number and
// the user confirms it on the WATCH; the phone side (RequestConfirmation) is answered by `onConfirm`.
private const val AGENT_CAPABILITY = "DisplayYesNo"

@DBusInterfaceName("org.bluez.AgentManager1")
interface BluezAgentManager1 : DBusInterface {
    fun RegisterAgent(agent: DBusPath, capability: String)
    fun RequestDefaultAgent(agent: DBusPath)
    fun UnregisterAgent(agent: DBusPath)
}

@DBusInterfaceName("org.bluez.Agent1")
interface BluezAgent1 : DBusInterface {
    fun Release()
    fun RequestPinCode(device: DBusPath): String
    fun DisplayPinCode(device: DBusPath, pincode: String)
    fun RequestPasskey(device: DBusPath): UInt32
    fun DisplayPasskey(device: DBusPath, passkey: UInt32, entered: UInt16)
    fun RequestConfirmation(device: DBusPath, passkey: UInt32)
    fun RequestAuthorization(device: DBusPath)
    fun AuthorizeService(device: DBusPath, uuid: String)
    fun Cancel()
}

/**
 * Registers a headless BlueZ pairing agent so that BLE-Secure-Connections / MITM pairing
 * (required by newer Pebble firmware, e.g. Pebble 2 / Time 2) can complete without a desktop UI.
 *
 * Newer watches request Bonding+MITM+SC, which yields Numeric Comparison. Without an agent BlueZ
 * has nothing to answer the confirmation, so [io.rebble.libpebblecommon...]'s `Pair()` times out
 * ("No reply within specified time") even though the watch shows its pairing popup. This agent is
 * registered as the system default agent, so it serves `Device1.Pair()` calls made on any connection
 * — and also every pairing or service request a *remote* device starts. So it refuses by default:
 * every answer is a callback the daemon decides, and a missing callback rejects.
 *
 *  - Numeric Comparison goes to `onConfirm` with the device's object path and the code: true accepts
 *    (the method returns), false declines (we throw, which BlueZ treats as a rejected pairing). It may
 *    block for a user decision; the daemon lets only a Pebble pair and only during a pairing window,
 *    asks the user on a `stoandl watch pair`/`repair` or GUI window (the CLI accepts by itself with
 *    `--yes` or without a terminal), and accepts on a notification's re-pair window. With a Pebble the
 *    user also confirms the code on the watch, which is the MITM check; that check only exists when
 *    the other end is a Pebble, hence the device check.
 *  - Just Works (`RequestAuthorization`) and legacy PIN entry (`RequestPinCode`, answered "0000") go
 *    to `mayPair`, with the same rule.
 *  - `AuthorizeService` (a device that isn't Trusted opening a profile) goes to `mayUseService`: the
 *    daemon allows Pebbles only. Without that, a device that managed to bond could open e.g. HID.
 *  - Passkey Entry, where the phone would type the watch's passkey, can't be answered headlessly and
 *    is always refused.
 *
 * The display-only methods report their code via `onPairingCode` so the daemon can surface it.
 */
class BluezPairingAgent {
    private val log = KotlinLogging.logger {}
    private var conn: DBusConnection? = null
    @Volatile private var onPairingCode: ((String) -> Unit)? = null
    @Volatile private var onConfirm: ((device: String, code: String) -> Boolean)? = null
    @Volatile private var mayPair: ((device: String) -> Boolean)? = null
    @Volatile private var mayUseService: ((device: String, uuid: String) -> Boolean)? = null

    fun register(
        onPairingCode: ((String) -> Unit)? = null,
        onConfirm: ((device: String, code: String) -> Boolean)? = null,
        mayPair: ((device: String) -> Boolean)? = null,
        mayUseService: ((device: String, uuid: String) -> Boolean)? = null,
    ) {
        this.onPairingCode = onPairingCode
        this.onConfirm = onConfirm
        this.mayPair = mayPair
        this.mayUseService = mayUseService
        try {
            val c = DBusConnectionBuilder.forSystemBus().withShared(false).build()
            conn = c
            c.exportObject(AGENT_PATH, AgentImpl())
            val mgr = c.getRemoteObject("org.bluez", "/org/bluez", BluezAgentManager1::class.java)
            mgr.RegisterAgent(DBusPath(AGENT_PATH), AGENT_CAPABILITY)
            log.info { "BlueZ pairing agent registered ($AGENT_CAPABILITY) at $AGENT_PATH" }
            try {
                mgr.RequestDefaultAgent(DBusPath(AGENT_PATH))
                log.info { "BlueZ pairing agent set as default agent" }
            } catch (e: Exception) {
                // Non-fatal, but pairing initiated on another connection may use a different
                // default agent if one already exists.
                log.warn(e) { "Could not become default pairing agent (another agent may be registered)" }
            }
        } catch (e: Exception) {
            log.warn(e) { "Failed to register BlueZ pairing agent — MITM pairing will not complete" }
        }
    }

    fun unregister() {
        val c = conn ?: return
        try {
            c.getRemoteObject("org.bluez", "/org/bluez", BluezAgentManager1::class.java)
                .UnregisterAgent(DBusPath(AGENT_PATH))
        } catch (e: Exception) {
            log.warn(e) { "Failed to unregister BlueZ pairing agent" }
        } finally {
            c.disconnect()
            conn = null
        }
    }

    private inner class AgentImpl : BluezAgent1 {
        override fun isRemote() = false
        override fun getObjectPath() = AGENT_PATH

        override fun Release() {
            log.info { "Pairing agent released" }
        }

        // Returning normally = accept. Throwing a DBus error = reject. Every decision is the daemon's
        // (see the class doc); without a callback the answer is no.

        override fun RequestConfirmation(device: DBusPath, passkey: UInt32) {
            // Numeric Comparison (DisplayYesNo). onConfirm decides (and may block for a user answer);
            // returning normally accepts, throwing declines (BlueZ aborts the pairing).
            val code = "%06d".format(passkey.toLong())
            log.info { "RequestConfirmation($device) code=$code — deciding" }
            if (onConfirm?.invoke(device.path, code) != true) {
                log.info { "RequestConfirmation($device) code=$code — declined" }
                throw DBusExecutionException("Pairing declined")
            }
            log.info { "RequestConfirmation($device) code=$code — accepted" }
        }

        override fun RequestAuthorization(device: DBusPath) {
            // Just Works pairing started by the remote device.
            if (mayPair?.invoke(device.path) != true) {
                log.info { "RequestAuthorization($device) — refused" }
                throw DBusExecutionException("Pairing refused")
            }
            log.info { "RequestAuthorization($device) — accepted" }
        }

        override fun AuthorizeService(device: DBusPath, uuid: String) {
            if (mayUseService?.invoke(device.path, uuid) != true) {
                log.info { "AuthorizeService($device, $uuid) — refused" }
                throw DBusExecutionException("Service refused")
            }
            log.info { "AuthorizeService($device, $uuid) — accepted" }
        }

        override fun DisplayPasskey(device: DBusPath, passkey: UInt32, entered: UInt16) {
            val code = "%06d".format(passkey.toLong())
            log.info { "DisplayPasskey($device) code=$code entered=$entered — enter this on the watch" }
            onPairingCode?.invoke(code)
        }

        override fun DisplayPinCode(device: DBusPath, pincode: String) {
            log.info { "DisplayPinCode($device) pin=$pincode — enter this on the watch" }
        }

        override fun RequestPasskey(device: DBusPath): UInt32 {
            // Passkey Entry where the WATCH displays and the phone must type it — not answerable
            // headlessly (any fixed answer would pair a device that shows that passkey). Log loudly so
            // we know this method was negotiated and can revisit.
            log.warn { "RequestPasskey($device) called — cannot supply a watch-displayed passkey headlessly; refusing" }
            throw DBusExecutionException("Passkey entry not supported")
        }

        override fun RequestPinCode(device: DBusPath): String {
            // Legacy (pre-SSP) PIN entry: only "0000" can be offered headlessly.
            if (mayPair?.invoke(device.path) != true) {
                log.info { "RequestPinCode($device) — refused" }
                throw DBusExecutionException("Pairing refused")
            }
            log.warn { "RequestPinCode($device) called — legacy PIN entry not answerable headlessly; returning \"0000\"" }
            return "0000"
        }

        override fun Cancel() {
            log.info { "Pairing cancelled by BlueZ" }
        }
    }
}
