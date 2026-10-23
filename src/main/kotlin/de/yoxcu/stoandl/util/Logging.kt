package de.yoxcu.stoandl.util

import io.github.oshai.kotlinlogging.KLogger
import java.util.concurrent.ConcurrentHashMap

/**
 * The last line a periodic job logged per key, to tell news from a repeat: a sync every half hour logs
 * its summary (or the same parse failure) at its real level only when the line changed, and at TRACE
 * while it repeats (the phone runs at DEBUG, where a repeat is still noise). Per daemon run, so a restart
 * logs everything once again.
 */
class RepeatedLines {
    private val last = ConcurrentHashMap<String, String>()

    /** True when [line] differs from the last one recorded under [key]; records it either way. */
    fun isNew(key: String, line: String): Boolean = last.put(key, line) != line
}

/** [line] at INFO when it is new under [key] in [lines], else at TRACE. */
fun KLogger.infoWhenChanged(lines: RepeatedLines, key: String, line: String) =
    if (lines.isNew(key, line)) info { line } else trace { line }

/** [line] at WARN when it is new under [key] in [lines], else at TRACE. */
fun KLogger.warnWhenChanged(lines: RepeatedLines, key: String, line: String) =
    if (lines.isNew(key, line)) warn { line } else trace { line }
