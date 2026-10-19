package de.yoxcu.stoandl.voice.tts

import de.yoxcu.stoandl.voice.tts.WatchSpeakerEncoding.BLOCK_BYTES
import de.yoxcu.stoandl.voice.tts.WatchSpeakerEncoding.BLOCK_SAMPLES
import de.yoxcu.stoandl.voice.tts.WatchSpeakerEncoding.INDEX_ADJUST
import de.yoxcu.stoandl.voice.tts.WatchSpeakerEncoding.STEPS
import kotlin.math.PI
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The watch speaker's audio (docs/speaker-stream.md), checked with a decoder written from the spec. */
class WatchSpeakerEncodingTest {
    /** One block on its own, as stoandl_speaker.c decodes it. */
    private fun decode(block: ByteArray): ShortArray {
        var pred = ((block[0].toInt() and 0xFF) or (block[1].toInt() shl 8)).toShort().toInt()
        var index = (block[2].toInt() and 0xFF).coerceAtMost(88)
        val out = ShortArray(1 + 2 * (block.size - 4))
        out[0] = pred.toShort()
        for (i in 0 until 2 * (block.size - 4)) {
            val b = block[4 + i / 2].toInt() and 0xFF
            val code = if (i % 2 == 0) b and 0x0F else b shr 4
            val step = STEPS[index]
            var diff = step shr 3
            if (code and 1 != 0) diff += step shr 2
            if (code and 2 != 0) diff += step shr 1
            if (code and 4 != 0) diff += step
            pred = (if (code and 8 != 0) pred - diff else pred + diff).coerceIn(-32768, 32767)
            index = (index + INDEX_ADJUST[code and 7]).coerceIn(0, 88)
            out[i + 1] = pred.toShort()
        }
        return out
    }

    private fun snrDb(ref: ShortArray, got: ShortArray, from: Int = 0, to: Int = ref.size): Double {
        var s = 0.0; var e = 0.0
        for (i in from until to) { s += ref[i].toDouble() * ref[i]; e += (ref[i] - got[i]).toDouble() * (ref[i] - got[i]) }
        return 10 * log10(s / e.coerceAtLeast(1e-9))
    }

    /** Vowel-like: a 140 Hz voice's harmonics with formant weights, its loudness swelling and fading. */
    private fun speechLike(rate: Int, seconds: Double) = ShortArray((rate * seconds).toInt()) { i ->
        val t = i.toDouble() / rate
        val env = 0.2 + 0.8 * sin(PI * t / seconds).let { it * it }
        val v = listOf(1 to 1.0, 2 to 0.7, 4 to 0.5, 5 to 0.6, 9 to 0.25, 17 to 0.15).sumOf { (h, a) -> a * sin(2 * PI * 140 * h * t) }
        (6000 * env * v).toInt().coerceIn(-32768, 32767).toShort()
    }

    private fun rms(x: FloatArray, from: Int, to: Int) = sqrt((from until to).sumOf { x[it].toDouble() * x[it] } / (to - from))

    private fun tone(hz: Double, rate: Int, n: Int) = ShortArray(n) { (10000 * sin(2 * PI * hz * it / rate)).toInt().toShort() }

    @Test
    fun `the low-pass keeps speech and removes what would alias`() {
        for (rate in listOf(16000, 22050, 24000)) {
            val pass = WatchSpeakerEncoding.lowPassTo8k(tone(1000.0, rate, rate), rate)
            val stop = WatchSpeakerEncoding.lowPassTo8k(tone(5000.0, rate, rate), rate)
            assertEquals(8000, pass.size, "one second at 8 kHz from $rate Hz")
            // Away from the edges, where the kernel is cut short.
            val inPass = rms(pass, 200, 7800)
            val inStop = rms(stop, 200, 7800)
            assertTrue(20 * log10(inPass / (10000 / sqrt(2.0))) > -0.5, "1 kHz passes at $rate Hz: ${inPass}")
            assertTrue(20 * log10(inPass / inStop) >= 30, "5 kHz attenuated ≥ 30 dB at $rate Hz: ${20 * log10(inPass / inStop)} dB")
        }
        assertEquals(0, WatchSpeakerEncoding.lowPassTo8k(ShortArray(0), 22050).size)
    }

    @Test
    fun `normalising raises the peak, compresses lightly, keeps silence`() {
        val loud = WatchSpeakerEncoding.normalize(floatArrayOf(0f, 10000f, -20000f, 5000f))
        assertEquals(-31129, loud[2].toInt(), "the peak is 95 % of full scale")
        assertTrue(loud[1] > 31129 / 2, "levels above the knee rise less: half the peak ends above half")
        assertContentEquals(ShortArray(3), WatchSpeakerEncoding.normalize(floatArrayOf(0f, 0.4f, -0.2f)))
        val quiet = WatchSpeakerEncoding.normalize(floatArrayOf(10f, -10f))
        assertTrue(quiet[0] in 1..(10 * 16 * 2), "a near-silent clip is raised at most 16 times: ${quiet[0]}")
    }

    @Test
    fun `ADPCM blocks decode on their own with at least 20 dB SNR`() {
        val pcm = WatchSpeakerEncoding.normalize(WatchSpeakerEncoding.lowPassTo8k(speechLike(22050, 2.3), 22050))
        val blocks = WatchSpeakerEncoding.adpcmBlocks(pcm)
        assertTrue(blocks.size >= 4)
        blocks.dropLast(1).forEach { assertEquals(BLOCK_BYTES, it.size) }
        assertTrue(blocks.last().size in 5..BLOCK_BYTES)
        var pos = 0
        for (b in blocks) {
            assertTrue(b[2] in 0..88 && b[3].toInt() == 0, "header: step index and the reserved byte")
            val samples = decode(b)
            assertEquals(1 + 2 * (b.size - 4), samples.size)
            assertEquals(pcm[pos], samples[0], "the first sample is exact")
            val n = minOf(BLOCK_SAMPLES, pcm.size - pos)
            assertTrue(snrDb(pcm.copyOfRange(pos, pos + n), samples.copyOf(n)) >= 20, "block at $pos decodes alone")
            pos += n
        }
        assertEquals(pcm.size, pos)
        val all = blocks.flatMap { decode(it).toList() }.toShortArray().copyOf(pcm.size)
        assertTrue(snrDb(pcm, all) >= 20, "whole stream: ${snrDb(pcm, all)} dB")
    }

    @Test
    fun `odd and tiny sample counts are padded to whole bytes`() {
        val four = WatchSpeakerEncoding.adpcmBlocks(shortArrayOf(100, 200, 300, 400))
        assertEquals(listOf(6), four.map { it.size }, "3 codes padded to 4: 2 bytes")
        assertEquals(5, decode(four[0]).size)
        val one = WatchSpeakerEncoding.adpcmBlocks(shortArrayOf(-5))
        assertEquals(listOf(5), one.map { it.size }, "never a header-only block")
        assertEquals(-5, decode(one[0])[0].toInt())
        assertEquals(listOf(BLOCK_BYTES, 5), WatchSpeakerEncoding.adpcmBlocks(ShortArray(BLOCK_SAMPLES + 1) { 7 }).map { it.size })
        assertTrue(WatchSpeakerEncoding.adpcmBlocks(ShortArray(0)).isEmpty())
    }

    /**
     * A fixed vector, so the encoder can't drift from the watch decoder unnoticed: 16 samples of a 1 kHz sine
     * at 8 kHz. The block matches a separate Python IMA encoder, and the samples are what stoandl_speaker.c's
     * decode_nibble, compiled on the host, made of it (2026-10-19).
     */
    @Test
    fun `a known vector`() {
        val sine = ShortArray(16) { (8000 * sin(2 * PI * it / 8)).toInt().toShort() }
        val block = WatchSpeakerEncoding.adpcmBlocks(sine).single()
        assertEquals(KNOWN_BLOCK, block.joinToString("") { "%02x".format(it) })
        assertEquals(KNOWN_DECODED, decode(block).toList())
    }

    private companion object {
        const val KNOWN_BLOCK = "00003f0017c99b4114c98b81"
        val KNOWN_DECODED = listOf(0, 5670, 8101, 5892, -135, -5808, -8017, -6009, -530, 6100, 8774, 6343, -287, -6527,
            -7337, -5128, -5797).map { it.toShort() }
    }
}
