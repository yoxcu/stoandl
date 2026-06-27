package de.yoxcu.stoandl.calendar

import de.yoxcu.stoandl.config.ConfigStore
import de.yoxcu.stoandl.config.StoandlConfig
import de.yoxcu.stoandl.secret.SecretStore
import de.yoxcu.stoandl.util.ConfFile
import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.File
import java.util.UUID

private val log = KotlinLogging.logger {}

/**
 * The add/edit/remove logic behind the GUI Calendars screen and the `stoandl calendar` CLI — the single
 * place that read-modify-writes the three calendar-source config keys (`calendar.caldav`,
 * `calendar.ical_urls`, `calendar.ics_paths`) and coordinates the CalDAV password with the
 * [SecretStore]. The actual live re-reconcile (reload + `requestRefresh`) is the caller's job
 * (PebbleIntegration), so this stays I/O-policy-only.
 *
 * A "source" is an editable entry the user manages: a **CalDAV account** (one URL → many discovered
 * calendars), a published **iCal feed** URL, or a local **.ics** file/dir (one source → its calendars).
 * Each is addressed by a self-describing [SourceId] (`caldav:<token>` / `ical:<url>` / `ics:<path>`).
 * Auto-discovery (`calendar.discover`) is a toggle, not a source listed here. The CalDAV password is
 * never in config — it lives in the keyring/file under the same `caldav:<token>` key.
 */
object CalendarSources {
    /** Split a `<type>:<value>` source id into (type, value); null for the `discover` bucket / malformed. */
    fun parseId(id: String): Pair<String, String>? {
        val i = id.indexOf(':')
        if (i <= 0) return null
        return id.substring(0, i) to id.substring(i + 1)
    }

    /** The editable sources as tab records `id \t type \t url \t username \t label`, CalDAV first, then
     *  iCal feeds, then local .ics paths. The password is NEVER included. */
    fun list(cfg: StoandlConfig): List<String> = buildList {
        cfg.calendarCalDav.forEach { add(rec(SourceId.caldav(it.id), "caldav", it.url, it.username, hostLabel(it.url))) }
        cfg.calendarIcalUrls.forEach { add(rec(SourceId.ical(it), "ical", it, "", hostLabel(it))) }
        cfg.calendarIcsPaths.forEach { add(rec(SourceId.ics(it), "ics", it, "", File(it).name.ifEmpty { it })) }
    }

    /** Add a source. [type] ∈ {caldav, ical, ics}; username/password apply to CalDAV only. Returns
     *  `ok:<id>\t<secretBackend>` (backend = keyring/file/none) or `error:`. */
    fun add(
        type: String, url: String, username: String, password: String,
        store: ConfigStore, confFile: File, secretStore: SecretStore,
    ): String = ConfFile.withLock {
        val u = url.trim()
        if (u.isEmpty()) return@withLock "error:a URL or path is required"
        store.reload()
        val cfg = store.current()
        when (type.trim().lowercase()) {
            "caldav" -> {
                if (!isHttp(u)) return@withLock "error:CalDAV URL must be http(s)"
                if (hasSep(u) || hasSep(username)) return@withLock "error:URL/username cannot contain '|' or ','"
                val token = newToken(cfg.calendarCalDav.map { it.id }.toSet())
                val backend = storeSecret(SourceId.caldav(token), password, secretStore)
                val entries = cfg.calendarCalDav + StoandlConfig.CalDavAccount(token, u, username.trim())
                writeCalDav(confFile, entries)
                store.reload()
                "ok:${SourceId.caldav(token)}\t$backend"
            }
            "ical" -> {
                if (!isHttp(u)) return@withLock "error:iCal URL must be http(s)"
                if (hasComma(u)) return@withLock "error:URL cannot contain ','"
                if (cfg.calendarIcalUrls.any { it.equals(u, true) }) return@withLock "error:that iCal URL is already configured"
                ConfFile.upsert(confFile, mapOf("calendar.ical_urls" to (cfg.calendarIcalUrls + u).joinToString(", ")))
                store.reload()
                "ok:${SourceId.ical(u)}\tnone"
            }
            "ics" -> {
                if (hasComma(u)) return@withLock "error:path cannot contain ','"
                if (cfg.calendarIcsPaths.any { it == u }) return@withLock "error:that path is already configured"
                ConfFile.upsert(confFile, mapOf("calendar.ics_paths" to (cfg.calendarIcsPaths + u).joinToString(", ")))
                store.reload()
                "ok:${SourceId.ics(u)}\tnone"
            }
            else -> "error:unknown source type '$type' (expected caldav, ical, or ics)"
        }
    }

    /** Edit a source by [id]. For CalDAV: [url]/[username] replace the stored values; an empty
     *  [password] KEEPS the existing one (the GUI leaves it blank unless changing it), a non-empty one
     *  replaces it. For iCal/.ics: [url] replaces the value (the id then becomes the new value).
     *  Returns `ok:<id>\t<secretBackend|kept|none>`, `notfound:`, or `error:`. */
    fun update(
        id: String, url: String, username: String, password: String,
        store: ConfigStore, confFile: File, secretStore: SecretStore,
    ): String = ConfFile.withLock {
        val (type, value) = parseId(id) ?: return@withLock "notfound:unknown source '$id'"
        val u = url.trim()
        store.reload()
        val cfg = store.current()
        when (type) {
            "caldav" -> {
                val idx = cfg.calendarCalDav.indexOfFirst { it.id == value }
                if (idx < 0) return@withLock "notfound:no CalDAV account '$id'"
                if (u.isEmpty()) return@withLock "error:a URL is required"
                if (!isHttp(u)) return@withLock "error:CalDAV URL must be http(s)"
                if (hasSep(u) || hasSep(username)) return@withLock "error:URL/username cannot contain '|' or ','"
                val backend = if (password.isNotEmpty()) storeSecret(SourceId.caldav(value), password, secretStore) else "kept"
                val entries = cfg.calendarCalDav.toMutableList()
                    .apply { this[idx] = StoandlConfig.CalDavAccount(value, u, username.trim()) }
                writeCalDav(confFile, entries)
                store.reload()
                "ok:$id\t$backend"
            }
            "ical", "ics" -> {
                val key = if (type == "ical") "calendar.ical_urls" else "calendar.ics_paths"
                val list = if (type == "ical") cfg.calendarIcalUrls else cfg.calendarIcsPaths
                if (value !in list) return@withLock "notfound:no $type source '$id'"
                if (u.isEmpty()) return@withLock "error:a ${if (type == "ical") "URL" else "path"} is required"
                if (type == "ical" && !isHttp(u)) return@withLock "error:iCal URL must be http(s)"
                if (hasComma(u)) return@withLock "error:value cannot contain ','"
                ConfFile.upsert(confFile, mapOf(key to list.map { if (it == value) u else it }.joinToString(", ")))
                store.reload()
                "ok:${if (type == "ical") SourceId.ical(u) else SourceId.ics(u)}\tnone"
            }
            else -> "notfound:unknown source type in '$id'"
        }
    }

    /** Remove a source by [id] (and its stored CalDAV password). Returns `ok:removed`, `notfound:`. */
    fun remove(id: String, store: ConfigStore, confFile: File, secretStore: SecretStore): String = ConfFile.withLock {
        val (type, value) = parseId(id) ?: return@withLock "notfound:unknown source '$id'"
        store.reload()
        val cfg = store.current()
        when (type) {
            "caldav" -> {
                if (cfg.calendarCalDav.none { it.id == value }) return@withLock "notfound:no CalDAV account '$id'"
                writeCalDav(confFile, cfg.calendarCalDav.filterNot { it.id == value })
                secretStore.remove(SourceId.caldav(value))
                store.reload()
                "ok:removed"
            }
            "ical", "ics" -> {
                val key = if (type == "ical") "calendar.ical_urls" else "calendar.ics_paths"
                val list = if (type == "ical") cfg.calendarIcalUrls else cfg.calendarIcsPaths
                if (value !in list) return@withLock "notfound:no $type source '$id'"
                ConfFile.upsert(confFile, mapOf(key to list.filterNot { it == value }.joinToString(", ")))
                store.reload()
                "ok:removed"
            }
            else -> "notfound:unknown source type in '$id'"
        }
    }

    private fun writeCalDav(confFile: File, entries: List<StoandlConfig.CalDavAccount>) =
        ConfFile.upsert(confFile, mapOf("calendar.caldav" to entries.joinToString(", ") { "${it.id}|${it.url}|${it.username}" }))

    /** Store (or clear, when [password] is blank) the CalDAV password; returns the backend name. */
    private fun storeSecret(ref: String, password: String, secretStore: SecretStore): String =
        if (password.isEmpty()) { secretStore.remove(ref); "none" }
        else secretStore.put(ref, password).name.lowercase()

    /** A short, unique opaque token (8 hex) for a new CalDAV account, not colliding with [existing]. */
    private fun newToken(existing: Set<String>): String {
        repeat(20) {
            val t = UUID.randomUUID().toString().replace("-", "").take(8)
            if (t !in existing) return t
        }
        return UUID.randomUUID().toString().replace("-", "")  // astronomically unlikely fallback
    }

    private fun rec(id: String, type: String, url: String, username: String, label: String) =
        "$id\t$type\t$url\t$username\t$label"

    private fun isHttp(u: String) = u.startsWith("http://", true) || u.startsWith("https://", true)
    private fun hasComma(s: String) = s.contains(',')
    private fun hasSep(s: String) = s.contains('|') || s.contains(',')

    /** A human label for a source: the URL host (or last path segment), else the raw string. */
    private fun hostLabel(url: String): String = try {
        val uri = java.net.URI(url)
        uri.host ?: uri.path?.trimEnd('/')?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: url
    } catch (e: Exception) {
        url
    }
}
