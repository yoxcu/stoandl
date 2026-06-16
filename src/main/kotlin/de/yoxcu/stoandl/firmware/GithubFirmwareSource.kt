package de.yoxcu.stoandl.firmware

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private val log = KotlinLogging.logger {}

/**
 * Firmware source for **Core devices** (Pebble 2 Duo / Pebble Time 2 …). Their firmware is built by CI
 * in the PebbleOS repo and attached to each GitHub release as per-board `normal_<board>_<version>.pbz`
 * bundles, where `<board>` is exactly the connected watch's `WatchHardwarePlatform.revision` (e.g.
 * `obelix_pvt`, `getafix_dvt2`, `asterix`). That makes the watch→asset mapping exact, no lookup table.
 *
 * Opt-in egress (gated by `firmware.github`). No token/account is needed — the PebbleOS releases are
 * public. Network/JSON failures map to [FirmwareSource.Resolution.Unreachable] / null rather than
 * throwing, so a transient GitHub outage degrades to "couldn't check".
 */
class GithubFirmwareSource(
    private val repo: String,
    private val includePrereleases: Boolean,
) : FirmwareSource {
    private val json = Json { ignoreUnknownKeys = true }

    override val label: String get() = "GitHub ($repo)"
    override val disabledHint: String =
        "GitHub firmware updates are off (set firmware.github = true in stoandl.conf)"

    override suspend fun resolve(boardRevision: String): FirmwareSource.Resolution {
        val release = latestRelease()
            ?: return FirmwareSource.Resolution.Unreachable("Couldn't reach GitHub ($repo)")
        val bundle = normalBundleFor(release, boardRevision)
            ?: return FirmwareSource.Resolution.NoFirmware
        return FirmwareSource.Resolution.Found(release.tag, bundle)
    }

    data class Asset(val name: String, val url: String, val size: Long)
    data class Release(val tag: String, val prerelease: Boolean, val assets: List<Asset>)

    /** Fetch the newest release (stable only, unless [includePrereleases]). Null on any failure. */
    suspend fun latestRelease(): Release? = withContext(Dispatchers.IO) {
        try {
            if (includePrereleases) {
                val body = getText("https://api.github.com/repos/$repo/releases?per_page=20")
                    ?: return@withContext null
                json.decodeFromString<List<GhRelease>>(body)
                    .firstOrNull { !it.draft }
                    ?.toRelease()
            } else {
                val body = getText("https://api.github.com/repos/$repo/releases/latest")
                    ?: return@withContext null
                json.decodeFromString<GhRelease>(body).toRelease()
            }
        } catch (e: Exception) {
            log.warn(e) { "Failed to fetch latest release from $repo" }
            null
        }
    }

    /**
     * The `normal` (non-recovery) firmware bundle for [boardRevision], if present in [release]. We
     * pick the combined dual-slot `.pbz` (no `_slot0`/`_slot1` suffix); libpebble3 selects the right
     * slot from its manifest. Returns null when this release ships no asset for the board — e.g. a
     * classic Pebble whose firmware lives on cohorts.rebble.io ([CohortsFirmwareSource]), not here.
     */
    fun normalBundleFor(release: Release, boardRevision: String): FirmwareBundle? {
        val prefix = "normal_${boardRevision}_"
        val asset = release.assets.firstOrNull {
            it.name.startsWith(prefix) && it.name.endsWith(".pbz") && !it.name.contains("_slot")
        } ?: return null
        return firmwareBundle(asset.name, asset.url)
    }

    private fun getText(url: String): String? {
        val resp = FirmwareHttp.getText(url, "application/vnd.github+json") ?: return null
        return if (resp.statusCode() in 200..299) {
            resp.body()
        } else {
            log.warn { "GitHub API HTTP ${resp.statusCode()} for $url" }
            null
        }
    }

    @Serializable
    private data class GhRelease(
        @SerialName("tag_name") val tagName: String = "",
        val prerelease: Boolean = false,
        val draft: Boolean = false,
        val assets: List<GhAsset> = emptyList(),
    ) {
        fun toRelease() = Release(
            tag = tagName,
            prerelease = prerelease,
            assets = assets.map { Asset(it.name, it.browserDownloadUrl, it.size) },
        )
    }

    @Serializable
    private data class GhAsset(
        val name: String = "",
        @SerialName("browser_download_url") val browserDownloadUrl: String = "",
        val size: Long = 0,
    )
}
