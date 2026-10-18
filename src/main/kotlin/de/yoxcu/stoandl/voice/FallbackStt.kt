package de.yoxcu.stoandl.voice

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.withTimeoutOrNull

private val log = KotlinLogging.logger {}

/** What the fallback keeps of a deadline: the official app's STTRouter reserves 7 s of the watch's 14 s
 *  for its second engine. Never more than half, so a long file transcription still favours the primary. */
private const val FALLBACK_RESERVE_MS = 7_000L

internal fun fallbackReserveMs(timeoutMs: Long): Long = minOf(FALLBACK_RESERVE_MS, timeoutMs / 2)

/**
 * `voice.fallback_backend`: [fallback] answers when [primary] isn't ready, fails, hears nothing, or doesn't
 * answer within its share of the deadline ([fallbackReserveMs] is held back for the fallback, which also gets
 * whatever the primary didn't use). The fallback runs only on the same audio, never in parallel: a local
 * engine would compete with itself for the CPU.
 */
class FallbackStt(
    val primary: SpeechToText,
    val fallback: SpeechToText,
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000 },
) : SpeechToText {
    override val id = "${primary.id}+${fallback.id}"

    override fun readiness(): String? {
        val why = primary.readiness() ?: return null
        return if (fallback.readiness() == null) null else why
    }

    override suspend fun transcribe(audio: SttAudio, language: String, timeoutMs: Long): SttOutcome {
        val primaryWhy = primary.readiness()
        val fallbackWhy = fallback.readiness()
        if (fallbackWhy != null) return primary.transcribe(audio, language, timeoutMs)
        if (primaryWhy != null) {
            log.info { "Voice: ${primary.id} not ready ($primaryWhy), using ${fallback.id}" }
            return fallback.transcribe(audio, language, timeoutMs)
        }
        val start = nowMs()
        val first = attempt(primary, audio, language, timeoutMs - fallbackReserveMs(timeoutMs))
        if (first is SttOutcome.Text && first.text.isNotBlank()) return first
        val left = timeoutMs - (nowMs() - start)
        if (left <= 0) return first
        log.info { "Voice: ${primary.id}: ${message(first)}; trying ${fallback.id} with $left ms left" }
        val second = attempt(fallback, audio, language, left)
        return when {
            second is SttOutcome.Text -> second
            // The primary heard nothing and the fallback failed: say nothing was heard.
            first is SttOutcome.Text -> first
            else -> {
                val both = "${primary.id}: ${message(first)}; ${fallback.id}: ${message(second)}"
                if (second is SttOutcome.Unreachable) SttOutcome.Unreachable(both) else SttOutcome.Failed(both)
            }
        }
    }

    /** A backend honours its own deadline; the timeout here only guards against one that doesn't. */
    private suspend fun attempt(stt: SpeechToText, audio: SttAudio, language: String, ms: Long): SttOutcome =
        withTimeoutOrNull(ms) { stt.transcribe(audio, language, ms) }
            ?: SttOutcome.Failed("no answer within ${ms / 1000} s")

    private fun message(o: SttOutcome) = when (o) {
        is SttOutcome.Text -> "heard nothing"
        is SttOutcome.Unreachable -> o.message
        is SttOutcome.Failed -> o.message
    }
}
