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
            assertTrue(voices.any { it.lang == lang && it.recommended }, "a recommended $lang voice")
        }
        voices.forEach { v ->
            assertEquals(listOf(".onnx", ".onnx.json"), v.files.map { it.substring(it.indexOf('.')) }, v.id)
            assertTrue(v.file.endsWith(".onnx") && v.urls.all { it.startsWith("https://huggingface.co/rhasspy/piper-voices/") }, v.id)
        }
        assertTrue(SPEECH_MODELS.filter { it.kind == ModelKind.STT }.all { it.files.size == 1 && it.lang.isEmpty() })
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
            val voice = SpeechModel("test-voice", "Test", SpeechEngine.PIPER, listOf("$base/x.onnx", "$base/x.onnx.json"), 1, "n", lang = "de")
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
}
