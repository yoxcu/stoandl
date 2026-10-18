@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class, kotlin.ExperimentalUnsignedTypes::class)

package de.yoxcu.stoandl.pebble

import de.yoxcu.stoandl.config.ConfigStore
import de.yoxcu.stoandl.config.DEFAULT_CANNED_REPLIES
import de.yoxcu.stoandl.imaging.ImageSource
import de.yoxcu.stoandl.notification.AppNamePlacements
import de.yoxcu.stoandl.notification.NotificationFilters
import de.yoxcu.stoandl.notification.NotificationImageStore
import de.yoxcu.stoandl.notification.watchFields
import de.yoxcu.stoandl.notification.watchText
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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Clock
import kotlin.uuid.Uuid
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The single choke point through which **every** notification reaches the watch — the passive desktop
 * bridge ([DesktopNotifOwner]) and every [de.yoxcu.stoandl.ext.ExtensionManager] extension alike. It
 * owns the per-app mute/style policy (drop-before-send, so a source can't bypass mute) and which text goes
 * in which attribute ([watchFields]), and records a [NotifRoute] per item so [WatchActionRouter] can route
 * the watch-side action/reply/dismiss back to whoever sent it.
 *
 * Routes live in memory ([NotifRouteTable], bounded by age and count) and owners are resolved by id
 * ([NotifOwnerRegistry]); the owner-opaque token travels in the route. After a daemon restart a wrist
 * Dismiss still works (it needs no route); other actions on an older item answer "Not supported".
 */
data class NotifAction(val id: String, val label: String)
/** A Reply (Response) action. An empty [cannedReplies] means `notification.canned_replies`; [label] is the
 *  action's title on the watch (an app's own inline-reply label, e.g. "Reply"). */
data class ReplySpec(val cannedReplies: List<String> = emptyList(), val label: String = "Reply")

/** The firmware's limit for the canned-response attribute: the items joined by NUL, UTF-8. */
private const val MAX_CANNED_BYTES = 512
/** Longest action title we send (bytes, UTF-8). */
private const val MAX_ACTION_TITLE_BYTES = 64
/** How long a notification waits for its picture to be decoded and stored. */
private const val IMAGE_STORE_TIMEOUT_MS = 3_000L

/** Keep whole canned replies (in order, blanks dropped) while the NUL-joined UTF-8 fits [maxBytes]; the
 *  first one that doesn't fit ends the list (a cut-off reply would be sent cut off). */
internal fun trimCannedReplies(replies: List<String>, maxBytes: Int = MAX_CANNED_BYTES): List<String> {
    val out = ArrayList<String>()
    var size = 0
    for (r in replies.map { it.trim() }.filter { it.isNotEmpty() }) {
        val add = r.encodeToByteArray().size + if (out.isEmpty()) 0 else 1
        if (size + add > maxBytes) break
        out += r; size += add
    }
    return out
}

/** [s] cut to at most [maxBytes] of UTF-8 without splitting a character. */
internal fun trimUtf8(s: String, maxBytes: Int): String {
    if (s.encodeToByteArray().size <= maxBytes) return s
    var end = s.length
    while (end > 0 && s.substring(0, end).encodeToByteArray().size > maxBytes) {
        end--
        if (end > 0 && Character.isLowSurrogate(s[end]) && Character.isHighSurrogate(s[end - 1])) end--
    }
    return s.substring(0, end)
}

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
    // The desktop `transient` hint: a passing notice (network changes, volume, …) the server itself
    // doesn't keep. Not forwarded unless an allow filter whitelists it.
    val transient: Boolean = false,
    // A picture the notification carries (a photo, a screenshot), shown on watches that support it while
    // notification.images is on. Never a sender avatar.
    val image: ImageSource? = null,
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

/** An owner turning down a wrist action for an expected reason (the notification was closed, its server
 *  restarted). [wristText] is what the watch shows instead of "Failed". */
class ActionRefused(val wristText: String, message: String) : Exception(message)

/** Per-item routing info recorded at send time, consumed by [WatchActionRouter]. (Action ids are UByte
 *  on the wire but stored as Int.) The watch offers the action menu from its notification history too
 *  (stoandl advertises SupportsTwoWayDismissal, see [watchClaims]), so a route has to outlive the popup:
 *  [NotifRouteTable] keeps it for [ROUTE_TTL_MS]. */
@kotlinx.serialization.Serializable
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

/** How long a route outlives its notification's send: the watch keeps notifications in its history for
 *  days, and an action from there needs the route. */
internal const val ROUTE_TTL_MS = 7L * 24 * 60 * 60 * 1000
/** Most routes kept; the oldest go first. */
internal const val MAX_ROUTES = 2000

/**
 * `itemId → route` table, written by [WatchNotifier] and read by [WatchActionRouter]: the one record of
 * which watch items are alive and who owns them. Bounded by age ([ttlMs]) and count ([maxRoutes]), so a
 * long-running daemon doesn't grow it forever. [NotifRouteStore] keeps it across restarts ([snapshot],
 * [restore], [onChange]).
 */
class NotifRouteTable(
    private val ttlMs: Long = ROUTE_TTL_MS,
    private val maxRoutes: Int = MAX_ROUTES,
    private val now: () -> Long = System::currentTimeMillis,
) {
    /** A route and when it was recorded. */
    data class Saved(val itemId: Uuid, val route: NotifRoute, val atMs: Long)

    private data class Entry(val route: NotifRoute, val atMs: Long)
    // Insertion order = send order (put re-inserts), so the eldest entry is always first.
    private val routes = LinkedHashMap<Uuid, Entry>()

    /** Runs after every change (put, remove, removeOwner; not age pruning), outside the table's lock. */
    @Volatile var onChange: () -> Unit = {}

    fun put(itemId: Uuid, route: NotifRoute) {
        synchronized(routes) {
            routes.remove(itemId)
            routes[itemId] = Entry(route, now())
            prune()
        }
        onChange()
    }

    fun get(itemId: Uuid): NotifRoute? = synchronized(routes) { prune(); routes[itemId]?.route }

    fun remove(itemId: Uuid) {
        if (synchronized(routes) { routes.remove(itemId) } != null) onChange()
    }

    /** Drop every route of [ownerId] (their tokens went stale); returns how many. */
    fun removeOwner(ownerId: String): Int {
        val n = synchronized(routes) {
            var n = 0
            routes.entries.removeIf { (it.value.route.ownerId == ownerId).also { hit -> if (hit) n++ } }
            n
        }
        if (n > 0) onChange()
        return n
    }

    /** The live routes, oldest first. */
    fun snapshot(): List<Saved> = synchronized(routes) {
        prune()
        routes.map { (id, e) -> Saved(id, e.route, e.atMs) }
    }

    /** Put back routes saved before a restart. Any route recorded since wins over a saved one for the same item. */
    fun restore(saved: List<Saved>) = synchronized(routes) {
        val live = LinkedHashMap(routes)
        routes.clear()
        for (s in saved.sortedBy { it.atMs }) if (s.itemId !in live) routes[s.itemId] = Entry(s.route, s.atMs)
        routes.putAll(live)
        prune()
    }

    /** The newest live item of [ownerId] whose token matches [token], if any: how an owner finds the watch
     *  item for something it learns about later (the desktop closed a notification). */
    fun findItem(ownerId: String, token: (String?) -> Boolean): Uuid? = synchronized(routes) {
        prune()
        routes.entries.lastOrNull { it.value.route.ownerId == ownerId && token(it.value.route.ownerToken) }?.key
    }

    val size: Int get() = synchronized(routes) { routes.size }

    private fun prune() {
        val cutoff = now() - ttlMs
        val it = routes.entries.iterator()
        while (it.hasNext()) {
            val e = it.next()
            if (routes.size > maxRoutes || e.value.atMs < cutoff) it.remove() else break
        }
    }
}

/** `notification.default_mute` → the mute state a newly-tracked app starts in. Unknown values fall back
 *  to Never; [de.yoxcu.stoandl.config.StoandlConfig] has already warned about them at load time. Lives
 *  here, next to its only caller. */
private fun parseMuteState(s: String): MuteState = when (s.lowercase()) {
    "always" -> MuteState.Always
    "weekdays" -> MuteState.Weekdays
    "weekends" -> MuteState.Weekends
    else -> MuteState.Never
}

class WatchNotifier(
    private val libPebbleRef: java.util.concurrent.atomic.AtomicReference<LibPebble?>,
    private val routeTable: NotifRouteTable,
    // Per-app mute/style store. Always built now (so per_app can be toggled live); whether it's
    // consulted is gated per-push on the live `notification.per_app`. Null only in tests.
    private val notifAppDao: NotificationAppRealDao?,
    private val timelineNotifDao: TimelineNotificationRealDao,
    // Live config (master forwarding switch + per_app gate, read per-push) and the global allow/block
    // filter list — both checked at the top of [push] so every source (desktop + extensions) obeys them.
    private val configStore: ConfigStore,
    private val filters: NotificationFilters,
    // Per-app overrides of notification.app_name (where the app name goes on the card).
    private val appNames: AppNamePlacements,
    // Pictures attached to notifications, served when the watch pulls them. Null in tests.
    private val images: NotificationImageStore? = null,
    // The user's named vibes ([VibePatterns]), resolved alongside the built-ins.
    private val customVibes: suspend () -> CustomVibes = { emptyMap() },
) {
    private val log = KotlinLogging.logger {}

    // For the sleep guard (power/SleepGuard): pushes currently between entry and the libpebble3 insert,
    // and when the last insert happened — libpebble3's BlobDB picks the new row up through a Room flow a
    // moment later, and only from then on does it show up in WatchLinkActivity.
    private val inFlight = AtomicInteger(0)
    @Volatile private var lastQueuedAtMs = 0L

    /** Why a suspend should wait for this stage, or null: a push in progress, or one queued within
     *  [pickupGraceMs] (not yet visible to libpebble3's BlobDB bookkeeping). */
    fun pendingDescription(pickupGraceMs: Long): String? = when {
        inFlight.get() > 0 -> "notification being built"
        System.currentTimeMillis() - lastQueuedAtMs < pickupGraceMs -> "notification just queued"
        else -> null
    }

    /**
     * Build, mute-check, style, send, and route a notification on behalf of [ownerId] (with the
     * owner-opaque [ownerToken] recorded for the action callbacks). Returns the watch item UUID, or
     * null if the app is muted (dropped host-side) or there's no watch connection. The action order
     * (named… , reply, Mute, Dismiss) is mirrored into the [NotifRoute] so action ids line up.
     */
    suspend fun push(req: NotifRequest, ownerId: String, ownerToken: String?): Uuid? {
        inFlight.incrementAndGet()
        try {
            return pushInternal(req, ownerId, ownerToken)
        } finally {
            inFlight.decrementAndGet()
        }
    }

    private suspend fun pushInternal(req: NotifRequest, ownerId: String, ownerToken: String?): Uuid? {
        val cfg = configStore.current()
        // Global filters first: an `allow` match whitelists the notification (bypasses block filters, the
        // master forwarding switch AND per-app mute); a `block` match drops it. Then the master switch.
        val whitelisted = when (filters.decide(req.appName, req.title, req.body)) {
            NotificationFilters.Decision.BLOCK -> {
                log.info { "Notification from ${req.appName} dropped by a block filter" }
                return null
            }
            NotificationFilters.Decision.ALLOW -> true
            NotificationFilters.Decision.NONE -> false
        }
        if (!whitelisted && !cfg.notificationForward) {
            log.info { "Notification forwarding paused (notification.forward=false) — dropped from ${req.appName}" }
            return null
        }
        if (!whitelisted && req.transient) {
            log.info { "Transient notification from ${req.appName} not forwarded" }
            return null
        }
        val dao = notifAppDao
        var app: NotificationAppItem? = null
        if (cfg.notificationPerApp && dao != null && req.appName.isNotBlank()) {
            val now = Clock.System.now()
            val existing = dao.getEntry(req.appName)
            val entry = if (existing == null) {
                // Read off the same per-push config snapshot taken above, so a `notification.default_mute`
                // change applies to the next newly-seen app with no restart — and with no second read of
                // the store, which is what a separate supplier parameter amounted to.
                val defaultMute = parseMuteState(cfg.notificationDefaultMute)
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
            if (!whitelisted && isMutedNow(entry, now)) {
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

        // An empty list means the configured one; trimmed to whole items the firmware accepts.
        val reply = req.reply?.let { r ->
            val canned = trimCannedReplies(r.cannedReplies.ifEmpty { cfg.notificationCannedReplies })
                .ifEmpty { DEFAULT_CANNED_REPLIES }
            r.copy(cannedReplies = canned, label = trimUtf8(r.label.ifBlank { "Reply" }, MAX_ACTION_TITLE_BYTES))
        }
        val styled = app
        // The id is fixed before building: a picture is stored under it and the watch asks for it by id.
        // A given id (re-send) replaces the same item, even across daemon restarts.
        val itemId = req.itemId ?: Uuid.random()
        val imageAspect = req.image?.let { storeImage(itemId, it, cfg.notificationImages) }
        // Like styles: an app's own placement applies only while per-app settings are on.
        val placement = styled?.let { appNames.get(it.packageName) } ?: cfg.notificationAppName
        val fields = watchFields(req.appName, req.title, req.subtitle, req.body, placement)
        // The custom store is only read for a name that isn't a built-in or a CSV.
        val vibeSpec = req.vibeName ?: styled?.vibePatternName
        val vibe = perAppVibe(vibeSpec) ?: vibeSpec?.let { spec ->
            perAppVibe(spec, try { customVibes() } catch (e: Exception) {
                log.warn(e) { "Custom vibrations unreadable; '$spec' buzzes the watch's default" }
                emptyMap()
            })
        }
        val notif = buildTimelineNotification(
            // Pin to the same parent UUID the official Android app uses: the firmware only round-trips
            // dismiss/actions for a recognized notification-source app, never for a random parentId.
            parentId = SystemAppIDs.ANDROID_NOTIFICATIONS_UUID,
            timestamp = Clock.System.now(),
        ) {
            itemID = itemId
            attributes {
                watchText(fields)
                // The watch reserves this band and pulls the picture when the card shows (libpebble3 drops
                // the attribute for watches without notification images).
                imageAspect?.let { a -> uByte(TimelineAttribute.ImageAspectRatio) { a } }
                // Explicit per-notification style wins, else the per-app store, else the app-name icon.
                tinyIcon { perAppIcon(req.iconCode ?: styled?.iconCode, req.appName) }
                perAppColor(req.colorName ?: styled?.colorName)?.let { c -> backgroundColor { c.toPebbleColor() } }
                vibe?.let { v -> vibrationPattern { v } }
            }
            actions {
                req.actions.forEach { a ->
                    action(TimelineItem.Action.Type.Generic) { attributes { title { trimUtf8(a.label, MAX_ACTION_TITLE_BYTES) } } }
                }
                if (reply != null) {
                    action(TimelineItem.Action.Type.Response) {
                        attributes { title { reply.label }; cannedResponse { reply.cannedReplies } }
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
            images?.remove(notif.itemId)
            return null
        }
        try {
            lp.sendNotification(notif)
            lastQueuedAtMs = System.currentTimeMillis()
        } catch (e: Exception) {
            log.warn(e) { "Failed to send notification from ${req.appName} to watch" }
            routeTable.remove(notif.itemId)
            images?.remove(notif.itemId)
            return null
        }
        log.info { "Notification queued for watch: ${req.appName} – ${req.title}" }
        return notif.itemId
    }

    /** Store [image] for [itemId] when images are on; the aspect to announce, or null to send it without.
     *  Bounded, so one huge file can't hold up the notification. */
    private suspend fun storeImage(itemId: Uuid, image: ImageSource, enabled: Boolean): UByte? {
        val store = images ?: return null
        if (!enabled) return null
        return withTimeoutOrNull(IMAGE_STORE_TIMEOUT_MS) { store.put(itemId, image) }
            .also { if (it == null) log.info { "Notification $itemId goes out without its image (unreadable or too slow)" } }
    }

    /**
     * Clear a notification from the watch because it was dismissed elsewhere (the desktop closed it, an
     * extension's `closeNotification`). Sets the item's STATE_READ flag, which libpebble3 re-syncs: on the
     * wire that flag is the firmware's *dismissed* status bit, and the firmware applies a status-only
     * insert to an item it has (removing it from the notification list) and ignores it for one it doesn't.
     * markForDeletion would do nothing here: notifications never send BlobDB deletions.
     */
    suspend fun retract(itemId: Uuid) {
        routeTable.remove(itemId)
        images?.remove(itemId)
        val lp = libPebbleRef.get()
        try {
            if (lp != null) lp.markNotificationRead(itemId) else timelineNotifDao.markNotificationRead(itemId)
            log.info { "Notification $itemId dismissed elsewhere; cleared on the watch" }
        } catch (e: Exception) {
            log.warn(e) { "Couldn't clear notification $itemId on the watch" }
        }
    }
}

/**
 * The single [PlatformNotificationActionHandler] for the daemon. Looks up the [NotifRoute] for the
 * acted-on item, resolves its owner via [NotifOwnerRegistry], and dispatches: Dismiss → drop the route,
 * then take the item out of our sync set (the watch already removed it) + [NotifOwner.onDismiss]; the Mute action → host-side per-app mute; the Reply action → extract the
 * chosen canned/dictated text and [NotifOwner.onReply]; a named Generic action → [NotifOwner.onAction].
 * Per-item overrides registered via `sendNotification(notif, handlers)` (e.g. FirmwareControl's Update
 * button) still take precedence upstream in libpebble3 and never reach here.
 */
class WatchActionRouter(
    private val routeTable: NotifRouteTable,
    private val owners: NotifOwnerRegistry,
    private val notifAppDao: NotificationAppRealDao?,
    private val timelineNotifDao: TimelineNotificationRealDao,
    private val images: NotificationImageStore? = null,
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
            // The watch has removed the item already; take it out of our sync set so nothing re-sends it.
            // The route goes first: closing the desktop original makes the server report the close, and
            // with no route left that report finds nothing to retract (no echo back to the watch).
            routeTable.remove(itemId)
            images?.remove(itemId)
            runCatching { timelineNotifDao.markForDeletion(itemId) }
            try {
                route?.let { owners.get(it.ownerId)?.onDismiss(itemId, it.ownerToken) }
            } catch (e: Exception) {
                log.warn(e) { "onDismiss failed for $itemId" }
            }
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
                    return failed("Reply", itemId, e)
                }
                // Optimistic: the wrist shows "Sent" before the owner's service confirms.
                TimelineActionResult(true, TimelineIcon.ResultSent, "Sent")
            }
            route.namedActions[aid] != null -> {
                try {
                    owner?.onAction(itemId, route.ownerToken, route.namedActions.getValue(aid))
                        ?: return TimelineActionResult(false, TimelineIcon.ResultFailed, "Not supported")
                } catch (e: Exception) {
                    return failed("Action", itemId, e)
                }
                TimelineActionResult(true, TimelineIcon.ResultSent, "Done")
            }
            else -> {
                log.info { "Unhandled action id=$aid on $itemId" }
                TimelineActionResult(false, TimelineIcon.ResultFailed, "Not supported")
            }
        }
    }

    /** An expected refusal is one INFO line and its reason on the wrist; anything else is a WARN. */
    private fun failed(what: String, itemId: Uuid, e: Exception): TimelineActionResult {
        if (e is ActionRefused) {
            log.info { "$what on $itemId refused: ${e.message}" }
            return TimelineActionResult(false, TimelineIcon.ResultFailed, e.wristText)
        }
        log.warn(e) { "$what failed for $itemId" }
        return TimelineActionResult(false, TimelineIcon.ResultFailed, "Failed")
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
        return attr?.content?.get()?.toByteArray()?.decodeToString()?.trimEnd('\u0000', ' ').orEmpty()
    }
}
