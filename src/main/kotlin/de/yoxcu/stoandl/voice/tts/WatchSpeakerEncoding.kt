package de.yoxcu.stoandl.voice.tts

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Speech for the watch speaker (docs/speaker-stream.md): an engine's WAV low-passed and resampled to 8 kHz,
 * normalised, and IMA ADPCM-encoded into self-contained blocks, one per AppMessage. Pure; the watch side is
 * `stoandl_speaker.c`.
 */
object WatchSpeakerEncoding {
    const val RATE = 8_000
    const val BLOCK_BYTES = 2_000
    const val HEADER_BYTES = 4
    /** A full block: the header's sample plus two per data byte (about 499 ms). */
    const val BLOCK_SAMPLES = 1 + 2 * (BLOCK_BYTES - HEADER_BYTES)

    /** Below the 4 kHz Nyquist of 8 kHz, so the filter's transition band ends before anything aliases. */
    private const val CUTOFF_HZ = 3_600.0
    /** Sinc zero crossings on each side of the kernel: a Blackman-windowed kernel this long attenuates 5 kHz by
     *  well over 40 dB. */
    private const val ZERO_CROSSINGS = 16
    /** Kernel table entries per input sample. */
    private const val TABLE_RES = 512
    /** The loudest sample after normalising, of full scale. */
    private const val PEAK = 0.95
    /** Quiet audio is raised at most this much, so a near-silent clip's noise stays quiet. */
    private const val MAX_GAIN = 16.0
    /** The compressor: above this fraction of the peak, levels rise at 1/[RATIO]. */
    private const val KNEE = 0.5
    private const val RATIO = 2.0

    /** [samples] at [rate] Hz as blocks for SPK_DATA, and the length of the audio in ms. */
    fun encode(samples: ShortArray, rate: Int): Pair<List<ByteArray>, Long> {
        val pcm = normalize(lowPassTo8k(samples, rate))
        return adpcmBlocks(pcm) to pcm.size * 1000L / RATE
    }

    /**
     * [samples] at [rate] Hz resampled to 8 kHz through a Blackman-windowed sinc low-pass at [CUTOFF_HZ] (a
     * linear resampler would fold everything between 4 kHz and the source's Nyquist back into the band).
     * Each output sample is normalised by its kernel's weights, so the gain at DC is exactly 1.
     */
    fun lowPassTo8k(samples: ShortArray, rate: Int): FloatArray {
        require(rate > 0) { "rate $rate" }
        if (samples.isEmpty()) return FloatArray(0)
        // Cutoff in cycles per input sample; kernel half-width in input samples.
        val fc = min(CUTOFF_HZ, 0.45 * min(rate, RATE)) / rate
        val half = ZERO_CROSSINGS / (2 * fc)
        val table = FloatArray(ceil(half * TABLE_RES).toInt() + 2) { i ->
            val d = i.toDouble() / TABLE_RES
            if (d > half) 0f else {
                val x = 2 * fc * d
                val sinc = if (x == 0.0) 1.0 else sin(PI * x) / (PI * x)
                val u = d / half
                (2 * fc * sinc * (0.42 + 0.5 * cos(PI * u) + 0.08 * cos(2 * PI * u))).toFloat()
            }
        }
        val out = FloatArray((samples.size.toLong() * RATE / rate).toInt())
        for (n in out.indices) {
            val t = n.toDouble() * rate / RATE
            val lo = maxOf(0, ceil(t - half).toInt())
            val hi = minOf(samples.size - 1, floor(t + half).toInt())
            var acc = 0.0
            var weights = 0.0
            for (k in lo..hi) {
                val w = table[(abs(t - k) * TABLE_RES).roundToInt()]
                acc += w * samples[k]
                weights += w
            }
            out[n] = if (weights > 0) (acc / weights).toFloat() else 0f
        }
        return out
    }

    /** Peak-normalise to [PEAK] of full scale with a gentle 2:1 compressor above half the peak, which makes the
     *  small speaker louder; at most [MAX_GAIN] of raise. Silence stays silence. */
    fun normalize(x: FloatArray): ShortArray {
        val peak = x.maxOfOrNull { abs(it) }?.toDouble() ?: return ShortArray(0)
        if (peak < 1.0) return ShortArray(x.size)
        // The level that maps to the peak: the real one, unless that would raise it more than MAX_GAIN.
        val ref = maxOf(peak, 32767.0 / MAX_GAIN)
        val top = KNEE + (1 - KNEE) / RATIO
        return ShortArray(x.size) { i ->
            val u = abs(x[i]) / ref
            val c = if (u <= KNEE) u else KNEE + (u - KNEE) / RATIO
            val v = (if (x[i] < 0) -c else c) / top * PEAK * 32767
            v.roundToInt().coerceIn(-32768, 32767).toShort()
        }
    }

    internal val STEPS = intArrayOf(
        7, 8, 9, 10, 11, 12, 13, 14, 16, 17, 19, 21, 23, 25, 28, 31, 34, 37, 41, 45, 50, 55, 60, 66, 73, 80,
        88, 97, 107, 118, 130, 143, 157, 173, 190, 209, 230, 253, 279, 307, 337, 371, 408, 449, 494, 544, 598,
        658, 724, 796, 876, 963, 1060, 1166, 1282, 1411, 1552, 1707, 1878, 2066, 2272, 2499, 2749, 3024, 3327,
        3660, 4026, 4428, 4871, 5358, 5894, 6484, 7132, 7845, 8630, 9493, 10442, 11487, 12635, 13899, 15289,
        16818, 18500, 20350, 22385, 24623, 27086, 29794, 32767,
    )
    internal val INDEX_ADJUST = intArrayOf(-1, -1, -1, -1, 2, 4, 6, 8)

    /**
     * 8 kHz [pcm] as IMA ADPCM blocks: s16 LE first sample (the decoder's predictor), u8 step index, u8 0, then
     * two 4-bit codes per byte, low nibble first. A block of n bytes decodes to 1 + 2·(n − 4) samples, so a
     * block's odd number of codes is padded with one more of its last sample, and a last block of one sample
     * with two (a header-only block carries no audio, and senders refuse it). The step index starts where the
     * first samples need it (rather than at the smallest step, which would smear a loud start) and carries over
     * from block to block; each block still decodes on its own.
     */
    fun adpcmBlocks(pcm: ShortArray): List<ByteArray> {
        val blocks = ArrayList<ByteArray>()
        // The first step that covers half the largest of the first eight sample-to-sample changes.
        val firstChange = (1 until minOf(pcm.size, 9)).maxOfOrNull { abs(pcm[it] - pcm[it - 1]) } ?: 0
        var index = STEPS.indexOfFirst { it * 2 >= firstChange }.let { if (it < 0) 88 else it }
        var pos = 0
        while (pos < pcm.size) {
            val n = minOf(BLOCK_SAMPLES, pcm.size - pos)
            val codes = maxOf(2, (n - 1) + ((n - 1) and 1))
            val block = ByteArray(HEADER_BYTES + codes / 2)
            val first = pcm[pos].toInt()
            block[0] = first.toByte(); block[1] = (first shr 8).toByte(); block[2] = index.toByte(); block[3] = 0
            var pred = first
            for (i in 0 until codes) {
                val sample = pcm[pos + minOf(1 + i, n - 1)].toInt()
                // Standard IMA: the code's delta is exactly what the decoder will add.
                var step = STEPS[index]
                var diff = sample - pred
                var code = 0
                if (diff < 0) { code = 8; diff = -diff }
                var delta = step shr 3
                if (diff >= step) { code = code or 4; diff -= step; delta += step }
                step = step shr 1
                if (diff >= step) { code = code or 2; diff -= step; delta += step }
                step = step shr 1
                if (diff >= step) { code = code or 1; delta += step }
                pred = (if (code and 8 != 0) pred - delta else pred + delta).coerceIn(-32768, 32767)
                index = (index + INDEX_ADJUST[code and 7]).coerceIn(0, 88)
                val at = HEADER_BYTES + i / 2
                block[at] = if (i % 2 == 0) code.toByte() else (block[at].toInt() or (code shl 4)).toByte()
            }
            blocks += block
            pos += n
        }
        return blocks
    }
}
