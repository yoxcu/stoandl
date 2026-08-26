package de.yoxcu.stoandl.config

import de.yoxcu.stoandl.util.ConfFile
import java.io.File

/**
 * The [StoandlConfig] keys the GUI Settings screen renders (schema-driven), plus the write path
 * ([applyGuiConfig]) that validates and persists a single key back to `stoandl.conf`.
 *
 * This is a hand-maintained extra view of the config (alongside the data-class KDoc, `defaults()`, the
 * `parseXxx()` reads and `stoandl.conf.example`) — **keep it in sync** when you add a key. Everything a
 * `stoandl.conf` key can be edited as belongs here; the keys deliberately NOT in this list are the ones
 * with a richer dedicated D-Bus surface (calendar sources → `AddCalendarSource`/…, `watch.<prefId>` →
 * `SetWatchPref`, `extensions.enabled`/`extension.<name>.<key>` → the `Ext*` methods) and the six sync
 * master switches the Sync screen owns via `SetSyncEnabled`. `docs/settings-parity.md` is the matrix.
 *
 * Five widget kinds exist — `toggle`, `combo`, `text`, `int` and `list` — and the GUI renders them from
 * the schema, so a new key appears in both front-ends with no GUI change:
 *
 *     GetConfig       → `key \t value`
 *     GetConfigSchema → `key \t type \t label \t options \t desc \t group \t apply \t min \t max \t unit \t placeholder`
 *
 * The first five columns are the original wire format; everything from `group` on is **appended**, so a
 * client built against the old contract keeps working (both front-ends index columns positionally and
 * tolerate missing ones). `value` is a combo's option label, a toggle's `true`/`false`, or the raw text /
 * number / comma-joined list; the write path maps that back to the raw `stoandl.conf` token via [parse].
 */

/** One combo option: the [label] shown in the GUI and emitted by `GetConfig`, the raw [token] written to
 *  `stoandl.conf` (what `parseXxx()` reads back), and whether it's the [selected] current value. Holding
 *  all three together keeps the read (current→label), the option list, and the write (label→token) from
 *  drifting apart. */
data class ConfigChoice(
    val label: String,
    val token: String,
    val selected: (StoandlConfig) -> Boolean,
)

/**
 * Whether a key takes effect the moment it is written, or only after a daemon restart.
 *
 * [LIVE] means `setConfigLive` (PebbleIntegration) reloads the store and — where a subsystem must be
 * re-run — re-reconciles it, **or** the consumer reads the value off the live config on every use.
 * [RESTART] means the value is captured once during startup wiring (a Koin binding, a constructor
 * snapshot, a one-shot `if` in `init()`), so a write persists but changes nothing until the daemon is
 * restarted. The GUI surfaces the difference; `docs/settings-parity.md` records why per key.
 */
enum class ConfigApply(val token: String) { LIVE("live"), RESTART("restart") }

/** Mapping a GUI-submitted value onto a raw `stoandl.conf` token either succeeds or explains itself. */
sealed interface ConfigParse {
    data class Ok(val token: String) : ConfigParse
    data class Err(val message: String) : ConfigParse
}

data class ConfigField(
    val key: String,
    /** `toggle` | `combo` | `text` | `int` | `list`. */
    val type: String,
    /** Section header the GUI groups this key under (display only). */
    val group: String,
    val label: String,
    val desc: String,
    /** Whether a write takes effect immediately or needs a daemon restart — surfaced in the GUI. */
    val apply: ConfigApply,
    /** Combo options in display order; empty for every other kind. */
    val choices: List<ConfigChoice> = emptyList(),
    /** Current value as the GUI sees it. Null only for a combo, whose value comes from [choices]. */
    val read: ((StoandlConfig) -> String)? = null,
    /** `int` only: inclusive bounds and the unit suffix the GUI shows next to the number. */
    val min: Int? = null,
    val max: Int? = null,
    val unit: String = "",
    /** `text`/`list` only: hint text for an empty field (also documents the expected shape). */
    val placeholder: String = "",
    /** `text`/`list` only: extra per-key validation beyond "won't corrupt the conf file". Returns an
     *  error message, or null when the value is acceptable. Runs on the already-trimmed input. */
    val validate: ((String) -> String?)? = null,
) {
    /** CSV of option labels for `GetConfigSchema` (empty for every non-combo kind). */
    val options: String get() = choices.joinToString(",") { it.label }

    /** Current value for `GetConfig`: a combo's selected label, else whatever [read] renders. */
    fun value(c: StoandlConfig): String = when (type) {
        "combo" -> (choices.firstOrNull { it.selected(c) } ?: choices.firstOrNull())?.label ?: ""
        else -> read?.invoke(c).orEmpty()
    }

    /** The `GetConfigSchema` record for this field. */
    fun schemaRow(): String = listOf(
        key, type, label, options, desc, group, apply.token,
        min?.toString().orEmpty(), max?.toString().orEmpty(), unit, placeholder,
    ).joinToString("\t")

    /**
     * Map a GUI-submitted value to the raw `stoandl.conf` token, or explain why it isn't valid.
     * Combos accept either the option label or the token (case-insensitive); toggles accept the usual
     * boolean words and normalise to `true`/`false`; `int` is range-checked; `text`/`list` are rejected
     * if they'd corrupt the file (see [confSafe]) and then run the field's own [validate].
     */
    fun parse(input: String): ConfigParse {
        val t = input.trim()
        return when (type) {
            "combo" -> choices.firstOrNull { it.label.equals(t, true) || it.token.equals(t, true) }
                ?.let { ConfigParse.Ok(it.token) }
                ?: ConfigParse.Err("invalid value '$input' for $key (expected one of $options)")
            "toggle" -> when (t.lowercase()) {
                "true", "yes", "on", "1" -> ConfigParse.Ok("true")
                "false", "no", "off", "0" -> ConfigParse.Ok("false")
                else -> ConfigParse.Err("invalid value '$input' for $key (expected true or false)")
            }
            "int" -> {
                val n = t.toIntOrNull()
                    ?: return ConfigParse.Err("invalid value '$input' for $key (expected a whole number)")
                val lo = min
                val hi = max
                if (lo != null && n < lo) return ConfigParse.Err("$key must be at least $lo")
                if (hi != null && n > hi) return ConfigParse.Err("$key must be at most $hi")
                ConfigParse.Ok(n.toString())
            }
            "list" -> {
                confSafe(t)?.let { return ConfigParse.Err("$key: $it") }
                // Normalise to the `a,b,c` form StoandlConfig.list() reads back, dropping blanks.
                val items = t.split(',').map { it.trim() }.filter { it.isNotEmpty() }
                val normalised = items.joinToString(",")
                validate?.invoke(normalised)?.let { return ConfigParse.Err("$key: $it") }
                ConfigParse.Ok(normalised)
            }
            else -> {
                confSafe(t)?.let { return ConfigParse.Err("$key: $it") }
                validate?.invoke(t)?.let { return ConfigParse.Err("$key: $it") }
                ConfigParse.Ok(t)
            }
        }
    }
}

/**
 * Reject values that `stoandl.conf` cannot round-trip. The file is `key = value` with `#` starting a
 * comment and one setting per line ([ConfFile] / [StoandlConfig.load]), so a `#` would silently truncate
 * the value on the next read and a newline would split it into a bogus second line. Returns the reason,
 * or null when the value is safe.
 */
private fun confSafe(v: String): String? = when {
    v.contains('\n') || v.contains('\r') -> "must be a single line"
    v.contains('#') -> "cannot contain '#' (it starts a comment in stoandl.conf)"
    else -> null
}

private fun toggle(
    key: String, group: String, label: String, desc: String,
    apply: ConfigApply = ConfigApply.LIVE, read: (StoandlConfig) -> Boolean,
) = ConfigField(key, "toggle", group, label, desc, apply, read = { read(it).toString() })

private fun combo(
    key: String, group: String, label: String, desc: String,
    apply: ConfigApply = ConfigApply.LIVE, choices: List<ConfigChoice>,
) = ConfigField(key, "combo", group, label, desc, apply, choices = choices)

private fun text(
    key: String, group: String, label: String, desc: String, placeholder: String = "",
    apply: ConfigApply = ConfigApply.LIVE, validate: ((String) -> String?)? = null,
    read: (StoandlConfig) -> String,
) = ConfigField(key, "text", group, label, desc, apply, read = read, placeholder = placeholder, validate = validate)

private fun int(
    key: String, group: String, label: String, desc: String, min: Int, max: Int, unit: String = "",
    apply: ConfigApply = ConfigApply.LIVE, read: (StoandlConfig) -> Int,
) = ConfigField(key, "int", group, label, desc, apply, read = { read(it).toString() }, min = min, max = max, unit = unit)

private fun list(
    key: String, group: String, label: String, desc: String, placeholder: String = "",
    apply: ConfigApply = ConfigApply.LIVE, validate: ((String) -> String?)? = null,
    read: (StoandlConfig) -> List<String>,
) = ConfigField(key, "list", group, label, desc, apply, read = { read(it).joinToString(",") },
    placeholder = placeholder, validate = validate)

// --- Per-key validators ----------------------------------------------------------------------------

/** `Name:lat:lon` entries, checked with the very parser that reads them back, so the GUI can't persist a
 *  location the daemon would silently drop at load time. */
private fun weatherLocationsValid(v: String): String? {
    if (v.isEmpty()) return null
    val entries = v.split(',').map { it.trim() }.filter { it.isNotEmpty() }
    val parsed = StoandlConfig.parseWeatherLocations(entries)
    return if (parsed.size == entries.size) null
    else "expected comma-separated Name:lat:lon entries (e.g. Berlin:52.52:13.405)"
}

private fun githubRepoValid(v: String): String? {
    if (v.isEmpty()) return "cannot be empty (the default is coredevices/PebbleOS)"
    val parts = v.split('/')
    return if (parts.size == 2 && parts.all { it.isNotBlank() && !it.contains(' ') }) null
    else "expected owner/repo (e.g. coredevices/PebbleOS)"
}

/** A `Long` minutes/days setting rendered into the schema's Int-valued `int` widget. A hand-edited value
 *  beyond Int range is reported at the boundary rather than wrapping — the GUI then clamps it into the
 *  field's own min/max, which is the only value it could write back anyway. */
private fun Long.toIntClamped(): Int = coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()

private fun httpUrlValid(v: String): String? {
    if (v.isEmpty()) return "cannot be empty"
    return if ((v.startsWith("http://") || v.startsWith("https://")) && !v.contains(' ')) null
    else "expected an http:// or https:// URL"
}

// --- The GUI-exposed keys, in display order --------------------------------------------------------

private const val G_NOTIF = "Notifications"
private const val G_ALERTS = "stoandl alerts"
private const val G_CALLS = "Calls & contacts"
private const val G_WEATHER = "Weather"
private const val G_CALENDAR = "Calendar"
private const val G_MUSIC = "Music"
private const val G_HEALTH = "Health"
private const val G_BATTERY = "Battery"
private const val G_FIRMWARE = "Firmware"
private const val G_LANGUAGE = "Language"
private const val G_CONNECTION = "Connection"
private const val G_DND = "Do Not Disturb"
private const val G_PRIVACY = "Privacy"
private const val G_DEVELOPER = "Developer"

/** Every `stoandl.conf` key the GUI Settings screen renders, in display order. */
val GUI_CONFIG_FIELDS: List<ConfigField> = listOf(
    // --- Notifications ---
    toggle("notification.per_app", G_NOTIF, "Per-app notifications",
        "Track apps and enforce per-app mute host-side") { it.notificationPerApp },
    combo("notification.default_mute", G_NOTIF, "Default mute for new apps",
        "How a newly-seen app is muted until you change it", choices = listOf(
        ConfigChoice("Never", "never") { it.notificationDefaultMute == "never" },
        ConfigChoice("Always", "always") { it.notificationDefaultMute == "always" },
        ConfigChoice("Weekdays", "weekdays") { it.notificationDefaultMute == "weekdays" },
        ConfigChoice("Weekends", "weekends") { it.notificationDefaultMute == "weekends" },
    )),
    toggle("notification.sync_to_watch", G_NOTIF, "Sync the app list to the watch",
        "Push the per-app list and mute states to the watch's BlobDB. Current firmware surfaces no " +
            "per-app notification UI, so this normally changes nothing — mute is enforced host-side.",
        apply = ConfigApply.RESTART) { it.notificationSyncToWatch },

    // --- stoandl's own alerts ---
    toggle("alerts.enabled", G_ALERTS, "Alerts from stoandl",
        "Master switch for the desktop alerts stoandl raises about itself (pairing, Bluetooth, " +
            "extensions). Forwarded app notifications are unaffected.") { it.alertsEnabled },
    toggle("alerts.pairing", G_ALERTS, "Pairing problems",
        "Alert when a watch loses its pairing, keeps dropping the link, or is nearby but no longer " +
            "paired — each with the action that fixes it") { it.alertsPairing },
    toggle("alerts.bluetooth", G_ALERTS, "Bluetooth blocked",
        "Alert when another app's Bluetooth scan is monopolising the adapter and blocking reconnects") { it.alertsBluetooth },
    toggle("alerts.extensions", G_ALERTS, "Extension problems",
        "Alert when an installed extension needs configuring before it can start") { it.alertsExtensions },

    // --- Calls & contacts ---
    list("call.dialer_apps", G_CALLS, "Dialer apps",
        "Notifications from these apps are suppressed (the watch's native call screen replaces them) " +
            "and their title is used as a fallback caller name",
        placeholder = "spacebar,calls") { it.dialerApps },
    list("contacts.vcard_paths", G_CALLS, "Contact files",
        "vCard files or directories scanned to turn an incoming number into a name. No egress.",
        placeholder = "~/.local/share/contacts") { it.vcardPaths },

    // --- Weather ---
    list("weather.locations", G_WEATHER, "Locations",
        "Fixed locations to fetch weather for, as Name:lat:lon entries",
        placeholder = "Berlin:52.52:13.405", validate = ::weatherLocationsValid) {
        it.weatherLocations.map { l -> "${l.name}:${l.latitude}:${l.longitude}" }
    },
    combo("weather.location_source", G_WEATHER, "Extra locations from",
        "Where additional fixed locations come from besides the list above", choices = listOf(
        ConfigChoice("Manual", "manual") { it.weatherLocationSource == StoandlConfig.WeatherLocationSource.MANUAL },
        ConfigChoice("GNOME", "gnome") { it.weatherLocationSource == StoandlConfig.WeatherLocationSource.GNOME },
        ConfigChoice("Command", "command") { it.weatherLocationSource == StoandlConfig.WeatherLocationSource.COMMAND },
    )),
    text("weather.location_command", G_WEATHER, "Location command",
        "Run for the Command source; must print one Name:lat:lon line per location",
        placeholder = "/usr/local/bin/my-locations") { it.weatherLocationCommand },
    combo("weather.units", G_WEATHER, "Temperature units",
        "Unit sent to the watch's weather", choices = listOf(
        ConfigChoice("Metric", "metric") { it.weatherUnits == StoandlConfig.WeatherUnits.METRIC },
        ConfigChoice("Imperial", "imperial") { it.weatherUnits == StoandlConfig.WeatherUnits.IMPERIAL },
    )),
    int("weather.interval", G_WEATHER, "Refresh interval",
        "How often weather is re-fetched", min = 5, max = 1440, unit = "min") {
        it.weatherIntervalMinutes.toIntClamped()
    },
    toggle("weather.gps", G_WEATHER, "Current-location weather",
        "Add a GeoClue2-tracked \"current location\" entry alongside the fixed ones") { it.weatherGps },
    text("weather.gps_name", G_WEATHER, "Current-location label",
        "Shown on the watch when reverse geocoding is off or yields no place name",
        placeholder = "Current location") { it.weatherGpsName },
    text("weather.gps_desktop_id", G_WEATHER, "GeoClue desktop id",
        "Must match the allow-list entry in /etc/geoclue/geoclue.conf",
        placeholder = "stoandl") { it.weatherGpsDesktopId },
    toggle("weather.reverse_geocode", G_WEATHER, "Reverse-geocode GPS",
        "Name the GPS location via OSM Nominatim (sends coordinates to a web service)") { it.weatherReverseGeocode },
    toggle("weather.pins", G_WEATHER, "Weather timeline pins",
        "Add sunrise/sunset pins for the primary location") { it.weatherPins },

    // --- Calendar ---
    toggle("calendar.discover", G_CALENDAR, "Auto-discover local calendars",
        "Find the desktop's local .ics calendars (Calindori, ~/.calendars). No egress.") { it.calendarDiscover },
    int("calendar.sync_interval", G_CALENDAR, "Re-read interval",
        "How often calendars are re-read (also rolls the timeline window forward)",
        min = 5, max = 1440, unit = "min") { it.calendarSyncIntervalMinutes.toIntClamped() },

    // --- Music ---
    toggle("music.enabled", G_MUSIC, "Music control",
        "Bridge desktop media players to the watch's Music app") { it.musicControl },
    combo("music.volume", G_MUSIC, "Volume buttons",
        "What the watch volume buttons control", choices = listOf(
        ConfigChoice("System", "system") { it.musicVolume == StoandlConfig.MusicVolumeMode.SYSTEM },
        ConfigChoice("Player", "player") { it.musicVolume == StoandlConfig.MusicVolumeMode.PLAYER },
    )),
    text("music.volume_up_command", G_MUSIC, "Volume-up command",
        "Overrides the auto-detected System-volume backend. Both commands must be set to take effect.",
        placeholder = "wpctl set-volume @DEFAULT_SINK@ 5%+") { it.musicVolumeUpCommand },
    text("music.volume_down_command", G_MUSIC, "Volume-down command",
        "Overrides the auto-detected System-volume backend. Both commands must be set to take effect.",
        placeholder = "wpctl set-volume @DEFAULT_SINK@ 5%-") { it.musicVolumeDownCommand },

    // --- Health ---
    toggle("health.sync", G_HEALTH, "Health sync",
        "Pull steps/sleep/HR from the watch on connect") { it.healthSync },
    toggle("health.export", G_HEALTH, "Health export",
        "Project synced health data to NDJSON files") { it.healthExport },
    toggle("health.export_samples", G_HEALTH, "Export minute-level samples",
        "Also export per-minute steps and heart rate — much higher volume than the daily summary") { it.healthExportSamples },
    int("health.export_days", G_HEALTH, "Export window",
        "How many days back the export re-projects on each update", min = 1, max = 365, unit = "days") { it.healthExportDays },

    // --- Battery ---
    toggle("battery.heartbeat", G_BATTERY, "Battery insights",
        "Decode the watch's hourly analytics heartbeat for voltage / time-to-empty / charge trends") { it.batteryHeartbeat },
    toggle("battery.history", G_BATTERY, "Battery level history",
        "Log the BLE battery level over time (fallback when the heartbeat has no data)") { it.batteryHistory },
    int("battery.retention_days", G_BATTERY, "History retention",
        "How much battery history to keep before pruning", min = 1, max = 3650, unit = "days") { it.batteryRetentionDays },

    // --- Firmware ---
    toggle("firmware.notify", G_FIRMWARE, "Firmware update alerts",
        "Notify when newer firmware is available (needs a firmware source enabled)") { it.firmwareNotify },
    toggle("firmware.github", G_FIRMWARE, "Firmware source: Core (GitHub)",
        "Check GitHub (PebbleOS) for Core-device firmware updates — opt-in network egress") { it.firmwareGithub },
    text("firmware.github_repo", G_FIRMWARE, "GitHub repository",
        "owner/repo whose releases publish per-board normal_<board>_<version>.pbz bundles",
        placeholder = "coredevices/PebbleOS", validate = ::githubRepoValid) { it.firmwareGithubRepo },
    toggle("firmware.github_prereleases", G_FIRMWARE, "Include GitHub pre-releases",
        "Consider pre-releases too, not just the latest stable release") { it.firmwareGithubPrereleases },
    toggle("firmware.cohorts", G_FIRMWARE, "Firmware source: classic (Rebble)",
        "Check Rebble's cohorts for classic-Pebble firmware updates — opt-in network egress") { it.firmwareCohorts },
    text("firmware.cohorts_url", G_FIRMWARE, "Cohorts service URL",
        "Base URL of the cohorts service — override for a self-hosted mirror",
        placeholder = "https://cohorts.rebble.io", validate = ::httpUrlValid) { it.firmwareCohortsUrl },

    // --- Language ---
    toggle("language.download", G_LANGUAGE, "Language pack download",
        "Download language packs from the online catalog — opt-in network egress") { it.languageDownload },

    // --- Connection ---
    toggle("classic.discover", G_CONNECTION, "Bluetooth Classic (classic-era watches)",
        "Discover, pair and connect Pebble Time / Time Steel over Bluetooth Classic (experimental)",
        apply = ConfigApply.RESTART) { it.classicDiscover },
    toggle("connection.autoswitch", G_CONNECTION, "Auto-switch between watches",
        "With 2+ paired watches, connect whichever is in range — preferring the most recently used") { it.connectionAutoswitch },

    // --- Do Not Disturb ---
    combo("dnd.sync", G_DND, "Do Not Disturb sync",
        "Mirror desktop Do Not Disturb and the watch's Quiet Time", choices = listOf(
        ConfigChoice("Off", "off") { it.dndSync == StoandlConfig.DndSyncMode.OFF },
        ConfigChoice("To watch", "to_watch") { it.dndSync == StoandlConfig.DndSyncMode.TO_WATCH },
        ConfigChoice("To host", "to_host") { it.dndSync == StoandlConfig.DndSyncMode.TO_HOST },
        ConfigChoice("Both", "both") { it.dndSync == StoandlConfig.DndSyncMode.BOTH },
    )),

    // --- Privacy ---
    toggle("geolocation.enabled", G_PRIVACY, "Watchapp geolocation",
        "Expose the device's GPS to watchapps / PKJS") { it.geolocation },

    // --- Developer ---
    toggle("datalog.enabled", G_DEVELOPER, "Datalog capture",
        "Save custom-watchapp datalog to NDJSON files (writes app-supplied data to disk)",
        apply = ConfigApply.RESTART) { it.datalog },
    toggle("developer.autostart", G_DEVELOPER, "Developer connection autostart",
        "Start the LAN dev server (port 9000) on every connect — UNAUTHENTICATED: anyone on your network can install apps") { it.developerAutostart },
)

/** Lookup by conf key — used by the write path and by the CLI's `config set`. */
fun guiConfigField(key: String): ConfigField? = GUI_CONFIG_FIELDS.firstOrNull { it.key == key }

/**
 * Validate and persist a single GUI config key to [confFile]. Maps the GUI's value (a toggle's
 * `true`/`false`, a combo's option label, or raw text/number/list) to the raw `stoandl.conf` token and
 * upserts it atomically (via [ConfFile], sharing the lock with the extension-config writers). The
 * **caller** (PebbleIntegration's `setConfigLive`) reloads the live [ConfigStore] and re-reconciles the
 * affected subsystem after this returns `ok:`, so a [ConfigApply.LIVE] change takes effect without a
 * restart. Returns a status-prefixed string: `ok:`, `notfound:` (unknown key), or `error:` (bad value /
 * IO). An `ok:` for a [ConfigApply.RESTART] key carries the restart hint in its message.
 */
fun applyGuiConfig(key: String, value: String, confFile: File): String {
    val field = guiConfigField(key) ?: return "notfound:no config key '$key'"
    val token = when (val p = field.parse(value)) {
        is ConfigParse.Ok -> p.token
        is ConfigParse.Err -> return "error:${p.message}"
    }
    return try {
        ConfFile.upsert(confFile, mapOf(key to token))
        val suffix = if (field.apply == ConfigApply.RESTART) " (restart stoandl to apply)" else ""
        "ok:$key = $token$suffix"
    } catch (e: Exception) {
        "error:${e.message ?: "failed to write ${confFile.name}"}"
    }
}
