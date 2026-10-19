package de.yoxcu.stoandl.calendar

import de.yoxcu.stoandl.config.StoandlConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** ListCalendarSources' lastError/lastSync: one calendar of an account reading fine must not hide another
 *  one failing, and a calendar gone from the server must not keep its old error. */
class SourceHealthTest {
    private var now = 1_000L
    private val health = SourceHealth { now }

    @Test
    fun `a failure stays until the same part reads again`() {
        health.failed("caldav:a", "https://dav/a/work/", "Sign-in failed (401)")
        now = 1_100
        health.ok("caldav:a", "https://dav/a/home/")
        assertEquals("Sign-in failed (401)", health.lastError("caldav:a"))
        assertEquals(1_100L, health.lastSync("caldav:a"))
        health.ok("caldav:a", "https://dav/a/work/")
        assertEquals("", health.lastError("caldav:a"))
    }

    @Test
    fun `retain drops the problems of calendars the source no longer has`() {
        health.failed("caldav:a", "https://dav/a/old/", "Not found (404)")
        health.retain("caldav:a", setOf("https://dav/a/home/"))
        assertEquals("", health.lastError("caldav:a"))
    }

    @Test
    fun `the first problem by part wins so the column doesn't flicker`() {
        health.failed("ics:/x", "/x/b.ics", "b")
        health.failed("ics:/x", "/x/a.ics", "a")
        assertEquals("a", health.lastError("ics:/x"))
    }

    @Test
    fun `a source that never read has no sync time`() {
        assertNull(health.lastSync("ical:https://example.com/f.ics"))
        assertEquals("", health.lastError("ical:https://example.com/f.ics"))
    }

    @Test
    fun `http statuses read as the user's problem`() {
        assertEquals("Sign-in failed (401)", httpProblem(401))
        assertEquals("Sign-in failed (403)", httpProblem(403))
        assertEquals("Not found (404)", httpProblem(404))
        assertEquals("Server error (503)", httpProblem(503))
        assertEquals("HTTP 418", httpProblem(418))
    }

    @Test
    fun `list appends lastError and lastSync to every source`() {
        val cfg = StoandlConfig.defaults().copy(
            calendarIcalUrls = listOf("https://example.com/f.ics"),
            calendarIcsPaths = listOf("/home/u/cal.ics"),
        )
        health.failed("ical:https://example.com/f.ics", "https://example.com/f.ics", "Unreachable: timeout")
        now = 1_234
        health.ok("ics:/home/u/cal.ics", "/home/u/cal.ics")
        assertEquals(
            listOf(
                "ical:https://example.com/f.ics\tical\thttps://example.com/f.ics\t\texample.com\tUnreachable: timeout\t",
                "ics:/home/u/cal.ics\tics\t/home/u/cal.ics\t\tcal.ics\t\t1234",
            ),
            CalendarSources.list(cfg, health),
        )
    }
}
