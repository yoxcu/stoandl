package de.yoxcu.stoandl.voice

import de.yoxcu.stoandl.config.ConfigStore
import de.yoxcu.stoandl.config.StoandlConfig
import de.yoxcu.stoandl.config.StoandlConfig.VoiceBackend
import de.yoxcu.stoandl.secret.FileSecretStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** `voice.fallback_backend`: when the second engine runs and how the deadline is split between the two. */
class FallbackSttTest {
    /** A fake clock the fake engines advance by the time they "take". */
    private var now = 0L

    private inner class Fake(
        override val id: String,
        private val takesMs: Long = 0,
        private val notReady: String? = null,
        private val answer: () -> SttOutcome,
    ) : SpeechToText {
        val budgets = mutableListOf<Long>()
        override fun readiness() = notReady
        override suspend fun transcribe(audio: SttAudio, language: String, timeoutMs: Long): SttOutcome {
            budgets += timeoutMs
            now += minOf(takesMs, timeoutMs)
            return if (takesMs > timeoutMs) SttOutcome.Failed("timed out") else answer()
        }
    }

    private val audio = SttAudio.Pcm16(ShortArray(16), 16_000)
    private fun router(p: SpeechToText, f: SpeechToText) = FallbackStt(p, f) { now }
    private fun run(p: SpeechToText, f: SpeechToText, budget: Long = 14_000) =
        runBlocking { router(p, f).transcribe(audio, "de", budget) }

    @Test
    fun `the primary's answer is final and the fallback never runs`() {
        val fallback = Fake("http") { SttOutcome.Text("other") }
        assertEquals(SttOutcome.Text("hello"), run(Fake("local", 3_000) { SttOutcome.Text("hello") }, fallback))
        assertTrue(fallback.budgets.isEmpty())
    }

    @Test
    fun `like the official STTRouter, the primary gets the deadline minus 7 s and the fallback the rest`() {
        val primary = Fake("local", 2_000) { SttOutcome.Failed("model crashed") }
        val fallback = Fake("http") { SttOutcome.Text("hello") }
        assertEquals(SttOutcome.Text("hello"), run(primary, fallback))
        assertEquals(listOf(7_000L), primary.budgets)
        // What the primary didn't use goes to the fallback too.
        assertEquals(listOf(12_000L), fallback.budgets)
    }

    @Test
    fun `a primary that runs out of its share hands over with the reserve left`() {
        val primary = Fake("local", 20_000) { SttOutcome.Text("too late") }
        val fallback = Fake("http") { SttOutcome.Text("hello") }
        assertEquals(SttOutcome.Text("hello"), run(primary, fallback))
        assertEquals(listOf(7_000L), fallback.budgets)
    }

    @Test
    fun `the reserve is at most half, so a long file transcription still favours the primary`() {
        assertEquals(7_000L, fallbackReserveMs(14_000))
        assertEquals(7_000L, fallbackReserveMs(60_000))
        assertEquals(2_000L, fallbackReserveMs(4_000))
    }

    @Test
    fun `an empty transcript gets a second opinion but stays empty when the fallback fails`() {
        val fallback = Fake("http") { SttOutcome.Unreachable("offline") }
        assertEquals(SttOutcome.Text(""), run(Fake("local") { SttOutcome.Text("") }, fallback))
        assertEquals(1, fallback.budgets.size)
        assertEquals(SttOutcome.Text("hi"), run(Fake("local") { SttOutcome.Text(" ") }, Fake("http") { SttOutcome.Text("hi") }))
    }

    @Test
    fun `when both fail the result is the fallback's kind with both reasons`() {
        val o = run(Fake("local") { SttOutcome.Failed("crash") }, Fake("http") { SttOutcome.Unreachable("offline") })
        assertIs<SttOutcome.Unreachable>(o)
        assertEquals("local: crash; http: offline", o.message)
        assertIs<SttOutcome.Failed>(run(Fake("http") { SttOutcome.Unreachable("offline") }, Fake("local") { SttOutcome.Failed("crash") }))
    }

    @Test
    fun `an engine that isn't ready is skipped and the other gets the whole deadline`() {
        val primary = Fake("local", notReady = "no model") { SttOutcome.Text("never") }
        val fallback = Fake("http") { SttOutcome.Text("hello") }
        val r = router(primary, fallback)
        assertNull(r.readiness())
        assertEquals(SttOutcome.Text("hello"), runBlocking { r.transcribe(audio, "de", 14_000) })
        assertEquals(listOf(14_000L), fallback.budgets)

        val alone = Fake("local") { SttOutcome.Text("hello") }
        run(alone, Fake("http", notReady = "voice.url is not set") { SttOutcome.Text("never") })
        assertEquals(listOf(14_000L), alone.budgets)
        assertEquals("no model", router(primary, Fake("http", notReady = "no url") { SttOutcome.Text("") }).readiness())
    }

    @Test
    fun `a backend ignoring its deadline is cut off at its share`() = runBlocking {
        val stuck = object : SpeechToText {
            override val id = "command"
            override fun readiness(): String? = null
            override suspend fun transcribe(audio: SttAudio, language: String, timeoutMs: Long): SttOutcome {
                delay(60_000); return SttOutcome.Text("never")
            }
        }
        val o = FallbackStt(stuck, Fake("http") { SttOutcome.Text("hello") }).transcribe(audio, "de", 400)
        assertEquals(SttOutcome.Text("hello"), o)
    }

    // --- VoiceBackends: config → router, VoiceStatus ---------------------------------------------

    private fun backends(primary: VoiceBackend, fallback: VoiceBackend, url: String = "http://127.0.0.1:9/v1/audio/transcriptions") =
        VoiceBackends(
            ConfigStore(StoandlConfig.load(File("/nonexistent/stoandl.conf"), logResult = false)
                .copy(voiceBackend = primary, voiceFallbackBackend = fallback, voiceCommand = "sh -c true", voiceUrl = url)),
            FileSecretStore(Files.createTempFile("secrets", "").toFile()),
            VoiceModels(kotlinx.coroutines.GlobalScope, Files.createTempDirectory("stt").toFile()),
        )

    @Test
    fun `no fallback when it is off, the primary again, or voice is off`() {
        assertIs<CommandStt>(backends(VoiceBackend.COMMAND, VoiceBackend.OFF).current())
        assertIs<CommandStt>(backends(VoiceBackend.COMMAND, VoiceBackend.COMMAND).current())
        assertNull(backends(VoiceBackend.OFF, VoiceBackend.HTTP).current())
        assertEquals("off:", backends(VoiceBackend.OFF, VoiceBackend.HTTP).status())
        val r = assertIs<FallbackStt>(backends(VoiceBackend.COMMAND, VoiceBackend.HTTP).current())
        assertIs<CommandStt>(r.primary)
        assertIs<HttpStt>(r.fallback)
    }

    @Test
    fun `VoiceStatus names the engine that answers first and notes the other`() {
        assumeTrue(SpeexDecoder.available, "libspeex not installed")
        assertEquals("ready:command\tsh\tthen http http://127.0.0.1:9/v1/audio/transcriptions",
            backends(VoiceBackend.COMMAND, VoiceBackend.HTTP).status())
        assertEquals("ready:command\tsh\t(fallback http unavailable: voice.url is not set)",
            backends(VoiceBackend.COMMAND, VoiceBackend.HTTP, url = "").status())
        val s = backends(VoiceBackend.LOCAL, VoiceBackend.HTTP).status()
        assertTrue(s.startsWith("ready:http\t"), s)
        assertContains(s, "(local unavailable: no model chosen")
        assertEquals("unavailable:no model chosen (voice.local_model)",
            backends(VoiceBackend.LOCAL, VoiceBackend.HTTP, url = "").status())
    }
}
