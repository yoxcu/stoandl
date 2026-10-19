package de.yoxcu.stoandl.voice.tts

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
import kotlinx.coroutines.sync.withLock

private val log = KotlinLogging.logger {}

/** The longest text read in one go; the rest is dropped. An answer's sentence is far shorter. */
internal const val MAX_TTS_CHARS = 2_000
/** The engine deadline for host playback, which nobody else waits on. */
private const val SPEAK_TIMEOUT_MS = 60_000L

/** The programs that play a WAV file on this device, in order of preference (PipeWire, PulseAudio, ALSA). */
internal val HOST_PLAYERS = listOf("pw-play", "paplay", "aplay")

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
