package de.yoxcu.stoandl.pebble

import io.rebble.libpebblecommon.database.dao.VibePatternDao
import io.rebble.libpebblecommon.database.entity.TimelineNotification
import io.rebble.libpebblecommon.database.entity.VibePatternEntity
import io.rebble.libpebblecommon.database.entity.buildTimelineNotification
import io.rebble.libpebblecommon.packets.blobdb.TimelineIcon
import kotlinx.coroutines.flow.first
import kotlin.time.Clock
import kotlin.uuid.Uuid

/**
 * The user's named vibes, kept in libpebble3's [VibePatternDao] next to the built-ins it seeds there
 * (the official app's store, which `calendar.reminder_vibe` resolves against too). Status-prefixed
 * returns like the other controls (`ok:` / `notfound:` / `error:`).
 */
class VibePatterns(private val dao: VibePatternDao) {

    suspend fun custom(): CustomVibes =
        dao.getVibePatterns().first().filterNot { it.bundled }.associate { it.name to it.pattern }

    /** Add or replace the custom vibe [name] (any case replaces it, taking the new spelling); an empty
     *  [pattern] removes it. */
    suspend fun set(name: String, pattern: String): String {
        if (pattern.isBlank()) return remove(name)
        val ok = when (val check = checkCustomVibe(name, pattern)) {
            is CustomVibeCheck.Refused -> return "error:${check.reason}"
            is CustomVibeCheck.Ok -> check
        }
        val rows = dao.getVibePatterns().first()
        // A bundled row the current DefaultVibePattern no longer has (an older libpebble3 seeded it) is
        // still the watch's, not the user's.
        if (rows.any { it.bundled && it.name.equals(ok.name, ignoreCase = true) }) return "error:'${ok.name}' is a built-in vibration"
        val existing = rows.firstOrNull { !it.bundled && it.name.equals(ok.name, ignoreCase = true) }
        if (existing != null && existing.name != ok.name) dao.deleteCustomPattern(existing.name)
        dao.insertOrUpdate(VibePatternEntity(name = ok.name, pattern = ok.pattern, bundled = false))
        return "ok:${if (existing != null) "Updated" else "Added"} vibration '${ok.name}' (${ok.pattern.joinToString(",")} ms)"
    }

    private suspend fun remove(name: String): String {
        val n = name.trim()
        val existing = custom().keys.firstOrNull { it.equals(n, ignoreCase = true) }
            ?: return if (defaultVibeOf(n) != null) "error:'$n' is built in and can't be removed"
            else "notfound:No custom vibration named '$n'"
        dao.deleteCustomPattern(existing)
        return "ok:Removed vibration '$existing' (apps still set to it buzz the watch's default)"
    }
}

/**
 * The preview the official app sends for a vibe: a plain notification carrying only [pattern] (null =
 * the watch's default vibe). It skips the notification pipeline, so no mute, filter, forwarding switch
 * or per-app style changes what buzzes.
 */
internal fun vibeTestNotification(label: String, pattern: List<UInt>?): TimelineNotification =
    buildTimelineNotification(parentId = Uuid.NIL, timestamp = Clock.System.now()) {
        attributes {
            title { "Vibration test" }
            body { label }
            tinyIcon { TimelineIcon.RadioShow }
            pattern?.let { p -> vibrationPattern { p } }
        }
    }
