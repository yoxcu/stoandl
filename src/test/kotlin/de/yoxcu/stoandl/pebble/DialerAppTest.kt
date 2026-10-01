package de.yoxcu.stoandl.pebble

import de.yoxcu.stoandl.config.StoandlConfig
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A wrong dialer match hides a whole app's notifications (Spacebar's SMS were all lost this way). */
class DialerAppTest {
    @Test
    fun `matches the app name or desktop-entry id exactly, ignoring case`() {
        assertTrue(isDialerApp(listOf("calls"), "Calls", null))
        assertTrue(isDialerApp(listOf("org.gnome.Calls"), "Anrufe", "org.gnome.calls"))
        assertFalse(isDialerApp(listOf("phone"), "Phonebook", null))
        assertFalse(isDialerApp(listOf("phone"), "Microphone", "org.kde.microphone"))
        assertFalse(isDialerApp(emptyList(), "Calls", null))
    }

    @Test
    fun `the default is GNOME Calls only, not Plasma Mobile's SMS app`() {
        val dialers = StoandlConfig.load(File("/nonexistent/stoandl.conf"), logResult = false).dialerApps
        assertEquals(listOf("calls"), dialers)
        assertFalse(isDialerApp(dialers, "Spacebar", "org.kde.spacebar"))
    }
}
