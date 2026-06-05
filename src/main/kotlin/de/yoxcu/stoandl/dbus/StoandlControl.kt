package de.yoxcu.stoandl.dbus

import org.freedesktop.dbus.annotations.DBusInterfaceName
import org.freedesktop.dbus.interfaces.DBusInterface

const val STOANDL_BUS_NAME = "de.yoxcu.stoandl"
const val STOANDL_OBJECT_PATH = "/de/yoxcu/stoandl"

@DBusInterfaceName("de.yoxcu.stoandl.Control")
interface StoandlControl : DBusInterface {
    fun SideloadApp(path: String): Boolean
}
