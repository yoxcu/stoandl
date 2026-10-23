package de.yoxcu.stoandl.calendar

import de.yoxcu.stoandl.config.StoandlConfig
import de.yoxcu.stoandl.secret.SecretStore
import de.yoxcu.stoandl.util.Flock
import de.yoxcu.stoandl.util.LenientJson
import de.yoxcu.stoandl.util.writeTextAtomic
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.isSuccess
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.UUID
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

private val log = KotlinLogging.logger {}

/** When a to-do is due: a moment, or a whole day. */
sealed interface TodoDue {
    data class At(val time: Instant) : TodoDue
    data class Day(val date: LocalDate) : TodoDue

    /** As the log shows it: `2026-10-22T20:00+02:00` (a moment, in [zone]) or `2026-10-22, all day`. */
    fun describe(zone: ZoneId): String = when (this) {
        is At -> OffsetDateTime.ofInstant(java.time.Instant.ofEpochSecond(time.epochSeconds), zone).toString()
        is Day -> "$date, all day"
    }

    companion object {
        /** `2026-10-22T17:00` (local time in [zone]), the same with an offset or `Z`, or `2026-10-22`.
         *  Null when [s] is none of these. */
        fun parse(s: String, zone: ZoneId): TodoDue? {
            val t = s.trim()
            runCatching { return Day(LocalDate.parse(t)) }
            runCatching { return At(Instant.fromEpochSeconds(OffsetDateTime.parse(t).toEpochSecond())) }
            runCatching { return At(Instant.fromEpochSeconds(LocalDateTime.parse(t).atZone(zone).toEpochSecond())) }
            return null
        }
    }
}

/** A to-do to write: a [title], and optionally when it is [due] and a [note]. */
data class NewTodo(val title: String, val due: TodoDue? = null, val note: String = "")

/**
 * The iCalendar (RFC 5545) text of a to-do. A due to-do gets a display alarm: at the due time, or at
 * [DAY_ALARM] local time on a due day. Times are written in UTC, so no VTIMEZONE is needed.
 */
object TodoIcs {
    private val DAY_ALARM: LocalTime = LocalTime.of(9, 0)
    private val UTC_STAMP = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
    private val DATE = DateTimeFormatter.ofPattern("yyyyMMdd")

    /** The VTODO component, CRLF lines, folded. */
    fun vtodo(todo: NewTodo, uid: String, now: Instant, zone: ZoneId): String {
        val lines = buildList {
            add("BEGIN:VTODO")
            add("UID:$uid")
            add("DTSTAMP:${utc(now)}")
            add("CREATED:${utc(now)}")
            add("SUMMARY:${escape(todo.title)}")
            if (todo.note.isNotBlank()) add("DESCRIPTION:${escape(todo.note)}")
            add("STATUS:NEEDS-ACTION")
            val alarm = when (val due = todo.due) {
                is TodoDue.At -> { add("DUE:${utc(due.time)}"); due.time }
                is TodoDue.Day -> {
                    add("DUE;VALUE=DATE:${DATE.format(due.date)}")
                    Instant.fromEpochSeconds(due.date.atTime(DAY_ALARM).atZone(zone).toEpochSecond())
                }
                null -> null
            }
            if (alarm != null) {
                add("BEGIN:VALARM")
                add("ACTION:DISPLAY")
                add("DESCRIPTION:${escape(todo.title)}")
                add("TRIGGER;VALUE=DATE-TIME:${utc(alarm)}")
                add("END:VALARM")
            }
            add("END:VTODO")
        }
        return lines.joinToString("") { fold(it) + "\r\n" }
    }

    private const val CALENDAR_HEAD = "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//stoandl//todos//EN\r\n"
    private const val CALENDAR_TAIL = "END:VCALENDAR\r\n"

    /** A whole calendar holding just [vtodo]. */
    fun calendar(vtodo: String): String = CALENDAR_HEAD + vtodo + CALENDAR_TAIL

    /** The VTODO a [calendar] holds: its inverse. */
    fun vtodoOf(calendar: String): String = calendar.removePrefix(CALENDAR_HEAD).removeSuffix(CALENDAR_TAIL)

    /** [existing] calendar text with [vtodo] added before its last `END:VCALENDAR`, in the file's own line
     *  endings; a whole new calendar when there is none. */
    fun insert(existing: String, vtodo: String): String {
        val end = existing.lastIndexOf("END:VCALENDAR")
        if (end < 0) return calendar(vtodo)
        val body = if ("\r\n" in existing) vtodo else vtodo.replace("\r\n", "\n")
        val head = existing.substring(0, end)
        val sep = if (head.isEmpty() || head.endsWith("\n")) "" else if ("\r\n" in existing) "\r\n" else "\n"
        return head + sep + body + existing.substring(end)
    }

    private fun utc(t: Instant): String =
        UTC_STAMP.format(java.time.Instant.ofEpochSecond(t.epochSeconds).atOffset(ZoneOffset.UTC))

    internal fun escape(s: String): String = s.trim()
        .replace("\\", "\\\\").replace(";", "\\;").replace(",", "\\,")
        .replace("\r\n", "\\n").replace("\n", "\\n").replace("\r", "\\n")

    /** RFC 5545 §3.1: lines longer than 75 octets continue on lines starting with a space, never splitting
     *  a UTF-8 sequence. */
    internal fun fold(line: String): String {
        val out = StringBuilder()
        var octets = 0
        var limit = 75
        var i = 0
        while (i < line.length) {
            val cp = line.codePointAt(i)
            val n = String(Character.toChars(cp)).toByteArray().size
            if (octets + n > limit) {
                out.append("\r\n ")
                octets = 0
                limit = 74
            }
            out.appendCodePoint(cp)
            octets += n
            i += Character.charCount(cp)
        }
        return out.toString()
    }
}

/**
 * Writes to-dos to the list `calendar.todos` names: a CalDAV collection of an account in
 * `calendar.caldav` (whose sign-in it uses), a local `.ics` file (the to-do is added to it), or a
 * directory (a vdir: one `<uid>.ics` per to-do). A CalDAV write that fails is kept in [queueDir] and
 * retried by [flush], which the daemon runs after every calendar sync, so a to-do dictated without a
 * network reaches the server later.
 *
 * A local file that a sync tool rewrites (its Calindori entry names a `lockFile`, as the op6's dav-sync
 * running vdirsyncer does) is written under that tool's flock, as Calindori saves it: a to-do written in
 * the middle of a sync would be lost when the tool puts its copy back. While a sync holds the lock longer
 * than [LOCK_WAIT] the to-do is queued like an unreachable server's and written by a [flush] as soon as the
 * sync lets the lock go (waited for on [scope], up to [SYNC_WAIT]; after that, or when a new sync took the
 * lock first, by the next calendar sync's flush).
 */
class TodoWriter(
    /** `calendar.todos`, read live: a collection URL or a local path; empty = off. */
    private val target: () -> String,
    private val accounts: () -> List<StoandlConfig.CalDavAccount>,
    /** The source of a discovered calendar (`caldav:<token>`), from the last calendar sync. */
    private val accountIdFor: (String) -> String?,
    private val secretStore: SecretStore,
    private val client: HttpClient,
    private val queueDir: File,
    /** Runs the wait that writes a to-do queued for a sync once that sync is over. */
    private val scope: CoroutineScope,
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
    private val now: () -> Instant = { Clock.System.now() },
    /** The lock a sync tool holds while it rewrites a local file (Calindori's `lockFile`), or null. */
    private val lockFileFor: (File) -> File? = ::calindoriLockFile,
    private val lockWait: Duration = LOCK_WAIT,
    private val syncWait: Duration = SYNC_WAIT,
) {
    sealed interface Result {
        data object Saved : Result
        /** Kept for a retry: [problem] is why the server refused it, empty when it couldn't be reached
         *  ([SYNC_RUNNING] when a sync holds the local file). */
        data class Queued(val problem: String) : Result
        data class Failed(val message: String) : Result
        data object Off : Result
    }

    /** A queued to-do, as listed by [pending]. */
    @Serializable
    data class Pending(val uid: String, val title: String, val target: String, val ics: String, val lastError: String = "")

    private val lock = Mutex()
    private val json = Json { prettyPrint = false }
    /** The wait for a sync's lock to be let go, then a [flush]; guarded by [lock]. */
    private var afterSync: Job? = null

    suspend fun add(todo: NewTodo): Result {
        val to = target().trim()
        if (to.isEmpty()) return Result.Off
        if (todo.title.isBlank()) return Result.Failed("empty title")
        val uid = "${UUID.randomUUID()}@stoandl"
        val ics = TodoIcs.calendar(TodoIcs.vtodo(todo, uid, now(), zone()))
        return lock.withLock {
            if (!isUrl(to)) {
                val file = File(to)
                val syncLock = syncLockOf(file)
                return@withLock writeLocal(file, syncLock, uid, ics, lockWait) ?: run {
                    enqueue(Pending(uid, todo.title.trim(), to, ics, SYNC_RUNNING))
                    if (syncLock != null) flushAfterSync(syncLock)
                    Result.Queued(SYNC_RUNNING)
                }
            }
            flushLocked()
            when (val put = put(to, uid, ics)) {
                null -> Result.Saved
                is PutFailure.Refused -> Result.Failed(put.message)
                is PutFailure.Retry -> {
                    enqueue(Pending(uid, todo.title.trim(), to, ics, put.message))
                    Result.Queued(if (put.offline) "" else put.message)
                }
            }
        }
    }

    /** Send what the queue holds, oldest first; stops at the first one that can't reach its server. */
    suspend fun flush() = lock.withLock { flushLocked() }

    fun pending(): List<Pending> = queued().map { it.second }

    private suspend fun flushLocked() {
        for ((file, p) in queued()) {
            if (!isUrl(p.target)) {
                // Queued while a sync held the file: still held, try again after the next sync.
                val target = File(p.target)
                val written = writeLocal(target, syncLockOf(target), p.uid, p.ics, Duration.ZERO) ?: continue
                file.delete()
                if (written is Result.Failed) log.warn { "Dropped queued to-do '${p.title}': ${written.message}" }
                else log.info { "Queued to-do '${p.title}' saved to ${p.target}" }
                continue
            }
            when (val put = put(p.target, p.uid, p.ics)) {
                null -> { file.delete(); log.info { "Queued to-do '${p.title}' saved to ${p.target}" } }
                is PutFailure.Refused -> {
                    // Its list is gone or won't take it: retrying can't help, and the user has the log.
                    file.delete(); log.warn { "Dropped queued to-do '${p.title}': ${put.message}" }
                }
                is PutFailure.Retry -> {
                    if (put.message != p.lastError) save(file, p.copy(lastError = put.message))
                    if (put.offline) return
                }
            }
        }
    }

    private fun queued(): List<Pair<File, Pending>> =
        (queueDir.listFiles { f -> f.isFile && f.name.endsWith(".json") } ?: emptyArray())
            .sortedBy { it.lastModified() }
            .mapNotNull { f -> runCatching { f to LenientJson.decodeFromString(Pending.serializer(), f.readText()) }.getOrNull() }

    private fun enqueue(p: Pending) {
        queueDir.mkdirs()
        save(File(queueDir, p.uid.substringBefore('@') + ".json"), p)
        log.info { "To-do '${p.title}' queued for ${p.target}: ${p.lastError}" }
    }

    private fun save(file: File, p: Pending) = file.writeTextAtomic(json.encodeToString(Pending.serializer(), p))

    /** The sync lock of a local target: none for a vdir (a sync writes other files than the new one). */
    private suspend fun syncLockOf(file: File): File? =
        withContext(Dispatchers.IO) { if (file.isDirectory) null else lockFileFor(file) }

    /** Once [syncLock] is let go, [flush] what the sync kept back: the retry needs no calendar sync, which a
     *  sync that wrote nothing (offline, say) or a target outside the watched folders wouldn't start before
     *  the next periodic one. One wait at a time; a later to-do is written by the same flush. */
    private fun flushAfterSync(syncLock: File) {
        if (afterSync?.isActive == true) return
        afterSync = scope.launch {
            if (!Flock.awaitFree(syncLock, syncWait)) log.debug { "$syncLock still held after $syncWait: the next calendar sync writes the queued to-dos" }
            else flush()
        }
    }

    /** Write [ics] (a calendar holding the one to-do) to a local [file]: a new `<uid>.ics` in a folder, or its
     *  VTODO added to a file, under its [syncLock] if it has one (waiting up to [lockWait]). Null when a sync
     *  still holds that lock: nothing was written. */
    private suspend fun writeLocal(file: File, syncLock: File?, uid: String, ics: String, lockWait: Duration): Result? = withContext(Dispatchers.IO) {
        try {
            val held = syncLock?.let { Flock.acquire(it, lockWait) ?: return@withContext null }
            held.use {
                when {
                    file.isDirectory -> File(file, "${uid.substringBefore('@')}.ics").writeTextAtomic(ics)
                    file.parentFile?.isDirectory == true ->
                        file.writeTextAtomic(if (file.isFile) TodoIcs.insert(file.readText(), TodoIcs.vtodoOf(ics)) else ics)
                    else -> return@withContext Result.Failed("no such folder: ${file.parent}")
                }
            }
            Result.Saved
        } catch (e: Exception) {
            log.warn { "Can't write the to-do to ${file.path}: ${e.message}" }
            Result.Failed("can't write ${file.name}: ${e.message ?: e::class.simpleName}")
        }
    }

    private sealed interface PutFailure {
        /** Worth retrying: [offline] when the server wasn't reached at all. */
        data class Retry(val message: String, val offline: Boolean) : PutFailure
        data class Refused(val message: String) : PutFailure
    }

    /** PUT one to-do into [collection]; null when it is stored. */
    private suspend fun put(collection: String, uid: String, ics: String): PutFailure? {
        val account = caldavAccountFor(collection, accounts(), accountIdFor)
            ?: return PutFailure.Refused("no CalDAV account for ${hostOf(collection)}: add it under Calendar")
        val password = withContext(Dispatchers.IO) { secretStore.get(SourceId.caldav(account.id)) } ?: ""
        val url = collection.trimEnd('/') + "/" + uid.substringBefore('@') + ".ics"
        return try {
            val resp = client.request(url) {
                method = HttpMethod.Put
                if (account.username.isNotBlank()) {
                    header(HttpHeaders.Authorization,
                        "Basic " + Base64.getEncoder().encodeToString("${account.username}:$password".toByteArray()))
                }
                header(HttpHeaders.IfNoneMatch, "*")
                header(HttpHeaders.ContentType, "text/calendar; charset=utf-8")
                setBody(ics)
            }
            val status = resp.status.value
            when {
                resp.status.isSuccess() -> null
                // The list was removed, or doesn't take to-dos (403/415 with a CalDAV precondition).
                status == 404 || status == 410 || status == 415 -> PutFailure.Refused(httpProblem(status))
                else -> PutFailure.Retry(httpProblem(status), offline = false)
            }
        } catch (e: Exception) {
            PutFailure.Retry(unreachable(e), offline = true)
        }
    }
}

private fun isUrl(s: String) = s.startsWith("https://") || s.startsWith("http://")

/** How long a to-do waits for a sync that holds its local file: under the assistant's 30 s wait for the
 *  answer, and as long as dav-sync waits for Calindori's save. A typical sync is over by then; a longer one
 *  queues the to-do. */
private val LOCK_WAIT: Duration = 10.seconds

/** How long a to-do queued for a sync waits for that sync to end before it is left to the next calendar
 *  sync: dav-sync gives vdirsyncer 300 s, after a guard step of its own. */
private val SYNC_WAIT: Duration = 6.minutes

/** The problem a to-do queued for a sync that holds its local file reports. */
internal const val SYNC_RUNNING = "the calendar is being synchronized"

private fun hostOf(url: String): String = runCatching { java.net.URI(url).host }.getOrNull() ?: url

/** The CalDAV account [collection] belongs to: the one the last sync found it under, else the account
 *  whose URL it is under, else the only account on its host. */
internal fun caldavAccountFor(
    collection: String,
    accounts: List<StoandlConfig.CalDavAccount>,
    accountIdFor: (String) -> String?,
): StoandlConfig.CalDavAccount? {
    accountIdFor(collection)?.let { id -> accounts.firstOrNull { SourceId.caldav(it.id) == id }?.let { return it } }
    accounts.firstOrNull { collection.startsWith(it.url.trimEnd('/')) }?.let { return it }
    return accounts.filter { hostOf(it.url).equals(hostOf(collection), ignoreCase = true) }.singleOrNull()
}
