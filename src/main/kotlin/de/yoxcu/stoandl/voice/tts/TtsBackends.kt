package de.yoxcu.stoandl.voice.tts

import de.yoxcu.stoandl.config.ConfigStore
import de.yoxcu.stoandl.config.StoandlConfig.VoiceBackend
import de.yoxcu.stoandl.secret.SecretStore
import de.yoxcu.stoandl.util.stoandlHttpClient
import de.yoxcu.stoandl.voice.ESPEAK_VOICE
import de.yoxcu.stoandl.voice.ModelKind
import de.yoxcu.stoandl.voice.SpeechModel
import de.yoxcu.stoandl.voice.VoiceModels

/** Where `tts.api_key` lives: the secret store, never stoandl.conf. Separate from `voice.api_key`. */
const val TTS_API_KEY_REF = "config:tts.api_key"

/**
 * The voice to speak [lang] with: `tts.local_voice` ([localVoice]) when it speaks that language (espeak-ng
 * speaks them all), else the first installed voice that does, else [localVoice] anyway. No language: the
 * local voice.
 */
internal fun pickVoice(localVoice: String, lang: String?, installed: List<SpeechModel>): String {
    val want = primaryLanguage(lang) ?: return localVoice
    if (localVoice == ESPEAK_VOICE || installed.any { it.id == localVoice && want in it.languages }) return localVoice
    return installed.firstOrNull { want in it.languages }?.id ?: localVoice
}

/**
 * The one place the text-to-speech engine is chosen, from the live config (`tts.*`, read on every use, so a
 * change applies to the next sentence). An extension's `synthesize` and D-Bus `Speak` both go through it.
 */
class TtsBackends(
    private val configStore: ConfigStore,
    private val secrets: SecretStore,
    val models: VoiceModels,
    /** Keeps `piper_exe` loaded between sentences; null: one process per sentence. */
    private val resident: ResidentPiper? = null,
) {
    private val http by lazy { stoandlHttpClient() }

    /** The configured engine for [lang] (see [pickVoice]), or null when text to speech is off. A resident Piper
     *  whose voice the config no longer picks for any language is stopped here. */
    fun current(lang: String? = null): TextToSpeech? {
        val c = configStore.current()
        val installed = if (c.ttsBackend == VoiceBackend.LOCAL) models.installed(ModelKind.TTS) else emptyList()
        resident?.retain((installed.flatMap { it.languages } + null).map { pickVoice(c.ttsLocalVoice, it, installed) }.toSet())
        return when (c.ttsBackend) {
            VoiceBackend.OFF -> null
            VoiceBackend.LOCAL -> when (val voice = pickVoice(c.ttsLocalVoice, lang, installed)) {
                ESPEAK_VOICE -> EspeakTts()
                else -> PiperTts(models, voice, resident)
            }
            VoiceBackend.COMMAND -> CommandTts(c.ttsCommand)
            VoiceBackend.HTTP -> HttpTts(http, c.ttsUrl, c.ttsHttpModel, c.ttsHttpVoice, secrets.get(TTS_API_KEY_REF))
        }
    }

    /** `tts.speed`, in percent. */
    fun speedPct(): Int = configStore.current().ttsSpeed

    /** `off:`, `ready:<backend>\t<detail>` or `unavailable:<reason>` (D-Bus `TtsStatus`). The detail is the
     *  voice's name, the command's program or the server URL. */
    fun status(): String {
        val tts = current() ?: return "off:"
        tts.readiness()?.let { return "unavailable:$it" }
        val c = configStore.current()
        val detail = when (tts) {
            is PiperTts -> tts.voice?.name.orEmpty()
            is EspeakTts -> ESPEAK_VOICE
            is CommandTts -> c.ttsCommand.trim().substringBefore(' ')
            else -> c.ttsUrl.substringBefore('?')
        }
        return "ready:${tts.id}\t$detail"
    }
}
