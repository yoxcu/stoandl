package de.yoxcu.stoandl.firmware

import de.yoxcu.stoandl.util.LenientJson
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

private val log = KotlinLogging.logger {}

/**
 * Firmware source for **Core devices** (Pebble 2 Duo / Pebble Time 2 …). Their firmware is built by CI
 * in the PebbleOS repo and attached to each GitHub release as per-board `normal_<board>_<version>.pbz`
 * bundles, where `<board>` is exactly the connected watch's `WatchHardwarePlatform.revision` (e.g.
 * `obelix_pvt`, `getafix_dvt2`, `asterix`). That makes the watch→asset mapping exact, no lookup table.
 *
 * "Latest" is the highest version among the recent releases, not GitHub's `/releases/latest`: that is
 * simply the last one published, and PebbleOS publishes backports (v4.27.3, v4.9.142.4, …) after newer
 * releases. A watch in recovery is offered whatever this resolves, so trusting GitHub's flag would
 * have flashed a backport onto it.
 *
 * Opt-in egress (gated by `firmware.github`). No token/account is needed — the PebbleOS releases are
 * public. Network/JSON failures map to [FirmwareSource.Resolution.Unreachable] / null rather than
 * throwing, so a transient GitHub outage degrades to "couldn't check".
 */
class GithubFirmwareSource(
    private val repo: String,
    private val includePrereleases: Boolean,
    /** The GitHub REST API root; a seam so tests can serve the release list locally. */
    private val apiBase: String = "https://api.github.com",
) : FirmwareSource {
    override val label: String get() = "GitHub ($repo)"
    override val disabledHint: String =
        "GitHub firmware updates are off (set firmware.github = true in stoandl.conf)"
    override val cacheKey: String get() = "github:$apiBase/$repo:prereleases=$includePrereleases"

    /**
     * The highest qualifying release among the newest [RELEASES_PER_PAGE]; only when none of those ships
     * a bundle for the board does it look again through [RELEASES_PER_PAGE_WIDE], the old page size.
     */
    override suspend fun resolve(boardRevision: String): FirmwareSource.Resolution {
        for (perPage in listOf(RELEASES_PER_PAGE, RELEASES_PER_PAGE_WIDE)) {
            val body = releasesJson(perPage)
                ?: return FirmwareSource.Resolution.Unreachable("Couldn't reach GitHub ($repo)")
            val newest = try {
                newestRelease(body, boardRevision, includePrereleases)
            } catch (e: Exception) {
                log.warn(e) { "Failed to read the release list of $repo" }
                return FirmwareSource.Resolution.Unreachable("Couldn't read the GitHub releases of $repo")
            } ?: continue
            return FirmwareSource.Resolution.Found(newest.tag, firmwareBundle(newest.asset.name, newest.asset.url, newest.asset.sha256))
        }
        return FirmwareSource.Resolution.NoFirmware
    }

    internal data class Asset(val name: String, val url: String, val sha256: String? = null)
    internal data class Release(val tag: String, val asset: Asset)

    /** The [perPage] most recent releases, newest first, as the raw API response. Null on any failure. */
    private suspend fun releasesJson(perPage: Int): String? = withContext(Dispatchers.IO) {
        try {
            getText("$apiBase/repos/$repo/releases?per_page=$perPage")
        } catch (e: Exception) {
            log.warn(e) { "Failed to fetch releases from $repo" }
            null
        }
    }

    private fun getText(url: String): String? {
        val resp = FirmwareHttp.getText(url, "application/vnd.github+json") ?: return null
        return if (resp.status in 200..299) {
            resp.body
        } else {
            log.warn { "GitHub API HTTP ${resp.status} for $url" }
            null
        }
    }

    @Serializable
    private data class GhRelease(
        @SerialName("tag_name") val tagName: String = "",
        val draft: Boolean = false,
        val prerelease: Boolean = false,
        val assets: List<GhAsset> = emptyList(),
    ) {
        /**
         * The `normal` (non-recovery) firmware bundle for [boardRevision], if this release has one. We
         * pick the combined dual-slot `.pbz` (no `_slot0`/`_slot1` suffix); libpebble3 selects the right
         * slot from its manifest. Null when the release ships no asset for the board — e.g. a classic
         * Pebble whose firmware lives on cohorts.rebble.io ([CohortsFirmwareSource]), not here.
         */
        fun normalBundleFor(boardRevision: String): Asset? {
            val prefix = "normal_${boardRevision}_"
            return assets.firstOrNull {
                it.name.startsWith(prefix) && it.name.endsWith(".pbz") && !it.name.contains("_slot")
            }?.let { Asset(it.name, it.browserDownloadUrl, it.digest.takeIf { d -> d.startsWith("sha256:") }?.removePrefix("sha256:")) }
        }
    }

    @Serializable
    private data class GhAsset(
        val name: String = "",
        @SerialName("browser_download_url") val browserDownloadUrl: String = "",
        /** `sha256:<hex>`, which GitHub computes for every asset uploaded since mid-2025. */
        val digest: String = "",
    )

    internal companion object {
        /**
         * The release list is about 0.25 MB per release, so this is most of a check's cost. The newest
         * release for a board is normally the first or second listed; what pushes it down are backports
         * (v4.27.3, v4.30.3, v4.9.142.4 …), pre-releases and board-specific releases published after it,
         * which [newestRelease] skips. Ten leaves room for a batch of those.
         */
        internal const val RELEASES_PER_PAGE = 10

        /** The second look when the first page has nothing for the board (a board-specific gap). */
        internal const val RELEASES_PER_PAGE_WIDE = 30

        /**
         * Pick, from a `/releases` response [body], the highest-versioned release ([FirmwareTag] order)
         * that is not a draft, is not a pre-release unless [includePrereleases], and ships a normal bundle
         * for [boardRevision]. Tags without a version can't be ranked, so they count only when no versioned
         * release qualifies (then the newest-published one wins, as a fork's own tagging may need). Null
         * when nothing qualifies. Throws on a body that isn't a release list.
         */
        fun newestRelease(body: String, boardRevision: String, includePrereleases: Boolean): Release? {
            val candidates = LenientJson.decodeFromString<List<GhRelease>>(body)
                .filter { !it.draft && (includePrereleases || !it.prerelease) }
                .mapNotNull { r -> r.normalBundleFor(boardRevision)?.let { Release(r.tagName, it) } }
            return candidates
                .mapNotNull { r -> FirmwareTag.parse(r.tag)?.let { r to it } }
                .maxByOrNull { it.second }?.first
                ?: candidates.firstOrNull()
        }
    }
}
