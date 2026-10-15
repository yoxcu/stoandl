package de.yoxcu.stoandl.voice

import io.github.oshai.kotlinlogging.KotlinLogging
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_BYTE
import java.lang.foreign.ValueLayout.JAVA_INT
import java.lang.foreign.ValueLayout.JAVA_SHORT
import java.lang.invoke.MethodHandle

private val log = KotlinLogging.logger {}

/** Speex mode id for wideband (16 kHz), the mode the watch's encoder uses. */
private const val SPEEX_MODEID_WB = 1
private const val SPEEX_SET_ENH = 0
private const val SPEEX_GET_FRAME_SIZE = 3

/** Larger than `SpeexBits` on any ABI (48 bytes on LP64). */
private const val SPEEX_BITS_BYTES = 64L

/** Largest encoded frame the firmware sends (`voice.c`). */
const val MAX_SPEEX_FRAME = 200

/**
 * The watch's dictation audio decoded through libspeex (`libspeex.so.1`, Alpine `speex`, Debian
 * `libspeex1`) over FFM. The firmware encodes wideband Speex, 16 kHz, 320-sample (20 ms) frames; on PRF
 * builds with two microphones it adds in-band stereo information, which this mono decoder skips.
 *
 * Each frame arrives as `[length][speex bytes]` (the audio endpoint's per-frame length byte).
 */
object SpeexDecoder {
    internal class Lib(private val lookup: SymbolLookup) {
        private val linker = Linker.nativeLinker()
        private fun h(name: String, d: FunctionDescriptor): MethodHandle =
            linker.downcallHandle(lookup.find(name).orElseThrow { NoSuchElementException(name) }, d)

        val getMode = h("speex_lib_get_mode", FunctionDescriptor.of(ADDRESS, JAVA_INT))
        val decInit = h("speex_decoder_init", FunctionDescriptor.of(ADDRESS, ADDRESS))
        val decCtl = h("speex_decoder_ctl", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS))
        val decDestroy = h("speex_decoder_destroy", FunctionDescriptor.ofVoid(ADDRESS))
        val bitsInit = h("speex_bits_init", FunctionDescriptor.ofVoid(ADDRESS))
        val bitsDestroy = h("speex_bits_destroy", FunctionDescriptor.ofVoid(ADDRESS))
        val bitsRead = h("speex_bits_read_from", FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, JAVA_INT))
        val decodeInt = h("speex_decode_int", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS))
    }

    private val lib: Lib? by lazy {
        try {
            Lib(SymbolLookup.libraryLookup("libspeex.so.1", Arena.global()))
        } catch (e: Throwable) {
            log.info { "libspeex not found (${e.message}); voice dictation needs it (install speex / libspeex1)" }
            null
        }
    }

    /** Whether libspeex could be loaded. */
    val available: Boolean get() = lib != null

    /** One dictation's decoder state. Not thread-safe; [close] when done. */
    class Session internal constructor(private val lib: Lib) : AutoCloseable {
        private val arena = Arena.ofConfined()
        private val dec: MemorySegment = lib.decInit.invoke(lib.getMode.invoke(SPEEX_MODEID_WB) as MemorySegment) as MemorySegment
        private val bits: MemorySegment = arena.allocate(SPEEX_BITS_BYTES)
        private val input: MemorySegment = arena.allocate(MAX_SPEEX_FRAME.toLong())
        val frameSize: Int
        private val pcm: MemorySegment

        init {
            lib.bitsInit.invoke(bits)
            val ip = arena.allocate(JAVA_INT)
            ip.set(JAVA_INT, 0, 1)
            lib.decCtl.invoke(dec, SPEEX_SET_ENH, ip)
            lib.decCtl.invoke(dec, SPEEX_GET_FRAME_SIZE, ip)
            frameSize = ip.get(JAVA_INT, 0)
            pcm = arena.allocate(JAVA_SHORT, frameSize.toLong())
        }

        /** PCM samples for one `[length][speex]` frame, or null for a malformed frame (skipped). */
        fun decodeFrame(prefixed: ByteArray): ShortArray? {
            if (prefixed.isEmpty()) return null
            val len = prefixed[0].toInt() and 0xFF
            if (len == 0 || len != prefixed.size - 1 || len > MAX_SPEEX_FRAME) return null
            MemorySegment.copy(prefixed, 1, input, JAVA_BYTE, 0, len)
            lib.bitsRead.invoke(bits, input, len)
            val r = lib.decodeInt.invoke(dec, bits, pcm) as Int
            if (r != 0) return null
            return ShortArray(frameSize) { pcm.getAtIndex(JAVA_SHORT, it.toLong()) }
        }

        override fun close() {
            lib.bitsDestroy.invoke(bits)
            lib.decDestroy.invoke(dec)
            arena.close()
        }
    }

    /** A decoder for one dictation, or null when libspeex isn't there. */
    fun open(): Session? = lib?.let { Session(it) }
}
