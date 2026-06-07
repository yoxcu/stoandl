package de.yoxcu.stoandl.dbus

import org.freedesktop.dbus.annotations.DBusInterfaceName
import org.freedesktop.dbus.interfaces.DBusInterface

const val STOANDL_BUS_NAME = "de.yoxcu.stoandl"
const val STOANDL_OBJECT_PATH = "/de/yoxcu/stoandl"

@DBusInterfaceName("de.yoxcu.stoandl.Control")
interface StoandlControl : DBusInterface {
    fun SideloadApp(path: String): Boolean

    /** List the apps in the watch locker. Each entry is a tab-separated record:
     *  `uuid \t type \t order \t flags \t title \t developer`, where flags is a comma-joined
     *  subset of {active, sideloaded, config, system}. Returns an empty list if libPebble
     *  is not ready. */
    fun ListApps(): List<String>

    /** Launch the app/watchface matching [query] (UUID or case-insensitive name). Returns a
     *  status-prefixed string: `ok:<msg>`, `notfound:<msg>`, `ambiguous:<msg>`,
     *  `notready:<msg>` or `error:<msg>`. */
    fun LaunchApp(query: String): String

    /** Uninstall the app/watchface matching [query] (UUID or case-insensitive name) from the
     *  locker. Same status-prefixed return convention as [LaunchApp]. System apps are refused. */
    fun RemoveApp(query: String): String
    /** Returns the configuration URL for the running PKJS app matching [app] (name or UUID).
     *  Returns empty string if no matching app is running or it has no config page. */
    fun OpenConfig(app: String): String
    /** Signal the running PKJS app that the config webview closed with [data]. */
    fun WebviewClose(data: String)

    /** Debug: inject a synthetic incoming (ringing) call so the watch shows the native call
     *  screen. The watch's Answer button transitions it to an active call; Decline/Hangup ends
     *  it. Lets the call path be exercised end-to-end without a real telephony source.
     *  Returns false if libPebble is not ready. */
    fun FakeCallRing(name: String, number: String): Boolean

    /** Debug: clear the current synthetic call (simulates the remote party hanging up). */
    fun FakeCallEnd(): Boolean
}
