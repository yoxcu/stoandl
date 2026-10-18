package de.yoxcu.stoandl.dbus

import org.freedesktop.dbus.annotations.DBusInterfaceName
import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import org.freedesktop.dbus.interfaces.DBusInterface
import org.freedesktop.dbus.messages.ExportedObject
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import java.lang.reflect.Proxy
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** A stand-in for the control interface: [Slow] advances the test clock past the threshold. */
@DBusInterfaceName("de.yoxcu.stoandl.test.Probe")
interface Probe : DBusInterface {
    fun Fast(): String
    fun Slow(arg: String): String
    fun Fail(): String
}

/**
 * The WARN for a slow `de.yoxcu.stoandl.Control` method, done by exporting a timing Proxy. A slow
 * method froze the GUI for seconds and left no trace in the log; a wrapper that dbus-java can't export,
 * or that wraps exceptions, would break every method instead.
 */
class SlowCallLogTest {
    private val nanos = AtomicLong(0)
    private val warnings = CopyOnWriteArrayList<String>()

    private val probe = object : Probe {
        override fun Fast() = "fast".also { nanos.addAndGet(499_000_000) }
        override fun Slow(arg: String) = "slow $arg".also { nanos.addAndGet(612_000_000) }
        override fun Fail(): String = throw IllegalStateException("boom")
        override fun getObjectPath() = "/de/yoxcu/stoandl/test"
    }

    private fun timed(): Probe = timeSlowCalls(Probe::class.java, probe, nanoTime = { nanos.get() }, warn = { warnings += it })

    @Test
    fun onlyCallsAtOrOverTheThresholdAreLogged() {
        val p = timed()
        assertEquals("fast", p.Fast())
        assertEquals(emptyList(), warnings)
        assertEquals("slow x", p.Slow("x"))
        assertEquals(listOf("D-Bus Probe.Slow took 612 ms"), warnings)
    }

    @Test
    fun exceptionsPassThroughUnwrapped() {
        val e = assertFailsWith<IllegalStateException> { timed().Fail() }
        assertEquals("boom", e.message)
    }

    @Test
    fun dbusJavaExportsTheWrappedControlInterface() {
        val stub = Proxy.newProxyInstance(StoandlControl::class.java.classLoader, arrayOf(StoandlControl::class.java)) { _, m, _ ->
            if (m.name == "getObjectPath") STOANDL_OBJECT_PATH else throw UnsupportedOperationException(m.name)
        } as StoandlControl
        val exported = ExportedObject(timeSlowCalls(StoandlControl::class.java, stub), false)
        val xml = exported.introspectiondata
        assertTrue("de.yoxcu.stoandl.Control" in xml, xml)
        assertTrue("\"CheckFirmware\"" in xml && "\"CheckFirmwareForce\"" in xml, xml)
    }

    /** End to end over a private bus: dbus-java invokes the wrapped object and the call is timed. */
    @Test
    fun aCallOverTheBusIsTimed() {
        val daemon = listOf("/usr/bin/dbus-daemon", "/bin/dbus-daemon").map(::File).firstOrNull { it.canExecute() }
        assumeTrue(daemon != null, "dbus-daemon not installed")
        val dir = kotlin.io.path.createTempDirectory("stoandl-bus").toFile()
        val proc = ProcessBuilder(daemon!!.path, "--session", "--nofork", "--print-address=1", "--address=unix:dir=${dir.path}")
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
        try {
            val address = proc.inputStream.bufferedReader().readLine()
            assumeTrue(!address.isNullOrBlank(), "dbus-daemon didn't start")
            val server = DBusConnectionBuilder.forAddress(address).withShared(false).build() as DBusConnection
            val client = DBusConnectionBuilder.forAddress(address).withShared(false).build() as DBusConnection
            try {
                server.requestBusName("de.yoxcu.stoandl.test")
                server.exportObject("/de/yoxcu/stoandl/test", timed())
                val remote = client.getRemoteObject("de.yoxcu.stoandl.test", "/de/yoxcu/stoandl/test", Probe::class.java)
                assertEquals("slow over the bus", remote.Slow("over the bus"))
                assertEquals("fast", remote.Fast())
                assertEquals(listOf("D-Bus Probe.Slow took 612 ms"), warnings)
            } finally {
                client.disconnect()
                server.disconnect()
            }
        } finally {
            proc.destroy()
            proc.waitFor(5, TimeUnit.SECONDS)
            dir.deleteRecursively()
        }
    }
}
