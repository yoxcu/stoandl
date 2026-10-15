@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package de.yoxcu.stoandl.pebble

import kotlinx.coroutines.GlobalScope
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.uuid.Uuid

class NotifRouteStoreTest {
    private val file = Files.createTempDirectory("routes").resolve("notification-routes.json").toFile()

    private fun store(table: NotifRouteTable, epoch: String?) =
        NotifRouteStore(file, table, @Suppress("OPT_IN_USAGE") GlobalScope) { epoch }

    @Test
    fun `routes and the desktop epoch survive a restart`() {
        val a = Uuid.random()
        val b = Uuid.random()
        val route = NotifRoute("desktop", "fdo\u001f:1.42\u001f7", mutePkg = "neochat", muteActionId = 2,
            replyActionId = 1, namedActions = mapOf(0 to "view"))
        val before = NotifRouteTable()
        assertNull(store(before, "bus-1").load())
        before.put(a, route)
        before.put(b, NotifRoute("matrix", "!room:event"))
        store(before, "bus-1").flush()

        val after = NotifRouteTable()
        assertEquals("bus-1", store(after, "bus-1").load())
        assertEquals(route, after.get(a))
        assertEquals("matrix", after.get(b)?.ownerId)
        // The saved order (age) is kept, so the oldest still goes first past the cap.
        assertEquals(listOf(a, b), after.snapshot().map { it.itemId })
    }

    @Test
    fun `a route recorded since the restart wins over the saved one`() {
        val a = Uuid.random()
        val before = NotifRouteTable()
        store(before, null).load()
        before.put(a, NotifRoute("desktop", "old"))
        store(before, null).flush()

        val after = NotifRouteTable()
        after.put(a, NotifRoute("desktop", "new"))
        store(after, null).load()
        assertEquals("new", after.get(a)?.ownerToken)
    }

    @Test
    fun `an unreadable file starts empty`() {
        file.writeText("{not json")
        val t = NotifRouteTable()
        assertNull(store(t, "bus").load())
        assertEquals(0, t.size)
    }
}
