package de.yoxcu.stoandl.voice

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Duration

private val log = KotlinLogging.logger {}

/** Which program runs a model: whisper.cpp's two CLIs (Alpine/postmarketOS `whisper.cpp`, Arch `whisper-cpp`). */
enum class SpeechEngine(val program: String) { PARAKEET("parakeet-cli"), WHISPER("whisper-cli") }

/** A speech model stoandl knows how to fetch and run. Sizes and quality from the October 2026 survey
 *  (docs/voice.md). */
data class SpeechModel(
    val id: String,
    val name: String,
    val engine: SpeechEngine,
    val file: String,
    val url: String,
    val sizeMb: Int,
    val note: String,
    val recommended: Boolean = false,
)

/** The catalog, best choice first. Parakeet detects the language itself; Whisper takes `voice.language`. */
val SPEECH_MODELS: List<SpeechModel> = listOf(
    SpeechModel(
        "parakeet-v3", "Parakeet v3", SpeechEngine.PARAKEET, "ggml-parakeet-tdt-0.6b-v3-q8_0.bin",
        "https://huggingface.co/ggml-org/parakeet-GGUF/resolve/main/ggml-parakeet-tdt-0.6b-v3-q8_0.bin", 669,
        "25 European languages, detects the language; best accuracy for its speed (phones, older PCs)", recommended = true,
    ),
    SpeechModel(
        "parakeet-v3-small", "Parakeet v3 (smaller)", SpeechEngine.PARAKEET, "ggml-parakeet-tdt-0.6b-v3-q4_0.bin",
        "https://huggingface.co/ggml-org/parakeet-GGUF/resolve/main/ggml-parakeet-tdt-0.6b-v3-q4_0.bin", 356,
        "the same model compressed further: less memory, slightly less accurate",
    ),
    SpeechModel(
        "whisper-small", "Whisper small", SpeechEngine.WHISPER, "ggml-small-q8_0.bin",
        "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-small-q8_0.bin", 264,
        "99 languages, set voice.language; slower and less accurate than Parakeet",
    ),
    SpeechModel(
        "whisper-base", "Whisper base", SpeechEngine.WHISPER, "ggml-base-q8_0.bin",
        "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-base-q8_0.bin", 82,
        "fast and small, weak for languages other than English",
    ),
    SpeechModel(
        "whisper-turbo", "Whisper large-v3-turbo", SpeechEngine.WHISPER, "ggml-large-v3-turbo-q8_0.bin",
        "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-large-v3-turbo-q8_0.bin", 874,
        "most accurate, for fast desktops only",
    ),
)

/**
 * The speech models on this machine, under `$XDG_DATA_HOME/stoandl/stt` (`~/.local/share/stoandl/stt`):
 * which of the catalog's are installed, downloading one on request (an explicit user action: the GUI's
 * Download button or `stoandl voice download`), and the command line that runs one. One download at a
 * time; its progress is part of [records].
 */
class VoiceModels(private val scope: CoroutineScope, val dir: File = defaultDir()) {
    private val http: HttpClient by lazy {
        HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(Duration.ofSeconds(20)).build()
    }

    private class Download(val model: SpeechModel, val job: Job) {
        @Volatile var received = 0L
        @Volatile var total = model.sizeMb * 1_000_000L
        @Volatile var error: String? = null
    }

    @Volatile private var download: Download? = null

    fun model(id: String): SpeechModel? = SPEECH_MODELS.firstOrNull { it.id == id }

    fun isInstalled(m: SpeechModel): Boolean = File(dir, m.file).isFile

    fun installed(): List<SpeechModel> = SPEECH_MODELS.filter(::isInstalled)

    /** Whether [engine]'s program is on PATH. */
    fun engineAvailable(engine: SpeechEngine): Boolean = onPath(engine.program) != null

    /**
     * `id\tname\tengine\tsizeMb\tstate\trecommended\tengineOk\tnote` per catalog model; state ∈ installed,
     * available, downloading:<percent>, failed:<message>.
     */
    fun records(): List<String> = SPEECH_MODELS.map { m ->
        val d = download?.takeIf { it.model.id == m.id }
        val state = when {
            d != null && d.error != null -> "failed:${d.error}"
            d != null && d.job.isActive -> "downloading:${if (d.total > 0) (d.received * 100 / d.total).coerceIn(0, 99) else 0}"
            isInstalled(m) -> "installed"
            else -> "available"
        }
        listOf(m.id, m.name, m.engine.program, m.sizeMb.toString(), state, m.recommended.toString(),
            engineAvailable(m.engine).toString(), m.note).joinToString("\t")
    }

    /** Start downloading [id]; [onDone] runs after it is in place. `ok:`, `busy:`, `notfound:` or `installed:`. */
    @Synchronized
    fun startDownload(id: String, onDone: (SpeechModel) -> Unit): String {
        val m = model(id) ?: return "notfound:no model '$id' (stoandl voice models)"
        if (isInstalled(m)) { onDone(m); return "installed:${m.name} is already installed" }
        download?.takeIf { it.job.isActive }?.let { return "busy:${it.model.name} is downloading" }
        lateinit var d: Download
        val job = scope.launch(Dispatchers.IO, start = kotlinx.coroutines.CoroutineStart.LAZY) {
            try {
                fetch(m, d)
                onDone(m)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                d.error = (e.message ?: e.javaClass.simpleName).replace('\t', ' ').replace('\n', ' ')
                log.warn { "Voice model ${m.name}: download failed: ${d.error}" }
            }
        }
        d = Download(m, job)
        download = d
        job.start()
        log.info { "Voice model ${m.name}: downloading ${m.sizeMb} MB from ${URI(m.url).host}" }
        return "ok:downloading ${m.name} (${m.sizeMb} MB)"
    }

    fun cancelDownload(): String {
        val d = download?.takeIf { it.job.isActive } ?: return "none:no download running"
        d.job.cancel()
        download = null
        return "ok:cancelled ${d.model.name}"
    }

    fun remove(id: String): String {
        val m = model(id) ?: return "notfound:no model '$id'"
        return if (File(dir, m.file).delete()) "ok:removed ${m.name}" else "notfound:${m.name} isn't installed"
    }

    private fun fetch(m: SpeechModel, d: Download) {
        dir.mkdirs()
        val part = File(dir, m.file + ".part")
        val response = http.send(HttpRequest.newBuilder(URI(m.url)).timeout(Duration.ofMinutes(60)).build(),
            HttpResponse.BodyHandlers.ofInputStream())
        check(response.statusCode() in 200..299) { "HTTP ${response.statusCode()}" }
        response.headers().firstValueAsLong("Content-Length").ifPresent { if (it > 0) d.total = it }
        response.body().use { input ->
            part.outputStream().use { out ->
                val buf = ByteArray(1 shl 16)
                while (true) {
                    if (!scope.isActive || !d.job.isActive) error("cancelled")
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    d.received += n
                }
            }
        }
        check(d.received >= d.total * 9 / 10) { "incomplete download (${d.received / 1_000_000} of ${d.total / 1_000_000} MB)" }
        Files.move(part.toPath(), File(dir, m.file).toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        log.info { "Voice model ${m.name}: installed (${d.received / 1_000_000} MB)" }
    }

    /** The shell command line that runs [m] on `{wav}` (`{lang}` for Whisper). CPU only (`-ng`): distro builds
     *  enable Vulkan, which aborts loading the model on GPUs without 16-bit storage (the OnePlus 6's Adreno 630),
     *  and a short dictation gains little from a GPU (custom command backend to use one). Pinned to the fast
     *  cores on a big.LITTLE CPU (a phone), where the slow cores would otherwise set the pace. */
    fun command(m: SpeechModel): String {
        val threads = minOf(4, Runtime.getRuntime().availableProcessors()).coerceAtLeast(1)
        val path = shellQuote(File(dir, m.file).absolutePath)
        val run = when (m.engine) {
            SpeechEngine.PARAKEET -> "parakeet-cli -m $path -t $threads -ng --no-prints -f {wav}"
            SpeechEngine.WHISPER -> "whisper-cli -m $path -l {lang} -t $threads -ng -ac 768 -bs 1 -bo 1 -nf -mc 0 -nt -np -f {wav}"
        }
        val big = bigCores()
        return if (big != null && onPath("taskset") != null) "taskset -c $big $run" else run
    }

    companion object {
        fun defaultDir(): File {
            val xdg = System.getenv("XDG_DATA_HOME")?.takeIf { it.isNotBlank() }
            return File(xdg ?: (System.getProperty("user.home") + "/.local/share"), "stoandl/stt")
        }

        internal fun onPath(program: String): File? =
            System.getenv("PATH").orEmpty().split(':').map { File(it, program) }.firstOrNull { it.canExecute() }

        internal fun shellQuote(s: String) = "'" + s.replace("'", "'\\''") + "'"

        /** The CPUs with the highest max frequency as a taskset list, when they are a strict subset of all. */
        internal fun bigCores(sysCpu: File = File("/sys/devices/system/cpu")): String? {
            val freqs = (sysCpu.listFiles { f -> f.name.matches(Regex("cpu\\d+")) } ?: return null).mapNotNull { c ->
                File(c, "cpufreq/cpuinfo_max_freq").takeIf { it.isFile }?.readText()?.trim()?.toLongOrNull()
                    ?.let { c.name.removePrefix("cpu").toInt() to it }
            }
            if (freqs.size < 2) return null
            val top = freqs.maxOf { it.second }
            val big = freqs.filter { it.second == top }.map { it.first }.sorted()
            return if (big.size in 1 until freqs.size) big.joinToString(",") else null
        }
    }
}
