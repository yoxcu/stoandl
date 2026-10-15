package de.yoxcu.stoandl.voice

import de.yoxcu.stoandl.config.ConfigStore
import kotlinx.coroutines.runBlocking
import java.io.File

/** How long a file transcription (D-Bus `Transcribe`, an extension's voice note) may take: no watch is
 *  waiting, so longer than a dictation. */
const val FILE_TRANSCRIBE_TIMEOUT_MS = 60_000L

/** The voice D-Bus methods (`VoiceStatus`, `Transcribe`) and extensions' `transcribe`, over [VoiceBackends]. */
class VoiceControl(private val backends: VoiceBackends, private val configStore: ConfigStore) {
    fun status(): String = backends.status()

    /** Transcribe the file at [path]: `ok:<ms>\t<text>`, `off:`, `unavailable:<why>` or `error:<msg>`. Blocks. */
    fun transcribeBlocking(path: String, language: String? = null): String = runBlocking { transcribe(path, language) }

    suspend fun transcribe(path: String, language: String? = null): String {
        val stt = backends.current() ?: return "off:"
        stt.readiness()?.let { return "unavailable:$it" }
        val file = File(path)
        if (!file.isFile) return "error:no such file $path"
        val audio = audioOf(file) ?: return "error:unsupported audio (WAV, Ogg/Opus or a .spx dictation dump)"
        val start = System.nanoTime()
        val lang = language?.takeIf { it.isNotBlank() } ?: configStore.current().voiceLanguage
        return when (val o = stt.transcribe(audio, lang, FILE_TRANSCRIBE_TIMEOUT_MS)) {
            is SttOutcome.Text -> "ok:${(System.nanoTime() - start) / 1_000_000}\t${o.text}"
            is SttOutcome.Unreachable -> "error:unreachable: ${o.message}"
            is SttOutcome.Failed -> "error:${o.message}"
        }
    }

    private fun audioOf(file: File): SttAudio? {
        val name = file.name.lowercase()
        return when {
            name.endsWith(".wav") -> Wav.decode(file.readBytes())?.let { (pcm, rate) -> SttAudio.Pcm16(pcm, rate) }
            name.endsWith(".ogg") || name.endsWith(".opus") || name.endsWith(".oga") -> SttAudio.Clip(file, "audio/ogg")
            else -> null
        }
    }
}
