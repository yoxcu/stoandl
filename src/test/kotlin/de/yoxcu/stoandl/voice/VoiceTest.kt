@file:OptIn(kotlin.ExperimentalUnsignedTypes::class)

package de.yoxcu.stoandl.voice

import de.yoxcu.stoandl.config.ConfigStore
import de.yoxcu.stoandl.config.StoandlConfig
import de.yoxcu.stoandl.secret.FileSecretStore
import io.rebble.libpebblecommon.voice.TranscriptionResult
import io.rebble.libpebblecommon.voice.VoiceEncoderInfo
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_BYTE
import java.lang.foreign.ValueLayout.JAVA_INT
import java.lang.foreign.ValueLayout.JAVA_SHORT
import java.nio.file.Files
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Dictation without a watch: Speex frames as the firmware sends them, WAV, the backends, the result. */
class VoiceTest {
    /** [seconds] of a voiced tone encoded like the firmware (WB, q6, complexity 1, 9800 bps), each frame
     *  prefixed with its length byte as the audio endpoint sends it. */
    private fun firmwareFrames(seconds: Int): List<ByteArray> {
        val lib = SymbolLookup.libraryLookup("libspeex.so.1", Arena.global())
        val l = Linker.nativeLinker()
        fun h(n: String, d: FunctionDescriptor) = l.downcallHandle(lib.find(n).orElseThrow(), d)
        val getMode = h("speex_lib_get_mode", FunctionDescriptor.of(ADDRESS, JAVA_INT))
        val encInit = h("speex_encoder_init", FunctionDescriptor.of(ADDRESS, ADDRESS))
        val encCtl = h("speex_encoder_ctl", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS))
        val bitsInit = h("speex_bits_init", FunctionDescriptor.ofVoid(ADDRESS))
        val bitsReset = h("speex_bits_reset", FunctionDescriptor.ofVoid(ADDRESS))
        val bitsWrite = h("speex_bits_write", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT))
        val encode = h("speex_encode_int", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS))
        return Arena.ofConfined().use { a ->
            val enc = encInit.invoke(getMode.invoke(1) as MemorySegment) as MemorySegment
            val ip = a.allocate(JAVA_INT)
            for ((ctl, v) in listOf(4 to 6, 16 to 1, 24 to 16000, 18 to 9800)) { ip.set(JAVA_INT, 0, v); encCtl.invoke(enc, ctl, ip) }
            val bits = a.allocate(64); bitsInit.invoke(bits)
            val pcm = a.allocate(JAVA_SHORT, 320); val out = a.allocate(200)
            (0 until seconds * 50).map { f ->
                for (i in 0 until 320) pcm.setAtIndex(JAVA_SHORT, i.toLong(), (3000 * sin(2 * Math.PI * 220 * (f * 320 + i) / 16000.0)).toInt().toShort())
                bitsReset.invoke(bits)
                encode.invoke(enc, pcm, bits)
                val n = bitsWrite.invoke(bits, out, 200) as Int
                ByteArray(n + 1).also { it[0] = n.toByte(); MemorySegment.copy(out, JAVA_BYTE, 0, it, 1, n) }
            }
        }
    }

    private fun config(backend: StoandlConfig.VoiceBackend, command: String = "") = ConfigStore(
        StoandlConfig.load(File("/nonexistent/stoandl.conf"), logResult = false)
            .copy(voiceBackend = backend, voiceCommand = command, voiceLanguage = "de"),
    )

    private fun secrets() = FileSecretStore(Files.createTempFile("secrets", "").toFile())
    private fun models() = VoiceModels(kotlinx.coroutines.GlobalScope, Files.createTempDirectory("stt").toFile())

    @Test
    fun `firmware Speex frames decode to 20 ms of 16 kHz audio each`() {
        assumeTrue(SpeexDecoder.available, "libspeex not installed")
        val frames = firmwareFrames(1)
        assertNotNull(SpeexDecoder.open()).use { s ->
            assertEquals(320, s.frameSize)
            val pcm = frames.map { assertNotNull(s.decodeFrame(it)) }
            assertEquals(50 * 320, pcm.sumOf { it.size })
            assertTrue(pcm.drop(10).any { f -> f.any { kotlin.math.abs(it.toInt()) > 500 } }, "decoded silence")
            // A frame whose length byte disagrees with its size is skipped, not decoded as garbage.
            assertEquals(null, s.decodeFrame(byteArrayOf(5, 1, 2)))
        }
    }

    @Test
    fun `a Speex session decodes on whichever thread the frames arrive`() {
        assumeTrue(SpeexDecoder.available, "libspeex not installed")
        val frames = firmwareFrames(1)
        // The watch's frames reach the decoder in a coroutine that hops between worker threads.
        val s = assertNotNull(SpeexDecoder.open())
        var decoded = 0
        for (f in frames) Thread.ofPlatform().start { if (s.decodeFrame(f) != null) decoded++ }.join()
        Thread.ofPlatform().start { s.close() }.join()
        assertEquals(frames.size, decoded)
    }

    @Test
    fun `WAV round-trips and resamples`() {
        val pcm = ShortArray(800) { (it * 7).toShort() }
        val (back, rate) = assertNotNull(Wav.decode(Wav.encode(pcm, 8000)))
        assertEquals(8000, rate)
        assertContentEquals(pcm, back)
        assertEquals(1600, Wav.resample(pcm, 8000, 16000).size)
    }

    @Test
    fun `transcripts are normalised and kept inside the watch's message`() {
        assertEquals("hallo Welt", normalizeTranscript(" [BLANK_AUDIO]\n hallo\n  Welt \n"))
        assertEquals("", normalizeTranscript("Untertitel der Amara.org-Community"))
        assertEquals("Danke, bis gleich", normalizeTranscript("Danke, bis gleich"))
        val words = transcriptWords(List(1000) { "wort" }.joinToString(" "))
        assertTrue(words.size in 200..220, "${words.size} words")
        assertEquals("de", safeLanguage("DE")); assertEquals("auto", safeLanguage("de; rm -rf"))
    }

    @Test
    fun `a local command gets the WAV and the language and its stdout is the text`() = runBlocking {
        val stt = CommandStt("""sh -c 'test -s "$1" && echo "Hallo $2"' x {wav} {lang}""")
        assertEquals(null, stt.readiness())
        val r = stt.transcribe(SttAudio.Pcm16(ShortArray(16000), 16000), "de", 5000)
        assertEquals(SttOutcome.Text("Hallo de"), r)
        assertTrue(CacheVoiceDirEmpty(), "temporary WAV left behind")
    }

    @Test
    fun `command failures and the exit-0-with-error quirk are failures`() = runBlocking {
        assertIs<SttOutcome.Failed>(CommandStt("sh -c 'exit 3'").transcribe(SttAudio.Pcm16(ShortArray(10), 16000), "de", 5000))
        assertIs<SttOutcome.Failed>(CommandStt("sh -c 'echo error: no model >&2'").transcribe(SttAudio.Pcm16(ShortArray(10), 16000), "de", 5000))
        assertIs<SttOutcome.Failed>(CommandStt("sleep 5").transcribe(SttAudio.Pcm16(ShortArray(10), 16000), "de", 500))
        assertEquals("'definitely-not-installed' not found", CommandStt("definitely-not-installed -f {wav}").readiness())
    }

    @Test
    fun `the watch gets the transcript of its own audio`() = runBlocking {
        assumeTrue(SpeexDecoder.available, "libspeex not installed")
        val frames = firmwareFrames(1)
        val cfg = config(StoandlConfig.VoiceBackend.COMMAND, """sh -c 'test -s "$1" && echo Bin gleich da' x {wav}""")
        val provider = StoandlTranscriptionProvider(VoiceBackends(cfg, secrets(), models()), cfg)
        assertTrue(provider.canServeSession())
        val info = VoiceEncoderInfo.Speex(16000, "1.2.1", 9800, 4, 320)
        val r = provider.transcribe(info, flow { frames.forEach { emit(it.toUByteArray()) } }, isNotificationReply = true)
        assertEquals(listOf("Bin", "gleich", "da"), assertIs<TranscriptionResult.Success>(r).words.map { it.word })
    }

    @Test
    fun `voice off, or no audio from the watch`() = runBlocking<Unit> {
        val off = config(StoandlConfig.VoiceBackend.OFF)
        val provider = StoandlTranscriptionProvider(VoiceBackends(off, secrets(), models()), off)
        assertEquals(false, provider.canServeSession())
        assertEquals("off:", VoiceBackends(off, secrets(), models()).status())
        assumeTrue(SpeexDecoder.available, "libspeex not installed")
        val on = config(StoandlConfig.VoiceBackend.COMMAND, "echo hi")
        val silent = StoandlTranscriptionProvider(VoiceBackends(on, secrets(), models()), on)
        val r = silent.transcribe(VoiceEncoderInfo.Speex(16000, "1.2.1", 9800, 4, 320), flow { kotlinx.coroutines.awaitCancellation() }, true)
        assertIs<TranscriptionResult.Error>(r)
    }

    @Test
    fun `Ogg pages are split into packets across segment boundaries`() {
        fun page(serial: Int, vararg packets: ByteArray): ByteArray {
            val lacing = packets.flatMap { p -> List(p.size / 255) { 255 } + (p.size % 255) }
            val header = ByteArray(27).also {
                "OggS".toByteArray().copyInto(it)
                it[14] = serial.toByte(); it[26] = lacing.size.toByte()
            }
            return header + lacing.map { it.toByte() }.toByteArray() + packets.reduce { a, b -> a + b }
        }
        val big = ByteArray(300) { 1 }
        val ogg = page(7, "OpusHead".toByteArray() + ByteArray(11)) + page(7, "OpusTags".toByteArray(), big) + page(9, ByteArray(3))
        val pkts = OggOpus.packets(ogg)
        assertEquals(3, pkts.size)
        assertEquals(300, pkts[2].size)
    }

    private fun CacheVoiceDirEmpty(): Boolean =
        de.yoxcu.stoandl.util.CacheDirs.dir("voice").listFiles { f -> f.name.endsWith(".wav") && System.currentTimeMillis() - f.lastModified() < 10_000 }.isNullOrEmpty()

    @Test
    fun `a local model runs through its whisper-cpp program, pinned to the big cores`() {
        val dir = Files.createTempDirectory("stt").toFile()
        val models = VoiceModels(kotlinx.coroutines.GlobalScope, dir)
        val parakeet = assertNotNull(models.model("parakeet-v3"))
        assertTrue(models.command(parakeet).contains("parakeet-cli -m '${dir.absolutePath}/ggml-parakeet-tdt-0.6b-v3-q8_0.bin'"))
        assertTrue(models.command(assertNotNull(models.model("whisper-small"))).contains("-l {lang}"))
        assertEquals(false, models.isInstalled(parakeet))
        File(dir, parakeet.file).writeText("x")
        assertEquals(listOf("parakeet-v3"), models.installed().map { it.id })
        assertTrue(models.records().first().split('\t')[4] == "installed")
        assertEquals("'it'\\''s'", VoiceModels.shellQuote("it's"))
    }

    @Test
    fun `big cores are the fastest ones when the CPU mixes core types`() {
        val sys = Files.createTempDirectory("cpu").toFile()
        listOf(1766400L, 1766400L, 2803200L, 2803200L).forEachIndexed { i, f ->
            File(sys, "cpu$i/cpufreq").apply { mkdirs() }.resolve("cpuinfo_max_freq").writeText("$f\n")
        }
        assertEquals("2,3", VoiceModels.bigCores(sys))
        listOf(0, 1).forEach { File(sys, "cpu$it/cpufreq/cpuinfo_max_freq").writeText("2803200\n") }
        assertNull(VoiceModels.bigCores(sys), "all cores alike: no pinning")
    }
}
