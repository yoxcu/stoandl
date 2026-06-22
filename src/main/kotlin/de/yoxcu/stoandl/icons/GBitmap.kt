package de.yoxcu.stoandl.icons

/**
 * Decoder for a Pebble **GBitmap** — the raw bitmap encoding used for resource images that aren't
 * already PNGs. (A menu-icon resource in a `.pbw` is EITHER a PNG, which we pass through unchanged,
 * OR a GBitmap, which this decodes.)
 *
 * Header (little-endian), verified against real `.pbw` files:
 * `uint16 row_size_bytes, uint16 info_flags, int16 origin_x, int16 origin_y, uint16 width, uint16 height`
 * then the pixel data. `format = (info_flags >> 1) & 0x7`.
 *
 * Formats handled:
 * - 0 = 1-bit (1 bpp, **LSB-first** in each byte; rows padded to `row_size_bytes`). Set bit →
 *   opaque near-black, clear → transparent, so the glyph reads on any background card.
 * - 1 = 8-bit (one ARGB2222 byte per pixel).
 * - 2/3/4 = 1/2/4-bit palettised: N-bit pixel indices (**MSB-first** in each byte) into a palette of
 *   `2^N` ARGB2222 colour bytes that follows the pixel data.
 * - 5 = 8-bit "circular" (chalk round display) — treated as 8-bit best-effort.
 *
 * Pebble colours are ARGB2222: bits `aarrggbb`, 2 bits per channel; we expand each 2-bit channel to
 * 8-bit via `× 85` (0,85,170,255). The result is a row-major `IntArray` of `0xAARRGGBB`.
 */
object GBitmap {

    class Decoded(val argb: IntArray, val width: Int, val height: Int)

    private fun u16(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)

    /** Expand an ARGB2222 byte (`aarrggbb`) to a 0xAARRGGBB Int. */
    private fun argb2222(v: Int): Int {
        val a = ((v ushr 6) and 3) * 85
        val r = ((v ushr 4) and 3) * 85
        val g = ((v ushr 2) and 3) * 85
        val b = (v and 3) * 85
        return (a shl 24) or (r shl 16) or (g shl 8) or b
    }

    /** Decode a GBitmap blob to RGBA, or null for an undecodable/unknown format or bad geometry. */
    fun decode(data: ByteArray): Decoded? {
        if (data.size < 12) return null
        val rowSize = u16(data, 0)
        val info = u16(data, 2)
        val width = u16(data, 8)
        val height = u16(data, 10)
        val format = (info ushr 1) and 0x7
        if (width <= 0 || height <= 0 || width > 1024 || height > 1024 || rowSize <= 0) return null

        val pixOff = 12
        val pix = data
        val out = IntArray(width * height)
        // Opaque near-black foreground for the monochrome (1-bit) case.
        val mono = (0xFF shl 24) or 0x101010

        fun rowByte(y: Int, byteInRow: Int): Int {
            val idx = pixOff + y * rowSize + byteInRow
            return if (idx in pix.indices) pix[idx].toInt() and 0xFF else 0
        }

        when (format) {
            0 -> { // 1-bit, LSB-first; set bit = opaque foreground, clear = transparent
                for (y in 0 until height) {
                    for (x in 0 until width) {
                        val bit = (rowByte(y, x ushr 3) ushr (x and 7)) and 1
                        out[y * width + x] = if (bit == 1) mono else 0
                    }
                }
            }
            1, 5 -> { // 8-bit (and round 8-bit, best-effort), one ARGB2222 byte per pixel
                for (y in 0 until height) {
                    for (x in 0 until width) {
                        out[y * width + x] = argb2222(rowByte(y, x))
                    }
                }
            }
            2, 3, 4 -> { // 1/2/4-bit palette indices (MSB-first) + trailing palette of 2^N colours
                val bits = when (format) { 2 -> 1; 3 -> 2; else -> 4 }
                val numColors = 1 shl bits
                val pixelsPerByte = 8 / bits
                val mask = numColors - 1
                // The palette occupies the LAST numColors bytes of the blob.
                val palStart = pix.size - numColors
                if (palStart < pixOff) return null
                val palette = IntArray(numColors) { argb2222(pix[palStart + it].toInt() and 0xFF) }
                for (y in 0 until height) {
                    for (x in 0 until width) {
                        val byte = rowByte(y, x / pixelsPerByte)
                        val shift = (pixelsPerByte - 1 - (x % pixelsPerByte)) * bits
                        val idx = (byte ushr shift) and mask
                        out[y * width + x] = palette[idx]
                    }
                }
            }
            else -> return null
        }
        return Decoded(out, width, height)
    }
}
