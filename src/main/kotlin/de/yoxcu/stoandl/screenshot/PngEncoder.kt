package de.yoxcu.stoandl.screenshot

import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater

/**
 * A tiny, dependency-free PNG encoder for opaque ARGB images.
 *
 * Watch screenshots come out of libpebble3 as a row-major `IntArray` of `0xAARRGGBB` pixels. We deliberately
 * avoid `java.awt`/`javax.imageio`: the daemon's real home is musl/postmarketOS, where a headless JRE may not
 * ship the AWT native libs at all (the same portability reason btleplug was dropped). `java.util.zip` is
 * pure-JDK and always present, so we hand-roll the (very small) PNG container around a `Deflater` stream.
 *
 * Output is 8-bit truecolour (colour type 2, no alpha — watch screens are opaque) with filter type 0 on every
 * scanline. Not optimised for size; screenshots are tiny (≤ 200×228), so it doesn't matter.
 */
object PngEncoder {
    private val SIGNATURE = byteArrayOf(137u.toByte(), 80, 78, 71, 13, 10, 26, 10)

    /** Encode [argb] (length must be [width] × [height], row-major) as a PNG byte stream. */
    fun encode(argb: IntArray, width: Int, height: Int): ByteArray {
        require(argb.size == width * height) { "pixel array is ${argb.size}, expected ${width * height}" }

        val out = ByteArrayOutputStream()
        out.write(SIGNATURE)

        // IHDR: width, height, bitDepth=8, colorType=2 (RGB), compression=0, filter=0, interlace=0
        val ihdr = ByteArrayOutputStream(13)
        writeInt(ihdr, width)
        writeInt(ihdr, height)
        ihdr.write(8)
        ihdr.write(2)
        ihdr.write(0)
        ihdr.write(0)
        ihdr.write(0)
        writeChunk(out, "IHDR", ihdr.toByteArray())

        // Filtered raw image: each scanline is a 0x00 filter byte followed by RGB triples.
        val raw = ByteArray(height * (1 + width * 3))
        var p = 0
        for (y in 0 until height) {
            raw[p++] = 0 // filter type: none
            val rowStart = y * width
            for (x in 0 until width) {
                val c = argb[rowStart + x]
                raw[p++] = (c ushr 16).toByte() // R
                raw[p++] = (c ushr 8).toByte()  // G
                raw[p++] = c.toByte()           // B
            }
        }
        writeChunk(out, "IDAT", zlib(raw))
        writeChunk(out, "IEND", ByteArray(0))

        return out.toByteArray()
    }

    private fun zlib(data: ByteArray): ByteArray {
        val deflater = Deflater(Deflater.DEFAULT_COMPRESSION)
        deflater.setInput(data)
        deflater.finish()
        val out = ByteArrayOutputStream(data.size / 2)
        val buf = ByteArray(8192)
        while (!deflater.finished()) {
            val n = deflater.deflate(buf)
            out.write(buf, 0, n)
        }
        deflater.end()
        return out.toByteArray()
    }

    private fun writeChunk(out: ByteArrayOutputStream, type: String, data: ByteArray) {
        writeInt(out, data.size)
        val typeBytes = type.toByteArray(Charsets.US_ASCII)
        out.write(typeBytes)
        out.write(data)
        val crc = CRC32()
        crc.update(typeBytes)
        crc.update(data)
        writeInt(out, crc.value.toInt())
    }

    private fun writeInt(out: ByteArrayOutputStream, v: Int) {
        out.write((v ushr 24) and 0xFF)
        out.write((v ushr 16) and 0xFF)
        out.write((v ushr 8) and 0xFF)
        out.write(v and 0xFF)
    }
}
