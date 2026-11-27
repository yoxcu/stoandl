package de.yoxcu.stoandl.pebble

import de.yoxcu.stoandl.util.UserDirs
import java.io.File
import java.nio.file.Files
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Find my phone: which player and sound ring, and who ends a ring (no real audio: `sh` stands in for the player). */
class FindMyPhoneRingerTest {
    private val dir = Files.createTempDirectory("stoandl-ring").toFile()
    private val sound = File(dir, "alarm.oga").apply { writeText("") }

    @AfterTest
    fun cleanup() { dir.deleteRecursively() }

    private fun programs(vararg names: String): (String) -> File? = { name -> if (name in names) File("/usr/bin/$name") else null }

    @Test
    fun `the first player found plays the sound file`() {
        assertEquals(listOf("/usr/bin/pw-play", sound.path), ringCommand(programs("pw-play", "paplay"), sound))
        assertEquals(listOf("/usr/bin/paplay", sound.path), ringCommand(programs("paplay"), sound))
        assertNull(ringCommand(programs("aplay"), sound), "aplay can't play the Ogg sound")
        assertNull(ringCommand(programs("pw-play"), null), "no sound theme, no ring")
    }

    @Test
    fun `the freedesktop alarm sound comes from the first data dir that has it`() {
        val home = File(dir, "home/share"); val system = File(dir, "usr/share")
        fun put(root: File, name: String) = File(root, "sounds/freedesktop/stereo/$name.oga").apply { parentFile.mkdirs(); writeText("") }
        assertNull(freedesktopRingSound(listOf(home, system)))
        val call = put(system, "phone-incoming-call")
        assertEquals(call, freedesktopRingSound(listOf(home, system)))
        val alarm = put(system, "alarm-clock-elapsed")
        assertEquals(alarm, freedesktopRingSound(listOf(home, system)), "the alarm sound wins over the call sound")
        val own = put(home, "alarm-clock-elapsed")
        assertEquals(own, freedesktopRingSound(listOf(home, system)))
    }

    @Test
    fun `data dirs follow the XDG variables`() {
        val env = mapOf("XDG_DATA_HOME" to "/h/data", "XDG_DATA_DIRS" to "/a:relative:/b")
        assertEquals(listOf(File("/h/data"), File("/a"), File("/b")), UserDirs.dataDirs(env::get, "/h"))
        assertEquals(listOf(File("/h/.local/share"), File("/usr/local/share"), File("/usr/share")), UserDirs.dataDirs({ null }, "/h"))
    }

    /** A ringer whose "player" is `sh -c <script>`; records each start and the notification's life. */
    private inner class Fixture(script: String) {
        val starts = AtomicInteger()
        val players: MutableList<Process> = Collections.synchronizedList(mutableListOf())
        var onStop: (() -> Unit)? = null
        val withdrawn = AtomicInteger()
        val stoppedHere = CountDownLatch(1)
        val stoppedCalls = AtomicInteger()
        val ringer = FindMyPhoneRinger(
            notify = { stop -> onStop = stop; AutoCloseable { withdrawn.incrementAndGet() } },
            findProgram = programs("pw-play"),
            sound = { sound },
            startPlayer = { argv ->
                assertEquals(listOf("/usr/bin/pw-play", sound.path), argv)
                starts.incrementAndGet()
                ProcessBuilder("sh", "-c", script).start().also { players += it }
            },
        )
        fun start(): AutoCloseable = ringer.start { stoppedCalls.incrementAndGet(); stoppedHere.countDown() }
    }

    private fun Process.ended(): Boolean = waitFor(5, TimeUnit.SECONDS)

    @Test
    fun `closing the ring stops the player and withdraws the notification`() {
        val f = Fixture("sleep 30")
        val ring = f.start()
        assertTrue(f.players.single().isAlive)
        ring.close()
        assertTrue(f.players.single().ended(), "player still running")
        assertEquals(1, f.withdrawn.get())
        ring.close()
        assertEquals(1, f.withdrawn.get(), "a second close changes nothing")
        assertEquals(0, f.stoppedCalls.get(), "a close is libpebble3's own stop: no callback")
    }

    @Test
    fun `the notification's Stop ends the ring and tells libpebble3`() {
        val f = Fixture("sleep 30")
        val ring = f.start()
        f.onStop!!.invoke()
        assertTrue(f.players.single().ended(), "player still running")
        assertEquals(1, f.stoppedCalls.get())
        assertEquals(0, f.withdrawn.get(), "Stop already took the notification away")
        ring.close()
        assertEquals(1, f.stoppedCalls.get())
        assertEquals(0, f.withdrawn.get())
    }

    @Test
    fun `the sound loops until stopped`() {
        val f = Fixture("sleep 0.8")
        val ring = f.start()
        val deadline = System.currentTimeMillis() + 10_000
        while (f.starts.get() < 3 && System.currentTimeMillis() < deadline) Thread.sleep(50)
        assertTrue(f.starts.get() >= 3, "played ${f.starts.get()} time(s)")
        ring.close()
        Thread.sleep(300)
        val after = f.starts.get()
        Thread.sleep(1_200)
        assertEquals(after, f.starts.get(), "still looping after close")
        assertEquals(0, f.stoppedCalls.get())
    }

    @Test
    fun `a failing player ends the ring on this device`() {
        val f = Fixture("exit 3")
        f.start()
        assertTrue(f.stoppedHere.await(5, TimeUnit.SECONDS), "onStopped not called")
        assertEquals(1, f.starts.get(), "a failed player isn't restarted")
        assertEquals(1, f.withdrawn.get())
    }

    @Test
    fun `a player that ends at once counts as failed, never spins`() {
        val f = Fixture("exit 0")
        f.start()
        assertTrue(f.stoppedHere.await(5, TimeUnit.SECONDS), "onStopped not called")
        assertEquals(1, f.starts.get())
    }

    @Test
    fun `no player means the ring can't start`() {
        val ringer = FindMyPhoneRinger(notify = { error("no notification without a ring") }, findProgram = { null }, sound = { sound })
        assertFailsWith<IllegalStateException> { ringer.start {} }
    }
}
