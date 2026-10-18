@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package de.yoxcu.stoandl.pebble

import de.yoxcu.stoandl.dbus.FreedesktopNotifications
import de.yoxcu.stoandl.dbus.GtkNotifications
import de.yoxcu.stoandl.dbus.IncomingNotification
import de.yoxcu.stoandl.dbus.KdeNotificationManager
import de.yoxcu.stoandl.dbus.NotifSource
import de.yoxcu.stoandl.dbus.NotificationEvent
import de.yoxcu.stoandl.dbus.PortalNotificationBackend
import de.yoxcu.stoandl.util.openSessionBus
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.interfaces.DBus
import org.freedesktop.dbus.interfaces.Introspectable
import org.freedesktop.dbus.types.UInt32
import java.util.concurrent.ConcurrentHashMap
import kotlin.uuid.Uuid

private const val FDO_NAME = "org.freedesktop.Notifications"
private const val FDO_PATH = "/org/freedesktop/Notifications"
private const val GNOME_SHELL_NAME = "org.gnome.Shell"
private const val GTK_NAME = "org.gtk.Notifications"
private const val GTK_PATH = "/org/gtk/Notifications"
private const val PORTAL_PATH = "/org/freedesktop/portal/desktop"
private const val KDE_PORTAL_NAME = "org.freedesktop.impl.portal.desktop.kde"
private const val KDE_NM_IFACE = "org.kde.NotificationManager"
private const val INLINE_REPLY = "inline-reply"
private const val DEFAULT_ACTION = "default"
private const val MAX_DEAD = 512

/**
 * Where a bridged desktop notification lives, recorded as the route's owner token so a wrist action
 * reaches the right notification. FDO ids are only meaningful for the server that assigned them — a
 * restarted plasmashell starts again at 1 — so the token carries that server's unique bus name.
 */
internal sealed interface DesktopNotifRef {
    data class Fdo(val owner: String?, val id: Long) : DesktopNotifRef {
        /** Whether a close reported by [server] for [id] is this notification. An unknown server on
         *  either side can't be told apart, so only the id has to agree then. */
        fun matches(server: String?, id: Long): Boolean =
            this.id == id && (owner == null || server == null || owner == server)
    }
    data class Portal(val backend: String, val appId: String, val id: String) : DesktopNotifRef
    data class Gtk(val appId: String, val id: String) : DesktopNotifRef

    fun encode(): String = when (this) {
        is Fdo -> listOf("fdo", owner.orEmpty(), id.toString())
        is Portal -> listOf("portal", backend, appId, id)
        is Gtk -> listOf("gtk", appId, id)
    }.joinToString(SEP)

    companion object {
        // ASCII unit separator: can't occur in a bus name, and app ids / portal ids don't carry it.
        private const val SEP = "\u001f"

        fun of(n: IncomingNotification): DesktopNotifRef? = when (n.source) {
            NotifSource.FDO -> Fdo(n.owner, n.id.toLong())
            NotifSource.PORTAL -> {
                val backend = n.backend; val appId = n.appId; val id = n.portalId
                if (backend == null || appId == null || id == null) null else Portal(backend, appId, id)
            }
            NotifSource.GTK -> {
                val appId = n.appId; val id = n.portalId
                if (appId == null || id == null) null else Gtk(appId, id)
            }
        }

        fun decode(token: String?): DesktopNotifRef? {
            if (token == null) return null
            val p = token.split(SEP)
            return when (p.firstOrNull()) {
                "fdo" -> if (p.size == 3) p[2].toLongOrNull()?.let { Fdo(p[1].ifEmpty { null }, it) } else null
                "portal" -> if (p.size == 4) Portal(p[1], p[2], p[3]) else null
                "gtk" -> if (p.size == 3) Gtk(p[1], p[2]) else null
                else -> token.toLongOrNull()?.let { Fdo(null, it) } // a bare id (pre-token routes)
            }
        }
    }
}

/** `NotificationClosed` reasons that mean the notification was dealt with: 2 dismissed by the user,
 *  3 closed by `CloseNotification` (the app withdrew it, e.g. read elsewhere). 1 (expired) only moves a
 *  popup into the history, and 4 (undefined) says nothing. */
internal fun closesOnWatch(reason: Long): Boolean = reason == 2L || reason == 3L

/** What the notification server can do for us beyond the spec, probed once per server instance. */
internal data class ServerCaps(val invokeAction: Boolean, val invokeReply: Boolean) {
    companion object {
        val NONE = ServerCaps(invokeAction = false, invokeReply = false)

        /** Read from the server object's introspection XML: Plasma exports `org.kde.NotificationManager`
         *  (InvokeAction since 5.19; InvokeReply only with the plasma-workspace patch). */
        fun fromIntrospection(xml: String): ServerCaps {
            val start = xml.indexOf("<interface name=\"$KDE_NM_IFACE\"")
            if (start < 0) return NONE
            val end = xml.indexOf("</interface>", start).let { if (it < 0) xml.length else it }
            val body = xml.substring(start, end)
            return ServerCaps(
                invokeAction = body.contains("<method name=\"InvokeAction\""),
                invokeReply = body.contains("<method name=\"InvokeReply\""),
            )
        }
    }
}

/** The actions of an FDO notification worth offering on the wrist: not the popup's click action
 *  (`default`) and not `inline-reply` (that one becomes the Reply action). */
internal fun wristActions(actions: List<Pair<String, String>>, max: Int): List<Pair<String, String>> =
    actions.filter { (key, label) -> key != DEFAULT_ACTION && key != INLINE_REPLY && label.isNotBlank() }.take(max)

/** The wrist's "Open" action: the popup's click action (`default`), offered when the app gave one. Its
 *  label is fixed — apps label it "default", "Open", "View" or nothing, and the popup never shows it. */
internal fun openAction(actions: List<Pair<String, String>>): Pair<String, String>? =
    if (actions.any { it.first == DEFAULT_ACTION }) DEFAULT_ACTION to OPEN_ACTION_LABEL else null

internal const val OPEN_ACTION_LABEL = "Open"

/** The named wrist actions for a desktop notification: up to [max] app actions, then Open when [openOn].
 *  All of them go through the server's `InvokeAction`, so none are offered when it lacks the method — a
 *  button that always fails would be worse than none. */
internal fun desktopWristActions(
    actions: List<Pair<String, String>>,
    caps: ServerCaps,
    openOn: Boolean,
    max: Int,
): List<NotifAction> {
    if (!caps.invokeAction) return emptyList()
    val open = if (openOn) listOfNotNull(openAction(actions)) else emptyList()
    return (wristActions(actions, max) + open).map { (key, label) -> NotifAction(key, label) }
}

/** The label the app gave its inline-reply action, if it has one. */
internal fun inlineReplyLabel(actions: List<Pair<String, String>>): String? =
    actions.firstOrNull { it.first == INLINE_REPLY }?.let { it.second.ifBlank { "Reply" } }

/**
 * [NotifOwner] for the passive desktop-notification bridge. The route's token is a [DesktopNotifRef]:
 * - dismiss: FDO → `CloseNotification()` (the one spec method a non-owner may call), falling back to
 *   GNOME Shell's own object when GNOME's relay refuses an id that belongs to another client; PORTAL →
 *   the backend's `RemoveNotification`; GTK → `org.gtk.Notifications.RemoveNotification`;
 * - reply / named actions / Open (FDO on Plasma only): `org.kde.NotificationManager.InvokeReply` /
 *   `InvokeAction` (Open is `InvokeAction(id, "default")`, a click on the popup), which make the server
 *   deliver `NotificationReplied` / `ActionInvoked` to the app itself — the signals an app only accepts
 *   from the server. GNOME Shell has no such method, so none of them are offered there.
 *
 * Stale ids: FDO ids restart after the server restarts, so every FDO call first checks that the token's
 * server still owns the name, and [onEvent] drops all desktop routes when the owner changes. A notification
 * the server reports closed (or an app withdrew) is remembered as dead, so a late reply fails at once
 * instead of reaching whatever reuses its id. Replies are delivered whether or not the screen is locked:
 * answering from the wrist while the phone stays in the pocket is the point.
 *
 * Dismissals travel the other way too: when the server reports a notification closed by the user or the
 * app (`NotificationClosed` reason 2 or 3), or an app withdraws a portal/GTK one, its watch item is
 * [retract]ed while [syncDismissals] is on. A popup that merely expired (reason 1) has moved to the
 * history and stays.
 */
class DesktopNotifOwner(
    private val routeTable: NotifRouteTable,
    /** `notification.sync_dismissals`, read per event. */
    private val syncDismissals: () -> Boolean = { false },
    /** Clears a watch item dismissed on the desktop ([WatchNotifier.retract]). */
    private val retract: suspend (Uuid) -> Unit = {},
) : NotifOwner {
    override val id = "desktop"
    private val log = KotlinLogging.logger {}
    @Volatile private var conn: DBusConnection? = null
    @Volatile private var lastOwner: String? = null
    private val caps = ConcurrentHashMap<String, ServerCaps>()
    private val dead: MutableSet<String> = java.util.Collections.newSetFromMap(
        object : LinkedHashMap<String, Boolean>() {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?) = size > MAX_DEAD
        },
    )

    /** Monitor events that aren't new notifications: owner changes, closes and withdrawals. */
    suspend fun onEvent(e: NotificationEvent) {
        when (e) {
            is NotificationEvent.OwnerChanged -> noteOwner(e.owner)
            is NotificationEvent.Closed -> {
                synchronized(dead) { dead += deadKey(e.owner, e.id.toLong()) }
                if (closesOnWatch(e.reason.toLong())) retractMatching { it is DesktopNotifRef.Fdo && it.matches(e.owner, e.id.toLong()) }
            }
            is NotificationEvent.Removed -> {
                synchronized(dead) { dead += removedKey(e.source, e.appId, e.portalId) }
                retractMatching { ref ->
                    when (ref) {
                        is DesktopNotifRef.Portal -> e.source == NotifSource.PORTAL && ref.appId == e.appId && ref.id == e.portalId
                        is DesktopNotifRef.Gtk -> e.source == NotifSource.GTK && ref.appId == e.appId && ref.id == e.portalId
                        is DesktopNotifRef.Fdo -> false
                    }
                }
            }
            is NotificationEvent.Posted -> {}
        }
    }

    /** The watch item for a desktop notification that went away, retracted when dismissals sync. No item
     *  (never forwarded, already dismissed on the wrist, too old) means nothing to do. */
    private suspend fun retractMatching(match: (DesktopNotifRef) -> Boolean) {
        if (!syncDismissals()) return
        val itemId = routeTable.findItem(id) { token -> DesktopNotifRef.decode(token)?.let(match) == true } ?: return
        retract(itemId)
    }

    /**
     * What a saved desktop route stays valid for: this session bus. Its id is new with every bus (a reboot,
     * a new login), while unique names start over, so a token's server name could then match a different
     * server. On the same bus a restarted server is caught at action time ([sameServer]). Null when the bus
     * can't say, which makes saved routes count as stale.
     */
    fun routeEpoch(): String? = busId()

    /** Drop the desktop routes restored from before a restart unless they were saved on this bus ([routeEpoch]). */
    fun resumeRoutes(saved: String?) {
        if (saved != null && saved == busId()) return
        val n = routeTable.removeOwner(id)
        if (n > 0) log.info { "Dropped $n restored desktop route(s): they belong to an earlier session" }
    }

    @Volatile private var cachedBusId: String? = null

    private fun busId(): String? = cachedBusId ?: try {
        session().getRemoteObject("org.freedesktop.DBus", "/org/freedesktop/DBus", DBus::class.java).GetId()
            .also { cachedBusId = it }
    } catch (_: Exception) {
        null
    }

    /** Record the current server owner; on a change every desktop route is stale, so drop them all. */
    private fun noteOwner(owner: String?) {
        val prev = lastOwner
        lastOwner = owner
        if (prev != null && prev != owner) {
            val n = routeTable.removeOwner(id)
            caps.clear()
            synchronized(dead) { dead.clear() }
            log.info { "Notification server changed ($prev → ${owner ?: "none"}): dropped $n desktop route(s)" }
        }
    }

    /**
     * Whether the desktop draws [n]'s body as markup, so the watch gets it as plain text. `Notify` bodies
     * are markup (GNOME Shell and Plasma advertise `body-markup`). Portal and GTK bodies are plain text
     * (plasmashell >= 6.7, GNOME Shell), except a portal one shown by xdg-desktop-portal-kde (Plasma <= 6.6),
     * which re-posts it through `Notify` as it is. The frontend may address that backend by its unique name.
     */
    internal suspend fun bodyIsMarkup(n: IncomingNotification): Boolean = when (n.source) {
        NotifSource.FDO -> true
        NotifSource.GTK -> false
        NotifSource.PORTAL -> n.backend == KDE_PORTAL_NAME ||
            (n.backend != null && withContext(Dispatchers.IO) { nameOwner(KDE_PORTAL_NAME) } == n.backend)
    }

    /** What the server that posted [n] supports (cached per server instance). */
    internal suspend fun capabilitiesFor(n: IncomingNotification): ServerCaps {
        if (n.source != NotifSource.FDO) return ServerCaps.NONE
        val owner = n.owner ?: return ServerCaps.NONE
        noteOwner(owner)
        caps[owner]?.let { return it }
        val probed = withContext(Dispatchers.IO) {
            try {
                val xml = session().getRemoteObject(owner, FDO_PATH, Introspectable::class.java).Introspect()
                ServerCaps.fromIntrospection(xml)
            } catch (e: Exception) {
                log.debug { "Introspecting $owner failed: ${e.message}" }
                null
            }
        } ?: return ServerCaps.NONE
        caps[owner] = probed
        log.info { "Notification server $owner: InvokeAction=${probed.invokeAction}, InvokeReply=${probed.invokeReply}" }
        return probed
    }

    override suspend fun onDismiss(itemId: Uuid, token: String?) {
        val ref = DesktopNotifRef.decode(token) ?: return
        withContext(Dispatchers.IO) {
            when (ref) {
                is DesktopNotifRef.Fdo -> dismissFdo(itemId, ref)
                is DesktopNotifRef.Portal -> if (!isRemoved(NotifSource.PORTAL, ref.appId, ref.id)) try {
                    session().getRemoteObject(ref.backend, PORTAL_PATH, PortalNotificationBackend::class.java)
                        .RemoveNotification(ref.appId, ref.id)
                    log.info { "Removed portal notification ${ref.appId}/${ref.id} for watch item $itemId" }
                } catch (e: Exception) {
                    log.warn { "RemoveNotification(${ref.appId}, ${ref.id}) on ${ref.backend} failed: ${e.message}" }
                    conn = null
                }
                is DesktopNotifRef.Gtk -> if (!isRemoved(NotifSource.GTK, ref.appId, ref.id)) try {
                    session().getRemoteObject(GTK_NAME, GTK_PATH, GtkNotifications::class.java)
                        .RemoveNotification(ref.appId, ref.id)
                    log.info { "Removed GTK notification ${ref.appId}/${ref.id} for watch item $itemId" }
                } catch (e: Exception) {
                    log.warn { "org.gtk.Notifications.RemoveNotification(${ref.appId}, ${ref.id}) failed: ${e.message}" }
                    conn = null
                }
            }
        }
    }

    private fun dismissFdo(itemId: Uuid, ref: DesktopNotifRef.Fdo) {
        if (isDead(ref)) { log.debug { "Notification ${ref.id} already closed; nothing to dismiss" }; return }
        if (!sameServer(ref)) { log.info { "Not closing ${ref.id}: the notification server restarted since" }; return }
        val dbusId = UInt32(ref.id)
        try {
            session().getRemoteObject(FDO_NAME, FDO_PATH, FreedesktopNotifications::class.java).CloseNotification(dbusId)
            log.info { "Closed D-Bus notification $dbusId for watch item $itemId" }
        } catch (e: Exception) {
            // GNOME >= 42's relay refuses CloseNotification for a live id that another client posted
            // ("Invalid notification ID"); GNOME Shell's own object behind it has no such check and uses
            // the same ids.
            try {
                session().getRemoteObject(GNOME_SHELL_NAME, FDO_PATH, FreedesktopNotifications::class.java)
                    .CloseNotification(dbusId)
                log.info { "Closed D-Bus notification $dbusId via GNOME Shell for watch item $itemId" }
            } catch (e2: Exception) {
                log.warn { "CloseNotification($dbusId) failed: ${e.message}" }
                conn = null
            }
        }
    }

    override suspend fun onReply(itemId: Uuid, token: String?, text: String) {
        val ref = DesktopNotifRef.decode(token) as? DesktopNotifRef.Fdo
            ?: error("only notifications posted with Notify can be replied to")
        require(text.isNotBlank()) { "empty reply" }
        withContext(Dispatchers.IO) {
            checkLive(ref)
            // Any D-Bus error (no such method on an unpatched Plasma, unknown or expired id, a sandboxed
            // caller) propagates: the watch then shows Failed rather than a false Sent.
            session().getRemoteObject(FDO_NAME, FDO_PATH, KdeNotificationManager::class.java)
                .InvokeReply(UInt32(ref.id), text)
            log.info { "Replied to D-Bus notification ${ref.id} from watch item $itemId" }
        }
    }

    override suspend fun onAction(itemId: Uuid, token: String?, actionId: String) {
        val ref = DesktopNotifRef.decode(token) as? DesktopNotifRef.Fdo
            ?: error("only notifications posted with Notify have actions")
        // KNotifications turns InvokeAction("inline-reply") into an empty reply the app would send.
        require(actionId != INLINE_REPLY) { "inline-reply is not an action" }
        withContext(Dispatchers.IO) {
            checkLive(ref)
            session().getRemoteObject(FDO_NAME, FDO_PATH, KdeNotificationManager::class.java)
                .InvokeAction(UInt32(ref.id), actionId)
            log.info { "Invoked action '$actionId' on D-Bus notification ${ref.id} from watch item $itemId" }
        }
    }

    /** A notification already closed, or from a server that has gone since, can't be acted on. */
    private fun checkLive(ref: DesktopNotifRef.Fdo) {
        if (isDead(ref)) throw ActionRefused("Already closed", "notification ${ref.id} was closed")
        if (!sameServer(ref)) throw ActionRefused("Server restarted", "the notification server restarted")
    }

    private fun deadKey(owner: String?, id: Long) = "fdo|${owner.orEmpty()}|$id"
    private fun removedKey(source: NotifSource, appId: String, id: String) = "$source|$appId|$id"
    private fun isRemoved(source: NotifSource, appId: String, id: String) =
        synchronized(dead) { removedKey(source, appId, id) in dead }

    private fun isDead(ref: DesktopNotifRef.Fdo): Boolean = synchronized(dead) {
        deadKey(ref.owner, ref.id) in dead || (ref.owner != null && deadKey(null, ref.id) in dead)
    }

    /** Whether the server that assigned [ref]'s id still owns the name (checked live, so it holds even if
     *  the owner-change event was missed). An unknown owner on either side can't be checked: allow. */
    private fun sameServer(ref: DesktopNotifRef.Fdo): Boolean {
        val owner = ref.owner ?: return true
        return nameOwner(FDO_NAME) == owner // null: nobody owns it now
    }

    /** The unique name that owns [name] now, or null when nobody does (or the bus can't say). */
    private fun nameOwner(name: String): String? = try {
        session().getRemoteObject("org.freedesktop.DBus", "/org/freedesktop/DBus", DBus::class.java)
            .GetNameOwner(name)
    } catch (_: Exception) {
        null
    }

    private fun session(): DBusConnection {
        val existing = conn
        if (existing != null && existing.isConnected()) return existing
        existing?.disconnect()
        return openSessionBus().also { conn = it }
    }
}
