package de.yoxcu.stoandl.voice.tts

import de.yoxcu.stoandl.config.ConfigStore
import de.yoxcu.stoandl.config.StoandlConfig
import de.yoxcu.stoandl.config.StoandlConfig.VoiceBackend
import de.yoxcu.stoandl.secret.FileSecretStore
import de.yoxcu.stoandl.voice.ESPEAK_VOICE
import de.yoxcu.stoandl.voice.SPEECH_MODELS
import de.yoxcu.stoandl.voice.VoiceModels
import de.yoxcu.stoandl.voice.Wav
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.nio.file.Files
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Text to speech without an engine installed: argv, speed, voice choice, and the service around a fake one. */
class TtsTest {
    private val out = File("/run/x.wav")

    @Test
    fun `engine command lines and the speed mapping`() {
        val model = File("/m/de_DE-thorsten-low.onnx")
        assertEquals(listOf("taskset", "-c", "4,5", "piper_exe", "-m", model.path, "-f", out.path, "--length_scale", "1.00"),
            PiperTts.piperArgv(listOf("taskset", "-c", "4,5"), "piper_exe", model, out, 100))
        assertEquals("0.50", PiperTts.piperArgv(emptyList(), "piper", model, out, 200).last(), "twice as fast: half as long")
        assertEquals("2.00", PiperTts.piperArgv(emptyList(), "piper", model, out, 10).last(), "clamped to 50 %")
        assertEquals(listOf("espeak-ng", "-b", "1", "-s", "262", "-v", "de", "-w", out.path, "--stdin"),
            EspeakTts.espeakArgv("de-DE", 150, out))
        assertEquals(listOf("espeak-ng", "-b", "1", "-s", "175", "-w", out.path, "--stdin"), EspeakTts.espeakArgv(null, 100, out))
        assertEquals(listOf("sh", "-c", "my-tts --lang en"), CommandTts.commandArgv("my-tts --lang {lang}", "EN"))
        assertEquals("my-tts --lang de", CommandTts.commandArgv("my-tts --lang {lang}", "de-DE", "en").last(), "the primary subtag")
        assertEquals("my-tts --lang de", CommandTts.commandArgv("my-tts --lang {lang}", null, "de").last(), "none given: the system's")
        assertEquals("my-tts --lang en", CommandTts.commandArgv("my-tts --lang {lang}", "x; rm", "").last(), "never empty, never shell")
        val body = Json.parseToJsonElement(HttpTts.requestBody("Hallo", "tts-1", "", 125)).jsonObject
        assertEquals(setOf("model", "input", "speed", "response_format"), body.keys, "an empty voice is left out")
        assertEquals("1.25", body["speed"]!!.jsonPrimitive.content)
        assertEquals("wav", body["response_format"]!!.jsonPrimitive.content)
    }

    /** §6 Q4: the voice for the language asked for, preferring tts.local_voice. */
    @Test
    fun `the voice is picked by language`() {
        val installed = SPEECH_MODELS.filter { it.id in setOf("piper-de-thorsten-low", "piper-en-kathleen-low", "piper-de-thorsten-medium") }
        assertEquals("piper-de-thorsten-medium", pickVoice("piper-de-thorsten-medium", "de", installed), "the local voice speaks it")
        assertEquals("piper-en-kathleen-low", pickVoice("piper-de-thorsten-medium", "en", installed))
        assertEquals("piper-de-thorsten-low", pickVoice("piper-en-kathleen-low", "de_DE", installed), "first installed of that language")
        assertEquals("piper-en-kathleen-low", pickVoice("piper-en-kathleen-low", "fr", installed), "none speaks it: the local voice")
        assertEquals("piper-en-kathleen-low", pickVoice("piper-en-kathleen-low", null, installed))
        assertEquals(ESPEAK_VOICE, pickVoice(ESPEAK_VOICE, "de", installed), "espeak-ng speaks every language")
        assertEquals("piper-de-thorsten-low", pickVoice("", "de", installed))
    }

    @Test
    fun `text is one line, capped`() {
        assertEquals("Hallo Welt.", ttsText("  Hallo\n\tWelt. \n"))
        assertEquals(MAX_TTS_CHARS, ttsText("a ".repeat(3000)).length)
    }

    private fun tone(rate: Int, ms: Int) = ShortArray(rate * ms / 1000) { (8000 * sin(2 * Math.PI * 440 * it / rate)).toInt().toShort() }

    private fun backends(backend: VoiceBackend, command: String = "", voice: String = "") = TtsBackends(
        ConfigStore(StoandlConfig.load(File("/nonexistent/stoandl.conf"), logResult = false)
            .copy(ttsBackend = backend, ttsCommand = command, ttsLocalVoice = voice)),
        FileSecretStore(Files.createTempFile("secrets", "").toFile()),
        VoiceModels(GlobalScope, Files.createTempDirectory("models").toFile()),
    )

    @Test
    fun `status says what is set up and what is missing`() {
        assertEquals("off:", backends(VoiceBackend.OFF).status())
        assertEquals("unavailable:tts.command is empty", backends(VoiceBackend.COMMAND).status())
        assertEquals("ready:command\tcat", backends(VoiceBackend.COMMAND, "cat /x.wav").status())
        assertEquals("unavailable:no voice chosen (tts.local_voice)", backends(VoiceBackend.LOCAL).status())
        assertEquals("unavailable:Thorsten (German) isn't downloaded yet", backends(VoiceBackend.LOCAL, voice = "piper-de-thorsten-low").status())
        assertEquals("unavailable:tts.url is not set", backends(VoiceBackend.HTTP).status())
    }

    /** A command engine's WAV on stdout, even with the streaming header espeak-ng and servers write. */
    @Test
    fun `a speech command's WAV is the audio`() = runBlocking {
        val pcm = tone(22050, 300)
        val wav = Wav.encode(pcm, 22050).also { java.nio.ByteBuffer.wrap(it).order(java.nio.ByteOrder.LITTLE_ENDIAN).putInt(40, 0x7ffff000) }
        val file = Files.createTempFile("tts", ".wav").toFile().apply { writeBytes(wav) }
        // The text arrives on stdin: the script checks it.
        val tts = CommandTts("""sh -c 'read t; [ "${'$'}t" = "Guten Tag." ] && cat ${file.path}'""")
        val audio = assertIs<TtsOutcome.Audio>(tts.synthesize("Guten Tag.", "de", 100, 5_000))
        assertEquals(22050, audio.sampleRate)
        assertContentEquals(pcm, audio.samples)
        assertEquals(300, audio.ms)
        assertIs<TtsOutcome.Failed>(CommandTts("echo not a wav").synthesize("x", null, 100, 5_000))
        assertTrue((CommandTts("exit 3").synthesize("x", null, 100, 5_000) as TtsOutcome.Failed).message.contains("exited 3"))
    }

    /** The keep-awake holds a service takes, and how many are still open. */
    private class Holds {
        val taken = java.util.Collections.synchronizedList(mutableListOf<String>())
        val open = java.util.concurrent.atomic.AtomicInteger()
        fun hold(why: String): AutoCloseable { taken += why; open.incrementAndGet(); return AutoCloseable { open.decrementAndGet() } }
        /** Wait until every hold is closed and at least [n] were taken; false after [ms]. */
        fun settle(n: Int, ms: Long = 4_000): Boolean {
            val end = System.nanoTime() + ms * 1_000_000
            while (System.nanoTime() < end) { if (taken.size >= n && open.get() == 0) return true; Thread.sleep(20) }
            return false
        }
    }

    @Test
    fun `Speak answers at once, plays on this device, and refuses with the backend's state`() {
        val file = Files.createTempFile("tts", ".wav").toFile().apply { writeBytes(Wav.encode(tone(16000, 500), 16000)) }
        val holds = Holds()
        // The engine takes 2 s; `true` stands in for pw-play: it exits at once.
        val service = TtsService(backends(VoiceBackend.COMMAND, "sh -c 'sleep 2; cat ${file.path}'"), GlobalScope, holds::hold, players = listOf("true"))
        val start = System.nanoTime()
        assertEquals("ok:speaking 5 characters", service.speak("Hello"))
        assertTrue((System.nanoTime() - start) / 1_000_000 < 1_000, "the caller doesn't wait for the engine")
        assertTrue(holds.settle(2), "read and played")
        assertEquals(setOf("Reading text aloud", "Text to speech"), holds.taken.toSet())
        assertEquals("off:", TtsService(backends(VoiceBackend.OFF), GlobalScope, players = listOf("true")).speak("Hello"))
        assertEquals("error:no text", service.speak(" \n "))
        assertEquals("unavailable:tts.command is empty", TtsService(backends(VoiceBackend.COMMAND), GlobalScope, players = listOf("true")).speak("x"))
        assertTrue(TtsService(backends(VoiceBackend.OFF), GlobalScope, players = listOf("stoandl-no-player")).speak("x").startsWith("unavailable:no audio player"))
    }

    @Test
    fun `a new Speak stops the reading before it`() {
        val file = Files.createTempFile("tts", ".wav").toFile().apply { writeBytes(Wav.encode(tone(16000, 200), 16000)) }
        val holds = Holds()
        // The engine takes 5 s for the first text and none for the second.
        val service = TtsService(backends(VoiceBackend.COMMAND, "sh -c 'read t; [ \"${'$'}t\" = slow ] && sleep 5; cat ${file.path}'"),
            GlobalScope, holds::hold, players = listOf("true"))
        service.speak("slow")
        Thread.sleep(500)
        val start = System.nanoTime()
        service.speak("fast")
        assertTrue(holds.settle(4), "both readings ended")
        assertTrue((System.nanoTime() - start) / 1_000_000 < 4_000, "the first engine was killed, not waited for")
        assertEquals("none:nothing is being read aloud", service.stop())
    }

    /** An extension's synthesize: blocks for the watch, or the error it shows (off, unavailable, busy). */
    @Test
    fun `synthesize answers with blocks, or off, unavailable and busy`() = runBlocking {
        val file = Files.createTempFile("tts", ".wav").toFile().apply { writeBytes(Wav.encode(tone(16000, 1000), 16000)) }
        val service = TtsService(backends(VoiceBackend.COMMAND, "sh -c 'read t; [ \"${'$'}t\" = slow ] && sleep 2; cat ${file.path}'"), GlobalScope)
        val r = assertIs<Synthesis.Blocks>(service.synthesize("Hallo.", "de", "pebble-adpcm"))
        assertEquals(1000, r.ms)
        assertEquals(listOf(2000, 2000, 11), r.blocks.map { it.size }, "8000 samples: two full blocks, then 14 (13 codes padded to 14)")
        val wav = assertIs<Synthesis.WavFile>(service.synthesize("Hallo.", null, "wav"))
        assertEquals(16000, Wav.decode(File(wav.path).readBytes())!!.second)
        File(wav.path).delete()

        val slow = async(Dispatchers.IO) { service.synthesize("slow", null, "pebble-adpcm") }
        Thread.sleep(500)
        assertEquals(Synthesis.Error("busy"), service.synthesize("Hallo.", null, "pebble-adpcm"))
        assertIs<Synthesis.Blocks>(slow.await())

        // Cancelled (an extension's newer request replaces it): the engine is killed and free at once.
        val replaced = async(Dispatchers.IO) { service.synthesize("slow", null, "pebble-adpcm") }
        Thread.sleep(500)
        val start = System.nanoTime()
        replaced.cancelAndJoin()
        assertIs<Synthesis.Blocks>(service.synthesize("Hallo.", null, "pebble-adpcm"))
        assertTrue((System.nanoTime() - start) / 1_000_000 < 1_500, "not waited out")

        assertEquals(Synthesis.Error("off"), TtsService(backends(VoiceBackend.OFF), GlobalScope).synthesize("x", null, "pebble-adpcm"))
        assertEquals(Synthesis.Error("unavailable: tts.command is empty"),
            TtsService(backends(VoiceBackend.COMMAND), GlobalScope).synthesize("x", null, "pebble-adpcm"))
        assertTrue(service.synthesize("x", null, "mp3").let { it is Synthesis.Error && it.message.startsWith("unsupported format") })
    }
}
