package de.yoxcu.stoandl.imaging

import kotlin.math.max
import kotlin.math.min

/** A sub-rectangle of a source image. */
data class Crop(val x: Int, val y: Int, val width: Int, val height: Int)

/** The largest centred part of a [srcW]×[srcH] image with the aspect of [dstW]×[dstH]. */
fun centreCrop(srcW: Int, srcH: Int, dstW: Int, dstH: Int): Crop {
    // Compare srcW/srcH with dstW/dstH without rounding: srcW*dstH vs dstW*srcH.
    return if (srcW.toLong() * dstH > dstW.toLong() * srcH) {
        val w = max(1, ((srcH.toLong() * dstW + dstH / 2) / dstH).toInt())
        Crop((srcW - w) / 2, 0, w, srcH)
    } else {
        val h = max(1, ((srcW.toLong() * dstH + dstW / 2) / dstW).toInt())
        Crop(0, (srcH - h) / 2, srcW, h)
    }
}

/** [w]×[h] that fits within [maxLongSide], keeping the aspect (unchanged if it already fits). */
fun fitWithin(w: Int, h: Int, maxLongSide: Int): Pair<Int, Int> {
    val long = max(w, h)
    if (long <= maxLongSide) return w to h
    val scale = maxLongSide.toDouble() / long
    return max(1, Math.round(w * scale).toInt()) to max(1, Math.round(h * scale).toInt())
}

/**
 * Scale [crop] of [src] ([srcW] wide) to exactly [dstW]×[dstH]: an area average when shrinking (every
 * source pixel weighted by how much of it a destination pixel covers, so it doesn't alias at any
 * factor), bilinear when enlarging. Works on premultiplied colour so transparent edges don't darken.
 */
fun scale(src: IntArray, srcW: Int, crop: Crop, dstW: Int, dstH: Int): IntArray =
    if (crop.width >= dstW && crop.height >= dstH) areaDownscale(src, srcW, crop, dstW, dstH)
    else bilinear(src, srcW, crop, dstW, dstH)

private fun areaDownscale(src: IntArray, srcW: Int, c: Crop, dstW: Int, dstH: Int): IntArray {
    val out = IntArray(dstW * dstH)
    val sx = c.width.toDouble() / dstW
    val sy = c.height.toDouble() / dstH
    for (dy in 0 until dstH) {
        val y0 = dy * sy; val y1 = y0 + sy
        for (dx in 0 until dstW) {
            val x0 = dx * sx; val x1 = x0 + sx
            var a = 0.0; var r = 0.0; var g = 0.0; var b = 0.0; var area = 0.0
            var yy = y0.toInt()
            while (yy < y1 && yy < c.height) {
                val wy = min(yy + 1.0, y1) - max(yy.toDouble(), y0)
                val row = (c.y + yy) * srcW + c.x
                var xx = x0.toInt()
                while (xx < x1 && xx < c.width) {
                    val w = wy * (min(xx + 1.0, x1) - max(xx.toDouble(), x0))
                    val p = src[row + xx]
                    val pa = (p ushr 24) and 0xFF
                    a += pa * w
                    r += ((p shr 16) and 0xFF) * pa * w
                    g += ((p shr 8) and 0xFF) * pa * w
                    b += (p and 0xFF) * pa * w
                    area += w
                    xx++
                }
                yy++
            }
            out[dy * dstW + dx] = unpremultiply(a, r, g, b, area)
        }
    }
    return out
}

private fun bilinear(src: IntArray, srcW: Int, c: Crop, dstW: Int, dstH: Int): IntArray {
    val out = IntArray(dstW * dstH)
    for (dy in 0 until dstH) {
        val fy = ((dy + 0.5) * c.height / dstH - 0.5).coerceIn(0.0, c.height - 1.0)
        val y0 = fy.toInt(); val y1 = min(y0 + 1, c.height - 1); val ty = fy - y0
        for (dx in 0 until dstW) {
            val fx = ((dx + 0.5) * c.width / dstW - 0.5).coerceIn(0.0, c.width - 1.0)
            val x0 = fx.toInt(); val x1 = min(x0 + 1, c.width - 1); val tx = fx - x0
            var a = 0.0; var r = 0.0; var g = 0.0; var b = 0.0
            fun add(x: Int, y: Int, w: Double) {
                val p = src[(c.y + y) * srcW + c.x + x]
                val pa = (p ushr 24) and 0xFF
                a += pa * w; r += ((p shr 16) and 0xFF) * pa * w; g += ((p shr 8) and 0xFF) * pa * w; b += (p and 0xFF) * pa * w
            }
            add(x0, y0, (1 - tx) * (1 - ty)); add(x1, y0, tx * (1 - ty))
            add(x0, y1, (1 - tx) * ty); add(x1, y1, tx * ty)
            out[dy * dstW + dx] = unpremultiply(a, r, g, b, 1.0)
        }
    }
    return out
}

private fun unpremultiply(a: Double, r: Double, g: Double, b: Double, area: Double): Int {
    if (a <= 0.0) return 0
    val alpha = (a / area).roundClamp()
    return (alpha shl 24) or ((r / a).roundClamp() shl 16) or ((g / a).roundClamp() shl 8) or (b / a).roundClamp()
}

private fun Double.roundClamp(): Int = Math.round(this).toInt().coerceIn(0, 255)

/** Composite every pixel over the opaque [background] (0xRRGGBB): the watch draws image pixels as they are,
 *  so a transparent one would come out black. */
fun flatten(argb: IntArray, background: Int): IntArray = IntArray(argb.size) { i ->
    val p = argb[i]
    val a = (p ushr 24) and 0xFF
    if (a == 255) return@IntArray p
    fun mix(shift: Int) = (((p shr shift) and 0xFF) * a + ((background shr shift) and 0xFF) * (255 - a) + 127) / 255
    (0xFF shl 24) or (mix(16) shl 16) or (mix(8) shl 8) or mix(0)
}

/** Rotate/flip row-major [argb] by an EXIF orientation (1..8); returns the pixels and the new size. */
fun orient(argb: IntArray, w: Int, h: Int, orientation: Int): Triple<IntArray, Int, Int> {
    if (orientation !in 2..8) return Triple(argb, w, h)
    val swap = orientation >= 5
    val ow = if (swap) h else w
    val oh = if (swap) w else h
    val out = IntArray(argb.size)
    for (y in 0 until h) for (x in 0 until w) {
        val (nx, ny) = when (orientation) {
            2 -> (w - 1 - x) to y            // mirror horizontally
            3 -> (w - 1 - x) to (h - 1 - y)  // rotate 180
            4 -> x to (h - 1 - y)            // mirror vertically
            5 -> y to x                      // transpose
            6 -> (h - 1 - y) to x            // rotate 90 clockwise
            7 -> (h - 1 - y) to (w - 1 - x)  // transverse
            else -> y to (w - 1 - x)         // 8: rotate 90 counter-clockwise
        }
        out[ny * ow + nx] = argb[y * w + x]
    }
    return Triple(out, ow, oh)
}
