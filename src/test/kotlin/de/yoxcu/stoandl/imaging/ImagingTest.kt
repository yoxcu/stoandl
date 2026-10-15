@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package de.yoxcu.stoandl.imaging

import de.yoxcu.stoandl.notification.NotificationImageStore
import de.yoxcu.stoandl.screenshot.PngEncoder
import de.yoxcu.stoandl.util.decodeDataUri
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.util.Base64
import kotlin.io.path.Path
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/** The image pipeline without a watch: parsing references, decoding, cropping, scaling, the store. */
class ImagingTest {
    private fun solid(w: Int, h: Int, argb: Int) = IntArray(w * h) { argb }

    /** A [w]×[h] PNG: left half red, right half blue. */
    private fun png(w: Int, h: Int): ByteArray =
        PngEncoder.encodeRgba(IntArray(w * h) { i -> if (i % w < w / 2) 0xFFFF0000.toInt() else 0xFF0000FF.toInt() }, w, h)

    @Test
    fun `references parse to local sources only`() {
        assertEquals(Path("/tmp/a b.png"), (ImageSource.parse("file:///tmp/a%20b.png") as ImageSource.LocalFile).path)
        assertEquals(Path("/tmp/a b.png"), (ImageSource.parse("file:///tmp/a b.png") as ImageSource.LocalFile).path)
        assertEquals(Path("/tmp/x.jpg"), (ImageSource.parse("/tmp/x.jpg") as ImageSource.LocalFile).path)
        assertIs<ImageSource.Bytes>(ImageSource.parse("data:image/png;base64,iVBORw0KGgo="))
        assertNull(ImageSource.parse("https://i.scdn.co/image/abc"))
        assertNull(ImageSource.parse("audio-x-generic"))
        assertNull(ImageSource.parse(""))
    }

    @Test
    fun `data URIs decode byte-exact`() {
        val bytes = byteArrayOf(0, -1, 0x2B, 0x25)
        assertContentEquals(bytes, decodeDataUri("data:application/octet-stream;base64," + Base64.getEncoder().encodeToString(bytes)).bytes)
        assertContentEquals(byteArrayOf(0x00, -1, '+'.code.toByte()), decodeDataUri("data:,%00%FF+").bytes)
        assertEquals("text/plain", decodeDataUri("data:,hi").mime)
    }

    @Test
    fun `centre crop keeps the middle at the target aspect`() {
        assertEquals(Crop(50, 0, 100, 100), centreCrop(200, 100, 166, 166))
        assertEquals(Crop(0, 50, 100, 100), centreCrop(100, 200, 50, 50))
        assertEquals(Crop(0, 0, 180, 270), centreCrop(180, 270, 180, 270))
    }

    @Test
    fun `fitting never enlarges and keeps the aspect`() {
        assertEquals(300 to 150, fitWithin(1200, 600, 300))
        assertEquals(40 to 20, fitWithin(40, 20, 300))
    }

    @Test
    fun `area downscale averages, bilinear enlarges`() {
        val src = IntArray(4) { i -> if (i % 2 == 0) 0xFFFFFFFF.toInt() else 0xFF000000.toInt() } // 2x2, columns W/B
        val one = scale(src, 2, Crop(0, 0, 2, 2), 1, 1)
        assertEquals(0xFF808080.toInt(), one[0])
        val big = scale(solid(2, 2, 0xFF123456.toInt()), 2, Crop(0, 0, 2, 2), 5, 5)
        assertTrue(big.all { it == 0xFF123456.toInt() })
    }

    @Test
    fun `transparency is flattened onto the background`() {
        val out = flatten(intArrayOf(0x00000000, 0xFF00FF00.toInt(), 0x80000000.toInt()), 0xFFFFFF)
        assertEquals(0xFFFFFFFF.toInt(), out[0])
        assertEquals(0xFF00FF00.toInt(), out[1])
        assertEquals(0xFF7F7F7F.toInt(), out[2])
    }

    @Test
    fun `EXIF orientations rotate and mirror`() {
        val src = intArrayOf(1, 2, 3, 4, 5, 6) // 3 wide, 2 high
        assertEquals(Triple(listOf(4, 1, 5, 2, 6, 3), 2, 3), orient(src, 3, 2, 6).let { Triple(it.first.toList(), it.second, it.third) })
        assertEquals(listOf(6, 5, 4, 3, 2, 1), orient(src, 3, 2, 3).first.toList())
        assertEquals(listOf(3, 6, 2, 5, 1, 4), orient(src, 3, 2, 8).first.toList())
        assertEquals(listOf(3, 2, 1, 6, 5, 4), orient(src, 3, 2, 2).first.toList())
    }

    @Test
    fun `EXIF orientation is read from a JPEG header`() {
        // SOI, APP1 "Exif\0\0" with a big-endian TIFF header and one IFD0 entry: 0x0112 SHORT 1 = 6.
        val tiff = byteArrayOf(0x4D, 0x4D, 0x00, 0x2A, 0, 0, 0, 8, 0, 1,
            0x01, 0x12, 0x00, 0x03, 0, 0, 0, 1, 0x00, 0x06, 0, 0, 0, 0, 0, 0, 0, 0)
        val app1Len = 2 + 6 + tiff.size
        val jpeg = byteArrayOf(-1, -40, -1, -31, (app1Len shr 8).toByte(), app1Len.toByte()) +
            "Exif".toByteArray() + byteArrayOf(0, 0) + tiff + byteArrayOf(-1, -39)
        assertEquals(6, ExifOrientation.of(jpeg))
        assertEquals(1, ExifOrientation.of(byteArrayOf(-1, -40, -1, -39)))
        assertEquals(1, ExifOrientation.of(byteArrayOf(1, 2, 3)))
    }

    @Test
    fun `PNG decodes, subsampled when large`() {
        val px = assertNotNull(ImageDecoder.decode(png(40, 20), maxLongSide = 1000, deadlineMs = 5000))
        assertEquals(40 to 20, px.width to px.height)
        assertEquals(0xFFFF0000.toInt(), px.argb[0])
        assertEquals(0xFF0000FF.toInt(), px.argb[39])
        val sub = assertNotNull(ImageDecoder.decode(png(400, 200), maxLongSide = 100, deadlineMs = 5000))
        assertTrue(sub.width in 100 until 200, "subsampled to ${sub.width}")
        assertNull(ImageDecoder.decode("not an image".toByteArray(), 100, 5000))
    }

    @Test
    fun `a picture is encoded for the watch at exactly the size it asks for`() {
        val enc = WatchImage.encode(ImageSource.Pixels(solid(30, 10, 0xFF00FF00.toInt()), 30, 10), 166, 166)
        assertEquals(166 to 166, enc.width to enc.height)
        assertEquals(83 * 166, enc.pixels.size)
        assertTrue(enc.palette.size in 1..16)
    }

    @Test
    fun `the notification store keeps a master and serves any size`() = runBlocking {
        val dir = Files.createTempDirectory("imgstore").toFile()
        try {
            val store = NotificationImageStore(dir)
            val id = Uuid.random()
            val aspect = store.put(id, ImageSource.Bytes(png(600, 300)))
            assertEquals(8.toUByte(), aspect) // height/width = 1/2 → 8 sixteenths
            assertEquals(listOf("$id.png"), dir.list()!!.toList())
            val enc = assertNotNull(store.image(id, 180, 90))
            assertEquals(180 to 90, enc.width to enc.height)
            store.remove(id)
            assertNull(store.image(id, 180, 90))
            assertNull(store.put(Uuid.random(), ImageSource.Bytes("nope".toByteArray())))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `album art comes from the player's local art, decoded once`() = runBlocking {
        val art = AlbumArt(this)
        val url = "data:image/png;base64," + Base64.getEncoder().encodeToString(png(64, 64))
        // The first ask may come before the decode finishes; then it's ready.
        val first = art.get(url, 166, 166) ?: run { kotlinx.coroutines.delay(1500); art.get(url, 166, 166) }
        assertEquals(166 to 166, assertNotNull(first).width to first.height)
        assertNull(art.get("https://example.com/cover.jpg", 166, 166))
    }
}
