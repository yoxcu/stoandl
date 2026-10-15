package de.yoxcu.stoandl.imaging

import de.yoxcu.stoandl.util.decodeDataUri
import java.net.URI
import java.nio.file.Path
import kotlin.io.path.Path

/**
 * Where an image comes from, before decoding: what notification images (an extension's photo, a
 * screenshot a desktop notification links to) and album art (a player's `mpris:artUrl`) hand to
 * [ImageLoader]. Only local data: nothing here is ever fetched from the network.
 */
sealed interface ImageSource {
    /** Already decoded, row-major 0xAARRGGBB. */
    class Pixels(val argb: IntArray, val width: Int, val height: Int) : ImageSource {
        init { require(width > 0 && height > 0 && argb.size == width * height) { "bad pixel geometry ${width}x$height" } }
    }

    /** An encoded image (PNG, JPEG, GIF, BMP): a `data:` body, an extension's base64. */
    class Bytes(val bytes: ByteArray) : ImageSource

    /** A file on this machine, read when the image is loaded. */
    class LocalFile(val path: Path) : ImageSource

    companion object {
        /**
         * The one parser of image references: a `file:` URI (escaped, or with raw spaces as some players
         * write it), an absolute path, or a `data:` URI. Anything else (http(s), an icon name) is null.
         */
        fun parse(ref: String): ImageSource? {
            val s = ref.trim()
            return when {
                s.isEmpty() -> null
                s.startsWith("data:", ignoreCase = true) -> runCatching { Bytes(decodeDataUri(s).bytes) }.getOrNull()
                s.startsWith("file:", ignoreCase = true) -> fileUriPath(s)?.let { LocalFile(Path(it)) }
                s.startsWith("/") -> LocalFile(Path(s))
                else -> null
            }
        }

        private fun fileUriPath(s: String): String? {
            val strict = runCatching { URI(s).path }.getOrNull()
            if (strict != null) return strict.takeIf { it.startsWith("/") }
            // Not a valid URI (a raw space or '#' in the name): take what follows the scheme as it is.
            val rest = s.substring("file:".length).let { if (it.startsWith("//")) it.substring(2) else it }
            return rest.takeIf { it.startsWith("/") }
        }
    }
}
