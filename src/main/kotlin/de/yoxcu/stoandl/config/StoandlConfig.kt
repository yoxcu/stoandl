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
    /** App-name substrings (case-insensitive) whose notifications are never forwarded to the watch. */
    val notificationBlocklist: List<String>,
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
    /** Watch "advanced settings" to push: `watch.<prefId>` config keys, mapped prefId → raw value.
     *  Applied (authoritatively) on each watch connect. See `stoandl settings` for the available ids. */
    val watchPrefs: Map<String, String>,
) {
    /** A weather location: a display [name] shown on the watch and its [latitude]/[longitude]. */
    data class WeatherLocation(val name: String, val latitude: Double, val longitude: Double)

    enum class WeatherUnits { METRIC, IMPERIAL }

    /** Source for DE-imported locations: none (manual only), the GNOME/Phosh weather GSettings,
     *  or a user-provided command that prints `Name:lat:lon` lines (DE-agnostic escape hatch). */
    enum class WeatherLocationSource { MANUAL, GNOME, COMMAND }

    companion object {
        // Covers Plasma Mobile (Spacebar) and GNOME Calls out of the box; override in config.
        private val DEFAULT_DIALER_APPS = listOf("spacebar", "calls")
        private const val DEFAULT_WEATHER_INTERVAL_MINUTES = 30L
        private const val DEFAULT_GPS_DESKTOP_ID = "stoandl"
        private const val DEFAULT_GPS_NAME = "Current location"

        private fun defaults() = StoandlConfig(
            notificationBlocklist = emptyList(),
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
            watchPrefs = emptyMap(),
        )

        fun configFile(): File {
            val xdg = System.getenv("XDG_CONFIG_HOME")?.takeIf { it.isNotBlank() }
            val base = xdg ?: (System.getProperty("user.home") + "/.config")
            return File("$base/stoandl/stoandl.conf")
        }

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
                notificationBlocklist = list("notification.blocklist"),
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
                // `watch.<prefId> = value` keys are applied to the watch's settings BlobDB.
                watchPrefs = map.entries
                    .filter { it.key.startsWith("watch.") && it.key.length > "watch.".length }
                    .associate { it.key.removePrefix("watch.") to it.value }
                    .filterValues { it.isNotEmpty() },
            )
            log.info {
                "Config loaded from ${file.path}: blocklist=${cfg.notificationBlocklist}, " +
                    "dialerApps=${cfg.dialerApps}, vcardPaths=${cfg.vcardPaths}, " +
                    "weatherLocations=${cfg.weatherLocations.map { it.name }}, " +
                    "weatherUnits=${cfg.weatherUnits}, weatherIntervalMinutes=${cfg.weatherIntervalMinutes}, " +
                    "weatherGps=${cfg.weatherGps}, weatherLocationSource=${cfg.weatherLocationSource}, " +
                    "watchPrefs=${cfg.watchPrefs.keys}"
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

        private fun parseBool(raw: String?): Boolean =
            raw?.trim()?.lowercase() in setOf("true", "yes", "1", "on")

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
