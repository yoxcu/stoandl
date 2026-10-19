package de.yoxcu.stoandl.calendar

import de.yoxcu.stoandl.secret.SecretStore
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.isSuccess
import io.rebble.libpebblecommon.calendar.CalendarEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.File
import java.util.Base64
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Document
import org.w3c.dom.Element
import kotlin.time.Instant

private val log = KotlinLogging.logger {}

/**
 * One calendar the daemon can read, abstracted over where it lives (local .ics file, published iCal
 * URL, CalDAV collection). [platformId] is the stable unique key libpebble3 stores on its
 * CalendarEntity and bakes into each pin's backingId, so it must be deterministic (we use the file
 * path / URL). [accountId] is the id of the editable *source* that produced this calendar (a CalDAV
 * account fans out to many; an iCal feed / .ics file is its own one) so the GUI can group calendars
 * under their account — see [SourceId]. [fetchEvents] returns the events in a window, already expanded
 * via [ICalParser]; it must never throw (callers run it inside libpebble3's sync, which has no
 * per-calendar guard).
 */
class RawCalendar(
    val platformId: String,
    val name: String,
    val accountId: String,
    val fetchEvents: suspend (start: Instant, end: Instant) -> List<CalendarEvent>,
)

/**
 * Stable, self-describing ids for the editable calendar *sources* (as opposed to the discovered
 * calendars). The GUI/CLI/D-Bus address a source by this id and each discovered calendar carries its
 * owning source id as its [RawCalendar.accountId]. The `<type>:<value>` shape is parsed back by
 * [CalendarSources.parseId]. A CalDAV account uses an opaque generated token (so its URL/username stay
 * editable while the stored password keeps its key); iCal/.ics sources use their own URL/path.
 */
object SourceId {
    /** All auto-discovered local .ics calendars share this synthetic source (toggled via
     *  `calendar.discover`, not individually editable). */
    const val DISCOVER = "discover"
    fun caldav(token: String) = "caldav:$token"
    fun ical(url: String) = "ical:$url"
    fun ics(path: String) = "ics:$path"
}

/** A configured place to discover calendars. [calendars] enumerates them (cheap; the actual event
 *  read is deferred to each [RawCalendar.fetchEvents]). */
interface CalendarSource {
    suspend fun calendars(): List<RawCalendar>
}

/** Build a [RawCalendar] backed by a local .ics file, read fresh on each sync, attributed to [accountId].
 *  Each read is reported to [health] (null: the discovery bucket, which isn't a listed source). */
private fun icsFileCalendar(file: File, accountId: String, health: SourceHealth?): RawCalendar {
    val path = file.absoluteFile.normalize().path
    return RawCalendar(
        platformId = path,
        name = file.nameWithoutExtension,
        accountId = accountId,
        fetchEvents = { start, end ->
            try {
                val text = withContext(Dispatchers.IO) { file.readText() }
                ICalParser.parse(text, calendarId = path, start = start, end = end)
                    .also { health?.ok(accountId, path) }
            } catch (e: Exception) {
                log.warn { "Failed to read calendar file ${file.path}: ${e.message}" }
                health?.failed(accountId, path, "Can't read ${file.name}: ${e.message ?: e::class.simpleName}")
                emptyList()
            }
        },
    )
}

/** Expand one configured path (a file, or a directory scanned for `*.ics`) into calendars, all
 *  attributed to [accountId] — so every .ics under a configured dir groups under that dir's source. */
private fun icsPathToCalendars(path: File, accountId: String, health: SourceHealth?): List<RawCalendar> {
    val cals = when {
        path.isDirectory -> path.listFiles { file -> file.isFile && file.extension.equals("ics", true) }
            ?.sortedBy { it.name }?.map { icsFileCalendar(it, accountId, health) } ?: emptyList()
        path.isFile -> listOf(icsFileCalendar(path, accountId, health))
        else -> {
            log.warn { "Calendar path does not exist: ${path.path}" }
            health?.retain(accountId, emptySet())
            health?.failed(accountId, path.path, "No such file or folder")
            return emptyList()
        }
    }
    health?.retain(accountId, cals.map { it.platformId }.toSet())
    return cals
}

/** Local .ics files or directories explicitly listed in config (`calendar.ics_paths`). No egress.
 *  Each configured path is its own source (`ics:<path>`); a directory fans out to one calendar per file. */
class IcsPathSource(private val paths: List<String>, private val health: SourceHealth) : CalendarSource {
    override suspend fun calendars(): List<RawCalendar> =
        paths.flatMap { p -> icsPathToCalendars(File(p), SourceId.ics(p), health) }
}

/**
 * Best-effort auto-discovery of calendars the desktop environment already keeps as local .ics files
 * — primarily Calindori (the Plasma Mobile calendar app, which stores plain .ics and is the realistic
 * "reuse the DE's calendars" path on the phone). Gated by `calendar.discover`. No egress.
 */
class DiscoverySource : CalendarSource {
    override suspend fun calendars(): List<RawCalendar> {
        val candidates = calendarDiscoveryDirs().filter { it.isDirectory }
        val cals = candidates.flatMap { icsPathToCalendars(it, SourceId.DISCOVER, health = null) }
        log.info { "Calendar discovery scanned ${candidates.size} known dir(s), found ${cals.size} calendar(s)" }
        return cals
    }
}

/** Well-known directories where desktop environments keep calendars as plain local .ics files.
 *  Best-effort: only Calindori (Plasma Mobile) is a reliable plain-.ics store; GNOME EDS and KDE
 *  Akonadi keep online calendars in caches we don't read (see README). Shared with the file-watcher. */
fun calendarDiscoveryDirs(): List<File> {
    val home = System.getProperty("user.home") ?: return emptyList()
    val dataHome = System.getenv("XDG_DATA_HOME")?.takeIf { it.isNotBlank() } ?: "$home/.local/share"
    return listOf(
        File(dataHome, "calindori"),   // Plasma Mobile (Calindori)
        File(home, ".calendars"),
        File(home, "calendars"),
    )
}

/** A published iCalendar feed fetched over HTTP(S) (e.g. Google/Nextcloud/Outlook "iCal URL").
 *  Opt-in egress (`calendar.ical_urls`). */
class IcalUrlSource(
    private val urls: List<String>,
    private val client: HttpClient,
    private val health: SourceHealth,
) : CalendarSource {
    override suspend fun calendars(): List<RawCalendar> = urls.map { url ->
        RawCalendar(
            platformId = url,
            name = urlLabel(url),
            accountId = SourceId.ical(url),
            fetchEvents = { start, end ->
                try {
                    val resp = client.request(url) { header(HttpHeaders.Accept, "text/calendar") }
                    if (!resp.status.isSuccess()) {
                        log.warn { "iCal feed $url returned HTTP ${resp.status}" }
                        health.failed(SourceId.ical(url), url, httpProblem(resp.status.value))
                        emptyList()
                    } else {
                        ICalParser.parse(resp.bodyAsText(), calendarId = url, start = start, end = end)
                            .also { health.ok(SourceId.ical(url), url) }
                    }
                } catch (e: Exception) {
                    log.warn { "Failed to fetch iCal feed $url: ${e.message}" }
                    health.failed(SourceId.ical(url), url, unreachable(e))
                    emptyList()
                }
            },
        )
    }
}

/**
 * CalDAV calendars. Point [Entry.url] at the account/principal/home URL and stoandl auto-discovers
 * every calendar collection (RFC 6764/4791: current-user-principal → calendar-home-set → PROPFIND
 * Depth:1); point it at a single calendar collection and only that one is used. Each collection is
 * read with a `calendar-query` REPORT (VEVENT time-range) + Basic auth. Opt-in egress
 * (`calendar.caldav`). No Digest/OAuth. Use `stoandl calendar disable` to drop discovered calendars
 * you don't want.
 */
class CalDavSource(
    private val entries: List<Entry>,
    private val client: HttpClient,
    private val secretStore: SecretStore,
    private val health: SourceHealth,
) : CalendarSource {
    /** A configured account: an opaque [id] (the source/secret key), its [url] and [username]. The
     *  password is NOT held here — it's resolved per sync from [secretStore] under `caldav:<id>`. */
    data class Entry(val id: String, val url: String, val username: String)
    private data class Collection(val url: String, val name: String)

    override suspend fun calendars(): List<RawCalendar> = entries.flatMap { e ->
        val accountId = SourceId.caldav(e.id)
        val password = withContext(Dispatchers.IO) { secretStore.get(accountId) } ?: ""
        val auth = e.username.takeIf { it.isNotBlank() }
            ?.let { "Basic " + Base64.getEncoder().encodeToString("${e.username}:$password".toByteArray()) }
        val collections = discover(e.url, auth).ifEmpty {
            // Not discoverable (or already a leaf collection) — use the URL as a single calendar.
            listOf(Collection(e.url, urlLabel(e.url)))
        }
        log.info { "CalDAV ${e.url}: ${collections.size} calendar(s) [${collections.joinToString { it.name }}]" }
        health.retain(accountId, collections.map { it.url }.toSet())
        collections.map { col -> calendarFor(col, auth, accountId) }
    }

    private fun calendarFor(col: Collection, auth: String?, accountId: String): RawCalendar = RawCalendar(
        platformId = col.url,
        name = col.name,
        accountId = accountId,
        fetchEvents = { start, end ->
            try {
                val resp = client.request(col.url) {
                    method = HttpMethod("REPORT")
                    header("Depth", "1")
                    auth?.let { header(HttpHeaders.Authorization, it) }
                    header(HttpHeaders.ContentType, "application/xml; charset=utf-8")
                    setBody(calendarQuery(start, end))
                }
                if (!resp.status.isSuccess()) {
                    log.warn { "CalDAV ${col.url} returned HTTP ${resp.status}" }
                    health.failed(accountId, col.url, httpProblem(resp.status.value))
                    emptyList()
                } else {
                    extractCalendarData(resp.bodyAsText()).flatMap { ical ->
                        ICalParser.parse(ical, calendarId = col.url, start = start, end = end)
                    }.also { health.ok(accountId, col.url) }
                }
            } catch (ex: Exception) {
                log.warn { "Failed CalDAV query ${col.url}: ${ex.message}" }
                health.failed(accountId, col.url, unreachable(ex))
                emptyList()
            }
        },
    )

    /**
     * RFC 6764/4791 discovery from [baseUrl]: one PROPFIND for resourcetype + displayname +
     * current-user-principal + calendar-home-set, then branch — the URL is itself a calendar (use it
     * alone); it advertises a calendar-home-set (it's the principal → list that home); or it gives a
     * current-user-principal (PROPFIND that for the home, then list). Best-effort: returns empty on any
     * failure so the caller falls back to the raw URL, whose event read then reports the problem (a
     * sign-in failure fails both alike).
     */
    private suspend fun discover(baseUrl: String, auth: String?): List<Collection> {
        return try {
            val xml = propfind(baseUrl, auth, "0", PROPFIND_DISCOVER)
            val info = withContext(Dispatchers.IO) { parseDiscover(xml) }
            when {
                info.selfIsCalendar -> listOf(Collection(baseUrl, info.selfName ?: urlLabel(baseUrl)))
                info.homeHref != null -> listCalendars(resolveHref(baseUrl, info.homeHref), auth)
                info.principalHref != null -> {
                    val principalUrl = resolveHref(baseUrl, info.principalHref)
                    val homeXml = propfind(principalUrl, auth, "0", PROPFIND_HOME_SET)
                    val home = withContext(Dispatchers.IO) { firstHomeHref(parseXml(homeXml)) } ?: return emptyList()
                    listCalendars(resolveHref(principalUrl, home), auth)
                }
                else -> emptyList()
            }
        } catch (ex: Exception) {
            log.warn { "CalDAV discovery failed for $baseUrl: ${ex.message}" }
            emptyList()
        }
    }

    /** PROPFIND Depth:1 on the calendar-home-set → every child that is a calendar collection. */
    private suspend fun listCalendars(homeUrl: String, auth: String?): List<Collection> {
        val xml = propfind(homeUrl, auth, "1", PROPFIND_LIST)
        return withContext(Dispatchers.IO) {
            val responses = parseXml(xml).getElementsByTagNameNS(DAV_NS, "response")
            (0 until responses.length).mapNotNull { i ->
                val resp = responses.item(i) as? Element ?: return@mapNotNull null
                if (!resp.isCalendarCollection()) return@mapNotNull null
                val href = resp.firstTextNS(DAV_NS, "href") ?: return@mapNotNull null
                Collection(resolveHref(homeUrl, href), resp.firstTextNS(DAV_NS, "displayname") ?: urlLabel(href))
            }
        }
    }

    /** A PROPFIND's multistatus body; a failed status throws [DavStatus]. */
    private suspend fun propfind(url: String, auth: String?, depth: String, body: String): String {
        val resp = client.request(url) {
            method = HttpMethod("PROPFIND")
            header("Depth", depth)
            auth?.let { header(HttpHeaders.Authorization, it) }
            header(HttpHeaders.ContentType, "application/xml; charset=utf-8")
            setBody(body)
        }
        if (!resp.status.isSuccess()) throw DavStatus(resp.status.value)
        return resp.bodyAsText()
    }

    private class DavStatus(val status: Int) : Exception("PROPFIND returned HTTP $status")

    private fun calendarQuery(start: Instant, end: Instant): String {
        val s = caldavStamp(start)
        val en = caldavStamp(end)
        return """<?xml version="1.0" encoding="utf-8" ?>
<C:calendar-query xmlns:D="DAV:" xmlns:C="urn:ietf:params:xml:ns:caldav">
  <D:prop><C:calendar-data/></D:prop>
  <C:filter>
    <C:comp-filter name="VCALENDAR">
      <C:comp-filter name="VEVENT">
        <C:time-range start="$s" end="$en"/>
      </C:comp-filter>
    </C:comp-filter>
  </C:filter>
</C:calendar-query>"""
    }

    /** Pull every <C:calendar-data> payload (one VCALENDAR each) out of a 207 multistatus body. */
    private suspend fun extractCalendarData(xml: String): List<String> = try {
        withContext(Dispatchers.IO) {
            val nodes = parseXml(xml).getElementsByTagNameNS(CALDAV_NS, "calendar-data")
            (0 until nodes.length).mapNotNull { nodes.item(it).textContent?.takeIf { t -> t.isNotBlank() } }
        }
    } catch (e: Exception) {
        log.warn { "Failed to parse CalDAV multistatus: ${e.message}" }
        emptyList()
    }

    private data class DiscoverInfo(
        val selfIsCalendar: Boolean, val selfName: String?, val principalHref: String?, val homeHref: String?,
    )

    /** Parse the Depth:0 discovery PROPFIND: is the URL itself a calendar, and what principal/home does
     *  it advertise? */
    private fun parseDiscover(xml: String): DiscoverInfo {
        val doc = parseXml(xml)
        val self = doc.getElementsByTagNameNS(DAV_NS, "response").item(0) as? Element
        return DiscoverInfo(
            selfIsCalendar = self?.isCalendarCollection() == true,
            selfName = self?.firstTextNS(DAV_NS, "displayname"),
            principalHref = (doc.getElementsByTagNameNS(DAV_NS, "current-user-principal").item(0) as? Element)
                ?.firstTextNS(DAV_NS, "href"),
            homeHref = firstHomeHref(doc),
        )
    }

    private fun firstHomeHref(doc: Document): String? =
        (doc.getElementsByTagNameNS(CALDAV_NS, "calendar-home-set").item(0) as? Element)
            ?.firstTextNS(DAV_NS, "href")

    companion object {
        private const val DAV_NS = "DAV:"
        private const val CALDAV_NS = "urn:ietf:params:xml:ns:caldav"
        private val PROPFIND_DISCOVER = """<?xml version="1.0" encoding="utf-8"?>
<d:propfind xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav"><d:prop>
<d:resourcetype/><d:displayname/><d:current-user-principal/><c:calendar-home-set/>
</d:prop></d:propfind>"""
        private val PROPFIND_HOME_SET = """<?xml version="1.0" encoding="utf-8"?>
<d:propfind xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav"><d:prop><c:calendar-home-set/></d:prop></d:propfind>"""
        private val PROPFIND_LIST = """<?xml version="1.0" encoding="utf-8"?>
<d:propfind xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav"><d:prop><d:resourcetype/><d:displayname/></d:prop></d:propfind>"""
    }
}

/** CalDAV time-range stamps are UTC basic-format: yyyyMMdd'T'HHmmss'Z'. */
private fun caldavStamp(instant: Instant): String {
    val z = java.time.Instant.ofEpochSecond(instant.epochSeconds).atZone(java.time.ZoneOffset.UTC)
    return java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").format(z)
}

/** A short human label for a URL: last non-empty path segment, else the host. */
private fun urlLabel(url: String): String = try {
    val uri = java.net.URI(url)
    uri.path?.trimEnd('/')?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: uri.host ?: url
} catch (e: Exception) {
    url
}

/** Resolve a (possibly absolute-path or relative) DAV href against a base URL. */
private fun resolveHref(base: String, href: String): String = try {
    java.net.URI(base).resolve(href.trim()).toString()
} catch (e: Exception) {
    href
}

private fun parseXml(xml: String): Document =
    DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        .newDocumentBuilder().parse(ByteArrayInputStream(xml.toByteArray()))

/** First non-blank text of the first descendant [local] element in namespace [ns], or null. */
private fun Element.firstTextNS(ns: String, local: String): String? =
    getElementsByTagNameNS(ns, local).item(0)?.textContent?.trim()?.takeIf { it.isNotBlank() }

/** True if any <D:resourcetype> under this element declares the CalDAV `<C:calendar/>` type. */
private fun Element.isCalendarCollection(): Boolean {
    val rts = getElementsByTagNameNS("DAV:", "resourcetype")
    for (i in 0 until rts.length) {
        val rt = rts.item(i) as? Element ?: continue
        if (rt.getElementsByTagNameNS("urn:ietf:params:xml:ns:caldav", "calendar").length > 0) return true
    }
    return false
}
