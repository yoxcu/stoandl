package de.yoxcu.stoandl.plugin

import io.rebble.libpebblecommon.plugin.Connections
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import org.freedesktop.dbus.interfaces.Properties
import org.freedesktop.dbus.types.UInt32
import org.freedesktop.dbus.types.Variant
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

private const val NM = "org.freedesktop.NetworkManager"
private const val NM_PATH = "/org/freedesktop/NetworkManager"

/**
 * [PhoneState.connection] over a private bus with a fake NetworkManager: libpebble3 collects it for as long as
 * the daemon runs, so it must re-read only on NetworkManager's signals and on resume, never on a timer.
 */
class PhoneStateTest {
    private class FakeNetworkManager : Properties {
        @Volatile var props: Map<String, Variant<*>> = nmProps("802-11-wireless", 70)
        val reads = AtomicInteger()
        override fun <A : Any?> Get(iface: String?, name: String?): A = throw UnsupportedOperationException()
        override fun <A : Any?> Set(iface: String?, name: String?, value: A) = throw UnsupportedOperationException()
        override fun GetAll(iface: String?): Map<String, Variant<*>> = props.also { reads.incrementAndGet() }
        override fun getObjectPath() = NM_PATH
    }

    /** A private bus with [FakeNetworkManager] on it as NetworkManager; [body] gets the fake, its own
     *  connection and a second, unconnected address for the client. */
    private fun withFakeNetworkManager(body: (fake: FakeNetworkManager, nm: DBusConnection, address: String) -> Unit) {
        val daemon = listOf("/usr/bin/dbus-daemon", "/bin/dbus-daemon").map(::File).firstOrNull { it.canExecute() }
        assumeTrue(daemon != null, "dbus-daemon not installed")
        val dir = kotlin.io.path.createTempDirectory("stoandl-bus").toFile()
        val proc = ProcessBuilder(daemon!!.path, "--session", "--nofork", "--print-address=1", "--address=unix:dir=${dir.path}")
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
        try {
            val address = proc.inputStream.bufferedReader().readLine()
            assumeTrue(!address.isNullOrBlank(), "dbus-daemon didn't start")
            val fake = FakeNetworkManager()
            val nm = DBusConnectionBuilder.forAddress(address).withShared(false).build() as DBusConnection
            try {
                nm.exportObject(NM_PATH, fake)
                nm.requestBusName(NM)
                body(fake, nm, address)
            } finally {
                nm.disconnect()
            }
        } finally {
            proc.destroy()
            proc.waitFor(5, TimeUnit.SECONDS)
            dir.deleteRecursively()
        }
    }

    @Test
    fun `the connection follows NetworkManager's signals and resumes, with no timer`() = withFakeNetworkManager { fake, nm, address ->
        val client = DBusConnectionBuilder.forAddress(address).withShared(false).build() as DBusConnection
        val wakeups = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        try {
            val state = PhoneState({ wakeups }, openBus = { client })
            runBlocking {
                val seen = Channel<String?>(Channel.UNLIMITED)
                val job = launch(Dispatchers.IO) { state.connection.collect { seen.send(it) } }
                suspend fun next() = withTimeout(10_000) { seen.receive() }
                try {
                    assertEquals(Connections.WIFI, next())

                    // A PropertiesChanged from NetworkManager re-reads it.
                    fake.props = nmProps("gsm", 70)
                    nm.sendMessage(Properties.PropertiesChanged(NM_PATH, NM, mapOf("PrimaryConnectionType" to Variant("gsm")), emptyList()))
                    assertEquals(Connections.CELLULAR, next())

                    // NetworkManager going and coming back (NameOwnerChanged) re-reads it.
                    nm.releaseBusName(NM)
                    assertNull(next())
                    fake.props = nmProps("802-11-wireless", 20)
                    nm.requestBusName(NM)
                    assertEquals(Connections.NONE, next())

                    // A resume re-reads it, with no signal.
                    fake.props = nmProps("802-11-wireless", 70)
                    wakeups.emit(Unit)
                    assertEquals(Connections.WIFI, next())

                    // Nothing else reads it: no timer.
                    val reads = fake.reads.get()
                    Thread.sleep(1_500)
                    assertEquals(reads, fake.reads.get())
                    assertNull(seen.tryReceive().getOrNull())
                } finally {
                    job.cancel()
                }
            }
        } finally {
            client.disconnect()
        }
    }

    /**
     * A system-bus connect that outlasts the read's 2 s wait (a loaded boot) is followed to its end: the
     * connection is read, and then followed, once the bus is there, not after the 10-minute retry.
     */
    @Test
    fun `a slow system-bus connect is read as soon as it finishes`() = withFakeNetworkManager { fake, nm, address ->
        val connects = AtomicInteger()
        val opened = AtomicReference<DBusConnection?>(null)
        val state = PhoneState({ MutableSharedFlow() }, openBus = {
            connects.incrementAndGet()
            Thread.sleep(3_500)
            (DBusConnectionBuilder.forAddress(address).withShared(false).build() as DBusConnection).also(opened::set)
        })
        try {
            runBlocking {
                val seen = Channel<String?>(Channel.UNLIMITED)
                val job = launch(Dispatchers.IO) { state.connection.collect { seen.send(it) } }
                try {
                    // No bus within the 2 s wait yet: nothing known.
                    assertNull(withTimeout(10_000) { seen.receive() })
                    // The connect ends ~1.5 s later and the connection is read then.
                    assertEquals(Connections.WIFI, withTimeout(10_000) { seen.receive() })
                    // ... and followed from there.
                    fake.props = nmProps("gsm", 70)
                    nm.sendMessage(Properties.PropertiesChanged(NM_PATH, NM, mapOf("PrimaryConnectionType" to Variant("gsm")), emptyList()))
                    assertEquals(Connections.CELLULAR, withTimeout(10_000) { seen.receive() })
                    assertEquals(1, connects.get(), "one connect, waited for, not a second attempt")
                } finally {
                    job.cancel()
                }
            }
        } finally {
            opened.get()?.disconnect()
        }
    }

    private companion object {
        fun nmProps(type: String, state: Int): Map<String, Variant<*>> =
            mapOf("PrimaryConnectionType" to Variant(type), "State" to Variant(UInt32(state.toLong())))
    }
}
