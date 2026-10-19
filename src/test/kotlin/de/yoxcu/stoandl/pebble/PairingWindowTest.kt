package de.yoxcu.stoandl.pebble

import de.yoxcu.stoandl.pebble.PairingWindow.ClassicStep
import de.yoxcu.stoandl.pebble.PairingWindow.Connecting
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** QOL-G3 and field/CLASSIC-PAIR.md: a pairing window pairs a second watch while the first is connected or
 *  away, the connect goal has one owner, and a window that fails gives the goal back. */
class PairingWindowTest {
    private val time2 = "ED:86:0A:D4:B3:49"
    private val steel = "B0:B4:48:B6:1E:81"

    @Test
    fun `a known Classic watch is never the handler's, whatever the window`() {
        // The airplane flip-flop: the Steel known and disconnected, the follower arming the Time 2.
        assertFalse(PairingWindow.classicCandidate(known = true, active = false, btOn = true))
        assertFalse(PairingWindow.classicCandidate(known = false, active = true, btOn = true))
        assertFalse(PairingWindow.classicCandidate(known = false, active = false, btOn = false))
        assertTrue(PairingWindow.classicCandidate(known = false, active = false, btOn = true))
    }

    @Test
    fun `a new Classic watch is paired or connected only inside a window`() {
        assertEquals(ClassicStep.CONNECT, PairingWindow.classicStep(windowOpen = true, bonded = true))
        assertEquals(ClassicStep.PAIR_THEN_CONNECT, PairingWindow.classicStep(windowOpen = true, bonded = false))
        assertEquals(ClassicStep.HINT, PairingWindow.classicStep(windowOpen = false, bonded = false))
        assertEquals(ClassicStep.NONE, PairingWindow.classicStep(windowOpen = false, bonded = true))
    }

    @Test
    fun `a bond BlueZ can't read starts nothing`() {
        // The 23:52:38 "createBondClassic: not found": the device object was gone, not unbonded.
        assertEquals(ClassicStep.NONE, PairingWindow.classicStep(windowOpen = true, bonded = null))
        assertEquals(ClassicStep.NONE, PairingWindow.classicStep(windowOpen = false, bonded = null))
    }

    @Test
    fun `the BLE scan steps aside for any connecting watch`() {
        assertTrue(PairingWindow.discoveryDisturbs(inquiry = false, listOf(Connecting(classic = false, linkUp = false))))
        assertTrue(PairingWindow.discoveryDisturbs(inquiry = false, listOf(Connecting(classic = true, linkUp = false))))
        assertFalse(PairingWindow.discoveryDisturbs(inquiry = false, emptyList()))
    }

    @Test
    fun `the inquiry steps aside for a handshake or a Classic connect, not for an away BLE watch arming`() {
        assertTrue(PairingWindow.discoveryDisturbs(inquiry = true, listOf(Connecting(classic = false, linkUp = true))))
        assertTrue(PairingWindow.discoveryDisturbs(inquiry = true, listOf(Connecting(classic = true, linkUp = false))))
        // Field case (2): the Time 2 in airplane mode holds the goal; the Steel must still be found.
        assertFalse(PairingWindow.discoveryDisturbs(inquiry = true, listOf(Connecting(classic = false, linkUp = false))))
    }

    @Test
    fun `a connected watch doesn't stop the scan, a disturbed connection or a suspend does`() {
        assertTrue(PairingWindow.scanAllowed(windowOpen = true, disturbs = false, hostSuspending = false))
        assertFalse(PairingWindow.scanAllowed(windowOpen = true, disturbs = true, hostSuspending = false))
        assertFalse(PairingWindow.scanAllowed(windowOpen = true, disturbs = false, hostSuspending = true))
        assertFalse(PairingWindow.scanAllowed(windowOpen = false, disturbs = false, hostSuspending = false))
    }

    @Test
    fun `the watch connected at the start doesn't end the window`() {
        val w = PairingWindow.NewWatch(knownAtOpen = setOf(time2), repairing = null, connectedAtOpen = setOf(time2))
        assertFalse(w.connected(listOf(time2)))
    }

    @Test
    fun `a known watch coming back doesn't end it, the new one does`() {
        // The Time 2 was away when the window opened and returns while the Steel is being paired.
        val w = PairingWindow.NewWatch(knownAtOpen = setOf(time2), repairing = null, connectedAtOpen = emptySet())
        assertFalse(w.connected(listOf(time2)))
        assertTrue(w.connected(listOf(steel)))
        assertTrue(w.connected(listOf(time2, steel)))
    }

    @Test
    fun `with nothing known at the start any watch counts`() {
        val w = PairingWindow.NewWatch(knownAtOpen = emptySet(), repairing = null, connectedAtOpen = emptySet())
        assertTrue(w.connected(listOf(time2)))
        assertFalse(w.connected(emptyList()))
    }

    @Test
    fun `a repaired watch still connected counts only once it has been down`() {
        // Repair forgets it and drops the link asynchronously: the first look still sees it connected.
        val w = PairingWindow.NewWatch(knownAtOpen = setOf(time2), repairing = time2, connectedAtOpen = setOf(time2))
        assertFalse(w.connected(listOf(time2)))
        assertFalse(w.connected(emptyList()))
        assertTrue(w.connected(listOf(time2)))
    }

    @Test
    fun `a repaired watch that was away counts when it connects`() {
        val w = PairingWindow.NewWatch(knownAtOpen = setOf(time2), repairing = time2, connectedAtOpen = emptySet())
        assertTrue(w.connected(listOf(time2)))
    }

    @Test
    fun `a window that ends without its new watch gives the goal back`() {
        assertEquals(time2, PairingWindow.restoreGoalTo("timeout:Pairing timed out", time2))
        assertEquals(time2, PairingWindow.restoreGoalTo("error:Pairing declined", time2))
        assertNull(PairingWindow.restoreGoalTo("ok:Paired and connected", time2))
        assertNull(PairingWindow.restoreGoalTo("ok:Paired", time2))
        assertNull(PairingWindow.restoreGoalTo("timeout:Pairing timed out", null))
    }
}
