@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package de.yoxcu.stoandl.plugin

import de.yoxcu.stoandl.notification.AppNamePlacement
import de.yoxcu.stoandl.notification.watchFields
import de.yoxcu.stoandl.notification.watchText
import io.github.oshai.kotlinlogging.KotlinLogging
import io.rebble.libpebblecommon.SystemAppIDs
import io.rebble.libpebblecommon.connection.LibPebble
import io.rebble.libpebblecommon.connection.endpointmanager.timeline.CustomTimelineActionHandler
import io.rebble.libpebblecommon.database.entity.buildTimelineNotification
import io.rebble.libpebblecommon.packets.blobdb.TimelineIcon
import io.rebble.libpebblecommon.packets.blobdb.TimelineActionType
import io.rebble.libpebblecommon.services.blobdb.TimelineActionResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import org.freedesktop.dbus.types.UInt32
import kotlin.time.Clock
import kotlin.uuid.Uuid

private val log = KotlinLogging.logger {}

/** Allow / Deny on a desktop prompt (action keys). */
private const val ALLOW = "allow"
private const val DENY = "deny"

/**
 * Asks the user when an app wants a permission (plugins on): one prompt per app, listing what it asks for,
 * with Allow and Deny on the watch (a direct notification with per-item handlers) and on the desktop (a
 * notification the monitor doesn't bridge). Either answer decides every listed request; answering in the
 * GUI or CLI instead, or putting it off there, retracts both prompts. Until answered, the app is refused (libpebble3 AppPermissions).
 */
class PermissionPrompter(
    private val scope: CoroutineScope,
    private val libPebble: LibPebble,
    private val appName: (Uuid) -> String?,
    private val allowed: () -> Boolean,
    private val appNamePlacement: () -> AppNamePlacement,
    private val desktopPrompt: (summary: String, body: String, actions: List<Pair<String, String>>, onAction: (String) -> Unit) -> UInt32,
    private val closeDesktop: (UInt32) -> Unit,
) {
    private class Prompt(val keys: Set<String>, val watchItem: Uuid?, val desktopId: UInt32?)

    private val prompts = HashMap<Uuid, Prompt>()

    fun start() {
        libPebble.activePermissionRequests()
            .onEach { all ->
                // A request put off (`perms later`, the GUI's Later) is left waiting but no longer asked.
                val requests = all.filter { it.seenAtMs == null }
                val byApp = requests.groupBy({ it.appUuid }, { it.permission }).mapValues { it.value.toSet() }
                val labels = runCatching { libPebble.pluginPermissions().first().mapValues { it.value.label } }.getOrDefault(emptyMap())
                synchronized(prompts) {
                    // Answered (here or elsewhere) or no longer running: take the prompts down.
                    (prompts.keys - byApp.keys).toList().forEach { retract(it) }
                    byApp.forEach { (app, keys) ->
                        if (prompts[app]?.keys == keys) return@forEach
                        retract(app)
                        prompts[app] = if (allowed()) post(app, keys, labels) else Prompt(keys, null, null)
                    }
                }
            }
            .launchIn(scope)
    }

    private fun post(app: Uuid, keys: Set<String>, labels: Map<String, String>): Prompt {
        val name = appName(app) ?: "An app"
        val wants = keys.sorted().joinToString("\n") { "• ${labelOf(it, labels)}" }
        log.info { "Permission request from $name: ${keys.sorted().joinToString()}" }
        val fields = watchFields(
            appName = "stoandl",
            title = "$name asks",
            subtitle = null,
            body = wants,
            placement = appNamePlacement(),
        )
        val notif = buildTimelineNotification(
            parentId = SystemAppIDs.ANDROID_NOTIFICATIONS_UUID,
            timestamp = Clock.System.now(),
        ) {
            attributes {
                watchText(fields)
                tinyIcon { TimelineIcon.NotificationFlag }
            }
            actions {
                action(TimelineActionType.Generic) { attributes { title { "Allow" } } }  // actionId 0
                action(TimelineActionType.Generic) { attributes { title { "Deny" } } }   // actionId 1
                action(TimelineActionType.Dismiss) { attributes { title { "Later" } } }
            }
        }
        val handlers = mapOf<UByte, CustomTimelineActionHandler>(
            0.toUByte() to { decide(app, keys, true); TimelineActionResult(true, TimelineIcon.ResultSent, "Allowed") },
            1.toUByte() to { decide(app, keys, false); TimelineActionResult(true, TimelineIcon.ResultDismissed, "Denied") },
        )
        scope.launch { runCatching { libPebble.sendNotification(notif, handlers) } }
        val desktopId = runCatching {
            desktopPrompt("$name asks for permission", wants, listOf(ALLOW to "Allow", DENY to "Deny")) { key ->
                if (key == ALLOW || key == DENY) decide(app, keys, key == ALLOW)
            }
        }.getOrNull()
        return Prompt(keys, notif.itemId, desktopId)
    }

    private fun decide(app: Uuid, keys: Set<String>, allow: Boolean) {
        scope.launch {
            libPebble.setPermissionDecisions(app, keys.associateWith { allow })
            log.info { "Permission ${if (allow) "allowed" else "denied"} for ${appName(app) ?: app}: ${keys.sorted().joinToString()}" }
        }
    }

    /** Must hold [prompts]. */
    private fun retract(app: Uuid) {
        val p = prompts.remove(app) ?: return
        p.watchItem?.let { item -> scope.launch { runCatching { libPebble.markNotificationRead(item) } } }
        p.desktopId?.takeIf { it.toLong() != 0L }?.let { id -> runCatching { closeDesktop(id) } }
    }
}
