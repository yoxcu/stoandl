package de.yoxcu.stoandl.pebble

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ScanFailuresTest {
    private val msg = "Failed to scan for watches: Bluetooth discovery didn't start (Resource Not Ready)"

    @Test
    fun `a repeating failure is logged at WARN once per message and minute`() {
        val f = ScanFailures()
        assertTrue(f.record(msg, 0, windowOpen = true).logWarn)
        assertFalse(f.record(msg, 2_000, windowOpen = true).logWarn)
        assertFalse(f.record(msg, 59_999, windowOpen = true).logWarn)
        assertTrue(f.record(msg, 60_000, windowOpen = true).logWarn)
        assertTrue(f.record("Failed to scan for classic watches", 61_000, windowOpen = true).logWarn)
    }

    @Test
    fun `the alert waits for a repeat and fires once per window`() {
        val f = ScanFailures()
        f.windowOpened()
        assertFalse(f.record(msg, 0, windowOpen = true).alert)
        assertTrue(f.record(msg, 2_000, windowOpen = true).alert)
        assertFalse(f.record(msg, 4_000, windowOpen = true).alert)
        f.windowOpened()
        assertFalse(f.record(msg, 200_000, windowOpen = true).alert)
        assertTrue(f.record(msg, 202_000, windowOpen = true).alert)
    }

    @Test
    fun `failures outside a pairing window never alert`() {
        val f = ScanFailures()
        repeat(3) { assertFalse(f.record(msg, it * 2_000L, windowOpen = false).alert) }
    }

    @Test
    fun `the PairStatus note lasts while failures keep coming`() {
        val f = ScanFailures()
        assertNull(f.note(0))
        f.record(msg, 1_000, windowOpen = true)
        assertEquals("$PAIRING_SCAN_FAILED$msg", f.note(1_000))
        assertEquals("$PAIRING_SCAN_FAILED$msg", f.note(10_999))
        assertNull(f.note(11_000))
        f.record(msg, 20_000, windowOpen = true)
        f.windowOpened()
        assertNull(f.note(20_000))
    }

    @Test
    fun `a timed-out window names a recent scan failure`() {
        val f = ScanFailures()
        assertEquals("timeout:Pairing timed out", f.timedOut(0))
        f.record(msg, 100_000, windowOpen = true)
        assertEquals(
            "timeout:Pairing timed out — failed to scan for watches: Bluetooth discovery didn't start (Resource Not Ready)",
            f.timedOut(120_000),
        )
        assertEquals("timeout:Pairing timed out", f.timedOut(130_000))
    }
}
