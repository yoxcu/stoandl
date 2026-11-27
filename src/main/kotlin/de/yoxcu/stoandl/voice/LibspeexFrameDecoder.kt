@file:OptIn(ExperimentalUnsignedTypes::class)

package de.yoxcu.stoandl.voice

import io.github.oshai.kotlinlogging.KotlinLogging
import io.rebble.libpebblecommon.voice.AudioFrameDecoder
import io.rebble.libpebblecommon.voice.AudioPcmDecoder
import io.rebble.libpebblecommon.voice.VoiceEncoderInfo

private val log = KotlinLogging.logger {}

/** The only Speex mode [SpeexDecoder] decodes: wideband, what the watch's encoder sends. */
private const val WIDEBAND_RATE = 16_000L

/**
 * libpebble3's [AudioFrameDecoder] (a watchapp's audio stream to its companion JS, `JvmHost.audioDecoder`) on
 * [SpeexDecoder], the libspeex the watch's dictation already decodes with: one Speex session per stream, its
 * `[length][speex]` frames to 16-bit little-endian mono PCM. Null for anything but wideband Speex, and while
 * libspeex isn't installed, which libpebble3 answers with "service unavailable".
 */
internal object LibspeexFrameDecoder : AudioFrameDecoder {
    override fun open(encoderInfo: VoiceEncoderInfo): AudioPcmDecoder? {
        if (encoderInfo !is VoiceEncoderInfo.Speex || encoderInfo.sampleRate != WIDEBAND_RATE) {
            log.info { "Watch audio stream: can't decode $encoderInfo (wideband Speex only)" }
            return null
        }
        val session = SpeexDecoder.open() ?: return null
        return object : AudioPcmDecoder {
            override fun decode(frame: UByteArray): ByteArray? = session.decodeFrame(frame.asByteArray())?.toLittleEndianBytes()
            override fun close() = session.close()
        }
    }
}

/** 16-bit samples as little-endian bytes, the PCM layout [AudioPcmDecoder] returns. */
internal fun ShortArray.toLittleEndianBytes(): ByteArray {
    val out = ByteArray(size * 2)
    for (i in indices) {
        val s = this[i].toInt()
        out[2 * i] = s.toByte()
        out[2 * i + 1] = (s shr 8).toByte()
    }
    return out
}
