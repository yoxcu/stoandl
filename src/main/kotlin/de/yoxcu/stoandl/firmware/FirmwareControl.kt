@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class, kotlin.ExperimentalUnsignedTypes::class)

package de.yoxcu.stoandl.firmware

import de.yoxcu.stoandl.config.StoandlConfig
import de.yoxcu.stoandl.pebble.isCoreDevice
import de.yoxcu.stoandl.util.connectedDevice
import io.github.oshai.kotlinlogging.KotlinLogging
import io.rebble.libpebblecommon.SystemAppIDs
import io.rebble.libpebblecommon.connection.CommonConnectedDevice
import io.rebble.libpebblecommon.connection.ConnectedPebbleDeviceInRecovery
import io.rebble.libpebblecommon.connection.LibPebble
import io.rebble.libpebblecommon.connection.endpointmanager.FirmwareUpdater.FirmwareUpdateStatus
import io.rebble.libpebblecommon.connection.endpointmanager.timeline.CustomTimelineActionHandler
import io.rebble.libpebblecommon.database.entity.buildTimelineNotification
import io.rebble.libpebblecommon.packets.blobdb.TimelineIcon
import io.rebble.libpebblecommon.packets.blobdb.TimelineItem
import io.rebble.libpebblecommon.services.FirmwareVersion
import io.rebble.libpebblecommon.services.blobdb.TimelineActionResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
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
 *    Both are opt-in egress. [maybeNotify] alerts when newer firmware appears — a watch notification AND
 *    a (non-bridged) host desktop notification, each with an "Update" button.
 *
 * libpebble3's [CommonConnectedDevice] (normal *or* recovery) does the real work — PutBytes transfer,
 * the FIRMWARE_START/COMPLETE handshake, and safety checks (board match, CRC, slot). The flash runs
 * asynchronously; callers observe [status] to follow progress. Every method returns a status-prefixed
 * string (`ok:` / `error:` / `notready:` / …) so the CLI can render the real outcome.
 */
class FirmwareControl(
    private val libPebbleRef: AtomicReference<LibPebble?>,
    private val scope: CoroutineScope,
    /**
     * The daemon's **live** config — a getter over the reloaded-on-write `ConfigStore`, not a startup
     * snapshot, so a `firmware.*` change from the GUI/CLI applies at the next check instead of only
     * after a daemon restart.
     */
    private val configOf: () -> StoandlConfig,
    /**
     * Hook to post the "firmware available" alert as a host *desktop* notification (with an Update
     * button), in addition to the direct watch notification. It's tagged with the `stoandl-desktop` app
     * name so the passive monitor doesn't bridge it to the watch — so the alert shows once on each
     * surface, each with its own working button. Args: summary, body, the action-button label, and the
     * callback to run when tapped. Null when no session bus is wired (truly headless) — then only the
     * watch notification is sent.
     */
    private val notifyDesktop: ((summary: String, body: String, actionLabel: String, onAction: () -> Unit) -> Unit)? = null,
) {
    private val config: StoandlConfig get() = configOf()

    // Stateless value holders (repo/URL + a suspend resolve()), so they're rebuilt per use rather than
    // cached — that's what lets a live firmware.github_repo / firmware.cohorts_url edit take effect.
    private val github: GithubFirmwareSource
        get() = GithubFirmwareSource(config.firmwareGithubRepo, config.firmwareGithubPrereleases)
    private val cohorts: CohortsFirmwareSource get() = CohortsFirmwareSource(config.firmwareCohortsUrl)

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

    /** The `.pbz` behind a watch's most recent flash, so a downgrade through recovery can be finished. */
    private data class Flash(val serial: String, val pbz: String)
    private val lastFlash = AtomicReference<Flash?>(null)

    /**
     * A downgrade waiting for its watch to come back in recovery (PRF). A dual-slot watch can't
     * downgrade from normal firmware, so libpebble3's `sideloadFirmware()` (upstream 2461f781) reboots
     * it into PRF without transferring anything; [resumeDowngradesInRecovery] flashes the same `.pbz`
     * once it reconnects there. Meanwhile [update] refuses: in PRF [needsUpdate] offers the latest
     * release, and one tap would undo the downgrade.
     */
    private data class PendingDowngrade(val serial: String, val pbz: String, val version: String)
    private val pendingDowngrade = AtomicReference<PendingDowngrade?>(null)

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
            // An explicit sideload replaces a pending downgrade: the user has chosen what to flash.
            pendingDowngrade.set(null)
            flash(dev, path)
            "ok:Flashing ${File(path).name}"
        } catch (e: Exception) {
            log.warn(e) { "sideloadFirmware($path) failed" }
            "error:${e.message ?: "sideload failed"}"
        }
    }

    /** Every flash goes through here, so a downgrade that ends in a PRF reboot knows its `.pbz`. */
    private fun flash(dev: CommonConnectedDevice, pbz: String) {
        lastFlash.set(Flash(dev.serial, pbz))
        dev.sideloadFirmware(Path(pbz))
    }

    /**
     * Current firmware-update state of the connected watch, as a status-prefixed string:
     * `idle:`, `downloading:<asset>`, `waiting:`, `inprogress:<percent>`, `reboot:` (success, watch
     * rebooting), `prf:<version>` (a downgrade: the watch is rebooting into recovery, nothing flashed
     * yet; [resumeDowngradesInRecovery] flashes it there), `failed:<reason>`, or `notready:` (no watch).
     */
    fun status(): String {
        val dev = device() ?: return "notready:No watch connected"
        return statusString(dev.firmwareUpdateState, dev.watchInfo.runningFwVersion)
    }

    /** Map a [FirmwareUpdateStatus] to the status-prefixed string (shared by [status] and [statusFlow]).
     *  For `InProgress` it samples the current percentage; [statusFlow] re-emits live as that ticks. */
    private fun statusString(st: FirmwareUpdateStatus, running: FirmwareVersion): String = when (st) {
        is FirmwareUpdateStatus.NotInProgress.Idle ->
            st.lastFailure?.let { "failed:${it.message ?: it::class.simpleName ?: "firmware update failed"}" }
                ?: preparing?.let { "downloading:$it" }
                ?: "idle:"
        is FirmwareUpdateStatus.NotInProgress.ErrorStarting -> "failed:${st.error}"
        is FirmwareUpdateStatus.WaitingToStart -> "waiting:"
        is FirmwareUpdateStatus.InProgress -> "inprogress:${(st.progress.value * 100).toInt().coerceIn(0, 100)}"
        is FirmwareUpdateStatus.WaitingForReboot ->
            if (isDowngradeViaRecovery(st.update.version, running)) "prf:${st.update.version.stringVersion}" else "reboot:"
    }

    /**
     * Whether flashing [target] over [running] goes through recovery: libpebble3's internal
     * `needsPrfToDowngrade` for a sideload, which always allows downgrades. `WaitingForReboot` then
     * means the watch is rebooting into PRF with nothing flashed, not that the flash succeeded.
     */
    private fun isDowngradeViaRecovery(target: FirmwareVersion, running: FirmwareVersion): Boolean =
        running.isDualSlot && !running.isRecovery &&
            compareValuesBy(target, running, { it.major }, { it.minor }, { it.patch }) < 0

    /**
     * Finish downgrades that libpebble3 routes through recovery (see [pendingDowngrade]): note one when
     * a flash ends in that PRF reboot, and flash the same `.pbz` once the watch reconnects in PRF. A
     * watch that comes back on normal firmware instead left recovery without it, so the downgrade is
     * dropped rather than applied at some later, unrelated PRF visit. Call once libPebble is up.
     */
    fun resumeDowngradesInRecovery() {
        val lp = libPebbleRef.get() ?: return
        lp.watches
            .onEach { devs -> devs.filterIsInstance<CommonConnectedDevice>().forEach(::followDowngrade) }
            .launchIn(scope)
    }

    private fun followDowngrade(dev: CommonConnectedDevice) {
        val st = dev.firmwareUpdateState
        val pending = pendingDowngrade.get()
        when {
            st is FirmwareUpdateStatus.WaitingForReboot &&
                isDowngradeViaRecovery(st.update.version, dev.watchInfo.runningFwVersion) -> {
                val flash = lastFlash.get()?.takeIf { it.serial == dev.serial } ?: return
                val next = PendingDowngrade(dev.serial, flash.pbz, st.update.version.stringVersion)
                if (pendingDowngrade.getAndSet(next) != next) {
                    log.info { "Downgrade to ${next.version}: the watch is rebooting into recovery; flashing ${File(next.pbz).name} there once it reconnects" }
                }
            }
            pending == null || pending.serial != dev.serial -> Unit
            dev is ConnectedPebbleDeviceInRecovery -> {
                if (st is FirmwareUpdateStatus.NotInProgress && pendingDowngrade.compareAndSet(pending, null)) {
                    log.info { "Watch is in recovery: flashing the pending downgrade to ${pending.version}" }
                    flash(dev, pending.pbz)
                }
            }
            st !is FirmwareUpdateStatus.WaitingForReboot && pendingDowngrade.compareAndSet(pending, null) ->
                log.warn { "Watch came back on normal firmware without the downgrade to ${pending.version}; dropped it (sideload the .pbz again to retry)" }
        }
    }

    /**
     * Reactive [status]: the same status-prefixed strings, but as a Flow that emits on every change —
     * phase transitions AND each in-progress percentage tick. The percentage lives in a nested
     * `InProgress.progress: StateFlow<Float>` that the outer device/watches flow does NOT re-surface, so
     * we [flatMapLatest] into it while flashing. Drives the `FirmwareProgress` D-Bus signal. Emits
     * `notready:` when no watch is connected (and follows connect/disconnect via `watches`).
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun statusFlow(): Flow<String> {
        val lp = libPebbleRef.get() ?: return flowOf("notready:")
        return lp.watches
            // CommonConnectedDevice (not ConnectedPebbleDevice) so this matches status()/device() and
            // also covers a watch connected in recovery (PRF) — the un-brick flash must report progress.
            .map { devs -> devs.filterIsInstance<CommonConnectedDevice>().firstOrNull() }
            .distinctUntilChanged { a, b -> a === b }
            .flatMapLatest { dev ->
                if (dev == null) return@flatMapLatest flowOf("notready:")
                when (val st = dev.firmwareUpdateState) {
                    is FirmwareUpdateStatus.InProgress ->
                        st.progress.map { "inprogress:${(it * 100).toInt().coerceIn(0, 100)}" }
                    else -> flowOf(statusString(st, dev.watchInfo.runningFwVersion))
                }
            }
            .distinctUntilChanged()
    }

    /**
     * Check the matching source for the latest firmware for the connected watch's board.
     * Returns `ok:<board>\t<current>\t<latest>\t<asset>\t<yes|no>\t<source>\t<changelogUrl>` (yes =
     * newer than running; the 7th field is the PebbleOS changelog page for the GUI's "What's new" link
     * and is the same on both `ok:` branches), `noasset:<board>\t<current>\t<source>` when the source
     * ships nothing for this board, or `disabled:`/`notready:`/`error:`.
     */
    suspend fun check(): String = when (val r = doCheck()) {
        is CheckResult.Disabled -> "disabled:${r.hint}"
        CheckResult.NoWatch -> "notready:No watch connected"
        is CheckResult.Error -> "error:${r.message}"
        is CheckResult.NoAsset -> "noasset:${r.board}\t${r.current}\t${r.source}"
        is CheckResult.UpToDate -> "ok:${r.board}\t${r.current}\t${r.latest}\t-\tno\t${r.source}\t$CHANGELOG_URL"
        is CheckResult.Update -> "ok:${r.board}\t${r.current}\t${r.latest}\t${r.bundle.name}\tyes\t${r.source}\t$CHANGELOG_URL"
    }

    /**
     * Check the matching source and, if newer firmware is available for the watch's board, download
     * it and start flashing. Returns `ok:<board>\t<current>\t<latest>\t<asset>` once the
     * download+flash is kicked off (poll [status] to follow it), `uptodate:<msg>`, `noasset:<msg>`,
     * `busy:<msg>` (also while a downgrade waits for recovery), or `disabled:`/`notready:`/`error:`.
     */
    suspend fun update(): String {
        val dev = device()
        pendingDowngrade.get()?.takeIf { dev != null && it.serial == dev.serial }?.let {
            return "busy:A downgrade to ${it.version} is pending: stoandl flashes it once the watch is in recovery"
        }
        return updateTo(doCheck())
    }

    private fun updateTo(check: CheckResult): String = when (val r = check) {
        is CheckResult.Disabled -> "disabled:${r.hint}"
        CheckResult.NoWatch -> "notready:No watch connected"
        is CheckResult.Error -> "error:${r.message}"
        is CheckResult.NoAsset -> "noasset:No firmware available for board '${r.board}' from ${r.source}"
        is CheckResult.UpToDate -> "uptodate:${r.current} is current (latest ${r.latest} from ${r.source})"
        is CheckResult.Update -> startFlash(r)
    }

    /**
     * Check the matching source (at most once per [minIntervalMs]) and, if newer firmware is available
     * than we last told the user about, post a watch notification AND a (non-bridged) desktop
     * notification, each with an "Update" action button. Safe to call on every connect; the throttle
     * keeps it to roughly once a day.
     */
    suspend fun maybeNotify(minIntervalMs: Long) {
        if (!config.firmwareNotify) return
        if (!config.firmwareGithub && !config.firmwareCohorts) return
        // Offering the latest release now would invite undoing the downgrade in flight.
        if (pendingDowngrade.get() != null) return
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
                device()?.let { flash(it, file.absolutePath) }
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
     * Alert that newer firmware is available, with an "Update" action that flashes it. Posted on BOTH
     * surfaces, each with a working button, without double-notifying the watch:
     *  - a **direct watch** notification (Update button → flash, via a per-item action handler);
     *  - a **host desktop** notification ([notifyDesktop]) tagged with the `stoandl-desktop` app name, so
     *    stoandl's passive monitor does NOT bridge it to the watch (the watch already has the direct one).
     */
    private suspend fun sendUpdateNotification(info: CheckResult.Update) {
        val lp = libPebbleRef.get() ?: return
        val languageNote = if (dropsBuiltInLanguages(info.board, info.current, info.latest)) {
            " It removes the built-in translations: a watch not in English needs a language pack afterwards."
        } else ""
        val notif = buildTimelineNotification(
            // Same parent the desktop notifications use, so the watch round-trips our Update action.
            parentId = SystemAppIDs.ANDROID_NOTIFICATIONS_UUID,
            timestamp = Clock.System.now(),
        ) {
            attributes {
                title { "Firmware update" }
                body { "${info.latest} is available (you're on ${info.current}). Choose Update to install it.$languageNote" }
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

        // Also a host desktop notification with the same Update button — tagged so it is NOT bridged to
        // the watch (the watch already has the direct notification above), so it shows once on each.
        notifyDesktop?.invoke(
            "Firmware update available",
            "${info.latest} is available — you're on ${info.current}. " +
                "Click Update to install, or run: stoandl firmware update.$languageNote",
            "Update",
        ) {
            scope.launch {
                val result = update()
                log.info { "Desktop-triggered firmware update: $result" }
            }
        }
    }

    companion object {
        /**
         * Whether to offer [candidate] over the [running] firmware. We offer when the watch is in recovery
         * (PRF) — it needs a normal-firmware reflash to leave it, regardless of version, which is why the
         * sources must resolve the highest release rather than whatever GitHub marks latest — when we can't
         * parse the candidate version (surface a downloadable build rather than silently hide it), or when
         * its numeric version ([FirmwareTag], all four parts) is newer. The build timestamp and suffix are
         * ignored, so a same-numbered re-spin isn't re-offered in a loop.
         */
        internal fun needsUpdate(candidate: String, running: FirmwareVersion): Boolean {
            if (running.isRecovery) return true
            val tag = FirmwareTag.parse(candidate) ?: return true
            return tag.compareNumbers(FirmwareTag.of(running)) > 0
        }

        /**
         * Whether flashing [latest] over [current] on [board] drops the watch's built-in translations:
         * PebbleOS removed German, French, Italian, Spanish, Portuguese, Dutch, Catalan and Polish in
         * 4.38.0 (on asterix, the Pebble 2 Duo, already in 4.37.0). A watch using one falls back to
         * English and needs a language pack ([BUILT_IN_LANGUAGES_NOTE]).
         */
        fun dropsBuiltInLanguages(board: String, current: String, latest: String): Boolean {
            val from = FirmwareTag.parse(current) ?: return false
            val to = FirmwareTag.parse(latest) ?: return false
            val removedIn = FirmwareTag(listOf(4, if (board.startsWith("asterix")) 37 else 38, 0, 0), null)
            return from.compareNumbers(removedIn) < 0 && to.compareNumbers(removedIn) >= 0
        }

        const val BUILT_IN_LANGUAGES_NOTE =
            "Note: this firmware no longer has the built-in German, French, Italian, Spanish, Portuguese, " +
                "Dutch, Catalan and Polish. A watch using one of them falls back to English; install a language " +
                "pack afterwards, e.g. from https://github.com/coredevices/pebbleos-translations/releases with " +
                "'stoandl language sideload <file.pbl>'."

        /** The human-readable PebbleOS changelog page, appended to [check]'s `ok:` records as the
         *  "What's new" link for the GUI's firmware banner. There is no per-release URL source, so this
         *  is a single constant (matches `docs/pebbleos-changelog-review.md`). */
        const val CHANGELOG_URL = "https://ndocs.repebble.com/PebbleOS-Changelog-25efbb55ea84801da04bfcf73c9346e1"
    }
}
