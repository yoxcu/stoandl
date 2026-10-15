package de.yoxcu.stoandl.voice

import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.ByteArrayOutputStream
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_BYTE
import java.lang.foreign.ValueLayout.JAVA_INT
import java.lang.foreign.ValueLayout.JAVA_SHORT

private val log = KotlinLogging.logger {}

/** Opus decodes at 48 kHz; mono is enough for speech. */
private const val OPUS_RATE = 48_000
/** Largest Opus frame: 120 ms at 48 kHz. */
private const val MAX_FRAME_SAMPLES = 5760

/**
 * Ogg/Opus (Matrix and WhatsApp voice notes) to mono 48 kHz PCM, for local engines that only read WAV: an
 * Ogg page demuxer here, the Opus decoding through libopus (`libopus.so.0`, Alpine `opus`) over FFM.
 * HTTP backends get the `.ogg` as it is and never need this.
 */
object OggOpus {
    private class Lib(private val lookup: SymbolLookup) {
        private val linker = Linker.nativeLinker()
        private fun h(name: String, d: FunctionDescriptor) =
            linker.downcallHandle(lookup.find(name).orElseThrow { NoSuchElementException(name) }, d)
        val create = h("opus_decoder_create", FunctionDescriptor.of(ADDRESS, JAVA_INT, JAVA_INT, ADDRESS))
        val decode = h("opus_decode", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT))
        val destroy = h("opus_decoder_destroy", FunctionDescriptor.ofVoid(ADDRESS))
    }

    private val lib: Lib? by lazy {
        try {
            Lib(SymbolLookup.libraryLookup("libopus.so.0", Arena.global()))
        } catch (e: Throwable) {
            log.info { "libopus not found (${e.message}); voice notes can't go to a local engine (install opus)" }
            null
        }
    }

    val available: Boolean get() = lib != null

    /** The Ogg packets of the first logical stream, in order. */
    internal fun packets(ogg: ByteArray): List<ByteArray> {
        val out = ArrayList<ByteArray>()
        val partial = ByteArrayOutputStream()
        var pos = 0
        var serial: Int? = null
        while (pos + 27 <= ogg.size) {
            if (String(ogg, pos, 4, Charsets.ISO_8859_1) != "OggS") return out
            val pageSerial = (ogg[pos + 14].toInt() and 0xFF) or ((ogg[pos + 15].toInt() and 0xFF) shl 8) or
                ((ogg[pos + 16].toInt() and 0xFF) shl 16) or ((ogg[pos + 17].toInt() and 0xFF) shl 24)
            val segments = ogg[pos + 26].toInt() and 0xFF
            var data = pos + 27 + segments
            if (data > ogg.size) return out
            val mine = (serial ?: pageSerial.also { serial = it }) == pageSerial
            for (s in 0 until segments) {
                val len = ogg[pos + 27 + s].toInt() and 0xFF
                if (data + len > ogg.size) return out
                if (mine) {
                    partial.write(ogg, data, len)
                    if (len < 255) { out += partial.toByteArray(); partial.reset() }
                }
                data += len
            }
            pos = data
        }
        return out
    }

    /** Mono PCM and its rate, or null when this isn't Ogg/Opus or libopus is missing. */
    fun decode(ogg: ByteArray): Pair<ShortArray, Int>? {
        val l = lib ?: return null
        val pkts = packets(ogg)
        val head = pkts.firstOrNull() ?: return null
        if (head.size < 19 || String(head, 0, 8, Charsets.ISO_8859_1) != "OpusHead") return null
        val preSkip = (head[10].toInt() and 0xFF) or ((head[11].toInt() and 0xFF) shl 8)
        return Arena.ofConfined().use { arena ->
            val err = arena.allocate(JAVA_INT)
            val dec = l.create.invoke(OPUS_RATE, 1, err) as MemorySegment
            if (err.get(JAVA_INT, 0) != 0) return null
            try {
                val pcm = arena.allocate(JAVA_SHORT, MAX_FRAME_SAMPLES.toLong())
                val input = arena.allocate(4096)
                var all = ShortArray(OPUS_RATE * 10)
                var count = 0
                // Packet 0 is OpusHead, packet 1 OpusTags.
                for (p in pkts.drop(2)) {
                    if (p.isEmpty() || p.size > 4096) continue
                    MemorySegment.copy(p, 0, input, JAVA_BYTE, 0, p.size)
                    val n = l.decode.invoke(dec, input, p.size, pcm, MAX_FRAME_SAMPLES, 0) as Int
                    if (n <= 0) continue
                    if (count + n > all.size) all = all.copyOf(maxOf(all.size * 2, count + n))
                    for (i in 0 until n) all[count + i] = pcm.getAtIndex(JAVA_SHORT, i.toLong())
                    count += n
                }
                all.copyOfRange(minOf(preSkip, count), count) to OPUS_RATE
            } finally {
                l.destroy.invoke(dec)
            }
        }
    }
}
