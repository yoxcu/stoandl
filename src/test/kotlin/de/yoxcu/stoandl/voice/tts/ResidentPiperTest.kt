package de.yoxcu.stoandl.voice.tts

import de.yoxcu.stoandl.voice.Wav
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread
import kotlin.math.sin
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The resident Piper around a fake process (and one real shell script standing in for `piper_exe`). */
class ResidentPiperTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val dir: File = Files.createTempDirectory("piper").toFile()
    private val pcm = ShortArray(2205) { (8000 * sin(2 * Math.PI * 440 * it / 22050)).toInt().toShort() }
    private val wav = Wav.encode(pcm, 22050)

    @AfterTest
    fun cleanup() {
        scope.cancel()
        dir.deleteRecursively()
    }

    /** A Piper that opens the line's output_file after [openAfterMs], writes the WAV and closes it after [workMs]. */
    private inner class FakePiper(
        val openAfterMs: Long = 5,
        val workMs: Long = 40,
        val opens: Boolean = true,
        val diesOnWrite: Boolean = false,
    ) : EngineProcess {
        @Volatile var dead = false
        val lines = CopyOnWriteArrayList<String>()
        @Volatile private var open: File? = null

        override val alive: Boolean get() = !dead

        override fun write(line: String): Boolean {
            if (dead) return false
            lines += line
            if (diesOnWrite) { dead = true; return true }
            val out = File(Json.parseToJsonElement(line).jsonObject["output_file"]!!.jsonPrimitive.content)
            if (opens) thread {
                Thread.sleep(openAfterMs)
                if (dead) return@thread
                open = out          // open(O_CREAT) creates the file and holds it in one step
                out.writeBytes(ByteArray(0))
                Thread.sleep(workMs)
                if (dead) return@thread
                out.writeBytes(wav)
                open = null
            }
            return true
        }

        override fun holds(file: File) = open == file
        override fun stderrTail() = "Model file doesn't exist\n"
        override fun kill() { dead = true }
    }

    private fun out() = File(dir, "${System.nanoTime()}.wav")
    private val argv = listOf("taskset", "-c", "4-7", "/usr/bin/piper_exe", "-m", "/m/a.onnx")

    @Test
    fun `the JSON line and the command line`() {
        val line = ResidentPiper.jsonLine("Er sagte: \"Hallo\"\nZeile zwei – ok", File("/run/x.wav"))
        val o = Json.parseToJsonElement(line).jsonObject
        assertEquals("Er sagte: \"Hallo\"\nZeile zwei – ok", o["text"]!!.jsonPrimitive.content)
        assertEquals("/run/x.wav", o["output_file"]!!.jsonPrimitive.content)
        assertFalse('\n' in line, "one line")
        assertEquals(listOf("taskset", "-c", "4,5", "piper_exe", "-m", "/m/v.onnx", "--json-input", "-d", "/run/tts", "--length_scale", "0.80"),
            ResidentPiper.argv(listOf("taskset", "-c", "4,5"), "piper_exe", File("/m/v.onnx"), File("/run/tts"), 125))
        assertFalse(ResidentPiper.canServe(File("/usr/bin/piper")), "only libpiper's piper_exe")
    }

    @Test
    fun `a voice stays loaded between sentences`() = runBlocking {
        val procs = CopyOnWriteArrayList<FakePiper>()
        val piper = ResidentPiper(scope, { FakePiper().also(procs::add) })
        repeat(2) {
            val o = out()
            val audio = assertIs<TtsOutcome.Audio>(piper.synthesize("v", argv, "Satz $it.", o, 5_000))
            assertContentEquals(pcm, audio.samples)
            assertFalse(o.exists(), "the WAV is deleted")
        }
        assertEquals(1, procs.size)
        assertEquals(2, procs[0].lines.size)
        assertTrue(piper.isRunning("v"))
    }

    @Test
    fun `another speed replaces the process, and voices no longer used stop`() = runBlocking {
        val procs = CopyOnWriteArrayList<FakePiper>()
        val piper = ResidentPiper(scope, { FakePiper().also(procs::add) })
        assertIs<TtsOutcome.Audio>(piper.synthesize("v", argv, "a", out(), 5_000))
        assertIs<TtsOutcome.Audio>(piper.synthesize("v", argv + listOf("--length_scale", "0.80"), "b", out(), 5_000))
        assertEquals(2, procs.size)
        assertTrue(procs[0].dead, "the old speed's process is stopped")
        assertIs<TtsOutcome.Audio>(piper.synthesize("w", argv, "c", out(), 5_000))
        assertTrue(piper.isRunning("v") && piper.isRunning("w"), "one per voice")
        piper.retain(setOf("w"))
        assertFalse(piper.isRunning("v"))
        assertTrue(piper.isRunning("w"))
    }

    @Test
    fun `it stops after the idle time`() = runBlocking {
        val proc = FakePiper()
        val piper = ResidentPiper(scope, { proc }, idleMs = 150)
        assertIs<TtsOutcome.Audio>(piper.synthesize("v", argv, "a", out(), 5_000))
        delay(50)
        assertTrue(piper.isRunning("v"))
        delay(300)
        assertFalse(piper.isRunning("v"))
        assertTrue(proc.dead)
    }

    @Test
    fun `a cancelled sentence kills the process`() = runBlocking {
        val procs = CopyOnWriteArrayList<FakePiper>()
        val piper = ResidentPiper(scope, { FakePiper(workMs = 5_000).also(procs::add) })
        val job = launch(Dispatchers.Default) { piper.synthesize("v", argv, "long", out(), 10_000) }
        delay(200)
        job.cancelAndJoin()
        assertTrue(procs.single().dead)
        assertFalse(piper.isRunning("v"))
    }

    @Test
    fun `a process that exits fails the sentence, and the next one starts again`() = runBlocking {
        val procs = CopyOnWriteArrayList<FakePiper>()
        val piper = ResidentPiper(scope, { (if (procs.isEmpty()) FakePiper(diesOnWrite = true) else FakePiper()).also(procs::add) })
        val failed = assertIs<TtsOutcome.Failed>(piper.synthesize("v", argv, "a", out(), 5_000))
        assertEquals("piper exited: Model file doesn't exist", failed.message)
        assertIs<TtsOutcome.Audio>(piper.synthesize("v", argv, "b", out(), 5_000))
        assertEquals(2, procs.size)
    }

    @Test
    fun `a sentence that takes too long is a failure`() = runBlocking {
        val proc = FakePiper(workMs = 5_000)
        val piper = ResidentPiper(scope, { proc })
        val failed = assertIs<TtsOutcome.Failed>(piper.synthesize("v", argv, "a", out(), 300))
        assertEquals("piper took longer than 0 s", failed.message)
        assertTrue(proc.dead)
    }

    @Test
    fun `a Piper that writes no file for a line is read one-shot instead`() = runBlocking {
        val proc = FakePiper(opens = false)
        val piper = ResidentPiper(scope, { proc }, coldAppearMs = 200)
        assertNull(piper.synthesize("v", listOf("/nonexistent/piper_exe"), "a", out(), 5_000))
        assertTrue(proc.dead)
        assertNull(ResidentPiper(scope, { null }).synthesize("v", argv, "a", out(), 5_000), "can't start: one-shot")
    }

    /** A shell script in piper_exe's place: it reads JSON lines and holds each file open while it "works",
     *  so the done check reads the real /proc/<pid>/fd. */
    @Test
    fun `a real process, its open file seen through proc`() = runBlocking {
        val src = File(dir, "speech.wav").apply { writeBytes(wav) }
        val script = """
            while IFS= read -r line; do
              f=${'$'}(printf '%s' "${'$'}line" | sed -n 's/.*"output_file":"\([^"]*\)".*/\1/p')
              exec 3>"${'$'}f"; sleep 0.3; cat '${src.path}' >&3; exec 3>&-
            done
        """.trimIndent()
        val piper = ResidentPiper(scope)
        val sh = listOf("sh", "-c", script)
        repeat(2) {
            val audio = assertIs<TtsOutcome.Audio>(piper.synthesize("v", sh, "Hallo.", out(), 5_000))
            assertContentEquals(pcm, audio.samples, "the whole file, read after it was closed")
        }
        assertTrue(piper.isRunning("v"))
        piper.retain(emptySet())
        assertFalse(piper.isRunning("v"))
    }
}
