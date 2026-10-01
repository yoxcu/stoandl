package de.yoxcu.stoandl.dbus

import org.freedesktop.dbus.types.Variant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The monitor's message parsing and cross-source dedupe: pure logic that fails silently on hardware. */
class NotificationParsingTest {
    @Test
    fun `Notify actions pair keys with labels from a list or an array`() {
        val flat = listOf("default", "", "inline-reply", "Reply", "mark-read", "Mark as read")
        val expected = listOf("default" to "", "inline-reply" to "Reply", "mark-read" to "Mark as read")
        assertEquals(expected, parseNotifyActions(flat))
        assertEquals(expected, parseNotifyActions(flat.toTypedArray()))
    }

    @Test
    fun `an unpaired trailing key and empty keys are dropped`() {
        assertEquals(listOf("a" to "A"), parseNotifyActions(listOf("a", "A", "", "x", "dangling")))
        assertEquals(emptyList(), parseNotifyActions(null))
        assertEquals(emptyList(), parseNotifyActions("not-a-list"))
    }

    @Test
    fun `the transient hint reads as a boolean or an integer, absent = false`() {
        val dict = mapOf("transient" to Variant(true), "one" to Variant(1.toByte()), "zero" to Variant(0))
        assertTrue(dictBool(dict, "transient"))
        assertTrue(dictBool(dict, "one"))
        assertFalse(dictBool(dict, "zero"))
        assertFalse(dictBool(dict, "missing"))
        assertFalse(dictBool(null, "transient"))
    }

    @Test
    fun `dict strings unwrap nested variants and ignore empty values`() {
        val dict = mapOf(
            "title" to Variant("Hello"),
            "body" to Variant(Variant("World")),
            "empty" to Variant(""),
            "count" to Variant(3),
        )
        assertEquals("Hello", dictString(dict, "title"))
        assertEquals("World", dictString(dict, "body"))
        assertNull(dictString(dict, "empty"))
        assertNull(dictString(dict, "count"))
        assertNull(dictString(dict, "missing"))
        assertNull(dictString("not-a-map", "title"))
    }

    @Test
    fun `a GTK notification forwarded from the portal is admitted once`() {
        val d = CrossSourceDedupe(windowMs = 2_000)
        assertTrue(d.admitPortalOrGtk("org.gnome.Fractal", "msg-1", "Alice", nowMs = 1_000))
        // xdg-desktop-portal-gnome re-posts it on org.gtk.Notifications with the same (app_id, id).
        assertFalse(d.admitPortalOrGtk("org.gnome.Fractal", "msg-1", "Alice", nowMs = 1_050))
        // A different id from the same app is a different notification.
        assertTrue(d.admitPortalOrGtk("org.gnome.Fractal", "msg-2", "Alice", nowMs = 1_060))
        // The same id again after the window is a replacement the app sent later.
        assertTrue(d.admitPortalOrGtk("org.gnome.Fractal", "msg-1", "Alice", nowMs = 5_000))
    }

    @Test
    fun `a portal notification that xdg-desktop-portal-kde also turns into Notify is admitted once`() {
        val d = CrossSourceDedupe(windowMs = 2_000)
        assertTrue(d.admitPortalOrGtk("org.kde.neochat", "42", "Bob", nowMs = 1_000))
        assertFalse(d.admitFdo(desktopEntry = "org.kde.neochat", title = "Bob", nowMs = 1_100))
        // A native Notify with another title, or without the hint, is unaffected.
        assertTrue(d.admitFdo(desktopEntry = "org.kde.neochat", title = "Carol", nowMs = 1_100))
        assertTrue(d.admitFdo(desktopEntry = null, title = "Bob", nowMs = 1_100))
        assertTrue(d.admitFdo(desktopEntry = "org.kde.neochat", title = "Bob", nowMs = 4_000))
    }

    @Test
    fun `the desktop Name comes from the Desktop Entry group only`() {
        val lines = listOf(
            "[Desktop Action new]", "Name=New Window",
            "[Desktop Entry]", "Type=Application", "Name[de]=NeoChat DE", "Name=NeoChat",
        )
        assertEquals("NeoChat", DesktopAppNames.desktopName(lines))
        assertNull(DesktopAppNames.desktopName(listOf("[Desktop Entry]", "Type=Application")))
    }
}
