package de.yoxcu.stoandl.voice

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** 16-bit PCM WAV, the form local speech engines (whisper.cpp) and OpenAI-style servers take. */
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

    /** Mono samples and the rate of a 16-bit PCM WAV (stereo is averaged), or null for anything else. */
    fun decode(bytes: ByteArray): Pair<ShortArray, Int>? {
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        if (bytes.size < 12 || String(bytes, 0, 4) != "RIFF" || String(bytes, 8, 4) != "WAVE") return null
        var pos = 12
        var channels = 0; var rate = 0; var bits = 0; var format = 0
        while (pos + 8 <= bytes.size) {
            val id = String(bytes, pos, 4)
            val len = b.getInt(pos + 4)
            val body = pos + 8
            if (len < 0 || body + len > bytes.size) return null
            when (id) {
                "fmt " -> { format = b.getShort(body).toInt(); channels = b.getShort(body + 2).toInt(); rate = b.getInt(body + 4); bits = b.getShort(body + 14).toInt() }
                "data" -> {
                    if (format != 1 || bits != 16 || channels !in 1..2 || rate <= 0) return null
                    val frames = len / (2 * channels)
                    val out = ShortArray(frames) { i ->
                        var sum = 0
                        for (c in 0 until channels) sum += b.getShort(body + (i * channels + c) * 2)
                        (sum / channels).toShort()
                    }
                    return out to rate
                }
            }
            pos = body + len + (len and 1)
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
