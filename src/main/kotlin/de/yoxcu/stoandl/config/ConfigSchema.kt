package de.yoxcu.stoandl.config

import de.yoxcu.stoandl.notification.AppNamePlacement
import de.yoxcu.stoandl.util.ConfFile
import de.yoxcu.stoandl.voice.ModelKind
import de.yoxcu.stoandl.voice.VoiceModels
import io.rebble.libpebblecommon.notification.DefaultVibePattern
import java.io.File

/**
 * The [StoandlConfig] keys the GUI Settings pages render (schema-driven), plus the write path
 * ([applyGuiConfig]) that validates and persists a single key back to `stoandl.conf`.
 *
 * This is a hand-maintained extra view of the config (alongside the data-class KDoc, the parser in
 * [StoandlConfig.load] and `stoandl.conf.example`) — **keep it in sync** when you add a key; the tests tie
 * all of them together. One key has exactly one control: the sync masters (`notification.forward`,
 * `weather.enabled`, `calendar.enabled`, …) are rows here like every other key, and `SetSyncEnabled` is only
 * an alias that writes them ([SYNC_SERVICE_KEYS]). The keys deliberately NOT in this list are the ones with
 * a richer dedicated D-Bus surface: calendar sources → `AddCalendarSource`/…, `watch.<prefId>` →
 * `SetWatchPref`, `extensions.enabled`/`extension.<name>.<key>` → the `Ext*` methods.
 * `docs/settings-parity.md` is the matrix.
 *
 * Six widget kinds exist — `toggle`, `combo`, `text`, `int`, `list` and `secret` — and the GUI renders
 * them from the schema, so a new key appears in the GUI with no GUI change:
 *
 *     GetConfig       → `key \t value`
 *     GetConfigSchema → `key \t type \t label \t options \t desc \t group \t apply \t min \t max \t unit \t
 *                        placeholder \t page \t help \t requires \t default \t egress`
 *
 * The first five columns are the original wire format; everything from `group` on is **appended**, so a
 * client built against an older contract keeps working (both front-ends index columns positionally and
 * tolerate missing ones). `value` is a combo's option label, a toggle's `true`/`false`, or the raw text /
 * number / comma-joined list ([ConfList]: `\,` escapes a comma inside an item); the write path maps
 * that back to the raw `stoandl.conf` token via [parse].
 *
 * `page` is the GUI page a row is on and `group` the section header on that page; a page's optional last
 * section is `Advanced <page>` (collapsed by the GUI). `desc` is the one-sentence subtitle and `help` the
 * longer text behind the row's help button, never repeating `desc`. `requires` is `alt('|'alt)*` with
 * `alt` = `key=value` or `key!=value` (`value` as GetConfig shows it): the row only matters while one
 * alternative holds, so the GUI disables it otherwise. `default` is the value of an empty `stoandl.conf`
 * (empty for a secret), and `egress` the hosts the key makes stoandl contact (empty: none).
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
 * [LIVE] means `persistConfig` (PebbleIntegration, the single write path) reloads the store and — where
 * a subsystem must be re-run — re-reconciles it, **or** the consumer reads the value off the live config
 * on every use.
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

/** Where a row is shown: on GUI [page], under the section header [group]. */
class ConfigSection(val page: String, val group: String)

data class ConfigField(
    val key: String,
    /** `toggle` | `combo` | `text` | `int` | `list` | `secret`. A `secret` is never in stoandl.conf: it lives
     *  in the secret store (keyring, else a 0600 file) under [secretRef]; `GetConfig` shows only `set` or
     *  empty, and writing an empty value removes it. */
    val type: String,
    val section: ConfigSection,
    val label: String,
    /** One sentence of at most 90 characters, shown under the label. */
    val desc: String,
    /** Whether a write takes effect immediately or needs a daemon restart — surfaced in the GUI. */
    val apply: ConfigApply,
    /** What [desc] leaves out, in a few sentences; behind the row's help button. */
    val help: String = "",
    /** When the row matters at all (see the file KDoc for the grammar); empty = always. */
    val requires: String = "",
    /** The hosts this key makes stoandl contact; empty = none. */
    val egress: String = "",
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

    /** The GUI page this row is on. */
    val page: String get() = section.page

    /** The section header on [page] this row is under. */
    val group: String get() = section.group

    /** The secret store reference of a `secret` key. */
    val secretRef: String get() = "config:$key"

    /** CSV of option labels for `GetConfigSchema` (empty for every non-combo kind). */
    val options: String get() = choices.joinToString(",") { it.label }

    /** Current value for `GetConfig`: a combo's selected label, else whatever [read] renders. */
    fun value(c: StoandlConfig): String = when (type) {
        "combo" -> (choices.firstOrNull { it.selected(c) } ?: choices.firstOrNull())?.label ?: ""
        else -> read?.invoke(c).orEmpty()
    }

    /** The value of an empty `stoandl.conf`; empty for a secret, and for a combo none of whose options
     *  is the default (no speech model installed yet). */
    fun defaultValue(): String {
        val d = StoandlConfig.defaults()
        return when (type) {
            "secret" -> ""
            "combo" -> choices.firstOrNull { it.selected(d) }?.label.orEmpty()
            else -> value(d)
        }
    }

    /** The `GetConfigSchema` record for this field. */
    fun schemaRow(): String = listOf(
        key, type, label, options, desc, group, apply.token,
        min?.toString().orEmpty(), max?.toString().orEmpty(), unit, placeholder,
        page, help, requires, defaultValue(), egress,
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
    key: String, s: ConfigSection, label: String, desc: String, help: String = "", requires: String = "",
    egress: String = "", apply: ConfigApply = ConfigApply.LIVE, read: (StoandlConfig) -> Boolean,
) = ConfigField(key, "toggle", s, label, desc, apply, help, requires, egress, read = { read(it).toString() })

private fun combo(
    key: String, s: ConfigSection, label: String, desc: String, help: String = "", requires: String = "",
    egress: String = "", apply: ConfigApply = ConfigApply.LIVE, choices: List<ConfigChoice>,
) = ConfigField(key, "combo", s, label, desc, apply, help, requires, egress, fixedChoices = choices)

/** A combo whose options are read on every use ([ConfigField.choicesOf]). */
private fun dynamicCombo(
    key: String, s: ConfigSection, label: String, desc: String, help: String = "", requires: String = "",
    choices: () -> List<ConfigChoice>,
) = ConfigField(key, "combo", s, label, desc, ConfigApply.LIVE, help, requires, choicesOf = choices)

private fun text(
    key: String, s: ConfigSection, label: String, desc: String, help: String = "", requires: String = "",
    egress: String = "", placeholder: String = "", apply: ConfigApply = ConfigApply.LIVE,
    validate: ((String) -> String?)? = null, read: (StoandlConfig) -> String,
) = ConfigField(key, "text", s, label, desc, apply, help, requires, egress, read = read, placeholder = placeholder,
    validate = validate)

private fun secret(key: String, s: ConfigSection, label: String, desc: String, help: String = "", requires: String = "") =
    ConfigField(key, "secret", s, label, desc, ConfigApply.LIVE, help, requires)

private fun int(
    key: String, s: ConfigSection, label: String, desc: String, help: String = "", requires: String = "",
    min: Int, max: Int, unit: String = "", apply: ConfigApply = ConfigApply.LIVE, read: (StoandlConfig) -> Int,
) = ConfigField(key, "int", s, label, desc, apply, help, requires, read = { read(it).toString() },
    min = min, max = max, unit = unit)

private fun list(
    key: String, s: ConfigSection, label: String, desc: String, help: String = "", requires: String = "",
    placeholder: String = "", validate: ((String) -> String?)? = null, read: (StoandlConfig) -> List<String>,
) = ConfigField(key, "list", s, label, desc, ConfigApply.LIVE, help, requires, read = { ConfList.encode(read(it)) },
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

// --- Options that change at runtime --------------------------------------------------------------

/**
 * What the combos whose options change at runtime offer: the installed speech-to-text models and
 * text-to-speech voices (read from disk on every use) and the user's custom vibrations (wired to
 * libpebble3's pattern store once the daemon is up; empty before). Tests swap in a fixed one, so a
 * rendered schema doesn't depend on the machine it runs on.
 */
internal data class ConfigEnvironment(
    /** What `voice.local_model` (STT) and `tts.local_voice` (TTS) can be set to, as (id, display name). */
    val installedModels: (ModelKind) -> List<Pair<String, String>> = { VoiceModels.choices(it) },
    val customVibeNames: () -> List<String> = { emptyList() },
)

@Volatile internal var configEnvironment = ConfigEnvironment()

/** The `voice.local_model` (STT) or `tts.local_voice` (TTS) options: what is installed of [kind], or a
 *  placeholder while nothing is. */
private fun installedModelChoices(kind: ModelKind): List<ConfigChoice> {
    val installed = configEnvironment.installedModels(kind)
    val (none, current) = when (kind) {
        ModelKind.STT -> "No model installed" to StoandlConfig::voiceLocalModel
        ModelKind.TTS -> "No voice installed" to StoandlConfig::ttsLocalVoice
    }
    if (installed.isEmpty()) return listOf(ConfigChoice(none, "") { true })
    return installed.map { (id, name) -> ConfigChoice(name, id) { current(it) == id } }
}

private fun reminderVibeChoices(): List<ConfigChoice> =
    listOf(ConfigChoice("Watch default", "") { it.calendarReminderVibe == null }) +
        // Value = the enum name (comma-free for the CSV options column); the config also takes the
        // display name.
        DefaultVibePattern.entries.map { p ->
            ConfigChoice(p.displayName.replace(",", ""), p.name) { c ->
                c.calendarReminderVibe.let { v -> v.equals(p.name, ignoreCase = true) || v.equals(p.displayName, ignoreCase = true) }
            }
        } +
        (runCatching { configEnvironment.customVibeNames() }.getOrNull() ?: emptyList()).map { n ->
            ConfigChoice(n.replace(",", ""), n) { it.calendarReminderVibe.equals(n, ignoreCase = true) }
        }

/** The engine choices of `voice.backend`, `voice.fallback_backend` and `tts.backend`, one list so they
 *  can't drift; [off] names the "none" option. */
private fun engineChoices(off: String, read: (StoandlConfig) -> StoandlConfig.VoiceBackend): List<ConfigChoice> =
    listOf(
        StoandlConfig.VoiceBackend.OFF to off,
        StoandlConfig.VoiceBackend.LOCAL to "Local model",
        StoandlConfig.VoiceBackend.COMMAND to "Custom command",
        StoandlConfig.VoiceBackend.HTTP to "HTTP service",
    ).map { (b, label) -> ConfigChoice(label, b.name.lowercase()) { read(it) == b } }

// --- The GUI-exposed keys, page by page, in display order ------------------------------------------

/** A page's last, collapsed section: `Advanced <page>`, unique across pages so an older GUI, which lists
 *  every section on one page and merges same-named ones, keeps them apart. */
private fun advanced(page: String) = ConfigSection(page, "Advanced ${page.lowercase()}")

private const val P_NOTIF = "Notifications"
private val S_FORWARDING = ConfigSection(P_NOTIF, "Forwarding")
private val S_NOTIF_WATCH = ConfigSection(P_NOTIF, "On the watch")
private val S_PER_APP = ConfigSection(P_NOTIF, "Per app")
private val S_CALLS = ConfigSection(P_NOTIF, "Calls")
private val S_MESSAGES = ConfigSection(P_NOTIF, "Messages from stoandl")

private const val P_WEATHER = "Weather"
private val S_WEATHER = ConfigSection(P_WEATHER, "Weather")
private val S_WEATHER_WATCH = ConfigSection(P_WEATHER, "Weather on the watch")

private const val P_CALENDAR = "Calendar"
private val S_CALENDAR = ConfigSection(P_CALENDAR, "Calendar")
private val S_REMINDERS = ConfigSection(P_CALENDAR, "Reminders")

private const val P_MUSIC = "Music"
private val S_MUSIC = ConfigSection(P_MUSIC, "Music")

private const val P_VOICE = "Voice"
private val S_STT = ConfigSection(P_VOICE, "Speech to text")
private val S_TTS = ConfigSection(P_VOICE, "Text to speech")

private const val P_HEALTH = "Health & battery"
private val S_HEALTH = ConfigSection(P_HEALTH, "Health")
private val S_BATTERY = ConfigSection(P_HEALTH, "Battery")

private const val P_APPS = "Apps & plugins"
private val S_APPS = ConfigSection(P_APPS, "Apps")
private val S_PLUGINS = ConfigSection(P_APPS, "Plugins and permissions")

private const val P_CONNECTION = "Connection & power"
private val S_CONNECTION = ConfigSection(P_CONNECTION, "Connection")
private val S_PHONE_BATTERY = ConfigSection(P_CONNECTION, "Phone battery")

private const val P_FIRMWARE = "Firmware & language"
private val S_UPDATES = ConfigSection(P_FIRMWARE, "Updates and downloads")

private val S_DEVELOPER = ConfigSection("Developer", "Developer")

private const val ALERTS_ON = "alerts.enabled=true"
private const val STT_HTTP = "voice.backend=HTTP service|voice.fallback_backend=HTTP service"
private const val STT_EGRESS = "the speech-to-text server set under Advanced (HTTP service only)"

/** Every `stoandl.conf` key the GUI Settings pages render, page by page in display order. */
val GUI_CONFIG_FIELDS: List<ConfigField> = listOf(
    // === Notifications ===
    toggle("notification.forward", S_FORWARDING, "Forward notifications",
        "Send notifications from this device and extensions to the watch",
        help = "The master switch for everything forwarded. Off drops notifications before they reach the watch; " +
            "incoming calls and firmware prompts use their own path and still arrive, as do notifications matching " +
            "an \"Always allow\" filter. For a short break use Mute temporarily on the Alerts tab.") { it.notificationForward },
    toggle("notification.only_when_locked", S_FORWARDING, "Only when the screen is off or locked",
        "Hold notifications back while you use this device",
        help = "While the display is on and the session unlocked, notifications stay here instead of also buzzing " +
            "your wrist. Urgent ones and \"Always allow\" filter matches still go through, and calls ring as usual. " +
            "Without a graphical login stoandl can't tell, so everything is forwarded.") { it.notificationOnlyWhenLocked },
    combo("dnd.sync", S_FORWARDING, "Mirror Do Not Disturb",
        "Link this device's Do Not Disturb with the watch's Quiet Time",
        help = "Desktop → watch turns on the watch's Quiet Time when you enable Do Not Disturb here; Watch → desktop " +
            "does the reverse; Both ways keeps them in step. GNOME/Phosh and Plasma are detected automatically. " +
            "Only manual Quiet Time is mirrored, not its schedules.", choices = listOf(
        ConfigChoice("Off", "off") { it.dndSync == StoandlConfig.DndSyncMode.OFF },
        ConfigChoice("Desktop → watch", "to_watch") { it.dndSync == StoandlConfig.DndSyncMode.TO_WATCH },
        ConfigChoice("Watch → desktop", "to_host") { it.dndSync == StoandlConfig.DndSyncMode.TO_HOST },
        ConfigChoice("Both ways", "both") { it.dndSync == StoandlConfig.DndSyncMode.BOTH },
    )),
    int("notification.catch_up_minutes", S_FORWARDING, "Catch up after reconnecting",
        "Also send missed notifications up to this old (minutes)",
        help = "When the watch comes back, it also gets notifications it missed within this window. Nothing from " +
            "before stoandl started or the watch was paired is sent, and nothing the watch already has. 0 sends " +
            "only what arrives after the reconnect.",
        min = 0, max = 1440, unit = "min") { it.notificationCatchUpMinutes.toIntClamped() },
    combo("notification.app_name", S_NOTIF_WATCH, "App name",
        "Where the sending app's name appears on a notification",
        help = "Small line puts it in small print under the title; Subtitle uses the large bold line unless the " +
            "source brings its own subtitle; Off leaves it out. It is never shown twice. Each app can override " +
            "this on the Alerts tab.", choices = listOf(
        AppNamePlacement.SMALL to "Small line",
        AppNamePlacement.SUBTITLE to "Subtitle",
        AppNamePlacement.OFF to "Off",
    ).map { (p, label) -> ConfigChoice(label, p.token) { it.notificationAppName == p } }),
    toggle("notification.images", S_NOTIF_WATCH, "Pictures",
        "Show photos and screenshots that notifications carry",
        help = "On Pebble Time 2 and Round 2 (firmware 4.35+), a picture attached to a notification is sent along; " +
            "sender avatars never are. At most 32 are kept on this device and deleted with their notification; " +
            "turning this off deletes them all. Single apps can be excluded on the Alerts tab.") { it.notificationImages },
    list("notification.canned_replies", S_NOTIF_WATCH, "Quick replies",
        "The reply choices the watch offers",
        help = "Used for notifications that accept replies: extension messages without their own list, and " +
            "Plasma desktop notifications with the reply patch. Empty uses the watch's own list. The watch takes " +
            "512 bytes in total, so the end of a long list is dropped.",
        placeholder = "Ok,Yes,No,Call me,Call you later") { it.notificationCannedReplies },
    toggle("notification.open_action", S_NOTIF_WATCH, "\"Open\" action",
        "Open the app on this device from the watch (Plasma)",
        help = "Adds Open to notifications that do something when clicked; choosing it does what clicking the " +
            "popup would. GNOME does not let another program click a notification, so it never appears there.") {
        it.notificationOpenAction
    },
    toggle("notification.sync_dismissals", S_NOTIF_WATCH, "Clear dismissed notifications",
        "Remove from the watch what you dismissed on this device",
        help = "A notification closed here, read in its app, or closed by an extension disappears from the watch " +
            "too. A popup that merely timed out into the history stays.") { it.notificationSyncDismissals },
    toggle("notification.per_app", S_PER_APP, "Per-app settings",
        "Remember each app so it can be muted or styled",
        help = "Every app that notifies gets an entry on the Alerts tab (mute, vibration, icon, colour, app name, " +
            "pictures), and the watch offers a Mute action. Muted notifications are dropped on this device. Off " +
            "treats every app the same.") { it.notificationPerApp },
    combo("notification.default_mute", S_PER_APP, "New apps start",
        "Mute state an app gets the first time it notifies",
        help = "Choose Muted to opt apps in one by one. Apps already in the list keep their own setting.",
        requires = "notification.per_app=true", choices = listOf(
        ConfigChoice("Unmuted", "never") { it.notificationDefaultMute == "never" },
        ConfigChoice("Muted", "always") { it.notificationDefaultMute == "always" },
        ConfigChoice("Muted on weekdays", "weekdays") { it.notificationDefaultMute == "weekdays" },
        ConfigChoice("Muted at weekends", "weekends") { it.notificationDefaultMute == "weekends" },
    )),
    list("call.dialer_apps", S_CALLS, "Phone apps",
        "Apps whose notifications the watch's call screen replaces",
        help = "While a call rings or is in progress, these apps' notifications are held back because the watch " +
            "shows its own call screen; Missed call still arrives. Their title also names the caller when the " +
            "number isn't in your contacts. \"calls\" is GNOME Calls; Plasma Mobile's Phone app needs no entry.",
        placeholder = "calls") { it.dialerApps },
    list("contacts.vcard_paths", S_CALLS, "Contacts for caller ID",
        "vCard files or folders used to name incoming callers",
        help = "Numbers are matched on their last digits, so +49… and 0… forms both work; folders are searched " +
            "recursively. The default is Plasma Mobile's address book. Nothing leaves this device.",
        placeholder = "~/.local/share/kpeoplevcard") { it.vcardPaths },
    toggle("alerts.enabled", S_MESSAGES, "Messages from stoandl",
        "stoandl's own warnings and prompts",
        help = "Pairing problems, Bluetooth blocked, an extension to set up, a permission request or a fully " +
            "charged watch. Permission prompts and the full-battery message also appear on the watch. Off only " +
            "hides them; problems are still logged and in support bundles. Firmware update messages have their " +
            "own switch.") { it.alertsEnabled },
    toggle("alerts.pairing", S_MESSAGES, "Pairing problems",
        "A watch keeps dropping, or its pairing was removed",
        help = "Shown when a watch connects and drops straight away (usually unpaired on the watch) or its pairing " +
            "was removed here, with a Re-pair or Pair button.", requires = ALERTS_ON) { it.alertsPairing },
    toggle("alerts.bluetooth", S_MESSAGES, "Bluetooth blocked",
        "Another app's Bluetooth search stops reconnecting",
        help = "A program searching for devices, often an open Bluetooth settings page, can hog the adapter so the " +
            "watch can't reconnect.", requires = ALERTS_ON) { it.alertsBluetooth },
    toggle("alerts.extensions", S_MESSAGES, "Extension needs setup",
        "An installed extension can't start yet, or keeps crashing",
        help = "Shown when an extension needs settings before it can run, or was stopped after repeated crashes. " +
            "Open Apps → Extensions.", requires = ALERTS_ON) { it.alertsExtensions },
    toggle("alerts.permissions", S_MESSAGES, "Permission requests",
        "Allow or deny an app's request on the watch and here",
        help = "With plugins on, an app that needs approval shows Allow and Deny on the watch and here. Off: no " +
            "prompt appears and the app is refused until you answer in its permissions or with `stoandl perms`.",
        requires = ALERTS_ON) { it.alertsPermissions },
    toggle("alerts.battery_full", S_MESSAGES, "Watch fully charged",
        "When a watch on the charger reaches 100 %",
        help = "Once per charge; re-arms at 97 % or below. Classic-era watches report hourly, so it can be up to an " +
            "hour late.", requires = ALERTS_ON) { it.alertsBatteryFull },
    toggle("alerts.scan_failed", S_MESSAGES, "Pairing search failed",
        "Bluetooth can't search while you pair",
        help = "Shown once per pairing attempt when discovery keeps failing to start, so no watch can be found. The " +
            "pairing status reports it either way.", requires = ALERTS_ON) { it.alertsScanFailed },
    toggle("notification.sync_to_watch", advanced(P_NOTIF), "Send the app list to the watch",
        "For future firmware with per-app settings on the watch",
        help = "Copies the per-app list and mute states into the watch's database, as the official app does. " +
            "Current PebbleOS has no screen for it, so this normally changes nothing; muting works here either way.",
        apply = ConfigApply.RESTART) { it.notificationSyncToWatch },

    // === Weather ===
    toggle("weather.enabled", S_WEATHER, "Weather",
        "Send forecasts to the watch's Weather app",
        help = "Forecasts come from Open-Meteo (free, no account) for the places below; nothing is fetched until a " +
            "place exists. Off stops these fetches and the pins but keeps your places. Weather for classic " +
            "watchfaces and Name places from coordinates have their own switches and keep working.",
        egress = "api.open-meteo.com") { it.weatherEnabled },
    list("weather.locations", S_WEATHER, "Places",
        "Fixed places, as Name:latitude:longitude",
        help = "For example Berlin:52.52:13.405; the name is what the watch shows. Use the search below to add one " +
            "without coordinates. The first place is the main one (sunrise and sunset pins) unless Current " +
            "location is on.",
        placeholder = "Berlin:52.52:13.405", validate = ::weatherLocationsValid) {
        it.weatherLocations.map { l -> "${l.name}:${l.latitude}:${l.longitude}" }
    },
    toggle("weather.place_search", S_WEATHER, "Find places by name",
        "Search a city to add it",
        help = "Turns on the search box below and `stoandl weather search`. Only the text you type is sent, and " +
            "only when you search.",
        requires = "weather.enabled=true", egress = "geocoding-api.open-meteo.com") { it.weatherPlaceSearch },
    toggle("weather.gps", S_WEATHER, "Current location",
        "Add weather for where you are, via the location service",
        help = "Adds a place that follows you, found by GeoClue at city level (usually Wi-Fi and cell towers). It " +
            "is shown first and updated on every refresh. GeoClue must allow stoandl: a location agent asks you, " +
            "or allow the app id under Apps & plugins → Advanced.") { it.weatherGps },
    toggle("weather.reverse_geocode", S_WEATHER, "Name places from coordinates",
        "Look up town names for coordinates",
        help = "Turns the current location, and coordinates that classic watchfaces ask about, into a town name. " +
            "Off by default because it sends your position to a third-party service; without it the watch shows " +
            "the label under Advanced.",
        egress = "nominatim.openstreetmap.org") { it.weatherReverseGeocode },
    toggle("weather.pins", S_WEATHER_WATCH, "Sunrise and sunset pins",
        "Add them to the watch's timeline",
        help = "Pins for today and the next two days for the main place, with the other places in the details, as " +
            "the original app did.") { it.weatherPins },
    toggle("weather.intercept_pkjs", S_WEATHER_WATCH, "Weather for classic watchfaces",
        "Answer old watchfaces whose weather service shut down",
        help = "Many 2014–2016 faces ask Yahoo or OpenWeatherMap 2.5, which no longer answer, so they show \"--\". " +
            "stoandl answers instead, from Open-Meteo, as the official app does. Most such faces also need " +
            "Location for watchapps.",
        egress = "api.open-meteo.com") { it.weatherInterceptPkjs },
    int("weather.interval", S_WEATHER_WATCH, "Refresh every",
        "How often forecasts are fetched (minutes)",
        help = "When a watch connects, weather is refetched only if it is older than this. Shorter is fresher but " +
            "means more requests and wake-ups; on a sleeping phone a due refresh runs at the next wake.",
        min = 5, max = 1440, unit = "min") { it.weatherIntervalMinutes.toIntClamped() },
    combo("weather.location_source", advanced(P_WEATHER), "Also import places from",
        "Add places from your desktop's weather app",
        help = "GNOME Weather reads places saved in GNOME/Phosh Weather. A command runs the program below, one " +
            "Name:lat:lon line per place. Imported places are re-read on every refresh. Plasma keeps no " +
            "coordinates.", choices = listOf(
        ConfigChoice("None", "manual") { it.weatherLocationSource == StoandlConfig.WeatherLocationSource.MANUAL },
        ConfigChoice("GNOME Weather", "gnome") { it.weatherLocationSource == StoandlConfig.WeatherLocationSource.GNOME },
        ConfigChoice("A command", "command") { it.weatherLocationSource == StoandlConfig.WeatherLocationSource.COMMAND },
    )),
    text("weather.location_command", advanced(P_WEATHER), "Place command",
        "Prints one Name:lat:lon line per place",
        help = "Runs on every refresh, as you, without a sandbox. If it calls a web geocoder, that request is one " +
            "you chose.",
        requires = "weather.location_source=A command", placeholder = "/usr/local/bin/my-locations") {
        it.weatherLocationCommand
    },
    text("weather.gps_name", advanced(P_WEATHER), "Current location label",
        "Shown when no place name is looked up",
        help = "The name the watch shows for the current location when name lookup is off or finds nothing.",
        requires = "weather.gps=true", placeholder = "Current location") { it.weatherGpsName },

    // === Calendar ===
    toggle("calendar.enabled", S_CALENDAR, "Calendar",
        "Put upcoming events on the watch's timeline",
        help = "Events from yesterday to a week ahead become timeline pins, with reminders if on. Needs a source: " +
            "local calendars found automatically, or accounts and feeds you add. Off removes the calendar pins " +
            "until turned back on.",
        egress = "the CalDAV and iCal servers you add") { it.calendarEnabled },
    toggle("calendar.discover", S_CALENDAR, "Find local calendars",
        "Use calendars stored on this device",
        help = "Looks for .ics calendars your desktop keeps: Calindori on Plasma Mobile, ~/.calendars and " +
            "~/calendars. Nothing leaves this device. Online calendars are added under Accounts and feeds.") {
        it.calendarDiscover
    },
    toggle("calendar.reminders", S_REMINDERS, "Event reminders",
        "The watch buzzes before events",
        help = "Uses each event's own reminders. Off keeps the pins but the watch stays quiet.") { it.calendarReminders },
    dynamicCombo("calendar.reminder_vibe", S_REMINDERS, "Reminder vibration",
        "Vibration pattern for calendar reminders",
        help = "Watch default uses the watch's own pattern. Your own patterns appear here too.",
        requires = "calendar.reminders=true", choices = ::reminderVibeChoices),
    toggle("calendar.show_declined", S_REMINDERS, "Show declined events",
        "Keep invitations you declined on the timeline",
        help = "Off hides them. Turn on to see your full schedule.") { it.calendarShowDeclined },
    int("calendar.sync_interval", advanced(P_CALENDAR), "Re-read every",
        "How often calendars are checked for changes (minutes)",
        help = "Local files are picked up when they change anyway; this mainly sets how often CalDAV accounts and " +
            "iCal links are fetched, and moves the timeline window forward.",
        min = 5, max = 1440, unit = "min") { it.calendarSyncIntervalMinutes.toIntClamped() },

    // === Music ===
    toggle("music.enabled", S_MUSIC, "Music control",
        "Control media players from the watch",
        help = "Works with any MPRIS player (most Linux players and browsers). Nothing leaves this device.") {
        it.musicControl
    },
    combo("music.volume", S_MUSIC, "Volume buttons change",
        "System volume or the player's own volume",
        help = "System volume works like the device's volume keys; Player volume changes only the playing app, " +
            "which not every player supports.",
        requires = "music.enabled=true", choices = listOf(
        ConfigChoice("System volume", "system") { it.musicVolume == StoandlConfig.MusicVolumeMode.SYSTEM },
        ConfigChoice("Player volume", "player") { it.musicVolume == StoandlConfig.MusicVolumeMode.PLAYER },
    )),
    toggle("music.seek_when_available", S_MUSIC, "Skip within podcasts",
        "Next and previous jump 15 s in podcasts and audiobooks",
        help = "In a player that can seek but has no next or previous track, the watch's buttons jump 15 seconds. " +
            "Off: they always skip tracks.", requires = "music.enabled=true") { it.musicSeekWhenAvailable },
    text("music.volume_up_command", advanced(P_MUSIC), "Volume-up command",
        "Replaces the detected system volume tool",
        help = "By default stoandl finds wpctl, pactl or amixer. Set both commands to use your own; each runs " +
            "once per button press.",
        requires = "music.volume=System volume", placeholder = "wpctl set-volume @DEFAULT_SINK@ 5%+") {
        it.musicVolumeUpCommand
    },
    text("music.volume_down_command", advanced(P_MUSIC), "Volume-down command",
        "Replaces the detected system volume tool",
        help = "Both commands must be set to take effect.",
        requires = "music.volume=System volume", placeholder = "wpctl set-volume @DEFAULT_SINK@ 5%-") {
        it.musicVolumeDownCommand
    },

    // === Voice ===
    combo("voice.backend", S_STT, "Speech to text",
        "Turn what you say into the watch into text",
        help = "Used for voice replies, dictation in watchapps and voice notes from extensions. Local model runs on " +
            "this device (a few seconds of CPU per dictation); Custom command runs your own engine; HTTP service " +
            "sends the recording to an OpenAI-compatible server.",
        egress = STT_EGRESS, choices = engineChoices("Off") { it.voiceBackend }),
    dynamicCombo("voice.local_model", S_STT, "Model",
        "The downloaded model Local model runs",
        help = "Download one below; bigger models are more accurate but slower. Parakeet v3 understands many " +
            "languages.", choices = { installedModelChoices(ModelKind.STT) }),
    text("voice.language", S_STT, "Language",
        "The language you speak, or auto",
        help = "A code such as en or de helps engines that guess badly. Passed to a custom command as {lang} and " +
            "to the HTTP service.",
        requires = "voice.backend!=Off", placeholder = "auto",
        validate = { v -> if (v == "auto" || v.matches(Regex("[a-z]{2,3}"))) null else "expected a language code (de, en, …) or auto" }) {
        it.voiceLanguage
    },
    combo("voice.fallback_backend", S_STT, "Second engine",
        "Tried when the first isn't ready, fails or is too slow",
        help = "Also used when the first hears nothing. The watch waits 14 s for a dictation, so with a second " +
            "engine the first gets 7 of them.",
        requires = "voice.backend!=Off", egress = STT_EGRESS, choices = engineChoices("None") { it.voiceFallbackBackend }),
    combo("tts.backend", S_TTS, "Text to speech",
        "Read assistant answers aloud on the watch speaker",
        help = "Needs a watch with a speaker (Pebble Time 2, Pebble 2 Duo). Local model runs Piper or espeak-ng on " +
            "this device; Custom command runs your own engine; HTTP service sends the text to an " +
            "OpenAI-compatible speech server. The watch's speaker volume and mute apply.",
        egress = "the speech server set under Advanced (HTTP service only)",
        choices = engineChoices("Off") { it.ttsBackend }),
    dynamicCombo("tts.local_voice", S_TTS, "Voice",
        "A downloaded Piper voice, or espeak-ng",
        help = "Download voices below; \"low\" voices are enough for the watch speaker and fastest. espeak-ng needs " +
            "no download and speaks every language, but sounds robotic.",
        requires = "tts.backend=Local model", choices = { installedModelChoices(ModelKind.TTS) }),
    int("tts.speed", S_TTS, "Speaking rate",
        "100 % is normal speed",
        help = "Applied as Piper's length scale, espeak's words per minute, or the HTTP speed field.",
        requires = "tts.backend!=Off", min = 50, max = 200, unit = "%") { it.ttsSpeed },
    text("voice.command", advanced(P_VOICE), "Custom command",
        "Prints the text spoken in {wav}",
        help = "Runs for each dictation with {wav} (16 kHz mono WAV) and {lang}; whatever it prints is the text. " +
            "See docs/voice.md.",
        requires = "voice.backend=Custom command|voice.fallback_backend=Custom command",
        placeholder = "parakeet-cli -m ~/.local/share/stoandl/stt/ggml-parakeet-tdt-0.6b-v3-q8_0.bin -t 4 -ng --no-prints -f {wav}") {
        it.voiceCommand
    },
    text("voice.url", advanced(P_VOICE), "Server address",
        "OpenAI-compatible transcription URL",
        help = "For example whisper-server, speaches or LocalAI on this device or your network, or a hosted " +
            "service. Each recording is sent here.",
        requires = STT_HTTP, egress = "this address", placeholder = "http://127.0.0.1:8080/v1/audio/transcriptions",
        validate = { v -> if (v.isEmpty()) null else httpUrlValid(v) }) { it.voiceUrl },
    text("voice.http_model", advanced(P_VOICE), "Server model",
        "Model name, if the server needs one",
        help = "Sent as the model field, for example whisper-1.",
        requires = STT_HTTP, placeholder = "whisper-1") { it.voiceHttpModel },
    secret("voice.api_key", advanced(P_VOICE), "Server API key",
        "Kept in the keyring, never in stoandl.conf",
        help = "Sent as a Bearer token. Stored in the system keyring, or a private file next to stoandl.conf when " +
            "there is none; left out of backups.", requires = STT_HTTP),
    toggle("voice.save_audio", advanced(P_VOICE), "Keep recordings",
        "Save each dictation as a WAV file, for troubleshooting",
        help = "Recordings go to ~/.cache/stoandl/voice. They contain your voice: turn this off and delete the " +
            "folder when done.", requires = "voice.backend!=Off") { it.voiceSaveAudio },
    text("tts.command", advanced(P_VOICE), "Speech command",
        "Reads text on stdin, writes a WAV file to stdout",
        help = "For engines installed by hand (Kokoro, sherpa-onnx, RHVoice). {lang} is replaced by the language.",
        requires = "tts.backend=Custom command", placeholder = "my-tts --lang {lang}") { it.ttsCommand },
    text("tts.url", advanced(P_VOICE), "Speech server address",
        "OpenAI-compatible /v1/audio/speech URL",
        help = "Each answer's text is sent here. Speaches, LocalAI or openedai-speech can run Piper or Kokoro on a " +
            "computer on your network; hosted services charge per character.",
        requires = "tts.backend=HTTP service", egress = "this address",
        placeholder = "http://127.0.0.1:8000/v1/audio/speech",
        validate = { v -> if (v.isEmpty()) null else httpUrlValid(v) }) { it.ttsUrl },
    text("tts.http_model", advanced(P_VOICE), "Speech server model",
        "For example tts-1",
        help = "Leave empty for servers that ignore it.",
        requires = "tts.backend=HTTP service", placeholder = "tts-1") { it.ttsHttpModel },
    text("tts.http_voice", advanced(P_VOICE), "Speech server voice",
        "For example alloy",
        help = "Leave empty for the server's default.",
        requires = "tts.backend=HTTP service", placeholder = "alloy") { it.ttsHttpVoice },
    secret("tts.api_key", advanced(P_VOICE), "Speech server API key",
        "Kept in the keyring, never in stoandl.conf",
        help = "Separate from the speech-to-text key so a key is never sent to a server it wasn't meant for. " +
            "Empty sends no key.", requires = "tts.backend=HTTP service"),

    // === Health & battery ===
    toggle("health.sync", S_HEALTH, "Health data",
        "Ask the watch for steps, sleep and heart rate on connect",
        help = "On each connection stoandl asks for data newer than what it already has (the very first time that " +
            "is everything), but not again within 15 minutes of the last request. While connected the watch also " +
            "sends new records about every 15 minutes. The data stays here and fills the Health tab.") { it.healthSync },
    toggle("health.export", S_HEALTH, "Export to files",
        "Write daily summaries and workouts for other tools",
        help = "Writes daily.ndjson and activities.ndjson (one JSON record per line) under ~/.config/stoandl/health " +
            "whenever new data arrives. Nothing is uploaded.") { it.healthExport },
    toggle("health.export_samples", S_HEALTH, "Include per-minute data",
        "Add steps and heart rate for every minute",
        help = "One file per day under health/samples; much larger than the summaries.",
        requires = "health.export=true") { it.healthExportSamples },
    int("health.export_days", S_HEALTH, "Export the last",
        "How many recent days each export rewrites (days)",
        help = "Picks up late changes, such as sleep the watch finalises later; older days stay as written. " +
            "Larger values take longer on a slow phone.",
        requires = "health.export=true", min = 1, max = 365, unit = "days") { it.healthExportDays },
    toggle("battery.heartbeat", S_BATTERY, "Battery insights",
        "Read the watch's hourly battery report",
        help = "Decodes the watch's hourly diagnostic record (charge, voltage, the watch's own time-to-empty) for " +
            "the Battery page. Works for classic watches and across disconnects; stays on this device.") {
        it.batteryHeartbeat
    },
    toggle("battery.history", S_BATTERY, "Battery level log",
        "Record the level the watch reports over Bluetooth",
        help = "Records every change while on. The Battery page prefers the hourly report and uses this log when " +
            "the report can't be read, for example on very new firmware.") { it.batteryHistory },
    int("battery.retention_days", S_BATTERY, "Keep battery history for",
        "Older entries are deleted (days)",
        help = "Applies to both sources.",
        requires = "battery.heartbeat=true|battery.history=true", min = 1, max = 3650, unit = "days") {
        it.batteryRetentionDays
    },

    // === Apps & plugins ===
    toggle("apps.order_faces_by_last_used", S_APPS, "Recently used faces first",
        "Move the watchface you switch to to the top",
        help = "As in the official app. Off keeps the order you arranged on the Apps tab.") { it.appsOrderFacesByLastUsed },
    toggle("geolocation.enabled", S_APPS, "Location for watchapps",
        "Let watchapps ask for this device's location",
        help = "Watchapps and faces that ask for your position (navigation, sports, many weather faces) get it from " +
            "GeoClue: street level, or GPS for apps that ask for high accuracy. Off by default because any app " +
            "that asks gets your location.") { it.geolocation },
    toggle("plugins.enabled", S_PLUGINS, "Plugins",
        "Data and actions from this device that apps can use",
        help = "Lets watchapps use built-in plugins (weather, music, calendar, phone, watch settings), plugins an " +
            "app brings, and those extensions offer. Also turns on app permissions. Off by default: no plugin " +
            "code runs and nothing is fetched.",
        egress = "only the hosts you allow apps to reach") { it.pluginsEnabled },
    combo("plugins.permission_policy", S_PLUGINS, "Ask before using data",
        "When an app has to ask you before it may use a permission",
        help = "When data could leave asks before every action and before anything that can send data off this " +
            "device, but lets an app without a companion app or internet access read without asking. Always also " +
            "asks before every read. Never grants whatever would need asking; only what you denied stays denied.",
        requires = "plugins.enabled=true", choices = listOf(
        ConfigChoice("When data could leave", "ask_if_exfiltrating") {
            it.pluginsPermissionPolicy == StoandlConfig.PermissionPolicyMode.ASK_IF_EXFILTRATING },
        ConfigChoice("Always", "always_ask") { it.pluginsPermissionPolicy == StoandlConfig.PermissionPolicyMode.ALWAYS_ASK },
        ConfigChoice("Never", "never_ask") { it.pluginsPermissionPolicy == StoandlConfig.PermissionPolicyMode.NEVER_ASK },
    )),
    toggle("plugins.ask_legacy_apps", S_PLUGINS, "Ask older apps too",
        "Apply permissions to apps made before they existed",
        help = "Older apps keep internet access and what they declared, so they keep working. On makes them ask " +
            "like newer apps; some may not work until allowed.", requires = "plugins.enabled=true") {
        it.pluginsAskLegacyApps
    },
    text("weather.gps_desktop_id", advanced(P_APPS), "Location service app id",
        "Must match the GeoClue allow rule",
        help = "The id stoandl gives GeoClue, for both Location for watchapps and current-location weather. " +
            "Change it only if /etc/geoclue/geoclue.conf allows a different id.",
        placeholder = "stoandl") { it.weatherGpsDesktopId },

    // === Connection & power ===
    toggle("connection.autoswitch", S_CONNECTION, "Follow the wrist",
        "With several watches, connect the one in range",
        help = "stoandl tries the most recently used watch first and moves on if it is out of range. A working " +
            "connection is never dropped to switch.") { it.connectionAutoswitch },
    toggle("connection.disconnect_persists", S_CONNECTION, "Stay disconnected after restart",
        "A manual disconnect survives restarts",
        help = "A watch you disconnect stays disconnected until you connect it again, even across restarts, like " +
            "the official app.") { it.connectionDisconnectPersists },
    toggle("classic.discover", S_CONNECTION, "Bluetooth Classic",
        "For Pebble Time, Time Steel and Time Round (experimental)",
        help = "Classic-era watches connect more reliably over Bluetooth Classic. With this on, pairing also " +
            "searches for them and they reconnect over Classic; it costs nothing while no such watch is paired.",
        apply = ConfigApply.RESTART) { it.classicDiscover },
    toggle("power.quiet_time_link_off", S_PHONE_BATTERY, "Disconnect during Quiet Time",
        "Save phone battery overnight",
        help = "During the watch's scheduled Quiet Time, once the screen has been off for 10 minutes, the " +
            "connection pauses until Quiet Time ends or the screen comes on; notifications from that time are " +
            "delivered then. If calls may interrupt Quiet Time, the connection stays on.") { it.powerQuietTimeLinkOff },
    toggle("power.pause_datalog_screen_off", S_PHONE_BATTERY, "Hold watch data while the screen is off",
        "Fewer wake-ups; health data arrives later",
        help = "The watch sends health and app data about every 15 minutes, which wakes a sleeping phone about " +
            "four times an hour. With this on it keeps the data until the screen is on. Only useful on a phone " +
            "that stays connected while suspended.") { it.powerPauseDatalogScreenOff },
    toggle("power.sleep_guard", advanced(P_CONNECTION), "Finish sending before suspend",
        "Briefly delay suspend for pending watch traffic",
        help = "Before suspend, stoandl asks the system to wait until anything on its way to the watch has " +
            "arrived. It never stops a suspend and releases at once when nothing is pending, so it is harmless on " +
            "a desktop.", apply = ConfigApply.RESTART) { it.powerSleepGuard },
    int("power.sleep_guard_max_ms", advanced(P_CONNECTION), "Longest wait",
        "Upper limit per suspend (ms)",
        help = "The system's own limit is usually 5 s; 0 doesn't wait. Raise it if notifications that wake the " +
            "phone sometimes only arrive on its next wake.",
        requires = "power.sleep_guard=true", min = 0, max = 4500, unit = "ms", apply = ConfigApply.RESTART) {
        it.powerSleepGuardMaxMs.toIntClamped()
    },

    // === Firmware & language ===
    toggle("firmware.github", S_UPDATES, "Check for Core firmware",
        "Pebble 2 Duo and Time 2 updates, from GitHub",
        help = "Looks for newer PebbleOS releases on GitHub and installs them. Installing a .pbz you already have " +
            "works without it (Diagnostics & developer → Flash firmware).",
        egress = "api.github.com, github.com") { it.firmwareGithub },
    toggle("firmware.cohorts", S_UPDATES, "Check for classic firmware",
        "Older Pebble updates, from Rebble",
        help = "The same for classic watches (Pebble, Time, Time Steel, Time Round, Pebble 2).",
        egress = "cohorts.rebble.io, or the update server set under Advanced") { it.firmwareCohorts },
    toggle("firmware.notify", S_UPDATES, "Tell me about updates",
        "Show a message here and on the watch when one exists",
        help = "Checks when a watch connects, at most once a day, with an Update button. Off: updates are only " +
            "found when you check yourself.",
        requires = "firmware.github=true|firmware.cohorts=true") { it.firmwareNotify },
    toggle("firmware.github_prereleases", S_UPDATES, "Include pre-releases",
        "Also offer beta firmware from GitHub",
        help = "Earlier fixes and features, possibly new bugs. The newest version with a build for your watch is " +
            "offered.", requires = "firmware.github=true") { it.firmwareGithubPrereleases },
    toggle("language.download", S_UPDATES, "Download language packs",
        "Install watch languages from the online catalog",
        help = "Downloads a pack and installs it on the watch. Installing a .pbl you already have always works.",
        egress = "binaries.rebble.io, or GitHub for community packs") { it.languageDownload },
    text("firmware.github_repo", advanced(P_FIRMWARE), "GitHub repository",
        "owner/repo that publishes the firmware",
        help = "Change only to follow a fork that publishes normal_‹board›_‹version›.pbz releases.",
        requires = "firmware.github=true", placeholder = "coredevices/PebbleOS", validate = ::githubRepoValid) {
        it.firmwareGithubRepo
    },
    text("firmware.cohorts_url", advanced(P_FIRMWARE), "Rebble update server",
        "Base URL, for a self-hosted mirror",
        help = "Every classic firmware check asks this server instead of Rebble's.",
        requires = "firmware.cohorts=true", egress = "this address", placeholder = "https://cohorts.rebble.io",
        validate = ::httpUrlValid) { it.firmwareCohortsUrl },

    // === Developer (shown at the top of Diagnostics & developer) ===
    toggle("developer.autostart", S_DEVELOPER, "Start developer connection automatically",
        "No password: anyone on your network can install apps",
        help = "Starts the developer connection (port 9000, all interfaces) whenever a watch connects, so the " +
            "Pebble SDK and CloudPebble can install and debug apps. Leave off and start it by hand when needed.") {
        it.developerAutostart
    },
    toggle("datalog.enabled", S_DEVELOPER, "Save app data logs",
        "Store what watchapps log, for developers",
        help = "Saves DataLogging data under ~/.config/stoandl/datalog, readable with `stoandl datalog`. A chatty " +
            "app can fill the disk.", apply = ConfigApply.RESTART) { it.datalog },
)

/**
 * The `stoandl.conf` key behind each sync service of `GetSyncStatus`/`SetSyncEnabled`. `SetSyncEnabled` is
 * only an alias that writes this key (dnd's is four-way, so it has none), and `GetSyncStatus` names it, so a
 * client finds the one settings row that controls the service.
 */
val SYNC_SERVICE_KEYS: Map<String, String> = linkedMapOf(
    "notifications" to "notification.forward",
    "weather" to "weather.enabled",
    "calendar" to "calendar.enabled",
    "music" to "music.enabled",
    "health" to "health.sync",
    "dnd" to "dnd.sync",
)

/** Lookup by conf key — used by the write path ([applyGuiConfig], behind `SetConfig`/`stoandl daemon set`). */
fun guiConfigField(key: String): ConfigField? = GUI_CONFIG_FIELDS.firstOrNull { it.key == key }

/**
 * Validate and persist a single GUI config key to [confFile]. Maps the GUI's value (a toggle's
 * `true`/`false`, a combo's option label, or raw text/number/list) to the raw `stoandl.conf` token and
 * upserts it atomically (via [ConfFile], sharing the lock with the extension-config writers). The
 * **caller** (PebbleIntegration's `persistConfig`, the single write path for `SetConfig`, `SetSyncEnabled`
 * and the daemon's own writes) reloads the live [ConfigStore] and re-reconciles the affected subsystem
 * after this returns `ok:`, so a [ConfigApply.LIVE] change takes effect without a restart. Returns a status-prefixed string: `ok:`, `notfound:` (unknown key), or `error:` (bad value /
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
