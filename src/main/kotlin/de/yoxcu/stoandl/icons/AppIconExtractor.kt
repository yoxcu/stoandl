package de.yoxcu.stoandl.icons

import de.yoxcu.stoandl.config.StoandlConfig
import de.yoxcu.stoandl.screenshot.PngEncoder
import io.github.oshai.kotlinlogging.KotlinLogging
import io.rebble.libpebblecommon.disk.pbw.PbwApp
import io.rebble.libpebblecommon.metadata.WatchType
import kotlinx.io.buffered
import kotlinx.io.readByteArray
import java.io.File

private val log = KotlinLogging.logger {}

/**
 * Extracts an installed watchapp/watchface's **menu icon** from its locally-cached `.pbw` and writes
 * it out as a PNG — entirely local, no network, no appstore.
 *
 * libpebble3 caches every installed app's `.pbw` on disk under `<configDir>/pbw-cache/` named
 * `<uuid>_<version>.pbw` (see `LockerPBWCache.pathForApp`). We read that file directly — globbing by
 * UUID prefix so we don't depend on the exact version string — and only ever touch it when it already
 * exists, so we never hit libpebble3's cache-miss download path. (Reaching `LockerPBWCache` through
 * Koin would also work, but it's a `protected`/internal cache; reading the well-defined on-disk layout
 * is simpler and keeps us provably offline.)
 *
 * The menu icon is one resource inside the per-platform `app_resources.pbpack`. Its id is the 1-based
 * position in `appinfo.json`'s `resources.media[]` of the entry with `menuIcon = true`. The resource
 * bytes are EITHER a PNG (passed through unchanged) or a Pebble GBitmap (decoded → RGBA → PNG).
 *
 * Results are cached at `<configDir>/icons/<uuid>.png`; a second request for the same app re-uses it.
 */
class AppIconExtractor(
    private val configDir: File = StoandlConfig.configDir(),
) {
    private val pbwCacheDir = File(configDir, "pbw-cache")
    private val iconDir = File(configDir, "icons")

    // Prefer colour platforms (richer icon), then mono, first variant actually present in the .pbw.
    private val platformPreference = listOf(
        WatchType.BASALT, WatchType.CHALK, WatchType.EMERY,
        WatchType.DIORITE, WatchType.APLITE, WatchType.FLINT, WatchType.GABBRO,
    )

    /**
     * Return the absolute path of a PNG menu icon for [uuid], extracting+caching it on first call.
     *
     * - Returns the path string on success.
     * - Returns null when there's no cached `.pbw`, no `menuIcon` declared, or the resource can't be
     *   decoded — the caller maps that to a `none:` D-Bus reply (a generic fallback icon in the GUI).
     */
    fun iconPngPath(uuid: String): String? {
        val cached = File(iconDir, "$uuid.png")
        if (cached.isFile && cached.length() > 0) return cached.absolutePath

        val pbwFile = findCachedPbw(uuid) ?: run {
            log.debug { "No cached .pbw for $uuid under ${pbwCacheDir.path}" }
            return null
        }

        return try {
            val png = extractMenuIconPng(pbwFile) ?: return null
            iconDir.mkdirs()
            // Write atomically (tmp + rename) so a partial write never leaves a corrupt cache entry.
            val tmp = File(iconDir, "$uuid.png.tmp")
            tmp.writeBytes(png)
            if (!tmp.renameTo(cached)) {
                cached.writeBytes(png)
                tmp.delete()
            }
            log.info { "Extracted menu icon for $uuid → ${cached.path} (${png.size} bytes)" }
            cached.absolutePath
        } catch (e: Exception) {
            log.warn(e) { "Failed to extract menu icon for $uuid" }
            null
        }
    }

    /** Find `<uuid>_<version>.pbw` in the cache by UUID prefix (version-agnostic). */
    private fun findCachedPbw(uuid: String): File? {
        if (!pbwCacheDir.isDirectory) return null
        val prefix = "${uuid}_"
        return pbwCacheDir.listFiles()
            ?.firstOrNull { it.isFile && it.name.startsWith(prefix) && it.name.endsWith(".pbw") }
    }

    /** Open the `.pbw`, locate the menuIcon resource, extract+decode it, return PNG bytes (or null). */
    private fun extractMenuIconPng(pbwFile: File): ByteArray? {
        val app = PbwApp(kotlinx.io.files.Path(pbwFile.absolutePath))
        val info = app.info

        // Resource id = 1-based index of the media entry flagged menuIcon.
        val media = info.resources.media
        val idx = media.indexOfFirst { it.menuIcon.value }
        if (idx < 0) {
            log.debug { "${pbwFile.name}: no menuIcon in appinfo media" }
            return null
        }
        val resourceId = idx + 1

        // Pick the first preferred platform whose resources pbpack is present in the .pbw.
        for (watchType in platformPreference) {
            val pbpack = app.getResourcesFor(watchType)?.buffered()?.use { it.readByteArray() } ?: continue
            val res = Pbpack.resource(pbpack, resourceId) ?: continue
            val png = resourceToPng(res) ?: continue
            log.debug { "${pbwFile.name}: menu icon from ${watchType.codename} (res id $resourceId)" }
            return png
        }
        log.debug { "${pbwFile.name}: menuIcon resource $resourceId not found/decodable on any platform" }
        return null
    }

    /** A menu-icon resource is either a PNG (pass through) or a GBitmap (decode → RGBA PNG). */
    private fun resourceToPng(res: ByteArray): ByteArray? {
        if (res.size >= 8 && res[0].toInt() and 0xFF == 0x89 &&
            res[1].toInt() == 0x50 && res[2].toInt() == 0x4E && res[3].toInt() == 0x47
        ) {
            return res // already a .png
        }
        val bmp = GBitmap.decode(res) ?: return null
        return PngEncoder.encodeRgba(bmp.argb, bmp.width, bmp.height)
    }
}
