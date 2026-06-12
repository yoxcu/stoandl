package de.yoxcu.stoandl.calendar

import io.github.oshai.kotlinlogging.KotlinLogging
import io.rebble.libpebblecommon.calendar.CalendarEvent
import io.rebble.libpebblecommon.calendar.EventAttendee
import io.rebble.libpebblecommon.calendar.EventReminder
import net.fortuna.ical4j.data.CalendarBuilder
import net.fortuna.ical4j.model.Component
import net.fortuna.ical4j.model.Parameter
import net.fortuna.ical4j.model.Property
import net.fortuna.ical4j.model.component.VAlarm
import net.fortuna.ical4j.model.component.VEvent
import net.fortuna.ical4j.model.property.Trigger
import net.fortuna.ical4j.util.CompatibilityHints
import java.io.StringReader
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import java.time.temporal.Temporal
import java.time.temporal.TemporalAmount
import kotlin.time.Instant
import java.time.Instant as JInstant
import net.fortuna.ical4j.model.Period as ICalPeriod

private val log = KotlinLogging.logger {}

/**
 * Wraps ical4j to turn raw iCalendar text into libpebble3 [CalendarEvent]s. This is the ONLY file
 * that touches ical4j — every calendar source funnels its text through [parse].
 *
 * libpebble3's `PhoneCalendarSyncer` expects already-expanded recurrence instances (it does no RRULE
 * parsing itself — see its `toTimelinePin`/composite-backingId model). So we expand RRULE/RDATE
 * (minus EXDATE) within the requested window: each occurrence becomes its own [CalendarEvent] sharing
 * the event UID as [CalendarEvent.baseEventId] but with a distinct [CalendarEvent.startTime], which
 * makes the syncer's `calendarId + baseEventId + startTime` backingId unique and stable per pin
 * (re-syncs update in place rather than duplicating).
 */
object ICalParser {
    init {
        // Real-world .ics (Google, Nextcloud, Outlook, Calindori) is frequently non-conformant; relax
        // parsing so one quirk doesn't drop a whole calendar. MapTimeZoneCache avoids the default
        // cache's startup warning and is faster for a long-lived process.
        System.setProperty(
            "net.fortuna.ical4j.timezone.cache.impl",
            "net.fortuna.ical4j.util.MapTimeZoneCache",
        )
        CompatibilityHints.setHintEnabled(CompatibilityHints.KEY_RELAXED_PARSING, true)
        CompatibilityHints.setHintEnabled(CompatibilityHints.KEY_RELAXED_UNFOLDING, true)
        CompatibilityHints.setHintEnabled(CompatibilityHints.KEY_RELAXED_VALIDATION, true)
        CompatibilityHints.setHintEnabled(CompatibilityHints.KEY_OUTLOOK_COMPATIBILITY, true)
    }

    /**
     * Parse [icalText] and expand every VEVENT occurrence intersecting [start]..[end] into
     * [CalendarEvent]s tagged with [calendarId]. Never throws: a malformed calendar logs a warning
     * and yields an empty list so one bad source can't break the sync.
     */
    fun parse(icalText: String, calendarId: String, start: Instant, end: Instant): List<CalendarEvent> {
        val cal = try {
            CalendarBuilder().build(StringReader(icalText))
        } catch (e: Exception) {
            log.warn { "Failed to parse iCalendar for '$calendarId': ${e.message}" }
            return emptyList()
        }
        // ical4j iterates recurrences in the QUERY PERIOD's temporal type, and that type must match
        // the event's DTSTART resolution or java.time throws "Unsupported unit: …":
        //   - date-time events need a date-time window — java.time.Instant lacks WEEKS/MONTHS/YEARS,
        //     so we use ZonedDateTime (UTC; per-event zones still come from each DTSTART);
        //   - all-day (VALUE=DATE) events need a date-only window — LocalDate lacks SECONDS.
        // So the window is chosen per-event below.
        val startZdt = start.toQueryZdt()
        val endZdt = end.toQueryZdt()
        val startDate = startZdt.toLocalDate()
        val endDate = endZdt.toLocalDate().plusDays(1) // make the last day inclusive for all-day events
        val out = ArrayList<CalendarEvent>()
        for (event in cal.getComponents<VEvent>(Component.VEVENT)) {
            // Detached overrides (a single edited instance of a series) carry RECURRENCE-ID. ical4j
            // expands each component independently, so the master would still emit the un-edited
            // instance AND the override would emit the moved one → a duplicate. We skip overrides;
            // the master's original-time instance stands. (Documented limitation.)
            if (event.has(Property.RECURRENCE_ID)) continue

            val dtStart = event.getProperty<Property>(Property.DTSTART).orElse(null)
            val dateOnly = dtStart?.getParameter<Parameter>(Parameter.VALUE)?.orElse(null)?.value
                .equals("DATE", ignoreCase = true)
            val occurrences = try {
                if (dateOnly) event.calculateRecurrenceSet<Temporal>(ICalPeriod(startDate, endDate))
                else event.calculateRecurrenceSet<Temporal>(ICalPeriod(startZdt, endZdt))
            } catch (e: Exception) {
                log.warn { "Recurrence expansion failed for an event in '$calendarId': ${e.message}" }
                continue
            }
            if (occurrences.isEmpty()) continue

            val uid = event.text(Property.UID) ?: continue
            val title = event.text(Property.SUMMARY) ?: "(untitled)"
            val description = event.text(Property.DESCRIPTION) ?: ""
            val location = event.text(Property.LOCATION)
            val recurs = event.has(Property.RRULE) || event.has(Property.RDATE)
            val status = when (event.text(Property.STATUS)?.uppercase()) {
                "CONFIRMED" -> CalendarEvent.Status.Confirmed
                "CANCELLED" -> CalendarEvent.Status.Cancelled
                "TENTATIVE" -> CalendarEvent.Status.Tentative
                else -> CalendarEvent.Status.None
            }
            val availability = when (event.text(Property.TRANSP)?.uppercase()) {
                "TRANSPARENT" -> CalendarEvent.Availability.Free
                else -> CalendarEvent.Availability.Busy
            }
            val attendees = parseAttendees(event)
            val triggers = parseTriggers(event)

            for (occ in occurrences) {
                val st = occ.start ?: continue
                val en = occ.end ?: st
                val stJava = st.toJavaInstant()
                val enJava = en.toJavaInstant()
                out += CalendarEvent(
                    id = "$uid@$st",
                    calendarId = calendarId,
                    title = title,
                    description = description,
                    location = location,
                    startTime = stJava.toKotlin(),
                    endTime = enJava.toKotlin(),
                    allDay = st is LocalDate,
                    attendees = attendees,
                    recurs = recurs,
                    reminders = triggers.mapNotNull { it.toReminder(stJava, enJava) }.distinct(),
                    availability = availability,
                    status = status,
                    baseEventId = uid,
                )
            }
        }
        return out
    }

    /** First value of a property by name, or null if absent/blank. */
    private fun VEvent.text(name: String): String? =
        getProperty<Property>(name).orElse(null)?.value?.takeIf { it.isNotBlank() }

    private fun VEvent.has(name: String): Boolean = getProperty<Property>(name).isPresent

    /** Best-effort attendee names/emails for the pin's "Attendees" section. No self-detection or
     *  RSVP status (we report supportsPinActions=false), so this is display-only. */
    private fun parseAttendees(event: VEvent): List<EventAttendee> = try {
        event.getProperties<Property>(Property.ATTENDEE).mapNotNull { prop ->
            val email = prop.value?.substringAfter("mailto:", prop.value)?.takeIf { it.isNotBlank() }
            val name = prop.getParameter<Parameter>(Parameter.CN).orElse(null)?.value?.takeIf { it.isNotBlank() }
            if (email == null && name == null) null
            else EventAttendee(
                name = name, email = email, role = null,
                isOrganizer = false, isCurrentUser = false, attendanceStatus = null,
            )
        }
    } catch (e: Exception) {
        emptyList()
    }
}

private fun Instant.toQueryZdt(): ZonedDateTime =
    JInstant.ofEpochSecond(epochSeconds, nanosecondsOfSecond.toLong()).atZone(ZoneOffset.UTC)

/** Collapse any java.time [Temporal] an occurrence may carry (Instant / Offset- / ZonedDateTime for
 *  timed events, LocalDateTime for floating, LocalDate for all-day) to a fixed java.time.Instant.
 *  Floating/date values are anchored to the daemon host's zone, matching how a desktop renders them. */
private fun Temporal.toJavaInstant(): JInstant = when (this) {
    is JInstant -> this
    is OffsetDateTime -> toInstant()
    is ZonedDateTime -> toInstant()
    is LocalDateTime -> atZone(ZoneId.systemDefault()).toInstant()
    is LocalDate -> atStartOfDay(ZoneId.systemDefault()).toInstant()
    else -> JInstant.from(this)
}

private fun JInstant.toKotlin(): Instant = Instant.fromEpochSeconds(epochSecond, nano.toLong())

/**
 * A VALARM trigger, pre-parsed once per event and evaluated per occurrence into an [EventReminder]
 * (minutes before that occurrence's start — libpebble3's `PhoneCalendarSyncer` then places the watch
 * reminder at `startTime - minutesBefore`). ACTION (DISPLAY/AUDIO/EMAIL) is ignored — the watch just
 * buzzes.
 */
private sealed interface AlarmTrigger {
    fun toReminder(occStart: JInstant, occEnd: JInstant): EventReminder?

    /** Relative offset (negative = before) from the occurrence start, or its end when [relatedToEnd]. */
    data class Relative(val amount: TemporalAmount, val relatedToEnd: Boolean) : AlarmTrigger {
        override fun toReminder(occStart: JInstant, occEnd: JInstant): EventReminder {
            val anchor = if (relatedToEnd) occEnd else occStart
            // Anchor via ZonedDateTime so week/day units in the duration resolve (Instant lacks them).
            val reminderAt = anchor.atZone(ZoneOffset.UTC).plus(amount).toInstant()
            return EventReminder(ChronoUnit.MINUTES.between(reminderAt, occStart).toInt())
        }
    }

    /** A fixed instant (meaningful for single events; best-effort for recurring ones). */
    data class Absolute(val instant: JInstant) : AlarmTrigger {
        override fun toReminder(occStart: JInstant, occEnd: JInstant): EventReminder =
            EventReminder(ChronoUnit.MINUTES.between(instant, occStart).toInt())
    }
}

/** Parse a VEVENT's VALARM triggers into [AlarmTrigger]s. Never throws. */
private fun parseTriggers(event: VEvent): List<AlarmTrigger> = try {
    event.getComponents<VAlarm>(Component.VALARM).mapNotNull { alarm ->
        val trigger = alarm.getProperty<Trigger>(Property.TRIGGER).orElse(null) ?: return@mapNotNull null
        if (trigger.isAbsolute) {
            AlarmTrigger.Absolute(trigger.date)
        } else {
            val amount = trigger.duration ?: return@mapNotNull null
            val relatedToEnd = trigger.getParameter<Parameter>(Parameter.RELATED)
                .orElse(null)?.value.equals("END", ignoreCase = true)
            AlarmTrigger.Relative(amount, relatedToEnd)
        }
    }
} catch (e: Exception) {
    emptyList()
}
