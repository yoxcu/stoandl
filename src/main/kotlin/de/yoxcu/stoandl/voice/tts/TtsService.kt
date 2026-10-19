package de.yoxcu.stoandl.voice.tts

import de.yoxcu.stoandl.util.CacheDirs
import de.yoxcu.stoandl.util.onPath
import de.yoxcu.stoandl.util.runProcess
import de.yoxcu.stoandl.voice.Wav
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

private val log = KotlinLogging.logger {}

/** The longest text read in one go; the rest is dropped. An answer's sentence is far shorter. */
internal const val MAX_TTS_CHARS = 2_000
/** The engine deadline for host playback, which nobody else waits on. */
private const val SPEAK_TIMEOUT_MS = 60_000L
/** The engine deadline for an extension's `synthesize`: inside the 30 s the assistant waits for an answer. */
private const val SYNTHESIZE_TIMEOUT_MS = 25_000L
/** How long a `synthesize` WAV file stays for its extension to read. */
private const val WAV_KEEP_MS = 10 * 60_000L

/** The programs that play a WAV file on this device, in order of preference (PipeWire, PulseAudio, ALSA). */
internal val HOST_PLAYERS = listOf("pw-play", "paplay", "aplay")

/** What an extension's `synthesize` gets back. */
sealed interface Synthesis {
    /** `pebble-adpcm`: the blocks for the watch speaker (docs/speaker-stream.md) and the audio's length. */
    class Blocks(val blocks: List<ByteArray>, val ms: Long) : Synthesis
    /** `wav`: a 16-bit WAV at the engine's rate, kept for [WAV_KEEP_MS]. */
    class WavFile(val path: String, val ms: Long) : Synthesis
    /** The JSON-RPC error message: `off`, `unavailable: <why>`, `busy`, or what went wrong. */
    data class Error(val message: String) : Synthesis
}

/** [text] as one line of at most [MAX_TTS_CHARS] characters: an engine reads stdin line by line. */
internal fun ttsText(text: String): String = text.replace(Regex("\\s+"), " ").trim().take(MAX_TTS_CHARS)

/**
 * Text to speech for its two consumers: D-Bus `Speak` plays on this device, an extension's `synthesize`
 * (watch speaker) gets the audio back. One synthesis at a time (on a phone one engine already takes the fast
 * cores); the system stays awake while one runs or plays ([keepAwake]).
 */
class TtsService(
    private val backends: TtsBackends,
    private val scope: CoroutineScope,
    private val keepAwake: (why: String) -> AutoCloseable = { AutoCloseable {} },
    private val players: List<String> = HOST_PLAYERS,
) {
    private val engine = Mutex()
    @Volatile private var speaking: Job? = null

    fun status(): String = backends.status()

    /**
     * [text] from the configured engine, in [lang] when given. [wait]: queue behind a running synthesis,
     * else refuse it with `busy:`. Returns the audio, or `off:`, `unavailable:<why>`, `busy:` or `error:<msg>`.
     */
    internal suspend fun render(text: String, lang: String?, wait: Boolean, timeoutMs: Long): Pair<TtsOutcome.Audio?, String> {
        val tts = backends.current(lang) ?: return null to "off:"
        tts.readiness()?.let { return null to "unavailable:$it" }
        val line = ttsText(text)
        if (line.isEmpty()) return null to "error:no text"
        if (!wait && !engine.tryLock()) return null to "busy:"
        if (wait) engine.lock()
        try {
            val start = System.nanoTime()
            val outcome = keepAwake("Text to speech").use { tts.synthesize(line, lang, backends.speedPct(), timeoutMs) }
            val ms = (System.nanoTime() - start) / 1_000_000
            return when (outcome) {
                is TtsOutcome.Audio -> {
                    log.info { "Text to speech (${tts.id}): ${line.length} characters → ${outcome.ms} ms of audio in $ms ms" }
                    outcome to "ok:"
                }
                is TtsOutcome.Failed -> {
                    log.warn { "Text to speech (${tts.id}): ${outcome.message}" }
                    null to "error:${outcome.message}"
                }
            }
        } finally {
            engine.unlock()
        }
    }

    /**
     * An extension's `synthesize`: [text] in [language] as [format] (`pebble-adpcm` or `wav`). Refuses with
     * `busy` while another synthesis runs rather than queueing: the caller is pacing a reading and would
     * rather know.
     */
    suspend fun synthesize(text: String, language: String?, format: String): Synthesis {
        if (format != "pebble-adpcm" && format != "wav") return Synthesis.Error("unsupported format '$format' (pebble-adpcm or wav)")
        val (audio, status) = render(text, language, wait = false, timeoutMs = SYNTHESIZE_TIMEOUT_MS)
        if (audio == null) {
            val (kind, why) = status.substringBefore(':') to status.substringAfter(':')
            return Synthesis.Error(when (kind) {
                "off", "busy" -> kind
                "unavailable" -> "unavailable: $why"
                else -> why
            })
        }
        return withContext(Dispatchers.Default) {
            if (format == "wav") {
                val dir = CacheDirs.runtime("tts")
                dir.listFiles { f -> f.name.startsWith("synth-") && f.lastModified() < System.currentTimeMillis() - WAV_KEEP_MS }
                    ?.forEach { it.delete() }
                val file = File(dir, "synth-${UUID.randomUUID()}.wav")
                file.writeBytes(Wav.encode(audio.samples, audio.sampleRate))
                Synthesis.WavFile(file.path, audio.ms)
            } else {
                val (blocks, ms) = WatchSpeakerEncoding.encode(audio.samples, audio.sampleRate)
                Synthesis.Blocks(blocks, ms)
            }
        }
    }

    /**
     * D-Bus `Speak`: read [text] aloud on this device through the first of [players] installed. A new call
     * stops the one before. Blocks until the audio is ready, then plays it in the background:
     * `ok:<ms of audio>`, `off:`, `unavailable:<why>`, `error:<msg>`, or `stopped:` when stopped first.
     */
    fun speak(text: String): String {
        val player = players.firstNotNullOfOrNull { onPath(it) }
            ?: return "unavailable:no audio player found (${players.joinToString(", ")})"
        val reply = CompletableDeferred<String>()
        val job = scope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
            keepAwake("Reading text aloud").use {
                val (audio, status) = render(text, null, wait = true, timeoutMs = SPEAK_TIMEOUT_MS)
                if (audio == null) { reply.complete(status); return@launch }
                val file = ttsScratchWav()
                try {
                    file.writeBytes(Wav.encode(audio.samples, audio.sampleRate))
                    reply.complete("ok:${audio.ms}")
                    val r = runInterruptible { runProcess(listOf(player.path, file.path), audio.ms + 10_000) }
                    if (r != null && r.exitCode != 0) log.warn { "${player.name} exited ${r.exitCode}: ${r.stderr.trim().takeLast(200)}" }
                } finally {
                    file.delete()
                }
            }
        }
        job.invokeOnCompletion { reply.complete("stopped:") }
        val before = synchronized(this) { speaking.also { speaking = job } }
        before?.cancel()
        job.start()
        return runBlocking { reply.await() }
    }

    /** D-Bus `StopSpeaking`: `ok:` when something was being read aloud, else `none:`. */
    fun stop(): String {
        val job = synchronized(this) { speaking.also { speaking = null } }
        return if (job != null && job.isActive) { job.cancel(); "ok:stopped" } else "none:nothing is being read aloud"
    }
}
