package de.yoxcu.stoandl.config

import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.File

private val log = KotlinLogging.logger {}

/**
 * Daemon configuration, loaded once at startup from a simple `key = value` file (`#` starts a
 * comment; list values are comma-separated). Lives at `$XDG_CONFIG_HOME/stoandl/stoandl.conf`
 * (default `~/.config/stoandl/stoandl.conf`). A missing/unreadable file yields defaults.
 *
 * Editing the file requires a daemon restart to take effect.
 */
data class StoandlConfig(
    /** Track every observed desktop app in a per-app store and enforce per-app mute state host-side
     *  (drop before send) before a notification reaches the watch. Apps are lazy-added the first time
     *  they notify, with [notificationDefaultMute]. On by default; control per app via `stoandl notif`
     *  (or the "Mute" action on a notification on the watch). Local-only. */
    val notificationPerApp: Boolean,
    /** Mute state applied to a newly observed app: `never` (deliver), `always` (mute), or the
     *  day-of-week schedules `weekdays`/`weekends`. */
    val notificationDefaultMute: String,
    /** Sync the per-app list + mute states to the watch (libpebble3's `NotificationAppItem` →
     *  BlobDB). **Off by default**: current Core/PebbleOS firmware exposes no per-app notification
     *  UI on the watch (Settings→Notifications is global-only, and notifications carry no per-app
     *  "Mute" action), so the synced records surface nowhere — mute is enforced host-side regardless.
     *  Kept as an opt-in for firmware that does surface it (the official Core phone app pushes the
     *  same records). BLE-only, no web egress. */
    val notificationSyncToWatch: Boolean,
    /** Telephony/dialer app-name substrings. Their notifications are suppressed from the watch (the
     *  native call screen replaces them) and their title is used as a fallback caller name. */
    val dialerApps: List<String>,
    /** vCard files or directories scanned for caller-ID resolution. */
    val vcardPaths: List<String>,
    /** Manually-configured locations to fetch weather for. Merged with any [weatherLocationSource]
     *  results. Empty (with no GPS and no source) disables weather sync. */
    val weatherLocations: List<WeatherLocation>,
    /** Where additional fixed locations come from besides [weatherLocations]: the DE's own weather
     *  config ([WeatherLocationSource.GNOME]) or a user command ([WeatherLocationSource.COMMAND]). */
    val weatherLocationSource: WeatherLocationSource,
    /** Command run for [WeatherLocationSource.COMMAND]; must print `Name:lat:lon` lines. */
    val weatherLocationCommand: String,
    /** Temperature unit sent to the watch: [WeatherUnits.METRIC] (°C) or [WeatherUnits.IMPERIAL] (°F). */
    val weatherUnits: WeatherUnits,
    /** How often weather is re-fetched, in minutes. */
    val weatherIntervalMinutes: Long,
    /** When true, add a GeoClue2-tracked "current location" weather entry alongside the fixed ones. */
    val weatherGps: Boolean,
    /** GeoClue `DesktopId` — must match the allow-list entry in `/etc/geoclue/geoclue.conf`. */
    val weatherGpsDesktopId: String,
    /** Label for the current-location entry when reverse geocoding is off or yields no place name. */
    val weatherGpsName: String,
    /** When true, reverse-geocode the GPS coordinates to a place name via OSM Nominatim. Off by
     *  default: this sends your coordinates to a third-party web service, so it's opt-in. */
    val weatherReverseGeocode: Boolean,
    /** When true (the default, whenever weather is enabled), also emit weather timeline pins —
     *  a sunrise and a sunset pin per day for the primary location — alongside the Weather app data. */
    val weatherPins: Boolean,
    /** Expose the device's GeoClue2 position to watchapps via libpebble3's `SystemGeolocation` hook,
     *  so PKJS apps' `navigator.geolocation` and location-aware sports/GPS watchapps get a fix. Uses
     *  the same GeoClue identity as weather ([weatherGpsDesktopId] / `weather.gps_desktop_id`). Off by
     *  default: it shares the device's location with whatever watchapp asks, so it's opt-in. */
    val geolocation: Boolean,
    /** Watch "advanced settings" to push: `watch.<prefId>` config keys, mapped prefId → raw value.
     *  Applied (authoritatively) on each watch connect. See `stoandl settings` for the available ids. */
    val watchPrefs: Map<String, String>,
    /** Bridge desktop media players (MPRIS over D-Bus) to the watch's Music app: now-playing display
     *  plus play/pause, next/previous and volume control. Local-only (no egress), on by default. */
    val musicControl: Boolean,
    /** What the watch's volume buttons control: [MusicVolumeMode.SYSTEM] master/output volume (default)
     *  or [MusicVolumeMode.PLAYER] the active player's own MPRIS volume. */
    val musicVolume: MusicVolumeMode,
    /** Optional explicit commands for system volume up/down (override the auto-detected backend).
     *  Both must be set to take effect; only used when [musicVolume] is [MusicVolumeMode.SYSTEM]. */
    val musicVolumeUpCommand: String,
    val musicVolumeDownCommand: String,
    /** Local .ics files or directories to sync to the watch timeline (no egress). Any of these (or
     *  [calendarDiscover]/[calendarIcalUrls]/[calendarCalDav]) being non-empty enables calendar sync. */
    val calendarIcsPaths: List<String>,
    /** Auto-discover calendars the DE keeps as local .ics (e.g. Calindori on Plasma Mobile). No egress. */
    val calendarDiscover: Boolean,
    /** Published iCal feed URLs fetched over HTTP(S) (opt-in egress). */
    val calendarIcalUrls: List<String>,
    /** CalDAV calendar collections to read (opt-in egress). */
    val calendarCalDav: List<CalDavAccount>,
    /** How often calendars are re-read, in minutes (also rolls the timeline window forward). */
    val calendarSyncIntervalMinutes: Long,
    /** Capture datalog frames from custom watchapps (PebbleKit DataLogging) to NDJSON files under
     *  `~/.config/stoandl/datalog/<uuid>/<tag>.ndjson`. Local-only (no egress), but it writes
     *  app-supplied data to disk, so it's off by default — enable it to see which apps log data. */
    val datalog: Boolean,
    /** Allow `stoandl firmware check`/`update` to query a public GitHub repo for firmware images and
     *  download+flash the bundle matching the connected watch's board. Opt-in egress, so off by
     *  default. (Local `stoandl firmware <file.pbz>` sideload never touches the network and is always
     *  available.) */
    val firmwareGithub: Boolean,
    /** `owner/repo` whose GitHub releases publish per-board `normal_<board>_<version>.pbz` firmware
     *  bundles. Defaults to the PebbleOS source for Core devices. */
    val firmwareGithubRepo: String,
    /** When true, consider GitHub pre-releases too (otherwise only the latest stable release). */
    val firmwareGithubPrereleases: Boolean,
    /** When true (and [firmwareGithub] is on), proactively notify the watch when newer firmware is
     *  available — checked on connect and at most once a day — with an "Update" action button that
     *  flashes it. On by default once GitHub checks are enabled; set false for check-on-demand only. */
    val firmwareNotify: Boolean,
    /** Allow `stoandl language install` to download a `.pbl` language pack from the catalog's source
     *  (Rebble's CDN or a community GitHub repo) and install it. Opt-in egress, so off by default.
     *  (Local `stoandl language sideload <file.pbl>` and `stoandl language list` never touch the
     *  network and are always available.) */
    val languageDownload: Boolean,
    /** Auto-start the developer connection (the LAN WebSocket server on port 9000 that lets the Pebble
     *  SDK / CloudPebble install and live-debug apps through stoandl over BLE) on every watch connect.
     *  The server binds all interfaces with no auth, so it's off by default; `stoandl developer
     *  start`/`stop` toggle it on demand regardless of this setting. */
    val developerAutostart: Boolean,
    /** Ask the watch for its health/activity data (steps, sleep, heart rate, workouts) on every fresh
     *  connect — incremental after the first full pull. Mirrors the official app. Local-only (no
     *  egress), so on by default; it costs a little watch BLE/battery. The data lands in the shared
     *  `libpebble3.db`; [healthExport] projects it to readable files. */
    val healthSync: Boolean,
    /** Continuously export the synced health data to NDJSON under `<configDir>/health/`
     *  (`daily.ndjson`, `activities.ndjson`) so other tools can consume it. Re-projected from the DB
     *  whenever new data arrives. Local-only; on by default. */
    val healthExport: Boolean,
    /** Also export minute-level samples (steps + heart rate per minute) to `health/samples/<date>.ndjson`.
     *  Much higher volume than the daily summary, so off by default. */
    val healthExportSamples: Boolean,
    /** How many days back the export re-projects on each update (the daily summary, activities and
     *  samples window). Older days already written stay in place. */
    val healthExportDays: Int,
    /** EXPERIMENTAL (branch `pts-ble-active-rescan`): when a bonded watch is disconnected and BlueZ's
     *  native background auto-connect isn't bringing it back, run an app-side ACTIVE scan after a short
     *  grace and reconnect by re-discovering the watch under its *current* object path — the same path
     *  the initial connect uses (which sidesteps the stale `dev_<oldRPA>` path + empty-resolving-list
     *  problem that strands a classic-era Pebble Time/Time Steel advertising "Pebble Time LE XXXX" with
     *  a rotating Resolvable Private Address). Off by default. The BLE-native Time 2 / Pebble 2 reconnect
     *  via the fast kernel path well within the grace, so they never trigger a rescan (no regression).
     *  Doubles as a diagnostic: if the watch never appears in the rescan log, it isn't advertising at
     *  all post-drop — in which case no BLE strategy can recover it and BT Classic is the only path. */
    val bleActiveRescan: Boolean,
    /** EXPERIMENTAL Bluetooth Classic (BR/EDR) transport for classic-era Pebbles (Time / Time Steel),
     *  whose native, reliable transport is RFCOMM/SPP — not BLE. When [classicMac] is set, stoandl
     *  connects that watch over a secure RFCOMM socket (the watch must already be BR/EDR-bonded:
     *  `btmgmt pair -t bredr <mac>`). The BLE path is unaffected — BLE-native watches still use BLE. */
    val classicMac: String?,
    /** RFCOMM channel of the watch's SPP service (from its SDP record — resolve with sdptool/the cache;
     *  it can change across re-pairs). Default 1. */
    val classicChannel: Int,
) {
    /** A weather location: a display [name] shown on the watch and its [latitude]/[longitude]. */
    data class WeatherLocation(val name: String, val latitude: Double, val longitude: Double)

    /** A CalDAV calendar collection URL plus its Basic-auth credentials. */
    data class CalDavAccount(val url: String, val username: String, val password: String)

    enum class WeatherUnits { METRIC, IMPERIAL }

    /** What the watch volume buttons drive: the system/master output, or the active player's own volume. */
    enum class MusicVolumeMode { SYSTEM, PLAYER }

    /** Source for DE-imported locations: none (manual only), the GNOME/Phosh weather GSettings,
     *  or a user-provided command that prints `Name:lat:lon` lines (DE-agnostic escape hatch). */
    enum class WeatherLocationSource { MANUAL, GNOME, COMMAND }

    companion object {
        // Covers Plasma Mobile (Spacebar) and GNOME Calls out of the box; override in config.
        private val DEFAULT_DIALER_APPS = listOf("spacebar", "calls")
        private const val DEFAULT_WEATHER_INTERVAL_MINUTES = 30L
        private const val DEFAULT_GPS_DESKTOP_ID = "stoandl"
        private const val DEFAULT_GPS_NAME = "Current location"
        private const val DEFAULT_CALENDAR_INTERVAL_MINUTES = 30L
        private const val DEFAULT_FIRMWARE_GITHUB_REPO = "coredevices/PebbleOS"
        private const val DEFAULT_HEALTH_EXPORT_DAYS = 30

        private val MUTE_STATES = setOf("never", "always", "weekdays", "weekends")

        private fun defaults() = StoandlConfig(
            notificationPerApp = true,
            notificationDefaultMute = "never",
            notificationSyncToWatch = false,
            dialerApps = DEFAULT_DIALER_APPS,
            vcardPaths = emptyList(),
            weatherLocations = emptyList(),
            weatherLocationSource = WeatherLocationSource.MANUAL,
            weatherLocationCommand = "",
            weatherUnits = WeatherUnits.METRIC,
            weatherIntervalMinutes = DEFAULT_WEATHER_INTERVAL_MINUTES,
            weatherGps = false,
            weatherGpsDesktopId = DEFAULT_GPS_DESKTOP_ID,
            weatherGpsName = DEFAULT_GPS_NAME,
            weatherReverseGeocode = false,
            weatherPins = true,
            geolocation = false,
            watchPrefs = emptyMap(),
            musicControl = true,
            musicVolume = MusicVolumeMode.SYSTEM,
            musicVolumeUpCommand = "",
            musicVolumeDownCommand = "",
            calendarIcsPaths = emptyList(),
            calendarDiscover = false,
            calendarIcalUrls = emptyList(),
            calendarCalDav = emptyList(),
            calendarSyncIntervalMinutes = DEFAULT_CALENDAR_INTERVAL_MINUTES,
            datalog = false,
            firmwareGithub = false,
            firmwareGithubRepo = DEFAULT_FIRMWARE_GITHUB_REPO,
            firmwareGithubPrereleases = false,
            firmwareNotify = true,
            languageDownload = false,
            developerAutostart = false,
            healthSync = true,
            healthExport = true,
            healthExportSamples = false,
            healthExportDays = DEFAULT_HEALTH_EXPORT_DAYS,
            bleActiveRescan = false,
            classicMac = null,
            classicChannel = 1,
        )

        /** The stoandl base directory, honouring `XDG_CONFIG_HOME` (falling back to `~/.config`).
         *  Holds `stoandl.conf`, the libpebble3 store (`libpebble3.db`), datalog, backups, etc. —
         *  the single source of truth for where everything lives. The libpebble3 fork resolves the
         *  same path independently (`stoandlConfigDir()`), so an `XDG_CONFIG_HOME` override moves
         *  the config and the daemon's stores together. */
        fun configDir(): File {
            val xdg = System.getenv("XDG_CONFIG_HOME")?.takeIf { it.isNotBlank() }
            val base = xdg ?: (System.getProperty("user.home") + "/.config")
            return File(base, "stoandl")
        }

        fun configFile(): File = File(configDir(), "stoandl.conf")

        fun load(file: File = configFile()): StoandlConfig {
            if (!file.isFile) {
                log.info { "No config file at ${file.path}; using defaults" }
                return defaults()
            }
            val map = HashMap<String, String>()
            try {
                file.readLines().forEach { raw ->
                    val line = raw.substringBefore('#').trim()
                    val idx = line.indexOf('=')
                    if (idx > 0) map[line.substring(0, idx).trim()] = line.substring(idx + 1).trim()
                }
            } catch (e: Exception) {
                log.warn(e) { "Failed to read config ${file.path}; using defaults" }
                return defaults()
            }
            fun list(key: String, default: List<String> = emptyList()): List<String> =
                map[key]?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: default

            val cfg = StoandlConfig(
                notificationPerApp = map["notification.per_app"]?.let { parseBool(it) } ?: true,
                notificationDefaultMute = parseDefaultMute(map["notification.default_mute"]),
                notificationSyncToWatch = parseBool(map["notification.sync_to_watch"]),
                dialerApps = list("call.dialer_apps", DEFAULT_DIALER_APPS),
                vcardPaths = list("contacts.vcard_paths").map(::expandTilde),
                weatherLocations = parseWeatherLocations(list("weather.locations")),
                weatherLocationSource = parseLocationSource(map["weather.location_source"]),
                weatherLocationCommand = map["weather.location_command"]?.trim().orEmpty(),
                weatherUnits = parseWeatherUnits(map["weather.units"]),
                weatherIntervalMinutes = map["weather.interval"]?.trim()?.toLongOrNull()
                    ?.takeIf { it > 0 } ?: DEFAULT_WEATHER_INTERVAL_MINUTES,
                weatherGps = parseBool(map["weather.gps"]),
                weatherGpsDesktopId = map["weather.gps_desktop_id"]?.trim()?.takeIf { it.isNotEmpty() }
                    ?: DEFAULT_GPS_DESKTOP_ID,
                weatherGpsName = map["weather.gps_name"]?.trim()?.takeIf { it.isNotEmpty() }
                    ?: DEFAULT_GPS_NAME,
                weatherReverseGeocode = parseBool(map["weather.reverse_geocode"]),
                weatherPins = map["weather.pins"]?.let { parseBool(it) } ?: true,
                geolocation = parseBool(map["geolocation.enabled"]),
                // `watch.<prefId> = value` keys are applied to the watch's settings BlobDB.
                watchPrefs = map.entries
                    .filter { it.key.startsWith("watch.") && it.key.length > "watch.".length }
                    .associate { it.key.removePrefix("watch.") to it.value }
                    .filterValues { it.isNotEmpty() },
                // On by default; only an explicit falsey value disables it.
                musicControl = map["music.enabled"]?.let { parseBool(it) } ?: true,
                musicVolume = parseMusicVolume(map["music.volume"]),
                musicVolumeUpCommand = map["music.volume_up_command"]?.trim().orEmpty(),
                musicVolumeDownCommand = map["music.volume_down_command"]?.trim().orEmpty(),
                calendarIcsPaths = list("calendar.ics_paths").map(::expandTilde),
                calendarDiscover = parseBool(map["calendar.discover"]),
                calendarIcalUrls = list("calendar.ical_urls"),
                calendarCalDav = parseCalDav(list("calendar.caldav")),
                calendarSyncIntervalMinutes = map["calendar.sync_interval"]?.trim()?.toLongOrNull()
                    ?.takeIf { it > 0 } ?: DEFAULT_CALENDAR_INTERVAL_MINUTES,
                datalog = parseBool(map["datalog.enabled"]),
                firmwareGithub = parseBool(map["firmware.github"]),
                firmwareGithubRepo = map["firmware.github_repo"]?.trim()?.takeIf { it.isNotEmpty() }
                    ?: DEFAULT_FIRMWARE_GITHUB_REPO,
                firmwareGithubPrereleases = parseBool(map["firmware.github_prereleases"]),
                firmwareNotify = map["firmware.notify"]?.let { parseBool(it) } ?: true,
                languageDownload = parseBool(map["language.download"]),
                developerAutostart = parseBool(map["developer.autostart"]),
                healthSync = map["health.sync"]?.let { parseBool(it) } ?: true,
                healthExport = map["health.export"]?.let { parseBool(it) } ?: true,
                healthExportSamples = parseBool(map["health.export_samples"]),
                bleActiveRescan = parseBool(map["reconnect.active_rescan"]),
                classicMac = map["classic.mac"]?.trim()?.takeIf { it.isNotEmpty() },
                classicChannel = map["classic.channel"]?.trim()?.toIntOrNull() ?: 1,
                healthExportDays = map["health.export_days"]?.trim()?.toIntOrNull()
                    ?.takeIf { it > 0 } ?: DEFAULT_HEALTH_EXPORT_DAYS,
            )
            log.info {
                "Config loaded from ${file.path}: " +
                    "perApp=${cfg.notificationPerApp}, defaultMute=${cfg.notificationDefaultMute}, " +
                    "syncToWatch=${cfg.notificationSyncToWatch}, " +
                    "dialerApps=${cfg.dialerApps}, vcardPaths=${cfg.vcardPaths}, " +
                    "weatherLocations=${cfg.weatherLocations.map { it.name }}, " +
                    "weatherUnits=${cfg.weatherUnits}, weatherIntervalMinutes=${cfg.weatherIntervalMinutes}, " +
                    "weatherGps=${cfg.weatherGps}, weatherPins=${cfg.weatherPins}, weatherLocationSource=${cfg.weatherLocationSource}, " +
                    "geolocation=${cfg.geolocation}, " +
                    "watchPrefs=${cfg.watchPrefs.keys}, musicControl=${cfg.musicControl}, " +
                    "musicVolume=${cfg.musicVolume}, calendarIcsPaths=${cfg.calendarIcsPaths}, " +
                    "calendarDiscover=${cfg.calendarDiscover}, calendarIcalUrls=${cfg.calendarIcalUrls.size}, " +
                    "calendarCalDav=${cfg.calendarCalDav.size}, calendarSyncIntervalMinutes=${cfg.calendarSyncIntervalMinutes}, " +
                    "datalog=${cfg.datalog}, firmwareGithub=${cfg.firmwareGithub}" +
                    (if (cfg.firmwareGithub) " (repo=${cfg.firmwareGithubRepo}, prereleases=${cfg.firmwareGithubPrereleases}, notify=${cfg.firmwareNotify})" else "") +
                    ", languageDownload=${cfg.languageDownload}" +
                    ", healthSync=${cfg.healthSync}, healthExport=${cfg.healthExport}" +
                    (if (cfg.healthExport) " (samples=${cfg.healthExportSamples}, days=${cfg.healthExportDays})" else "") +
                    ", bleActiveRescan=${cfg.bleActiveRescan}" +
                    (cfg.classicMac?.let { ", classicMac=$it (ch${cfg.classicChannel})" } ?: "")
            }
            return cfg
        }

        /** Parse `Name:lat:lon` entries (e.g. `Berlin:52.52:13.405`). Malformed entries are dropped
         *  with a warning rather than failing the whole config load. Shared with the DE/command
         *  location source, which emits the same `Name:lat:lon` spec. */
        internal fun parseWeatherLocations(entries: List<String>): List<WeatherLocation> =
            entries.mapNotNull { entry ->
                // rsplit so a place name may itself contain ':'; the last two fields are lat/lon.
                val lastColon = entry.lastIndexOf(':')
                val prevColon = if (lastColon > 0) entry.lastIndexOf(':', lastColon - 1) else -1
                val name = if (prevColon > 0) entry.substring(0, prevColon).trim() else ""
                val lat = if (prevColon > 0) entry.substring(prevColon + 1, lastColon).trim().toDoubleOrNull() else null
                val lon = if (lastColon > 0) entry.substring(lastColon + 1).trim().toDoubleOrNull() else null
                if (name.isEmpty() || lat == null || lon == null) {
                    log.warn { "Ignoring malformed weather.locations entry '$entry' (expected Name:lat:lon)" }
                    null
                } else {
                    WeatherLocation(name, lat, lon)
                }
            }

        /** Parse `url|username|password` CalDAV entries. Only the URL is required; missing user/pass
         *  become empty (the password is never trimmed of inner content, only the field is split). */
        private fun parseCalDav(entries: List<String>): List<CalDavAccount> = entries.mapNotNull { entry ->
            val parts = entry.split('|')
            val url = parts.getOrNull(0)?.trim().orEmpty()
            if (url.isEmpty()) {
                log.warn { "Ignoring malformed calendar.caldav entry (expected url|username|password)" }
                null
            } else {
                CalDavAccount(url, parts.getOrElse(1) { "" }.trim(), parts.getOrElse(2) { "" }.trim())
            }
        }

        private fun parseBool(raw: String?): Boolean =
            raw?.trim()?.lowercase() in setOf("true", "yes", "1", "on")

        private fun parseDefaultMute(raw: String?): String = when (val v = raw?.trim()?.lowercase()) {
            null, "" -> "never"
            in MUTE_STATES -> v
            else -> {
                log.warn { "Unknown notification.default_mute '$raw'; defaulting to never" }
                "never"
            }
        }

        private fun parseMusicVolume(raw: String?): MusicVolumeMode = when (raw?.trim()?.lowercase()) {
            null, "", "system", "master" -> MusicVolumeMode.SYSTEM
            "player", "mpris", "app" -> MusicVolumeMode.PLAYER
            else -> {
                log.warn { "Unknown music.volume '$raw'; defaulting to system" }
                MusicVolumeMode.SYSTEM
            }
        }

        private fun parseLocationSource(raw: String?): WeatherLocationSource =
            when (raw?.trim()?.lowercase()) {
                null, "", "manual", "none" -> WeatherLocationSource.MANUAL
                "gnome", "phosh", "gsettings" -> WeatherLocationSource.GNOME
                "command", "cmd" -> WeatherLocationSource.COMMAND
                else -> {
                    log.warn { "Unknown weather.location_source '$raw'; defaulting to manual" }
                    WeatherLocationSource.MANUAL
                }
            }

        private fun parseWeatherUnits(raw: String?): WeatherUnits = when (raw?.trim()?.lowercase()) {
            null, "", "metric", "celsius", "c" -> WeatherUnits.METRIC
            "imperial", "fahrenheit", "f" -> WeatherUnits.IMPERIAL
            else -> {
                log.warn { "Unknown weather.units '$raw'; defaulting to metric" }
                WeatherUnits.METRIC
            }
        }

        private fun expandTilde(p: String): String =
            if (p == "~" || p.startsWith("~/")) System.getProperty("user.home") + p.substring(1) else p
    }
}
