package de.yoxcu.stoandl.calendar

import io.rebble.libpebblecommon.calendar.CalendarEvent
import io.rebble.libpebblecommon.calendar.EventReminder
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * The watch reads an all-day timestamp as local wall-clock time and applies the timezone offset itself,
 * so all-day pins (and their reminders) must be sent in that frame: UTC midnight of the date. Anchoring
 * to the host's local midnight put them a day early east of UTC once the watch applied its offset.
 */
class ICalParserTest {

    private fun parse(zone: String): List<CalendarEvent> = ICalParser.parse(
        ICS, calendarId = "test",
        start = Instant.parse("2026-08-01T00:00:00Z"), end = Instant.parse("2026-09-01T00:00:00Z"),
        zone = ZoneId.of(zone),
    )

    private fun List<CalendarEvent>.named(title: String) = single { it.title == title }

    @Test
    fun `an all-day event starts at UTC midnight of its date in any host zone`() {
        for (zone in listOf("Europe/Paris", "America/New_York", "Asia/Tokyo", "UTC")) {
            val holiday = parse(zone).named("Holiday")
            assertTrue(holiday.allDay, zone)
            assertEquals(Instant.parse("2026-08-13T00:00:00Z"), holiday.startTime, zone)
            assertEquals(Instant.parse("2026-08-14T00:00:00Z"), holiday.endTime, zone)
        }
    }

    @Test
    fun `a relative alarm on an all-day event keeps its wall-clock offset`() {
        // -PT15H: 09:00 local the day before, in any zone.
        for (zone in listOf("Europe/Paris", "America/New_York")) {
            assertTrue(EventReminder(900) in parse(zone).named("Holiday").reminders, zone)
        }
    }

    @Test
    fun `an absolute alarm on an all-day event moves into the all-day frame`() {
        // 07:00Z is 09:00 in Paris (UTC+2 in August): 9 h after the all-day start, i.e. -540 min.
        assertTrue(EventReminder(-540) in parse("Europe/Paris").named("Holiday").reminders)
        // ...and 03:00 in New York (UTC-4): -180 min.
        assertTrue(EventReminder(-180) in parse("America/New_York").named("Holiday").reminders)
    }

    @Test
    fun `timed events and their absolute alarms are unchanged`() {
        for (zone in listOf("Europe/Paris", "America/New_York")) {
            val dentist = parse(zone).named("Dentist")
            assertFalse(dentist.allDay, zone)
            assertEquals(Instant.parse("2026-08-13T14:00:00Z"), dentist.startTime, zone)
            assertEquals(listOf(EventReminder(30)), dentist.reminders, zone)
        }
    }

    private companion object {
        val ICS = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//stoandl//ICalParserTest//EN
            BEGIN:VEVENT
            UID:holiday@stoandl.test
            DTSTAMP:20260801T000000Z
            DTSTART;VALUE=DATE:20260813
            DTEND;VALUE=DATE:20260814
            SUMMARY:Holiday
            BEGIN:VALARM
            ACTION:DISPLAY
            DESCRIPTION:Holiday tomorrow
            TRIGGER:-PT15H
            END:VALARM
            BEGIN:VALARM
            ACTION:DISPLAY
            DESCRIPTION:Holiday today
            TRIGGER;VALUE=DATE-TIME:20260813T070000Z
            END:VALARM
            END:VEVENT
            BEGIN:VEVENT
            UID:dentist@stoandl.test
            DTSTAMP:20260801T000000Z
            DTSTART:20260813T140000Z
            DTEND:20260813T150000Z
            SUMMARY:Dentist
            BEGIN:VALARM
            ACTION:DISPLAY
            DESCRIPTION:Dentist
            TRIGGER;VALUE=DATE-TIME:20260813T133000Z
            END:VALARM
            END:VEVENT
            END:VCALENDAR
        """.trimIndent().replace("\n", "\r\n")
    }
}
