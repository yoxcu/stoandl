package de.yoxcu.stoandl.power

import de.yoxcu.stoandl.util.openPrivateSystemBus
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeoutOrNull
import org.freedesktop.dbus.DBusPath
import org.freedesktop.dbus.Struct
import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.interfaces.Properties
import org.freedesktop.dbus.types.Variant
import java.io.File

private val log = KotlinLogging.logger {}

private const val LOGIN1 = "org.freedesktop.login1"
private const val SESSION_IFACE = "org.freedesktop.login1.Session"
private const val USER_IFACE = "org.freedesktop.login1.User"
/** The caller's session, else (a user service has none) the user's display session. */
private const val AUTO_SESSION = "/org/freedesktop/login1/session/auto"
private const val SESSION_PATH_PREFIX = "/org/freedesktop/login1/session/"
/** How long a notification waits for logind before it is forwarded anyway. */
private const val IN_USE_TIMEOUT_MS = 1_000L

/** The logind session the user is looking at: its `Type` is a graphical one, it's the `Active` session
 *  on its seat, and its `LockedHint` (set by the screen locker: KScreenLocker, GNOME Shell). */
data class LoginSession(val graphical: Boolean, val active: Boolean, val locked: Boolean)

/**
 * Someone is using this device: the display is not known to be off ([ScreenState]) and the user's
 * graphical session is active and unlocked. Anything unknown (no logind session, a text or remote session,
 * logind unreachable) counts as *not in use*, so `notification.only_when_locked` never holds a
 * notification back on a guess.
 */
internal fun deviceInUse(screenOn: Boolean?, session: LoginSession?): Boolean =
    screenOn != false && session != null && session.graphical && session.active && !session.locked

/** The session object path out of logind's `User.Display` property, a `(so)` struct, however dbus-java
 *  hands it over; null for the empty `/` path (no display session). */
internal fun displaySessionPath(display: Any?): String? {
    val parts: List<Any?> = when (val v = if (display is Variant<*>) display.value else display) {
        is Struct -> v.parameters.toList()
        is Array<*> -> v.toList()
        is List<*> -> v
        else -> listOf(v)
    }
    return parts.firstNotNullOfOrNull { p ->
        (if (p is DBusPath) p.path else p as? String)?.takeIf { it.startsWith(SESSION_PATH_PREFIX) }
    }
}

/** The fields of a logind `Session` properties map that [deviceInUse] needs; null when it isn't one. */
internal fun loginSessionOf(props: Map<String, Any?>): LoginSession? {
    fun prop(name: String): Any? = props[name].let { if (it is Variant<*>) it.value else it }
    val locked = prop("LockedHint") as? Boolean ?: return null
    val type = prop("Type") as? String ?: ""
    return LoginSession(
        graphical = type in GRAPHICAL_TYPES,
        active = prop("Active") as? Boolean ?: true,
        locked = locked,
    )
}

private val GRAPHICAL_TYPES = setOf("x11", "wayland", "mir")

/**
 * Reads the user's graphical session from logind (system bus). Used by `notification.only_when_locked`,
 * which mirrors the official app's "Always send notifications" switched off: a notification is held back
 * while the user is at the phone or desktop. Read per notification, only while that key is on.
 */
object SessionLock {
    @Volatile private var conn: DBusConnection? = null
    @Volatile private var unknownLogged = false

    /** [deviceInUse] for the current screen and session, bounded by [IN_USE_TIMEOUT_MS] (a slow logind
     *  never delays a notification by more than that, and then it's forwarded). */
    suspend fun inUse(): Boolean = withTimeoutOrNull(IN_USE_TIMEOUT_MS) {
        runInterruptible(Dispatchers.IO) { deviceInUse(ScreenState.isOn(), read()) }
    } ?: false

    /** The user's graphical session, or null when there is none or logind can't be asked. */
    fun read(): LoginSession? {
        val c = connection() ?: return unknown("no system bus")
        return try {
            val session = sessionProps(c, AUTO_SESSION)
                ?: displaySession(c)?.let { sessionProps(c, it) }
                ?: return unknown("no display session")
            unknownLogged = false
            loginSessionOf(session) ?: unknown("session has no LockedHint")
        } catch (e: Exception) {
            // A broken connection (system bus restarted) is rebuilt on the next read.
            conn = null
            runCatching { c.disconnect() }
            unknown("logind: ${e.message}")
        }
    }

    private fun sessionProps(c: DBusConnection, path: String): Map<String, Any?>? = try {
        c.getRemoteObject(LOGIN1, path, Properties::class.java).GetAll(SESSION_IFACE)
    } catch (e: Exception) {
        log.debug { "logind session $path unavailable: ${e.message}" }
        null
    }

    /** elogind and older systemd have no `session/auto`: ask the user object for its display session. */
    private fun displaySession(c: DBusConnection): String? {
        val uid = uid() ?: return null
        val user = c.getRemoteObject(LOGIN1, "/org/freedesktop/login1/user/_$uid", Properties::class.java)
        return displaySessionPath(user.GetAll(USER_IFACE)["Display"])
    }

    private fun uid(): Long? = runCatching {
        File("/proc/self/status").useLines { lines ->
            lines.first { it.startsWith("Uid:") }.split(Regex("\\s+"))[1].toLong()
        }
    }.getOrNull()

    private fun connection(): DBusConnection? {
        conn?.let { return it }
        return try {
            openPrivateSystemBus().also { conn = it }
        } catch (e: Exception) {
            log.debug { "No system bus for the session lock state: ${e.message}" }
            null
        }
    }

    private fun unknown(why: String): LoginSession? {
        if (!unknownLogged) {
            unknownLogged = true
            log.info { "Can't tell whether the session is locked ($why); notifications are forwarded" }
        }
        return null
    }
}
