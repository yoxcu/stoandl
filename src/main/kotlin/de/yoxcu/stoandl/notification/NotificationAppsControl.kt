package de.yoxcu.stoandl.notification

import io.github.oshai.kotlinlogging.KotlinLogging
import io.rebble.libpebblecommon.database.asMillisecond
import io.rebble.libpebblecommon.database.dao.NotificationAppRealDao
import io.rebble.libpebblecommon.database.entity.MuteState
import io.rebble.libpebblecommon.database.entity.NotificationAppItem
import kotlinx.coroutines.runBlocking
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

private val log = KotlinLogging.logger {}

/**
 * Per-app notification settings over libpebble3's [NotificationAppItem] store (the same store the
 * daemon lazily fills as desktop apps notify, in `PebbleIntegration`'s notification listener). Lets
 * the CLI list tracked apps and set their mute state — `always`, the day-of-week schedules
 * `weekdays`/`weekends`, a temporary mute (`1h`/`30m`/`2d`), or `never`. Mute is enforced host-side
 * before a notification is sent; when watch sync is on, these same records appear in the watch's
 * Notifications settings menu and wrist toggles write back into this DAO.
 *
 * Methods return a status-prefixed string as the other controls do (`ok:<msg>` / `notfound:<msg>` /
 * `ambiguous:<msg>` / `error:<msg>`). App lookups match a substring of the app name or package
 * (case-insensitive), erroring on zero or multiple matches — the same ergonomics as `repair`.
 */
class NotificationAppsControl(
    private val dao: NotificationAppRealDao,
) {
    /** Each entry tab-separated: `name \t muteLabel \t color \t icon \t vibe \t lastNotifiedEpoch`
     *  (empty string for an unset style field). One row per known app. */
    fun list(): List<String> = try {
        val now = Clock.System.now()
        runBlocking { dao.allApps() }.map { app ->
            listOf(
                app.name,
                muteLabel(app, now),
                app.colorName.orEmpty(),
                app.iconCode.orEmpty(),
                app.vibePatternName.orEmpty(),
                app.lastNotified.instant.epochSeconds.toString(),
            ).joinToString("\t")
        }
    } catch (e: Exception) {
        log.warn(e) { "notif list failed" }
        emptyList()
    }

    fun setMute(query: String, spec: String): String = try {
        val now = Clock.System.now()
        val parsed = parseSpec(spec, now) ?: return "error:Unknown mute '$spec' (use always|weekdays|weekends|never or a duration like 1h/30m/2d)"
        val apps = runBlocking { dao.allApps() }
        val matches = apps.filter { it.matches(query) }
        when {
            matches.isEmpty() -> "notfound:No tracked app matching '$query'"
            matches.size > 1 -> "ambiguous:" + matches.joinToString("; ") { it.name }
            else -> {
                val app = matches.first()
                runBlocking { dao.insertOrReplace(parsed.applyTo(app, now)) }
                "ok:${describe(app.name, parsed)}"
            }
        }
    } catch (e: Exception) {
        log.warn(e) { "notif setMute failed" }
        "error:${e.message ?: "failed"}"
    }

    fun setMuteAll(spec: String): String = try {
        val now = Clock.System.now()
        val parsed = parseSpec(spec, now) ?: return "error:Unknown mute '$spec' (use always|weekdays|weekends|never or a duration like 1h/30m/2d)"
        val apps = runBlocking { dao.allApps() }
        if (apps.isEmpty()) return "ok:No tracked apps yet"
        runBlocking { dao.insertOrReplace(apps.map { parsed.applyTo(it, now) }) }
        "ok:${describe("all ${apps.size} app(s)", parsed)}"
    } catch (e: Exception) {
        log.warn(e) { "notif setMuteAll failed" }
        "error:${e.message ?: "failed"}"
    }

    /**
     * Set a per-app background [color] name, [icon] (a TimelineIcon enum name like
     * `NotificationSlack`), and/or [vibe] (a preset `short`/`long`/`double`/`triple`/`pulse`, or a
     * CSV of on/off ms). Applied to the app's outgoing notifications host-side. For each value: an
     * empty string leaves it unchanged; `default`/`none`/`clear` resets it. Unknown color/icon/vibe
     * values are stored but fall back to the default at send time.
     */
    fun setStyle(query: String, color: String, icon: String, vibe: String): String = try {
        val apps = runBlocking { dao.allApps() }
        val matches = apps.filter { it.matches(query) }
        when {
            matches.isEmpty() -> "notfound:No tracked app matching '$query'"
            matches.size > 1 -> "ambiguous:" + matches.joinToString("; ") { it.name }
            else -> {
                val app = matches.first()
                val newColor = resolveStyle(color, app.colorName)
                val newIcon = resolveStyle(icon, app.iconCode)
                val newVibe = resolveStyle(vibe, app.vibePatternName)
                runBlocking { dao.updateAppState(app.packageName, newVibe, newColor, newIcon) }
                "ok:Updated style for ${app.name} (color=${newColor ?: "default"}, icon=${newIcon ?: "default"}, vibe=${newVibe ?: "default"})"
            }
        }
    } catch (e: Exception) {
        log.warn(e) { "notif setStyle failed" }
        "error:${e.message ?: "failed"}"
    }

    private fun resolveStyle(arg: String, current: String?): String? = when (arg.trim().lowercase()) {
        "" -> current
        "default", "none", "clear" -> null
        else -> arg.trim()
    }

    private fun NotificationAppItem.matches(query: String): Boolean =
        name.contains(query, ignoreCase = true) || packageName.contains(query, ignoreCase = true)

    private data class MuteSpec(val state: MuteState, val expiration: Instant?) {
        fun applyTo(app: NotificationAppItem, now: Instant): NotificationAppItem = app.copy(
            muteState = state,
            muteExpiration = expiration?.asMillisecond(),
            stateUpdated = now.asMillisecond(),
        )
    }

    private fun parseSpec(spec: String, now: Instant): MuteSpec? = when (val s = spec.trim().lowercase()) {
        "", "always", "on", "mute" -> MuteSpec(MuteState.Always, null)
        "never", "off", "unmute" -> MuteSpec(MuteState.Never, null)
        "weekdays" -> MuteSpec(MuteState.Weekdays, null)
        "weekends" -> MuteSpec(MuteState.Weekends, null)
        else -> parseDuration(s)?.let { MuteSpec(MuteState.Always, now + it) }
    }

    private fun describe(target: String, spec: MuteSpec): String = when {
        spec.expiration != null -> "Muted $target until ${spec.expiration} (temporary)"
        spec.state == MuteState.Never -> "Unmuted $target"
        spec.state == MuteState.Always -> "Muted $target"
        else -> "Muted $target on ${spec.state.name.lowercase()}"
    }

    private fun muteLabel(app: NotificationAppItem, now: Instant): String {
        val exp = app.muteExpiration?.instant
        if (exp != null) return if (exp > now) "muted-until $exp" else "never"
        return app.muteState.name.lowercase()
    }

    companion object {
        private val DURATION_RE = Regex("""^(\d+)\s*([smhd])$""")

        /** Parse `90s`/`30m`/`2h`/`1d` into a [Duration]; null if not a recognised duration. */
        internal fun parseDuration(s: String): Duration? {
            val m = DURATION_RE.matchEntire(s.trim().lowercase()) ?: return null
            val n = m.groupValues[1].toLongOrNull() ?: return null
            return when (m.groupValues[2]) {
                "s" -> n.seconds
                "m" -> n.minutes
                "h" -> n.hours
                "d" -> n.days
                else -> null
            }
        }
    }
}
