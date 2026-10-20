package de.yoxcu.stoandl.pebble

import de.yoxcu.stoandl.pebble.WatchTasks.Kind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.Collections
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The start-then-status contract of sideload, screenshot, logs and core dump: the call answers at once, a
 *  second start of the same kind is busy, the result stays until the next start, and every change is
 *  signalled with the same string TaskStatus returns. */
class WatchTasksTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val changes = Collections.synchronizedList(mutableListOf<Pair<String, String>>())
    private val tasks = WatchTasks(scope) { kind, status -> changes += kind.token to status }

    @AfterTest fun stop() = scope.cancel()

    private fun awaitEnd(kind: Kind) = runBlocking {
        withTimeout(5_000) { while (tasks.status(kind).startsWith("running:")) kotlinx.coroutines.delay(10) }
    }

    @Test
    fun `a task answers at once and its result stays until the next start`() {
        val release = CompletableDeferred<Unit>()
        assertEquals("idle:", tasks.status(Kind.SCREENSHOT))
        assertEquals("ok:started", tasks.start(Kind.SCREENSHOT, "Capturing") { release.await(); "ok:/p.png\t144\t168" })
        assertEquals("running:Capturing", tasks.status(Kind.SCREENSHOT))
        assertTrue(tasks.start(Kind.SCREENSHOT, "again") { "ok:" }.startsWith("busy:"))
        // Another kind isn't held up by it.
        assertEquals("ok:started", tasks.start(Kind.LOGS, "Reading") { "ok:/l.txt" })
        release.complete(Unit)
        awaitEnd(Kind.SCREENSHOT); awaitEnd(Kind.LOGS)
        assertEquals("ok:/p.png\t144\t168", tasks.status(Kind.SCREENSHOT))
        assertEquals(
            listOf("screenshot" to "running:Capturing", "screenshot" to "ok:/p.png\t144\t168"),
            changes.filter { it.first == "screenshot" },
        )
    }

    @Test
    fun `a throwing task ends as an error`() {
        tasks.start(Kind.SIDELOAD, "Installing a.pbw") { error("bad pbw") }
        awaitEnd(Kind.SIDELOAD)
        assertEquals("error:bad pbw", tasks.status(Kind.SIDELOAD))
    }

    /** A watch dropping mid-transfer cancels the work from libpebble3's connection scope: the task fails
     *  and the kind is free again, rather than `running:` until a restart. */
    @Test
    fun `work cancelled from outside ends as an error and frees the kind`() {
        tasks.start(Kind.SCREENSHOT, "Capturing") { throw CancellationException("connection scope cancelled") }
        awaitEnd(Kind.SCREENSHOT)
        assertEquals("error:the watch disconnected", tasks.status(Kind.SCREENSHOT))
        assertEquals("ok:started", tasks.start(Kind.SCREENSHOT, "Capturing") { "ok:/p.png\t144\t168" })
    }

    @Test
    fun `a task the watch never answers ends at the time limit`() {
        val short = WatchTasks(scope, maxRunMs = 100) { kind, status -> changes += kind.token to status }
        short.start(Kind.COREDUMP, "Reading") { CompletableDeferred<String>().await() }
        runBlocking { withTimeout(5_000) { while (short.status(Kind.COREDUMP).startsWith("running:")) kotlinx.coroutines.delay(10) } }
        assertEquals("error:the watch stopped answering", short.status(Kind.COREDUMP))
    }

    @Test
    fun `kinds parse from their tokens only`() {
        assertEquals(Kind.COREDUMP, Kind.of("coredump"))
        assertEquals(Kind.LOGS, Kind.of(" Logs "))
        assertNull(Kind.of("firmware"))
    }

    @Test
    fun `a requested output path must be absolute`() {
        assertEquals("/tmp/x.png", WatchTasks.outputFile(Kind.SCREENSHOT, "/tmp/x.png")?.path)
        assertNull(WatchTasks.outputFile(Kind.LOGS, "logs.txt"))
    }
}
