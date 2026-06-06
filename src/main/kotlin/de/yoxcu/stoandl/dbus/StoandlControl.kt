package de.yoxcu.stoandl.dbus

import org.freedesktop.dbus.annotations.DBusInterfaceName
import org.freedesktop.dbus.interfaces.DBusInterface

const val STOANDL_BUS_NAME = "de.yoxcu.stoandl"
const val STOANDL_OBJECT_PATH = "/de/yoxcu/stoandl"

@DBusInterfaceName("de.yoxcu.stoandl.Control")
interface StoandlControl : DBusInterface {
    fun SideloadApp(path: String): Boolean
    /** Returns the configuration URL for the running PKJS app matching [app] (name or UUID).
     *  Returns empty string if no matching app is running or it has no config page. */
    fun OpenConfig(app: String): String
    /** Signal the running PKJS app that the config webview closed with [data]. */
    fun WebviewClose(data: String)
}
