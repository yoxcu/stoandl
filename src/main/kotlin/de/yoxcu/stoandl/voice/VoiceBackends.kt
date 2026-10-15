package de.yoxcu.stoandl.voice

import de.yoxcu.stoandl.config.ConfigStore
import de.yoxcu.stoandl.config.StoandlConfig.VoiceBackend
import de.yoxcu.stoandl.secret.SecretStore
import de.yoxcu.stoandl.util.stoandlHttpClient

/** Where `voice.api_key` lives: the secret store, never stoandl.conf. */
const val VOICE_API_KEY_REF = "config:voice.api_key"

/**
 * The one place the speech-to-text backend is chosen, from the live config (`voice.backend`, read on every
 * use, so a change applies to the next dictation). Watch dictation, D-Bus `Transcribe` and extensions'
 * `transcribe` all go through it.
 */
class VoiceBackends(
    private val configStore: ConfigStore,
    private val secrets: SecretStore,
    val models: VoiceModels,
) {
    private val http by lazy { stoandlHttpClient() }

    /** The configured backend, or null when voice is off. */
    fun current(): SpeechToText? {
        val c = configStore.current()
        return when (c.voiceBackend) {
            VoiceBackend.OFF -> null
            VoiceBackend.COMMAND -> CommandStt(c.voiceCommand)
            VoiceBackend.LOCAL -> LocalModelStt(models, c.voiceLocalModel)
            VoiceBackend.HTTP -> HttpStt(http, c.voiceUrl, c.voiceHttpModel, secrets.get(VOICE_API_KEY_REF))
        }
    }

    /** `off:`, `ready:<backend>\t<detail>` or `unavailable:<reason>` (D-Bus `VoiceStatus`). */
    fun status(): String {
        val c = configStore.current()
        val stt = current() ?: return "off:"
        stt.readiness()?.let { return "unavailable:$it" }
        if (!SpeexDecoder.available) return "unavailable:libspeex.so.1 not found (install speex)"
        val detail = when (c.voiceBackend) {
            VoiceBackend.LOCAL -> models.model(c.voiceLocalModel)?.name.orEmpty()
            VoiceBackend.COMMAND -> c.voiceCommand.trim().substringBefore(' ')
            else -> c.voiceUrl.substringBefore('?')
        }
        return "ready:${stt.id}\t$detail"
    }
}

/** `voice.backend = local`: an installed catalog model run by its whisper.cpp program. */
class LocalModelStt(private val models: VoiceModels, private val modelId: String) : SpeechToText {
    override val id = "local"
    private val model = models.model(modelId)

    override fun readiness(): String? = when {
        model == null -> if (modelId.isEmpty()) "no model chosen (voice.local_model)" else "unknown model '$modelId'"
        !models.isInstalled(model) -> "${model.name} isn't downloaded yet"
        !models.engineAvailable(model.engine) -> "${model.engine.program} not found (install whisper.cpp)"
        else -> null
    }

    override suspend fun transcribe(audio: SttAudio, language: String, timeoutMs: Long): SttOutcome {
        val m = model ?: return SttOutcome.Failed(readiness() ?: "no model")
        return CommandStt(models.command(m)).transcribe(audio, language, timeoutMs)
    }
}
