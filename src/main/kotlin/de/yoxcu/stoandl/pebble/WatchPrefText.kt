package de.yoxcu.stoandl.pebble

import io.rebble.libpebblecommon.database.entity.WatchPref

/**
 * stoandl's text on top of libpebble3's watch prefs, for `ListWatchPrefs` (columns 7–10): the one-sentence
 * description, the section each pref is shown under, a longer help text, and when a pref only matters while
 * another has some value.
 *
 * libpebble3's names stay as they are: they match the watch's own Settings menu. Only two are overridden,
 * where two prefs would read the same in one list. The descriptions are stoandl's since libpebble3 dropped
 * `WatchPref.description` (upstream 76fc8f4a moved them into its app's string resources): upstream's
 * English `watch_pref_*_desc` texts, plus three of the fork's own that upstream never had. A pref this
 * table doesn't know yet (a newer libpebble3) lands in [OTHER]; a diagnostic one in [ADVANCED], which the
 * GUI collapses.
 */
internal object WatchPrefText {
    const val ADVANCED = "Advanced"
    const val OTHER = "Other"

    /** Section titles in display order. */
    val SECTIONS = listOf(
        "Quick launch", "Backlight & display", "Theme", "Notifications & timeline", "Quiet Time", "Vibration",
        "Speaker", "Music", "Time & units", "Health", "Motion & menus", ADVANCED, OTHER,
    )

    private val sectionOf: Map<String, String> = buildMap {
        fun section(title: String, vararg ids: String) = ids.forEach { put(it, title) }
        section("Quick launch", "qlUp", "qlDown", "qlSelect", "qlBack", "qlComboBackUp", "qlComboUpDown",
            "qlSingleClickUp", "qlSingleClickDown")
        section("Backlight & display", "lightEnabled", "lightPreset", "lightIntensity", "lightDynamicMode",
            "lightAmbientSensorEnabled", "lightTimeoutMs", "lightMotion", "lightTouch", "lightColor",
            "lightColorDayNightEnabled", "lightColorNight", "lightColorSunriseMinute", "lightColorSunsetMinute",
            "systemTextSize", "displayOrientationLeftHanded", "touchEnabled", "touchNavMenuEnabled",
            "legacyAppRenderMode")
        section("Theme", "darkMode", "darkModeSchedule", "themeHighlightColor")
        section("Notifications & timeline", "mask", "notifWindowTimeout", "notifVibeDelay", "notifBacklight",
            "notifDesignStyle", "notifTextSize", "notifRespectPhoneSilence", "notifDismissalSync",
            "notifGroupingRange", "notifStatusBarStyle", "timelineQuickViewEnabled",
            "timelineQuickViewBeforeTimeMin", "timelineQuickViewWatchfaceFit")
        section("Quiet Time", "dndManuallyEnabled", "dndSmartEnabled", "dndWeekdayScheduleEnabled",
            "dndWeekdaySchedule", "dndWeekendScheduleEnabled", "dndWeekendSchedule", "dndInterruptionsMask",
            "dndShowNotifications", "dndMotionBacklight", "dndTouchBacklight", "dndNotificationBacklight",
            "dndAutoDismiss", "dndMuteSpeaker")
        section("Vibration", "vibeIntensity", "vibeScoreNotifications", "vibeScoreIncomingCalls", "vibeScoreAlarms",
            "vibeScoreHourly", "vibeScoreOnDisconnect")
        section("Speaker", "speakerMuted", "speakerVolume")
        section("Music", "musicShowVolumeControls", "musicShowProgressBar", "musicShowAlbumArt")
        section("Time & units", "clock24h", "timezoneSource", "unitsWind", "unitsTemperature")
        section("Health", "activityInsightsStyle")
        section("Motion & menus", "stationaryMode", "menuScrollWrapAround", "menuScrollVibeBehavior")
    }

    private const val TEXT_SIZE_HELP = "On older firmware change it on the watch; Extra Large can only be set there."
    private val QUICK_LAUNCH_HELP = "Choose an app, a system action (${QuickLaunchActions.names.joinToString(", ")}) " +
        "or Off. Actions the connected watch can't run are left out."
    private const val DIAGNOSTIC_HELP = "Diagnostic setting."
    private const val MINUTE_OF_DAY_HELP = "Minutes after midnight: 360 is 06:00, 1080 is 18:00."

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
        put("notifRespectPhoneSilence", "stoandl marks no notification as silenced, so this changes nothing; " +
            "Mirror Do Not Disturb (Notifications) follows your desktop instead.")
        put("lightColorSunriseMinute", MINUTE_OF_DAY_HELP)
        put("lightColorSunsetMinute", MINUTE_OF_DAY_HELP)
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
        listOf("lightColorNight", "lightColorSunriseMinute", "lightColorSunsetMinute")
            .forEach { put(it, "lightColorDayNightEnabled" to listOf("true")) }
        put("darkModeSchedule", "darkMode" to listOf("Schedule"))
        put("touchNavMenuEnabled", "touchEnabled" to listOf("true"))
    }

    /**
     * id → the one-sentence description `ListWatchPrefs` shows (column 8, before the firmware note). Upstream's
     * `watch_pref_*_desc` English strings (pebble/composeResources/values/strings.xml at de47e36f), except
     * where noted; prefs upstream describes nowhere have none.
     */
    private val descriptionOf: Map<String, String> = mapOf(
        "timezoneSource" to "Manually configure a time zone on the watch (instead of automatically using the time zone of the phone)",
        "stationaryMode" to "Watch will disable bluetooth when not in use (i.e. no movement is detected), to save power",
        "displayOrientationLeftHanded" to "Button functions are reversed",
        "lightAmbientSensorEnabled" to "Only enable backlight when in a dark environment (using light sensor)",
        "lightMotion" to "Turn on backlight by flicking wrist",
        "lightColorDayNightEnabled" to "Use a different backlight color between sunset and sunrise (color-backlight watches only)",
        "timelineQuickViewEnabled" to "Show upcoming events below watchface",
        "timelineQuickViewWatchfaceFit" to "How the Quick View peek is drawn on watchfaces that don't support it (supported watch models only)",
        "dndManuallyEnabled" to "Notifications are muted (and will stay on-screen without a timeout) when in quiet time",
        "dndSmartEnabled" to "Automatically enable Quiet Time during calendar events",
        "dndWeekdayScheduleEnabled" to "Automatically enable Quiet Time during the scheduled hours, Monday to Friday",
        "dndWeekendScheduleEnabled" to "Automatically enable Quiet Time during the scheduled hours, Saturday and Sunday",
        "notifVibeDelay" to "Delay notification vibration until the notification is visible (after animations)",
        "notifBacklight" to "Turn on the backlight when a notification arrives",
        // Upstream's second sentence is about Android's call filter; stoandl lets every call through.
        "notifRespectPhoneSilence" to "Notifications silenced by the phone's Do Not Disturb / Focus are delivered quietly (no popup, vibration or backlight)",
        "notifDismissalSync" to "Notifications dismissed or acted on are removed from the watch's notification history completely (otherwise they are just removed if visible in the notification popup)",
        "notifWindowTimeout" to "Notifications time out (disappear) after this period (unless Quiet Time is enabled)",
        "notifGroupingRange" to "Group notifications from the same sender received within this time",
        "menuScrollWrapAround" to "Up button will go to the bottom of menus",
        "dndMotionBacklight" to "Enable motion backlight during Quiet Time",
        "dndTouchBacklight" to "Enable touch backlight during Quiet Time",
        "dndNotificationBacklight" to "Turn on the backlight for notifications during Quiet Time",
        "dndAutoDismiss" to "When notifications are shown in Quiet Time, automatically dismiss them instead of leaving them on-screen",
        "dndMuteSpeaker" to "Mute the speaker during Quiet Time",
        "lightPreset" to "Bundles the ambient sensor, dynamic backlight, brightness and timeout into one mode. Choose Advanced to configure them individually.",
        "lightIntensity" to "Maximum backlight brightness when on",
        "lightDynamicMode" to "Automatically adjust backlight brightness to match your environment (using light sensor). Dimmer modes stay dimmer in bright light.",
        "lightTouch" to "Turn on backlight when tapping the screen",
        "touchEnabled" to "Enable the touchscreen",
        "touchNavMenuEnabled" to "Navigate system menus by touch",
        "unitsWind" to "Automatic follows the Imperial Units setting.",
        "unitsTemperature" to "Automatic follows the Imperial Units setting.",
        "lightColor" to "LED color used when the backlight is on, unless over-ridden by an app (color-backlight watches only)",
        "lightColorNight" to "LED color used at night when \"Change Backlight Color at Night\" is on",
        "activityInsightsStyle" to "Wording of sleep insights for nights outside 7 to 9 hours",
        // The fork's own (8181523b, e16e1327): upstream has no description for these.
        "systemTextSize" to "Text size for system menus and the launcher",
        "notifTextSize" to "Text size for notifications",
        "lightAmbientThreshold" to "Controls how low ambient light needs to be to enable backlight (if using Ambient Light Sensor)",
    )

    /** Names that would otherwise read the same in one list ("Text Size" twice). */
    private val nameOf = mapOf(
        "systemTextSize" to "Menu Text Size",
        "notifTextSize" to "Notification Text Size",
    )

    fun name(pref: WatchPref<*>): String = nameOf[pref.id] ?: pref.displayName

    /** [pref]'s one-sentence description, or null when it has none. */
    fun description(pref: WatchPref<*>): String? = descriptionOf[pref.id]

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
        requiresOf.values.map { it.first } + descriptionOf.keys
}
