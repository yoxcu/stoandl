package de.yoxcu.stoandl.util

import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.InputStream
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

private val log = KotlinLogging.logger {}

/** Bytes of stderr kept; the rest is read and dropped so the child never blocks on a full pipe. */
private const val STDERR_TAIL = 4096

/** How a finished child went: its exit code, stdout and the tail of stderr. Null [exitCode] = timed out. */
class ProcessResult(val exitCode: Int?, val stdout: String, val stderr: String) {
    val timedOut: Boolean get() = exitCode == null
}

/**
 * The one child-process runner. Runs [cmd] for at most [timeoutMs]: stdout and stderr are drained on their
 * own threads, so the deadline holds even for a child that hangs with its pipes open or fills a pipe, and
 * on timeout the child and all its descendants are killed. Blocks the calling thread; null when [cmd]
 * can't be started (logged at warn).
 */
fun runProcess(cmd: List<String>, timeoutMs: Long, redirectErrorStream: Boolean = false): ProcessResult? {
    val p = try {
        ProcessBuilder(cmd).redirectErrorStream(redirectErrorStream).start()
    } catch (e: Exception) {
        log.warn { "cannot run '${cmd.firstOrNull()}': ${e.message}" }
        return null
    }
    p.outputStream.close()
    val out = drain(p.inputStream) { it.readBytes() }
    val err = if (redirectErrorStream) null else drain(p.errorStream) { tail(it) }
    if (!p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
        killTree(p)
        return ProcessResult(null, "", err?.getQuietly().orEmpty())
    }
    // The child has exited, so its pipes are at EOF unless a grandchild still holds them; don't wait on that.
    val stdout = out.getQuietly()?.toString(Charsets.UTF_8).orEmpty()
    return ProcessResult(p.exitValue(), stdout, err?.getQuietly().orEmpty())
}

/**
 * Run [cmd], waiting up to [timeoutSec] seconds, and return its stdout — or null on timeout, non-zero
 * exit, or any exception (all logged at warn, with the tail of stderr). [redirectErrorStream] folds
 * stderr into the returned output. Output is returned verbatim (untrimmed); callers parse as they see fit.
 */
fun runCommand(cmd: List<String>, timeoutSec: Long, redirectErrorStream: Boolean = false): String? {
    val r = runProcess(cmd, timeoutSec * 1000, redirectErrorStream) ?: return null
    val name = cmd.joinToString(" ")
    return when {
        r.timedOut -> { log.warn { "command timed out after ${timeoutSec}s: $name" }; null }
        r.exitCode != 0 -> {
            val stderr = r.stderr.trim()
            log.warn { "command exited ${r.exitCode}: $name" + if (stderr.isEmpty()) "" else " — $stderr" }
            null
        }
        else -> r.stdout
    }
}

private val pipeReaders = Executors.newVirtualThreadPerTaskExecutor()

private fun <T> drain(stream: InputStream, read: (InputStream) -> T): CompletableFuture<T> =
    CompletableFuture.supplyAsync({ stream.use(read) }, pipeReaders)

private fun tail(stream: InputStream): String {
    val buf = ByteArray(8192)
    var kept = ByteArray(0)
    while (true) {
        val n = stream.read(buf)
        if (n < 0) break
        kept = (kept + buf.copyOf(n)).takeLast(STDERR_TAIL).toByteArray()
    }
    return kept.toString(Charsets.UTF_8)
}

private fun <T> CompletableFuture<T>.getQuietly(): T? = runCatching { get(1, TimeUnit.SECONDS) }.getOrNull()

private fun killTree(p: Process) {
    p.descendants().forEach { it.destroyForcibly() }
    p.destroyForcibly()
}
