package de.yoxcu.stoandl.pebble

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** QOL-G3: a pairing window opened while a watch is connected pairs a second watch instead of ending at
 *  once on the watch that was already there. */
class PairingWindowTest {
    @Test
    fun `the watch connected at the start doesn't end the window`() {
        assertFalse(PairingWindow.newWatchConnected(listOf("ED:86:0A:D4:B3:49"), setOf("ED:86:0A:D4:B3:49")))
    }

    @Test
    fun `a second watch connecting ends it, whichever transport`() {
        assertTrue(PairingWindow.newWatchConnected(listOf("B0:B4:48:B6:1E:81"), setOf("ED:86:0A:D4:B3:49")))
        assertTrue(PairingWindow.newWatchConnected(listOf("ED:86:0A:D4:B3:49", "B0:B4:48:B6:1E:81"), setOf("ED:86:0A:D4:B3:49")))
    }

    @Test
    fun `with nothing connected at the start any watch counts`() {
        assertTrue(PairingWindow.newWatchConnected(listOf("ED:86:0A:D4:B3:49"), emptySet()))
        assertFalse(PairingWindow.newWatchConnected(emptyList(), emptySet()))
    }

    @Test
    fun `a connected watch doesn't stop the scan, a connecting one or a suspend does`() {
        assertTrue(PairingWindow.scanAllowed(windowOpen = true, connecting = false, hostSuspending = false))
        assertFalse(PairingWindow.scanAllowed(windowOpen = true, connecting = true, hostSuspending = false))
        assertFalse(PairingWindow.scanAllowed(windowOpen = true, connecting = false, hostSuspending = true))
        assertFalse(PairingWindow.scanAllowed(windowOpen = false, connecting = false, hostSuspending = false))
    }
}
