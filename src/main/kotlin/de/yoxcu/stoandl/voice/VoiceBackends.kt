package de.yoxcu.stoandl.voice

import de.yoxcu.stoandl.config.ConfigStore
import de.yoxcu.stoandl.config.StoandlConfig
import de.yoxcu.stoandl.config.StoandlConfig.VoiceBackend
import de.yoxcu.stoandl.secret.SecretStore
import de.yoxcu.stoandl.util.stoandlHttpClient

/** Where `voice.api_key` lives: the secret store, never stoandl.conf. */
const val VOICE_API_KEY_REF = "config:voice.api_key"

/**
 * The one place the speech-to-text backend is chosen, from the live config (`voice.backend` and
 * `voice.fallback_backend`, read on every use, so a change applies to the next dictation). Watch dictation,
 * D-Bus `Transcribe` and extensions' `transcribe` all go through it.
 */
class VoiceBackends(
    private val configStore: ConfigStore,
    private val secrets: SecretStore,
    val models: VoiceModels,
) {
    private val http by lazy { stoandlHttpClient() }

    /** The configured backend (with `voice.fallback_backend` behind it, if any), or null when voice is off. */
    fun current(): SpeechToText? {
        val c = configStore.current()
        val primary = backend(c.voiceBackend, c) ?: return null
        val fallback = fallbackKind(c)?.let { backend(it, c) } ?: return primary
        return FallbackStt(primary, fallback)
    }

    /**
     * `off:`, `ready:<backend>\t<detail>[\t<fallback note>]` or `unavailable:<reason>` (D-Bus `VoiceStatus`).
     * `<backend>` is the one that answers first: the fallback when the primary isn't ready.
     */
    fun status(): String {
        val c = configStore.current()
        val primary = backend(c.voiceBackend, c) ?: return "off:"
        val primaryWhy = primary.readiness()
        val fb = fallbackKind(c)?.let { kind -> backend(kind, c)?.let { Triple(kind, it, it.readiness()) } }
        if (primaryWhy != null && (fb == null || fb.third != null)) return "unavailable:$primaryWhy"
        if (!SpeexDecoder.available) return "unavailable:libspeex.so.1 not found (install speex)"
        if (fb == null) return "ready:${primary.id}\t${detail(c.voiceBackend, c)}"
        val (fbKind, fallback, fallbackWhy) = fb
        return when {
            primaryWhy != null -> "ready:${fallback.id}\t${detail(fbKind, c)}\t(${primary.id} unavailable: $primaryWhy)"
            fallbackWhy != null -> "ready:${primary.id}\t${detail(c.voiceBackend, c)}\t(fallback ${fallback.id} unavailable: $fallbackWhy)"
            else -> "ready:${primary.id}\t${detail(c.voiceBackend, c)}\tthen ${fallback.id} ${detail(fbKind, c)}"
        }
    }

    /** The fallback's kind, or null for none (off, or the primary again: same settings, same result). */
    private fun fallbackKind(c: StoandlConfig): VoiceBackend? =
        c.voiceFallbackBackend.takeIf { it != VoiceBackend.OFF && it != c.voiceBackend }

    private fun backend(kind: VoiceBackend, c: StoandlConfig): SpeechToText? = when (kind) {
        VoiceBackend.OFF -> null
        VoiceBackend.COMMAND -> CommandStt(c.voiceCommand)
        VoiceBackend.LOCAL -> LocalModelStt(models, c.voiceLocalModel)
        VoiceBackend.HTTP -> HttpStt(http, c.voiceUrl, c.voiceHttpModel, secrets.get(VOICE_API_KEY_REF))
    }

    private fun detail(kind: VoiceBackend, c: StoandlConfig): String = when (kind) {
        VoiceBackend.LOCAL -> models.model(c.voiceLocalModel)?.name.orEmpty()
        VoiceBackend.COMMAND -> c.voiceCommand.trim().substringBefore(' ')
        else -> c.voiceUrl.substringBefore('?')
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
