package de.yoxcu.stoandl.calendar

import de.yoxcu.stoandl.power.NoWakeups
import de.yoxcu.stoandl.power.delayWallClock
import de.yoxcu.stoandl.util.Flock
import de.yoxcu.stoandl.util.RepeatedLines
import de.yoxcu.stoandl.util.infoWhenChanged
import io.github.oshai.kotlinlogging.KotlinLogging
import io.rebble.libpebblecommon.calendar.CalendarEvent
import io.rebble.libpebblecommon.calendar.NewCalendarEvent
import io.rebble.libpebblecommon.calendar.SystemCalendar
import io.rebble.libpebblecommon.database.entity.CalendarEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import java.io.File
import java.nio.file.ClosedWatchServiceException
import java.nio.file.FileSystems
import java.nio.file.Path
import java.nio.file.StandardWatchEventKinds.ENTRY_CREATE
import java.nio.file.StandardWatchEventKinds.ENTRY_DELETE
import java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY
import java.time.ZoneId
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

private val log = KotlinLogging.logger {}

/** The per-sync summary repeats every refresh: INFO only when the calendars changed. */
private val summaries = RepeatedLines()

/**
 * The Linux implementation of libpebble3's [SystemCalendar]. stoandl supplies this (`JvmHost.calendar`,
 * the JVM's only one) and libpebble3's `PhoneCalendarSyncer` does everything else — windowing, pin
 * build via `CalendarEvent.toTimelinePin`, diffing by backingId, and insert/update/delete on the
 * watch. We only supply calendars + events, and a "something changed" signal.
 *
 * Crucially, refresh is driven entirely through [registerForCalendarChanges]: the syncer has no
 * periodic timer of its own, so we merge a periodic ticker (which also rolls the relative window
 * forward and drops past pins), a filesystem watch on the local .ics directories (near-instant
 * updates), and a manual trigger for the `stoandl calendar sync` CLI. The syncer samples this at 5s.
 */
internal class LinuxSystemCalendar(
    // Read LIVE on each enumeration (not a fixed snapshot) so adding/editing/removing a source via the
    // GUI/CLI takes effect on the next [requestRefresh] with no daemon restart — mirrors how the weather
    // syncer re-reads its config. Always bound (even when empty), so the FIRST source added live works.
    private val sources: () -> List<CalendarSource>,
    // Also read live, and re-sampled on every tick, so editing `calendar.sync_interval` takes effect from
    // the next re-sync instead of only after a daemon restart.
    private val intervalMinutes: () -> Long,
    // Directories file-watched for near-instant .ics updates. Read once when the watch starts; a path
    // added live is still picked up by [requestRefresh] + the ticker (just not the instant file-watch).
    private val watchDirs: () -> List<File>,
    // The lock a sync tool holds while it rewrites a calendar file, by that file (absolute, normalized):
    // its Calindori group's `lockFile`, e.g. dav-sync's. Read at each file change: a change to a file whose
    // lock is held re-reads once the sync is done; a file without one, or whose lock is free, at once.
    private val syncLocks: () -> Map<File, File> = { emptyMap() },
    // Live master switch (`calendar.enabled`): when off, we expose no calendars/events so
    // PhoneCalendarSyncer removes the watch's pins. [requestRefresh] re-evaluates it, so toggling it at
    // runtime clears or repopulates pins without a restart. Default always-on for non-GUI callers.
    private val isEnabled: () -> Boolean = { true },
    // Invoked when the syncer reads events (a sync happened) — stamps the GetSyncStatus lastSync time.
    private val onSync: (() -> Unit)? = null,
    // Invoked on every enumeration (each sync, calendar.enabled or not): the to-do queue's retry.
    private val onRefresh: (() -> Unit)? = null,
    // Resume signal (SleepGuard.resumed): the ticker counts wall-clock time and re-checks on resume, so
    // the timeline window keeps rolling on a phone that is suspended most of the time.
    private val wakeups: Flow<Unit> = NoWakeups,
) : SystemCalendar {
    @Volatile private var byPlatformId: Map<String, RawCalendar> = emptyMap()
    private val manualTrigger = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    /** The interval [ticker] is currently counting down. Re-sampled from [intervalMinutes] on every
     *  [requestRefresh] — which is what a `calendar.sync_interval` write triggers — so a changed value
     *  restarts the countdown instead of waiting out the old one. A StateFlow conflates equal values, so
     *  a refresh that does NOT change the interval leaves the countdown running rather than resetting
     *  it; that is what stops repeated edits to other calendar keys from starving the sync. */
    private val tickInterval = MutableStateFlow(intervalMinutes())
    private val _calendarsChanged = MutableSharedFlow<Unit>(extraBufferCapacity = 4)

    /** Emits when the SET of exposed calendars (by platformId) changes between enumerations — i.e. a
     *  sync added or dropped calendars (after a source was added/removed/edited, or a periodic re-read
     *  found new ones). Drives the daemon's `CalendarsChanged` D-Bus poke so a client re-fetches exactly
     *  when the data is ready, instead of racing the ~5 s (+ CalDAV-discovery) sync after a CRUD call. */
    val calendarsChanged: SharedFlow<Unit> = _calendarsChanged.asSharedFlow()

    /** Force a re-sync now — used by `calendar sync` AND after any source CRUD so a newly added/edited
     *  account's calendars appear (and a removed one's pins drop) within ~5s (the syncer samples the
     *  changes flow). */
    fun requestRefresh() {
        tickInterval.value = intervalMinutes()
        manualTrigger.tryEmit(Unit)
    }

    /** The id of the editable source that produced the calendar with this [platformId] (its
     *  [RawCalendar.accountId]), so `ListCalendars` can group calendars under their account. Null if the
     *  calendar isn't from a currently-known source (resolved from the last enumeration). */
    fun accountIdFor(platformId: String): String? = byPlatformId[platformId]?.accountId

    /** The calendars a to-do can be written to (`calendar.todos`), from the last enumeration. */
    fun todoLists(): List<RawCalendar> = byPlatformId.values.filter { it.acceptsTodos }

    override suspend fun getCalendars(): List<CalendarEntity> {
        onRefresh?.invoke()
        if (!isEnabled()) {
            // Disabled at runtime: expose nothing so the syncer drops every calendar (and its pins).
            if (byPlatformId.isNotEmpty()) { byPlatformId = emptyMap(); _calendarsChanged.tryEmit(Unit) }
            eventCache.clear()
            log.infoWhenChanged(summaries, "calendars", "Calendar sync disabled (calendar.enabled=false) — no calendars exposed")
            return emptyList()
        }
        val raws = sources().flatMap { src ->
            try {
                src.calendars()
            } catch (e: Exception) {
                log.warn { "Calendar source failed to enumerate: ${e.message}" }
                emptyList()
            }
        }
        // De-dupe by platformId (a discovered file might also be listed explicitly) and cache for
        // getCalendarEvents to resolve back to the right source. Poke the client when the SET changed.
        val unique = raws.associateBy { it.platformId }
        if (unique.keys != byPlatformId.keys) _calendarsChanged.tryEmit(Unit)
        byPlatformId = unique
        eventCache.keys.retainAll(unique.keys) // a removed calendar's events go with it
        log.infoWhenChanged(summaries, "calendars",
            "Calendar: ${unique.size} calendar(s)${if (unique.isEmpty()) "" else " [" + unique.values.joinToString { it.name } + "]"}")
        return unique.values.map { rc ->
            CalendarEntity(
                platformId = rc.platformId,
                name = rc.name,
                ownerName = "",
                ownerId = "",
                color = 0,            // no Linux calendar source exposes a usable colour; leave unset
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
        if (!isEnabled()) return emptyList()
        val rc = byPlatformId[calendar.platformId]
            ?: run { getCalendars(); byPlatformId[calendar.platformId] }
            ?: return emptyList()
        // The one cache of a calendar's events. The calendar plugin asks every minute for the next few days;
        // a remote calendar (CalDAV, iCal URL) would make that a network round trip a minute, and every
        // tick or vdirsyncer run would re-parse unchanged local files. Answer from the last fetch while its
        // window covers the request and nothing changed: no change signal since (tick, file change, manual
        // sync), or its content fingerprint is the same as when it was fetched. The parse also depends on
        // the host zone (floating times, all-day alarms), which no change signal follows: a new zone misses.
        val gen = generation.get()
        val zone = ZoneId.systemDefault()
        val cached = eventCache[calendar.platformId]
            ?.takeIf { it.zone == zone && it.start <= startDate && it.end >= endDate }
        // Taken BEFORE the fetch, so a change racing it leaves a fingerprint older than the events: the next
        // sync re-reads once, never keeps stale events.
        val fingerprint = if (cached != null && cached.generation == gen) null
            else rc.fingerprint?.invoke()
        if (cached != null && (cached.generation == gen || (fingerprint != null && fingerprint == cached.fingerprint))) {
            if (cached.generation != gen) {
                // A sync round over unchanged content: still a sync (GetSyncStatus), without the parse.
                eventCache[calendar.platformId] = CachedEvents(gen, zone, fingerprint, cached.start, cached.end, cached.events)
                onSync?.invoke()
            }
            return cached.events.filter { it.endTime > startDate && it.startTime < endDate }
        }
        // Fetch a window covering both callers (the pin sync's yesterday … +6 d, the plugin's now … +7 d).
        val now = Clock.System.now()
        val fetchStart = minOf(startDate, now - 1.days)
        val fetchEnd = maxOf(endDate, now + 8.days)
        val all = rc.fetchEvents(fetchStart, fetchEnd) // already defensive: returns empty on failure
        eventCache[calendar.platformId] = CachedEvents(gen, zone, fingerprint, fetchStart, fetchEnd, all)
        val events = all.filter { it.endTime > startDate && it.startTime < endDate }
        onSync?.invoke()
        log.debug { "Calendar '${calendar.name}': ${events.size} event(s) in window $startDate .. $endDate" }
        // TRACE: every event's title, each sync (personal data, and the count above says what changed).
        log.trace {
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

    /** Bumped by every change signal: cached events from before it are stale unless their calendar's
     *  [RawCalendar.fingerprint] says the content is the same. */
    private val generation = java.util.concurrent.atomic.AtomicLong()
    /** One calendar's last fetch: the [generation], host [zone] and content [fingerprint] (null: none, or
     *  unreadable) it was fetched at, its window and its events. */
    private class CachedEvents(
        val generation: Long,
        val zone: ZoneId,
        val fingerprint: Any?,
        val start: Instant,
        val end: Instant,
        val events: List<CalendarEvent>,
    )
    private val eventCache = java.util.concurrent.ConcurrentHashMap<String, CachedEvents>()

    override fun registerForCalendarChanges(): Flow<Unit> =
        merge(ticker(), fileWatch(), manualTrigger).onEach { generation.incrementAndGet() }

    override fun hasPermission(): Boolean = true

    override fun supportsPinActions(): Boolean = false // no write-back (no CalDAV PUT / RSVP) yet

    // Read-only for the same reason: creating an event would need a CalDAV PUT or an .ics write (upstream's
    // only caller is the Core app's assistant). Null = "creation failed" / no default calendar.
    override suspend fun createEvent(calendarId: String, event: NewCalendarEvent): String? = null

    override suspend fun defaultCalendarPlatformId(): String? = null

    /** Periodic re-sync. Delays first — PhoneCalendarSyncer.init() already does an immediate sync.
     *  Keyed on [tickInterval], so changing `calendar.sync_interval` cancels the pending delay and
     *  starts counting down the new one rather than serving out the old interval first.
     *  Wall-clock based (see [delayWallClock]): monotonic `delay()` stops while the machine is suspended. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun ticker(): Flow<Unit> = tickInterval.flatMapLatest { minutes ->
        flow {
            while (true) {
                delayWallClock(minutes.minutes, wakeups)
                emit(Unit)
            }
        }
    }

    /** Emit when a `.ics` under any watched directory changes. Best-effort: registration errors just
     *  disable file-watching (the ticker still covers refresh). Blocks in take() (cancellation interrupts
     *  it) rather than polling, so an idle calendar costs no wakeups. A change to a file whose sync lock
     *  ([syncLocks]) a sync tool holds emits once that sync has ended, once for everything it wrote:
     *  vdirsyncer rewrites the files one collection at a time, seconds apart, which made one re-read per
     *  collection. A file no held lock covers emits at once, sync or not. */
    private fun fileWatch(): Flow<Unit> = channelFlow {
        val dirs = watchDirs().filter { it.isDirectory }
        if (dirs.isEmpty()) return@channelFlow
        val ws = FileSystems.getDefault().newWatchService()
        // A held lock -> the wait that emits when its sync ends; a change under it meanwhile adds nothing.
        val syncWaits = mutableMapOf<File, Job>()
        try {
            dirs.forEach { dir ->
                try {
                    dir.toPath().register(ws, ENTRY_CREATE, ENTRY_MODIFY, ENTRY_DELETE)
                } catch (e: Exception) {
                    log.debug { "Cannot watch $dir for calendar changes: ${e.message}" }
                }
            }
            while (isActive) {
                val key = try {
                    runInterruptible { ws.take() }
                } catch (_: ClosedWatchServiceException) {
                    return@channelFlow
                }
                val dir = key.watchable() as? Path
                val changed = key.pollEvents().mapNotNull { ev ->
                    (ev.context() as? Path)?.takeIf { it.toString().endsWith(".ics", ignoreCase = true) }
                        ?.let { (dir?.resolve(it) ?: it).toFile() }
                }
                key.reset()
                if (changed.isEmpty()) continue
                val held = heldSyncLocks(changed)
                // Locked and unlocked files in one batch: the unlocked ones are read now, the sync's after it.
                if (held.size < changed.size) send(Unit)
                for (lock in held.toSet()) {
                    if (syncWaits[lock]?.isActive == true) continue
                    log.debug { "Calendar files changed while $lock is held: re-reading once the sync is done" }
                    syncWaits[lock] = launch {
                        if (!Flock.awaitFree(lock, SYNC_LOCK_WAIT)) log.warn { "$lock still held after $SYNC_LOCK_WAIT: re-reading the calendars anyway" }
                        send(Unit)
                    }
                }
            }
        } finally {
            runCatching { ws.close() }
        }
    }.flowOn(Dispatchers.IO)

    /** The held sync lock of each of the [changed] files that has one (repeats included, so a count below
     *  `changed.size` means some file has none held). */
    private fun heldSyncLocks(changed: List<File>): List<File> {
        val locks = syncLocks()
        if (locks.isEmpty()) return emptyList()
        val held = mutableMapOf<File, Boolean>()
        return changed.mapNotNull { file ->
            locks[file.absoluteFile.normalize()]?.takeIf { lock ->
                held.getOrPut(lock) {
                    try {
                        Flock.isHeld(lock)
                    } catch (e: Throwable) {
                        log.debug { "Can't check the sync lock $lock: ${e.message}" }
                        false
                    }
                }
            }
        }
    }

    private companion object {
        /** dav-sync gives vdirsyncer 300 s, after a guard step of its own. */
        val SYNC_LOCK_WAIT = 6.minutes
    }
}
