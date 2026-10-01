package de.yoxcu.stoandl.dbus

import org.freedesktop.dbus.types.UInt32
import org.freedesktop.dbus.types.Variant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The monitor's call/reply correlation and owner tracking, without a bus. */
class NotificationMonitorStateTest {
    private val events = mutableListOf<NotificationEvent>()
    private var now = 1_000_000L
    private val state = NotificationMonitorState(emit = { events += it }, nowMs = { now })

    private fun notify(app: String, summary: String, actions: List<String> = emptyList()): Array<Any?> =
        arrayOf(app, UInt32(0), "", summary, "body of $summary", actions, emptyMap<String, Variant<*>>(), -1)

    private fun posted() = events.filterIsInstance<NotificationEvent.Posted>().map { it.notification }

    @Test
    fun `two callers with the same serial each get their own reply`() {
        state.onNotifyCall(":1.10", 7, notify("NeoChat", "from neochat", listOf("default", "Open")))
        state.onNotifyCall(":1.11", 7, notify("Spacebar", "from spacebar"))
        state.onReturn(":1.37", ":1.11", 7) { arrayOf(UInt32(42)) }
        state.onReturn(":1.37", ":1.10", 7) { arrayOf(UInt32(43)) }

        val (first, second) = posted()
        assertEquals(listOf("Spacebar", "from spacebar", 42L), listOf(first.appName, first.summary, first.id.toLong()))
        assertEquals(listOf("NeoChat", "from neochat", 43L), listOf(second.appName, second.summary, second.id.toLong()))
        assertEquals(listOf("default" to "Open"), second.actions)
    }

    @Test
    fun `the owner of a notification is the server that answered`() {
        state.onNotifyCall(":1.10", 3, notify("NeoChat", "hi"))
        state.onReturn(":1.52", ":1.10", 3) { arrayOf(UInt32(1)) }
        assertEquals(":1.52", posted().single().owner)
    }

    @Test
    fun `a reply to another call is ignored without reading its body`() {
        state.onNotifyCall(":1.10", 3, notify("NeoChat", "hi"))
        // e.g. the monitor connection's own BecomeMonitor reply, or the server answering GetCapabilities
        state.onReturn("org.freedesktop.DBus", ":1.99", 3) { error("body read") }
        state.onReturn(":1.37", ":1.10", 4) { error("body read") }
        assertTrue(posted().isEmpty())
        // the pending call is still there for its real reply
        state.onReturn(":1.37", ":1.10", 3) { arrayOf(UInt32(5)) }
        assertEquals(5L, posted().single().id.toLong())
    }

    @Test
    fun `a call made before the server existed is answered by the server it activated`() {
        state.onNotifyCall(":1.10", 2, notify("NeoChat", "at boot"))
        state.onOwnerChanged(null, ":1.37")
        state.onReturn(":1.37", ":1.10", 2) { arrayOf(UInt32(1)) }
        assertEquals(
            listOf<Any>(NotificationEvent.OwnerChanged(":1.37"), "at boot"),
            events.map { (it as? NotificationEvent.Posted)?.notification?.summary ?: it },
        )
    }

    @Test
    fun `owner transitions are reported once each`() {
        state.onOwnerChanged(null, ":1.37")
        state.onOwnerChanged(":1.37", null)
        state.onOwnerChanged(null, ":1.52")
        state.onOwnerChanged(":1.52", ":1.60")
        state.onOwnerChanged(":1.60", ":1.60")
        assertEquals(listOf(":1.37", null, ":1.52", ":1.60"), events.map { (it as NotificationEvent.OwnerChanged).owner })
    }

    @Test
    fun `a closed notification names the server that closed it`() {
        state.onClosed(":1.37", arrayOf(UInt32(9), UInt32(2)))
        assertEquals(NotificationEvent.Closed(":1.37", UInt32(9), UInt32(2)), events.single())
    }

    @Test
    fun `stale calls age out once the table is full`() {
        state.onNotifyCall(":1.10", 1, notify("Old", "old"))
        now += 60_000
        for (i in 2L..256L) state.onNotifyCall(":1.10", i, notify("App", "n$i"))
        state.onNotifyCall(":1.10", 257, notify("App", "n257"))
        state.onReturn(":1.37", ":1.10", 1) { arrayOf(UInt32(1)) }
        state.onReturn(":1.37", ":1.10", 257) { arrayOf(UInt32(2)) }
        assertEquals(listOf("n257"), posted().map { it.summary })
    }
}
