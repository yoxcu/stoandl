package de.yoxcu.stoandl.voice

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** 16-bit PCM WAV, the form speech engines (whisper.cpp, Piper, espeak-ng) and OpenAI-style servers take and give. */
object Wav {
    fun encode(samples: ShortArray, sampleRate: Int, channels: Int = 1): ByteArray {
        val data = samples.size * 2
        val b = ByteBuffer.allocate(44 + data).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()).putInt(36 + data).put("WAVE".toByteArray())
        b.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(channels.toShort())
            .putInt(sampleRate).putInt(sampleRate * channels * 2).putShort((channels * 2).toShort()).putShort(16)
        b.put("data".toByteArray()).putInt(data)
        samples.forEach { b.putShort(it) }
        return b.array()
    }

    /**
     * Mono samples and the rate of a 16-bit PCM WAV (stereo is averaged), or null for anything else. A `data`
     * chunk that claims more than the file holds is read to the end: an engine writing to a pipe can't seek
     * back to fill in the sizes, and leaves 0, -1 or espeak-ng's 0x7ffff000 there.
     */
    fun decode(bytes: ByteArray): Pair<ShortArray, Int>? {
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        if (bytes.size < 12 || String(bytes, 0, 4) != "RIFF" || String(bytes, 8, 4) != "WAVE") return null
        var pos = 12
        var channels = 0; var rate = 0; var bits = 0; var format = 0
        while (pos + 8 <= bytes.size) {
            val id = String(bytes, pos, 4)
            val declared = b.getInt(pos + 4)
            val body = pos + 8
            val available = bytes.size - body
            if (id == "data") {
                // WAVE_FORMAT_EXTENSIBLE (0xFFFE) at 16 bits is PCM too (sox writes it).
                if ((format != 1 && format != 0xFFFE) || bits != 16 || channels !in 1..2 || rate <= 0) return null
                val len = if (declared <= 0 || declared > available) available else declared
                val frames = len / (2 * channels)
                val out = ShortArray(frames) { i ->
                    var sum = 0
                    for (c in 0 until channels) sum += b.getShort(body + (i * channels + c) * 2)
                    (sum / channels).toShort()
                }
                return out to rate
            }
            if (declared < 0 || declared > available) return null
            if (id == "fmt " && declared >= 16) {
                format = b.getShort(body).toInt() and 0xFFFF; channels = b.getShort(body + 2).toInt()
                rate = b.getInt(body + 4); bits = b.getShort(body + 14).toInt()
            }
            pos = body + declared + (declared and 1)
        }
        return null
    }

    /** [samples] at [from] Hz resampled to [to] Hz (linear; fine for speech recognition). */
    fun resample(samples: ShortArray, from: Int, to: Int): ShortArray {
        if (from == to || samples.isEmpty()) return samples
        val n = (samples.size.toLong() * to / from).toInt()
        return ShortArray(n) { i ->
            val x = i.toDouble() * from / to
            val i0 = x.toInt().coerceAtMost(samples.size - 1)
            val i1 = (i0 + 1).coerceAtMost(samples.size - 1)
            val t = x - i0
            (samples[i0] * (1 - t) + samples[i1] * t).toInt().toShort()
        }
    }
}
