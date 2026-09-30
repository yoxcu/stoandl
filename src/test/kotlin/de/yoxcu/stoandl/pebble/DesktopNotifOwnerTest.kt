@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package de.yoxcu.stoandl.pebble

import de.yoxcu.stoandl.dbus.IncomingNotification
import de.yoxcu.stoandl.dbus.NotifSource
import org.freedesktop.dbus.types.UInt32
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.uuid.Uuid

class DesktopNotifOwnerTest {
    @Test
    fun `route tokens round-trip for every source`() {
        val refs = listOf(
            DesktopNotifRef.Fdo(":1.42", 7),
            DesktopNotifRef.Fdo(null, 7),
            DesktopNotifRef.Portal("org.freedesktop.impl.portal.desktop.plasmanotify", "org.kde.neochat", "a:b|c"),
            DesktopNotifRef.Gtk("org.gnome.Fractal", "room/1"),
        )
        refs.forEach { assertEquals(it, DesktopNotifRef.decode(it.encode())) }
    }

    @Test
    fun `a bare numeric token is an FDO id of an unknown server`() {
        assertEquals(DesktopNotifRef.Fdo(null, 12), DesktopNotifRef.decode("12"))
        assertNull(DesktopNotifRef.decode(null))
        assertNull(DesktopNotifRef.decode("garbage"))
        assertNull(DesktopNotifRef.decode("fdo\u001f:1.2\u001fnot-a-number"))
    }

    @Test
    fun `a ref is built from each kind of monitored notification`() {
        val fdo = IncomingNotification(UInt32(5), "App", "t", "b", owner = ":1.9")
        assertEquals(DesktopNotifRef.Fdo(":1.9", 5), DesktopNotifRef.of(fdo))
        val portal = IncomingNotification(UInt32(0), "App", "t", "b", NotifSource.PORTAL,
            appId = "org.x", portalId = "1", backend = ":1.20")
        assertEquals(DesktopNotifRef.Portal(":1.20", "org.x", "1"), DesktopNotifRef.of(portal))
        // A portal notification without the backend it went to can't be dismissed: no ref.
        assertNull(DesktopNotifRef.of(portal.copy(backend = null)))
        val gtk = IncomingNotification(UInt32(0), "App", "t", "b", NotifSource.GTK, appId = "org.y", portalId = "2")
        assertEquals(DesktopNotifRef.Gtk("org.y", "2"), DesktopNotifRef.of(gtk))
    }

    @Test
    fun `server capabilities come from the org_kde_NotificationManager interface only`() {
        val patched = """
            <node><interface name="org.freedesktop.Notifications"><method name="Notify"/></interface>
            <interface name="org.kde.NotificationManager">
              <method name="RegisterWatcher"/><method name="InvokeAction"><arg type="u"/></method>
              <method name="InvokeReply"><arg type="u"/><arg type="s"/></method>
            </interface></node>""".trimIndent()
        assertEquals(ServerCaps(invokeAction = true, invokeReply = true), ServerCaps.fromIntrospection(patched))
        val stock = patched.replace("""<method name="InvokeReply"><arg type="u"/><arg type="s"/></method>""", "")
        assertEquals(ServerCaps(invokeAction = true, invokeReply = false), ServerCaps.fromIntrospection(stock))
        // GNOME: no KDE interface; a method of that name elsewhere doesn't count.
        val gnome = """<node><interface name="org.freedesktop.Notifications"><method name="InvokeReply"/></interface></node>"""
        assertEquals(ServerCaps.NONE, ServerCaps.fromIntrospection(gnome))
    }

    @Test
    fun `wrist actions skip default and inline-reply and are capped`() {
        val actions = listOf("default" to "Open", "inline-reply" to "Reply", "a" to "A", "b" to "", "c" to "C", "d" to "D", "e" to "E")
        assertEquals(listOf("a" to "A", "c" to "C", "d" to "D"), wristActions(actions, max = 3))
        assertEquals("Reply", inlineReplyLabel(actions))
        assertEquals("Reply", inlineReplyLabel(listOf("inline-reply" to " ")))
        assertNull(inlineReplyLabel(listOf("a" to "A")))
    }

    @Test
    fun `removeOwner drops only that owner's routes`() {
        val t = NotifRouteTable()
        val a = Uuid.random(); val b = Uuid.random(); val c = Uuid.random()
        t.put(a, NotifRoute("desktop")); t.put(b, NotifRoute("desktop")); t.put(c, NotifRoute("matrix"))
        assertEquals(2, t.removeOwner("desktop"))
        assertNull(t.get(a)); assertNull(t.get(b)); assertEquals("matrix", t.get(c)?.ownerId)
    }

    @Test
    fun `an owner change drops desktop routes, the first report does not`() {
        val t = NotifRouteTable()
        val owner = DesktopNotifOwner(t)
        val a = Uuid.random()
        t.put(a, NotifRoute("desktop"))
        owner.onEvent(de.yoxcu.stoandl.dbus.NotificationEvent.OwnerChanged(":1.5"))
        assertEquals("desktop", t.get(a)?.ownerId)
        owner.onEvent(de.yoxcu.stoandl.dbus.NotificationEvent.OwnerChanged(":1.5"))
        assertEquals("desktop", t.get(a)?.ownerId)
        owner.onEvent(de.yoxcu.stoandl.dbus.NotificationEvent.OwnerChanged(":1.77"))
        assertNull(t.get(a))
    }
}
