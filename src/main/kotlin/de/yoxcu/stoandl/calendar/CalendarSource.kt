package de.yoxcu.stoandl.calendar

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
import kotlin.time.Instant

private val log = KotlinLogging.logger {}

/**
 * One calendar the daemon can read, abstracted over where it lives (local .ics file, published iCal
 * URL, CalDAV collection). [platformId] is the stable unique key libpebble3 stores on its
 * CalendarEntity and bakes into each pin's backingId, so it must be deterministic (we use the file
 * path / URL). [fetchEvents] returns the events in a window, already expanded via [ICalParser]; it
 * must never throw (callers run it inside libpebble3's sync, which has no per-calendar guard).
 */
class RawCalendar(
    val platformId: String,
    val name: String,
    val color: Int = 0,
    val fetchEvents: suspend (start: Instant, end: Instant) -> List<CalendarEvent>,
)

/** A configured place to discover calendars. [calendars] enumerates them (cheap; the actual event
 *  read is deferred to each [RawCalendar.fetchEvents]). */
interface CalendarSource {
    suspend fun calendars(): List<RawCalendar>
}

/** Build a [RawCalendar] backed by a local .ics file, read fresh on each sync. */
private fun icsFileCalendar(file: File): RawCalendar {
    val path = file.absoluteFile.normalize().path
    return RawCalendar(
        platformId = path,
        name = file.nameWithoutExtension,
        fetchEvents = { start, end ->
            try {
                val text = withContext(Dispatchers.IO) { file.readText() }
                ICalParser.parse(text, calendarId = path, start = start, end = end)
            } catch (e: Exception) {
                log.warn { "Failed to read calendar file ${file.path}: ${e.message}" }
                emptyList()
            }
        },
    )
}

/** Expand a list of files/directories into per-file calendars (directories scanned for `*.ics`). */
private fun icsFilesToCalendars(paths: List<File>): List<RawCalendar> = paths.flatMap { f ->
    when {
        f.isDirectory -> f.listFiles { file -> file.isFile && file.extension.equals("ics", true) }
            ?.sortedBy { it.name }?.map(::icsFileCalendar) ?: emptyList()
        f.isFile -> listOf(icsFileCalendar(f))
        else -> {
            log.warn { "Calendar path does not exist: ${f.path}" }
            emptyList()
        }
    }
}

/** Local .ics files or directories explicitly listed in config (`calendar.ics_paths`). No egress. */
class IcsPathSource(private val paths: List<String>) : CalendarSource {
    override suspend fun calendars(): List<RawCalendar> = icsFilesToCalendars(paths.map(::File))
}

/**
 * Best-effort auto-discovery of calendars the desktop environment already keeps as local .ics files
 * — primarily Calindori (the Plasma Mobile calendar app, which stores plain .ics and is the realistic
 * "reuse the DE's calendars" path on the phone). Gated by `calendar.discover`. No egress.
 */
class DiscoverySource : CalendarSource {
    override suspend fun calendars(): List<RawCalendar> {
        val candidates = calendarDiscoveryDirs().filter { it.isDirectory }
        val cals = icsFilesToCalendars(candidates)
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
class IcalUrlSource(private val urls: List<String>, private val client: HttpClient) : CalendarSource {
    override suspend fun calendars(): List<RawCalendar> = urls.map { url ->
        RawCalendar(
            platformId = url,
            name = urlLabel(url),
            fetchEvents = { start, end ->
                try {
                    val resp = client.request(url) { header(HttpHeaders.Accept, "text/calendar") }
                    if (!resp.status.isSuccess()) {
                        log.warn { "iCal feed $url returned HTTP ${resp.status}" }
                        emptyList()
                    } else {
                        ICalParser.parse(resp.bodyAsText(), calendarId = url, start = start, end = end)
                    }
                } catch (e: Exception) {
                    log.warn { "Failed to fetch iCal feed $url: ${e.message}" }
                    emptyList()
                }
            },
        )
    }
}

/** A CalDAV calendar **collection** (the direct collection URL, not the principal). Issues a
 *  `calendar-query` REPORT with a VEVENT time-range and Basic auth. Opt-in egress (`calendar.caldav`).
 *  No principal/home-set discovery or Digest/OAuth — the user supplies the collection URL + creds. */
class CalDavSource(private val entries: List<Entry>, private val client: HttpClient) : CalendarSource {
    data class Entry(val url: String, val username: String, val password: String)

    override suspend fun calendars(): List<RawCalendar> = entries.map { e ->
        val auth = "Basic " + Base64.getEncoder().encodeToString("${e.username}:${e.password}".toByteArray())
        RawCalendar(
            platformId = e.url,
            name = urlLabel(e.url),
            fetchEvents = { start, end ->
                try {
                    val body = calendarQuery(start, end)
                    val resp = client.request(e.url) {
                        method = HttpMethod("REPORT")
                        header("Depth", "1")
                        header(HttpHeaders.Authorization, auth)
                        header(HttpHeaders.ContentType, "application/xml; charset=utf-8")
                        setBody(body)
                    }
                    if (!resp.status.isSuccess()) {
                        log.warn { "CalDAV ${e.url} returned HTTP ${resp.status}" }
                        emptyList()
                    } else {
                        extractCalendarData(resp.bodyAsText()).flatMap { ical ->
                            ICalParser.parse(ical, calendarId = e.url, start = start, end = end)
                        }
                    }
                } catch (ex: Exception) {
                    log.warn { "Failed CalDAV query ${e.url}: ${ex.message}" }
                    emptyList()
                }
            },
        )
    }

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
            val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
                .newDocumentBuilder().parse(ByteArrayInputStream(xml.toByteArray()))
            val nodes = doc.getElementsByTagNameNS("urn:ietf:params:xml:ns:caldav", "calendar-data")
            (0 until nodes.length).mapNotNull { nodes.item(it).textContent?.takeIf { t -> t.isNotBlank() } }
        }
    } catch (e: Exception) {
        log.warn { "Failed to parse CalDAV multistatus: ${e.message}" }
        emptyList()
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
