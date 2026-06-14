package de.yoxcu.stoandl.support

import io.github.oshai.kotlinlogging.KotlinLogging
import io.rebble.libpebblecommon.connection.CommonConnectedDevice
import io.rebble.libpebblecommon.connection.LibPebble
import io.rebble.libpebblecommon.services.WatchInfo
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.concurrent.atomic.AtomicReference

private val log = KotlinLogging.logger {}

/**
 * Pull diagnostics off the connected watch: its firmware log dump, a coredump, and the watch
 * metadata block — the raw material for a support bundle (the CLI's `stoandl support` packages
 * these together with the host-side daemon log + config).
 *
 * libpebble3 already implements all three over the wire — [CommonConnectedDevice] is
 * `ConnectedPebble.Logs` (`gatherLogs`) and `ConnectedPebble.CoreDump` (`getCoreDump`) backed by
 * real common services (no JVM stub, unlike the screenshot path), and exposes [WatchInfo] directly.
 * So this is pure wiring: drive the suspend calls, copy the temp file libpebble3 writes onto the
 * caller's absolute path, and format the metadata. Purely local — no network, no egress opt-in.
 *
 * Each method returns a status-prefixed string mirroring [de.yoxcu.stoandl.screenshot.ScreenshotControl]:
 * `ok:<...>`, `notready:<msg>` (no watch), `none:<msg>` (watch reachable but nothing to fetch), or
 * `error:<msg>`.
 */
class LogsControl(
    private val libPebbleRef: AtomicReference<LibPebble?>,
) {
    private fun device(): CommonConnectedDevice? =
        libPebbleRef.get()?.watches?.value?.filterIsInstance<CommonConnectedDevice>()?.firstOrNull()

    /**
     * Dump the watch's firmware logs to the absolute [path] (the daemon's cwd differs from the CLI's,
     * so the CLI resolves and sends an absolute path). Returns `ok:<path>`, `notready:<msg>`, or
     * `error:<msg>`. The transfer streams several log "generations" with a 5 s per-generation timeout,
     * so it can take a handful of seconds.
     */
    fun gatherLogs(path: String): String {
        libPebbleRef.get() ?: return "notready:libPebble not ready"
        val dev = device() ?: return "notready:No watch connected"
        return try {
            val src = runBlocking { dev.gatherLogs() }
                ?: return "error:Log dump failed (watch didn't respond)"
            copyOut(src.toString(), path)
            log.info { "Gathered watch logs → $path" }
            "ok:$path"
        } catch (e: Exception) {
            log.warn(e) { "gatherLogs failed" }
            "error:${e.message ?: "log dump failed"}"
        }
    }

    /**
     * Fetch an existing coredump from the watch and write it to the absolute [path]. Returns
     * `ok:<path>`, `none:<msg>` (the watch has no coredump to give), `notready:<msg>`, or `error:<msg>`.
     * Fetches any coredump (read or unread).
     */
    fun getCoreDump(path: String): String {
        libPebbleRef.get() ?: return "notready:libPebble not ready"
        val dev = device() ?: return "notready:No watch connected"
        return try {
            val src = runBlocking { dev.getCoreDump(unread = false) }
                ?: return "none:No coredump available on the watch"
            copyOut(src.toString(), path)
            log.info { "Fetched coredump → $path (${File(path).length()} bytes)" }
            "ok:$path"
        } catch (e: Exception) {
            log.warn(e) { "getCoreDump failed" }
            "error:${e.message ?: "coredump fetch failed"}"
        }
    }

    /**
     * The connected watch's metadata as a human-readable block (model, firmware, board, serial,
     * language, capabilities …). Returns `ok:<text>` or `notready:<msg>`.
     */
    fun watchInfoText(): String {
        libPebbleRef.get() ?: return "notready:libPebble not ready"
        val dev = device() ?: return "notready:No watch connected"
        return "ok:${formatWatchInfo(dev.watchInfo)}"
    }

    /** Copy a libpebble3 temp file onto the caller's target path, creating parent dirs. */
    private fun copyOut(srcPath: String, destPath: String) {
        val dest = File(destPath)
        dest.absoluteFile.parentFile?.mkdirs()
        File(srcPath).copyTo(dest, overwrite = true)
    }

    private fun formatWatchInfo(w: WatchInfo): String = buildString {
        appendLine("Model:           ${w.color.uiDescription} (${w.color.jsName})")
        appendLine("Platform:        ${w.platform.watchType.codename} [${w.platform.name}]")
        appendLine("Board:           ${w.platform.revision}")
        appendLine("Firmware:        ${w.runningFwVersion.stringVersion}")
        appendLine("Recovery FW:     ${w.recoveryFwVersion?.stringVersion ?: "—"}")
        appendLine("Serial:          ${w.serial}")
        appendLine("BT address:      ${w.btAddress}")
        appendLine("Language:        ${w.language} (v${w.languageVersion})")
        appendLine("Bootloader:      ${w.bootloaderTimestamp}")
        appendLine("Resources:       crc=${w.resourceCrc} @ ${w.resourceTimestamp}")
        appendLine("JS version:      ${w.javascriptVersion ?: "—"}")
        appendLine("Health insights: ${w.healthInsightsVersion ?: "—"}")
        appendLine("Unfaithful:      ${w.isUnfaithful}")
        appendLine("Capabilities:    ${w.capabilities.joinToString(", ") { it.name }}")
    }.trimEnd()
}
