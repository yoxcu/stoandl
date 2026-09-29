package de.yoxcu.stoandl.pebble

import io.rebble.libpebblecommon.connection.LibPebble
import io.rebble.libpebblecommon.database.dao.WatchPreference
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A notification timeout under 15 s makes a notification vanish at once and cancels its vibration on
 * every released firmware (the fw's own clamp is only on PebbleOS `main`). The floor is libpebble3's
 * (`NotificationTimeoutMs.min`, upstream 912fde2c), and [WatchPrefsControl] range-checks every number
 * pref against its own bounds, so there is no second copy of the 15 s here. These tests pin that the
 * floor reaches both the CLI/GUI path and the `watch.*` config pins.
 */
class WatchPrefsControlTest {
    private val written = mutableListOf<WatchPreference<*>>()
    private val control = WatchPrefsControl(fakeLibPebble(), resolveAppUuid = { null }, appName = { null })

    @Test
    fun `a notification timeout under 15 s is refused`() {
        val r = control.setOne("notifWindowTimeout", "5000")
        assertTrue(r.startsWith("error:"), r)
        assertContains(r, "15000..")
        assertTrue(written.isEmpty(), "nothing may reach the watch")

        assertTrue(control.setOne("notifWindowTimeout", "15000").startsWith("ok:"))
        assertEquals(15_000L, written.single().value)
    }

    @Test
    fun `a pinned timeout under 15 s is skipped on connect`() {
        control.applyConfigured(mapOf("notifWindowTimeout" to "3000", "clock24h" to "true"))
        assertEquals(listOf("clock24h"), written.map { it.pref.id }, "the bad pin is skipped, the rest applied")
    }

    private fun fakeLibPebble(): LibPebble =
        Proxy.newProxyInstance(LibPebble::class.java.classLoader, arrayOf(LibPebble::class.java)) { proxy, method, args ->
            when (method.name) {
                "setWatchPref" -> written.add(args!![0] as WatchPreference<*>).let { null }
                "toString" -> "fake LibPebble"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.firstOrNull()
                else -> throw UnsupportedOperationException("LibPebble.${method.name}")
            }
        } as LibPebble
}
