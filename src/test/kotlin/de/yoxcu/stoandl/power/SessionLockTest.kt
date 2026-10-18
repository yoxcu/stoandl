package de.yoxcu.stoandl.power

import de.yoxcu.stoandl.pebble.heldBackWhileInUse
import kotlinx.coroutines.runBlocking
import org.freedesktop.dbus.DBusPath
import org.freedesktop.dbus.types.Variant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** `notification.only_when_locked`: when the device counts as in use, and what still gets through. */
class SessionLockTest {
    private val unlocked = LoginSession(graphical = true, active = true, locked = false)

    @Test
    fun `in use only with the screen not off and an active unlocked graphical session`() {
        assertTrue(deviceInUse(true, unlocked))
        assertTrue(deviceInUse(null, unlocked)) // no DRM state (a desktop without one): the session decides
        assertFalse(deviceInUse(false, unlocked))
        assertFalse(deviceInUse(true, unlocked.copy(locked = true)))
        assertFalse(deviceInUse(true, unlocked.copy(active = false)))
        assertFalse(deviceInUse(true, unlocked.copy(graphical = false)))
        assertFalse(deviceInUse(true, null)) // can't tell: forward
    }

    @Test
    fun `a logind session map gives type, active and the locked hint`() {
        val props = mapOf("Type" to Variant("wayland"), "Active" to Variant(true), "LockedHint" to Variant(true))
        assertEquals(LoginSession(graphical = true, active = true, locked = true), loginSessionOf(props))
        assertEquals(false, loginSessionOf(props + ("Type" to Variant("tty")))?.graphical)
        assertNull(loginSessionOf(mapOf("Type" to Variant("x11"))))
    }

    @Test
    fun `the display session path comes out of the user's Display struct`() {
        val path = "/org/freedesktop/login1/session/_32"
        assertEquals(path, displaySessionPath(arrayOf<Any>("2", DBusPath(path))))
        assertEquals(path, displaySessionPath(Variant(listOf("2", path), "as")))
        assertNull(displaySessionPath(arrayOf<Any>("", DBusPath("/"))))
        assertNull(displaySessionPath(null))
    }

    @Test
    fun `held back only while on, in use and not exempt, asking logind only when it matters`() = runBlocking {
        var asked = 0
        val inUse: suspend () -> Boolean = { asked++; true }
        assertFalse(heldBackWhileInUse(onlyWhenLocked = false, exempt = false, inUse = inUse))
        assertFalse(heldBackWhileInUse(onlyWhenLocked = true, exempt = true, inUse = inUse))
        assertEquals(0, asked)
        assertTrue(heldBackWhileInUse(onlyWhenLocked = true, exempt = false, inUse = inUse))
        assertFalse(heldBackWhileInUse(onlyWhenLocked = true, exempt = false, inUse = { false }))
        assertEquals(1, asked)
    }
}
