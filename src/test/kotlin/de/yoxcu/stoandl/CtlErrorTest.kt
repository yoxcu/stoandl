package de.yoxcu.stoandl

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** How the CLI reports a failed D-Bus call: the message and the exit code a script sees. */
class CtlErrorTest {
    @Test
    fun `a daemon that isn't running is exit 3 with the start command`() {
        for (name in listOf("org.freedesktop.dbus.errors.ServiceUnknown", "org.freedesktop.DBus.Error.NameHasNoOwner")) {
            val (message, code) = ctlError(name, "The name de.yoxcu.stoandl was not provided by any .service files")
            assertEquals(EXIT_UNREACHABLE, code)
            assertTrue("systemctl --user start stoandl" in message && "rc-service stoandl start" in message, message)
        }
    }

    @Test
    fun `an unknown method means the CLI and daemon versions differ`() {
        val (message, code) = ctlError("org.freedesktop.dbus.errors.UnknownMethod", "No such method")
        assertEquals(EXIT_FAILURE, code)
        assertTrue("versions differ" in message, message)
    }

    @Test
    fun `no reply in time says the daemon may still be working`() {
        val (message, code) = ctlError("org.freedesktop.dbus.errors.NoReply", "No reply within specified time")
        assertEquals(EXIT_FAILURE, code)
        assertTrue("didn't answer in time" in message, message)
    }

    @Test
    fun `any other error passes the daemon's message on`() {
        assertEquals("Error: watch busy" to EXIT_FAILURE,
            ctlError("org.freedesktop.dbus.exceptions.DBusExecutionException", "watch busy"))
        assertEquals("Error: org.freedesktop.dbus.exceptions.DBusExecutionException" to EXIT_FAILURE,
            ctlError("org.freedesktop.dbus.exceptions.DBusExecutionException", ""), "no message: the error name")
    }
}
