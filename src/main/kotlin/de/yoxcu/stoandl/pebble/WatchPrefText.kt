package de.yoxcu.stoandl.pebble

import io.rebble.libpebblecommon.database.entity.WatchPref

/**
 * stoandl's text on top of libpebble3's watch prefs, for `ListWatchPrefs` (columns 8–10): the section each
 * pref is shown under, a longer help text, and when a pref only matters while another has some value.
 *
 * libpebble3's names and summaries stay as they are: they match the watch's own Settings menu. Only two
 * names are overridden, where two prefs would read the same in one list. A pref this table doesn't know yet
 * (a newer libpebble3) lands in [OTHER]; a diagnostic one in [ADVANCED], which the GUI collapses.
 */
internal object WatchPrefText {
    const val ADVANCED = "Advanced"
    const val OTHER = "Other"

    /** Section titles in display order. */
    val SECTIONS = listOf(
        "Quick launch", "Backlight & display", "Notifications & timeline", "Quiet Time", "Vibration", "Music",
        "Time, units & language", "Motion & menus", ADVANCED, OTHER,
    )

    private val sectionOf: Map<String, String> = buildMap {
        fun section(title: String, vararg ids: String) = ids.forEach { put(it, title) }
        section("Quick launch", "qlUp", "qlDown", "qlSelect", "qlBack", "qlComboBackUp", "qlComboUpDown",
            "qlSingleClickUp", "qlSingleClickDown")
        section("Backlight & display", "lightEnabled", "lightPreset", "lightIntensity", "lightDynamicMode",
            "lightAmbientSensorEnabled", "lightTimeoutMs", "lightMotion", "lightTouch", "lightColor",
            "systemTextSize", "displayOrientationLeftHanded")
        section("Notifications & timeline", "mask", "notifWindowTimeout", "notifVibeDelay", "notifBacklight",
            "notifDesignStyle", "notifTextSize", "timelineQuickViewEnabled", "timelineQuickViewBeforeTimeMin")
        section("Quiet Time", "dndManuallyEnabled", "dndSmartEnabled", "dndWeekdayScheduleEnabled",
            "dndWeekdaySchedule", "dndWeekendScheduleEnabled", "dndWeekendSchedule", "dndInterruptionsMask",
            "dndShowNotifications", "dndMotionBacklight", "dndNotificationBacklight", "dndAutoDismiss")
        section("Vibration", "vibeIntensity", "vibeScoreNotifications", "vibeScoreIncomingCalls", "vibeScoreAlarms")
        section("Music", "musicShowVolumeControls", "musicShowProgressBar", "musicShowAlbumArt")
        section("Time, units & language", "clock24h", "timezoneSource", "unitsWind", "language")
        section("Motion & menus", "stationaryMode", "menuScrollWrapAround", "menuScrollVibeBehavior")
    }

    private const val TEXT_SIZE_HELP = "On older firmware change it on the watch; Extra Large can only be set there."
    private const val QUICK_LAUNCH_HELP = "Choose an app, a system action (Quiet Time, Backlight, Motion " +
        "Backlight, Airplane Mode, Timeline Past/Future, Toggle Touch) or Off. Actions the connected watch " +
        "can't run are left out."
    private const val DIAGNOSTIC_HELP = "Diagnostic setting."

    private val helpOf: Map<String, String> = buildMap {
        sectionOf.filterValues { it == "Quick launch" }.keys.forEach { put(it, QUICK_LAUNCH_HELP) }
        put("lightTouch", "Double Tap avoids lighting up by accident.")
        put("systemTextSize", TEXT_SIZE_HELP)
        put("notifTextSize", TEXT_SIZE_HELP)
        put("mask", "Which alerts the watch shows and vibrates for. stoandl still sends everything; the watch filters.")
        put("notifVibeDelay", "Off vibrates immediately, during the animation.")
        put("dndManuallyEnabled", "Mirror Do Not Disturb (Notifications) can switch this with your desktop.")
        put("dndWeekdaySchedule", "An end before the start runs overnight (22:00-07:00).")
        put("dndWeekendSchedule", "An end before the start runs overnight (22:00-07:00).")
        put("dndInterruptionsMask", "Disconnect during Quiet Time (Connection & power) only works when nothing " +
            "is let through.")
        put("vibeScoreNotifications", "Single apps can have their own pattern on the Alerts tab.")
        put("musicShowAlbumArt", "Uses the cover stoandl sends from your player.")
        put("timezoneSource", "stoandl updates the watch when this device's time zone changes.")
        put("language", "Install packs under Firmware & language or on the Watch tab.")
        put("motionSensitivity", DIAGNOSTIC_HELP)
        put("lightAmbientThreshold", DIAGNOSTIC_HELP)
    }

    /** id → (the pref it depends on, the values that make it matter, in `ListWatchPrefs`' `current` spelling). */
    private val requiresOf: Map<String, Pair<String, List<String>>> = buildMap {
        // The backlight preset sets these together; they are the user's only under Advanced.
        listOf("lightIntensity", "lightDynamicMode", "lightAmbientSensorEnabled", "lightTimeoutMs")
            .forEach { put(it, "lightPreset" to listOf("Advanced")) }
        put("timelineQuickViewBeforeTimeMin", "timelineQuickViewEnabled" to listOf("true"))
        put("dndWeekdaySchedule", "dndWeekdayScheduleEnabled" to listOf("true"))
        put("dndWeekendSchedule", "dndWeekendScheduleEnabled" to listOf("true"))
    }

    /** Names that would otherwise read the same in one list ("Text Size" twice). */
    private val nameOf = mapOf(
        "systemTextSize" to "Menu Text Size",
        "notifTextSize" to "Notification Text Size",
    )

    fun name(pref: WatchPref<*>): String = nameOf[pref.id] ?: pref.displayName

    fun section(pref: WatchPref<*>): String =
        if (pref.isDebugSetting) ADVANCED else sectionOf[pref.id] ?: OTHER

    fun help(pref: WatchPref<*>): String = helpOf[pref.id].orEmpty()

    /** The pref [pref] depends on (null when none), so a caller can drop the clause where that pref has no
     *  effect on the connected watch. */
    fun requiredPref(pref: WatchPref<*>): String? = requiresOf[pref.id]?.first

    /** `id=value` alternatives joined by `|`, or empty when [pref] always matters. */
    fun requires(pref: WatchPref<*>): String =
        requiresOf[pref.id]?.let { (id, values) -> values.joinToString("|") { "$id=$it" } }.orEmpty()

    /** Display order: by [SECTIONS], stable within a section. */
    fun sectionIndex(section: String): Int = SECTIONS.indexOf(section).let { if (it < 0) SECTIONS.size else it }

    /** The ids this table mentions, for the test that keeps it in step with libpebble3. */
    internal val knownIds: Set<String> get() = sectionOf.keys + helpOf.keys + requiresOf.keys + nameOf.keys +
        requiresOf.values.map { it.first }
}
