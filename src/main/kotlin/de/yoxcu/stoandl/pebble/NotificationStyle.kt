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

/** The pattern for a built-in vibe name or a raw CSV; null (the watch's default) for anything else. */
internal fun perAppVibe(spec: String?): List<UInt>? =
    spec?.let { defaultVibeOf(it)?.uIntPattern() ?: vibeCsv(it) }

/** A [TimelineIcon] by enum name (any case) or `system://images/…` code. */
internal fun timelineIconOf(code: String): TimelineIcon? =
    TimelineIcon.entries.firstOrNull { it.name.equals(code.trim(), ignoreCase = true) } ?: TimelineIcon.fromCode(code.trim())

internal fun perAppColor(name: String?): TimelineColor? = name?.let { TimelineColor.findByName(it) }

/**
 * The stored form of a style value, or null when the watch can't show it. Vibes are stored in their
 * display spelling (libpebble3's convention for `vibePatternName`), icons and colours by enum name.
 */
internal fun normalizeVibe(spec: String): String? = defaultVibeOf(spec)?.displayName ?: vibeCsv(spec)?.joinToString(",")
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

/** `NotifStyleChoices` records: `vibe|icon \t value \t label`, value being what `NotifSetStyle` stores. */
internal fun notifStyleChoices(): List<String> =
    DefaultVibePattern.entries.map { "vibe\t${it.displayName}\t${it.displayName}" } +
        ICON_CHOICES.map { (label, icon) -> "icon\t${icon.name}\t$label" }
