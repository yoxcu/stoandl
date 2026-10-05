package de.yoxcu.stoandl.notification

import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

private val log = KotlinLogging.logger {}

/**
 * Per-app overrides of `notification.app_name`: where an app's name goes on its watch notifications.
 * Keyed by the app's policy key (`NotificationAppItem.packageName`, the name stoandl tracks it by). It is
 * stoandl's own file rather than a column in libpebble3's per-app table, so the fork's database schema
 * stays upstream's.
 *
 * Persisted as one `app⇥token` line per override in `<configDir>/notification-app-name`. Read at startup;
 * [set] changes it live (through `NotificationAppsControl`) and rewrites the file. The send path reads a
 * `@Volatile` snapshot, lock-free.
 */
class AppNamePlacements(private val file: File) {
    private val lock = Any()
    @Volatile private var overrides: Map<String, AppNamePlacement> = emptyMap()

    init { load() }

    private fun load() = synchronized(lock) {
        if (!file.isFile) return@synchronized
        val out = LinkedHashMap<String, AppNamePlacement>()
        file.readLines().forEach { raw ->
            // The app name is kept as written (it must match the tracked name exactly); only the token is
            // trimmed. No comment syntax: an app's name may start with '#', and a line without a tab is skipped.
            if (raw.isBlank()) return@forEach
            val tab = raw.lastIndexOf('\t')
            if (tab <= 0) return@forEach
            val token = raw.substring(tab + 1).trim()
            val placement = AppNamePlacement.parse(token)
            if (placement == null) {
                log.warn { "Ignoring unknown app-name placement '$token' in ${file.path} (use small|subtitle|off)" }
                return@forEach
            }
            out[raw.substring(0, tab)] = placement
        }
        overrides = out
        log.info { "App-name overrides: ${out.size} loaded from ${file.path}" }
    }

    /** [app]'s own placement, or null when it follows `notification.app_name`. */
    fun get(app: String): AppNamePlacement? = overrides[app]

    /** Set [app]'s placement, or drop its override when [placement] is null. The file is written before
     *  the change shows, so a failed write changes nothing. */
    fun set(app: String, placement: AppNamePlacement?) {
        require(app.isNotEmpty() && app.none { it == '\t' || it == '\n' || it == '\r' }) {
            "app name '$app' can't be stored (empty, or contains a tab or line break)"
        }
        synchronized(lock) {
            val next = if (placement == null) overrides - app else overrides + (app to placement)
            if (next == overrides) return
            persist(next)
            overrides = next
        }
    }

    /** Atomic rewrite (temp + ATOMIC_MOVE, falling back to a plain replace). Lock-held. */
    private fun persist(map: Map<String, AppNamePlacement>) {
        val text = map.entries.joinToString("") { "${it.key}\t${it.value.token}\n" }
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(text)
        try {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: Exception) {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
