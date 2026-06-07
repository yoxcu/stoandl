package de.yoxcu.stoandl.weather

import de.yoxcu.stoandl.config.StoandlConfig
import de.yoxcu.stoandl.config.StoandlConfig.WeatherLocation
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Imports weather locations the user already configured in their desktop environment, so they don't
 * have to re-enter coordinates. No DE exposes the weather *data* over a shared API, but the chosen
 * *location* is readable:
 *
 * - [Mode.GNOME] reads GNOME/Phosh's weather GSettings (`org.gnome.shell.weather` / `org.gnome.Weather`),
 *   whose coordinates are stored in radians.
 * - [Mode.COMMAND] runs a user command that prints `Name:lat:lon` lines — a DE-agnostic escape hatch
 *   for KDE/KWeather or anything else.
 *
 * KDE/Plasma has no stable coordinate store (the weather widget keeps a provider-specific source
 * string, and KWeather persists the chosen *city*), so the robust KDE recipe is to resolve a city
 * name to coordinates with Open-Meteo's free geocoder (needs `curl` + `jq`):
 * ```
 * weather.location_command = curl -s "https://geocoding-api.open-meteo.com/v1/search?name=Munich&count=1" \
 *   | jq -r '.results[0] | "\(.name):\(.latitude):\(.longitude)"'
 * ```
 * Swap `Munich` for your city, or splice in the name from KWeather's config, e.g.
 * `name=$(kreadconfig6 --file kweatherrc --group ... --key ...); curl -s ".../search?name=$name..." | jq ...`.
 *
 * Resolved fresh on each weather sync (a cheap subprocess), so DE changes apply without a restart.
 * Any failure yields an empty list and is logged; the manual `weather.locations` still apply.
 */
class DeLocationSource(private val mode: Mode, private val command: String) {
    enum class Mode { GNOME, COMMAND }

    private val log = KotlinLogging.logger {}

    suspend fun locations(): List<WeatherLocation> = withContext(Dispatchers.IO) {
        try {
            when (mode) {
                Mode.GNOME -> readGnome()
                Mode.COMMAND -> readCommand()
            }
        } catch (e: Exception) {
            log.warn(e) { "DE location source ($mode) failed: ${e.message}" }
            emptyList()
        }
    }

    private fun readCommand(): List<WeatherLocation> {
        if (command.isBlank()) {
            log.warn { "weather.location_source=command but weather.location_command is empty" }
            return emptyList()
        }
        val out = runCommand(listOf("sh", "-c", command)) ?: return emptyList()
        val lines = out.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val locs = StoandlConfig.parseWeatherLocations(lines)
        log.info { "weather.location_command produced ${locs.size} location(s)" }
        return locs
    }

    private fun readGnome(): List<WeatherLocation> {
        for (schema in GNOME_SCHEMAS) {
            val out = runCommand(listOf("gsettings", "get", schema, "locations")) ?: continue
            val locs = parseGnomeLocations(out)
            if (locs.isNotEmpty()) {
                log.info { "Imported ${locs.size} location(s) from $schema: ${locs.map { it.name }}" }
                return locs
            }
        }
        log.warn { "No GNOME weather locations found (tried ${GNOME_SCHEMAS.joinToString()})" }
        return emptyList()
    }

    private fun runCommand(cmd: List<String>): String? = try {
        val p = ProcessBuilder(cmd).redirectErrorStream(false).start()
        val out = p.inputStream.bufferedReader().readText()
        if (!p.waitFor(10, TimeUnit.SECONDS)) {
            p.destroy()
            log.warn { "command timed out: ${cmd.joinToString(" ")}" }
            null
        } else if (p.exitValue() != 0) {
            log.warn { "command exited ${p.exitValue()}: ${cmd.joinToString(" ")}" }
            null
        } else {
            out
        }
    } catch (e: Exception) {
        log.warn { "cannot run '${cmd.firstOrNull()}': ${e.message}" }
        null
    }

    companion object {
        private val GNOME_SCHEMAS = listOf("org.gnome.shell.weather", "org.gnome.Weather")
        // First single-quoted token in each entry is the place name; later ones are METAR codes.
        private val NAME_RE = Regex("'((?:[^'\\\\]|\\\\.)*)'")
        // First (double, double) pair is the lat/lon in radians; GVariant type tags like @a(dd) have no digits.
        private val COORD_RE = Regex("\\(\\s*(-?[0-9.eE+]+)\\s*,\\s*(-?[0-9.eE+]+)\\s*\\)")

        /**
         * Parse the GSettings `locations` value (an `av` of serialized GWeatherLocations). Each entry
         * starts with a `uint32` level marker; within it the first quoted string is the name and the
         * first coordinate pair is (latitude, longitude) in **radians**. Best-effort and defensive.
         */
        internal fun parseGnomeLocations(raw: String): List<WeatherLocation> =
            raw.split("uint32").drop(1).mapNotNull { chunk ->
                val name = NAME_RE.find(chunk)?.groupValues?.get(1)
                    ?.replace("\\'", "'")?.replace("\\\\", "\\")
                    ?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val coord = COORD_RE.find(chunk) ?: return@mapNotNull null
                val latRad = coord.groupValues[1].toDoubleOrNull() ?: return@mapNotNull null
                val lonRad = coord.groupValues[2].toDoubleOrNull() ?: return@mapNotNull null
                WeatherLocation(name, Math.toDegrees(latRad), Math.toDegrees(lonRad))
            }
    }
}
