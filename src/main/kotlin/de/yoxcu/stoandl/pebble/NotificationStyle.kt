package de.yoxcu.stoandl.pebble

import io.rebble.libpebblecommon.notification.DefaultVibePattern
import io.rebble.libpebblecommon.notification.uIntPattern
import io.rebble.libpebblecommon.packets.blobdb.TimelineIcon
import io.rebble.libpebblecommon.timeline.TimelineColor

// Per-app notification styling (set via `stoandl notif style`, the GUI or an extension's `notify`), applied
// to the outgoing notification at send time. One vocabulary for all of them: libpebble3's
// [DefaultVibePattern] for vibes, [TimelineIcon] and [TimelineColor] by enum name.

/** A built-in vibe by display or enum name, any case ("Ohhh, Oh", "ohhhoh"). */
internal fun defaultVibeOf(name: String): DefaultVibePattern? = name.trim().let { n ->
    DefaultVibePattern.entries.firstOrNull { it.displayName.equals(n, ignoreCase = true) || it.name.equals(n, ignoreCase = true) }
}

/** A raw CSV of on/off millisecond durations (`100,50,100`), or null when [spec] isn't one. */
private fun vibeCsv(spec: String): List<UInt>? =
    spec.split(',').map { it.trim().toUIntOrNull() ?: return null }.takeIf { it.isNotEmpty() }

/** The user's own named vibes (libpebble3's `VibePatternEntity` rows with `bundled = false`): name to on/off ms. */
typealias CustomVibes = Map<String, List<UInt>>

private fun customVibeOf(name: String, custom: CustomVibes): Map.Entry<String, List<UInt>>? =
    name.trim().let { n -> custom.entries.firstOrNull { it.key.equals(n, ignoreCase = true) } }

/** The pattern for a built-in vibe name, a [custom] one or a raw CSV; null (the watch's default) for anything else. */
internal fun perAppVibe(spec: String?, custom: CustomVibes = emptyMap()): List<UInt>? =
    spec?.let { defaultVibeOf(it)?.uIntPattern() ?: customVibeOf(it, custom)?.value ?: vibeCsv(it) }

/** A [TimelineIcon] by enum name (any case) or `system://images/…` code. */
internal fun timelineIconOf(code: String): TimelineIcon? =
    TimelineIcon.entries.firstOrNull { it.name.equals(code.trim(), ignoreCase = true) } ?: TimelineIcon.fromCode(code.trim())

internal fun perAppColor(name: String?): TimelineColor? = name?.let { TimelineColor.findByName(it) }

/**
 * The stored form of a style value, or null when the watch can't show it. Vibes are stored in their
 * display spelling (libpebble3's convention for `vibePatternName`), icons and colours by enum name.
 */
internal fun normalizeVibe(spec: String, custom: CustomVibes = emptyMap()): String? =
    defaultVibeOf(spec)?.displayName ?: customVibeOf(spec, custom)?.key ?: vibeCsv(spec)?.joinToString(",")
internal fun normalizeIcon(code: String): String? = timelineIconOf(code)?.name
internal fun normalizeColor(name: String): String? = TimelineColor.findByName(name.trim())?.name

/** The icons a picker offers (the CLI lists every [TimelineIcon]): label to icon. */
internal val ICON_CHOICES: List<Pair<String, TimelineIcon>> = listOf(
    "Generic" to TimelineIcon.NotificationGeneric,
    "Chat" to TimelineIcon.GenericSms,
    "Email" to TimelineIcon.GenericEmail,
    "Calendar" to TimelineIcon.TimelineCalendar,
    "Reminder" to TimelineIcon.NotificationReminder,
    "Alarm" to TimelineIcon.AlarmClock,
    "Phone" to TimelineIcon.IncomingPhoneCall,
    "Warning" to TimelineIcon.GenericWarning,
    "Flag" to TimelineIcon.NotificationFlag,
    "Music" to TimelineIcon.MusicEvent,
    "News" to TimelineIcon.NewsEvent,
    "Settings" to TimelineIcon.Settings,
)

/**
 * `NotifStyleChoices` records: `vibe \t value \t label \t ms,ms,… \t builtin|custom` (the built-ins,
 * then the [custom] ones by name) and `icon \t value \t label`, value being what `NotifSetStyle` stores.
 */
internal fun notifStyleChoices(custom: CustomVibes = emptyMap()): List<String> =
    DefaultVibePattern.entries.map { "vibe\t${it.displayName}\t${it.displayName}\t${it.pattern.joinToString(",")}\tbuiltin" } +
        custom.entries.sortedBy { it.key.lowercase() }.map { (name, ms) -> "vibe\t$name\t$name\t${ms.joinToString(",")}\tcustom" } +
        ICON_CHOICES.map { (label, icon) -> "icon\t${icon.name}\t$label" }

/** Limits for a custom pattern: on/off segments (the longest built-in has 23), total run time, name length. */
internal const val MAX_CUSTOM_VIBE_SEGMENTS = 64
internal const val MAX_CUSTOM_VIBE_TOTAL_MS = 10_000L
internal const val MAX_CUSTOM_VIBE_NAME = 40

/** A custom pattern ready to store, or why not. */
internal sealed interface CustomVibeCheck {
    data class Ok(val name: String, val pattern: List<UInt>) : CustomVibeCheck
    data class Refused(val reason: String) : CustomVibeCheck
}

/**
 * Check a custom vibe named [name] with the on/off ms [csv]. The name must not shadow a built-in, read as
 * a CSV, or be one of the reset words `NotifSetStyle` takes; it lands in tab-separated records, so no
 * control characters either.
 */
internal fun checkCustomVibe(name: String, csv: String): CustomVibeCheck {
    val n = name.trim()
    val refused = when {
        n.isEmpty() -> "A vibration needs a name"
        n.length > MAX_CUSTOM_VIBE_NAME -> "Name too long (at most $MAX_CUSTOM_VIBE_NAME characters)"
        n.any { it.isISOControl() } -> "Name can't contain tabs or line breaks"
        defaultVibeOf(n) != null -> "'$n' is a built-in vibration"
        vibeCsv(n) != null -> "A name can't be a list of numbers"
        n.lowercase() in STYLE_RESET_WORDS -> "'$n' is reserved (it resets a style)"
        else -> null
    }
    if (refused != null) return CustomVibeCheck.Refused(refused)
    val ms = vibeCsv(csv) ?: return CustomVibeCheck.Refused("Pattern must be on/off milliseconds, e.g. 100,50,100")
    return when {
        ms.size > MAX_CUSTOM_VIBE_SEGMENTS -> CustomVibeCheck.Refused("Too many segments (at most $MAX_CUSTOM_VIBE_SEGMENTS)")
        ms.sumOf { it.toLong() } > MAX_CUSTOM_VIBE_TOTAL_MS -> CustomVibeCheck.Refused("Pattern too long (at most ${MAX_CUSTOM_VIBE_TOTAL_MS / 1000} s in total)")
        // On/off alternating, starting with on: only the even segments vibrate.
        ms.filterIndexed { i, _ -> i % 2 == 0 }.none { it > 0u } -> CustomVibeCheck.Refused("Pattern never vibrates (use the built-in 'No vibration')")
        else -> CustomVibeCheck.Ok(n, ms)
    }
}

/** The words `NotifSetStyle` takes to reset a style to the default. */
internal val STYLE_RESET_WORDS = setOf("default", "none", "clear")
