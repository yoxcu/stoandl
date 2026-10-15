@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package de.yoxcu.stoandl.pebble

import de.yoxcu.stoandl.dbus.NotifSource
import de.yoxcu.stoandl.dbus.NotificationEvent
import io.rebble.libpebblecommon.packets.ProtocolCapsFlag
import kotlinx.coroutines.runBlocking
import org.freedesktop.dbus.types.UInt32
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/** Desktop → watch dismissals, the route table that finds the watch item, and what we claim to the watch. */
class DismissalSyncTest {
    private val routes = NotifRouteTable()
    private val retracted = mutableListOf<Uuid>()
    private var sync = true
    private val owner = DesktopNotifOwner(routes, syncDismissals = { sync }, retract = { retracted += it })

    private fun sent(ref: DesktopNotifRef): Uuid =
        Uuid.random().also { routes.put(it, NotifRoute(ownerId = "desktop", ownerToken = ref.encode())) }

    private fun closed(owner: String?, id: Long, reason: Long) =
        NotificationEvent.Closed(owner, UInt32(id), UInt32(reason))

    @Test
    fun `closed by the user or the app clears the watch item`() = runBlocking {
        val a = sent(DesktopNotifRef.Fdo(":1.5", 7))
        val b = sent(DesktopNotifRef.Fdo(":1.5", 8))
        owner.onEvent(closed(":1.5", 7, 2))
        owner.onEvent(closed(":1.5", 8, 3))
        assertEquals(listOf(a, b), retracted)
    }

    @Test
    fun `an expired popup or an undefined reason stays on the watch`() = runBlocking {
        sent(DesktopNotifRef.Fdo(":1.5", 7))
        owner.onEvent(closed(":1.5", 7, 1))
        owner.onEvent(closed(":1.5", 7, 4))
        assertTrue(retracted.isEmpty())
    }

    @Test
    fun `a close from another server instance doesn't hit a reused id`() = runBlocking {
        sent(DesktopNotifRef.Fdo(":1.5", 7))
        owner.onEvent(closed(":1.99", 7, 2))
        assertTrue(retracted.isEmpty())
        // An unknown server on either side matches by id.
        val c = sent(DesktopNotifRef.Fdo(null, 9))
        owner.onEvent(closed(":1.5", 9, 2))
        assertEquals(listOf(c), retracted)
    }

    @Test
    fun `portal and GTK withdrawals clear their own item only`() = runBlocking {
        val p = sent(DesktopNotifRef.Portal(":1.20", "org.kde.neochat", "1"))
        val g = sent(DesktopNotifRef.Gtk("org.gnome.Fractal", "1"))
        owner.onEvent(NotificationEvent.Removed(NotifSource.GTK, "org.kde.neochat", "1"))
        assertTrue(retracted.isEmpty())
        owner.onEvent(NotificationEvent.Removed(NotifSource.PORTAL, "org.kde.neochat", "1"))
        owner.onEvent(NotificationEvent.Removed(NotifSource.GTK, "org.gnome.Fractal", "1"))
        assertEquals(listOf(p, g), retracted)
    }

    @Test
    fun `nothing propagates with sync_dismissals off`() = runBlocking {
        sync = false
        sent(DesktopNotifRef.Fdo(":1.5", 7))
        owner.onEvent(closed(":1.5", 7, 2))
        assertTrue(retracted.isEmpty())
    }

    @Test
    fun `a wrist dismiss leaves no route, so the server's close report is not echoed`() = runBlocking {
        val item = sent(DesktopNotifRef.Fdo(":1.5", 7))
        routes.remove(item) // WatchActionRouter drops the route before closing the desktop original
        owner.onEvent(closed(":1.5", 7, 3))
        assertTrue(retracted.isEmpty())
    }

    @Test
    fun `routes expire by age and the oldest go first past the cap`() {
        var now = 0L
        val table = NotifRouteTable(ttlMs = 1000, maxRoutes = 3, now = { now })
        val ids = List(4) { Uuid.random() }
        ids.forEachIndexed { i, id -> now = i * 10L; table.put(id, NotifRoute(ownerId = "x")) }
        assertNull(table.get(ids[0]), "evicted past the cap")
        assertEquals(3, table.size)
        now = 1015
        assertNull(table.get(ids[1]), "older than the TTL")
        assertEquals(NotifRoute(ownerId = "x"), table.get(ids[3]))
        // A re-send refreshes the entry's age.
        table.put(ids[2], NotifRoute(ownerId = "x"))
        now = 1035
        assertEquals(NotifRoute(ownerId = "x"), table.get(ids[2]))
    }

    @Test
    fun `removeOwner and findItem only see the owner's routes`() {
        val a = Uuid.random(); val b = Uuid.random()
        routes.put(a, NotifRoute(ownerId = "matrix", ownerToken = "t"))
        routes.put(b, NotifRoute(ownerId = "desktop", ownerToken = "t"))
        assertEquals(b, routes.findItem("desktop") { it == "t" })
        assertEquals(1, routes.removeOwner("matrix"))
        assertNull(routes.findItem("matrix") { true })
    }

    @Test
    fun `the watch is told about two-way dismissal, so history items keep their actions`() {
        assertTrue(ProtocolCapsFlag.SupportsTwoWayDismissal in WatchClaims.phoneCapabilities)
        assertTrue(ProtocolCapsFlag.SupportsExtendedMusicProtocol in WatchClaims.phoneCapabilities)
        assertTrue(ProtocolCapsFlag.SupportsImageFetch in WatchClaims.phoneCapabilities)
        assertFalse(closesOnWatch(1))
    }
}
