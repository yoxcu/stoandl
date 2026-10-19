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
}
