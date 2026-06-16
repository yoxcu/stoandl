package de.yoxcu.stoandl.util

import io.github.oshai.kotlinlogging.KotlinLogging
import java.util.concurrent.TimeUnit

private val log = KotlinLogging.logger {}

/**
 * Run [cmd], waiting up to [timeoutSec] seconds, and return its stdout — or null on timeout, non-zero
 * exit, or any exception (all logged at warn). [redirectErrorStream] folds stderr into the returned
 * output. Output is returned verbatim (untrimmed); callers parse as they see fit.
 */
fun runCommand(cmd: List<String>, timeoutSec: Long, redirectErrorStream: Boolean = false): String? = try {
    val p = ProcessBuilder(cmd).redirectErrorStream(redirectErrorStream).start()
    val out = p.inputStream.bufferedReader().readText()
    when {
        !p.waitFor(timeoutSec, TimeUnit.SECONDS) -> {
            p.destroy()
            log.warn { "command timed out: ${cmd.joinToString(" ")}" }
            null
        }
        p.exitValue() != 0 -> {
            log.warn { "command exited ${p.exitValue()}: ${cmd.joinToString(" ")}" }
            null
        }
        else -> out
    }
} catch (e: Exception) {
    log.warn { "cannot run '${cmd.firstOrNull()}': ${e.message}" }
    null
}
