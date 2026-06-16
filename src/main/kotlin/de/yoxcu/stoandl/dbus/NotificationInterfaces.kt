package de.yoxcu.stoandl.dbus

import org.freedesktop.dbus.annotations.DBusInterfaceName
import org.freedesktop.dbus.interfaces.DBusInterface
import org.freedesktop.dbus.messages.DBusSignal
import org.freedesktop.dbus.types.UInt32
import org.freedesktop.dbus.types.Variant

@DBusInterfaceName("org.freedesktop.Notifications")
interface FreedesktopNotifications : DBusInterface {
    fun Notify(
        app_name: String,
        replaces_id: UInt32,
        app_icon: String,
        summary: String,
        body: String,
        actions: List<String>,
        hints: Map<String, Variant<*>>,
        expire_timeout: Int,
    ): UInt32

    fun CloseNotification(id: UInt32)
    fun GetCapabilities(): List<String>
    fun GetServerInformation(): Array<String>

    class ActionInvoked(
        path: String,
        id: UInt32,
        action_key: String,
    ) : DBusSignal(path, id, action_key) {
        val id: UInt32 = id
        val action_key: String = action_key
    }
}
