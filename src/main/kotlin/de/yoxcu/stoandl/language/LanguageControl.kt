package de.yoxcu.stoandl.language

import de.yoxcu.stoandl.config.StoandlConfig
import io.github.oshai.kotlinlogging.KotlinLogging
import io.rebble.libpebblecommon.connection.ConnectedPebbleDevice
import io.rebble.libpebblecommon.connection.LibPebble
import io.rebble.libpebblecommon.connection.endpointmanager.LanguagePackInstallState
import kotlinx.io.files.Path
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference

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
 * downloads first, then PutBytes-transfers the file as `lang` and tells the watch to install it.
 * The transfer runs asynchronously; callers poll [status] to follow it. Every method returns a
 * status-prefixed string (`ok:` / `error:` / `notready:` / …) so the CLI renders the real outcome.
 */
class LanguageControl(
    private val libPebbleRef: AtomicReference<LibPebble?>,
    private val config: StoandlConfig,
) {
    private val catalog by lazy { LanguagePackCatalog.load() }

    /** Language packs need a *normal* connection ([ConnectedPebbleDevice]); a recovery-mode watch
     *  (firmware-only) can't take one. */
    private fun device(): ConnectedPebbleDevice? =
        libPebbleRef.get()?.watches?.value?.filterIsInstance<ConnectedPebbleDevice>()?.firstOrNull()

    /** Install a local `.pbl` at [path] (absolute — the daemon's cwd differs from the CLI's). */
    fun sideload(path: String): String {
        libPebbleRef.get() ?: return "notready:libPebble not ready"
        val dev = device() ?: return "notready:No watch connected"
        val file = File(path)
        if (!file.isFile) return "error:No such file: $path"
        if (!file.name.endsWith(".pbl")) return "error:Not a language pack (expected a .pbl): ${file.name}"
        return try {
            dev.installLanguagePack(Path(path), file.name)
            "ok:Installing ${file.name}"
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
        val installed = dev.installedLanguagePack
        return catalog.forWatch(dev.watchInfo.platform, systemLocale()).map { pack ->
            val isInstalled = installed != null &&
                installed.isoLocal.equals(pack.isoLocal, ignoreCase = true) &&
                installed.version == pack.version
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
     * `ok:<displayName>` once kicked off (poll [status]), `notfound:`, `notready:`, or `error:`.
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
            dev.installLanguagePack(pack.file, pack.displayName())
            "ok:${pack.displayName()}"
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
        return when (val st = dev.languagePackInstallState) {
            is LanguagePackInstallState.Idle -> when {
                st.previousError != null -> "failed:${st.previousError}"
                st.successfullyInstalledLanguage != null -> "done:${st.successfullyInstalledLanguage}"
                else -> "idle:"
            }
            is LanguagePackInstallState.Downloading -> "downloading:${st.language}"
            is LanguagePackInstallState.Installing ->
                "installing:${(st.progress.value * 100).toInt().coerceIn(0, 100)}"
        }
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

    /** The daemon's locale as an `xx_YY` tag (e.g. `de_DE`), matching the catalog's `ISOLocal`. */
    private fun systemLocale(): String {
        val l = Locale.getDefault()
        return if (l.country.isNotEmpty()) "${l.language}_${l.country}" else l.language
    }

    companion object {
        private const val DISABLED =
            "disabled:Language-pack download is off (set language.download = true in stoandl.conf). " +
                "Local 'stoandl language sideload <file.pbl>' always works."
    }
}
