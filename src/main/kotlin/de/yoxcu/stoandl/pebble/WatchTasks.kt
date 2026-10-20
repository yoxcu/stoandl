package de.yoxcu.stoandl.pebble

import de.yoxcu.stoandl.util.UserDirs
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.io.File
import java.util.concurrent.ConcurrentHashMap

private val log = KotlinLogging.logger {}

/**
 * The watch transfers a client starts and then follows: an app sideload, a screenshot, the watch's logs
 * and a core dump. Each takes seconds to tens of seconds over Bluetooth, so the D-Bus call only starts it
 * (`ok:started`) and returns; the outcome comes through [status] and the `TaskChanged` signal, the
 * pattern pairing, firmware and language installs already use. Before, the call blocked a D-Bus worker in
 * runBlocking for as long as the transfer ran and froze the GUI that waited on it.
 *
 * One task of a kind at a time (a second start answers `busy:`); different kinds may overlap. A kind's
 * status is `idle:` until its first start, `running:<what>` while it runs, then its result, which stays
 * until the next start: `ok:<…>`, `none:<msg>`, `notready:<msg>` or `error:<msg>`.
 */
class WatchTasks(
    private val scope: CoroutineScope,
    private val maxRunMs: Long = MAX_RUN_MS,
    private val onChange: (kind: Kind, status: String) -> Unit,
) {
    enum class Kind(val token: String) {
        SIDELOAD("sideload"), SCREENSHOT("screenshot"), LOGS("logs"), COREDUMP("coredump");

        companion object {
            fun of(token: String): Kind? = entries.firstOrNull { it.token == token.trim().lowercase() }
        }
    }

    private val states = ConcurrentHashMap<Kind, String>()

    fun status(kind: Kind): String = states[kind] ?: IDLE

    /** Start [work] as [kind] unless one is running: `ok:started` or `busy:<msg>`. [running] says what it
     *  does while it runs. [work] returns the task's result; a throw becomes `error:<message>`. */
    fun start(kind: Kind, running: String, work: suspend () -> String): String {
        val runningStatus = "running:$running"
        var started = false
        states.compute(kind) { _, cur ->
            if (cur != null && cur.startsWith("running:")) cur else runningStatus.also { started = true }
        }
        if (!started) return "busy:${kind.busyText}"
        onChange(kind, runningStatus)
        scope.launch {
            val result = try {
                withTimeout(maxRunMs) { work() }
            } catch (e: TimeoutCancellationException) {
                log.warn { "${kind.token} gave up after ${maxRunMs / 1000} s" }
                "error:the watch stopped answering"
            } catch (e: CancellationException) {
                // Only the daemon stopping cancels the task itself. The work's own cancellation (the watch's
                // connection scope, cancelled when it drops mid-transfer) is the task failing: rethrown, it
                // would leave the kind `running:`, and every later start `busy:`, until a restart.
                currentCoroutineContext().ensureActive()
                log.warn { "${kind.token} cut off: ${e.message}" }
                "error:the watch disconnected"
            } catch (e: Exception) {
                log.warn(e) { "${kind.token} failed" }
                "error:${e.message ?: "${kind.token} failed"}"
            }
            states[kind] = result
            onChange(kind, result)
        }
        return "ok:started"
    }

    private val Kind.busyText: String get() = when (this) {
        Kind.SIDELOAD -> "an app is already being installed"
        Kind.SCREENSHOT -> "a screenshot is already being taken"
        Kind.LOGS -> "the watch's logs are already being read"
        Kind.COREDUMP -> "a core dump is already being read"
    }

    companion object {
        const val IDLE = "idle:"

        /** The longest a task may run: a transfer the watch stops answering (some wait on it without a
         *  timeout of their own) must not hold its kind forever. */
        const val MAX_RUN_MS = 300_000L

        /** Where a [kind]'s output goes: [requested] when given (it must be absolute: the daemon's working
         *  directory isn't the caller's), else a new timestamped file in the user's Pictures/Screenshots
         *  or Documents/stoandl. Null for a relative [requested]. */
        fun outputFile(kind: Kind, requested: String): File? {
            if (requested.isNotBlank()) return File(requested).takeIf { it.isAbsolute }
            val documents = File(UserDirs.dir("DOCUMENTS", "Documents"), "stoandl")
            return when (kind) {
                Kind.SCREENSHOT -> UserDirs.newFile(File(UserDirs.dir("PICTURES", "Pictures"), "Screenshots"), "pebble-screenshot", ".png")
                Kind.LOGS -> UserDirs.newFile(documents, "pebble-logs", ".txt")
                Kind.COREDUMP -> UserDirs.newFile(documents, "pebble-coredump", ".bin")
                Kind.SIDELOAD -> null
            }
        }
    }
}
