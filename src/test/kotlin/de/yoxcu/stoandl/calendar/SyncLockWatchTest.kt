package de.yoxcu.stoandl.calendar

import de.yoxcu.stoandl.util.Flock
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration

/**
 * The calendar file watch and a sync tool's lock (Calindori's `lockFile`): files changed while the lock is
 * held make one change signal once it is let go, not one per file; without a held lock a change signals at once,
 * and so does a file whose calendar has no lock while another calendar's sync holds its own.
 */
class SyncLockWatchTest {
    private val dir = Files.createTempDirectory("synclock").toFile()
    private val lock = File(dir.parentFile, "${dir.name}.lock")

    @AfterTest
    fun tearDown() {
        dir.deleteRecursively()
        lock.delete()
    }

    private val calendar = LinuxSystemCalendar(
        sources = { emptyList() }, intervalMinutes = { 600 }, watchDirs = { listOf(dir) }, syncLocks = { listOf("a.ics", "b.ics", "c.ics").associate { File(dir, it).absoluteFile.normalize() to lock } },
    )

    @Test
    fun `changes during a sync signal once, after it`() = runBlocking {
        val signals = AtomicInteger()
        val watch = launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
            calendar.registerForCalendarChanges().collect { signals.incrementAndGet() }
        }
        try {
            delay(300) // the watch is registered

            // No sync: a change signals at once (once or twice: its create and its write may come apart).
            File(dir, "a.ics").writeText("BEGIN:VCALENDAR\nEND:VCALENDAR\n")
            withTimeout(5_000) { while (signals.get() < 1) delay(20) }
            delay(300)
            val before = signals.get()

            // A sync writes three files, seconds apart in real life.
            val sync = Flock.acquire(lock, Duration.ZERO)!!
            for (name in listOf("a.ics", "b.ics", "c.ics")) {
                File(dir, name).writeText("BEGIN:VCALENDAR\nX-$name:1\nEND:VCALENDAR\n")
                delay(200)
            }
            delay(500)
            assertEquals(before, signals.get(), "nothing while the sync holds the lock")
            // A calendar without the lock changes meanwhile: read at once, not after the sync.
            File(dir, "local.ics").writeText("BEGIN:VCALENDAR\nEND:VCALENDAR\n")
            withTimeout(5_000) { while (signals.get() < before + 1) delay(20) }
            delay(500)
            val local = signals.get()
            File(dir, "b.ics").writeText("BEGIN:VCALENDAR\nX-b:2\nEND:VCALENDAR\n")
            delay(500)
            assertEquals(local, signals.get(), "the sync's files still wait for it")
            sync.close()
            withTimeout(5_000) { while (signals.get() < local + 1) delay(20) }
            delay(1_000)
            assertEquals(local + 1, signals.get(), "one signal for the whole sync")
        } finally {
            watch.cancel()
        }
    }
}
