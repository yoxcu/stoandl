package de.yoxcu.stoandl.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** runCommand's deadline must hold for a child that never exits or never closes its pipes. */
class ProcessTest {
    @Test
    fun returnsStdout() {
        assertEquals("hi\n", runCommand(listOf("sh", "-c", "echo hi; echo noise >&2"), 5))
    }

    @Test
    fun nonZeroExitIsNull() {
        assertNull(runCommand(listOf("sh", "-c", "echo out; exit 3"), 5))
    }

    @Test
    fun hangingChildTimesOut() {
        val start = System.nanoTime()
        assertNull(runCommand(listOf("sh", "-c", "echo partial; sleep 30"), 1))
        assertTrue((System.nanoTime() - start) / 1_000_000 < 5_000, "deadline not honoured")
    }

    @Test
    fun loudStderrDoesNotBlock() {
        // 1 MiB on stderr overflows the pipe buffer; an undrained stderr would wedge the child
        val out = runCommand(listOf("sh", "-c", "head -c 1048576 /dev/zero >&2; echo done"), 10)
        assertEquals("done\n", out)
    }

    @Test
    fun pathProbe() {
        assertEquals("sh", onPath("sh")?.name)
        assertNull(onPath("stoandl-no-such-program"))
        assertNull(missingProgram("sh -c 'echo {lang}'"))
        assertEquals("/nonexistent/tts", missingProgram("/nonexistent/tts --fast"))
    }

    @Test
    fun stdinInBinaryOut() {
        val bytes = byteArrayOf(0, -1, 10, 'R'.code.toByte())
        kotlin.test.assertContentEquals(bytes, runProcess(listOf("cat"), 5_000, stdin = bytes)?.stdoutBytes)
    }

    /** A cancelled engine (runInterruptible) must not leave its process running. */
    @Test
    fun interruptKillsTheChild() {
        val marker = java.io.File.createTempFile("child", ".pid")
        val t = Thread { runCatching { runProcess(listOf("sh", "-c", "echo $$ > ${marker.path}; exec sleep 30"), 60_000) } }
        t.start()
        val deadline = System.nanoTime() + 5_000_000_000
        while (marker.length() == 0L && System.nanoTime() < deadline) Thread.sleep(20)
        val pid = marker.readText().trim().toLong()
        t.interrupt()
        t.join(3_000)
        assertTrue(!t.isAlive, "runProcess returned")
        Thread.sleep(200)
        assertTrue(ProcessHandle.of(pid).map { !it.isAlive }.orElse(true), "the child was killed")
        marker.delete()
    }
}
