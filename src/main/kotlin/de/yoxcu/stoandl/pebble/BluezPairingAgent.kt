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
// DisplayYesNo => MITM pairing resolves to Numeric Comparison: the watch shows a 6-digit number,
// the user confirms it on the WATCH, and we auto-accept on the phone side (RequestConfirmation).
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
 * registered as the system default agent (so it serves `Device1.Pair()` calls made on any
 * connection) and auto-accepts; the user only needs to confirm the matching code on the watch.
 *
 * Confirmation (Numeric Comparison) is routed through [register]'s `onConfirm` callback: it returns
 * true to accept (the method returns) or false to decline (we throw, which BlueZ treats as a rejected
 * pairing). The callback may block to wait for a user decision. When `onConfirm` is null we auto-accept.
 * The display-only methods report their code via `onPairingCode` so the daemon can surface it.
 */
class BluezPairingAgent {
    private val log = KotlinLogging.logger {}
    private var conn: DBusConnection? = null
    @Volatile private var onPairingCode: ((String) -> Unit)? = null
    @Volatile private var onConfirm: ((String) -> Boolean)? = null

    fun register(
        onPairingCode: ((String) -> Unit)? = null,
        onConfirm: ((String) -> Boolean)? = null,
    ) {
        this.onPairingCode = onPairingCode
        this.onConfirm = onConfirm
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

        // Returning normally = accept. Throwing a DBus error = reject. We auto-accept everything.

        override fun RequestConfirmation(device: DBusPath, passkey: UInt32) {
            // Numeric Comparison (DisplayYesNo). onConfirm decides (and may block for a user answer);
            // returning normally accepts, throwing declines (BlueZ aborts the pairing).
            val code = "%06d".format(passkey.toLong())
            val confirm = onConfirm
            if (confirm == null) {
                log.info { "RequestConfirmation($device) code=$code — auto-accepting (no confirmer wired)" }
                return
            }
            log.info { "RequestConfirmation($device) code=$code — deciding" }
            if (!confirm(code)) {
                log.info { "RequestConfirmation($device) code=$code — declined" }
                throw DBusExecutionException("Pairing declined")
            }
            log.info { "RequestConfirmation($device) code=$code — accepted" }
        }

        override fun RequestAuthorization(device: DBusPath) {
            log.info { "RequestAuthorization($device) — auto-accepting" }
        }

        override fun AuthorizeService(device: DBusPath, uuid: String) {
            log.info { "AuthorizeService($device, $uuid) — auto-accepting" }
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
            // headlessly. Log loudly so we know this method was negotiated and can revisit.
            log.warn { "RequestPasskey($device) called — cannot supply a watch-displayed passkey headlessly; returning 0 (pairing will likely fail)" }
            return UInt32(0)
        }

        override fun RequestPinCode(device: DBusPath): String {
            log.warn { "RequestPinCode($device) called — legacy PIN entry not answerable headlessly; returning \"0000\"" }
            return "0000"
        }

        override fun Cancel() {
            log.info { "Pairing cancelled by BlueZ" }
        }
    }
}
