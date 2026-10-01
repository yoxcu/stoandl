package de.yoxcu.stoandl.dbus

import de.yoxcu.stoandl.util.openSessionBus
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.freedesktop.dbus.annotations.DBusInterfaceName
import org.freedesktop.dbus.connections.base.AbstractConnectionBase
import org.freedesktop.dbus.connections.transports.AbstractTransport
import org.freedesktop.dbus.connections.transports.TransportConnection
import org.freedesktop.dbus.interfaces.DBusInterface
import org.freedesktop.dbus.messages.DBusSignal
import org.freedesktop.dbus.messages.Message
import org.freedesktop.dbus.messages.MethodCall
import org.freedesktop.dbus.messages.MethodReturn
import org.freedesktop.dbus.spi.message.IMessageReader
import org.freedesktop.dbus.spi.message.IMessageWriter
import org.freedesktop.dbus.types.UInt32
import org.freedesktop.dbus.types.Variant
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

private val log = KotlinLogging.logger {}

private const val NOTIFICATIONS_OBJECT_PATH = "/org/freedesktop/Notifications"
private const val NOTIFICATIONS_IFACE = "org.freedesktop.Notifications"
private const val NOTIFY_MEMBER = "Notify"
private const val PORTAL_IMPL_IFACE = "org.freedesktop.impl.portal.Notification"
private const val GTK_IFACE = "org.gtk.Notifications"
private const val DBUS_NAME = "org.freedesktop.DBus"

/** Bound on Notify calls awaiting their reply (a reply that never comes, e.g. across an owner change). */
private const val MAX_PENDING = 256
private const val PENDING_MAX_AGE_MS = 30_000L

@DBusInterfaceName("org.freedesktop.DBus.Monitoring")
private interface DBusMonitoring : DBusInterface {
    fun BecomeMonitor(rules: Array<String>, flags: UInt32)
}

/** Which interface a notification was posted on — decides how a wrist action is delivered back. */
enum class NotifSource {
    /** `org.freedesktop.Notifications.Notify`: [IncomingNotification.id] is the server-assigned id. */
    FDO,
    /** `org.freedesktop.impl.portal.Notification.AddNotification` (sandboxed apps). Plasma >= 6.7 draws
     *  these inside plasmashell (`plasmanotify`) and never calls `Notify`, so this is the only place they
     *  are visible. */
    PORTAL,
    /** `org.gtk.Notifications.AddNotification` (GApplication apps and xdg-desktop-portal-gnome on GNOME). */
    GTK,
}

data class IncomingNotification(
    /** FDO: the id the notification server assigned. 0 for PORTAL/GTK (they use [appId] + [portalId]). */
    val id: UInt32,
    val appName: String,
    val summary: String,
    val body: String,
    val source: NotifSource = NotifSource.FDO,
    /** FDO: unique bus name of the server that assigned [id] (null if unresolved). */
    val owner: String? = null,
    /** PORTAL/GTK: the app id; FDO: the `desktop-entry` hint, if any. */
    val appId: String? = null,
    /** PORTAL/GTK: the app's own notification id. */
    val portalId: String? = null,
    /** PORTAL: the bus name the portal frontend addressed (the backend that shows it). */
    val backend: String? = null,
    /** FDO: the notification's actions as (key, label), in order. */
    val actions: List<Pair<String, String>> = emptyList(),
    /** FDO: the `transient` hint — a passing notice the server keeps out of its history. */
    val transient: Boolean = false,
)

/** What the passive monitor reports. */
sealed interface NotificationEvent {
    data class Posted(val notification: IncomingNotification) : NotificationEvent
    /** The notification server closed FDO notification [id] ([reason] per the spec: 1 expired, 2 dismissed,
     *  3 closed by CloseNotification, 4 undefined). */
    data class Closed(val owner: String?, val id: UInt32, val reason: UInt32) : NotificationEvent
    /** An app withdrew a PORTAL/GTK notification. */
    data class Removed(val source: NotifSource, val appId: String, val portalId: String) : NotificationEvent
    /** org.freedesktop.Notifications has a new owner (or none): every FDO id seen so far is stale. Also
     *  sent with the current owner each time the monitor (re)connects. */
    data class OwnerChanged(val owner: String?) : NotificationEvent
}

private data class PendingNotify(
    val appName: String,
    val summary: String,
    val body: String,
    val desktopEntry: String?,
    val actions: List<Pair<String, String>>,
    val transient: Boolean,
    val atMs: Long,
)

/** Unwrap D-Bus [Variant]s (possibly nested) to their value. */
internal fun unwrapVariant(v: Any?): Any? {
    var x = v
    while (x is Variant<*>) x = x.getValue()
    return x
}

/** The `as` actions argument of Notify (key, label, key, label, …) as pairs. dbus-java hands `as` over as a
 *  List or an array depending on the path; both are accepted. A trailing unpaired key is dropped. */
internal fun parseNotifyActions(raw: Any?): List<Pair<String, String>> {
    val flat: List<String> = when (raw) {
        is List<*> -> raw.map { it?.toString() ?: "" }
        is Array<*> -> raw.map { it?.toString() ?: "" }
        else -> return emptyList()
    }
    return flat.chunked(2).filter { it.size == 2 && it[0].isNotEmpty() }.map { it[0] to it[1] }
}

/** A string-valued entry of an `a{sv}` map (Notify hints, a portal/GTK notification dict). */
internal fun dictString(dict: Any?, key: String): String? =
    ((dict as? Map<*, *>)?.get(key)?.let(::unwrapVariant) as? String)?.takeIf { it.isNotEmpty() }

/** A boolean entry of an `a{sv}` map. The spec types hints like `transient` as `b`; some senders use an integer. */
internal fun dictBool(dict: Any?, key: String): Boolean =
    when (val v = (dict as? Map<*, *>)?.get(key)?.let(::unwrapVariant)) {
        is Boolean -> v
        is Number -> v.toLong() != 0L
        else -> false
    }

/**
 * Suppresses the second sighting of one notification that reaches the bus twice:
 * - GNOME: xdg-desktop-portal-gnome forwards a portal notification to `org.gtk.Notifications` with the
 *   same (app_id, id);
 * - Plasma <= 6.6: xdg-desktop-portal-kde turns it into a `Notify` whose `desktop-entry` hint is the app id.
 * The first sighting wins; a key is remembered for [windowMs].
 */
internal class CrossSourceDedupe(private val windowMs: Long = 2_000L) {
    private val seen = LinkedHashMap<String, Long>()

    @Synchronized fun admitPortalOrGtk(appId: String, id: String, title: String, nowMs: Long): Boolean {
        prune(nowMs)
        val key = "id|$appId|$id"
        val prev = seen[key]
        if (prev != null && nowMs - prev < windowMs) return false
        seen[key] = nowMs
        seen["title|$appId|$title"] = nowMs
        return true
    }

    @Synchronized fun admitFdo(desktopEntry: String?, title: String, nowMs: Long): Boolean {
        if (desktopEntry == null) return true
        prune(nowMs)
        val prev = seen["title|$desktopEntry|$title"] ?: return true
        return nowMs - prev >= windowMs
    }

    private fun prune(nowMs: Long) {
        if (seen.size < 64) return
        seen.entries.removeIf { nowMs - it.value >= windowMs }
    }
}

/**
 * The display name of an app id (`org.kde.neochat` → `NeoChat`) from its `.desktop` file, so a portal or
 * GTK notification gets the same per-app mute entry as the app's `Notify` ones. Falls back to the id.
 */
internal object DesktopAppNames {
    private val cache = ConcurrentHashMap<String, String>()

    fun nameOf(appId: String): String = cache.getOrPut(appId) { lookup(appId) ?: appId }

    private fun lookup(appId: String): String? {
        if (appId.isEmpty() || appId.contains('/')) return null
        val home = System.getProperty("user.home")
        val dataHome = System.getenv("XDG_DATA_HOME")?.takeIf { it.isNotEmpty() } ?: "$home/.local/share"
        val dataDirs = (System.getenv("XDG_DATA_DIRS")?.takeIf { it.isNotEmpty() } ?: "/usr/local/share:/usr/share")
            .split(':')
        val roots = listOf(dataHome) + dataDirs +
            listOf("$dataHome/flatpak/exports/share", "/var/lib/flatpak/exports/share")
        for (root in roots) {
            val f = File(root, "applications/$appId.desktop")
            if (!f.isFile) continue
            return runCatching { desktopName(f.readLines()) }.getOrNull()
        }
        return null
    }

    /** `Name=` of the `[Desktop Entry]` group (unlocalised). */
    internal fun desktopName(lines: List<String>): String? {
        var inEntry = false
        for (raw in lines) {
            val line = raw.trim()
            if (line.startsWith("[")) { inEntry = line == "[Desktop Entry]"; continue }
            if (inEntry && line.startsWith("Name=")) return line.removePrefix("Name=").trim().takeIf { it.isNotEmpty() }
        }
        return null
    }
}

// Wraps the transport reader to intercept the monitored messages:
// - Notify method calls and their returns: Notify call serials are recorded when the call is seen; when
//   the corresponding MethodReturn arrives the daemon-assigned notification ID is extracted and the
//   notification is emitted. The existing Notify() fallback handler is a no-op.
// - portal / GTK AddNotification and RemoveNotification calls (no reply needed: the app picks the id);
// - NotificationClosed from the notification server, and NameOwnerChanged for its name.
//
// D-Bus serials are per-sender, but the monitor sees method_returns from every client
// on the bus. Correlating by reply-serial alone therefore risks matching an unrelated
// reply whose serial collides with a pending Notify, yielding a wrong notification ID —
// which later makes CloseNotification() a silent no-op on the daemon. To avoid that we
// only accept a method_return as a Notify reply when its sender is the notification
// daemon (daemonOwner). With no daemon (daemonOwner null, e.g. at login before the shell
// takes the name) there are no replies to watch: pending Notify calls wait for the server
// that NameOwnerChanged announces, and the loop rebuilds the monitor for it.
private class InterceptingReader(
    private val inner: IMessageReader,
    private val pending: ConcurrentHashMap<Long, PendingNotify>,
    private val lastSeen: ConcurrentHashMap<String, Long>,
    private val crossDedupe: CrossSourceDedupe,
    private val daemonOwner: String?,
    private val ownerChanged: AtomicBoolean,
    private val emit: (NotificationEvent) -> Unit,
) : IMessageReader {

    override fun readMessage(): Message? {
        val msg = inner.readMessage() ?: return null
        try {
            when (msg) {
                is MethodCall -> onCall(msg)
                is MethodReturn -> onReturn(msg)
                is DBusSignal -> onSignal(msg)
            }
        } catch (_: Exception) {}
        return msg
    }

    private fun onCall(msg: MethodCall) {
        val iface = msg.getInterface()
        val member = msg.getName()
        when {
            iface == NOTIFICATIONS_IFACE && member == NOTIFY_MEMBER -> {
                val params = msg.getParameters()
                if (params != null && params.size >= 5) {
                    val appName = params[0] as? String ?: ""
                    val summary = params[3] as? String ?: ""
                    val body = params[4] as? String ?: ""
                    if (summary.isNotEmpty()) {
                        val now = System.currentTimeMillis()
                        if (pending.size >= MAX_PENDING) pending.entries.removeIf { now - it.value.atMs > PENDING_MAX_AGE_MS }
                        if (pending.size >= MAX_PENDING) pending.clear()
                        pending[msg.getSerial()] = PendingNotify(
                            appName, summary, body,
                            desktopEntry = dictString(params.getOrNull(6), "desktop-entry"),
                            actions = parseNotifyActions(params.getOrNull(5)),
                            transient = dictBool(params.getOrNull(6), "transient"),
                            atMs = now,
                        )
                    }
                }
            }
            (iface == PORTAL_IMPL_IFACE || iface == GTK_IFACE) && member == "AddNotification" -> {
                val params = msg.getParameters() ?: return
                if (params.size < 3) return
                val appId = params[0] as? String ?: return
                val id = params[1] as? String ?: return
                val dict = unwrapVariant(params[2])
                val title = dictString(dict, "title") ?: return
                val body = dictString(dict, "body") ?: ""
                if (!crossDedupe.admitPortalOrGtk(appId, id, title, System.currentTimeMillis())) return
                val source = if (iface == PORTAL_IMPL_IFACE) NotifSource.PORTAL else NotifSource.GTK
                emit(NotificationEvent.Posted(IncomingNotification(
                    id = UInt32(0), appName = DesktopAppNames.nameOf(appId), summary = title, body = body,
                    source = source, appId = appId, portalId = id,
                    backend = if (source == NotifSource.PORTAL) msg.getDestination() else null,
                )))
            }
            (iface == PORTAL_IMPL_IFACE || iface == GTK_IFACE) && member == "RemoveNotification" -> {
                val params = msg.getParameters() ?: return
                val appId = params.getOrNull(0) as? String ?: return
                val id = params.getOrNull(1) as? String ?: return
                val source = if (iface == PORTAL_IMPL_IFACE) NotifSource.PORTAL else NotifSource.GTK
                emit(NotificationEvent.Removed(source, appId, id))
            }
        }
    }

    private fun onReturn(msg: MethodReturn) {
        if (daemonOwner == null || msg.getSource() != daemonOwner) return
        val p = pending.remove(msg.getReplySerial()) ?: return
        val params = try { msg.getParameters() } catch (_: Exception) { return }
        val id = when (val rawId = params?.firstOrNull() ?: return) {
            is UInt32 -> rawId
            is Long   -> UInt32(rawId)
            is Int    -> UInt32(rawId.toLong())
            else      -> return
        }
        val now = System.currentTimeMillis()
        // Our own "desktop-only" alerts carry a matching direct watch notification, so don't
        // bridge them (otherwise the watch would show the alert twice).
        if (p.appName == STOANDL_DESKTOP_ONLY_APP) return
        // Plasma <= 6.6 turns a portal notification (already bridged) into a Notify as well.
        if (!crossDedupe.admitFdo(p.desktopEntry, p.summary, now)) return
        val dedupeKey = "${p.appName}|${p.summary}"
        val prev = lastSeen.put(dedupeKey, now)
        if (lastSeen.size > 512) lastSeen.entries.removeIf { now - it.value > 1_000L }
        if (prev == null || now - prev >= 200L) {
            emit(NotificationEvent.Posted(IncomingNotification(
                id, p.appName, p.summary, p.body,
                source = NotifSource.FDO, owner = daemonOwner, appId = p.desktopEntry, actions = p.actions,
                transient = p.transient,
            )))
        }
    }

    private fun onSignal(msg: DBusSignal) {
        val iface = msg.getInterface()
        val member = msg.getName()
        when {
            iface == DBUS_NAME && member == "NameOwnerChanged" && msg.getSource() == DBUS_NAME -> {
                val params = msg.getParameters() ?: return
                if (params.getOrNull(0) != NOTIFICATIONS_IFACE) return
                val newOwner = (params.getOrNull(2) as? String)?.takeIf { it.isNotEmpty() }
                if (newOwner == daemonOwner) return
                // The monitor's reply rule is narrowed to the old owner: rebuild it (the loop re-resolves the
                // owner). The old server's pending calls will never be answered; with no old server, the
                // calls still pending are the ones the new server (perhaps bus-activated by them) answers.
                ownerChanged.set(true)
                if (daemonOwner != null) pending.clear()
                emit(NotificationEvent.OwnerChanged(newOwner))
            }
            iface == NOTIFICATIONS_IFACE && member == "NotificationClosed" -> {
                if (daemonOwner == null || msg.getSource() != daemonOwner) return
                val params = msg.getParameters() ?: return
                val id = params.getOrNull(0) as? UInt32 ?: return
                val reason = params.getOrNull(1) as? UInt32 ?: UInt32(4)
                emit(NotificationEvent.Closed(daemonOwner, id, reason))
            }
        }
    }

    override fun isClosed() = inner.isClosed()
    override fun close() = inner.close()
}

fun monitorNotifications(): Flow<NotificationEvent> = callbackFlow {
    launch {
        // Dedup state persists across reconnect iterations so a reconnect doesn't re-admit
        // a notification that arrived in the 200 ms window straddling the reconnect.
        val lastSeen = ConcurrentHashMap<String, Long>()
        val crossDedupe = CrossSourceDedupe()
        // Notify calls awaiting their reply. Kept across a rebuild for a server that just appeared (see
        // InterceptingReader's NameOwnerChanged); entries age out (PENDING_MAX_AGE_MS).
        val pendingBySerial = ConcurrentHashMap<Long, PendingNotify>()

        // Reflected accessors — reused across reconnect iterations.
        val getTransportMethod = AbstractConnectionBase::class.java
            .getDeclaredMethod("getTransport")
            .also { it.isAccessible = true }
        val writerField = TransportConnection::class.java
            .getDeclaredField("writer")
            .also { it.isAccessible = true }
        val readerField = TransportConnection::class.java
            .getDeclaredField("reader")
            .also { it.isAccessible = true }

        while (isActive) {
            val monitorConn = try {
                openSessionBus()
            } catch (e: Exception) {
                log.warn { "BecomeMonitor: cannot open DBus connection: ${e.message}" }
                delay(2000)
                continue
            }
            var tc: TransportConnection? = null
            var originalWriter: IMessageWriter? = null
            val ownerChanged = AtomicBoolean(false)
            try {
                monitorConn.addFallback(NOTIFICATIONS_OBJECT_PATH, object : FreedesktopNotifications {
                    override fun isRemote() = false
                    override fun getObjectPath() = NOTIFICATIONS_OBJECT_PATH

                    // Notifications are emitted by InterceptingReader on MethodReturn so we
                    // have the real daemon-assigned ID. This handler is intentionally a no-op.
                    override fun Notify(
                        app_name: String, replaces_id: UInt32, app_icon: String,
                        summary: String, body: String, actions: List<String>,
                        hints: Map<String, Variant<*>>, expire_timeout: Int,
                    ): UInt32 = UInt32(0)

                    override fun CloseNotification(id: UInt32) {}
                    override fun GetCapabilities(): List<String> = emptyList()
                    override fun GetServerInformation(): Array<String> = arrayOf("stoandl", "stoandl", "1.0", "1.2")
                })

                val monitoring = monitorConn.getRemoteObject(
                    "org.freedesktop.DBus", "/org/freedesktop/DBus", DBusMonitoring::class.java,
                )

                // Resolve the unique name owning org.freedesktop.Notifications so the reader can
                // reject method_returns from other senders. Must happen before BecomeMonitor — the
                // monitor connection can no longer make normal method calls afterwards.
                val daemonOwner = try {
                    val dbus = monitorConn.getRemoteObject(
                        "org.freedesktop.DBus", "/org/freedesktop/DBus",
                        org.freedesktop.dbus.interfaces.DBus::class.java,
                    )
                    dbus.GetNameOwner(NOTIFICATIONS_IFACE)
                } catch (e: Exception) {
                    // Normal at login, before the shell takes the name; NameOwnerChanged triggers the rebuild.
                    log.info { "No owner of $NOTIFICATIONS_IFACE yet (${e.message}) — waiting for a notification server" }
                    null
                }
                log.info { "Notification daemon owner: ${daemonOwner ?: "unknown"}" }
                trySend(NotificationEvent.OwnerChanged(daemonOwner))

                val transport = getTransportMethod.invoke(monitorConn) as AbstractTransport
                tc = transport.getTransportConnection()

                // Install intercepting reader *before* BecomeMonitor so that the Notify call
                // serial is captured before the matching MethodReturn can arrive.
                val originalReader = tc.getReader()
                readerField.set(tc, InterceptingReader(
                    originalReader, pendingBySerial, lastSeen, crossDedupe, daemonOwner, ownerChanged,
                ) { event -> trySend(event) })

                // Replies and NotificationClosed only from the notification server, and none while there is
                // no server. An unnarrowed method_return rule receives *every* reply on the bus — a firehose
                // that makes the bus drop the monitor for falling behind (EOF on the transport), which is
                // what happened at every login while the owner was still unknown. Besides those, only
                // method_calls and NameOwnerChanged: portal/GTK notifications need no reply (the app picks
                // the id); NameOwnerChanged tells us a server appeared or restarted (its ids start over).
                val serverRules = if (daemonOwner != null) listOf(
                    "type='method_return',sender='$daemonOwner'",
                    "type='signal',sender='$daemonOwner',interface='$NOTIFICATIONS_IFACE',member='NotificationClosed'",
                ) else emptyList()
                monitoring.BecomeMonitor(
                    (listOf(
                        "type='method_call',interface='$NOTIFICATIONS_IFACE',member='$NOTIFY_MEMBER'",
                        "type='method_call',interface='$PORTAL_IMPL_IFACE',member='AddNotification'",
                        "type='method_call',interface='$PORTAL_IMPL_IFACE',member='RemoveNotification'",
                        "type='method_call',interface='$GTK_IFACE',member='AddNotification'",
                        "type='method_call',interface='$GTK_IFACE',member='RemoveNotification'",
                        "type='signal',sender='$DBUS_NAME',interface='$DBUS_NAME',member='NameOwnerChanged'," +
                            "arg0='$NOTIFICATIONS_IFACE'",
                    ) + serverRules).toTypedArray(),
                    UInt32(0),
                )

                // Install no-op writer after BecomeMonitor so the BecomeMonitor call itself
                // goes through. BecomeMonitor connections must never write back to the daemon.
                originalWriter = writerField.get(tc) as IMessageWriter
                writerField.set(tc, object : IMessageWriter {
                    override fun writeMessage(msg: Message?) {}
                    override fun isClosed(): Boolean = false
                    override fun close() {}
                })

                log.info { "Notification monitor: BecomeMonitor active" }

                while (isActive && monitorConn.isConnected() && !ownerChanged.get()) {
                    delay(500)
                }
                if (isActive) {
                    if (ownerChanged.get()) log.info { "Owner of $NOTIFICATIONS_IFACE changed — rebuilding the notification monitor" }
                    else log.warn { "BecomeMonitor connection lost, reconnecting..." }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn { "BecomeMonitor error: ${e.message}" }
            } finally {
                if (tc != null && originalWriter != null) {
                    try { writerField.set(tc, originalWriter) } catch (_: Exception) {}
                }
                try { monitorConn.disconnect() } catch (_: Exception) {}
            }
            // A new server: rebuild at once, so its replies to the calls still pending aren't missed.
            if (isActive && !ownerChanged.get()) delay(1000)
        }
    }

    awaitClose { log.info { "DBus notification monitor stopping" } }
}
