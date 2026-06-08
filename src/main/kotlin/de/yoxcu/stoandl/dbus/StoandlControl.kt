package de.yoxcu.stoandl.dbus

import org.freedesktop.dbus.annotations.DBusInterfaceName
import org.freedesktop.dbus.interfaces.DBusInterface

const val STOANDL_BUS_NAME = "de.yoxcu.stoandl"
const val STOANDL_OBJECT_PATH = "/de/yoxcu/stoandl"

@DBusInterfaceName("de.yoxcu.stoandl.Control")
interface StoandlControl : DBusInterface {
    /** The version of the running daemon (from `git describe` at build time). */
    fun Version(): String

    /** Install a `.pbw` from [path] onto the watch. Returns a status-prefixed string
     *  (`ok:<msg>` / `error:<msg>` / `notready:<msg>`) so the real failure reason — e.g. an
     *  invalid .pbw — reaches the CLI instead of a bare boolean. */
    fun SideloadApp(path: String): String

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

    /** Force an immediate weather fetch for the configured locations and push it to the watch.
     *  Same status-prefixed return convention as [LaunchApp]. Returns `error:` if weather sync is
     *  not enabled (no `weather.locations` configured). */
    fun SyncWeather(): String

    /** List the watch's advanced settings (WatchPrefs). Each entry is a tab-separated record:
     *  `id \t type \t current \t default \t allowed \t flags \t name \t description`. */
    fun ListWatchPrefs(): List<String>

    /** Set a watch setting [id] to [value] (parsed per the pref's type). Status-prefixed return
     *  (`ok:`/`error:`/`notready:`) as for [LaunchApp]. */
    fun SetWatchPref(id: String, value: String): String

    /** Open a ~2-minute pairing window and start monitoring for a connection. Returns
     *  `ok:` immediately; poll [PairStatus] for the outcome. */
    fun Pair(): String

    /** Return the current pairing status: `pending:`, `ok:<msg>`, `error:<msg>`, or
     *  `timeout:<msg>`. Returns `error:No pairing in progress` if [Pair] was never called. */
    fun PairStatus(): String
}
