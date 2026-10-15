@file:OptIn(kotlin.ExperimentalUnsignedTypes::class)

package de.yoxcu.stoandl.voice

import de.yoxcu.stoandl.config.ConfigStore
import de.yoxcu.stoandl.util.CacheDirs
import io.github.oshai.kotlinlogging.KotlinLogging
import io.rebble.libpebblecommon.voice.TranscriptionProvider
import io.rebble.libpebblecommon.voice.TranscriptionResult
import io.rebble.libpebblecommon.voice.TranscriptionWord
import io.rebble.libpebblecommon.voice.VoiceEncoderInfo
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import kotlin.time.TimeSource

private val log = KotlinLogging.logger {}

/** An early cancel on the watch sends nothing: give up when no audio arrives within this. */
private const val FIRST_FRAME_MS = 3_000L
/** The watch records at most 15 s; a bit of slack for the link. */
private const val MAX_RECORDING_MS = 20_000L
/** The watch waits 15 s for the result after it stops recording; answer within 14. */
private const val RESULT_BUDGET_MS = 14_000L
/** The transcript's UTF-8 budget: well inside the watch's 2044-byte inbound message. */
private const val MAX_TRANSCRIPT_BYTES = 1_500

private class NoAudio : Exception("no audio")

/**
 * Dictation from the watch's microphone (notification replies, watchapps' dictation API): decodes the
 * Speex frames as they arrive ([SpeexDecoder]), then hands the speech to the configured backend
 * ([VoiceBackends]) within the firmware's 15 s result window. libpebble3 (VoiceSessionManager) does the
 * protocol; the transcript reaches the reply path as the Response action's text, like a canned reply.
 */
class StoandlTranscriptionProvider(
    private val backends: VoiceBackends,
    private val configStore: ConfigStore,
) : TranscriptionProvider {

    /** Off, or the backend or libspeex missing: the watch says "Enable voice in the settings page…". */
    override suspend fun canServeSession(): Boolean {
        val stt = backends.current() ?: return false
        val why = stt.readiness() ?: if (!SpeexDecoder.available) "libspeex missing" else null
        if (why != null) log.info { "Voice session refused: $why" }
        return why == null
    }

    override suspend fun transcribe(
        encoderInfo: VoiceEncoderInfo,
        audioFrames: Flow<UByteArray>,
        isNotificationReply: Boolean,
    ): TranscriptionResult {
        val stt = backends.current() ?: return TranscriptionResult.Disabled
        if (encoderInfo !is VoiceEncoderInfo.Speex) return TranscriptionResult.Error("unsupported encoder $encoderInfo")
        log.info { "Voice: Speex ${encoderInfo.version}, ${encoderInfo.sampleRate} Hz, ${encoderInfo.bitRate} bps, frame ${encoderInfo.frameSize}" }
        val session = SpeexDecoder.open() ?: return TranscriptionResult.Disabled
        val pcm = session.use { collect(it, audioFrames) } ?: return TranscriptionResult.Error("no audio")
        val stopped = TimeSource.Monotonic.markNow()
        val cfg = configStore.current()
        if (cfg.voiceSaveAudio) saveAudio(pcm, encoderInfo.sampleRate.toInt())
        if (pcm.isEmpty()) return TranscriptionResult.Success(emptyList())
        val outcome = stt.transcribe(SttAudio.Pcm16(pcm, encoderInfo.sampleRate.toInt()), cfg.voiceLanguage, RESULT_BUDGET_MS)
        val took = stopped.elapsedNow()
        val seconds = pcm.size / encoderInfo.sampleRate.toDouble()
        return when (outcome) {
            is SttOutcome.Text -> {
                val words = transcriptWords(outcome.text)
                log.info { "Voice: %.1f s audio, backend=%s, %d ms, %d words".format(seconds, stt.id, took.inWholeMilliseconds, words.size) }
                log.debug { "Voice transcript: ${outcome.text}" }
                // Firmware 4.39+ shows "Missed that. Try again." for an empty result.
                TranscriptionResult.Success(words)
            }
            is SttOutcome.Unreachable -> {
                log.warn { "Voice: backend ${stt.id} unreachable: ${outcome.message}" }
                TranscriptionResult.ConnectionError(outcome.message)
            }
            is SttOutcome.Failed -> {
                log.warn { "Voice: backend ${stt.id} failed after ${took.inWholeMilliseconds} ms: ${outcome.message}" }
                TranscriptionResult.Error(outcome.message)
            }
        }
    }

    /** Decode frames as they arrive until the watch stops recording. Null when none came in time. */
    private suspend fun collect(session: SpeexDecoder.Session, frames: Flow<UByteArray>): ShortArray? {
        var out = ShortArray(16_000 * 16)
        var n = 0
        var bad = 0
        return try {
            withTimeoutOrNull(MAX_RECORDING_MS) {
                coroutineScope {
                    val first = CompletableDeferred<Unit>()
                    val watchdog = launch {
                        delay(FIRST_FRAME_MS)
                        if (!first.isCompleted) throw NoAudio()
                    }
                    frames.collect { f ->
                        first.complete(Unit)
                        val samples = session.decodeFrame(f.asByteArray())
                        if (samples == null) { bad++; return@collect }
                        if (n + samples.size > out.size) out = out.copyOf(out.size * 2)
                        samples.copyInto(out, n)
                        n += samples.size
                    }
                    watchdog.cancel()
                }
            }
            if (bad > 0) log.info { "Voice: $bad malformed frame(s) skipped" }
            out.copyOf(n)
        } catch (_: NoAudio) {
            log.info { "Voice: no audio within ${FIRST_FRAME_MS / 1000} s (cancelled on the watch?)" }
            null
        }
    }

    private fun saveAudio(pcm: ShortArray, rate: Int) {
        val f = File(CacheDirs.dir("voice"), "dictation-${System.currentTimeMillis()}.wav")
        runCatching { f.writeBytes(Wav.encode(pcm, rate)) }
            .onSuccess { log.info { "Voice: audio kept at $f (voice.save_audio)" } }
    }
}

/** The transcript as the watch's words, cut to [MAX_TRANSCRIPT_BYTES] of UTF-8 at a word boundary. */
internal fun transcriptWords(text: String): List<TranscriptionWord> {
    val out = ArrayList<TranscriptionWord>()
    var bytes = 0
    for (w in text.split(Regex("""\s+""")).filter { it.isNotEmpty() }) {
        // Each word costs its bytes plus 3 bytes of framing on the wire.
        val cost = w.encodeToByteArray().size + 3
        if (bytes + cost > MAX_TRANSCRIPT_BYTES) break
        out += TranscriptionWord(w, 0.9f)
        bytes += cost
    }
    return out
}
