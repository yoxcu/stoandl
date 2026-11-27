package de.yoxcu.stoandl.util

import io.rebble.libpebblecommon.connection.CommonConnectedDevice
import io.rebble.libpebblecommon.connection.LibPebble
import io.rebble.libpebblecommon.connection.PebbleDevice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** libpebble3's watches as stoandl reads them: one StateFlow, holding the known watches from its first value on. */
class WatchesTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @AfterTest
    fun cleanup() = scope.cancel()

    /** A LibPebble whose only working member is [watches] (libpebble3's: nothing until the known watches load). */
    private fun libPebble(watches: Flow<List<PebbleDevice>>): LibPebble =
        Proxy.newProxyInstance(LibPebble::class.java.classLoader, arrayOf(LibPebble::class.java)) { _, method, _ ->
            check(method.name == "getWatches") { "unexpected ${method.name}" }
            watches
        } as LibPebble

    private fun watch(): CommonConnectedDevice =
        Proxy.newProxyInstance(CommonConnectedDevice::class.java.classLoader, arrayOf(CommonConnectedDevice::class.java)) { proxy, method, args ->
            when (method.name) {
                "equals" -> proxy === args?.get(0)
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "watch"
                else -> error("unexpected ${method.name}")
            }
        } as CommonConnectedDevice

    @Test
    fun `nothing is read before the known watches loaded`() {
        val lp = StoandlLibPebble(libPebble(MutableStateFlow<List<PebbleDevice>?>(null).filterNotNull()))
        assertNull(lp.watchesIfLoaded())
        assertFailsWith<IllegalStateException> { lp.watches }
        assertFailsWith<IllegalStateException> { lp.watchesNow() }
    }

    @Test
    fun `loading waits for libpebble3's first list, past the warning, and then follows it`() {
        val source = MutableStateFlow<List<PebbleDevice>?>(null)
        val lp = StoandlLibPebble(libPebble(source.filterNotNull()))
        val known: List<PebbleDevice> = listOf(watch())
        scope.launch { delay(300); source.value = known }
        lp.loadWatches(scope, warnAfter = 50.milliseconds, giveUpAfter = 30.seconds)
        assertSame(known, lp.watchesNow(), "the first value is the known watches, never an empty list")
        assertSame(known, lp.watchesIfLoaded())
        assertSame<PebbleDevice?>(known.single(), AtomicReference<LibPebble?>(lp).connectedDevice<CommonConnectedDevice>())
        val more = known + watch()
        source.value = more
        val deadline = System.currentTimeMillis() + 5_000
        while (lp.watchesNow() != more && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertEquals(more, lp.watchesNow())
        assertFailsWith<IllegalStateException> { lp.loadWatches(scope, 1.seconds, 1.seconds) }
    }

    /** libpebble3 loads the known watches in a coroutine of its own: one that threw never emits. Startup
     *  must fail then (the daemon exits), never wait for ever or go on with no watches. */
    @Test
    fun `loading gives up when libpebble3 never lists its watches`() {
        val lp = StoandlLibPebble(libPebble(MutableStateFlow<List<PebbleDevice>?>(null).filterNotNull()))
        val started = System.nanoTime()
        val e = assertFailsWith<IllegalStateException> {
            lp.loadWatches(scope, warnAfter = 50.milliseconds, giveUpAfter = 300.milliseconds)
        }
        assertTrue((System.nanoTime() - started) / 1_000_000 >= 300, "gave up only after giveUpAfter")
        assertTrue("didn't load its known watches" in e.message.orEmpty(), e.message)
        assertNull(lp.watchesIfLoaded(), "no list, not an empty one")
        assertFailsWith<IllegalStateException> { lp.watchesNow() }
    }

    @Test
    fun `watchesNow reads a test fake's StateFlow and refuses a plain Flow`() {
        val known: List<PebbleDevice> = listOf(watch())
        assertEquals(known, libPebble(MutableStateFlow(known)).watchesNow())
        assertFailsWith<IllegalStateException> { libPebble(MutableStateFlow(known).map { it }).watchesNow() }
    }
}
