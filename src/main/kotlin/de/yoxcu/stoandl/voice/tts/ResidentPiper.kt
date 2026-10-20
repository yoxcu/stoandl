package de.yoxcu.stoandl.voice.tts

import de.yoxcu.stoandl.util.runProcess
import de.yoxcu.stoandl.voice.Wav
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

private val log = KotlinLogging.logger {}

/** A resident Piper is stopped after this long without a sentence. */
internal const val PIPER_IDLE_MS = 60_000L
/** How often a running sentence's file is checked. */
private const val POLL_MS = 15L
/** A sentence's file appears once Piper has read its line: at once when the voice is loaded, after the model
 *  load (seconds on a phone) for the first. Longer means the program doesn't take JSON lines. */
private const val WARM_APPEAR_MS = 5_000L
private const val COLD_APPEAR_MS = 20_000L
/** Bytes of a resident's stderr kept for an error message. */
private const val STDERR_TAIL = 2048

/** A running engine process as [ResidentPiper] sees it (a fake in tests). */
internal interface EngineProcess {
    val alive: Boolean
    /** Writes [line] and a newline to its stdin; false when the pipe is closed. */
    fun write(line: String): Boolean
    /** Whether the process has [file] open. */
    fun holds(file: File): Boolean
    /** The end of its stderr. */
    fun stderrTail(): String
    fun kill()
}

/**
 * Piper that stays loaded. libpiper's `piper_exe` takes one JSON line per sentence on stdin (`--json-input`,
 * `{"text": …, "output_file": …}`) and writes each WAV to that file, so a voice model loads once instead of
 * once per sentence (about 3 s on a phone). It prints the finished path, but its stdout is block-buffered on
 * a pipe, so a sentence counts as done once its file exists and the process no longer holds it open (it opens
 * the file before synthesizing and closes it when done).
 *
 * One process per voice ([synthesize]'s `voice`); a different command line for that voice (another speed, the
 * program moved) replaces it. [warm] starts one ahead of its first sentence. A process is stopped after
 * [idleMs] without a sentence and when [retain] no longer lists its voice (the config changed), and killed
 * when a sentence times out or fails; the next sentence starts it again. A cancelled sentence is left to
 * finish (a second at most once loaded) and its audio dropped: killing it would cost the next reading the
 * model load. The text is never logged.
 */
class ResidentPiper internal constructor(
    private val scope: CoroutineScope,
    private val start: (argv: List<String>) -> EngineProcess?,
    private val idleMs: Long = PIPER_IDLE_MS,
    private val pollMs: Long = POLL_MS,
    private val warmAppearMs: Long = WARM_APPEAR_MS,
    private val coldAppearMs: Long = COLD_APPEAR_MS,
) {
    constructor(scope: CoroutineScope) : this(scope, ::startEngineProcess)

    private class Running(val argv: List<String>, val proc: EngineProcess) {
        var warm = false
        var idle: Job? = null
        /** The file of the sentence it is writing; still set after that sentence was cancelled, so the next
         *  line waits for it (Piper reads its lines one after another). */
        var writing: File? = null
    }

    private val lock = Mutex()
    private val running = HashMap<String, Running>()   // by voice, under lock

    /**
     * [text] (one line) as speech from the resident Piper for [voice], started with [argv] if it isn't running.
     * [out] is the WAV file it writes, deleted afterwards. Null when this Piper can't serve resident (it doesn't
     * start, or never opened the file): the caller reads the sentence one-shot instead.
     */
    internal suspend fun synthesize(voice: String, argv: List<String>, text: String, out: File, timeoutMs: Long): TtsOutcome? =
        lock.withLock {
            var r = runningLocked(voice, argv) ?: return@withLock null
            try {
                withContext(Dispatchers.IO) {
                    if (!finishedLocked(r, timeoutMs)) {
                        stopLocked(voice, "a cancelled sentence didn't finish")
                        r = runningLocked(voice, argv) ?: return@withContext null
                    }
                    sentence(voice, r, text, out, timeoutMs)
                }
            } finally {
                if (r.writing != out) out.delete()   // else Piper still writes it: deleted once it is done
                idleLocked(voice, r)
            }
        }

    /**
     * Starts the resident Piper for [voice] with [argv] if it isn't running, so the model is loaded by the time
     * the first sentence comes, and restarts its idle time. False when it can't be started.
     */
    internal suspend fun warm(voice: String, argv: List<String>): Boolean = lock.withLock {
        val r = runningLocked(voice, argv) ?: return@withLock false
        idleLocked(voice, r)
        true
    }

    /** The running process for [voice], replaced when [argv] changed or it died, started when there is none;
     *  null when it can't start. Its idle timer is stopped: the caller arms it again ([idleLocked]). */
    private fun runningLocked(voice: String, argv: List<String>): Running? {
        running[voice]?.let { r ->
            r.idle?.cancel()
            if (r.argv == argv && r.proc.alive) return r
            stopLocked(voice, if (r.proc.alive) "its settings changed" else null)
        }
        val r = Running(argv, start(argv) ?: return null)
        running[voice] = r
        log.info { "Text to speech: Piper keeps $voice loaded (until ${idleMs / 1000} s without a sentence)" }
        return r
    }

    private fun idleLocked(voice: String, r: Running) {
        if (running[voice] !== r) return
        r.idle?.cancel()
        r.idle = scope.launch {
            delay(idleMs)
            lock.withLock { if (running[voice] === r) stopLocked(voice, "${idleMs / 1000} s without a sentence") }
        }
    }

    /** Waits up to [timeoutMs] for the sentence a cancelled synthesis left [r] writing, and deletes its file.
     *  False when it didn't finish or the process died. */
    private suspend fun finishedLocked(r: Running, timeoutMs: Long): Boolean {
        val f = r.writing ?: return true
        val began = System.nanoTime()
        while (r.proc.alive && !(f.exists() && !r.proc.holds(f))) {
            if ((System.nanoTime() - began) / 1_000_000 >= timeoutMs) return false
            delay(pollMs)
        }
        f.delete()
        r.writing = null
        return r.proc.alive
    }

    private suspend fun sentence(voice: String, r: Running, text: String, out: File, timeoutMs: Long): TtsOutcome? {
        r.writing = out
        if (!r.proc.write(jsonLine(text, out))) {
            val why = r.proc.stderrTail().trim().takeLast(300)
            stopLocked(voice, null)
            return TtsOutcome.Failed("piper stopped" + if (why.isEmpty()) "" else ": $why")
        }
        val began = System.nanoTime()
        val appearBy = if (r.warm) warmAppearMs else coldAppearMs
        while (true) {
            val elapsed = (System.nanoTime() - began) / 1_000_000
            val exists = out.exists()
            if (exists && !r.proc.holds(out) && r.proc.alive) break
            if (!r.proc.alive) {
                val why = r.proc.stderrTail().trim().takeLast(300)
                stopLocked(voice, null)
                return TtsOutcome.Failed("piper exited" + if (why.isEmpty()) "" else ": $why")
            }
            if (!exists && elapsed >= appearBy) {
                log.warn { "Text to speech: ${r.argv.firstOrNull { it.endsWith("piper_exe") } ?: "piper"} wrote no file for a JSON line; one process per sentence from now on" }
                unsupported[r.argv.programKey()] = true
                stopLocked(voice, null)
                return null
            }
            if (elapsed >= timeoutMs) {
                stopLocked(voice, null)
                return TtsOutcome.Failed("piper took longer than ${timeoutMs / 1000} s")
            }
            delay(pollMs)
        }
        r.writing = null
        r.warm = true
        return Wav.decode(out.readBytes())?.let { (pcm, rate) -> TtsOutcome.Audio(pcm, rate) }
            ?: TtsOutcome.Failed("piper gave back no WAV")
    }

    /** Stops the residents of voices not in [voices] (the config changed). Skipped while a sentence runs: the
     *  next one replaces a stale process anyway. */
    internal fun retain(voices: Set<String>) {
        if (!lock.tryLock()) return
        try {
            running.keys.filter { it !in voices }.forEach { stopLocked(it, "it is no longer in use") }
        } finally {
            lock.unlock()
        }
    }

    /** Whether a process for [voice] is running (tests). */
    internal fun isRunning(voice: String): Boolean = running[voice]?.proc?.alive == true

    private fun stopLocked(voice: String, why: String?) {
        val r = running.remove(voice) ?: return
        r.idle?.cancel()
        r.proc.kill()
        r.writing?.delete()
        if (why != null) log.info { "Text to speech: Piper unloaded $voice ($why)" }
    }

    companion object {
        /** Programs that showed they don't take JSON lines (or failed to), by path and modification time. */
        private val unsupported = ConcurrentHashMap<String, Boolean>()
        private val helpChecks = ConcurrentHashMap<String, Boolean>()

        private fun List<String>.programKey(): String =
            firstOrNull { File(it).name == "piper_exe" }?.let { "$it@${File(it).lastModified()}" } ?: ""

        /** Whether [program] can stay loaded: libpiper's `piper_exe` whose `--help` offers `--json-input`.
         *  Other Pipers (the Python one, older builds) read one sentence per process. Asked once per file. */
        internal fun canServe(program: File): Boolean {
            if (program.name != "piper_exe") return false
            val key = "${program.path}@${program.lastModified()}"
            if (unsupported[key] == true) return false
            return helpChecks.getOrPut(key) {
                val help = runProcess(listOf(program.path, "--help"), 10_000, redirectErrorStream = true)?.stdout.orEmpty()
                "json-input" in help || "json_input" in help
            }
        }

        /** The command line of a resident Piper: [model], JSON lines in, the speed as its length scale. `-d` is
         *  where a line without `output_file` would go (none does). */
        internal fun argv(prefix: List<String>, program: String, model: File, dir: File, speedPct: Int): List<String> =
            prefix + listOf(program, "-m", model.path, "--json-input", "-d", dir.path,
                "--length_scale", String.format(Locale.ROOT, "%.2f", 100.0 / speedPct.coerceIn(50, 200)))

        /** One sentence for `--json-input`: the text and the file to write. */
        internal fun jsonLine(text: String, out: File): String = buildJsonObject {
            put("text", text)
            put("output_file", out.path)
        }.toString()
    }
}

/** Starts [argv] with stdin open, stdout discarded and the tail of stderr kept, or null (logged). */
internal fun startEngineProcess(argv: List<String>): EngineProcess? = try {
    ProcessEngine(ProcessBuilder(argv).redirectOutput(ProcessBuilder.Redirect.DISCARD).start())
} catch (e: IOException) {
    log.warn { "cannot run '${argv.firstOrNull()}': ${e.message}" }
    null
}

/** A real child process; [holds] reads its open files from `/proc/<pid>/fd`. */
private class ProcessEngine(private val p: Process) : EngineProcess {
    private val stdin = p.outputStream.bufferedWriter(Charsets.UTF_8)
    private val err = StringBuilder()

    init {
        Thread.ofVirtual().start {
            runCatching {
                p.errorStream.bufferedReader(Charsets.UTF_8).use { r ->
                    val buf = CharArray(1024)
                    while (true) {
                        val n = r.read(buf)
                        if (n < 0) break
                        synchronized(err) {
                            err.appendRange(buf, 0, n)
                            if (err.length > STDERR_TAIL) err.deleteRange(0, err.length - STDERR_TAIL)
                        }
                    }
                }
            }
        }
    }

    override val alive: Boolean get() = p.isAlive

    override fun write(line: String): Boolean = synchronized(stdin) {
        try {
            stdin.write(line)
            stdin.write("\n")
            stdin.flush()
            true
        } catch (e: IOException) {
            false
        }
    }

    override fun holds(file: File): Boolean {
        // The kernel shows the resolved path: compare with the file's real path, not one through a symlink.
        val target = runCatching { file.toPath().toRealPath() }.getOrElse { file.toPath().toAbsolutePath() }
        return try {
            Files.list(Path.of("/proc/${p.pid()}/fd")).use { fds ->
                fds.anyMatch { fd -> runCatching { Files.readSymbolicLink(fd) == target }.getOrDefault(false) }
            }
        } catch (e: IOException) {
            false   // exited meanwhile
        }
    }

    override fun stderrTail(): String = synchronized(err) { err.toString() }

    override fun kill() {
        runCatching { stdin.close() }
        p.descendants().forEach { it.destroyForcibly() }
        p.destroyForcibly()
    }
}
