package de.yoxcu.stoandl.config

/**
 * The curated subset of [StoandlConfig] keys the GUI Settings screen renders (schema-driven). Each
 * [ConfigField] carries the `stoandl.conf` [key], its [type]/[label]/[options]/[desc] for the form, and
 * a [value] reader for the current setting.
 *
 * This is a hand-maintained extra view of the config (alongside the data-class KDoc, `defaults()`, the
 * `parseXxx()` reads and `stoandl.conf.example`) — **keep it in sync** when you add a GUI-exposed key.
 * It's deliberately a small *curated* set, not the full surface (decision #5 in `docs/gui-hooks-plan.md`).
 *
 * Only `toggle` (boolean) and `combo` (fixed options) kinds are listed — the two widgets the GUI's schema
 * form renders. `GetConfig` emits `key\tvalue`; `GetConfigSchema` emits `key\ttype\tlabel\toptions\tdesc`.
 * For a combo, [value] returns one of the [options] labels; for a toggle, `true`/`false`. The matching
 * write path (`SetConfig`) is a later batch.
 */
data class ConfigField(
    val key: String,
    /** `toggle` | `combo`. */
    val type: String,
    val label: String,
    /** CSV of option labels for a combo; empty for a toggle. */
    val options: String,
    val desc: String,
    val value: (StoandlConfig) -> String,
)

private fun bool(b: Boolean): String = b.toString()

/** The curated, GUI-exposed config keys, in display order. */
val GUI_CONFIG_FIELDS: List<ConfigField> = listOf(
    ConfigField("weather.units", "combo", "Temperature units", "Metric,Imperial",
        "Unit sent to the watch's weather") { c ->
        if (c.weatherUnits == StoandlConfig.WeatherUnits.IMPERIAL) "Imperial" else "Metric"
    },
    ConfigField("weather.pins", "toggle", "Weather timeline pins", "",
        "Add sunrise/sunset pins for the primary location") { bool(it.weatherPins) },
    ConfigField("weather.reverse_geocode", "toggle", "Reverse-geocode GPS", "",
        "Name the GPS location via OSM Nominatim (sends coordinates to a web service)") { bool(it.weatherReverseGeocode) },
    ConfigField("geolocation.enabled", "toggle", "Watchapp geolocation", "",
        "Expose the device's GPS to watchapps / PKJS") { bool(it.geolocation) },
    ConfigField("notification.per_app", "toggle", "Per-app notifications", "",
        "Track apps and enforce per-app mute host-side") { bool(it.notificationPerApp) },
    ConfigField("music.enabled", "toggle", "Music control", "",
        "Bridge desktop media players to the watch's Music app") { bool(it.musicControl) },
    ConfigField("music.volume", "combo", "Volume buttons", "System,Player",
        "What the watch volume buttons control") { c ->
        if (c.musicVolume == StoandlConfig.MusicVolumeMode.PLAYER) "Player" else "System"
    },
    ConfigField("health.sync", "toggle", "Health sync", "",
        "Pull steps/sleep/HR from the watch on connect") { bool(it.healthSync) },
    ConfigField("health.export", "toggle", "Health export", "",
        "Project synced health data to NDJSON files") { bool(it.healthExport) },
    ConfigField("firmware.notify", "toggle", "Firmware update alerts", "",
        "Notify when newer firmware is available (needs a firmware source enabled)") { bool(it.firmwareNotify) },
    ConfigField("dnd.sync", "combo", "Do Not Disturb sync", "Off,To watch,To host,Both",
        "Mirror desktop Do Not Disturb and the watch's Quiet Time") { c ->
        when (c.dndSync) {
            StoandlConfig.DndSyncMode.TO_WATCH -> "To watch"
            StoandlConfig.DndSyncMode.TO_HOST -> "To host"
            StoandlConfig.DndSyncMode.BOTH -> "Both"
            StoandlConfig.DndSyncMode.OFF -> "Off"
        }
    },
)
