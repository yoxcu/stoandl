package de.yoxcu.stoandl.config

import de.yoxcu.stoandl.notification.AppNamePlacement
import de.yoxcu.stoandl.util.ConfFile
import io.rebble.libpebblecommon.notification.DefaultVibePattern
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
 * number / comma-joined list ([ConfList]: `\,` escapes a comma inside an item); the write path maps
 * that back to the raw `stoandl.conf` token via [parse].
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
    /** `toggle` | `combo` | `text` | `int` | `list` | `secret`. A `secret` is never in stoandl.conf: it lives
     *  in the secret store (keyring, else a 0600 file) under [secretRef]; `GetConfig` shows only `set` or
     *  empty, and writing an empty value removes it. */
    val type: String,
    /** Section header the GUI groups this key under (display only). */
    val group: String,
    val label: String,
    val desc: String,
    /** Whether a write takes effect immediately or needs a daemon restart — surfaced in the GUI. */
    val apply: ConfigApply,
    /** Combo options in display order; empty for every other kind. */
    val fixedChoices: List<ConfigChoice> = emptyList(),
    /** A combo whose options change at runtime (installed speech models): read on every use. */
    val choicesOf: (() -> List<ConfigChoice>)? = null,
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
    /** The combo's options now. */
    val choices: List<ConfigChoice> get() = choicesOf?.invoke() ?: fixedChoices

    /** The secret store reference of a `secret` key. */
    val secretRef: String get() = "config:$key"

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
            "secret" -> if (t.contains('\n') || t.contains('\r')) ConfigParse.Err("$key: must be a single line")
                else ConfigParse.Ok(t)
            "list" -> {
                confSafe(t)?.let { return ConfigParse.Err("$key: $it") }
                // Normalise to the escaped `a,b\,c` form StoandlConfig.list() reads back, dropping blanks.
                val normalised = ConfList.encode(ConfList.decode(t))
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
) = ConfigField(key, "combo", group, label, desc, apply, fixedChoices = choices)

private fun text(
    key: String, group: String, label: String, desc: String, placeholder: String = "",
    apply: ConfigApply = ConfigApply.LIVE, validate: ((String) -> String?)? = null,
    read: (StoandlConfig) -> String,
) = ConfigField(key, "text", group, label, desc, apply, read = read, placeholder = placeholder, validate = validate)

private fun secret(key: String, group: String, label: String, desc: String, placeholder: String = "") =
    ConfigField(key, "secret", group, label, desc, ConfigApply.LIVE, placeholder = placeholder)

private fun int(
    key: String, group: String, label: String, desc: String, min: Int, max: Int, unit: String = "",
    apply: ConfigApply = ConfigApply.LIVE, read: (StoandlConfig) -> Int,
) = ConfigField(key, "int", group, label, desc, apply, read = { read(it).toString() }, min = min, max = max, unit = unit)

private fun list(
    key: String, group: String, label: String, desc: String, placeholder: String = "",
    apply: ConfigApply = ConfigApply.LIVE, validate: ((String) -> String?)? = null,
    read: (StoandlConfig) -> List<String>,
) = ConfigField(key, "list", group, label, desc, apply, read = { ConfList.encode(read(it)) },
    placeholder = placeholder, validate = validate)

// --- Per-key validators ----------------------------------------------------------------------------

/** `Name:lat:lon` entries, checked with the very parser that reads them back, so the GUI can't persist a
 *  location the daemon would silently drop at load time. */
private fun weatherLocationsValid(v: String): String? {
    if (v.isEmpty()) return null
    val entries = ConfList.decode(v)
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

/** The `voice.local_model` options: the installed speech models (read from disk each time), or a
 *  placeholder while none is installed. */
private fun installedModelChoices(): List<ConfigChoice> {
    val dir = de.yoxcu.stoandl.voice.VoiceModels.defaultDir()
    val installed = de.yoxcu.stoandl.voice.SPEECH_MODELS.filter { java.io.File(dir, it.file).isFile }
    if (installed.isEmpty()) return listOf(ConfigChoice("No model installed", "") { true })
    return installed.map { m -> ConfigChoice(m.name, m.id) { it.voiceLocalModel == m.id } }
}

/** The user's custom vibration names, offered by `calendar.reminder_vibe` after the built-ins. The daemon
 *  wires it to libpebble3's vibe store; empty elsewhere (tests, before init). */
@Volatile internal var customVibeNames: () -> List<String> = { emptyList() }

private fun reminderVibeChoices(): List<ConfigChoice> =
    listOf(ConfigChoice("Watch default", "") { it.calendarReminderVibe == null }) +
        // Value = the enum name (comma-free for the CSV options column); the config also takes the
        // display name.
        DefaultVibePattern.entries.map { p ->
            ConfigChoice(p.displayName.replace(",", ""), p.name) { c ->
                c.calendarReminderVibe.let { v -> v.equals(p.name, ignoreCase = true) || v.equals(p.displayName, ignoreCase = true) }
            }
        } +
        (runCatching { customVibeNames() }.getOrNull() ?: emptyList()).map { n ->
            ConfigChoice(n.replace(",", ""), n) { it.calendarReminderVibe.equals(n, ignoreCase = true) }
        }

private const val G_APPS = "Apps & faces"
private const val G_PLUGINS = "Plugins"
private const val G_VOICE = "Voice"
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
private const val G_SLEEP = "Deep sleep"
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
    combo("notification.app_name", G_NOTIF, "App name on the watch",
        "Where the app's name goes on the watch: a small line under the title, the large bold subtitle, " +
            "or not at all. A source's own subtitle (an extension's) keeps the subtitle. Apps can override " +
            "this in their notification settings.", choices = listOf(
        AppNamePlacement.SMALL to "Small line",
        AppNamePlacement.SUBTITLE to "Subtitle",
        AppNamePlacement.OFF to "Off",
    ).map { (p, label) -> ConfigChoice(label, p.token) { it.notificationAppName == p } }),
    toggle("notification.sync_to_watch", G_NOTIF, "Sync the app list to the watch",
        "Push the per-app list and mute states to the watch's BlobDB. Current firmware surfaces no " +
            "per-app notification UI, so this normally changes nothing — mute is enforced host-side.",
        apply = ConfigApply.RESTART) { it.notificationSyncToWatch },
    toggle("notification.only_when_locked", G_NOTIF, "Only while the screen is off or locked",
        "Hold notifications back while you're using the phone or computer (display on and session " +
            "unlocked). Urgent ones (alarms, incoming calls) and allow-filter matches still reach the watch; " +
            "calls ring as usual.") { it.notificationOnlyWhenLocked },
    toggle("notification.images", G_NOTIF, "Pictures on the watch",
        "Photos and screenshots a notification carries are shown on watches that can (Pebble Time 2 / " +
            "Round 2, firmware 4.35+). Sender avatars are never sent. Off deletes the stored pictures.") { it.notificationImages },
    toggle("notification.sync_dismissals", G_NOTIF, "Clear dismissed notifications from the watch",
        "When a notification is closed on the desktop or phone (or its app withdraws it), it is removed " +
            "from the watch too. A popup that only times out stays.") { it.notificationSyncDismissals },
    toggle("notification.open_action", G_NOTIF, "Open on the phone or computer",
        "An \"Open\" action on the watch for a notification that opens its app when clicked: it opens the " +
            "app as a click on the popup would. Plasma only; GNOME doesn't let another program do this.") { it.notificationOpenAction },
    int("notification.catch_up_minutes", G_NOTIF, "Catch up after a disconnect",
        "A reconnecting watch also gets the notifications it missed that are at most this old (never from " +
            "before the daemon started or the watch was paired). 0 = only ones posted after it reconnected.",
        min = 0, max = 1440, unit = "min", apply = ConfigApply.RESTART) { it.notificationCatchUpMinutes.toIntClamped() },
    list("notification.canned_replies", G_NOTIF, "Canned replies",
        "The watch's Reply list for desktop notifications that take a reply (Plasma with the InvokeReply " +
            "patch) and for extensions without their own list. Empty = Ok, Yes, No, Call me, Call you later. " +
            "Whole items up to 512 bytes in total are sent.",
        placeholder = "Ok,Yes,No,Call me,Call you later") { it.notificationCannedReplies },

    // --- stoandl's own alerts ---
    toggle("alerts.enabled", G_ALERTS, "Alerts from stoandl",
        "Master switch for the alerts stoandl raises about itself (pairing, Bluetooth, extensions, " +
            "permission requests, full battery, pairing scan). Most show on the desktop only; permission " +
            "prompts and the full-battery alert also show on the watch. Forwarded app notifications are " +
            "unaffected.") { it.alertsEnabled },
    toggle("alerts.pairing", G_ALERTS, "Pairing problems",
        "Alert when a watch keeps dropping the link (unpaired on the watch) or its pairing was " +
            "removed on this computer — each with the action that fixes it") { it.alertsPairing },
    toggle("alerts.bluetooth", G_ALERTS, "Bluetooth blocked",
        "Alert when another app's Bluetooth scan is monopolising the adapter and blocking reconnects") { it.alertsBluetooth },
    toggle("alerts.extensions", G_ALERTS, "Extension problems",
        "Alert when an installed extension needs configuring before it can start") { it.alertsExtensions },
    toggle("alerts.permissions", G_ALERTS, "Permission requests",
        "When an app asks for a permission (plugins on), prompt with Allow and Deny on the watch and the desktop") { it.alertsPermissions },
    toggle("alerts.battery_full", G_ALERTS, "Watch fully charged",
        "Alert on the watch and the desktop when a watch on the charger reaches 100 %. Classic-era watches " +
            "report their charge once an hour, so theirs can come up to an hour late.") { it.alertsBatteryFull },
    toggle("alerts.scan_failed", G_ALERTS, "Pairing scan failed",
        "Alert when a pairing window can't search for watches because Bluetooth discovery keeps failing " +
            "to start (once per window). `stoandl watch pair` reports it either way.") { it.alertsScanFailed },

    // --- Calls & contacts ---
    list("call.dialer_apps", G_CALLS, "Dialer apps",
        "Apps, by name or desktop-entry id (exact match), whose notifications are held back while a call " +
            "is up (the watch's native call screen replaces them); their title is a fallback caller name. " +
            "Their other notifications, such as Missed call, still reach the watch.",
        placeholder = "calls") { it.dialerApps },
    list("contacts.vcard_paths", G_CALLS, "Contact files",
        "vCard files (.vcf, .vcard) or folders, searched recursively, used to turn an incoming number " +
            "into a name. Default: ~/.local/share/kpeoplevcard (Plasma Mobile's phonebook). No egress.",
        placeholder = "~/.local/share/kpeoplevcard") { it.vcardPaths },

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
    toggle("weather.intercept_pkjs", G_WEATHER, "Weather for classic watchfaces",
        "Answer the dead Yahoo / OpenWeatherMap 2.5 weather APIs old watchfaces call, from Open-Meteo " +
            "(sends the watchface's coordinates or place name to a web service)") { it.weatherInterceptPkjs },
    toggle("weather.place_search", G_WEATHER, "Find locations by name",
        "Look a city up by name to add it to the locations (`stoandl weather search`, or the search under " +
            "this section). Sends the typed name to Open-Meteo's geocoder; works only while weather sync is on.") {
        it.weatherPlaceSearch
    },

    // --- Calendar ---
    toggle("calendar.discover", G_CALENDAR, "Auto-discover local calendars",
        "Find the desktop's local .ics calendars (Calindori, ~/.calendars). No egress.") { it.calendarDiscover },
    int("calendar.sync_interval", G_CALENDAR, "Re-read interval",
        "How often calendars are re-read (also rolls the timeline window forward)",
        min = 5, max = 1440, unit = "min") { it.calendarSyncIntervalMinutes.toIntClamped() },
    toggle("calendar.reminders", G_CALENDAR, "Event reminders",
        "The watch buzzes before an event (its own reminders). Off = pins only.") { it.calendarReminders },
    toggle("calendar.show_declined", G_CALENDAR, "Show declined events",
        "Events you declined still appear on the timeline") { it.calendarShowDeclined },
    ConfigField("calendar.reminder_vibe", "combo", G_CALENDAR, "Reminder vibration",
        "The vibration for every calendar reminder: a built-in one or one of yours (stoandl notif vibe-add)",
        ConfigApply.LIVE, choicesOf = ::reminderVibeChoices),

    // --- Apps & faces ---
    toggle("apps.order_faces_by_last_used", G_APPS, "Recently used faces first",
        "Move a watchface to the top of the locker order whenever it becomes the active face. Off keeps " +
            "the order you set.") { it.appsOrderFacesByLastUsed },

    // --- Plugins ---
    toggle("plugins.enabled", G_PLUGINS, "Plugins",
        "Phone-side data and actions for watchfaces and watchapps: built-in (weather, music, calendar, " +
            "phone, watch settings), from installed apps, and from extensions. Turns on app permissions too: " +
            "apps then ask before reading your data or sending it anywhere.") { it.pluginsEnabled },
    combo("plugins.permission_policy", G_PLUGINS, "When apps ask",
        "When an app must ask before using a permission", choices = listOf(
        ConfigChoice("If data could leave this machine", "ask_if_exfiltrating") {
            it.pluginsPermissionPolicy == StoandlConfig.PermissionPolicyMode.ASK_IF_EXFILTRATING },
        ConfigChoice("Always", "always_ask") { it.pluginsPermissionPolicy == StoandlConfig.PermissionPolicyMode.ALWAYS_ASK },
        ConfigChoice("Never", "never_ask") { it.pluginsPermissionPolicy == StoandlConfig.PermissionPolicyMode.NEVER_ASK },
    )),
    toggle("plugins.ask_legacy_apps", G_PLUGINS, "Ask older apps too",
        "Apps made before permissions are asked as well, instead of keeping internet access and what they " +
            "declared") { it.pluginsAskLegacyApps },

    // --- Voice ---
    combo("voice.backend", G_VOICE, "Speech to text",
        "Turns the watch's dictation (voice replies, watchapps) and voice notes into text. Local model runs " +
            "a downloaded model on this computer; a custom command runs your own engine; HTTP sends the audio " +
            "to an OpenAI-compatible service.", choices = listOf(
        ConfigChoice("Off", "off") { it.voiceBackend == StoandlConfig.VoiceBackend.OFF },
        ConfigChoice("Local model", "local") { it.voiceBackend == StoandlConfig.VoiceBackend.LOCAL },
        ConfigChoice("Custom command", "command") { it.voiceBackend == StoandlConfig.VoiceBackend.COMMAND },
        ConfigChoice("HTTP service", "http") { it.voiceBackend == StoandlConfig.VoiceBackend.HTTP },
    )),
    ConfigField("voice.local_model", "combo", G_VOICE, "Model",
        "The installed speech model the local engine uses (download one below)", ConfigApply.LIVE,
        choicesOf = ::installedModelChoices),
    text("voice.command", G_VOICE, "Custom command",
        "A command printing the transcript of {wav} (16 kHz mono WAV) in language {lang} (a code or auto)",
        placeholder = "parakeet-cli -m ~/.local/share/stoandl/stt/ggml-parakeet-tdt-0.6b-v3-q8_0.bin -t 4 -ng --no-prints -f {wav}") { it.voiceCommand },
    text("voice.url", G_VOICE, "HTTP endpoint",
        "OpenAI-compatible transcription URL (whisper.cpp whisper-server, speaches, LocalAI, a hosted service)",
        placeholder = "http://127.0.0.1:8080/v1/audio/transcriptions",
        validate = { v -> if (v.isEmpty()) null else httpUrlValid(v) }) { it.voiceUrl },
    text("voice.http_model", G_VOICE, "HTTP model", "The model the service should use, if it asks for one",
        placeholder = "whisper-1") { it.voiceHttpModel },
    secret("voice.api_key", G_VOICE, "HTTP API key",
        "Sent as a Bearer token; kept in the keyring (or a private file), never in stoandl.conf"),
    text("voice.language", G_VOICE, "Language", "The language you speak: a code such as de or en, or auto",
        placeholder = "auto",
        validate = { v -> if (v == "auto" || v.matches(Regex("[a-z]{2,3}"))) null else "expected a language code (de, en, …) or auto" }) { it.voiceLanguage },
    toggle("voice.save_audio", G_VOICE, "Keep recordings",
        "Save each dictation as a WAV in ~/.cache/stoandl/voice (for debugging)") { it.voiceSaveAudio },

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
    toggle("music.seek_when_available", G_MUSIC, "Seek instead of skip for podcasts",
        "In a player that can seek but has no next/previous track (podcasts, audiobooks), the watch's " +
            "next/previous jump 15 s forward/back. Off = they always skip.") { it.musicSeekWhenAvailable },

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
        "Consider pre-releases too, not just stable releases") { it.firmwareGithubPrereleases },
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
    toggle("connection.disconnect_persists", G_CONNECTION, "Stay disconnected after a restart",
        "A watch you disconnected by hand stays disconnected when stoandl restarts, until you connect it again") { it.connectionDisconnectPersists },

    // --- Deep sleep (phones that suspend whenever the display is off; see docs/deep-sleep.md) ---
    toggle("power.sleep_guard", G_SLEEP, "Sleep guard",
        "Hold a logind delay lock so a suspend waits until watch traffic in flight (the notification a " +
            "push wake produced) has reached the watch. Never makes a suspend fail; harmless on a desktop.",
        apply = ConfigApply.RESTART) { it.powerSleepGuard },
    int("power.sleep_guard_max_ms", G_SLEEP, "Longest hold per suspend",
        "How long a suspend waits at most for pending watch traffic (logind's own cap is 5 s). 0 doesn't " +
            "wait; stoandl still stops its own discovery before every suspend.",
        min = 0, max = 4500, unit = "ms", apply = ConfigApply.RESTART) { it.powerSleepGuardMaxMs.toIntClamped() },
    toggle("power.pause_datalog_screen_off", G_SLEEP, "Pause datalog while the display is off",
        "The watch holds back its health data (flushed every 15 min) until the display is on again: " +
            "fewer wakes on a phone that keeps the watch link across suspend") { it.powerPauseDatalogScreenOff },
    toggle("power.quiet_time_link_off", G_SLEEP, "Pause watch connection during Quiet Time",
        "Saves phone battery at night. Notifications that arrive during Quiet Time are sent to the watch " +
            "when it ends. If calls may interrupt Quiet Time, the connection stays on.") { it.powerQuietTimeLinkOff },

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

/** Lookup by conf key — used by the write path ([applyGuiConfig], behind `SetConfig`/`stoandl daemon set`). */
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
    if (field.type == "secret") return "error:$key is a secret, kept out of ${confFile.name}"
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
