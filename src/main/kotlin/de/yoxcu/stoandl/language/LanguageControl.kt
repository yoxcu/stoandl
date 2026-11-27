package de.yoxcu.stoandl.language

import de.yoxcu.stoandl.config.StoandlConfig
import de.yoxcu.stoandl.util.connectedDevice
import io.github.oshai.kotlinlogging.KotlinLogging
import io.rebble.libpebblecommon.connection.ConnectedPebbleDevice
import io.rebble.libpebblecommon.connection.LibPebble
import io.rebble.libpebblecommon.connection.UserFacingError
import io.rebble.libpebblecommon.connection.endpointmanager.LanguagePackInstallState
import io.rebble.libpebblecommon.connection.endpointmanager.WatchLanguagePack
import io.rebble.libpebblecommon.packets.MAX_LANGUAGE_PACKS
import io.rebble.libpebblecommon.packets.ProtocolCapsFlag
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.plus
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.io.files.Path
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration.Companion.seconds

private val log = KotlinLogging.logger {}

/**
 * Language-pack install for the connected watch. Three entry points, mirroring [FirmwareControl]:
 *
 *  - **Sideload** ([sideload]) — install a `.pbl` already on disk. Offline, always available.
 *  - **Catalog** ([list]) — show the packs the bundled [LanguagePackCatalog] offers for this watch's
 *    board, flagging the one currently installed. Offline.
 *  - **Install** ([install]) — auto-pick a pack for a locale/name/id, download it and install. Opt-in
 *    egress (gated by `language.download`), since it fetches from Rebble's CDN / GitHub.
 *
 * libpebble3's `ConnectedPebbleDevice.installLanguagePack(...)` does the real work — for a URL it
 * downloads first, then PutBytes-transfers the file and tells the watch to install it. Firmware that
 * keeps several packs (`SupportsMultipleLanguagePacks`, PebbleOS 4.42) adds it to a free slot of
 * [MAX_LANGUAGE_PACKS] and switches to it; older firmware replaces its one pack. On the former the packs
 * on the watch are managed here too: [packs] lists them, [use] picks the watch's language among them and
 * built-in English, [remove] frees a slot.
 * The transfer runs asynchronously; callers poll [status] to follow it. Every method returns a
 * status-prefixed string (`ok:` / `error:` / `notready:` / …) so the CLI renders the real outcome.
 */
class LanguageControl(
    private val libPebbleRef: AtomicReference<LibPebble?>,
    /** Runs the two followers behind [attempt]; started with the first install. */
    private val scope: CoroutineScope,
    /** The daemon's **live** config (a getter over the reloaded-on-write `ConfigStore`), so toggling
     *  `language.download` from the GUI/CLI applies at once rather than only after a restart. */
    private val configOf: () -> StoandlConfig,
) {
    private val config: StoandlConfig get() = configOf()
    private val catalog by lazy { LanguagePackCatalog.load() }

    /**
     * The last install this daemon started, and how it ended. libpebble3 keeps only the active language
     * now (`Idle.activeLanguage`, upstream dropped `previousError`/`successfullyInstalledLanguage`) and
     * reports a failure as a [UserFacingError.FailedToInstallLanguagePack] that names no watch, so the
     * `done:`/`failed:` outcome is ours. Only stoandl starts installs, so a failure is this attempt's.
     * [ran]: its watch was seen installing; [activated]: whether the watch's language was this pack
     * once it was idle again (libpebble3 sets the pack's name on success and keeps the previous one when
     * the install failed or the added pack wasn't switched to), so a failure whose report was lost (the
     * error stream drops events when a subscriber lags) still isn't taken for `done:`.
     * [isoLocal]: the catalog pack's locale (null for a sideload), which [list] shows as installed before
     * the watch reconnects (libpebble3's `WatchInfo` is a connect-time snapshot). Cleared once its watch's
     * connection ends, as libpebble3's own state is.
     */
    private data class Attempt(
        val deviceId: String,
        val name: String,
        val isoLocal: String?,
        val startedAtMs: Long,
        val ran: Boolean = false,
        val failure: String? = null,
        val activated: Boolean? = null,
    ) {
        /** libpebble3 started it but no `watches` snapshot shows that yet. */
        fun justStarted(now: Long) = !ran && failure == null && now - startedAtMs < START_GRACE_MS
    }
    private val attempt = MutableStateFlow<Attempt?>(null)

    /** The watch's language as [use] or [remove] last left it on [deviceId] (null [locale]: built-in
     *  English); [list] shows it until the watch reconnects. Cleared with the connection, like [attempt]. */
    private data class Chosen(val deviceId: String, val locale: String?)
    private val chosen = MutableStateFlow<Chosen?>(null)
    private val following = AtomicBoolean(false)

    /** Start [dev]'s install of [name] through [start], recording it as the current [attempt]; a status
     *  string when it can't start. One at a time: libpebble3 drops an install asked for while one runs. */
    @Synchronized
    private fun begin(dev: ConnectedPebbleDevice, name: String, isoLocal: String?, start: () -> Unit): String? {
        busy(dev)?.let { return it }
        addRefusal(dev, isoLocal)?.let { return it }
        follow()
        attempt.value = Attempt(dev.identifier.asString, name, isoLocal, System.currentTimeMillis())
        start()
        return null
    }

    /** `busy:` while [dev] installs a pack (or one was just started), else null. */
    private fun busy(dev: ConnectedPebbleDevice): String? {
        val running = dev.languagePackInstallState.takeIf { it !is LanguagePackInstallState.Idle }
            ?.let { libPebbleName(it) }
            ?: attempt.value?.takeIf { it.deviceId == dev.identifier.asString && it.justStarted(System.currentTimeMillis()) }?.name
            ?: return null
        return "busy:The watch is installing $running; wait for it to finish ('stoandl language status')"
    }

    private fun libPebbleName(st: LanguagePackInstallState): String = when (st) {
        is LanguagePackInstallState.Downloading -> st.language
        is LanguagePackInstallState.Installing -> st.language
        is LanguagePackInstallState.Idle -> ""
    }

    /** Whether [dev]'s firmware keeps several packs (it adds an install to a free slot). */
    private fun multiPack(dev: ConnectedPebbleDevice) = ProtocolCapsFlag.SupportsMultipleLanguagePacks in dev.watchInfo.capabilities

    /**
     * Why a pack (of [isoLocal], null when unknown: a sideload) can't be added to [dev], or null. Only firmware
     * that keeps several packs can refuse: libpebble3 would fail the install once the slots are full, and
     * a locale already there would take a second slot (upstream's picker leaves installed locales out).
     */
    private fun addRefusal(dev: ConnectedPebbleDevice, isoLocal: String?): String? {
        if (!multiPack(dev)) return null
        val packs = try {
            watchCall { dev.listLanguagePacks() }
        } catch (e: Exception) {
            log.warn(e) { "listLanguagePacks failed" }
            return "error:Couldn't read the language packs on the watch: ${e.message ?: e::class.simpleName}"
        }
        if (packs.size >= MAX_LANGUAGE_PACKS) {
            return "error:All $MAX_LANGUAGE_PACKS language-pack slots on the watch are in use; remove one first " +
                "('stoandl language installed', then 'stoandl language remove <slot>')"
        }
        val same = isoLocal?.let { loc -> packs.firstOrNull { it.hasTranslation && sameLocale(it.locale, loc) } }
        if (same != null) {
            return "error:${same.name} (${same.locale}) is already on the watch, in slot ${same.slot}; to replace it, " +
                "remove it first ('stoandl language remove ${same.slot}')"
        }
        return null
    }

    /** Subscribe once, before the first install, to what [attempt] is derived from. */
    private fun follow() {
        val lp = libPebbleRef.get() ?: return
        if (!following.compareAndSet(false, true)) return
        // Unconfined: the collector runs inside libpebble3's report, which comes before it sets the
        // Idle state, so a reported failure is in [attempt] before that Idle can be read. (A report the
        // error stream dropped is caught by [Attempt.activated].)
        lp.userFacingErrors
            .filterIsInstance<UserFacingError.FailedToInstallLanguagePack>()
            .onEach { e -> attempt.update { it?.copy(failure = e.message) } }
            .launchIn(scope + Dispatchers.Unconfined)
        lp.watches
            .onEach { devs ->
                val connected = devs.filterIsInstance<ConnectedPebbleDevice>()
                chosen.update { c -> c?.takeIf { connected.any { it.identifier.asString == c.deviceId } } }
                attempt.update { a ->
                    if (a == null) return@update null
                    val dev = connected.firstOrNull { it.identifier.asString == a.deviceId }
                    val st = dev?.languagePackInstallState
                    when {
                        dev == null -> null  // its connection ended: a new one starts from a plain idle
                        !a.ran && st !is LanguagePackInstallState.Idle -> a.copy(ran = true)
                        // The end, kept: a later `use` changes the watch's language, not this outcome.
                        a.ran && a.activated == null && st is LanguagePackInstallState.Idle ->
                            a.copy(activated = st.activeLanguage == a.name)
                        else -> a
                    }
                }
            }
            .launchIn(scope)
    }

    /** Language packs need a *normal* connection ([ConnectedPebbleDevice]); a recovery-mode watch
     *  (firmware-only) can't take one. */
    private fun device(): ConnectedPebbleDevice? = libPebbleRef.connectedDevice()

    /** Install a local `.pbl` at [path] (absolute — the daemon's cwd differs from the CLI's). */
    fun sideload(path: String): String {
        val dev = device() ?: return "notready:No watch connected"
        val file = File(path)
        if (!file.isFile) return "error:No such file: $path"
        if (!file.name.endsWith(".pbl")) return "error:Not a language pack (expected a .pbl): ${file.name}"
        return try {
            begin(dev, file.name, isoLocal = null) { dev.installLanguagePack(Path(path), file.name) }
                ?: "ok:Installing ${file.name}"
        } catch (e: Exception) {
            log.warn(e) { "installLanguagePack($path) failed" }
            "error:${e.message ?: "language pack install failed"}"
        }
    }

    /**
     * List the catalog packs available for the connected watch's board, best-match-for-the-system-
     * locale first. Each record is tab-separated:
     * `id \t isoLocal \t displayName \t installed(yes|no) \t source(rebble|github)`.
     * Returns an empty list when no watch is connected or the catalog is empty (the CLI explains).
     */
    fun list(): List<String> {
        val dev = device() ?: return emptyList()
        // Match on locale only — the watch's languageVersion rarely equals the catalog's newest pack
        // version, so requiring both would wrongly flag the installed language as "not installed".
        val installedLocale = installedLocale(dev)
        return catalog.forWatch(dev.watchInfo.platform, systemLocale()).map { pack ->
            val isInstalled = installedLocale != null &&
                installedLocale.equals(pack.isoLocal, ignoreCase = true)
            val source = if (pack.file.startsWith("https://binaries.rebble.io")) "rebble" else "github"
            listOf(
                pack.id,
                pack.isoLocal,
                pack.displayName(),
                if (isInstalled) "yes" else "no",
                source,
            ).joinToString("\t")
        }
    }

    /**
     * Auto-pick a pack for [query] (an ISO locale like `de_DE`/`de`, a language name like `German`,
     * or a catalog id), download it and install it. A blank [query] uses the daemon's system locale.
     * Opt-in egress: returns `disabled:` unless `language.download = true`. Other returns:
     * `ok:<displayName>` once kicked off (poll [status]), `notfound:`, `notready:`, `busy:` (an install
     * runs), or `error:` (also: every slot in use, or the locale already on the watch).
     */
    fun install(query: String?): String {
        if (!config.languageDownload) return DISABLED
        val dev = device() ?: return "notready:No watch connected"
        val wanted = query?.trim().orEmpty().ifEmpty { systemLocale() }
        val candidates = catalog.forWatch(dev.watchInfo.platform, systemLocale())
        if (candidates.isEmpty()) {
            return "notfound:No language packs for this watch's board (${dev.watchInfo.platform.languagePackBoard()})"
        }
        val pack = pick(candidates, wanted)
            ?: return "notfound:No language pack matching '$wanted' for this watch " +
                "(try 'stoandl language list')"
        return try {
            // The target locale lets `list` reflect it once the (async) install completes, without
            // waiting for a reconnect; installedLocale() gates it on the outcome.
            begin(dev, pack.displayName(), pack.isoLocal) { dev.installLanguagePack(pack.file, pack.displayName()) }
                ?: "ok:${pack.displayName()}"
        } catch (e: Exception) {
            log.warn(e) { "installLanguagePack(${pack.file}) failed" }
            "error:${e.message ?: "language pack install failed"}"
        }
    }

    /**
     * Current language-pack install state of the connected watch, as a status-prefixed string:
     * `idle:`, `downloading:<name>`, `installing:<percent>`, `done:<name>` (just finished), or
     * `failed:<reason>`; `notready:` when no watch is connected.
     */
    fun status(): String {
        val dev = device() ?: return "notready:No watch connected"
        return statusString(dev, attempt.value)
    }

    /** [a] when it is [dev]'s, ended (its watch is idle again) and succeeded; else null. */
    private fun succeeded(dev: ConnectedPebbleDevice, a: Attempt?): Attempt? =
        a?.takeIf { it.deviceId == dev.identifier.asString && it.ran && it.failure == null }
            ?.takeIf { activated(dev, it) == true }

    /** Whether the ended [a] left [dev] on its pack (null while [dev] isn't idle): kept once the follower saw
     *  the end, else read off the Idle state now. */
    private fun activated(dev: ConnectedPebbleDevice, a: Attempt): Boolean? {
        val st = dev.languagePackInstallState as? LanguagePackInstallState.Idle ?: return null
        return a.activated ?: (st.activeLanguage == a.name)
    }

    /** Map [dev]'s [LanguagePackInstallState] and the [attempt] to the status-prefixed string (shared
     *  by [status] and [statusFlow]). For `Installing` it samples the current percentage; [statusFlow]
     *  re-emits live. */
    private fun statusString(dev: ConnectedPebbleDevice, a: Attempt?): String = when (val st = dev.languagePackInstallState) {
        is LanguagePackInstallState.Idle -> {
            val mine = a?.takeIf { it.deviceId == dev.identifier.asString }
            when {
                mine == null -> "idle:"
                mine.failure != null -> "failed:${mine.failure}"
                !mine.ran -> "idle:"
                activated(dev, mine) == true -> "done:${mine.name}"
                else -> "failed:${mine.name} isn't the watch's language after the install " +
                    "(the watch is on ${st.activeLanguage ?: "its previous language"}; see 'stoandl language installed')"
            }
        }
        is LanguagePackInstallState.Downloading -> "downloading:${st.language}"
        is LanguagePackInstallState.Installing ->
            "installing:${(st.progress.value * 100).toInt().coerceIn(0, 100)}"
    }

    /**
     * Reactive [status]: the same status-prefixed strings as a Flow that emits on every change — phase
     * transitions AND each install percentage tick (the % is in a nested `Installing.progress:
     * StateFlow<Float>` the outer flow doesn't re-surface, so we [flatMapLatest] into it). Drives the
     * `LanguageProgress` D-Bus signal. Filters [ConnectedPebbleDevice] — like [device], a recovery-mode
     * watch can't install a language pack — and emits `notready:` when none is connected.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun statusFlow(): Flow<String> {
        val lp = libPebbleRef.get() ?: return flowOf("notready:")
        return lp.watches
            .map { devs -> devs.filterIsInstance<ConnectedPebbleDevice>().firstOrNull() }
            .distinctUntilChanged { a, b -> a === b }
            // The outcome (done/failed) lives in [attempt], so re-evaluate when it changes too.
            .combine(attempt) { dev, a -> dev to a }
            .flatMapLatest { (dev, a) ->
                if (dev == null) return@flatMapLatest flowOf("notready:")
                when (val st = dev.languagePackInstallState) {
                    is LanguagePackInstallState.Installing ->
                        st.progress.map { "installing:${(it * 100).toInt().coerceIn(0, 100)}" }
                    else -> flowOf(statusString(dev, a))
                }
            }
            .distinctUntilChanged()
    }

    /**
     * Resolve [query] against [candidates] (already board-filtered): an exact catalog id wins, then
     * an exact ISO-locale match, then a same-language (first two chars) match, then a name substring.
     * Within a tie the highest pack [version] is chosen.
     */
    private fun pick(candidates: List<LanguagePack>, query: String): LanguagePack? {
        candidates.firstOrNull { it.id.equals(query, ignoreCase = true) }?.let { return it }
        val byBest = { sel: (LanguagePack) -> Boolean ->
            candidates.filter(sel).maxByOrNull { it.version }
        }
        return byBest { it.isoLocal.equals(query, ignoreCase = true) }
            ?: byBest { it.isoLocal.take(2).equals(query.take(2), ignoreCase = true) }
            ?: byBest { it.name.contains(query, ignoreCase = true) || it.localName.contains(query, ignoreCase = true) }
    }

    /**
     * The locale currently installed on [dev]. libpebble3's `WatchInfo` is captured once at connect
     * and never re-queried, so right after an install it still reports the old language until the watch
     * reconnects. To avoid that lag we prefer the locale of a catalog pack we installed on this
     * connection — but only once it finished without a failure (so a failed/in-flight install doesn't
     * show as installed) — then the language [use] or [remove] left it on. A reconnect clears both, so we
     * fall back to the now-correct connect-time snapshot.
     */
    private fun installedLocale(dev: ConnectedPebbleDevice): String? {
        succeeded(dev, attempt.value)?.let { return it.isoLocal ?: dev.installedLanguagePack?.isoLocal }
        chosen.value?.takeIf { it.deviceId == dev.identifier.asString }?.let { return it.locale }
        return dev.installedLanguagePack?.isoLocal
    }

    // ---- the packs on the watch (firmware that keeps several) -------------------------------------

    /**
     * The language packs on the connected watch, one tab-separated record per line after `ok:`:
     * `slot \t locale \t name \t version \t active(yes|no) \t kind(translation|fonts|unreadable)` (no
     * pack active: built-in English). `notready:`, `unavailable:` (firmware with one pack, which an
     * install replaces), `busy:` (an install runs) or `error:`.
     */
    fun packs(): String {
        val dev = device() ?: return "notready:No watch connected"
        packsRefusal(dev)?.let { return it }
        return try {
            "ok:" + watchCall { dev.listLanguagePacks() }.joinToString("\n") { p ->
                listOf(
                    p.slot.toString(), p.locale, p.name, p.version.toString(), if (p.isActive) "yes" else "no",
                    when {
                        p.hasTranslation -> "translation"
                        p.readable -> "fonts"
                        else -> "unreadable"
                    },
                ).joinToString("\t")
            }
        } catch (e: Exception) {
            log.warn(e) { "listLanguagePacks failed" }
            "error:Couldn't read the language packs on the watch: ${e.message ?: e::class.simpleName}"
        }
    }

    /**
     * Make [query] the watch's language: a pack on the watch by slot, locale or name, or `english` (the
     * built-in language; the packs stay). `ok:<message>`, `notfound:`, `ambiguous:`, `failed:` (the watch
     * refused), `notready:`, `unavailable:`, `busy:` or `error:`.
     */
    fun use(query: String): String {
        val dev = device() ?: return "notready:No watch connected"
        packsRefusal(dev)?.let { return it }
        return try {
            val english = query.trim().equals(ENGLISH, ignoreCase = true)
            val pack = if (english) null else when (val m = matchPack(watchCall { dev.listLanguagePacks() }, query)) {
                is PackMatch.Found -> m.pack
                is PackMatch.Refused -> return m.status
            }
            if (pack != null && !pack.hasTranslation) {
                return "error:Slot ${pack.slot} holds fonts only, no translation to switch to"
            }
            val label = pack?.name ?: "English"
            if (!watchCall { dev.setActiveLanguage(pack) }) return "failed:The watch didn't switch to $label"
            settled(dev, pack?.locale)
            "ok:The watch's language is $label"
        } catch (e: Exception) {
            log.warn(e) { "setActiveLanguage($query) failed" }
            "error:${e.message ?: "couldn't change the watch's language"}"
        }
    }

    /**
     * Remove a pack from the watch by slot, locale or name; removing the active one switches the watch to
     * English. Same returns as [use].
     */
    fun remove(query: String): String {
        val dev = device() ?: return "notready:No watch connected"
        packsRefusal(dev)?.let { return it }
        return try {
            val pack = when (val m = matchPack(watchCall { dev.listLanguagePacks() }, query)) {
                is PackMatch.Found -> m.pack
                is PackMatch.Refused -> return m.status
            }
            if (!watchCall { dev.removeLanguagePack(pack) }) return "failed:The watch didn't remove ${pack.name}"
            if (pack.isActive) settled(dev, locale = null)
            "ok:Removed ${pack.name} from slot ${pack.slot}" + if (pack.isActive) "; the watch's language is English" else ""
        } catch (e: Exception) {
            log.warn(e) { "removeLanguagePack($query) failed" }
            "error:${e.message ?: "couldn't remove the language pack"}"
        }
    }

    /** Why [packs], [use] or [remove] can't run on [dev] now, or null. */
    private fun packsRefusal(dev: ConnectedPebbleDevice): String? {
        if (!multiPack(dev)) {
            return "unavailable:This watch's firmware holds one language pack; installing another replaces it " +
                "(several packs need PebbleOS 4.42 or later)"
        }
        return busy(dev)
    }

    /** After a [use] or [remove] that changed the language: [list] shows [locale], and the last install's
     *  `done:` (about the language before) gives way to `idle:`. */
    private fun settled(dev: ConnectedPebbleDevice, locale: String?) {
        follow()
        chosen.value = Chosen(dev.identifier.asString, locale)
        attempt.update { a -> a?.takeUnless { it.deviceId == dev.identifier.asString } }
    }

    private fun <T> watchCall(call: suspend () -> T): T = runBlocking { withTimeout(WATCH_CALL_TIMEOUT) { call() } }

    /** The daemon's locale as an `xx_YY` tag (e.g. `de_DE`), matching the catalog's `ISOLocal`. */
    private fun systemLocale(): String {
        val l = Locale.getDefault()
        return if (l.country.isNotEmpty()) "${l.language}_${l.country}" else l.language
    }

    companion object {
        /** How long a call to the watch (list, switch, remove) may take; libpebble3 waits 5 s per answer. */
        private val WATCH_CALL_TIMEOUT = 15.seconds
        /** How long after an install starts the watches snapshot may still show the watch idle. */
        private const val START_GRACE_MS = 5_000L
        /** [use]'s word for the watch's built-in language. */
        const val ENGLISH = "english"
        private const val DISABLED =
            "disabled:Language-pack download is off (set language.download = true in stoandl.conf). " +
                "Local 'stoandl language sideload <file.pbl>' always works."
    }
}

internal sealed interface PackMatch {
    data class Found(val pack: WatchLanguagePack) : PackMatch
    /** `notfound:` or `ambiguous:`. */
    data class Refused(val status: String) : PackMatch
}

/** `de-DE`, `de_de` and `de_DE` are one locale. */
internal fun sameLocale(a: String, b: String) = a.replace('-', '_').equals(b.replace('-', '_'), ignoreCase = true)

/**
 * The pack among [packs] that [query] names: its slot number, its locale, its name, or a unique part of its
 * name, in that order.
 */
internal fun matchPack(packs: List<WatchLanguagePack>, query: String): PackMatch {
    val q = query.trim()
    if (q.isEmpty()) return PackMatch.Refused("notfound:Name a pack by slot, locale or name ('stoandl language installed')")
    fun pick(found: List<WatchLanguagePack>): PackMatch? = when (found.size) {
        0 -> null
        1 -> PackMatch.Found(found.single())
        else -> PackMatch.Refused("ambiguous:'$q' matches slots ${found.joinToString(", ") { "${it.slot} (${it.name})" }}; name one by its slot")
    }
    return q.toIntOrNull()?.let { slot -> pick(packs.filter { it.slot == slot }) }
        ?: pick(packs.filter { sameLocale(it.locale, q) })
        ?: pick(packs.filter { it.name.equals(q, ignoreCase = true) })
        ?: pick(packs.filter { it.name.contains(q, ignoreCase = true) })
        ?: PackMatch.Refused("notfound:No language pack '$q' on the watch ('stoandl language installed' lists them)")
}
