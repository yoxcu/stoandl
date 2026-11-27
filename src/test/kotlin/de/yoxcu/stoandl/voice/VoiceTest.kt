@file:OptIn(kotlin.ExperimentalUnsignedTypes::class)

package de.yoxcu.stoandl.voice

import de.yoxcu.stoandl.config.ConfigStore
import de.yoxcu.stoandl.config.StoandlConfig
import de.yoxcu.stoandl.secret.FileSecretStore
import io.rebble.libpebblecommon.voice.TranscriptionResult
import io.rebble.libpebblecommon.voice.VoiceEncoderInfo
import kotlinx.coroutines.delay
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
    fun `libpebble3's audio streams decode through libspeex to little-endian PCM`() {
        assumeTrue(SpeexDecoder.available, "libspeex not installed")
        val frames = firmwareFrames(1)
        val decoder = assertNotNull(LibspeexFrameDecoder.open(VoiceEncoderInfo.Speex(16000, "1.2.1", 9800, 4, 320)))
        val expected = assertNotNull(SpeexDecoder.open()).use { s -> frames.map { assertNotNull(s.decodeFrame(it)) } }
        try {
            frames.zip(expected).forEach { (frame, samples) ->
                assertContentEquals(samples.toLittleEndianBytes(), decoder.decode(frame.toUByteArray()))
            }
            assertNull(decoder.decode(ubyteArrayOf(5u, 1u, 2u)), "a malformed frame is skipped")
        } finally {
            decoder.close()
        }
    }

    @Test
    fun `only wideband Speex streams are decoded`() {
        assertNull(LibspeexFrameDecoder.open(VoiceEncoderInfo.Speex(8000, "1.2.1", 9800, 4, 160)))
    }

    @Test
    fun `PCM bytes are little-endian 16-bit`() {
        assertContentEquals(
            byteArrayOf(0x34, 0x12, 0xff.toByte(), 0xff.toByte(), 0x00, 0x80.toByte()),
            shortArrayOf(0x1234, -1, Short.MIN_VALUE).toLittleEndianBytes(),
        )
    }

    @Test
    fun `WAV round-trips and resamples`() {
        val pcm = ShortArray(800) { (it * 7).toShort() }
        val (back, rate) = assertNotNull(Wav.decode(Wav.encode(pcm, 8000)))
        assertEquals(8000, rate)
        assertContentEquals(pcm, back)
        assertEquals(1600, Wav.resample(pcm, 8000, 16000).size)
    }

    /** A WAV written to a pipe can't have its sizes filled in afterwards: espeak-ng leaves 0x7ffff000, others
     *  0 or -1. The samples there are read all the same, and a LIST chunk ahead of the data is skipped. */
    @Test
    fun `WAV with a streaming header or a LIST chunk decodes`() {
        val pcm = ShortArray(801) { (it * 13 - 4000).toShort() }
        val wav = Wav.encode(pcm, 22050)
        for (declared in listOf(0x7ffff000, 0, -1)) {
            val streamed = wav.copyOf().also { java.nio.ByteBuffer.wrap(it).order(java.nio.ByteOrder.LITTLE_ENDIAN).putInt(4, declared).putInt(40, declared) }
            val (back, rate) = assertNotNull(Wav.decode(streamed), "data size $declared")
            assertEquals(22050, rate)
            assertContentEquals(pcm, back)
        }
        // RIFF, fmt (as encode writes it), then an odd-sized LIST/INFO chunk with its pad byte, then data.
        val list = "LIST".toByteArray() + byteArrayOf(5, 0, 0, 0) + "INFOx".toByteArray() + byteArrayOf(0)
        val withList = wav.copyOfRange(0, 36) + list + wav.copyOfRange(36, wav.size)
        assertContentEquals(pcm, assertNotNull(Wav.decode(withList)).first)
        // A truncated fmt chunk is still not a WAV.
        assertNull(Wav.decode(wav.copyOfRange(0, 30)))
    }

    /** Alpine's piper_exe (libpiper) writes 32-bit IEEE float, with streaming sizes (field, op6 2026-10-20). */
    @Test
    fun `32-bit float WAV decodes to 16-bit samples`() {
        val floats = floatArrayOf(0f, 0.5f, -0.5f, 1f, -1f, 1.5f)
        val b = java.nio.ByteBuffer.allocate(44 + floats.size * 4).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()).putInt(0x7ffff024).put("WAVE".toByteArray())
        b.put("fmt ".toByteArray()).putInt(16).putShort(3).putShort(1).putInt(16000).putInt(64000).putShort(4).putShort(32)
        b.put("data".toByteArray()).putInt(0x7ffff000)
        floats.forEach { b.putFloat(it) }
        val (back, rate) = assertNotNull(Wav.decode(b.array()))
        assertEquals(16000, rate)
        assertContentEquals(shortArrayOf(0, 16383, -16383, 32767, -32767, 32767), back)
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
        val provider = StoandlTranscriptionProvider(VoiceBackends(cfg, secrets(), models()), cfg, this, 0, 0)
        assertTrue(provider.canServeSession())
        val info = VoiceEncoderInfo.Speex(16000, "1.2.1", 9800, 4, 320)
        val r = provider.transcribe(info, flow { frames.forEach { emit(it.toUByteArray()) } }, isNotificationReply = true)
        assertEquals(listOf("Bin", "gleich", "da"), assertIs<TranscriptionResult.Success>(r).words.map { it.word })
    }

    @Test
    fun `a dictation keeps the system awake until the watch answered its result`() = runBlocking {
        assumeTrue(SpeexDecoder.available, "libspeex not installed")
        val frames = firmwareFrames(1)
        val cfg = config(StoandlConfig.VoiceBackend.COMMAND, "echo ok")
        val held = mutableListOf<String>()
        lateinit var provider: StoandlTranscriptionProvider
        provider = StoandlTranscriptionProvider(VoiceBackends(cfg, secrets(), models()), cfg, this, afterResultMs = 200) { why ->
            held += "take"
            AutoCloseable { held += "release" }
        }
        val info = VoiceEncoderInfo.Speex(16000, "1.2.1", 9800, 4, 320)
        var pendingMidway: String? = null
        val audio = flow { frames.forEach { emit(it.toUByteArray()) }; pendingMidway = provider.pendingDescription() }
        assertIs<TranscriptionResult.Success>(provider.transcribe(info, audio, isNotificationReply = true))
        assertEquals(listOf("take"), held, "still awake for what the watch does with the transcript")
        assertEquals("dictation", pendingMidway)
        assertNull(provider.pendingDescription())
        delay(500)
        assertEquals(listOf("take", "release"), held)
    }

    @Test
    fun `a retry after an empty transcript keeps the same sleep block`() = runBlocking {
        assumeTrue(SpeexDecoder.available, "libspeex not installed")
        val frames = firmwareFrames(1)
        val cfg = config(StoandlConfig.VoiceBackend.COMMAND, "true")
        val held = mutableListOf<String>()
        val provider = StoandlTranscriptionProvider(VoiceBackends(cfg, secrets(), models()), cfg, this, afterResultMs = 50, afterRetryMs = 400) {
            held += "take"
            AutoCloseable { held += "release" }
        }
        val info = VoiceEncoderInfo.Speex(16000, "1.2.1", 9800, 4, 320)
        val audio = flow { frames.forEach { emit(it.toUByteArray()) } }
        assertEquals(0, assertIs<TranscriptionResult.Success>(provider.transcribe(info, audio, true)).words.size)
        delay(200) // past the transcript's grace: still held for the retry
        assertEquals(listOf("take"), held)
        provider.transcribe(info, audio, true)
        delay(250) // the first grace would have ended now; the retry's own one runs
        assertEquals(listOf("take"), held, "handed over, never released between the sessions")
        delay(400)
        assertEquals(listOf("take", "release"), held)
    }

    @Test
    fun `voice off, or no audio from the watch`() = runBlocking<Unit> {
        val off = config(StoandlConfig.VoiceBackend.OFF)
        val provider = StoandlTranscriptionProvider(VoiceBackends(off, secrets(), models()), off, this, 0, 0)
        assertEquals(false, provider.canServeSession())
        assertEquals("off:", VoiceBackends(off, secrets(), models()).status())
        assumeTrue(SpeexDecoder.available, "libspeex not installed")
        val on = config(StoandlConfig.VoiceBackend.COMMAND, "echo hi")
        val silent = StoandlTranscriptionProvider(VoiceBackends(on, secrets(), models()), on, this, 0, 0)
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
        val models = VoiceModels(kotlinx.coroutines.GlobalScope, dir) { null }
        val parakeet = assertNotNull(models.model("parakeet-v3"))
        assertTrue(models.command(parakeet).contains("parakeet-cli -m '${dir.absolutePath}/stt/ggml-parakeet-tdt-0.6b-v3-q8_0.bin'"))
        assertTrue(models.command(assertNotNull(models.model("whisper-small"))).contains("-l {lang}"))
        assertEquals(false, models.isInstalled(parakeet))
        File(dir, "stt/${parakeet.file}").apply { parentFile.mkdirs() }.writeText("x")
        assertEquals(listOf("parakeet-v3"), models.installed(ModelKind.STT).map { it.id })
        assertTrue(models.records().first().split('\t')[4] == "installed")
        assertEquals("'it'\\''s'", VoiceModels.shellQuote("it's"))
    }

    @Test
    fun `a command that names {txt} is read from that file, not its stdout`() = runBlocking {
        val stt = CommandStt("""sh -c 'echo "model: x"; printf "Hallo %s" "$2" > "$1"' x {txt} {lang}""")
        assertEquals(SttOutcome.Text("Hallo de"), stt.transcribe(SttAudio.Pcm16(ShortArray(160), 16000), "de", 5000))
        assertEquals(SttOutcome.Text(""), CommandStt("true {txt}").transcribe(SttAudio.Pcm16(ShortArray(160), 16000), "de", 5000))
        assertTrue(de.yoxcu.stoandl.util.CacheDirs.dir("voice").listFiles { f -> f.name.endsWith(".txt") }.isNullOrEmpty(),
            "transcript file left behind")
    }

    /** A stand-in `transcribe-cli` that checks its argv and writes [transcript] where `-o` says (stdout gets the
     *  metadata block the real one prints). */
    private fun fakeTranscribeCli(dir: File, transcript: String): File = File(dir, "transcribe-cli").apply {
        writeText("""#!/bin/sh
            |echo "audio: ${'$'}*"; echo "text: not this"
            |while [ ${'$'}# -gt 1 ]; do
            |  case "${'$'}1" in -l) lang=${'$'}2; shift ;; -o) out=${'$'}2; shift ;; esac; shift
            |done
            |test -s "${'$'}1" || exit 4
            |printf '%s' "${'$'}lang: $transcript" > "${'$'}out"
            |""".trimMargin())
        setExecutable(true)
    }

    @Test
    fun `Canary runs transcribe-cli with the language, on the big cores, its transcript in a file`() {
        val dir = Files.createTempDirectory("stt").toFile()
        try {
            val exe = File(dir, "bin/transcribe-cli")
            val models = VoiceModels(kotlinx.coroutines.GlobalScope, dir) { if (it == SpeechEngine.CANARY) exe else null }
            val canary = assertNotNull(models.model("canary-180m"))
            val threads = minOf(4, Runtime.getRuntime().availableProcessors()).coerceAtLeast(1)
            val cmd = models.command(canary)
            val expected = "'${exe.absolutePath}' -q -m '${dir.absolutePath}/stt/canary-180m-flash-Q8_0.gguf' -l {lang} --threads $threads --backend cpu -o {txt} {wav}"
            assertEquals((VoiceModels.bigCoresPrefix() + expected).joinToString(" "), cmd)
            // Without the program the command names it, for sh's PATH lookup.
            val bare = VoiceModels(kotlinx.coroutines.GlobalScope, dir) { null }.command(canary)
            assertTrue(bare.contains(" transcribe-cli -q -m ") || bare.startsWith("transcribe-cli -q -m "), bare)
        } finally {
            dir.deleteRecursively()
        }
    }

    /** Canary has no detection, and told the wrong language it translates: it runs only with one of its own. */
    @Test
    fun `Canary is ready only with a language it knows`() = runBlocking {
        val dir = Files.createTempDirectory("stt").toFile()
        try {
            val exe = fakeTranscribeCli(File(dir, "bin").apply { mkdirs() }, "Für was steht U S B ? ?")
            var installed = false
            val models = VoiceModels(kotlinx.coroutines.GlobalScope, dir) { if (installed) exe else null }
            val canary = assertNotNull(models.model("canary-180m"))
            assertEquals("Canary 180M flash needs voice.language (en, de, es or fr)", languageProblem(canary, "auto"))
            assertEquals("Canary 180M flash needs voice.language (en, de, es or fr), not it", languageProblem(canary, "it"))
            assertNull(languageProblem(canary, "de"))
            assertNull(languageProblem(assertNotNull(models.model("parakeet-v3")), "auto"), "Parakeet takes any")

            assertEquals("Canary 180M flash isn't downloaded yet", LocalModelStt(models, "canary-180m", "de").readiness())
            File(dir, "stt/${canary.file}").apply { parentFile.mkdirs() }.writeText("x")
            assertEquals("transcribe-cli not found (install transcribe-cpp, see docs/voice.md)",
                LocalModelStt(models, "canary-180m", "de").readiness())
            installed = true
            assertEquals(languageProblem(canary, "auto"), LocalModelStt(models, "canary-180m", "auto").readiness())
            assertEquals(languageProblem(canary, "it"), LocalModelStt(models, "canary-180m", "it").readiness())
            assertNull(LocalModelStt(models, "canary-180m", "en").readiness())
            assertNull(LocalModelStt(models, "canary-180m", "de").readiness())

            // The transcript comes from -o, tidied; given a language it doesn't know it runs nothing.
            val stt = LocalModelStt(models, "canary-180m", "de")
            assertEquals(SttOutcome.Text("de: Für was steht U S B?"), stt.transcribe(SttAudio.Pcm16(ShortArray(1600), 16000), "de", 5000))
            assertEquals(SttOutcome.Failed("Canary 180M flash needs voice.language (en, de, es or fr)"),
                stt.transcribe(SttAudio.Pcm16(ShortArray(1600), 16000), "auto", 5000))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `the voice status names Canary's language, or why it can't run`() {
        val dir = Files.createTempDirectory("stt").toFile()
        try {
            val exe = fakeTranscribeCli(File(dir, "bin").apply { mkdirs() }, "x")
            val models = VoiceModels(kotlinx.coroutines.GlobalScope, dir) { exe }
            File(dir, "stt/canary-180m-flash-Q8_0.gguf").apply { parentFile.mkdirs() }.writeText("x")
            fun status(lang: String) = VoiceBackends(ConfigStore(
                StoandlConfig.load(File("/nonexistent/stoandl.conf"), logResult = false)
                    .copy(voiceBackend = StoandlConfig.VoiceBackend.LOCAL, voiceLocalModel = "canary-180m", voiceLanguage = lang),
            ), secrets(), models).status()
            assertEquals("unavailable:Canary 180M flash needs voice.language (en, de, es or fr)", status("auto"))
            assumeTrue(SpeexDecoder.available, "libspeex not installed")
            assertEquals("ready:local\tCanary 180M flash (en)", status("en"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `Canary's spaces before punctuation and doubled question marks are tidied`() {
        assertEquals("Für was steht U S B?", tidyCanaryTranscript("Für was steht U S B ? ?"))
        assertEquals("Kannst du auf dem Heimweg noch Milch und Brot mitbringen?",
            tidyCanaryTranscript("Kannst du auf dem Heimweg noch Milch und Brot mitbringen ? ?"))
        assertEquals("Ich komme zehn Minuten später, fand schon ohne mich an.",
            tidyCanaryTranscript("Ich komme zehn Minuten später , fand schon ohne mich an ."))
        assertEquals("Das Treffen wird auf Donnerstag um halb vier verschoben.",
            tidyCanaryTranscript("Das Treffen wird auf Donnerstag um halb vier verschoben ."))
        assertEquals("Ja passt.", tidyCanaryTranscript("Ja passt . "))
        assertEquals("Ja, Passt!", tidyCanaryTranscript("Ja , Passt !"))
        assertEquals("Running ten minutes late. Start without me.", tidyCanaryTranscript("Running ten minutes late. Start without me."))
        assertEquals("at 3.30 in the afternoon: fine; ok", tidyCanaryTranscript("at 3.30 in the afternoon : fine ; ok"))
        assertEquals("", tidyCanaryTranscript(""))
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
