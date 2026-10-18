package de.yoxcu.stoandl.pebble

import io.rebble.libpebblecommon.connection.WatchConnector
import java.io.File
import java.lang.reflect.Proxy
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The manual disconnect is one fork hold, released exactly once, and its marker decides restarts. */
class ManualDisconnectTest {
    private val dir = Files.createTempDirectory("stoandl-manual-disconnect").toFile()
    private val marker = File(dir, "watch-disconnected")
    private var open = 0
    private var taken = 0
    private val connector = Proxy.newProxyInstance(
        WatchConnector::class.java.classLoader, arrayOf(WatchConnector::class.java),
    ) { _, method, _ ->
        check(method.name == "holdDisconnected") { "unexpected ${method.name}" }
        taken++
        open++
        var closed = false
        AutoCloseable { if (!closed) { closed = true; open-- } }
    } as WatchConnector

    private fun manual(persist: Boolean = true) = ManualDisconnect(connector, marker) { persist }

    @AfterTest
    fun cleanup() { dir.deleteRecursively() }

    @Test
    fun disconnectHoldsOnceAndRecordsIt() {
        val m = manual()
        assertTrue(m.disconnect())
        assertFalse(m.disconnect())
        assertEquals(1, taken)
        assertEquals(1, open)
        assertTrue(m.held)
        assertTrue(m.paused.value)
        assertTrue(marker.exists())
    }

    @Test
    fun resumeReleasesTheHoldAndTheMarker() {
        val m = manual()
        m.disconnect()
        assertTrue(m.resume())
        assertFalse(m.resume())
        assertEquals(0, open)
        assertFalse(m.held)
        assertFalse(m.paused.value)
        assertFalse(marker.exists())
    }

    @Test
    fun restartKeepsThePauseWhenPersisting() {
        manual().disconnect()
        open = 0
        val after = manual(persist = true)
        after.restore()
        assertTrue(after.held)
        assertEquals(1, open)
        assertTrue(marker.exists())
    }

    @Test
    fun restartReconnectsWhenNotPersisting() {
        manual().disconnect()
        open = 0
        val after = manual(persist = false)
        after.restore()
        assertFalse(after.held)
        assertEquals(0, open)
        assertFalse(marker.exists())
    }

    @Test
    fun restoreWithoutMarkerDoesNothing() {
        val m = manual()
        m.restore()
        assertFalse(m.held)
        assertEquals(0, taken)
    }
}
