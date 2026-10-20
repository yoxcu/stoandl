package de.yoxcu.stoandl.voice.tts

import de.yoxcu.stoandl.util.CacheDirs
import de.yoxcu.stoandl.util.missingProgram
import de.yoxcu.stoandl.util.onPath
import de.yoxcu.stoandl.util.runProcess
import de.yoxcu.stoandl.voice.ESPEAK_VOICE
import de.yoxcu.stoandl.voice.ModelKind
import de.yoxcu.stoandl.voice.SpeechModel
import de.yoxcu.stoandl.voice.VoiceModels
import de.yoxcu.stoandl.voice.Wav
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.timeout
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.util.Locale
import java.util.UUID

/** What an engine made of the text. */
sealed interface TtsOutcome {
    /** Mono 16-bit speech at [sampleRate]. */
    class Audio(val samples: ShortArray, val sampleRate: Int) : TtsOutcome {
        val ms: Long get() = samples.size * 1000L / sampleRate
    }
    /** The engine failed, timed out or gave back something that isn't a 16-bit WAV. */
    data class Failed(val message: String) : TtsOutcome
}

/**
 * A text-to-speech engine. One interface for every consumer (an extension's `synthesize` for the watch
 * speaker, D-Bus `Speak` on this device), chosen in one place ([TtsBackends]). The text is never logged, only
 * its length.
 */
interface TextToSpeech {
    /** `local` | `command` | `http`: the `tts.backend` it implements. */
    val id: String
    /** Null when ready to use, else why not (cheap: no network, no model load). */
    fun readiness(): String?
    /** [lang] is a language code (`de`, `en`) or null for the engine's default; [speedPct] 100 is normal. */
    suspend fun synthesize(text: String, lang: String?, speedPct: Int, timeoutMs: Long): TtsOutcome
}

/** `de-DE`, `en_US` or `EN` as the primary language subtag (`de`, `en`), or null when it isn't one. */
internal fun primaryLanguage(lang: String?): String? =
    lang?.trim()?.lowercase()?.split('-', '_')?.first()?.takeIf { it.matches(Regex("[a-z]{2,3}")) }

/** A new WAV file for an engine to write, in the per-login scratch dir; the caller deletes it. */
internal fun ttsScratchWav(): File = File(CacheDirs.runtime("tts"), "${UUID.randomUUID()}.wav")

/**
 * Run an engine process with [text] on stdin; its WAV is [out] when given, else its stdout. [out] is deleted
 * afterwards. Cancelling the caller kills the process.
 */
internal suspend fun runEngine(name: String, argv: List<String>, text: String, out: File?, timeoutMs: Long): TtsOutcome =
    runInterruptible(Dispatchers.IO) {
        try {
            val r = runProcess(argv, timeoutMs, stdin = (text + "\n").toByteArray())
                ?: return@runInterruptible TtsOutcome.Failed("can't start $name")
            when {
                r.timedOut -> TtsOutcome.Failed("$name took longer than ${timeoutMs / 1000} s")
                r.exitCode != 0 -> TtsOutcome.Failed("$name exited ${r.exitCode}: ${r.stderr.trim().takeLast(300)}")
                else -> {
                    val wav = if (out != null) out.takeIf { it.isFile }?.readBytes() else r.stdoutBytes
                    wav?.let(Wav::decode)?.let { (pcm, rate) -> TtsOutcome.Audio(pcm, rate) }
                        ?: TtsOutcome.Failed("$name gave back no 16-bit WAV")
                }
            }
        } finally {
            out?.delete()
        }
    }

/**
 * `tts.backend = local` with a downloaded Piper voice: `piper_exe` (libpiper) or the Python `piper`, which take
 * the same flags, on the fast cores of a big.LITTLE CPU. `piper_exe` stays loaded between sentences
 * ([ResidentPiper], when [resident] is given); any other Piper runs one process per sentence, loading the
 * model each time.
 */
class PiperTts(
    private val models: VoiceModels,
    private val voiceId: String,
    private val resident: ResidentPiper? = null,
) : TextToSpeech {
    override val id = "local"
    val voice: SpeechModel? = models.model(voiceId)?.takeIf { it.kind == ModelKind.TTS }

    override fun readiness(): String? = when {
        voice == null -> if (voiceId.isEmpty()) "no voice chosen (tts.local_voice)" else "unknown voice '$voiceId'"
        !models.isInstalled(voice) -> "${voice.name} isn't downloaded yet"
        !models.engineAvailable(voice.engine) -> "piper not found (install ${voice.engine.install})"
        else -> null
    }

    override suspend fun synthesize(text: String, lang: String?, speedPct: Int, timeoutMs: Long): TtsOutcome {
        val v = voice ?: return TtsOutcome.Failed(readiness() ?: "no voice")
        val program = v.engine.programPath() ?: return TtsOutcome.Failed("piper not found")
        if (resident != null && runInterruptible(Dispatchers.IO) { ResidentPiper.canServe(program) }) {
            val argv = ResidentPiper.argv(VoiceModels.bigCoresPrefix(), program.path, models.path(v), CacheDirs.runtime("tts"), speedPct)
            resident.synthesize(v.id, argv, text, ttsScratchWav(), timeoutMs)?.let { return it }
        }
        val out = ttsScratchWav()
        return runEngine("piper", piperArgv(VoiceModels.bigCoresPrefix(), program.path, models.path(v), out, speedPct), text, out, timeoutMs)
    }

    companion object {
        /** Piper reads the text on stdin; the speed is its length scale, 100/speedPct (longer = slower). */
        internal fun piperArgv(prefix: List<String>, program: String, model: File, out: File, speedPct: Int): List<String> =
            prefix + listOf(program, "-m", model.path, "-f", out.path,
                "--length_scale", String.format(Locale.ROOT, "%.2f", 100.0 / speedPct.coerceIn(50, 200)))
    }
}

/** `tts.local_voice = espeak-ng`: no download, every language, robotic. */
class EspeakTts : TextToSpeech {
    override val id = "local"

    override fun readiness(): String? = if (onPath(ESPEAK_VOICE) == null) "espeak-ng not found (install espeak-ng)" else null

    override suspend fun synthesize(text: String, lang: String?, speedPct: Int, timeoutMs: Long): TtsOutcome {
        val out = ttsScratchWav()
        return runEngine(ESPEAK_VOICE, espeakArgv(lang, speedPct, out), text, out, timeoutMs)
    }

    companion object {
        /** UTF-8 text on stdin; 175 words per minute is espeak's normal rate. */
        internal fun espeakArgv(lang: String?, speedPct: Int, out: File): List<String> =
            listOf(ESPEAK_VOICE, "-b", "1", "-s", (175 * speedPct.coerceIn(50, 200) / 100).toString()) +
                (primaryLanguage(lang)?.let { listOf("-v", it) } ?: emptyList()) + listOf("-w", out.path, "--stdin")
    }
}

/**
 * `tts.backend = command`: an engine installed by hand. [template] is a shell command line with `{lang}`
 * filled in (see [commandArgv]); it reads the text on stdin and writes a WAV to stdout.
 */
class CommandTts(private val template: String) : TextToSpeech {
    override val id = "command"

    override fun readiness(): String? {
        if (template.isBlank()) return "tts.command is empty"
        return missingProgram(template)?.let { "'$it' not found" }
    }

    override suspend fun synthesize(text: String, lang: String?, speedPct: Int, timeoutMs: Long): TtsOutcome =
        runEngine("the speech command", commandArgv(template, lang), text, null, timeoutMs)

    companion object {
        /** `{lang}` is the text's language as a two- or three-letter code (`de-DE` → `de`); when the caller named
         *  none (D-Bus `Speak`), [systemLanguage] (the locale's), else `en`. Always a bare code: no shell
         *  metacharacters, never empty. */
        internal fun commandArgv(template: String, lang: String?, systemLanguage: String = Locale.getDefault().language): List<String> =
            listOf("sh", "-c", template.replace("{lang}", primaryLanguage(lang) ?: primaryLanguage(systemLanguage) ?: "en"))
    }
}

/**
 * An OpenAI-compatible speech endpoint (`POST …/v1/audio/speech`): speaches, LocalAI or openedai-speech on
 * the network, or a hosted service with [apiKey]. Network egress, so only used when the user chose it
 * (`tts.backend = http`).
 */
class HttpTts(
    private val client: HttpClient,
    private val url: String,
    private val model: String,
    private val voice: String,
    private val apiKey: String?,
) : TextToSpeech {
    override val id = "http"

    override fun readiness(): String? =
        if (url.startsWith("http://") || url.startsWith("https://")) null else "tts.url is not set"

    override suspend fun synthesize(text: String, lang: String?, speedPct: Int, timeoutMs: Long): TtsOutcome = try {
        val response = client.post(url) {
            timeout { requestTimeoutMillis = timeoutMs }
            if (!apiKey.isNullOrBlank()) header(HttpHeaders.Authorization, "Bearer $apiKey")
            contentType(ContentType.Application.Json)
            setBody(requestBody(text, model, voice, speedPct))
        }
        val body = response.bodyAsBytes()
        when {
            !response.status.isSuccess() -> TtsOutcome.Failed("HTTP ${response.status.value}: ${body.decodeToString().take(200)}")
            else -> Wav.decode(body)?.let { (pcm, rate) -> TtsOutcome.Audio(pcm, rate) }
                ?: TtsOutcome.Failed("the speech server sent no 16-bit WAV")
        }
    } catch (e: CancellationException) {
        throw e    // stopped or replaced: not an engine failure
    } catch (e: HttpRequestTimeoutException) {
        TtsOutcome.Failed("no answer within ${timeoutMs / 1000} s")
    } catch (e: Exception) {
        TtsOutcome.Failed("unreachable: ${e.message ?: e.javaClass.simpleName}")
    }

    companion object {
        /** `{model, voice, input, speed, response_format: "wav"}`; an empty model or voice is left out (the
         *  server's default). */
        internal fun requestBody(text: String, model: String, voice: String, speedPct: Int): String = buildJsonObject {
            if (model.isNotBlank()) put("model", model)
            if (voice.isNotBlank()) put("voice", voice)
            put("input", text)
            put("speed", speedPct.coerceIn(50, 200) / 100.0)
            put("response_format", "wav")
        }.toString()
    }
}
