package de.yoxcu.stoandl.imaging

import io.github.oshai.kotlinlogging.KotlinLogging
import io.rebble.libpebblecommon.imaging.EncodedImage
import io.rebble.libpebblecommon.imaging.ImageEncoder
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.util.concurrent.Executors

private val log = KotlinLogging.logger {}

/**
 * Turns an [ImageSource] into bounded pixels: reads a local file (a regular file, at most [MAX_INPUT_BYTES]),
 * decodes it ([ImageDecoder]) and shrinks it to a requested longest side. One image at a time on its own
 * thread, so a large screenshot never runs on a notification or watch-protocol collector.
 */
object ImageLoader {
    /** Largest encoded input read at all (a 50 MP phone JPEG is about 25 MB). */
    const val MAX_INPUT_BYTES = 32L shl 20

    /** How long one decode may take before it is abandoned. */
    const val DECODE_DEADLINE_MS = 5_000L

    private val dispatcher = Executors.newSingleThreadExecutor { r ->
        Thread(r, "stoandl-image").apply { isDaemon = true }
    }.asCoroutineDispatcher()

    /** [src] decoded and fitted within [maxLongSide] (never enlarged), or null when it isn't a readable image. */
    suspend fun load(src: ImageSource, maxLongSide: Int): ImageSource.Pixels? = withContext(dispatcher) {
        val px = when (src) {
            is ImageSource.Pixels -> src
            is ImageSource.Bytes -> decodeBounded(src.bytes, maxLongSide, "data")
            is ImageSource.LocalFile -> readFile(src)?.let { decodeBounded(it, maxLongSide, src.path.toString()) }
        } ?: return@withContext null
        fit(px, maxLongSide)
    }

    private fun decodeBounded(bytes: ByteArray, maxLongSide: Int, what: String): ImageSource.Pixels? {
        if (bytes.size > MAX_INPUT_BYTES) { log.info { "Image $what too large (${bytes.size} B)" }; return null }
        return ImageDecoder.decode(bytes, maxLongSide, DECODE_DEADLINE_MS)
            .also { if (it == null) log.debug { "Not a decodable image: $what" } }
    }

    private fun readFile(src: ImageSource.LocalFile): ByteArray? = try {
        val p = src.path
        when {
            !Files.isRegularFile(p) -> { log.debug { "Image file missing or not a regular file: $p" }; null }
            Files.size(p) > MAX_INPUT_BYTES -> { log.info { "Image file too large: $p (${Files.size(p)} B)" }; null }
            else -> Files.readAllBytes(p)
        }
    } catch (e: Exception) {
        log.debug { "Can't read image ${src.path}: ${e.message}" }
        null
    }

    /** [px] scaled down so its longer side is at most [maxLongSide]. */
    fun fit(px: ImageSource.Pixels, maxLongSide: Int): ImageSource.Pixels {
        val (w, h) = fitWithin(px.width, px.height, maxLongSide)
        if (w == px.width && h == px.height) return px
        return ImageSource.Pixels(scale(px.argb, px.width, Crop(0, 0, px.width, px.height), w, h), w, h)
    }
}

/** Images for the watch: the JVM twin of the official app's Android `encodeForWatch`. */
object WatchImage {
    /** Background a transparent pixel is composited onto (the watch draws image pixels as they are). */
    const val BACKGROUND = 0xFFFFFF

    /** [px] centre-cropped to the [width]×[height] aspect, scaled to exactly that size and encoded in the
     *  watch's 4-bit palette format. */
    fun encode(px: ImageSource.Pixels, width: Int, height: Int): EncodedImage {
        val crop = centreCrop(px.width, px.height, width, height)
        val scaled = scale(px.argb, px.width, crop, width, height)
        return ImageEncoder.encode(flatten(scaled, BACKGROUND), width, height)
    }
}
