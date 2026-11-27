@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class, kotlin.ExperimentalUnsignedTypes::class)

package de.yoxcu.stoandl.firmware

import de.yoxcu.stoandl.config.StoandlConfig
import de.yoxcu.stoandl.notification.watchFields
import de.yoxcu.stoandl.notification.watchText
import de.yoxcu.stoandl.pebble.isCoreDevice
import de.yoxcu.stoandl.util.connectedDevice
import io.github.oshai.kotlinlogging.KotlinLogging
import io.rebble.libpebblecommon.SystemAppIDs
import io.rebble.libpebblecommon.connection.CommonConnectedDevice
import io.rebble.libpebblecommon.connection.LibPebble
import io.rebble.libpebblecommon.connection.endpointmanager.FirmwareUpdateTrigger
import io.rebble.libpebblecommon.connection.endpointmanager.FirmwareUpdater.FirmwareUpdateStatus
import io.rebble.libpebblecommon.connection.endpointmanager.timeline.CustomTimelineActionHandler
import io.rebble.libpebblecommon.database.entity.buildTimelineNotification
import io.rebble.libpebblecommon.packets.blobdb.TimelineActionType
import io.rebble.libpebblecommon.packets.blobdb.TimelineIcon
import io.rebble.libpebblecommon.services.FirmwareVersion
import io.rebble.libpebblecommon.services.blobdb.TimelineActionResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
 *
 * A build the watch wouldn't boot from its other slot (a downgrade on a dual-slot watch) is libpebble3's
 * alone to finish: it reboots the watch into recovery (PRF) with nothing transferred
 * (`WaitingForReboot.viaPrf`, our `prf:`) and installs the same `.pbz` itself once the watch reconnects
 * there (upstream 5f24440b, `PendingFirmwareInstalls`). A watch that comes back on its normal firmware
 * instead ends as an `Idle` failure (our `failed:`). stoandl only reports those states and refuses a new
 * flash while one is under way ([busyReason]), so it never flashes a second time.
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
    /** Test seam: the source for a Core (`true`) or classic watch instead of the configured ones. */
    private val sourceOverride: ((core: Boolean) -> FirmwareSource)? = null,
    /** Test seam: the clock behind [CHECK_TTL_MS]. */
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    private val config: StoandlConfig get() = configOf()

    // Stateless value holders (repo/URL + a suspend resolve()), so they're rebuilt per use rather than
    // cached — that's what lets a live firmware.github_repo / firmware.cohorts_url edit take effect. The
    // answers are cached instead, under each source's cacheKey (see [resolveShared]).
    private fun sourceFor(core: Boolean): FirmwareSource = sourceOverride?.invoke(core)
        ?: if (core) GithubFirmwareSource(config.firmwareGithubRepo, config.firmwareGithubPrereleases)
        else CohortsFirmwareSource(config.firmwareCohortsUrl)

    /**
     * The check cache: what a source last answered for a board, per running firmware. A check downloads
     * the GitHub release list (megabytes before gzip) and the GUI asks on every Watch page, so
     * [check]/[update]/[maybeNotify] share one answer for [CHECK_TTL_MS] ([ERROR_TTL_MS] for a failure)
     * and one in-flight lookup per key. The running version is part of the key, so a flash re-checks.
     */
    private data class CheckKey(val source: String, val board: String, val firmware: String)
    private class CachedCheck(val resolution: FirmwareSource.Resolution, val atMs: Long)
    private val checkLock = Mutex()
    // Written under checkLock; [status] reads it without the lock.
    private val checkCache = java.util.concurrent.ConcurrentHashMap<CheckKey, CachedCheck>()
    private val checksInFlight = HashMap<CheckKey, Deferred<FirmwareSource.Resolution>>()  // under checkLock

    /** An online update before the watch takes over: downloading, or the reason it stopped there. */
    private sealed interface Prep {
        data class Downloading(val name: String) : Prep
        data class Failed(val reason: String) : Prep
    }

    // [status] reports this while the device is still Idle; cleared once a flash starts.
    private val prep = MutableStateFlow<Prep?>(null)

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
        busyReason(dev)?.let { return "busy:$it" }
        return try {
            flash(dev, path, FirmwareUpdateTrigger.Sideload)
            "ok:Flashing ${File(path).name}"
        } catch (e: Exception) {
            log.warn(e) { "sideloadFirmware($path) failed" }
            "error:${e.message ?: "sideload failed"}"
        }
    }

    /** Every flash goes through here. libpebble3 picks the route (direct, or through recovery). */
    private fun flash(dev: CommonConnectedDevice, pbz: String, trigger: FirmwareUpdateTrigger) {
        prep.value = null
        dev.sideloadFirmware(Path(pbz), trigger)
    }

    /**
     * Why [dev] can't take a new flash now, or null. libpebble3 runs one install at a time and drops a
     * second one silently, and while a downgrade goes through recovery (`viaPrf`, then the install it
     * starts itself in PRF) the latest release must not be offered: one tap would undo the downgrade.
     */
    private fun busyReason(dev: CommonConnectedDevice): String? = when (val st = dev.firmwareUpdateState) {
        is FirmwareUpdateStatus.WaitingForReboot ->
            if (st.viaPrf) "A downgrade to ${st.update.version.stringVersion} is pending: the watch installs it from recovery"
            else "The watch is rebooting to apply its new firmware"
        is FirmwareUpdateStatus.Active -> "A firmware install is already in progress"
        is FirmwareUpdateStatus.NotInProgress -> null
    }

    /**
     * Current firmware-update state of the connected watch, as a status-prefixed string:
     * `idle:`, `downloading:<asset>`, `waiting:`, `inprogress:<percent>`, `reboot:` (success, watch
     * rebooting), `prf:<version>` (a downgrade: the watch is rebooting into recovery, nothing flashed
     * yet; libpebble3 installs it there), `failed:<reason>` (also a downgrade the watch dropped by coming
     * back on its normal firmware), or `notready:` (no watch). An `idle:` reads `idle:available
     * <version>` while the check cache knows newer firmware for the watch: never a network call, so a
     * status poll can't cost egress.
     */
    fun status(): String {
        val dev = device() ?: return "notready:No watch connected"
        val s = statusString(dev.firmwareUpdateState)
        if (s != "idle:") return s
        return cachedUpdate(dev)?.let { "idle:available $it" } ?: s
    }

    /** The newer firmware version a fresh cached check found for [dev], or null (nothing cached, up to
     *  date, the source off). Reads only the cache. */
    private fun cachedUpdate(dev: CommonConnectedDevice): String? {
        val (_, key) = checkTarget(dev) ?: return null
        val found = checkCache[key]?.takeIf { isFresh(it) }?.resolution as? FirmwareSource.Resolution.Found ?: return null
        return found.version.takeIf { needsUpdate(it, dev.watchInfo.runningFwVersion) }
    }

    /** The source that answers for [dev] and its check-cache key, or null while that source is off. */
    private fun checkTarget(dev: CommonConnectedDevice): Pair<FirmwareSource, CheckKey>? {
        val platform = dev.watchInfo.platform
        val core = platform.isCoreDevice()
        if (!(if (core) config.firmwareGithub else config.firmwareCohorts)) return null
        val source = sourceFor(core)
        val running = dev.watchInfo.runningFwVersion
        val current = running.stringVersion
        return source to CheckKey(source.cacheKey, platform.revision, if (running.isRecovery) "$current/prf" else current)
    }

    /** Map a [FirmwareUpdateStatus] to the status-prefixed string (shared by [status] and [statusFlow]).
     *  For `InProgress` it samples the current percentage; [statusFlow] re-emits live as that ticks. */
    private fun statusString(st: FirmwareUpdateStatus): String = when (st) {
        is FirmwareUpdateStatus.NotInProgress.Idle -> when (val p = prep.value) {
            is Prep.Downloading -> "downloading:${p.name}"
            is Prep.Failed -> "failed:${p.reason}"
            // Also a downgrade the watch dropped: libpebble3 says so as the reconnect's lastFailure.
            null -> st.lastFailure?.let { "failed:${it.message ?: it::class.simpleName ?: "firmware update failed"}" }
                ?: "idle:"
        }
        is FirmwareUpdateStatus.NotInProgress.ErrorStarting -> "failed:${st.error}"
        is FirmwareUpdateStatus.WaitingToStart -> "waiting:"
        is FirmwareUpdateStatus.InProgress -> "inprogress:${(st.progress.value * 100).toInt().coerceIn(0, 100)}"
        // viaPrf: rebooting into recovery with nothing flashed yet, not the success of `reboot:`.
        is FirmwareUpdateStatus.WaitingForReboot ->
            if (st.viaPrf) "prf:${st.update.version.stringVersion}" else "reboot:"
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
            .combine(prep) { dev, _ -> dev }
            .flatMapLatest { dev ->
                if (dev == null) return@flatMapLatest flowOf("notready:")
                when (val st = dev.firmwareUpdateState) {
                    is FirmwareUpdateStatus.InProgress ->
                        st.progress.map { "inprogress:${(it * 100).toInt().coerceIn(0, 100)}" }
                    else -> flowOf(statusString(st))
                }
            }
            .distinctUntilChanged()
    }

    /**
     * Check the matching source for the latest firmware for the connected watch's board, answered from
     * the check cache unless [force] (an explicit refresh: `firmware check --refresh`, `CheckFirmwareForce`).
     * Returns `ok:<board>\t<current>\t<latest>\t<asset>\t<yes|no>\t<source>\t<changelogUrl>` (yes =
     * newer than running; the 7th field is the PebbleOS changelog page for the GUI's "What's new" link
     * and is the same on both `ok:` branches), `noasset:<board>\t<current>\t<source>` when the source
     * ships nothing for this board, or `disabled:`/`notready:`/`error:`.
     */
    suspend fun check(force: Boolean = false): String = when (val r = doCheck(force)) {
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
     * `busy:<msg>` (also while a downgrade goes through recovery, [busyReason]), or
     * `disabled:`/`notready:`/`error:`.
     *
     * Always asks the source afresh (and refreshes the cache): an update is explicit and about to
     * download megabytes anyway, so it must not act on a cached "up to date" from before a release.
     */
    suspend fun update(): String {
        device()?.let(::busyReason)?.let { return "busy:$it" }
        return updateTo(doCheck(force = true))
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
        // Offering the latest release now would invite undoing the downgrade (or any flash) in flight.
        if (device()?.let(::busyReason) != null) return
        if (!notifying.compareAndSet(false, true)) return
        try {
            val now = System.currentTimeMillis()
            if (lastCheckMs != 0L && now - lastCheckMs < minIntervalMs) return
            lastCheckMs = now
            // Forced: the cache may hold an answer the GUI fetched up to CHECK_TTL_MS ago, from before
            // a release, and this runs once a day. A lookup already in flight is still joined.
            val res = doCheck(force = true)
            if (res is CheckResult.Update && res.latest != lastNotifiedVersion) {
                sendUpdateNotification(res)
                lastNotifiedVersion = res.latest
            }
        } finally {
            notifying.set(false)
        }
    }

    private fun startFlash(update: CheckResult.Update): String {
        if (prep.value is Prep.Downloading) return "busy:A firmware update is already being prepared"
        prep.value = Prep.Downloading(update.bundle.name)
        scope.launch {
            val name = update.bundle.name
            try {
                val file = update.bundle.download()
                val dev = device()
                // Another install that began during the download: libpebble3 would drop this one silently.
                val busy = dev?.let(::busyReason)
                if (dev == null) {
                    log.warn { "Watch disconnected before flashing $name" }
                    prep.value = Prep.Failed("The watch disconnected before $name could be flashed; update again")
                } else if (busy != null) {
                    prep.value = Prep.Failed("$busy; update again once it has finished")
                } else {
                    flash(dev, file.absolutePath, FirmwareUpdateTrigger.Ui)
                }
            } catch (e: CancellationException) {
                prep.value = null
                throw e
            } catch (e: FirmwareDownloadException) {
                log.warn { "Firmware update ($name): ${e.message}" }
                prep.value = Prep.Failed(e.message ?: "Couldn't download $name")
            } catch (e: Exception) {
                log.warn(e) { "Firmware update ($name) failed" }
                prep.value = Prep.Failed(e.message ?: "Firmware update failed")
            }
        }
        return "ok:${update.board}\t${update.current}\t${update.latest}\t${update.bundle.name}"
    }

    /**
     * Resolve the latest firmware for the connected watch from the source matching its generation:
     * Core devices → GitHub ([GithubFirmwareSource]), classic / Rebble devices → cohorts.rebble.io
     * ([CohortsFirmwareSource]). Each source is independently opt-in. [force] skips the check cache.
     */
    private suspend fun doCheck(force: Boolean = false): CheckResult {
        val dev = device() ?: return CheckResult.NoWatch
        val (source, key) = checkTarget(dev)
            ?: return CheckResult.Disabled(sourceFor(dev.watchInfo.platform.isCoreDevice()).disabledHint)
        val running = dev.watchInfo.runningFwVersion
        val current = running.stringVersion
        val board = key.board
        return when (val r = resolveShared(source, key, force)) {
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
     * [source]'s answer for [key]: from the check cache while it is fresh (unless [force]), else from the
     * lookup already in flight for the key, else from a new one. The lookup runs in [scope], not in the
     * caller, so a D-Bus caller that gives up doesn't cancel it for the others, and it fills the cache
     * whoever started it. A forced call still joins a lookup in flight: that one is fresh too. A lookup
     * that fails while the cache still holds a fresh good answer answers that one and keeps it.
     */
    private suspend fun resolveShared(source: FirmwareSource, key: CheckKey, force: Boolean): FirmwareSource.Resolution {
        val lookup = checkLock.withLock {
            if (!force) checkCache[key]?.takeIf { isFresh(it) }?.let {
                log.debug { "Firmware check for ${key.board} answered from the cache" }
                return it.resolution
            }
            // isActive: a lookup cancelled with the daemon's scope never removed itself.
            checksInFlight[key]?.takeIf { it.isActive } ?: run {
                scope.async {
                    val r = try {
                        source.resolve(key.board)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        log.warn(e) { "Firmware lookup for ${key.board} failed" }
                        FirmwareSource.Resolution.Unreachable(e.message ?: "firmware check failed")
                    }
                    checkLock.withLock {
                        checksInFlight.remove(key)
                        checkCache.values.removeAll { !isFresh(it) }
                        // A failed refresh (GitHub's 403 once the unauthenticated 60/h are used up, a
                        // network blip) doesn't throw away a good answer that is still fresh: that one
                        // is kept and answered instead.
                        val kept = checkCache[key]?.takeIf { r is FirmwareSource.Resolution.Unreachable && it.resolution !is FirmwareSource.Resolution.Unreachable }
                        if (kept != null) {
                            log.warn { "Firmware lookup for ${key.board} failed (${(r as FirmwareSource.Resolution.Unreachable).message}); keeping the answer from ${(nowMs() - kept.atMs) / 60_000} min ago" }
                            kept.resolution
                        } else {
                            checkCache[key] = CachedCheck(r, nowMs())
                            r
                        }
                    }
                }.also { checksInFlight[key] = it }
            }
        }
        return lookup.await()
    }

    private fun isFresh(c: CachedCheck): Boolean {
        val ttl = if (c.resolution is FirmwareSource.Resolution.Unreachable) ERROR_TTL_MS else CHECK_TTL_MS
        return nowMs() - c.atMs < ttl
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
        // Laid out like every forwarded notification, with stoandl as the app (notification.app_name).
        val fields = watchFields(
            appName = "stoandl",
            title = "Firmware update",
            subtitle = null,
            body = "${info.latest} is available (you're on ${info.current}). Choose Update to install it.$languageNote",
            placement = config.notificationAppName,
        )
        val notif = buildTimelineNotification(
            // Same parent the desktop notifications use, so the watch round-trips our Update action.
            parentId = SystemAppIDs.ANDROID_NOTIFICATIONS_UUID,
            timestamp = Clock.System.now(),
        ) {
            attributes {
                watchText(fields)
                tinyIcon { TimelineIcon.NotificationFlag }
            }
            actions {
                action(TimelineActionType.Generic) { attributes { title { "Update" } } }   // actionId 0
                action(TimelineActionType.Dismiss) { attributes { title { "Dismiss" } } }  // actionId 1
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
         * How long a firmware check answer is reused. PebbleOS releases a few times a month, so three
         * hours costs little freshness while the GUI asks on every Watch page. The daily connect-time
         * notification ([maybeNotify]) and [update] skip the cache and ask the source afresh (and
         * refresh it).
         */
        const val CHECK_TTL_MS = 3 * 60 * 60 * 1000L

        /** How long a failed check is reused: enough to stop hammering an unreachable source. */
        const val ERROR_TTL_MS = 60 * 1000L

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
