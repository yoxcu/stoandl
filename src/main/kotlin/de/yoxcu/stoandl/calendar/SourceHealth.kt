package de.yoxcu.stoandl.calendar

import java.util.concurrent.ConcurrentHashMap

/**
 * How each editable calendar source fared on its last sync: the `lastError` and `lastSync` columns of
 * `ListCalendarSources`, so the GUI can say "Sign-in failed (401)" next to the account that has the
 * problem instead of the user reading the log.
 *
 * Keyed by [SourceId]. Within one source every *part* (a CalDAV account's discovery, each calendar's
 * event read, keyed by its platformId) keeps its own problem, so one calendar of an account reading fine
 * doesn't hide another one failing. A source's lastSync is the last time any part read successfully.
 */
class SourceHealth(private val nowSeconds: () -> Long = { System.currentTimeMillis() / 1000 }) {
    private val problems = ConcurrentHashMap<String, ConcurrentHashMap<String, String>>()
    private val lastOk = ConcurrentHashMap<String, Long>()

    fun ok(source: String, part: String) {
        problems[source]?.remove(part)
        lastOk[source] = nowSeconds()
    }

    fun failed(source: String, part: String, problem: String) {
        problems.computeIfAbsent(source) { ConcurrentHashMap() }[part] = problem
    }

    /** Forget the problems of parts [source] no longer has (a calendar removed on the server). */
    fun retain(source: String, parts: Set<String>) {
        problems[source]?.keys?.retainAll(parts)
    }

    /** The source's current problem (the first part's, by part key, so it doesn't flicker), or "". */
    fun lastError(source: String): String =
        problems[source]?.let { p -> p.keys.minOrNull()?.let { p[it] } }.orEmpty()

    /** Epoch seconds of the source's last successful read, or null when it never read. */
    fun lastSync(source: String): Long? = lastOk[source]
}

/** A failed HTTP status as the user reads it. */
internal fun httpProblem(status: Int): String = when (status) {
    401, 403 -> "Sign-in failed ($status)"
    404, 410 -> "Not found ($status)"
    in 500..599 -> "Server error ($status)"
    else -> "HTTP $status"
}

/** A request that never got an answer (DNS, refused, TLS, timeout). */
internal fun unreachable(e: Exception): String = "Unreachable: ${e.message ?: e::class.simpleName}"
