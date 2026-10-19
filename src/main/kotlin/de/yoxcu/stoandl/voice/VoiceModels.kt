package de.yoxcu.stoandl.voice

import de.yoxcu.stoandl.util.onPath
import de.yoxcu.stoandl.util.runProcess
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
import java.util.concurrent.ConcurrentHashMap

private val log = KotlinLogging.logger {}

/** What a model is for: speech to text (`voice.local_model`) or text to speech (`tts.local_voice`). Each kind
 *  has its own directory and its own setting; the token is the D-Bus/CLI spelling. */
enum class ModelKind {
    STT, TTS;

    val token: String get() = name.lowercase()
}

/** Which program runs a model: whisper.cpp's two CLIs (Alpine/postmarketOS `whisper.cpp`, Arch `whisper-cpp`),
 *  or Piper (`piper_exe` from libpiper, `piper-tts`, else the Python `piper`; see [programPath]). [install]
 *  names the package to install. */
enum class SpeechEngine(val program: String, val kind: ModelKind, val install: String) {
    PARAKEET("parakeet-cli", ModelKind.STT, "whisper.cpp"),
    WHISPER("whisper-cli", ModelKind.STT, "whisper.cpp"),
    PIPER("piper", ModelKind.TTS, "piper-tts");

    /** The installed program that runs this engine, or null. Piper prefers libpiper's `piper_exe`: native, it
     *  starts faster than the Python `piper` (same flags, text on stdin). `piper-tts` is the name some distro
     *  packages use; a bare `piper` counts only once [isPiperTts] says it isn't libratbag's mouse tool. */
    fun programPath(): File? = when (this) {
        PIPER -> onPath("piper_exe") ?: onPath("piper-tts") ?: onPath("piper")?.takeIf(::isPiperTts)
        else -> onPath(program)
    }
}

/** [isPiperTts]'s answers, by path and modification time: it runs the program. */
private val piperChecks = ConcurrentHashMap<String, Boolean>()

/**
 * Whether [program] (a `piper` on PATH) is Piper text to speech. The name is also libratbag's gaming-mouse
 * configurator (Arch extra, Alpine community); Piper's `--help` names its length scale, the mouse tool's
 * doesn't. Asked once per file, for at most 10 s.
 */
internal fun isPiperTts(program: File): Boolean = piperChecks.getOrPut("${program.path}@${program.lastModified()}") {
    val help = runProcess(listOf(program.path, "--help"), 10_000, redirectErrorStream = true)?.stdout.orEmpty()
    ("length_scale" in help || "length-scale" in help).also { if (!it) log.info { "${program.path} isn't Piper text to speech" } }
}

/**
 * A model stoandl knows how to fetch and run: a speech-to-text model (one file) or a Piper voice (the `.onnx`
 * and its `.onnx.json`). [urls]' last path segments are the file names; the first is the one the engine
 * loads. [lang] is the language a voice speaks (`de`, `en`), empty for a multilingual STT model. Sizes and
 * quality from the October 2026 survey (docs/voice.md).
 */
data class SpeechModel(
    val id: String,
    val name: String,
    val engine: SpeechEngine,
    val urls: List<String>,
    val sizeMb: Int,
    val note: String,
    val recommended: Boolean = false,
    val lang: String = "",
) {
    val kind: ModelKind get() = engine.kind
    val files: List<String> get() = urls.map { it.substringAfterLast('/') }
    /** The file the engine loads. */
    val file: String get() = files.first()
}

private const val PIPER_VOICES = "https://huggingface.co/rhasspy/piper-voices/resolve/main"

/** A Piper voice from rhasspy/piper-voices: [path] is `<lang>/<locale>/<speaker>/<quality>/<voice>`. */
private fun piper(id: String, name: String, path: String, sizeMb: Int, lang: String, note: String, recommended: Boolean = false) =
    SpeechModel(id, name, SpeechEngine.PIPER, listOf("$PIPER_VOICES/$path.onnx", "$PIPER_VOICES/$path.onnx.json"),
        sizeMb, note, recommended, lang)

/**
 * The catalog, best choice first within each kind. Parakeet detects the language itself; Whisper takes
 * `voice.language`. Voices: a "low" one per language, fast and plenty for the watch speaker's 8 kHz;
 * "medium" ones sound better on this device's speakers but take longer to make, and like any voice a
 * downloaded one becomes `tts.local_voice`, the watch's voice for its language too. Only voices whose
 * training data may be used freely (the dataset licence is in each note; lessac's Blizzard licence is
 * unclear and ryan's is non-commercial).
 */
val SPEECH_MODELS: List<SpeechModel> = listOf(
    SpeechModel(
        "parakeet-v3", "Parakeet v3", SpeechEngine.PARAKEET,
        listOf("https://huggingface.co/ggml-org/parakeet-GGUF/resolve/main/ggml-parakeet-tdt-0.6b-v3-q8_0.bin"), 669,
        "25 European languages, detects the language; best accuracy for its speed (phones, older PCs)", recommended = true,
    ),
    SpeechModel(
        "parakeet-v3-small", "Parakeet v3 (smaller)", SpeechEngine.PARAKEET,
        listOf("https://huggingface.co/ggml-org/parakeet-GGUF/resolve/main/ggml-parakeet-tdt-0.6b-v3-q4_0.bin"), 356,
        "the same model compressed further: less memory, slightly less accurate",
    ),
    SpeechModel(
        "whisper-small", "Whisper small", SpeechEngine.WHISPER,
        listOf("https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-small-q8_0.bin"), 264,
        "99 languages, set voice.language; slower and less accurate than Parakeet",
    ),
    SpeechModel(
        "whisper-base", "Whisper base", SpeechEngine.WHISPER,
        listOf("https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-base-q8_0.bin"), 82,
        "fast and small, weak for languages other than English",
    ),
    SpeechModel(
        "whisper-turbo", "Whisper large-v3-turbo", SpeechEngine.WHISPER,
        listOf("https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-large-v3-turbo-q8_0.bin"), 874,
        "most accurate, for fast desktops only",
    ),
    piper("piper-de-thorsten-low", "Thorsten (German)", "de/de_DE/thorsten/low/de_DE-thorsten-low", 63, "de",
        "German, male; low quality, fast: enough for the watch speaker. Thorsten-Voice dataset, CC0", recommended = true),
    piper("piper-en-kathleen-low", "Kathleen (English)", "en/en_US/kathleen/low/en_US-kathleen-low", 63, "en",
        "US English, female; low quality, fast: enough for the watch speaker. Kathleen dataset, CC0", recommended = true),
    piper("piper-de-eva_k-x_low", "Eva K (German, smallest)", "de/de_DE/eva_k/x_low/de_DE-eva_k-x_low", 21, "de",
        "German, female; the smallest and fastest, rougher. M-AILABS dataset (BSD-style licence)"),
    piper("piper-de-thorsten-medium", "Thorsten (German, medium)", "de/de_DE/thorsten/medium/de_DE-thorsten-medium", 63, "de",
        "German, male; 22 kHz, clearer on this device's speakers, slower to make (the watch waits longer). Thorsten-Voice dataset, CC0"),
    piper("piper-en-ljspeech-medium", "LJSpeech (English, medium)", "en/en_US/ljspeech/medium/en_US-ljspeech-medium", 64, "en",
        "US English, female; 22 kHz, clearer on this device's speakers, slower to make (the watch waits longer). LJ Speech dataset, public domain"),
)

/** The `tts.local_voice` that runs espeak-ng: no download, every language, robotic. Offered while
 *  `espeak-ng` is on PATH; not a catalog row. */
const val ESPEAK_VOICE = "espeak-ng"

/**
 * The speech models and voices on this machine, under `$XDG_DATA_HOME/stoandl/<kind>`
 * (`~/.local/share/stoandl/stt` and `…/tts`): which of the catalog's are installed, downloading one on
 * request (an explicit user action: the GUI's Download button or `stoandl voice download`), and the command
 * line that runs a speech-to-text model. One download at a time, whatever its kind; its progress is part of
 * [records].
 */
class VoiceModels(
    private val scope: CoroutineScope,
    val root: File = defaultRoot(),
    private val catalog: List<SpeechModel> = SPEECH_MODELS,
) {
    private val http: HttpClient by lazy {
        HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(Duration.ofSeconds(20)).build()
    }

    private class Download(val model: SpeechModel, val job: Job) {
        @Volatile var received = 0L
        @Volatile var total = model.sizeMb * 1_000_000L
        @Volatile var error: String? = null
    }

    @Volatile private var download: Download? = null

    // Ask a bare `piper` what it is now, not in the first status or catalog call (a Python start: seconds).
    init { scope.launch(Dispatchers.IO) { SpeechEngine.PIPER.programPath() } }

    fun model(id: String): SpeechModel? = catalog.firstOrNull { it.id == id }

    /** Where [kind]'s files live. */
    fun dir(kind: ModelKind): File = File(root, kind.token)

    /** The file [m]'s engine loads. */
    fun path(m: SpeechModel): File = File(dir(m.kind), m.file)

    fun isInstalled(m: SpeechModel): Boolean = isInstalled(m, root)

    fun installed(kind: ModelKind): List<SpeechModel> = catalog.filter { it.kind == kind && isInstalled(it) }

    /** Whether [engine]'s program is installed. */
    fun engineAvailable(engine: SpeechEngine): Boolean = engine.programPath() != null

    /**
     * `id\tname\tengine\tsizeMb\tstate\trecommended\tengineOk\tnote\tkind` per catalog model; state ∈
     * installed, available, downloading:<percent>, failed:<message>; kind ∈ stt, tts.
     */
    fun records(): List<String> = catalog.map { m ->
        val d = download?.takeIf { it.model.id == m.id }
        val state = when {
            d != null && d.error != null -> "failed:${d.error}"
            d != null && d.job.isActive -> "downloading:${if (d.total > 0) (d.received * 100 / d.total).coerceIn(0, 99) else 0}"
            isInstalled(m) -> "installed"
            else -> "available"
        }
        listOf(m.id, m.name, m.engine.program, m.sizeMb.toString(), state, m.recommended.toString(),
            engineAvailable(m.engine).toString(), m.note, m.kind.token).joinToString("\t")
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
        log.info { "Voice model ${m.name}: downloading ${m.sizeMb} MB from ${URI(m.urls.first()).host}" }
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
        val removed = m.files.map { File(dir(m.kind), it).delete() }.any { it }
        return if (removed) "ok:removed ${m.name}" else "notfound:${m.name} isn't installed"
    }

    /** Each of [m]'s files to a `.part` first; all are moved into place only when every one arrived, the
     *  loaded file last, so [isInstalled] never sees half a voice. */
    private fun fetch(m: SpeechModel, d: Download) {
        val dir = dir(m.kind).apply { mkdirs() }
        val parts = m.files.map { File(dir, "$it.part") }
        try {
            m.urls.forEachIndexed { i, url -> fetchFile(url, parts[i], d, main = i == 0) }
            parts.indices.reversed().forEach { i ->
                Files.move(parts[i].toPath(), File(dir, m.files[i]).toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            }
        } finally {
            parts.forEach { it.delete() }
        }
        log.info { "Voice model ${m.name}: installed (${d.received / 1_000_000} MB)" }
    }

    /** One file; [main] is the big one the progress and the size check go by (a sidecar is a few KB). */
    private fun fetchFile(url: String, part: File, d: Download, main: Boolean) {
        val response = http.send(HttpRequest.newBuilder(URI(url)).timeout(Duration.ofMinutes(60)).build(),
            HttpResponse.BodyHandlers.ofInputStream())
        check(response.statusCode() in 200..299) { "HTTP ${response.statusCode()}" }
        val length = response.headers().firstValueAsLong("Content-Length").orElse(-1)
        if (main && length > 0) d.total = length
        var got = 0L
        response.body().use { input ->
            part.outputStream().use { out ->
                val buf = ByteArray(1 shl 16)
                while (true) {
                    if (!scope.isActive || !d.job.isActive) error("cancelled")
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    got += n
                    d.received += n
                }
            }
        }
        val expected = if (length > 0) length else if (main) d.total else 1
        check(got >= expected * 9 / 10) { "incomplete download (${got / 1_000_000} of ${expected / 1_000_000} MB)" }
    }

    /** The shell command line that runs speech-to-text model [m] on `{wav}` (`{lang}` for Whisper). CPU only (`-ng`): distro builds
     *  enable Vulkan, which aborts loading the model on GPUs without 16-bit storage (the OnePlus 6's Adreno 630),
     *  and a short dictation gains little from a GPU (custom command backend to use one). Pinned to the fast
     *  cores on a big.LITTLE CPU (a phone), where the slow cores would otherwise set the pace. */
    fun command(m: SpeechModel): String {
        val threads = minOf(4, Runtime.getRuntime().availableProcessors()).coerceAtLeast(1)
        val path = shellQuote(path(m).absolutePath)
        val run = when (m.engine) {
            SpeechEngine.PARAKEET -> "parakeet-cli -m $path -t $threads -ng --no-prints -f {wav}"
            SpeechEngine.WHISPER -> "whisper-cli -m $path -l {lang} -t $threads -ng -ac 768 -bs 1 -bo 1 -nf -mc 0 -nt -np -f {wav}"
            SpeechEngine.PIPER -> error("${m.id} is a text-to-speech voice")
        }
        return (bigCoresPrefix() + run).joinToString(" ")
    }

    companion object {
        fun defaultRoot(): File {
            val xdg = System.getenv("XDG_DATA_HOME")?.takeIf { it.isNotBlank() }
            return File(xdg ?: (System.getProperty("user.home") + "/.local/share"), "stoandl")
        }

        fun isInstalled(m: SpeechModel, root: File = defaultRoot()): Boolean =
            m.files.all { File(root, "${m.kind.token}/$it").isFile }

        /**
         * What `voice.local_model` (STT) or `tts.local_voice` (TTS) can be set to, as (id, display name): the
         * installed catalog entries of [kind], and for TTS [ESPEAK_VOICE] while espeak-ng is installed.
         */
        fun choices(kind: ModelKind, root: File = defaultRoot()): List<Pair<String, String>> =
            SPEECH_MODELS.filter { it.kind == kind && isInstalled(it, root) }.map { it.id to it.name } +
                if (kind == ModelKind.TTS && onPath(ESPEAK_VOICE) != null) listOf(ESPEAK_VOICE to "espeak-ng (robotic, every language)") else emptyList()

        /** `taskset -c <big cores>` for a command that should run on the fast cores of a big.LITTLE CPU (a
         *  phone), where the slow ones would otherwise set the pace; empty elsewhere or without taskset. */
        fun bigCoresPrefix(): List<String> {
            val big = bigCores() ?: return emptyList()
            return if (onPath("taskset") != null) listOf("taskset", "-c", big) else emptyList()
        }

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
