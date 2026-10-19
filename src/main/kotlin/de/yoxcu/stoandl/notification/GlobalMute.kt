package de.yoxcu.stoandl.notification

import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.time.Clock
import kotlin.time.Instant

private val log = KotlinLogging.logger {}

/**
 * "Mute all for 30 min / 1 h / today": one mute over every app, until a time, kept apart from the per-app
 * mutes. It used to be written into every app's own mute, so resuming erased permanent and weekday mutes,
 * and an app that first notified during it wasn't muted at all. The forward path checks [mutedUntil] next
 * to the master switch (`notification.forward`), which is the indefinite form of the same thing: this is
 * only ever temporary.
 *
 * Persisted as epoch milliseconds in `<configDir>/notification-muted-until`, so a restart inside the mute
 * stays muted; resuming (or the time passing) makes it inert, and a resume deletes the file.
 */
class GlobalMute(
    private val file: File,
    private val clock: () -> Instant = { Clock.System.now() },
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
) {
    @Volatile private var until: Instant? = load()

    private fun load(): Instant? = runCatching {
        file.takeIf { it.isFile }?.readText()?.trim()?.toLongOrNull()?.let { Instant.fromEpochMilliseconds(it) }
    }.getOrNull()

    /** When the mute ends, or null while nothing is muted (also once that time has passed). */
    fun mutedUntil(now: Instant = clock()): Instant? = until?.takeIf { it > now }

    /**
     * `NotifSetMuteAll`: [spec] is a duration (`30m`, `1h`, `2d`), `today` (until the next local midnight)
     * or `never` (resume). Returns `ok:<what happened>` or `error:<why>`; `always`, `weekdays` and
     * `weekends` are per-app states, so they are refused with a pointer to the right control.
     */
    @Synchronized
    fun set(spec: String): String {
        val now = clock()
        val s = spec.trim().lowercase()
        if (s in RESUME_WORDS) {
            val was = mutedUntil(now)
            persist(null)
            return if (was != null) "ok:Notifications resumed" else "ok:Notifications weren't muted"
        }
        if (s in PER_APP_WORDS) {
            return "error:Muting all apps is temporary: give a time (30m, 1h, 2d or today). To stop " +
                "forwarding until you turn it back on, set notification.forward to false"
        }
        val end = when (s) {
            "today" -> Instant.fromEpochMilliseconds(
                now.zoned().toLocalDate().plusDays(1).atStartOfDay(zone()).toInstant().toEpochMilli(),
            )
            else -> NotificationAppsControl.parseDuration(s)?.let { now + it }
        } ?: return "error:Unknown mute '$spec' (use a time like 30m, 1h, 2d, or today, or never to resume)"
        persist(end)
        log.info { "All notifications muted until $end" }
        return "ok:Muted all notifications until ${describe(end, now)}"
    }

    /** [end] as the user reads it: the local time, with the day when it isn't today. */
    internal fun describe(end: Instant, now: Instant = clock()): String {
        val e = end.zoned()
        val n = now.zoned()
        val pattern = if (e.toLocalDate() == n.toLocalDate()) "HH:mm" else "EEE d MMM HH:mm"
        return e.format(DateTimeFormatter.ofPattern(pattern, Locale.ENGLISH))
    }

    private fun Instant.zoned(): ZonedDateTime =
        ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(toEpochMilliseconds()), zone())

    /** The file is written before the change shows, so a failed write changes nothing. */
    private fun persist(next: Instant?) {
        if (next == null) {
            file.delete()
        } else {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText("${next.toEpochMilliseconds()}\n")
            try {
                Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (e: Exception) {
                Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        }
        until = next
    }

    private companion object {
        val RESUME_WORDS = setOf("never", "off", "unmute", "resume", "")
        val PER_APP_WORDS = setOf("always", "on", "mute", "weekdays", "weekends")
    }
}
