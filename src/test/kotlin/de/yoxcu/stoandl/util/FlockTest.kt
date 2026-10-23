package de.yoxcu.stoandl.util

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** flock(2) through FFM: it excludes a second open in this process and the `flock` command (dav-sync's
 *  busybox `flock` takes the same lock), and it waits only as long as it is told to. */
class FlockTest {
    private val dir = Files.createTempDirectory("flock").toFile()
    private val lock = File(dir, "dav-sync.lock")

    @AfterTest
    fun tearDown() {
        dir.deleteRecursively()
    }

    @Test
    fun `a missing lock is free and not created by looking`() = runBlocking {
        assertFalse(Flock.isHeld(lock))
        assertTrue(Flock.awaitFree(lock, Duration.ZERO))
        assertFalse(lock.exists())
    }

    @Test
    fun `a held lock excludes another holder until it is let go`() = runBlocking {
        val held = assertNotNull(Flock.acquire(lock, Duration.ZERO))
        assertTrue(lock.isFile) // created to be held
        assertTrue(Flock.isHeld(lock))
        assertNull(Flock.acquire(lock, 150.milliseconds, poll = 50.milliseconds))
        assertFalse(Flock.awaitFree(lock, 150.milliseconds, poll = 50.milliseconds))
        held.close()
        held.close() // once only: the descriptor number may already be someone else's
        assertFalse(Flock.isHeld(lock))
        Flock.acquire(lock, Duration.ZERO)!!.close()
    }

    @Test
    fun `a waiter gets the lock once the holder lets go`() = runBlocking {
        val held = Flock.acquire(lock, Duration.ZERO)!!
        val free = async { Flock.awaitFree(lock, 5.seconds, poll = 20.milliseconds) }
        delay(100)
        held.close()
        assertTrue(free.await())

        val again = Flock.acquire(lock, Duration.ZERO)!!
        val waiter = async { Flock.acquire(lock, 5.seconds, poll = 20.milliseconds) }
        delay(100)
        again.close()
        assertNotNull(waiter.await()).close()
        assertFalse(Flock.isHeld(lock))
    }

    @Test
    fun `the flock command sees the lock and is seen`() = runBlocking {
        val flock = listOf("/usr/bin/flock", "/bin/flock").map(::File).firstOrNull { it.canExecute() } ?: return@runBlocking
        fun tryCommand(): Int {
            val p = ProcessBuilder(flock.path, "-n", lock.path, "true").redirectErrorStream(true).start()
            assertTrue(p.waitFor(10, TimeUnit.SECONDS))
            return p.exitValue()
        }
        Flock.acquire(lock, Duration.ZERO)!!.use { assertEquals(1, tryCommand()) }
        assertEquals(0, tryCommand())

        // The command holds it (for as long as its child runs) and this side sees that.
        val holder = ProcessBuilder(flock.path, lock.path, "sh", "-c", "echo held; read x").start()
        try {
            assertEquals("held", holder.inputReader().readLine())
            assertTrue(Flock.isHeld(lock))
            assertNull(Flock.acquire(lock, 100.milliseconds, poll = 50.milliseconds))
        } finally {
            holder.outputStream.close() // ends `read`, then the command lets go
            assertTrue(holder.waitFor(10, TimeUnit.SECONDS))
        }
        assertTrue(Flock.awaitFree(lock, 5.seconds, poll = 20.milliseconds))
    }
}
