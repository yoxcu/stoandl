package de.yoxcu.stoandl.dbus

import de.yoxcu.stoandl.util.openSessionBus
import io.github.oshai.kotlinlogging.KotlinLogging
import org.freedesktop.dbus.annotations.DBusInterfaceName
import org.freedesktop.dbus.interfaces.DBusInterface
import org.freedesktop.dbus.messages.DBusSignal
import org.freedesktop.dbus.types.UInt32
import org.freedesktop.dbus.types.Variant

private val notifLog = KotlinLogging.logger {}

/**
 * App name for stoandl desktop notifications that must NOT be bridged to the watch. Used when a matching
 * *direct* watch notification is sent separately (e.g. firmware: a watch notif with a working Update
 * button + a desktop notif with its own button) — tagging the desktop one with this app name makes the
 * passive monitor skip it, so the alert shows once on each surface instead of twice on the watch.
 */
const val STOANDL_DESKTOP_ONLY_APP = "stoandl-desktop"

/**
 * Post a desktop notification on the session bus (best-effort). stoandl's passive notification monitor
 * bridges it to the watch automatically, so this is how a stoandl-originated alert reaches BOTH the
 * phone/desktop and the watch — *without* also pushing a direct watch notification (which would show up
 * twice). Returns the notification id (0 on failure / no bus).
 */
fun sendDesktopNotification(
    summary: String,
    body: String,
    appName: String = "stoandl",
    icon: String = "phone",
    timeoutMs: Int = 15_000,
): UInt32 = try {
    val conn = openSessionBus()
    try {
        conn.getRemoteObject(
            "org.freedesktop.Notifications", "/org/freedesktop/Notifications",
            FreedesktopNotifications::class.java,
        ).Notify(appName, UInt32(0), icon, summary, body, emptyList(), emptyMap(), timeoutMs)
    } finally {
        conn.disconnect()
    }
} catch (e: Exception) {
    notifLog.warn { "sendDesktopNotification failed: ${e.message}" }
    UInt32(0)
}

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
