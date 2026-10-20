package de.yoxcu.stoandl.voice

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The one model store for speech-to-text models and text-to-speech voices. */
class VoiceModelsTest {
    @Test
    fun `the catalog has a recommended watch voice for German and English, each with its config`() {
        val voices = SPEECH_MODELS.filter { it.kind == ModelKind.TTS }
        assertEquals(SPEECH_MODELS.size, SPEECH_MODELS.map { it.id }.toSet().size, "ids are unique")
        for (lang in listOf("de", "en")) {
            assertTrue(voices.any { lang in it.languages && it.recommended }, "a recommended $lang voice")
        }
        voices.forEach { v ->
            assertEquals(listOf(".onnx", ".onnx.json"), v.files.map { it.substring(it.indexOf('.')) }, v.id)
            assertTrue(v.file.endsWith(".onnx") && v.urls.all { it.startsWith("https://huggingface.co/rhasspy/piper-voices/") }, v.id)
        }
        assertTrue(voices.all { it.languages.size == 1 }, "a voice speaks one language")
        assertTrue(SPEECH_MODELS.filter { it.kind == ModelKind.STT }.all { it.files.size == 1 })
    }

    /** Canary is pinned to a checked revision and names the languages it must be told; Parakeet v3 stays the one
     *  recommendation for any language. */
    @Test
    fun `the Canary entry is pinned and limited to its languages`() {
        val canary = SPEECH_MODELS.single { it.id == "canary-180m" }
        assertEquals(SpeechEngine.CANARY, canary.engine)
        assertEquals(ModelKind.STT, canary.kind)
        assertEquals(listOf("en", "de", "es", "fr"), canary.languages)
        assertEquals(1, canary.urls.size)
        assertTrue(Regex("""^https://huggingface\.co/[^/]+/[^/]+/resolve/[0-9a-f]{40}/[^/]+\.gguf$""").matches(canary.urls.single()), canary.urls.single())
        assertEquals("canary-180m-flash-Q8_0.gguf", canary.file)
        assertEquals(218, canary.sizeMb)
        assertTrue("CC-BY-4.0" in canary.note && "NVIDIA" in canary.note && "voice.language" in canary.note)
        assertTrue(SpeechEngine.CANARY.needsLanguage && SPEECH_MODELS.none { it.engine.needsLanguage && it.languages.isEmpty() })
        assertEquals(listOf("parakeet-v3"), SPEECH_MODELS.filter { it.kind == ModelKind.STT && it.recommended }.map { it.id })
        assertTrue(SPEECH_MODELS.any { it.id == "parakeet-v2-en" })
    }

    /** A voice is installed only with both its files; download, records and remove handle every file. */
    @Test
    fun `a two-file voice downloads, lists by kind and removes`() = runBlocking {
        val onnx = ByteArray(300_000) { it.toByte() }
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { ex ->
                val body = if (ex.requestURI.path.endsWith(".onnx")) onnx else "{\"audio\":{}}".toByteArray()
                ex.sendResponseHeaders(200, body.size.toLong())
                ex.responseBody.use { it.write(body) }
            }
            start()
        }
        val root = Files.createTempDirectory("models").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val base = "http://127.0.0.1:${server.address.port}/v"
            val voice = SpeechModel("test-voice", "Test", SpeechEngine.PIPER, listOf("$base/x.onnx", "$base/x.onnx.json"), 1, "n", languages = listOf("de"))
            val stt = SpeechModel("test-stt", "STT", SpeechEngine.WHISPER, listOf("$base/m.bin"), 1, "n")
            val models = VoiceModels(scope, root, listOf(stt, voice))
            assertTrue(models.records().all { it.split('\t').size == 9 })
            assertEquals(listOf("stt", "tts"), models.records().map { it.split('\t')[8] })

            // Half a voice (the config without the model) isn't installed.
            File(root, "tts").mkdirs(); File(root, "tts/x.onnx.json").writeText("{}")
            assertFalse(models.isInstalled(voice))

            val done = kotlinx.coroutines.CompletableDeferred<SpeechModel>()
            assertTrue(models.startDownload("test-voice") { done.complete(it) }.startsWith("ok:"))
            assertEquals("test-voice", withTimeout(10_000) { done.await() }.id)
            assertTrue(models.isInstalled(voice))
            assertEquals(onnx.size.toLong(), File(root, "tts/x.onnx").length())
            assertEquals(listOf(voice), models.installed(ModelKind.TTS))
            assertEquals(emptyList(), models.installed(ModelKind.STT))
            assertEquals("installed", models.records().single { it.startsWith("test-voice\t") }.split('\t')[4])
            assertTrue(File(root, "tts").listFiles()!!.none { it.name.endsWith(".part") })

            assertTrue(models.remove("test-voice").startsWith("ok:"))
            assertFalse(File(root, "tts/x.onnx").exists() || File(root, "tts/x.onnx.json").exists())
            assertTrue(models.remove("test-voice").startsWith("notfound:"))
        } finally {
            scope.cancel()
            server.stop(0)
            root.deleteRecursively()
        }
    }

    @Test
    fun `choices list what is installed of a kind`() {
        val root = Files.createTempDirectory("models").toFile()
        try {
            val thorsten = SPEECH_MODELS.first { it.id == "piper-de-thorsten-low" }
            thorsten.files.forEach { File(root, "tts/$it").apply { parentFile.mkdirs() }.writeText("x") }
            assertEquals("piper-de-thorsten-low" to thorsten.name, VoiceModels.choices(ModelKind.TTS, root).first())
            assertEquals(emptyList(), VoiceModels.choices(ModelKind.STT, root))
        } finally {
            root.deleteRecursively()
        }
    }

    /** A bare `piper` may be libratbag's mouse configurator: only Piper's help names the length scale. */
    @Test
    fun `a piper on PATH counts only when it is Piper text to speech`() {
        val dir = Files.createTempDirectory("piper").toFile()
        fun script(name: String, help: String) = File(dir, name).apply {
            writeText("#!/bin/sh\necho '$help' >&2\nexit 0\n"); setExecutable(true)
        }
        try {
            assertTrue(isPiperTts(script("tts", "usage: piper [-h] -m MODEL [--length-scale LENGTH_SCALE]")))
            assertTrue(isPiperTts(script("cpp", "   --length_scale        NUM   phoneme length (default: 1.0)")))
            assertFalse(isPiperTts(script("mouse", "Usage:\n  piper [OPTION…]\n  --gapplication-service")))
        } finally {
            dir.deleteRecursively()
        }
    }
}
