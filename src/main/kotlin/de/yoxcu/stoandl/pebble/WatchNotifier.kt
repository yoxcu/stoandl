@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class, kotlin.ExperimentalUnsignedTypes::class)

package de.yoxcu.stoandl.pebble

import de.yoxcu.stoandl.dbus.FreedesktopNotifications
import de.yoxcu.stoandl.util.openSessionBus
import io.github.oshai.kotlinlogging.KotlinLogging
import io.rebble.libpebblecommon.SystemAppIDs
import io.rebble.libpebblecommon.connection.LibPebble
import io.rebble.libpebblecommon.connection.endpointmanager.timeline.PlatformNotificationActionHandler
import io.rebble.libpebblecommon.database.asMillisecond
import io.rebble.libpebblecommon.database.dao.NotificationAppRealDao
import io.rebble.libpebblecommon.database.dao.TimelineNotificationRealDao
import io.rebble.libpebblecommon.database.entity.BaseAction
import io.rebble.libpebblecommon.database.entity.MuteState
import io.rebble.libpebblecommon.database.entity.NotificationAppItem
import io.rebble.libpebblecommon.database.entity.buildTimelineNotification
import io.rebble.libpebblecommon.packets.blobdb.TimelineAttribute
import io.rebble.libpebblecommon.packets.blobdb.TimelineIcon
import io.rebble.libpebblecommon.packets.blobdb.TimelineItem
import io.rebble.libpebblecommon.services.blobdb.TimelineActionResult
import io.rebble.libpebblecommon.timeline.toPebbleColor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.types.UInt32
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Clock
import kotlin.uuid.Uuid

/**
 * The single choke point through which **every** notification reaches the watch — the passive desktop
 * bridge ([DesktopNotifOwner]) and every [de.yoxcu.stoandl.ext.ExtensionManager] extension alike. It
 * owns the per-app mute/style policy (drop-before-send, so a source can't bypass mute) and records a
 * [NotifRoute] per item so [WatchActionRouter] can route the watch-side action/reply/dismiss back to
 * whoever sent it.
 *
 * Routes are persisted ([NotifRouteTable]) and owners resolved by id ([NotifOwnerRegistry]), so a
 * notification's actions keep working across a daemon restart (the route survives; the owner re-
 * registers under the same id; the owner-opaque token travels in the route).
 */
data class NotifAction(val id: String, val label: String)
data class ReplySpec(val cannedReplies: List<String>, val allowVoice: Boolean)

/** A request to put a notification on the watch. [appName] is the policy key (per-app mute/style). */
data class NotifRequest(
    val appName: String,
    val title: String,
    val body: String = "",
    val subtitle: String? = null,
    val actions: List<NotifAction> = emptyList(),
    val reply: ReplySpec? = null,
    val iconCode: String? = null,
    val colorName: String? = null,
    val vibeName: String? = null,
    // Stable watch-item id. When set, a re-send replaces the same notification (even across daemon
    // restarts) instead of creating a duplicate — and its action route is refreshed. Random when null.
    val itemId: Uuid? = null,
)

/**
 * Who sent a notification and what to do when the user acts on it on the wrist. [token] is the
 * owner-opaque correlation value recorded in the [NotifRoute] at send time (the desktop owner stores
 * the D-Bus id; an extension stores its own conversation token) — it travels in the persisted route,
 * so callbacks work after a restart without any in-memory per-item map.
 */
interface NotifOwner {
    val id: String
    suspend fun onAction(itemId: Uuid, token: String?, actionId: String)
    suspend fun onReply(itemId: Uuid, token: String?, text: String)
    suspend fun onDismiss(itemId: Uuid, token: String?)
}

/** Per-item routing info recorded at send time, consumed by [WatchActionRouter]. (Action ids are UByte
 *  on the wire but stored as Int.) In-memory only: the firmware presents the action menu solely on the
 *  *live* notification, never from the history, so a route never needs to outlive the daemon — once a
 *  notification scrolls out of the active view its actions can't be invoked regardless. */
data class NotifRoute(
    val ownerId: String,
    val ownerToken: String? = null,
    val mutePkg: String? = null,           // non-null → a "Mute <app>" action exists; mute this app
    val muteActionId: Int? = null,         // actionId of that Mute action
    val replyActionId: Int? = null,        // actionId of the Reply (Response) action, if any
    val namedActions: Map<Int, String> = emptyMap(),  // actionId → the owner's action id (Generic)
)

/** Resolves an owner id (e.g. "desktop", or an extension name) to the live [NotifOwner]. */
class NotifOwnerRegistry {
    private val owners = ConcurrentHashMap<String, NotifOwner>()
    fun register(owner: NotifOwner) { owners[owner.id] = owner }
    fun get(id: String): NotifOwner? = owners[id]
}

/** `itemId → route` table, written by [WatchNotifier] and read by [WatchActionRouter]. */
class NotifRouteTable {
    private val routes = ConcurrentHashMap<String, NotifRoute>()  // key = itemId.toString()
    fun put(itemId: Uuid, route: NotifRoute) { routes[itemId.toString()] = route }
    fun get(itemId: Uuid): NotifRoute? = routes[itemId.toString()]
    fun remove(itemId: Uuid) { routes.remove(itemId.toString()) }
}

class WatchNotifier(
    private val libPebbleRef: java.util.concurrent.atomic.AtomicReference<LibPebble?>,
    private val routeTable: NotifRouteTable,
    // Per-app mute/style store (null when notification.per_app is off). Lazy-add + host-side mute.
    private val notifAppDao: NotificationAppRealDao?,
    private val defaultMute: MuteState,
    private val timelineNotifDao: TimelineNotificationRealDao,
) {
    private val log = KotlinLogging.logger {}

    /**
     * Build, mute-check, style, send, and route a notification on behalf of [ownerId] (with the
     * owner-opaque [ownerToken] recorded for the action callbacks). Returns the watch item UUID, or
     * null if the app is muted (dropped host-side) or there's no watch connection. The action order
     * (named… , reply, Mute, Dismiss) is mirrored into the [NotifRoute] so action ids line up.
     */
    suspend fun push(req: NotifRequest, ownerId: String, ownerToken: String?): Uuid? {
        val dao = notifAppDao
        var app: NotificationAppItem? = null
        if (dao != null && req.appName.isNotBlank()) {
            val now = Clock.System.now()
            val existing = dao.getEntry(req.appName)
            val entry = if (existing == null) {
                val created = NotificationAppItem(
                    packageName = req.appName, name = req.appName, muteState = defaultMute,
                    channelGroups = emptyList(), stateUpdated = now.asMillisecond(),
                    lastNotified = now.asMillisecond(), muteExpiration = null,
                    vibePatternName = null, colorName = null, iconCode = null,
                )
                dao.insertOrReplace(created)
                log.info { "Tracking new notification app '${req.appName}' (default mute=${defaultMute.name.lowercase()})" }
                created
            } else {
                dao.insertOrReplace(existing.copy(lastNotified = now.asMillisecond()))
                existing
            }
            if (isMutedNow(entry, now)) {
                log.info { "Muted notification from ${req.appName} (${entry.muteState.name.lowercase()})" }
                return null
            }
            app = entry
        }

        // Action ids are assigned sequentially by the DSL in declaration order; mirror that here.
        var aid = 0
        val named = LinkedHashMap<Int, String>()
        req.actions.forEach { named[aid++] = it.id }
        val replyActionId = if (req.reply != null) aid++ else null
        val muteActionId = if (app != null) aid++ else null
        // (the Dismiss action takes the next id; it's matched by type, not stored)

        val reply = req.reply
        val styled = app
        val fixedId = req.itemId
        val notif = buildTimelineNotification(
            // Pin to the same parent UUID the official Android app uses: the firmware only round-trips
            // dismiss/actions for a recognized notification-source app, never for a random parentId.
            parentId = SystemAppIDs.ANDROID_NOTIFICATIONS_UUID,
            timestamp = Clock.System.now(),
        ) {
            // Stable id (when given) → a re-send replaces the same item instead of duplicating.
            if (fixedId != null) itemID = fixedId
            attributes {
                title { req.title }
                if (req.body.isNotEmpty()) body { req.body }
                val sub = req.subtitle?.takeIf { it.isNotEmpty() } ?: req.appName
                if (sub.isNotEmpty()) subtitle { sub }
                // Explicit per-notification style wins, else the per-app store, else the app-name icon.
                tinyIcon { perAppIcon(req.iconCode ?: styled?.iconCode, req.appName) }
                perAppColor(req.colorName ?: styled?.colorName)?.let { c -> backgroundColor { c.toPebbleColor() } }
                perAppVibe(req.vibeName ?: styled?.vibePatternName)?.let { v -> vibrationPattern { v } }
            }
            actions {
                req.actions.forEach { a ->
                    action(TimelineItem.Action.Type.Generic) { attributes { title { a.label } } }
                }
                if (reply != null) {
                    action(TimelineItem.Action.Type.Response) {
                        attributes { title { "Reply" }; cannedResponse { reply.cannedReplies } }
                    }
                }
                if (styled != null) {
                    action(TimelineItem.Action.Type.Generic) { attributes { title { "Mute ${styled.name}" } } }
                }
                action(TimelineItem.Action.Type.Dismiss) { attributes { title { "Dismiss" } } }
            }
        }

        routeTable.put(
            notif.itemId,
            NotifRoute(
                ownerId = ownerId, ownerToken = ownerToken, mutePkg = app?.packageName,
                muteActionId = muteActionId, replyActionId = replyActionId, namedActions = named,
            ),
        )
        val lp = libPebbleRef.get()
        if (lp == null) {
            log.warn { "No watch connection; notification from ${req.appName} dropped" }
            routeTable.remove(notif.itemId)
            return null
        }
        try {
            lp.sendNotification(notif)
        } catch (e: Exception) {
            log.warn(e) { "Failed to send notification from ${req.appName} to watch" }
            routeTable.remove(notif.itemId)
            return null
        }
        log.info { "Notification queued for watch: ${req.appName} – ${req.title}" }
        return notif.itemId
    }

    /** Clear a notification from the watch (a source's proactive `closeNotification`). Marks it deleted
     *  rather than read so it doesn't re-sync (same reasoning as the Dismiss path). */
    suspend fun close(itemId: Uuid) {
        runCatching { timelineNotifDao.markForDeletion(itemId) }
        routeTable.remove(itemId)
    }
}

/**
 * The single [PlatformNotificationActionHandler] for the daemon. Looks up the [NotifRoute] for the
 * acted-on item, resolves its owner via [NotifOwnerRegistry], and dispatches: Dismiss → remove the
 * notification (markForDeletion — NOT markNotificationRead, which would re-insert and re-sync) +
 * [NotifOwner.onDismiss]; the Mute action → host-side per-app mute; the Reply action → extract the
 * chosen canned/dictated text and [NotifOwner.onReply]; a named Generic action → [NotifOwner.onAction].
 * Per-item overrides registered via `sendNotification(notif, handlers)` (e.g. FirmwareControl's Update
 * button) still take precedence upstream in libpebble3 and never reach here.
 */
class WatchActionRouter(
    private val routeTable: NotifRouteTable,
    private val owners: NotifOwnerRegistry,
    private val notifAppDao: NotificationAppRealDao?,
    private val timelineNotifDao: TimelineNotificationRealDao,
) : PlatformNotificationActionHandler {
    private val log = KotlinLogging.logger {}

    override suspend fun invoke(
        itemId: Uuid,
        action: BaseAction,
        attributes: List<TimelineItem.Attribute>,
    ): TimelineActionResult {
        val route = routeTable.get(itemId)
        log.info { "Watch action: type=${action.type} id=${action.actionID} itemId=$itemId owner=${route?.ownerId ?: "?"}" }

        if (action.type == TimelineItem.Action.Type.Dismiss ||
            action.type == TimelineItem.Action.Type.AncsDismiss
        ) {
            // Delete (not just mark-read): markNotificationRead re-inserts the record with a changed
            // hash, which the BlobDB re-syncs — so a dismissed notification would reappear. Marking it
            // deleted removes it from our sync set so the dismissal sticks.
            runCatching { timelineNotifDao.markForDeletion(itemId) }
            try {
                route?.let { owners.get(it.ownerId)?.onDismiss(itemId, it.ownerToken) }
            } catch (e: Exception) {
                log.warn(e) { "onDismiss failed for $itemId" }
            }
            routeTable.remove(itemId)
            return TimelineActionResult(true, TimelineIcon.ResultDismissed, "Dismissed")
        }

        if (route == null) {
            log.info { "Action on unmapped item $itemId — not supported (route lost, e.g. sent before a restart)" }
            return TimelineActionResult(false, TimelineIcon.ResultFailed, "Not supported")
        }
        val owner = owners.get(route.ownerId)
        val aid = action.actionID.toInt()
        return when {
            aid == route.muteActionId -> {
                val pkg = route.mutePkg
                val dao = notifAppDao
                if (pkg != null && dao != null) {
                    dao.updateAppMuteState(pkg, MuteState.Always)
                    log.info { "Muted '$pkg' from watch action" }
                    TimelineActionResult(true, TimelineIcon.ResultMute, "Muted")
                } else {
                    TimelineActionResult(false, TimelineIcon.ResultFailed, "Not supported")
                }
            }
            aid == route.replyActionId -> {
                val text = responseText(attributes)
                try {
                    owner?.onReply(itemId, route.ownerToken, text)
                        ?: return TimelineActionResult(false, TimelineIcon.ResultFailed, "Not supported")
                } catch (e: Exception) {
                    log.warn(e) { "onReply failed for $itemId" }
                    return TimelineActionResult(false, TimelineIcon.ResultFailed, "Failed")
                }
                // Optimistic: the wrist shows "Sent" before the owner's service confirms.
                TimelineActionResult(true, TimelineIcon.ResultSent, "Sent")
            }
            route.namedActions[aid] != null -> {
                try {
                    owner?.onAction(itemId, route.ownerToken, route.namedActions.getValue(aid))
                        ?: return TimelineActionResult(false, TimelineIcon.ResultFailed, "Not supported")
                } catch (e: Exception) {
                    log.warn(e) { "onAction failed for $itemId" }
                    return TimelineActionResult(false, TimelineIcon.ResultFailed, "Failed")
                }
                TimelineActionResult(true, TimelineIcon.ResultSent, "Done")
            }
            else -> {
                log.info { "Unhandled action id=$aid on $itemId" }
                TimelineActionResult(false, TimelineIcon.ResultFailed, "Not supported")
            }
        }
    }

    /** The watch returns the chosen canned response / dictated transcript as the Title (0x01)
     *  attribute of the Response action invocation. Logged verbatim at debug for hardware verification. */
    private fun responseText(attributes: List<TimelineItem.Attribute>): String {
        if (attributes.isNotEmpty()) {
            log.debug { "Reply attributes: ${attributes.map { it.attributeId.get() }}" }
        }
        val titleId = TimelineAttribute.Title.id
        val attr = attributes.firstOrNull { it.attributeId.get() == titleId } ?: attributes.firstOrNull()
        // Wire strings are NUL-terminated; trim that (and any trailing space) off the chosen text.
        return attr?.content?.get()?.toByteArray()?.decodeToString()?.trimEnd(' ', ' ').orEmpty()
    }
}

/**
 * [NotifOwner] for the passive desktop-notification bridge. The route's token is the originating
 * desktop notification's D-Bus id, so a wrist dismiss can close it via `CloseNotification()` (the one
 * method a non-owner may call on `org.freedesktop.Notifications`). Desktop notifications carry no named
 * actions or reply, so those callbacks are no-ops.
 */
class DesktopNotifOwner : NotifOwner {
    override val id = "desktop"
    private val log = KotlinLogging.logger {}
    @Volatile private var conn: DBusConnection? = null

    override suspend fun onAction(itemId: Uuid, token: String?, actionId: String) {}
    override suspend fun onReply(itemId: Uuid, token: String?, text: String) {}

    override suspend fun onDismiss(itemId: Uuid, token: String?) {
        val dbusId = token?.toLongOrNull()?.let { UInt32(it) } ?: return
        withContext(Dispatchers.IO) {
            try {
                notifService()?.CloseNotification(dbusId)
                log.info { "Closed D-Bus notification $dbusId for watch item $itemId" }
            } catch (e: Exception) {
                log.warn { "CloseNotification($dbusId) failed: ${e.message}" }
                conn = null
            }
        }
    }

    private fun notifService(): FreedesktopNotifications? = try {
        val existing = conn
        val c = if (existing != null && existing.isConnected()) existing else {
            existing?.disconnect()
            openSessionBus().also { conn = it }
        }
        c.getRemoteObject(
            "org.freedesktop.Notifications",
            "/org/freedesktop/Notifications",
            FreedesktopNotifications::class.java,
        )
    } catch (e: Exception) {
        log.warn { "Cannot reach D-Bus notification service: ${e.message}" }
        conn = null
        null
    }
}
