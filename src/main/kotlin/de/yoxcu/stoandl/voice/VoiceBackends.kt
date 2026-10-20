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
        VoiceBackend.LOCAL -> LocalModelStt(models, c.voiceLocalModel, c.voiceLanguage)
        VoiceBackend.HTTP -> HttpStt(http, c.voiceUrl, c.voiceHttpModel, secrets.get(VOICE_API_KEY_REF))
    }

    private fun detail(kind: VoiceBackend, c: StoandlConfig): String = when (kind) {
        VoiceBackend.LOCAL -> models.model(c.voiceLocalModel)?.let { m ->
            if (m.engine.needsLanguage) "${m.name} (${safeLanguage(c.voiceLanguage)})" else m.name
        }.orEmpty()
        VoiceBackend.COMMAND -> c.voiceCommand.trim().substringBefore(' ')
        else -> c.voiceUrl.substringBefore('?')
    }
}

/**
 * `voice.backend = local`: an installed catalog model run by its program (whisper.cpp, transcribe.cpp).
 * [language] is `voice.language`, which readiness checks for an engine that needs one; a transcription
 * checks the language it is given.
 */
class LocalModelStt(
    private val models: VoiceModels,
    private val modelId: String,
    private val language: String = "auto",
) : SpeechToText {
    override val id = "local"
    private val model = models.model(modelId)?.takeIf { it.kind == ModelKind.STT }

    override fun readiness(): String? = when {
        model == null -> if (modelId.isEmpty()) "no model chosen (voice.local_model)" else "unknown model '$modelId'"
        !models.isInstalled(model) -> "${model.name} isn't downloaded yet"
        !models.engineAvailable(model.engine) -> "${model.engine.program} not found (install ${model.engine.install})"
        else -> languageProblem(model, language)
    }

    override suspend fun transcribe(audio: SttAudio, language: String, timeoutMs: Long): SttOutcome {
        val m = model ?: return SttOutcome.Failed(readiness() ?: "no model")
        languageProblem(m, language)?.let { return SttOutcome.Failed(it) }
        val outcome = CommandStt(models.command(m)).transcribe(audio, language, timeoutMs)
        return if (m.engine == SpeechEngine.CANARY && outcome is SttOutcome.Text) SttOutcome.Text(tidyCanaryTranscript(outcome.text))
        else outcome
    }
}

/**
 * Why [m] can't transcribe [language], or null. An engine that [SpeechEngine.needsLanguage] runs only with one
 * of the model's languages: `auto` must not quietly become English, since Canary told the wrong language
 * translates into it (a German dictation would come back as an English reply). No language detection in
 * front of it either: VoxLingua107 heard three of four accented English dictations as German.
 */
internal fun languageProblem(m: SpeechModel, language: String): String? {
    if (!m.engine.needsLanguage || safeLanguage(language) in m.languages) return null
    val choices = m.languages.dropLast(1).joinToString(", ") + (if (m.languages.size > 1) " or " else "") + m.languages.last()
    val now = safeLanguage(language).let { if (it == "auto") "" else ", not $it" }
    return "${m.name} needs voice.language ($choices)$now"
}

/** The flash Canary models' detokenizer leaves a space before punctuation (`später , fand`, `verschoben .`)
 *  and sometimes doubles a question mark (`mitbringen ? ?`). */
internal fun tidyCanaryTranscript(text: String): String =
    text.replace(Regex("""\s+([,.?!:;])"""), "$1").replace(Regex("""\?(\s*\?)+"""), "?").trim()
