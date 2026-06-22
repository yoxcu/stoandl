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

    init {
        // Bust the icon cache when the extraction format changes (e.g. the upscale below) so stale
        // low-res PNGs from an older build get regenerated instead of served forever.
        val marker = File(iconDir, ".fmt")
        if (!marker.isFile || marker.readText().trim() != CACHE_VERSION) {
            iconDir.deleteRecursively()
            iconDir.mkdirs()
            runCatching { marker.writeText(CACHE_VERSION) }
        }
    }

    // PNG menu icons (colour faces ship these) — search colour platforms first.
    private val colourOrder = listOf(
        WatchType.BASALT, WatchType.CHALK, WatchType.EMERY,
        WatchType.DIORITE, WatchType.APLITE, WatchType.FLINT, WatchType.GABBRO,
    )
    // Monochrome GBitmap icons (e.g. app glyphs like hooky): prefer the 1-bit B&W variants — hard edges
    // that stay crisp when enlarged, vs basalt's anti-aliased palette whose grey edge becomes a soft halo.
    private val monoOrder = listOf(
        WatchType.APLITE, WatchType.DIORITE, WatchType.BASALT,
        WatchType.CHALK, WatchType.EMERY, WatchType.FLINT, WatchType.GABBRO,
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

        fun resourceBytes(watchType: WatchType): ByteArray? {
            val pbpack = app.getResourcesFor(watchType)?.buffered()?.use { it.readByteArray() } ?: return null
            return Pbpack.resource(pbpack, resourceId)
        }

        // 1) A PNG menu icon — colour faces ship these; pass it through unchanged (colour-platform order).
        for (watchType in colourOrder) {
            val res = resourceBytes(watchType) ?: continue
            if (isPng(res)) {
                log.debug { "${pbwFile.name}: PNG menu icon from ${watchType.codename}" }
                return res
            }
        }
        // 2) Otherwise a GBitmap (typically a monochrome glyph): decode the crispest 1-bit variant. No
        //    upscaling — the GUI renders it at native pixels so the hard edges stay sharp.
        for (watchType in monoOrder) {
            val res = resourceBytes(watchType) ?: continue
            val bmp = GBitmap.decode(res) ?: continue
            log.debug { "${pbwFile.name}: GBitmap menu icon from ${watchType.codename} (1-bit pref)" }
            return PngEncoder.encodeRgba(flattenOnWhite(bmp.argb), bmp.width, bmp.height)
        }
        log.debug { "${pbwFile.name}: menuIcon resource $resourceId not found/decodable on any platform" }
        return null
    }

    private fun isPng(b: ByteArray) =
        b.size >= 8 && (b[0].toInt() and 0xFF) == 0x89 && b[1].toInt() == 0x50 &&
            b[2].toInt() == 0x4E && b[3].toInt() == 0x47

    /** Composite a (possibly transparent) RGBA buffer over opaque white in place, so a black-on-transparent
     *  1-bit glyph reads as black-on-white — the negative space is true white, not the GUI card colour. */
    private fun flattenOnWhite(argb: IntArray): IntArray {
        for (i in argb.indices) {
            val c = argb[i]
            val a = (c ushr 24) and 0xFF
            if (a == 0xFF) continue
            val r = ((c ushr 16 and 0xFF) * a + 0xFF * (0xFF - a)) / 0xFF
            val g = ((c ushr 8 and 0xFF) * a + 0xFF * (0xFF - a)) / 0xFF
            val b = ((c and 0xFF) * a + 0xFF * (0xFF - a)) / 0xFF
            argb[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        return argb
    }

    companion object {
        // Bump to invalidate the on-disk icon cache when the output format changes.
        private const val CACHE_VERSION = "4"
    }
}
