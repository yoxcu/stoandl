package de.yoxcu.stoandl.dnd

import de.yoxcu.stoandl.util.runCommand
import de.yoxcu.stoandl.util.unwrapVariant
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.freedesktop.dbus.annotations.DBusInterfaceName
import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import org.freedesktop.dbus.interfaces.DBusInterface
import org.freedesktop.dbus.interfaces.Properties
import org.freedesktop.dbus.matchrules.DBusMatchRuleBuilder
import org.freedesktop.dbus.messages.DBusSignal
import org.freedesktop.dbus.types.UInt32
import org.freedesktop.dbus.types.Variant

private val log = KotlinLogging.logger {}

/**
 * The desktop's "Do Not Disturb" state, abstracted over the two backends Linux desktops actually
 * expose — there is no single cross-DE mechanism:
 *
 *  - **GNOME / Phosh** drive DND through the GSettings key
 *    `org.gnome.desktop.notifications show-banners` (`false` = DND on; the key is inverted). It carries
 *    no D-Bus property, so we read/write via `gsettings` and watch live changes with `gsettings
 *    monitor` (a long-running subprocess that prints a line per change). [GnomeDndBackend]
 *  - **KDE / Plasma (incl. Plasma Mobile)** expose the freedesktop `Inhibited` boolean property on
 *    `org.freedesktop.Notifications`, settable via `Inhibit()`/`UnInhibit()`. gnome-shell does **not**
 *    implement this property — that absence is exactly how we tell the two DEs apart. [KdeDndBackend]
 *
 * [DndSync] mirrors this to/from the watch's Quiet Time ([io.rebble.libpebblecommon.database.entity.BoolWatchPref.QuietTimeManuallyEnabled]).
 */
interface HostDndBackend {
    /** Human-readable backend name for logging (e.g. "GNOME", "KDE/Plasma"). */
    val name: String

    /** Current host DND state, or null if it can't be read (backend not actually present). */
    fun current(): Boolean?

    /** Call [onChange] whenever the host DND state changes. At most one observer per backend. */
    fun observe(onChange: (Boolean) -> Unit)

    /** Push [dnd] to the host. */
    fun set(dnd: Boolean)

    /** Release resources (kill the monitor subprocess / drop the bus connection). */
    fun close()
}

/**
 * Pick whichever host DND backend is actually live on this session, or null if neither is. KDE is
 * probed first (its `Inhibited` property is specific — it throws on gnome-shell), then GNOME (whose
 * GSettings schema may exist even where it isn't honoured, so it's the fallback).
 */
fun detectHostDnd(scope: CoroutineScope): HostDndBackend? {
    KdeDndBackend().let { kde ->
        if (kde.current() != null) return kde
        kde.close()
    }
    GnomeDndBackend(scope).let { gnome ->
        if (gnome.current() != null) return gnome
        gnome.close()
    }
    return null
}

// ---- GNOME / Phosh -------------------------------------------------------------------------------

private const val GNOME_SCHEMA = "org.gnome.desktop.notifications"
private const val GNOME_KEY = "show-banners"

/** DND via the inverted `org.gnome.desktop.notifications show-banners` GSettings key. */
class GnomeDndBackend(private val scope: CoroutineScope) : HostDndBackend {
    override val name = "GNOME"
    @Volatile private var monitor: Process? = null

    // show-banners=false means DND is on (banners hidden).
    override fun current(): Boolean? {
        val out = runCommand(listOf("gsettings", "get", GNOME_SCHEMA, GNOME_KEY), 5) ?: return null
        return when (out.trim()) {
            "true" -> false
            "false" -> true
            else -> null
        }
    }

    override fun observe(onChange: (Boolean) -> Unit) {
        scope.launch(Dispatchers.IO) {
            try {
                val p = ProcessBuilder("gsettings", "monitor", GNOME_SCHEMA, GNOME_KEY).start()
                monitor = p
                // Each change prints a line like "show-banners: false".
                p.inputStream.bufferedReader().forEachLine { line ->
                    val v = line.substringAfter(':', "").trim()
                    when (v) {
                        "true" -> onChange(false)
                        "false" -> onChange(true)
                    }
                }
            } catch (e: Exception) {
                log.warn { "gsettings monitor for DND failed: ${e.message}" }
            }
        }
    }

    override fun set(dnd: Boolean) {
        // show-banners is inverted: DND on → banners off.
        runCommand(listOf("gsettings", "set", GNOME_SCHEMA, GNOME_KEY, (!dnd).toString()), 5)
    }

    override fun close() {
        monitor?.destroy()
        monitor = null
    }
}

// ---- KDE / Plasma --------------------------------------------------------------------------------

private const val NOTIF_NAME = "org.freedesktop.Notifications"
private const val NOTIF_PATH = "/org/freedesktop/Notifications"
private const val NOTIF_IFACE = "org.freedesktop.Notifications"

/** The KDE-specific inhibition control on `org.freedesktop.Notifications` (absent on gnome-shell).
 *  Kept separate from [de.yoxcu.stoandl.dbus.FreedesktopNotifications] so the notification-monitor
 *  fallback object isn't forced to implement methods it never serves. */
@DBusInterfaceName("org.freedesktop.Notifications")
private interface KdeNotificationsInhibit : DBusInterface {
    @Suppress("FunctionName")
    fun Inhibit(desktop_entry: String, reason: String, hints: Map<String, Variant<*>>): UInt32
    @Suppress("FunctionName")
    fun UnInhibit(cookie: UInt32)
}

/** DND via the freedesktop `Inhibited` property + `Inhibit()`/`UnInhibit()` on KDE's notification server. */
class KdeDndBackend : HostDndBackend {
    override val name = "KDE/Plasma"
    // Held for the backend lifetime: dropping it tears down the signal handler AND (per the spec)
    // auto-releases any inhibition cookie we hold.
    @Volatile private var conn: DBusConnection? = null
    // Non-null while we are actively inhibiting (DND pushed on by the watch).
    @Volatile private var cookie: UInt32? = null

    private fun connection(): DBusConnection? {
        conn?.let { return it }
        return try {
            DBusConnectionBuilder.forSessionBus().withShared(false).build().also { conn = it }
        } catch (e: Exception) {
            log.debug { "No session bus for KDE DND backend: ${e.message}" }
            null
        }
    }

    override fun current(): Boolean? {
        val c = connection() ?: return null
        return try {
            val props = c.getRemoteObject(NOTIF_NAME, NOTIF_PATH, Properties::class.java)
            unwrapVariant(props.Get<Any>(NOTIF_IFACE, "Inhibited")) as? Boolean
        } catch (e: Exception) {
            // gnome-shell (and anything that isn't KDE's server) has no Inhibited property → not KDE.
            log.debug { "Inhibited property unavailable (not a KDE notification server): ${e.message}" }
            null
        }
    }

    override fun observe(onChange: (Boolean) -> Unit) {
        val c = connection() ?: return
        try {
            val rule = DBusMatchRuleBuilder.create()
                .withType("signal")
                .withInterface("org.freedesktop.DBus.Properties")
                .withMember("PropertiesChanged")
                .withPath(NOTIF_PATH)
                .build()
            c.addGenericSigHandler(rule) { msg: DBusSignal ->
                try {
                    val params = msg.getParameters() ?: return@addGenericSigHandler
                    val ifaceName = params.getOrNull(0) as? String
                    if (ifaceName != null && ifaceName != NOTIF_IFACE) return@addGenericSigHandler
                    @Suppress("UNCHECKED_CAST")
                    val changed = params.getOrNull(1) as? Map<String, *>
                    val raw = changed?.get("Inhibited")
                    val v = unwrapVariant(raw) as? Boolean
                    if (v != null) {
                        onChange(v)
                    } else {
                        // Some servers invalidate rather than emit the new value — re-read it.
                        @Suppress("UNCHECKED_CAST")
                        val invalidated = params.getOrNull(2) as? List<String>
                        if (invalidated?.contains("Inhibited") == true) current()?.let(onChange)
                    }
                } catch (e: Exception) {
                    log.debug { "Notifications PropertiesChanged parse error: ${e.message}" }
                }
            }
        } catch (e: Exception) {
            log.debug { "Failed to subscribe to KDE Inhibited changes: ${e.message}" }
        }
    }

    override fun set(dnd: Boolean) {
        val c = connection() ?: return
        try {
            val inhibit = c.getRemoteObject(NOTIF_NAME, NOTIF_PATH, KdeNotificationsInhibit::class.java)
            if (dnd) {
                if (cookie == null) cookie = inhibit.Inhibit("stoandl", "Quiet Time (Pebble watch)", emptyMap())
            } else {
                cookie?.let { inhibit.UnInhibit(it); cookie = null }
            }
        } catch (e: Exception) {
            log.warn { "Failed to set KDE DND=$dnd: ${e.message}" }
        }
    }

    override fun close() {
        try {
            cookie?.let { c ->
                conn?.getRemoteObject(NOTIF_NAME, NOTIF_PATH, KdeNotificationsInhibit::class.java)?.UnInhibit(c)
            }
        } catch (_: Exception) {
        }
        cookie = null
        conn?.disconnect()
        conn = null
    }
}
