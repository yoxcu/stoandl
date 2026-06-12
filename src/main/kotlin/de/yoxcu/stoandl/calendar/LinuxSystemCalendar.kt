package de.yoxcu.stoandl.calendar

import io.github.oshai.kotlinlogging.KotlinLogging
import io.rebble.libpebblecommon.calendar.CalendarEvent
import io.rebble.libpebblecommon.calendar.SystemCalendar
import io.rebble.libpebblecommon.database.entity.CalendarEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.isActive
import java.io.File
import java.nio.file.FileSystems
import java.nio.file.Path
import java.nio.file.StandardWatchEventKinds.ENTRY_CREATE
import java.nio.file.StandardWatchEventKinds.ENTRY_DELETE
import java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

private val log = KotlinLogging.logger {}

/**
 * The Linux implementation of libpebble3's [SystemCalendar]. stoandl registers this (overriding the
 * no-op JVM default) and libpebble3's `PhoneCalendarSyncer` does everything else — windowing, pin
 * build via `CalendarEvent.toTimelinePin`, diffing by backingId, and insert/update/delete on the
 * watch. We only supply calendars + events, and a "something changed" signal.
 *
 * Crucially, refresh is driven entirely through [registerForCalendarChanges]: the syncer has no
 * periodic timer of its own, so we merge a periodic ticker (which also rolls the relative window
 * forward and drops past pins), a filesystem watch on the local .ics directories (near-instant
 * updates), and a manual trigger for the `stoandl calendar sync` CLI. The syncer samples this at 5s.
 */
class LinuxSystemCalendar(
    private val sources: List<CalendarSource>,
    private val intervalMinutes: Long,
    private val watchDirs: List<File>,
) : SystemCalendar {
    @Volatile private var byPlatformId: Map<String, RawCalendar> = emptyMap()
    private val manualTrigger = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** Force a re-sync now (used by the `calendar sync` control method). The syncer re-reads within
     *  ~5s (it samples the changes flow). */
    fun requestRefresh() {
        manualTrigger.tryEmit(Unit)
    }

    override suspend fun getCalendars(): List<CalendarEntity> {
        val raws = sources.flatMap { src ->
            try {
                src.calendars()
            } catch (e: Exception) {
                log.warn { "Calendar source failed to enumerate: ${e.message}" }
                emptyList()
            }
        }
        // De-dupe by platformId (a discovered file might also be listed explicitly) and cache for
        // getCalendarEvents to resolve back to the right source.
        val unique = raws.associateBy { it.platformId }
        byPlatformId = unique
        log.info { "Calendar: ${unique.size} calendar(s)${if (unique.isEmpty()) "" else " [" + unique.values.joinToString { it.name } + "]"}" }
        return unique.values.map { rc ->
            CalendarEntity(
                platformId = rc.platformId,
                name = rc.name,
                ownerName = "",
                ownerId = "",
                color = rc.color,
                enabled = true,       // default new calendars on; libpebble3 persists the user's choice after
                syncEvents = true,
            )
        }
    }

    override suspend fun getCalendarEvents(
        calendar: CalendarEntity,
        startDate: Instant,
        endDate: Instant,
    ): List<CalendarEvent> {
        val rc = byPlatformId[calendar.platformId]
            ?: run { getCalendars(); byPlatformId[calendar.platformId] }
            ?: return emptyList()
        val events = rc.fetchEvents(startDate, endDate) // already defensive: returns empty on failure
        log.info { "Calendar '${calendar.name}': ${events.size} event(s) in window $startDate .. $endDate" }
        log.debug {
            "Calendar '${calendar.name}' events: " + events.sortedBy { it.startTime }.joinToString("; ") {
                "${it.startTime}${if (it.allDay) " (all-day)" else ""} ${it.title}" +
                    if (it.reminders.isEmpty()) "" else " [+${it.reminders.size} reminder(s)]"
            }
        }
        return events
    }

    override suspend fun enableSyncForCalendar(calendar: CalendarEntity) {
        // No-op: every calendar we expose already has its events available; there's no per-calendar
        // "enable sync at the source" concept like Android's CalendarContract.
    }

    override fun registerForCalendarChanges(): Flow<Unit> = merge(ticker(), fileWatch(), manualTrigger)

    override fun hasPermission(): Boolean = true

    override fun supportsPinActions(): Boolean = false // no write-back (no CalDAV PUT / RSVP) yet

    /** Periodic re-sync. Delays first — PhoneCalendarSyncer.init() already does an immediate sync. */
    private fun ticker(): Flow<Unit> = flow {
        while (true) {
            delay(intervalMinutes.minutes)
            emit(Unit)
        }
    }

    /** Emit when a `.ics` under any watched directory changes. Best-effort: registration/poll errors
     *  just disable file-watching (the ticker still covers refresh). Cancellable via the poll timeout. */
    private fun fileWatch(): Flow<Unit> = flow {
        val dirs = watchDirs.filter { it.isDirectory }
        if (dirs.isEmpty()) return@flow
        val ws = FileSystems.getDefault().newWatchService()
        try {
            dirs.forEach { dir ->
                try {
                    dir.toPath().register(ws, ENTRY_CREATE, ENTRY_MODIFY, ENTRY_DELETE)
                } catch (e: Exception) {
                    log.debug { "Cannot watch $dir for calendar changes: ${e.message}" }
                }
            }
            while (currentCoroutineContext().isActive) {
                val key = ws.poll(1, TimeUnit.SECONDS) ?: continue
                val touchedIcs = key.pollEvents().any { ev ->
                    (ev.context() as? Path)?.toString()?.endsWith(".ics", ignoreCase = true) == true
                }
                key.reset()
                if (touchedIcs) emit(Unit)
            }
        } finally {
            runCatching { ws.close() }
        }
    }.flowOn(Dispatchers.IO)
}
