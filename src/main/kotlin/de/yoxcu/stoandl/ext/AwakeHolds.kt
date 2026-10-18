package de.yoxcu.stoandl.ext

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.UUID

private val log = KotlinLogging.logger {}

/**
 * One extension's `keepAwake` holds: each a logind sleep block ([holdAwake]) that closes itself after its
 * `maxMs`, on `release`, in [releaseAll] when the child exits, or in [close] when the extension stops.
 * After [close] it refuses new holds: a `keepAwake` racing the stop would otherwise get a hold whose expiry
 * never runs on the cancelled [scope]. Bounded (at most [MAX_HOLDS], each at most
 * [MAX_MS]) because a block refuses a suspend the user asked for.
 */
internal class AwakeHolds(
    private val tag: String,
    private val scope: CoroutineScope,
    private val holdAwake: (String) -> AutoCloseable,
) {
    private class Hold(val handle: AutoCloseable, var expiry: Job? = null)
    private val holds = LinkedHashMap<String, Hold>()
    private var closed = false

    /** A new token, or the JSON-RPC error message. */
    fun acquire(why: String?, maxMs: Long?): Result<String> {
        if (maxMs == null || maxMs <= 0) return Result.failure(IllegalArgumentException("keepAwake: maxMs must be 1..$MAX_MS"))
        val ms = maxMs.coerceAtMost(MAX_MS)
        val reason = why?.trim()?.take(80)?.takeIf { it.isNotEmpty() } ?: "extension work"
        val token = UUID.randomUUID().toString()
        synchronized(holds) {
            if (closed) return Result.failure(IllegalStateException("keepAwake: extension is stopping"))
            if (holds.size >= MAX_HOLDS) return Result.failure(IllegalStateException("keepAwake: too many holds"))
            holds[token] = Hold(holdAwake("$tag: $reason"))
        }
        val job = scope.launch {
            delay(ms)
            if (release(token)) log.debug { "[$tag] keepAwake '$reason' expired after $ms ms" }
        }
        val live = synchronized(holds) { holds[token]?.also { it.expiry = job } != null }
        if (!live) job.cancel()
        return Result.success(token)
    }

    /** Close [token]'s hold; false when it is unknown or already gone (still not an error). */
    fun release(token: String): Boolean {
        val h = synchronized(holds) { holds.remove(token) } ?: return false
        h.expiry?.cancel()
        runCatching { h.handle.close() }
        return true
    }

    fun releaseAll() = releaseAll(close = false)

    /** [releaseAll] for good: later [acquire]s fail. */
    fun close() = releaseAll(close = true)

    private fun releaseAll(close: Boolean) {
        val all = synchronized(holds) {
            if (close) closed = true
            holds.values.toList().also { holds.clear() }
        }
        all.forEach { h -> h.expiry?.cancel(); runCatching { h.handle.close() } }
    }

    val size: Int get() = synchronized(holds) { holds.size }

    companion object {
        const val MAX_MS = 60_000L
        const val MAX_HOLDS = 4
    }
}
