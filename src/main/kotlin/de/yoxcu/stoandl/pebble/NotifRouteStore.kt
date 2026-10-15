package de.yoxcu.stoandl.pebble

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.uuid.Uuid

private val log = KotlinLogging.logger {}

/** Changes are written this long after the last one, so a burst of notifications is one write. */
private const val SAVE_DELAY_MS = 2_000L

@Serializable
private data class SavedRoute(val item: String, val at: Long, val route: NotifRoute)

@Serializable
private data class SavedRoutes(
    /** What the desktop routes are valid for ([DesktopNotifOwner.routeEpoch]); null drops them on load. */
    val desktop: String? = null,
    val routes: List<SavedRoute> = emptyList(),
)

/**
 * Keeps [NotifRouteTable] across daemon restarts, so a wrist action on a notification sent before one (a
 * reply, a dismissal, an app's action from the watch's history) still reaches its owner. The file lives in
 * the cache (`~/.cache/stoandl/notification-routes.json`): losing it only loses those actions.
 *
 * Desktop routes name a notification by its server's unique bus name and id, which a new session bus hands
 * out again to a different server and notification. The file therefore records the bus they belong to
 * ([DesktopNotifOwner.routeEpoch]), and [load] returns it for [DesktopNotifOwner.resumeRoutes] to check.
 */
class NotifRouteStore(
    private val file: File,
    private val table: NotifRouteTable,
    private val scope: CoroutineScope,
    private val desktopEpoch: () -> String?,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val lock = Any()
    private var pending: Job? = null

    /** Restore the saved routes into the table and start saving changes. Returns the saved desktop epoch. */
    fun load(): String? {
        val saved = try {
            if (file.isFile) json.decodeFromString(SavedRoutes.serializer(), file.readText()) else null
        } catch (e: Exception) {
            log.warn { "Notification routes in $file unreadable (${e.message}); starting without them" }
            null
        }
        if (saved != null) {
            table.restore(saved.routes.mapNotNull { r ->
                runCatching { NotifRouteTable.Saved(Uuid.parse(r.item), r.route, r.at) }.getOrNull()
            })
            log.info { "Restored ${table.size} notification route(s) for actions from the watch's history" }
        }
        table.onChange = ::scheduleSave
        return saved?.desktop
    }

    private fun scheduleSave() = synchronized(lock) {
        if (pending?.isActive == true) return
        pending = scope.launch(Dispatchers.IO) {
            delay(SAVE_DELAY_MS)
            save()
        }
    }

    /** Write now (at shutdown). */
    fun flush() {
        synchronized(lock) { pending?.cancel(); pending = null }
        save()
    }

    @Synchronized
    private fun save() {
        val out = SavedRoutes(desktopEpoch(), table.snapshot().map { SavedRoute(it.itemId.toString(), it.atMs, it.route) })
        try {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(json.encodeToString(SavedRoutes.serializer(), out))
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: Exception) {
            log.warn { "Could not save notification routes to $file: ${e.message}" }
        }
    }
}
