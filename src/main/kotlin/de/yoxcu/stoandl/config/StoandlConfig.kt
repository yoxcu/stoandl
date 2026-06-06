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
) {
    companion object {
        // Covers Plasma Mobile (Spacebar) and GNOME Calls out of the box; override in config.
        private val DEFAULT_DIALER_APPS = listOf("spacebar", "calls")

        private fun defaults() = StoandlConfig(emptyList(), DEFAULT_DIALER_APPS, emptyList())

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
            )
            log.info {
                "Config loaded from ${file.path}: blocklist=${cfg.notificationBlocklist}, " +
                    "dialerApps=${cfg.dialerApps}, vcardPaths=${cfg.vcardPaths}"
            }
            return cfg
        }

        private fun expandTilde(p: String): String =
            if (p == "~" || p.startsWith("~/")) System.getProperty("user.home") + p.substring(1) else p
    }
}
