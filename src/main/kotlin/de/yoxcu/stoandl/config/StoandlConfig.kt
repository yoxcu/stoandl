package de.yoxcu.stoandl.config

import io.github.oshai.kotlinlogging.KotlinLogging
import io.rebble.libpebblecommon.BleConnParamSet
import java.io.File

private val log = KotlinLogging.logger {}

/**
 * Daemon configuration, loaded once at startup from a simple `key = value` file (`#` starts a
 * comment; list values are comma-separated). Lives at `$XDG_CONFIG_HOME/stoandl/stoandl.conf`
 * (default `~/.config/stoandl/stoandl.conf`). A missing/unreadable file yields defaults.
 *
 * The daemon holds the loaded config in a live [ConfigStore]: the `SetConfig`/`SetSyncEnabled` D-Bus
 * methods (and `stoandl daemon set`/`stoandl sync`) persist a change here, reload the store, then re-reconcile the
 * affected subsystem — so GUI-exposed keys take effect without a restart. A hand-edit of this file
 * still needs a restart (nothing watches it), as do a few startup-only structural keys.
 */
/** The firmware's own canned-reply list: `notification.canned_replies` when that is empty. */
val DEFAULT_CANNED_REPLIES = listOf("Ok", "Yes", "No", "Call me", "Call you later")

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
    /** Master switch for forwarding desktop/extension notifications to the watch. On by default;
     *  flipped live by the GUI's "pause forwarding" toggle (`SetSyncEnabled("notifications", …)`).
     *  When off, every notification is dropped host-side at the send choke point (per-app mute, filters
     *  and styling are moot) — calls and firmware prompts use their own paths and are unaffected. */
    val notificationForward: Boolean,
    /** How far back, in minutes, a (re)connecting watch catches up on notifications it hasn't received —
     *  those posted while it was disconnected (a phone that drops the link on suspend and wakes for a
     *  push, a watch out of range). Never older than the daemon's start or the watch's pairing; `0` =
     *  libpebble3's upstream behaviour (only notifications posted after the connection came up are sent).
     *  Capped at 1440 (the notification DB never syncs anything older than a day). Startup-only. */
    val notificationCatchUpMinutes: Long,
    /** The watch's Reply list for desktop notifications with an inline reply (Plasma) and for extensions
     *  that don't bring their own. Empty = the firmware's own list. Items are kept whole and cut off once
     *  they no longer fit the firmware's 512-byte limit. Read per notification. */
    val notificationCannedReplies: List<String>,
    /** Master switch for the desktop alerts stoandl raises **about itself** — pairing/bond trouble, a
     *  Bluetooth scan blocking reconnects, an extension that needs setup. These are the daemon's own
     *  events, not forwarded app notifications (those follow [notificationForward] / per-app mute), and
     *  they're posted on the host desktop, not the watch. On by default; the per-event switches below
     *  refine it. The firmware-update alert has its own, older key ([firmwareNotify]) because it also
     *  drives a *watch* notification with an Update button — it is NOT gated by this. */
    val alertsEnabled: Boolean,
    /** Alert when a watch keeps connecting-then-dropping (unpaired on the watch) or its pairing was
     *  removed on this host. Each carries the action that fixes it (Re-pair / Pair). On by default —
     *  without it a watch can silently stop reconnecting forever. */
    val alertsPairing: Boolean,
    /** Alert when another process' Bluetooth discovery is monopolising the adapter's scanner, which
     *  blocks the watch from reconnecting. On by default. */
    val alertsBluetooth: Boolean,
    /** Alert when an installed extension requires configuration before it can start. On by default. */
    val alertsExtensions: Boolean,
    /** Dialer apps, by app name or desktop-entry id (exact, case-insensitive). Their notifications are
     *  suppressed from the watch while a call is up (the native call screen replaces them) and their
     *  title is used as a fallback caller name. */
    val dialerApps: List<String>,
    /** vCard files or directories (walked recursively) scanned for caller-ID resolution. */
    val vcardPaths: List<String>,
    /** Master switch for weather sync. On by default; flipped live by the Sync screen
     *  (`SetSyncEnabled("weather", …)`). Weather only actually runs when this is on **and** a source is
     *  configured ([weatherLocations]/[weatherGps]/[weatherLocationSource]) — turning it off stops the
     *  sync while leaving the configured locations in place. */
    val weatherEnabled: Boolean,
    /** Manually-configured locations to fetch weather for. Merged with any [weatherLocationSource]
     *  results. Empty (with no GPS and no source) disables weather sync. */
    val weatherLocations: List<WeatherLocation>,
    /** Where additional fixed locations come from besides [weatherLocations]: the DE's own weather
     *  config ([WeatherLocationSource.GNOME]) or a user command ([WeatherLocationSource.COMMAND]). */
    val weatherLocationSource: WeatherLocationSource,
    /** Command run for [WeatherLocationSource.COMMAND]; must print `Name:lat:lon` lines. */
    val weatherLocationCommand: String,
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
    /** Master switch for calendar sync. On by default; flipped live by the Sync screen
     *  (`SetSyncEnabled("calendar", …)`). Calendar only actually syncs when this is on **and** a source
     *  is configured ([calendarIcsPaths]/[calendarDiscover]/[calendarIcalUrls]/[calendarCalDav]) —
     *  turning it off stops syncing and removes the watch's calendar pins until re-enabled. */
    val calendarEnabled: Boolean,
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
    /** When true, consider GitHub pre-releases too (otherwise only stable releases). */
    val firmwareGithubPrereleases: Boolean,
    /** Allow `stoandl firmware check`/`update` to query Rebble's cohorts service for firmware images
     *  for classic / Rebble-generation Pebbles (original Pebble, Pebble Time / Time Steel, Time Round,
     *  Pebble 2) — whose firmware was never published to GitHub. The Core-vs-classic source split is by
     *  board generation, so this is the classic counterpart of [firmwareGithub]. Opt-in egress, off by
     *  default. (Local `.pbz` sideload never touches the network and is always available.) */
    val firmwareCohorts: Boolean,
    /** Base URL of the cohorts service (no trailing slash). Defaults to Rebble's; overridable for a
     *  self-hosted/mirror cohorts instance. */
    val firmwareCohortsUrl: String,
    /** When true (and the matching source — [firmwareGithub] or [firmwareCohorts] — is on), proactively
     *  notify the watch when newer firmware is available — checked on connect and at most once a day —
     *  with an "Update" action button that flashes it. On by default once a source is enabled; set
     *  false for check-on-demand only. */
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
    /** Record the watch's battery over time so `stoandl battery insights` and the GUI can show trends
     *  (discharge rate, time-to-empty, charge cycles, voltage). The **primary** source is the watch's
     *  hourly analytics heartbeat ([batteryHeartbeat]); this switch additionally enables a lean
     *  **fallback** that logs the BLE GATT battery level whenever it changes, used only when the
     *  heartbeat has no decoded data for a watch. Local-only (no egress), on by default. */
    val batteryHistory: Boolean,
    /** Capture + decode the watch's analytics native-heartbeat — state-of-charge, real voltage, the
     *  firmware's own time-to-empty, and a measured charge signal. This is the richer battery source
     *  (and the only one over Bluetooth Classic / across disconnects). It writes the raw heartbeat blob
     *  to `<configDir>/battery/heartbeat/` (local-only, never uploaded — the official app forwards the
     *  same blob to its cloud). On by default; the blob is decoded only behind a strict firmware-layout
     *  guard, else captured raw. */
    val batteryHeartbeat: Boolean,
    /** How many days of battery history (both sources) to retain before pruning. */
    val batteryRetentionDays: Int,
    /** EXPERIMENTAL Bluetooth Classic (BR/EDR) transport for classic-era Pebbles (Time / Time Steel),
     *  whose native, reliable transport is RFCOMM/SPP — not BLE. When set, stoandl discovers these
     *  watches via a BR/EDR inquiry (during a pairing window) and auto-pairs + connects them over a
     *  secure RFCOMM socket. On by default — it's idle when no classic watch is paired (the inquiry only
     *  runs while a pairing window is open, and a bonded watch reconnects by paging its fixed address).
     *  The BLE path is unaffected — BLE-native watches use BLE. */
    val classicDiscover: Boolean,
    /** "Follow the wrist": when two or more watches are paired, connect whichever one is actually in
     *  range instead of only ever the watch that last held the connection goal (the last one paired or
     *  explicitly `Connect`ed — which need not be the one you're wearing). The daemon arms the
     *  most-recently-connected watch first and, if it's out of range, rotates the goal to the next
     *  candidate until one connects; a live link is never dropped to chase another watch. Inert with a
     *  single paired watch. On by default; turn off to pin the connection to the last-chosen watch
     *  (use `stoandl watch connect <name>` to move it). */
    val connectionAutoswitch: Boolean,
    /** LE connection parameters the watch should use while idle (`ble.conn_params = min_ms,max_ms,
     *  latency,supervision_ms`). Null (the default, `off`) keeps libpebble3's upstream behaviour: the
     *  phone claims to manage the parameters and never changes them, so the link keeps whatever it had
     *  at connect (often the watch's 15 ms bulk set). Set, the watch manages them with this set in all
     *  three of its response-time slots, so it converges once and never asks again — each request needs
     *  the host, i.e. a wake on a sleeping phone. On Linux the host must allow the interval:
     *  `/etc/bluetooth/main.conf [LE] MaxConnectionInterval` ≥ max_ms / 1.25. Startup-only. */
    val bleConnParams: BleConnParamSet?,
    /** Optional fast set (`ble.conn_params_fast`) used during the connect handshake and bulk transfers
     *  (firmware, language pack, app install, big syncs), then back to [bleConnParams]. Off by default:
     *  on the Linux LL path a link dropped while fast is re-created fast and can't go slow again unless
     *  the kernel carries the "K5" fix (see docs/deep-sleep.md). Only used with [bleConnParams]. */
    val bleConnParamsFast: BleConnParamSet?,
    /** Hold a logind *delay* inhibitor so a suspend waits (up to [powerSleepGuardMaxMs]) until watch
     *  traffic in flight — typically the notification a push wake just produced — has reached the watch.
     *  Switches only the lock: the suspend-aware (wall-clock) scheduling of weather/calendar/firmware
     *  checks and the before-suspend discovery stop work the same without it. On by default; harmless on
     *  desktops (released within milliseconds when nothing is pending). */
    val powerSleepGuard: Boolean,
    /** Longest a suspend is held for pending watch traffic, in ms (logind's own cap is
     *  `InhibitDelayMaxSec`, 5 s by default). */
    val powerSleepGuardMaxMs: Long,
    /** Pause the watch's datalog sends (health data, app datalog) while the display is off, resume when
     *  it comes on (DataLogging SetSendEnabled). Saves the ~4 watch-initiated wakes per hour of the
     *  15-minute datalog flush on a phone that keeps the link across suspend; health data then arrives
     *  in bursts when the phone is used. Off by default. */
    val powerPauseDatalogScreenOff: Boolean,
    /** Mirror the desktop's Do Not Disturb state to/from the watch's manual Quiet Time. [DndSyncMode.OFF]
     *  by default — it actively changes state on both the host and the watch, so it's opt-in (it never
     *  touches the network). GNOME (`show-banners` GSettings) and KDE/Plasma (the `Inhibited` property)
     *  are auto-detected. */
    val dndSync: DndSyncMode,
    /** Enabled extensions ("companion apps"): the names from `extensions.enabled`. Each resolves to a
     *  child process under `<configDir>/ext/<name>/` (default entry `<name>.py`, overridable). The
     *  `stoandl ext` CLI edits this list live. See docs/extensions.md. */
    val extensionsEnabled: List<String>,
    /** Optional per-extension settings (`extension.<name>.<key>` → value), passed to the child in its
     *  `initialize` handshake; `cmd` overrides the default entry command. Most extensions need none. */
    val extensionConfig: Map<String, Map<String, String>>,
) {
    /** A weather location: a display [name] shown on the watch and its [latitude]/[longitude]. */
    data class WeatherLocation(val name: String, val latitude: Double, val longitude: Double)

    /** A configured CalDAV account: an opaque [id] (the stable source key, also the key its password
     *  is stored under in the secret store), its account/collection [url] and Basic-auth [username].
     *  The password is NOT in config — it lives in the system keyring (or the 0600 secrets file). */
    data class CalDavAccount(val id: String, val url: String, val username: String)

    /** What the watch volume buttons drive: the system/master output, or the active player's own volume. */
    enum class MusicVolumeMode { SYSTEM, PLAYER }

    /** Which way desktop DND ↔ watch Quiet Time is mirrored: not at all, host→watch only, watch→host
     *  only, or both. */
    enum class DndSyncMode { OFF, TO_WATCH, TO_HOST, BOTH }

    /** Source for DE-imported locations: none (manual only), the GNOME/Phosh weather GSettings,
     *  or a user-provided command that prints `Name:lat:lon` lines (DE-agnostic escape hatch). */
    enum class WeatherLocationSource { MANUAL, GNOME, COMMAND }

    companion object {
        // GNOME Calls. Not Plasma Mobile's "Phone": it shows calls full-screen and posts only "Missed call",
        // after the call. Not Spacebar: that is Plasma Mobile's SMS app.
        private val DEFAULT_DIALER_APPS = listOf("calls")
        // Where Plasma Mobile's phonebook (KPeopleVCard) keeps its vCards; a missing folder is just empty.
        private val DEFAULT_VCARD_PATHS = listOf("~/.local/share/kpeoplevcard")
        private const val DEFAULT_WEATHER_INTERVAL_MINUTES = 30L
        private const val DEFAULT_GPS_DESKTOP_ID = "stoandl"
        private const val DEFAULT_GPS_NAME = "Current location"
        private const val DEFAULT_CALENDAR_INTERVAL_MINUTES = 30L
        private const val DEFAULT_FIRMWARE_GITHUB_REPO = "coredevices/PebbleOS"
        private const val DEFAULT_FIRMWARE_COHORTS_URL = "https://cohorts.rebble.io"
        private const val DEFAULT_HEALTH_EXPORT_DAYS = 30
        private const val DEFAULT_BATTERY_RETENTION_DAYS = 90
        private const val DEFAULT_SLEEP_GUARD_MAX_MS = 3000L
        private const val DEFAULT_NOTIFICATION_CATCH_UP_MINUTES = 60L

        private val MUTE_STATES = setOf("never", "always", "weekdays", "weekends")

        private fun defaults() = StoandlConfig(
            notificationPerApp = true,
            notificationDefaultMute = "never",
            notificationSyncToWatch = false,
            notificationForward = true,
            notificationCatchUpMinutes = DEFAULT_NOTIFICATION_CATCH_UP_MINUTES,
            notificationCannedReplies = DEFAULT_CANNED_REPLIES,
            alertsEnabled = true,
            alertsPairing = true,
            alertsBluetooth = true,
            alertsExtensions = true,
            dialerApps = DEFAULT_DIALER_APPS,
            vcardPaths = DEFAULT_VCARD_PATHS.map(::expandTilde),
            weatherEnabled = true,
            weatherLocations = emptyList(),
            weatherLocationSource = WeatherLocationSource.MANUAL,
            weatherLocationCommand = "",
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
            calendarEnabled = true,
            calendarIcsPaths = emptyList(),
            calendarDiscover = false,
            calendarIcalUrls = emptyList(),
            calendarCalDav = emptyList(),
            calendarSyncIntervalMinutes = DEFAULT_CALENDAR_INTERVAL_MINUTES,
            datalog = false,
            firmwareGithub = false,
            firmwareGithubRepo = DEFAULT_FIRMWARE_GITHUB_REPO,
            firmwareGithubPrereleases = false,
            firmwareCohorts = false,
            firmwareCohortsUrl = DEFAULT_FIRMWARE_COHORTS_URL,
            firmwareNotify = true,
            languageDownload = false,
            developerAutostart = false,
            healthSync = true,
            healthExport = true,
            healthExportSamples = false,
            healthExportDays = DEFAULT_HEALTH_EXPORT_DAYS,
            batteryHistory = true,
            batteryHeartbeat = true,
            batteryRetentionDays = DEFAULT_BATTERY_RETENTION_DAYS,
            classicDiscover = true,
            connectionAutoswitch = true,
            bleConnParams = null,
            bleConnParamsFast = null,
            powerSleepGuard = true,
            powerSleepGuardMaxMs = DEFAULT_SLEEP_GUARD_MAX_MS,
            powerPauseDatalogScreenOff = false,
            dndSync = DndSyncMode.OFF,
            extensionsEnabled = emptyList(),
            extensionConfig = emptyMap(),
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

        fun load(file: File = configFile(), logResult: Boolean = true): StoandlConfig {
            if (!file.isFile) {
                if (logResult) log.info { "No config file at ${file.path}; using defaults" }
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
                notificationForward = map["notification.forward"]?.let { parseBool(it) } ?: true,
                notificationCatchUpMinutes = map["notification.catch_up_minutes"]?.trim()?.toLongOrNull()
                    ?.coerceIn(0L, 1440L) ?: DEFAULT_NOTIFICATION_CATCH_UP_MINUTES,
                notificationCannedReplies = list("notification.canned_replies", DEFAULT_CANNED_REPLIES)
                    .ifEmpty { DEFAULT_CANNED_REPLIES },
                alertsEnabled = map["alerts.enabled"]?.let { parseBool(it) } ?: true,
                alertsPairing = map["alerts.pairing"]?.let { parseBool(it) } ?: true,
                alertsBluetooth = map["alerts.bluetooth"]?.let { parseBool(it) } ?: true,
                alertsExtensions = map["alerts.extensions"]?.let { parseBool(it) } ?: true,
                dialerApps = list("call.dialer_apps", DEFAULT_DIALER_APPS),
                vcardPaths = list("contacts.vcard_paths", DEFAULT_VCARD_PATHS).map(::expandTilde),
                weatherEnabled = map["weather.enabled"]?.let { parseBool(it) } ?: true,
                weatherLocations = parseWeatherLocations(list("weather.locations")),
                weatherLocationSource = parseLocationSource(map["weather.location_source"]),
                weatherLocationCommand = map["weather.location_command"]?.trim().orEmpty(),
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
                calendarEnabled = map["calendar.enabled"]?.let { parseBool(it) } ?: true,
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
                firmwareCohorts = parseBool(map["firmware.cohorts"]),
                firmwareCohortsUrl = map["firmware.cohorts_url"]?.trim()?.trimEnd('/')
                    ?.takeIf { it.isNotEmpty() } ?: DEFAULT_FIRMWARE_COHORTS_URL,
                firmwareNotify = map["firmware.notify"]?.let { parseBool(it) } ?: true,
                languageDownload = parseBool(map["language.download"]),
                developerAutostart = parseBool(map["developer.autostart"]),
                healthSync = map["health.sync"]?.let { parseBool(it) } ?: true,
                healthExport = map["health.export"]?.let { parseBool(it) } ?: true,
                healthExportSamples = parseBool(map["health.export_samples"]),
                // Default TRUE, so only an explicit falsey value disables it — a bare parseBool() would
                // read an absent key as false and contradict defaults(), conf.example and the docs.
                classicDiscover = map["classic.discover"]?.let { parseBool(it) } ?: true,
                connectionAutoswitch = map["connection.autoswitch"]?.let { parseBool(it) } ?: true,
                bleConnParams = parseConnParams("ble.conn_params", map["ble.conn_params"]),
                bleConnParamsFast = parseConnParams("ble.conn_params_fast", map["ble.conn_params_fast"]),
                powerSleepGuard = map["power.sleep_guard"]?.let { parseBool(it) } ?: true,
                powerSleepGuardMaxMs = map["power.sleep_guard_max_ms"]?.trim()?.toLongOrNull()
                    ?.coerceIn(0L, 4500L) ?: DEFAULT_SLEEP_GUARD_MAX_MS,
                powerPauseDatalogScreenOff = parseBool(map["power.pause_datalog_screen_off"]),
                healthExportDays = map["health.export_days"]?.trim()?.toIntOrNull()
                    ?.takeIf { it > 0 } ?: DEFAULT_HEALTH_EXPORT_DAYS,
                batteryHistory = map["battery.history"]?.let { parseBool(it) } ?: true,
                batteryHeartbeat = map["battery.heartbeat"]?.let { parseBool(it) } ?: true,
                batteryRetentionDays = map["battery.retention_days"]?.trim()?.toIntOrNull()
                    ?.takeIf { it > 0 } ?: DEFAULT_BATTERY_RETENTION_DAYS,
                dndSync = parseDndSync(map["dnd.sync"]),
                extensionsEnabled = parseList(map["extensions.enabled"]),
                extensionConfig = parseExtensionConfig(map),
            )
            if (logResult) log.info {
                "Config loaded from ${file.path}: " +
                    "perApp=${cfg.notificationPerApp}, defaultMute=${cfg.notificationDefaultMute}, " +
                    "syncToWatch=${cfg.notificationSyncToWatch}, catchUp=${cfg.notificationCatchUpMinutes}min, " +
                    "cannedReplies=${cfg.notificationCannedReplies}, " +
                    "dialerApps=${cfg.dialerApps}, vcardPaths=${cfg.vcardPaths}, " +
                    "weatherLocations=${cfg.weatherLocations.map { it.name }}, " +
                    "weatherIntervalMinutes=${cfg.weatherIntervalMinutes}, " +
                    "weatherGps=${cfg.weatherGps}, weatherPins=${cfg.weatherPins}, weatherLocationSource=${cfg.weatherLocationSource}, " +
                    "geolocation=${cfg.geolocation}, " +
                    "watchPrefs=${cfg.watchPrefs.keys}, musicControl=${cfg.musicControl}, " +
                    "musicVolume=${cfg.musicVolume}, calendarIcsPaths=${cfg.calendarIcsPaths}, " +
                    "calendarDiscover=${cfg.calendarDiscover}, calendarIcalUrls=${cfg.calendarIcalUrls.size}, " +
                    "calendarCalDav=${cfg.calendarCalDav.size}, calendarSyncIntervalMinutes=${cfg.calendarSyncIntervalMinutes}, " +
                    "datalog=${cfg.datalog}, firmwareGithub=${cfg.firmwareGithub}" +
                    (if (cfg.firmwareGithub) " (repo=${cfg.firmwareGithubRepo}, prereleases=${cfg.firmwareGithubPrereleases})" else "") +
                    ", firmwareCohorts=${cfg.firmwareCohorts}" +
                    (if (cfg.firmwareCohorts) " (url=${cfg.firmwareCohortsUrl})" else "") +
                    (if (cfg.firmwareGithub || cfg.firmwareCohorts) ", firmwareNotify=${cfg.firmwareNotify}" else "") +
                    ", languageDownload=${cfg.languageDownload}" +
                    ", healthSync=${cfg.healthSync}, healthExport=${cfg.healthExport}" +
                    (if (cfg.healthExport) " (samples=${cfg.healthExportSamples}, days=${cfg.healthExportDays})" else "") +
                    ", battery=history:${cfg.batteryHistory}/heartbeat:${cfg.batteryHeartbeat}" +
                    (if (cfg.batteryHistory || cfg.batteryHeartbeat) " (retention=${cfg.batteryRetentionDays}d)" else "") +
                    (if (cfg.classicDiscover) ", classicDiscover=true" else "") +
                    (if (!cfg.connectionAutoswitch) ", autoswitch=off" else "") +
                    (cfg.bleConnParams?.let { ", bleConnParams=$it" + (cfg.bleConnParamsFast?.let { f -> " (fast $f)" } ?: "") } ?: "") +
                    (if (!cfg.powerSleepGuard) ", sleepGuard=off" else if (cfg.powerSleepGuardMaxMs != DEFAULT_SLEEP_GUARD_MAX_MS) ", sleepGuardMaxMs=${cfg.powerSleepGuardMaxMs}" else "") +
                    (if (cfg.powerPauseDatalogScreenOff) ", pauseDatalogScreenOff=true" else "") +
                    (if (cfg.dndSync != DndSyncMode.OFF) ", dndSync=${cfg.dndSync.name.lowercase()}" else "") +
                    (if (!cfg.alertsEnabled) ", alerts=off"
                     else listOfNotNull(
                         if (!cfg.alertsPairing) "pairing" else null,
                         if (!cfg.alertsBluetooth) "bluetooth" else null,
                         if (!cfg.alertsExtensions) "extensions" else null,
                     ).takeIf { it.isNotEmpty() }?.let { ", alerts muted: ${it.joinToString("/")}" }.orEmpty()) +
                    (if (cfg.extensionsEnabled.isNotEmpty()) ", extensions=${cfg.extensionsEnabled}" else "")
            }
            // Dropped keys are ignored like any unknown one; this only says what replaced them.
            if (logResult && "weather.units" in map) log.warn {
                "weather.units is no longer read: weather follows the watch's units " +
                    "(stoandl health profile set units metric|imperial). Remove the line from ${file.path}."
            }
            if (logResult && "power.screen_gate" in map) log.warn {
                "power.screen_gate is no longer read: a pairing window always discovers, display on or off. " +
                    "Remove the line from ${file.path}."
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

        /** Parse `id|url|username` CalDAV entries. The password is no longer in config — it lives in the
         *  secret store keyed by the id (managed via the GUI / `stoandl calendar` CLI). Both id and url
         *  are required; a legacy `url|user|password` entry (id missing) is dropped with a warning so it
         *  can be re-added securely. */
        private fun parseCalDav(entries: List<String>): List<CalDavAccount> = entries.mapNotNull { entry ->
            val parts = entry.split('|')
            val id = parts.getOrNull(0)?.trim().orEmpty()
            val url = parts.getOrNull(1)?.trim().orEmpty()
            if (id.isEmpty() || url.isEmpty()) {
                log.warn { "Ignoring malformed calendar.caldav entry (expected id|url|username) — re-add it from the GUI" }
                null
            } else {
                CalDavAccount(id, url, parts.getOrElse(2) { "" }.trim())
            }
        }

        /** Parse `min_ms,max_ms,latency,supervision_ms` (e.g. `500,520,0,6000`); empty/`off` = not set.
         *  A malformed or out-of-range set is dropped with a warning (the watch or the Linux host would
         *  refuse it anyway), falling back to "not set". */
        private fun parseConnParams(key: String, raw: String?): BleConnParamSet? =
            decodeConnParams(raw).getOrElse { e ->
                log.warn { "Ignoring $key '$raw': ${e.message}" }
                null
            }

        /**
         * Decode a `ble.conn_params` / `ble.conn_params_fast` value — `min_ms,max_ms,latency,supervision_ms`,
         * or empty / `off` for none — into the set (null = off), or fail with the reason it can't be used.
         * [load] and the Settings schema's validator share it, so a value the GUI accepts is one the daemon
         * applies rather than logs and ignores.
         */
        internal fun decodeConnParams(raw: String?): Result<BleConnParamSet?> {
            val v = raw?.trim().orEmpty()
            if (v.isEmpty() || v.lowercase() in setOf("off", "false", "no", "none")) return Result.success(null)
            val parts = v.split(',').map { it.trim() }
            val set = if (parts.size == 4) {
                val min = parts[0].toDoubleOrNull()
                val max = parts[1].toDoubleOrNull()
                val latency = parts[2].toIntOrNull()
                val supervision = parts[3].toIntOrNull()
                if (min != null && max != null && latency != null && supervision != null) {
                    BleConnParamSet(min, max, latency, supervision)
                } else null
            } else null
            if (set == null) {
                return Result.failure(
                    IllegalArgumentException("expected min_ms,max_ms,latency,supervision_ms, e.g. 500,520,0,6000"),
                )
            }
            set.validate()?.let { problem -> return Result.failure(IllegalArgumentException(problem)) }
            return Result.success(set)
        }

        /** [decodeConnParams]'s inverse: the `stoandl.conf` form of [set], empty for off. Whole milliseconds
         *  are written without a trailing `.0`, so a value reads back the way it was entered. */
        internal fun encodeConnParams(set: BleConnParamSet?): String {
            if (set == null) return ""
            fun ms(v: Double) = if (v % 1.0 == 0.0) v.toLong().toString() else v.toString()
            return "${ms(set.minIntervalMs)},${ms(set.maxIntervalMs)},${set.slaveLatency},${set.supervisionTimeoutMs}"
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

        private fun parseList(raw: String?): List<String> =
            raw?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.distinct() ?: emptyList()

        /** Gather the `extension.<name>.<key> = value` settings into name → {key → value}. `%h`/`~` in
         *  values expand to home. The keys (incl. `cmd`) are resolved against the ext dir at spawn time. */
        private fun parseExtensionConfig(map: Map<String, String>): Map<String, Map<String, String>> {
            val out = HashMap<String, MutableMap<String, String>>()
            map.entries.forEach { (k, v) ->
                if (!k.startsWith("extension.")) return@forEach
                val rest = k.removePrefix("extension.")
                val dot = rest.indexOf('.')
                if (dot <= 0) return@forEach
                val name = rest.substring(0, dot)
                val key = rest.substring(dot + 1)
                out.getOrPut(name) { LinkedHashMap() }[key] = expandHome(v)
            }
            return out
        }

        private fun parseDndSync(raw: String?): DndSyncMode = when (raw?.trim()?.lowercase()) {
            null, "", "off", "false", "no", "none" -> DndSyncMode.OFF
            "to_watch", "watch", "host_to_watch" -> DndSyncMode.TO_WATCH
            "to_host", "host", "watch_to_host" -> DndSyncMode.TO_HOST
            "both", "true", "yes", "on" -> DndSyncMode.BOTH
            else -> {
                log.warn { "Unknown dnd.sync '$raw'; defaulting to off" }
                DndSyncMode.OFF
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

        private fun expandTilde(p: String): String =
            if (p == "~" || p.startsWith("~/")) System.getProperty("user.home") + p.substring(1) else p

        /** Expand `~`/`~/…` and the systemd-style `%h` specifier to the home directory (used in
         *  extension command tokens and config values). */
        private fun expandHome(p: String): String {
            val home = System.getProperty("user.home")
            return expandTilde(p).replace("%h", home)
        }
    }
}
