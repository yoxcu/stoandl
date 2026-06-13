package de.yoxcu.stoandl.firmware

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse.BodyHandlers
import java.time.Duration

private val log = KotlinLogging.logger {}

/**
 * Reads firmware bundles published as GitHub release assets. Core-device firmware (Pebble 2 Duo /
 * Pebble Time 2) is built by CI in the PebbleOS repo and attached to each release as per-board
 * `normal_<board>_<version>.pbz` bundles, where `<board>` is exactly the connected watch's
 * `WatchHardwarePlatform.revision` (e.g. `obelix_pvt`, `getafix_dvt2`, `asterix`). That makes the
 * watch→asset mapping exact, with no lookup table to drift.
 *
 * Every call here is opt-in egress (gated by `firmware.github` in the config). No token/account is
 * needed — the PebbleOS releases are public. Network/JSON failures return null rather than throwing,
 * so a transient GitHub outage degrades to "no update found".
 */
class GithubFirmwareSource(
    private val repo: String,
    private val includePrereleases: Boolean,
) {
    private val http = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .connectTimeout(Duration.ofSeconds(15))
        .build()
    private val json = Json { ignoreUnknownKeys = true }

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
     * classic Pebble whose firmware lives on cohorts.rebble.io, not here.
     */
    fun normalBundleFor(release: Release, boardRevision: String): Asset? {
        val prefix = "normal_${boardRevision}_"
        return release.assets.firstOrNull {
            it.name.startsWith(prefix) && it.name.endsWith(".pbz") && !it.name.contains("_slot")
        }
    }

    /** Download [asset] to a temp `.pbz` file. Returns the file, or null on failure. */
    suspend fun download(asset: Asset): File? = withContext(Dispatchers.IO) {
        val out = File.createTempFile("stoandl-fw-", ".pbz")
        out.deleteOnExit()
        try {
            val req = HttpRequest.newBuilder(URI.create(asset.url))
                .header("User-Agent", USER_AGENT)
                .timeout(Duration.ofMinutes(2))
                .GET()
                .build()
            val resp = http.send(req, BodyHandlers.ofFile(out.toPath()))
            if (resp.statusCode() !in 200..299) {
                log.warn { "Firmware download failed: HTTP ${resp.statusCode()} for ${asset.url}" }
                out.delete()
                null
            } else {
                log.info { "Downloaded ${asset.name} (${out.length()} bytes)" }
                out
            }
        } catch (e: Exception) {
            log.warn(e) { "Failed to download firmware ${asset.name}" }
            out.delete()
            null
        }
    }

    private fun getText(url: String): String? {
        val req = HttpRequest.newBuilder(URI.create(url))
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/vnd.github+json")
            .timeout(Duration.ofSeconds(30))
            .GET()
            .build()
        val resp = http.send(req, BodyHandlers.ofString())
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

    companion object {
        private const val USER_AGENT = "stoandl-firmware-updater"
    }
}
