package de.yoxcu.stoandl.notification

import de.yoxcu.stoandl.notification.AppNamePlacement.OFF
import de.yoxcu.stoandl.notification.AppNamePlacement.SMALL
import de.yoxcu.stoandl.notification.AppNamePlacement.SUBTITLE
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Which text goes into which watch attribute: the only layout control the firmware leaves the host, and
 *  a mistake there (the app name twice, a lost subtitle) only shows on the wrist. */
class NotificationLayoutTest {
    @Test
    fun `the app name goes where the placement says`() {
        // NeoChat's live group message: title = room, body = "Sender: text".
        assertEquals(WatchFields("Room", null, "NeoChat", "Alice: hi"), watchFields("NeoChat", "Room", null, "Alice: hi", SMALL))
        assertEquals(WatchFields("Room", "NeoChat", null, "Alice: hi"), watchFields("NeoChat", "Room", null, "Alice: hi", SUBTITLE))
        assertEquals(WatchFields("Room", null, null, "Alice: hi"), watchFields("NeoChat", "Room", null, "Alice: hi", OFF))
    }

    @Test
    fun `a source's own subtitle keeps the subtitle slot`() {
        // The Matrix extension: title = sender, subtitle = room.
        assertEquals(WatchFields("Alice", "#weekend", null, "dinner?"), watchFields("Matrix", "Alice", "#weekend", "dinner?", SUBTITLE))
        assertEquals(WatchFields("Alice", "#weekend", "Matrix", "dinner?"), watchFields("Matrix", "Alice", "#weekend", "dinner?", SMALL))
        assertEquals(WatchFields("Alice", "#weekend", null, "dinner?"), watchFields("Matrix", "Alice", "#weekend", "dinner?", OFF))
    }

    @Test
    fun `an empty or blank source subtitle is no subtitle`() {
        assertEquals("Matrix", watchFields("Matrix", "Alice", "", "x", SUBTITLE).subtitle)
        assertEquals("Matrix", watchFields("Matrix", "Alice", "  ", "x", SUBTITLE).subtitle)
        assertNull(watchFields("Matrix", "Alice", "", "x", SMALL).subtitle)
    }

    @Test
    fun `the app name is not repeated when it is the title`() {
        // NeoChat titles a DM with the peer's name, which can be the app's own name; case and spaces don't count.
        assertEquals(WatchFields("neochat ", null, null, "x"), watchFields("NeoChat", "neochat ", null, "x", SMALL))
        assertEquals(WatchFields("NeoChat", null, null, "x"), watchFields("NeoChat", "NeoChat", null, "x", SUBTITLE))
    }

    @Test
    fun `the app name is not repeated when it is the source subtitle`() {
        assertEquals(WatchFields("Alice", "matrix", null, "x"), watchFields("Matrix", "Alice", "matrix", "x", SMALL))
        assertEquals(WatchFields("Alice", " Matrix", null, "x"), watchFields("Matrix", "Alice", " Matrix", "x", SUBTITLE))
    }

    @Test
    fun `a blank app name is never shown`() {
        listOf(SMALL, SUBTITLE, OFF).forEach { p ->
            assertEquals(WatchFields("T", null, null, "b"), watchFields("", "T", null, "b", p), "$p")
            assertEquals(WatchFields("T", null, null, "b"), watchFields("  ", "T", null, "b", p), "$p")
        }
    }

    @Test
    fun `the app name is trimmed, the title is kept as sent`() {
        assertEquals(WatchFields(" T ", null, "App", null), watchFields(" App ", " T ", null, "", SMALL))
    }

    @Test
    fun `an empty body is not sent and an empty title still is`() {
        assertEquals(WatchFields("", null, "App", null), watchFields("App", "", null, "", SMALL))
        assertEquals(" ", watchFields("App", "T", null, " ", SMALL).body)
    }

    @Test
    fun `placement tokens parse case-insensitively and unknown ones are null`() {
        assertEquals(SMALL, AppNamePlacement.parse("small"))
        assertEquals(SUBTITLE, AppNamePlacement.parse("Subtitle"))
        assertEquals(OFF, AppNamePlacement.parse(" OFF "))
        assertNull(AppNamePlacement.parse("huge"))
        assertNull(AppNamePlacement.parse(""))
        assertNull(AppNamePlacement.parse("default"))
    }
}
