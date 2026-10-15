package de.yoxcu.stoandl.imaging

import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import javax.imageio.ImageReader
import javax.imageio.event.IIOReadProgressListener
import javax.imageio.stream.ImageInputStream
import kotlin.math.max

private val log = KotlinLogging.logger {}

/**
 * Image decoding through javax.imageio, the only decoder: PNG, GIF and BMP everywhere; JPEG when the JRE
 * has `libjavajpeg` (Alpine's `openjdk25-jre-headless` doesn't, `openjdk25-jre` does; [jpegAvailable]).
 * No AWT display is touched (`java.awt.headless` is set in main()).
 *
 * Bounded: the header is read first and an image over [maxPixels] is refused before decoding (a
 * decompression bomb), large images are decoded subsampled, and a decode past its deadline is aborted
 * from inside the reader.
 */
object ImageDecoder {
    /** Most source pixels decoded at all (a 64 MP photo is fine, a 30000² bomb is not). */
    const val MAX_SOURCE_PIXELS = 64_000_000L

    /** Whether JPEG can be decoded here: its reader's native library loads. Checked once. */
    val jpegAvailable: Boolean by lazy {
        try {
            ImageIO.getImageReadersByFormatName("jpeg").next().dispose()
            true
        } catch (e: Throwable) {
            log.debug { "No JPEG decoder: ${e.javaClass.simpleName}: ${e.message}" }
            false
        }
    }

    /** The formats this JRE decodes, for the startup line and `stoandl support`. */
    fun formats(): List<String> = buildList {
        add("png"); add("gif"); add("bmp")
        if (jpegAvailable) add("jpeg")
    }

    /** "Images: png gif bmp jpeg", or the same with a hint when JPEG is missing. */
    fun describe(): String =
        "Images: ${formats().joinToString(" ")}" + if (jpegAvailable) "" else " (no JPEG: install the full JRE, e.g. openjdk25-jre)"

    /** Width and height from the header only, or null for something that isn't a decodable image. */
    fun size(bytes: ByteArray): Pair<Int, Int>? = withReader(bytes) { reader, _ -> reader.getWidth(0) to reader.getHeight(0) }

    /**
     * Decode [bytes] to ARGB with its EXIF orientation applied, subsampled so the longer side is less than
     * twice [maxLongSide] (callers scale the rest of the way). Null for a non-image, an image over
     * [MAX_SOURCE_PIXELS], a format this JRE can't read, or a decode that ran past [deadlineMs].
     */
    fun decode(bytes: ByteArray, maxLongSide: Int, deadlineMs: Long): ImageSource.Pixels? = withReader(bytes) { reader, format ->
        val w = reader.getWidth(0)
        val h = reader.getHeight(0)
        if (w <= 0 || h <= 0 || w.toLong() * h > MAX_SOURCE_PIXELS) {
            log.info { "Image ${w}x$h refused (over ${MAX_SOURCE_PIXELS / 1_000_000} MP)" }
            return@withReader null
        }
        val step = max(1, max(w, h) / max(1, maxLongSide))
        val param = reader.defaultReadParam.apply { if (step > 1) setSourceSubsampling(step, step, 0, 0) }
        val deadline = System.currentTimeMillis() + deadlineMs
        var aborted = false
        reader.addIIOReadProgressListener(object : IIOReadProgressListener {
            override fun imageProgress(source: ImageReader, percentageDone: Float) {
                if (System.currentTimeMillis() > deadline) { aborted = true; source.abort() }
            }
            override fun readAborted(source: ImageReader) { aborted = true }
            override fun sequenceStarted(source: ImageReader, minIndex: Int) {}
            override fun sequenceComplete(source: ImageReader) {}
            override fun imageStarted(source: ImageReader, imageIndex: Int) {}
            override fun imageComplete(source: ImageReader) {}
            override fun thumbnailStarted(source: ImageReader, imageIndex: Int, thumbnailIndex: Int) {}
            override fun thumbnailProgress(source: ImageReader, percentageDone: Float) {}
            override fun thumbnailComplete(source: ImageReader) {}
        })
        val img = reader.read(0, param)
        if (aborted) {
            log.info { "Image decode ${w}x$h ($format) aborted after ${deadlineMs} ms" }
            return@withReader null
        }
        val iw = img.width
        val ih = img.height
        val argb = img.getRGB(0, 0, iw, ih, null, 0, iw)
        val orientation = if (format == "jpeg") ExifOrientation.of(bytes) else 1
        val (px, ow, oh) = orient(argb, iw, ih, orientation)
        ImageSource.Pixels(px, ow, oh)
    }

    private fun <T> withReader(bytes: ByteArray, block: (ImageReader, String) -> T?): T? {
        var stream: ImageInputStream? = null
        var reader: ImageReader? = null
        return try {
            stream = ImageIO.createImageInputStream(ByteArrayInputStream(bytes)) ?: return null
            reader = ImageIO.getImageReaders(stream).asSequence().firstOrNull() ?: return null
            reader.setInput(stream, true, true)
            block(reader, reader.formatName.lowercase().let { if (it == "jpg") "jpeg" else it })
        } catch (e: Throwable) {
            // LinkageError included: a JPEG without libjavajpeg is "no image", never a crash.
            log.debug { "Image decode failed: ${e.javaClass.simpleName}: ${e.message}" }
            null
        } finally {
            reader?.dispose()
            stream?.close()
        }
    }
}

/** The EXIF orientation (1..8) of a JPEG: APP1 "Exif" → TIFF IFD0 tag 0x0112. 1 when absent or unreadable. */
object ExifOrientation {
    fun of(jpeg: ByteArray): Int = try { find(jpeg) } catch (_: IndexOutOfBoundsException) { 1 }

    private fun find(b: ByteArray): Int {
        fun u8(i: Int) = b[i].toInt() and 0xFF
        if (b.size < 4 || u8(0) != 0xFF || u8(1) != 0xD8) return 1
        var i = 2
        while (i + 4 <= b.size && u8(i) == 0xFF) {
            val marker = u8(i + 1)
            if (marker == 0xDA || marker == 0xD9) return 1 // start of scan: no EXIF before the image data
            val len = (u8(i + 2) shl 8) or u8(i + 3)
            if (marker == 0xE1 && len >= 8 && String(b, i + 4, 4, Charsets.ISO_8859_1) == "Exif") {
                return tiffOrientation(b, i + 10, i + 2 + len)
            }
            i += 2 + len
        }
        return 1
    }

    private fun tiffOrientation(b: ByteArray, tiff: Int, end: Int): Int {
        val little = b[tiff].toInt() == 'I'.code
        fun u16(o: Int): Int {
            val x = b[tiff + o].toInt() and 0xFF; val y = b[tiff + o + 1].toInt() and 0xFF
            return if (little) x or (y shl 8) else (x shl 8) or y
        }
        fun u32(o: Int): Int = if (little) u16(o) or (u16(o + 2) shl 16) else (u16(o) shl 16) or u16(o + 2)
        val ifd = u32(4)
        val count = u16(ifd)
        for (n in 0 until count) {
            val entry = ifd + 2 + n * 12
            if (tiff + entry + 12 > end) break
            if (u16(entry) == 0x0112) return u16(entry + 8).takeIf { it in 1..8 } ?: 1
        }
        return 1
    }
}
