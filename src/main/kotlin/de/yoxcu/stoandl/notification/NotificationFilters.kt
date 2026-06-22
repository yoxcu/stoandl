package de.yoxcu.stoandl.notification

import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

private val log = KotlinLogging.logger {}

/**
 * Global notification filters — a host-side allow/block list enforced at the send choke point
 * ([de.yoxcu.stoandl.pebble.WatchNotifier.push]), independent of the per-app mute store. Each filter is
 * a Java regex (inline flags such as `(?i)` work) matched against the notification's app name, title and
 * body. **Precedence:** an `allow` match is a whitelist — it forces the notification through, bypassing
 * any `block` filter, the master forwarding switch *and* per-app mute; otherwise a `block` match drops it.
 *
 * Persisted as one `action⇥pattern` line per filter in `<configDir>/notification-filters` — a dedicated
 * file, not a `stoandl.conf` key, because a regex may contain any `,`/`=`/`|`. The list is read at
 * startup and the D-Bus methods ([add]/[remove]) mutate it live (no restart) and rewrite the file. The
 * compiled list is held in a `@Volatile` snapshot so [decide] (on the hot send path) reads it lock-free.
 */
class NotificationFilters(private val file: File) {
    enum class Action { ALLOW, BLOCK }
    data class Filter(val pattern: String, val action: Action)
    enum class Decision { ALLOW, BLOCK, NONE }

    private class Compiled(val pattern: String, val action: Action, val regex: Regex)

    private val lock = Any()
    @Volatile private var filters: List<Compiled> = emptyList()

    init { load() }

    private fun load() = synchronized(lock) {
        if (!file.isFile) { filters = emptyList(); return@synchronized }
        filters = file.readLines().mapNotNull { raw ->
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) return@mapNotNull null
            val tab = line.indexOf('\t')
            if (tab <= 0) return@mapNotNull null
            val action = parseAction(line.substring(0, tab)) ?: return@mapNotNull null
            val pattern = line.substring(tab + 1)
            compile(pattern)?.let { Compiled(pattern, action, it) }
        }
        log.info { "Notification filters: ${filters.size} loaded from ${file.path}" }
    }

    private fun parseAction(s: String): Action? = when (s.trim().lowercase()) {
        "allow" -> Action.ALLOW
        "block" -> Action.BLOCK
        else -> null
    }

    private fun compile(pattern: String): Regex? = try {
        Regex(pattern)
    } catch (e: Exception) {
        log.warn { "Ignoring invalid notification filter regex '$pattern': ${e.message}" }
        null
    }

    fun list(): List<Filter> = filters.map { Filter(it.pattern, it.action) }

    /** Decide for a notification: an `allow` match (whitelist) wins over any `block`; [Decision.NONE]
     *  means no filter matched (fall through to the normal forwarding/mute checks). */
    fun decide(appName: String, title: String, body: String): Decision {
        val snap = filters
        if (snap.isEmpty()) return Decision.NONE
        val hay = "$appName\n$title\n$body"
        var blocked = false
        for (f in snap) {
            if (f.regex.containsMatchIn(hay)) {
                if (f.action == Action.ALLOW) return Decision.ALLOW
                blocked = true
            }
        }
        return if (blocked) Decision.BLOCK else Decision.NONE
    }

    /** Add (or replace, by exact pattern) a filter. `action` is coerced to `block` if not `allow`
     *  (matching the GUI mock). Rejects an empty/uncompilable pattern with an `error:` status. */
    fun add(pattern: String, action: String): String {
        if (pattern.isBlank()) return "error:empty pattern"
        val act = if (action.trim().equals("allow", true)) Action.ALLOW else Action.BLOCK
        val regex = compile(pattern) ?: return "error:invalid regex '$pattern'"
        synchronized(lock) {
            filters = filters.filterNot { it.pattern == pattern } + Compiled(pattern, act, regex)
            persist()
        }
        return "ok:added ${act.name.lowercase()} filter"
    }

    /** Remove every filter with this exact pattern; `notfound:` if none matched. */
    fun remove(pattern: String): String = synchronized(lock) {
        val before = filters.size
        filters = filters.filterNot { it.pattern == pattern }
        if (filters.size == before) return "notfound:no filter matching '$pattern'"
        persist()
        "ok:filter removed"
    }

    /** Atomic rewrite (temp + ATOMIC_MOVE, falling back to a plain replace). Lock-held. */
    private fun persist() {
        val text = filters.joinToString("\n") { "${it.action.name.lowercase()}\t${it.pattern}" }
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(if (text.isEmpty()) "" else text + "\n")
        try {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: Exception) {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
