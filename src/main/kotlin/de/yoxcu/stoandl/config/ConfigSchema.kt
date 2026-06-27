package de.yoxcu.stoandl.config

import de.yoxcu.stoandl.util.ConfFile
import java.io.File

/**
 * The curated subset of [StoandlConfig] keys the GUI Settings screen renders (schema-driven), plus the
 * write path ([applyGuiConfig]) that persists a single key back to `stoandl.conf`.
 *
 * This is a hand-maintained extra view of the config (alongside the data-class KDoc, `defaults()`, the
 * `parseXxx()` reads and `stoandl.conf.example`) — **keep it in sync** when you add a GUI-exposed key.
 * It's deliberately a small *curated* set, not the full surface (decision #5 in `docs/gui-hooks-plan.md`).
 *
 * Only `toggle` (boolean) and `combo` (fixed options) kinds exist — the two widgets the GUI's schema form
 * renders. `GetConfig` emits `key\tvalue`; `GetConfigSchema` emits `key\ttype\tlabel\toptions\tdesc`.
 * For a combo, [ConfigField.value] returns one of the option labels; for a toggle, `true`/`false`. The
 * write path maps that label/bool back to the raw `stoandl.conf` token via [ConfigField.toConfToken].
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

data class ConfigField(
    val key: String,
    /** `toggle` | `combo`. */
    val type: String,
    val label: String,
    val desc: String,
    /** Combo options in display order; empty for a toggle. */
    val choices: List<ConfigChoice> = emptyList(),
    /** Toggle: reads the current boolean. Null for a combo. */
    val read: ((StoandlConfig) -> Boolean)? = null,
) {
    /** CSV of option labels for `GetConfigSchema` (empty for a toggle). */
    val options: String get() = choices.joinToString(",") { it.label }

    /** Current value for `GetConfig`: a combo's selected label, or a toggle's `true`/`false`. */
    fun value(c: StoandlConfig): String = when (type) {
        "combo" -> (choices.firstOrNull { it.selected(c) } ?: choices.firstOrNull())?.label ?: ""
        else -> (read?.invoke(c) ?: false).toString()
    }

    /** Map a GUI-submitted value to the raw `stoandl.conf` token, or null if it isn't valid for this
     *  field. Combos accept either the option label or the token (case-insensitive); toggles accept the
     *  usual boolean words and normalise to `true`/`false`. */
    fun toConfToken(input: String): String? {
        val t = input.trim()
        return when (type) {
            "combo" -> choices.firstOrNull { it.label.equals(t, true) || it.token.equals(t, true) }?.token
            else -> when (t.lowercase()) {
                "true", "yes", "on", "1" -> "true"
                "false", "no", "off", "0" -> "false"
                else -> null
            }
        }
    }
}

private fun toggle(key: String, label: String, desc: String, read: (StoandlConfig) -> Boolean) =
    ConfigField(key, "toggle", label, desc, read = read)

private fun combo(key: String, label: String, desc: String, choices: List<ConfigChoice>) =
    ConfigField(key, "combo", label, desc, choices = choices)

/** The curated, GUI-exposed config keys, in display order. */
val GUI_CONFIG_FIELDS: List<ConfigField> = listOf(
    combo("weather.units", "Temperature units", "Unit sent to the watch's weather", listOf(
        ConfigChoice("Metric", "metric") { it.weatherUnits == StoandlConfig.WeatherUnits.METRIC },
        ConfigChoice("Imperial", "imperial") { it.weatherUnits == StoandlConfig.WeatherUnits.IMPERIAL },
    )),
    toggle("weather.pins", "Weather timeline pins",
        "Add sunrise/sunset pins for the primary location") { it.weatherPins },
    toggle("weather.reverse_geocode", "Reverse-geocode GPS",
        "Name the GPS location via OSM Nominatim (sends coordinates to a web service)") { it.weatherReverseGeocode },
    toggle("geolocation.enabled", "Watchapp geolocation",
        "Expose the device's GPS to watchapps / PKJS") { it.geolocation },
    toggle("calendar.discover", "Auto-discover local calendars",
        "Find the desktop's local .ics calendars (Calindori, ~/.calendars). No egress.") { it.calendarDiscover },
    toggle("notification.per_app", "Per-app notifications",
        "Track apps and enforce per-app mute host-side") { it.notificationPerApp },
    toggle("music.enabled", "Music control",
        "Bridge desktop media players to the watch's Music app") { it.musicControl },
    combo("music.volume", "Volume buttons", "What the watch volume buttons control", listOf(
        ConfigChoice("System", "system") { it.musicVolume == StoandlConfig.MusicVolumeMode.SYSTEM },
        ConfigChoice("Player", "player") { it.musicVolume == StoandlConfig.MusicVolumeMode.PLAYER },
    )),
    toggle("health.sync", "Health sync",
        "Pull steps/sleep/HR from the watch on connect") { it.healthSync },
    toggle("health.export", "Health export",
        "Project synced health data to NDJSON files") { it.healthExport },
    toggle("firmware.notify", "Firmware update alerts",
        "Notify when newer firmware is available (needs a firmware source enabled)") { it.firmwareNotify },
    combo("dnd.sync", "Do Not Disturb sync", "Mirror desktop Do Not Disturb and the watch's Quiet Time", listOf(
        ConfigChoice("Off", "off") { it.dndSync == StoandlConfig.DndSyncMode.OFF },
        ConfigChoice("To watch", "to_watch") { it.dndSync == StoandlConfig.DndSyncMode.TO_WATCH },
        ConfigChoice("To host", "to_host") { it.dndSync == StoandlConfig.DndSyncMode.TO_HOST },
        ConfigChoice("Both", "both") { it.dndSync == StoandlConfig.DndSyncMode.BOTH },
    )),
)

/**
 * Validate and persist a single GUI config key to [confFile]. Maps the GUI's value (a toggle's
 * `true`/`false` or a combo's option label) to the raw `stoandl.conf` token and upserts it atomically
 * (via [ConfFile], sharing the lock with the extension-config writers). The **caller** (PebbleIntegration's
 * `setConfigLive`) reloads the live [ConfigStore] and re-reconciles the affected subsystem after this
 * returns `ok:`, so the change takes effect without a restart. Returns a status-prefixed string: `ok:`,
 * `notfound:` (unknown key), or `error:` (bad value / IO).
 */
fun applyGuiConfig(key: String, value: String, confFile: File): String {
    val field = GUI_CONFIG_FIELDS.firstOrNull { it.key == key }
        ?: return "notfound:no config key '$key'"
    val token = field.toConfToken(value)
        ?: return "error:invalid value '$value' for $key" +
            (if (field.type == "combo") " (expected one of ${field.options})" else "")
    return try {
        ConfFile.upsert(confFile, mapOf(key to token))
        "ok:$key = $token"
    } catch (e: Exception) {
        "error:${e.message ?: "failed to write ${confFile.name}"}"
    }
}
