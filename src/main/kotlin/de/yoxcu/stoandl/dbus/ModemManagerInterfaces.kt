package de.yoxcu.stoandl.dbus

import org.freedesktop.dbus.DBusPath
import org.freedesktop.dbus.annotations.DBusInterfaceName
import org.freedesktop.dbus.interfaces.DBusInterface
import org.freedesktop.dbus.messages.DBusSignal
import org.freedesktop.dbus.types.UInt32

const val MM_BUS_NAME = "org.freedesktop.ModemManager1"
const val MM_OBJECT_PATH = "/org/freedesktop/ModemManager1"
const val MM_CALL_IFACE = "org.freedesktop.ModemManager1.Call"
const val MM_VOICE_IFACE = "org.freedesktop.ModemManager1.Modem.Voice"

/** ModemManager call states (MMCallState). */
object MMCallState {
    const val UNKNOWN = 0
    const val DIALING = 1
    const val RINGING_OUT = 2
    const val RINGING_IN = 3
    const val ACTIVE = 4
    const val HELD = 5
    const val WAITING = 6
    const val TERMINATED = 7
}

/** org.freedesktop.ModemManager1.Modem.Voice — one per modem; emits call lifecycle signals. */
@DBusInterfaceName("org.freedesktop.ModemManager1.Modem.Voice")
interface ModemVoice : DBusInterface {
    fun ListCalls(): List<DBusPath>

    class CallAdded(path: String, call: DBusPath) : DBusSignal(path, call) {
        val call: DBusPath = call
    }

    class CallDeleted(path: String, call: DBusPath) : DBusSignal(path, call) {
        val call: DBusPath = call
    }
}

/** org.freedesktop.ModemManager1.Call — one per call; Accept/Hangup drive the modem,
 *  StateChanged reports transitions (DIALING → RINGING_IN → ACTIVE → TERMINATED). */
@DBusInterfaceName("org.freedesktop.ModemManager1.Call")
interface ModemCall : DBusInterface {
    fun Accept()
    fun Hangup()

    // `reason` stays in the constructor (it defines the signal's wire signature for dbus-java to
    // unmarshal) but isn't exposed — nothing reads the disconnect reason.
    class StateChanged(path: String, old: Int, new: Int, reason: UInt32) : DBusSignal(path, old, new, reason) {
        val oldState: Int = old
        val newState: Int = new
    }
}
