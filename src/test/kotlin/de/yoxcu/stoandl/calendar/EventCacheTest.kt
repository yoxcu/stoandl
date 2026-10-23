package de.yoxcu.stoandl.calendar

import io.rebble.libpebblecommon.calendar.CalendarEvent
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.nio.file.Files
import java.util.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

/**
 * LinuxSystemCalendar's one event cache: a local file is parsed again only when its content changed, not
 * on every tick or vdirsyncer rewrite, and a calendar without a fingerprint (CalDAV, iCal URL) holds its
 * last fetch until the next change signal.
 */
class EventCacheTest {
    private var fetches = 0
    private var syncs = 0
    /** The calendar's content as its fingerprint reports it; null: no fingerprint at all. */
    private var content: String? = "v1"
    private var listed = true

    /** Rebuilt on every enumeration, like the real sources. */
    private val source = object : CalendarSource {
        override suspend fun calendars(): List<RawCalendar> = if (!listed) emptyList() else listOf(
            RawCalendar(
                platformId = "/cal/work.ics", name = "work", accountId = SourceId.DISCOVER,
                fetchEvents = { _, _ -> fetches++; listOf(event()) },
                fingerprint = content?.let { { content } },
            ),
        )
    }

    private val calendar = LinuxSystemCalendar(
        sources = { listOf(source) }, intervalMinutes = { 30 }, watchDirs = { emptyList() },
        onSync = { syncs++ },
    )

    private fun event(): CalendarEvent {
        val start = Clock.System.now() + 1.hours
        return CalendarEvent(
            id = "e@$start", calendarId = "/cal/work.ics", title = "Meeting", description = "", location = null,
            startTime = start, endTime = start + 1.hours, allDay = false, attendees = emptyList(), recurs = false,
            reminders = emptyList(), availability = CalendarEvent.Availability.Busy,
            status = CalendarEvent.Status.Confirmed, baseEventId = "e",
        )
    }

    /** A pin-sync shaped request: yesterday .. +6 d. */
    private suspend fun sync(end: Instant = Clock.System.now() + 6.days): Int {
        val entity = calendar.getCalendars().single()
        return calendar.getCalendarEvents(entity, Clock.System.now() - 1.days, end).size
    }

    /** Collect the change flow and send one change signal through it (a tick, file change or manual sync). */
    private fun withChanges(block: suspend (signal: suspend () -> Unit) -> Unit) = runBlocking {
        var seen = 0
        val job = launch(start = CoroutineStart.UNDISPATCHED) { calendar.registerForCalendarChanges().collect { seen++ } }
        try {
            block {
                val before = seen
                withTimeout(5_000) { while (seen == before) { calendar.requestRefresh(); delay(10) } }
            }
        } finally {
            job.cancel()
        }
    }

    @Test
    fun `unchanged content is parsed once across change signals`() = withChanges { signal ->
        assertEquals(1, sync())
        signal()
        assertEquals(1, sync())
        signal()
        assertEquals(1, sync())
        assertEquals(1, fetches)
        assertEquals(3, syncs) // each round still counts as a sync
    }

    @Test
    fun `changed content is parsed again`() = withChanges { signal ->
        sync()
        content = "v2"
        signal()
        sync()
        assertEquals(2, fetches)
    }

    @Test
    fun `a request outside the cached window is fetched`() = runBlocking {
        sync()
        sync(end = Clock.System.now() + 30.days)
        assertEquals(2, fetches)
    }

    @Test
    fun `a host time zone change is parsed again`() = runBlocking {
        val before = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Europe/Berlin"))
            sync()
            sync()
            assertEquals(1, fetches)
            // `timedatectl set-timezone`: no change signal, same content, but floating times move.
            TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"))
            sync()
            assertEquals(2, fetches)
            // A calendar without a fingerprint misses too, before the next change signal.
            content = null
            sync()
            assertEquals(2, fetches)
            TimeZone.setDefault(TimeZone.getTimeZone("Europe/Berlin"))
            sync()
            assertEquals(3, fetches)
        } finally {
            TimeZone.setDefault(before)
        }
    }

    @Test
    fun `without a fingerprint the last fetch holds until the next change signal`() = withChanges { signal ->
        content = null
        sync()
        sync()
        assertEquals(1, fetches)
        signal()
        sync()
        assertEquals(2, fetches)
    }

    @Test
    fun `a calendar that went away loses its cached events`() = runBlocking {
        sync()
        listed = false
        calendar.getCalendars()
        listed = true
        sync()
        assertEquals(2, fetches)
    }

    @Test
    fun `a local file's fingerprint follows its bytes, not its writes`() = runBlocking {
        val dir = Files.createTempDirectory("calcache").toFile()
        try {
            val file = File(dir, "work.ics").apply { writeText(ICS) }
            suspend fun fingerprint() = IcsPathSource(listOf(file.path), SourceHealth()).calendars().single().fingerprint!!()
            val first = fingerprint()
            file.writeText(ICS) // a vdirsyncer rewrite of the same content
            assertEquals(first, fingerprint())
            file.writeText(ICS.replace("Meeting", "Meeting moved"))
            assertNotEquals(first, fingerprint())
            // Gone between the enumeration and the sync: no fingerprint, so the fetch runs and reports it.
            val enumerated = IcsPathSource(listOf(file.path), SourceHealth()).calendars().single()
            file.delete()
            assertNull(enumerated.fingerprint!!())
        } finally {
            dir.deleteRecursively()
        }
    }

    private companion object {
        val ICS = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//stoandl//EventCacheTest//EN
            BEGIN:VEVENT
            UID:meeting@stoandl.test
            DTSTAMP:20260801T000000Z
            DTSTART:20260813T140000Z
            DTEND:20260813T150000Z
            SUMMARY:Meeting
            END:VEVENT
            END:VCALENDAR
        """.trimIndent().replace("\n", "\r\n")
    }
}
