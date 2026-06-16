@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class, kotlin.ExperimentalUnsignedTypes::class)

package de.yoxcu.stoandl.firmware

import de.yoxcu.stoandl.config.StoandlConfig
import de.yoxcu.stoandl.pebble.isCoreDevice
import de.yoxcu.stoandl.util.connectedDevice
import io.github.oshai.kotlinlogging.KotlinLogging
import io.rebble.libpebblecommon.SystemAppIDs
import io.rebble.libpebblecommon.connection.CommonConnectedDevice
import io.rebble.libpebblecommon.connection.LibPebble
import io.rebble.libpebblecommon.connection.endpointmanager.FirmwareUpdater.FirmwareUpdateStatus
import io.rebble.libpebblecommon.connection.endpointmanager.timeline.CustomTimelineActionHandler
import io.rebble.libpebblecommon.database.entity.buildTimelineNotification
import io.rebble.libpebblecommon.packets.blobdb.TimelineIcon
import io.rebble.libpebblecommon.packets.blobdb.TimelineItem
import io.rebble.libpebblecommon.services.FirmwareVersion
import io.rebble.libpebblecommon.services.blobdb.TimelineActionResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.io.files.Path
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Clock

private val log = KotlinLogging.logger {}

/**
 * Firmware install for the connected watch. Two layers:
 *
 *  - **Local sideload** ([sideload]) — flash a `.pbz` already on disk. Offline, always available.
 *  - **Online update** ([check]/[update]) — resolve the firmware bundle matching the watch's board
 *    and flash it. The source is chosen by the watch's generation ([isCoreDevice]): Core devices pull
 *    from the PebbleOS GitHub releases ([GithubFirmwareSource], gated by `firmware.github`), classic /
 *    Rebble devices from cohorts.rebble.io ([CohortsFirmwareSource], gated by `firmware.cohorts`).
 *    Both are opt-in egress. [maybeNotify] additionally pushes a watch notification with an "Update"
 *    button when newer firmware appears.
 *
 * libpebble3's [CommonConnectedDevice] (normal *or* recovery) does the real work — PutBytes transfer,
 * the FIRMWARE_START/COMPLETE handshake, and safety checks (board match, CRC, slot). The flash runs
 * asynchronously; callers observe [status] to follow progress. Every method returns a status-prefixed
 * string (`ok:` / `error:` / `notready:` / …) so the CLI can render the real outcome.
 */
class FirmwareControl(
    private val libPebbleRef: AtomicReference<LibPebble?>,
    private val scope: CoroutineScope,
    private val config: StoandlConfig,
) {
    private val github by lazy {
        GithubFirmwareSource(config.firmwareGithubRepo, config.firmwareGithubPrereleases)
    }
    private val cohorts by lazy { CohortsFirmwareSource(config.firmwareCohortsUrl) }

    // Set to the asset name while a bundle is downloading (before the on-device flash begins), so
    // [status] can report "downloading" in the gap where the device is still Idle.
    @Volatile private var preparing: String? = null

    // [maybeNotify] throttling: when we last hit the network, and the version we last notified about
    // (so a reconnect on the same day, or a second day with no new release, doesn't re-notify).
    @Volatile private var lastCheckMs = 0L
    @Volatile private var lastNotifiedVersion: String? = null

    // Guards [maybeNotify] so overlapping connect callbacks can't both pass the throttle and run two
    // concurrent network checks (the throttle's read-then-write is otherwise racy).
    private val notifying = AtomicBoolean(false)

    /** Outcome of an online firmware check, shared by [check]/[update]/[maybeNotify]. */
    private sealed class CheckResult {
        /** The source for this watch is off; [hint] names the config key to enable it. */
        data class Disabled(val hint: String) : CheckResult()
        object NoWatch : CheckResult()
        data class Error(val message: String) : CheckResult()
        /** The source is reachable but ships nothing for this board. [source] is where we looked. */
        data class NoAsset(val board: String, val current: String, val source: String) : CheckResult()
        data class UpToDate(
            val board: String,
            val current: String,
            val latest: String,
            val source: String,
        ) : CheckResult()
        data class Update(
            val board: String,
            val current: String,
            val latest: String,
            val bundle: FirmwareBundle,
            val source: String,
        ) : CheckResult()
    }

    private fun device(): CommonConnectedDevice? = libPebbleRef.connectedDevice()

    /** Flash a local `.pbz` at [path]. The path must be absolute (the daemon's cwd differs from the CLI's). */
    fun sideload(path: String): String {
        val dev = device() ?: return "notready:No watch connected"
        if (!File(path).isFile) return "error:No such file: $path"
        return try {
            dev.sideloadFirmware(Path(path))
            "ok:Flashing ${File(path).name}"
        } catch (e: Exception) {
            log.warn(e) { "sideloadFirmware($path) failed" }
            "error:${e.message ?: "sideload failed"}"
        }
    }

    /**
     * Current firmware-update state of the connected watch, as a status-prefixed string:
     * `idle:`, `downloading:<asset>`, `waiting:`, `inprogress:<percent>`, `reboot:` (success, watch
     * rebooting), `failed:<reason>`, or `notready:` (no watch).
     */
    fun status(): String {
        val dev = device() ?: return "notready:No watch connected"
        return when (val st = dev.firmwareUpdateState) {
            is FirmwareUpdateStatus.NotInProgress.Idle ->
                st.lastFailure?.let { "failed:${it.message ?: it::class.simpleName ?: "firmware update failed"}" }
                    ?: preparing?.let { "downloading:$it" }
                    ?: "idle:"
            is FirmwareUpdateStatus.NotInProgress.ErrorStarting -> "failed:${st.error}"
            is FirmwareUpdateStatus.WaitingToStart -> "waiting:"
            is FirmwareUpdateStatus.InProgress -> "inprogress:${(st.progress.value * 100).toInt().coerceIn(0, 100)}"
            is FirmwareUpdateStatus.WaitingForReboot -> "reboot:"
        }
    }

    /**
     * Check the matching source for the latest firmware for the connected watch's board.
     * Returns `ok:<board>\t<current>\t<latest>\t<asset>\t<yes|no>\t<source>` (yes = newer than
     * running), `noasset:<board>\t<current>\t<source>` when the source ships nothing for this board,
     * or `disabled:`/`notready:`/`error:`.
     */
    suspend fun check(): String = when (val r = doCheck()) {
        is CheckResult.Disabled -> "disabled:${r.hint}"
        CheckResult.NoWatch -> "notready:No watch connected"
        is CheckResult.Error -> "error:${r.message}"
        is CheckResult.NoAsset -> "noasset:${r.board}\t${r.current}\t${r.source}"
        is CheckResult.UpToDate -> "ok:${r.board}\t${r.current}\t${r.latest}\t-\tno\t${r.source}"
        is CheckResult.Update -> "ok:${r.board}\t${r.current}\t${r.latest}\t${r.bundle.name}\tyes\t${r.source}"
    }

    /**
     * Check the matching source and, if newer firmware is available for the watch's board, download
     * it and start flashing. Returns `ok:<board>\t<current>\t<latest>\t<asset>` once the
     * download+flash is kicked off (poll [status] to follow it), `uptodate:<msg>`, `noasset:<msg>`,
     * `busy:<msg>`, or `disabled:`/`notready:`/`error:`.
     */
    suspend fun update(): String = when (val r = doCheck()) {
        is CheckResult.Disabled -> "disabled:${r.hint}"
        CheckResult.NoWatch -> "notready:No watch connected"
        is CheckResult.Error -> "error:${r.message}"
        is CheckResult.NoAsset -> "noasset:No firmware available for board '${r.board}' from ${r.source}"
        is CheckResult.UpToDate -> "uptodate:${r.current} is current (latest ${r.latest} from ${r.source})"
        is CheckResult.Update -> startFlash(r)
    }

    /**
     * Check the matching source (at most once per [minIntervalMs]) and, if newer firmware is available
     * than we last told the user about, push a watch notification with an "Update" action button. Safe
     * to call on every connect; the throttle keeps it to roughly once a day.
     */
    suspend fun maybeNotify(minIntervalMs: Long) {
        if (!config.firmwareNotify) return
        if (!config.firmwareGithub && !config.firmwareCohorts) return
        if (!notifying.compareAndSet(false, true)) return
        try {
            val now = System.currentTimeMillis()
            if (lastCheckMs != 0L && now - lastCheckMs < minIntervalMs) return
            lastCheckMs = now
            val res = doCheck()
            if (res is CheckResult.Update && res.latest != lastNotifiedVersion) {
                sendUpdateNotification(res)
                lastNotifiedVersion = res.latest
            }
        } finally {
            notifying.set(false)
        }
    }

    private fun startFlash(update: CheckResult.Update): String {
        if (preparing != null) return "busy:A firmware update is already being prepared"
        preparing = update.bundle.name
        scope.launch {
            try {
                val file = update.bundle.download()
                if (file == null) {
                    log.warn { "Firmware download failed for ${update.bundle.name}; aborting update" }
                    return@launch
                }
                device()?.sideloadFirmware(Path(file.absolutePath))
                    ?: log.warn { "Watch disconnected before flashing ${update.bundle.name}" }
            } catch (e: Exception) {
                log.warn(e) { "Firmware update (${update.bundle.name}) failed" }
            } finally {
                preparing = null
            }
        }
        return "ok:${update.board}\t${update.current}\t${update.latest}\t${update.bundle.name}"
    }

    /**
     * Resolve the latest firmware for the connected watch from the source matching its generation:
     * Core devices → GitHub ([GithubFirmwareSource]), classic / Rebble devices → cohorts.rebble.io
     * ([CohortsFirmwareSource]). Each source is independently opt-in.
     */
    private suspend fun doCheck(): CheckResult {
        val dev = device() ?: return CheckResult.NoWatch
        val platform = dev.watchInfo.platform
        val core = platform.isCoreDevice()
        val source = if (core) github else cohorts
        val enabled = if (core) config.firmwareGithub else config.firmwareCohorts
        if (!enabled) return CheckResult.Disabled(source.disabledHint)

        val running = dev.watchInfo.runningFwVersion
        val current = running.stringVersion
        val board = platform.revision
        return when (val r = source.resolve(board)) {
            is FirmwareSource.Resolution.Unreachable -> CheckResult.Error(r.message)
            FirmwareSource.Resolution.NoFirmware -> CheckResult.NoAsset(board, current, source.label)
            is FirmwareSource.Resolution.Found ->
                if (needsUpdate(r.version, running)) {
                    CheckResult.Update(board, current, r.version, r.bundle, source.label)
                } else {
                    CheckResult.UpToDate(board, current, r.version, source.label)
                }
        }
    }

    /**
     * Whether to offer [candidate] over the [running] firmware. We offer when the watch is in recovery
     * (PRF) — it needs a normal-firmware reflash to leave it, regardless of version — when we can't
     * parse the candidate version (surface a downloadable build rather than silently hide it), or when
     * it's a newer major.minor.patch. We compare only the numeric version (not the build timestamp), so
     * a same-numbered re-spin isn't re-offered in a loop.
     */
    private fun needsUpdate(candidate: String, running: FirmwareVersion): Boolean =
        running.isRecovery || parseSemver(candidate) == null || isNewer(candidate, running)

    /** Push a "firmware available" notification to the watch with an Update button that flashes it. */
    private suspend fun sendUpdateNotification(info: CheckResult.Update) {
        val lp = libPebbleRef.get() ?: return
        val notif = buildTimelineNotification(
            // Same parent the desktop notifications use, so the watch round-trips our Update action.
            parentId = SystemAppIDs.ANDROID_NOTIFICATIONS_UUID,
            timestamp = Clock.System.now(),
        ) {
            attributes {
                title { "Firmware update" }
                body { "${info.latest} is available (you're on ${info.current}). Choose Update to install it." }
                subtitle { "stoandl" }
                tinyIcon { TimelineIcon.NotificationFlag }
            }
            actions {
                action(TimelineItem.Action.Type.Generic) { attributes { title { "Update" } } }   // actionId 0
                action(TimelineItem.Action.Type.Dismiss) { attributes { title { "Dismiss" } } }  // actionId 1
            }
        }
        // Per-item action override (no global handler needed): the Update button kicks off the flash.
        val handlers = mapOf<UByte, CustomTimelineActionHandler>(
            0.toUByte() to {
                scope.launch {
                    val result = update()
                    log.info { "Watch-triggered firmware update: $result" }
                }
                TimelineActionResult(true, TimelineIcon.ResultSent, "Updating…")
            }
        )
        lp.sendNotification(notif, handlers)
        log.info { "Sent firmware-update notification to watch: ${info.current} → ${info.latest}" }
    }

    /** True if firmware [tag] (e.g. `v4.12.0`) is a newer major.minor.patch than the [running] version. */
    private fun isNewer(tag: String, running: FirmwareVersion): Boolean {
        val (maj, min, pat) = parseSemver(tag) ?: return false
        val current = intArrayOf(running.major, running.minor, running.patch)
        val candidate = intArrayOf(maj, min, pat)
        for (i in 0..2) {
            if (candidate[i] != current[i]) return candidate[i] > current[i]
        }
        return false
    }

    private fun parseSemver(s: String): Triple<Int, Int, Int>? {
        val m = SEMVER.find(s) ?: return null
        return Triple(
            m.groupValues[1].toInt(),
            m.groupValues[2].toInt(),
            m.groupValues.getOrNull(3)?.toIntOrNull() ?: 0,
        )
    }

    companion object {
        private val SEMVER = Regex("""v?(\d+)\.(\d+)(?:\.(\d+))?""")
    }
}
