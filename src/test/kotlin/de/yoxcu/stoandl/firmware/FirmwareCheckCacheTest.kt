package de.yoxcu.stoandl.firmware

import de.yoxcu.stoandl.config.StoandlConfig
import io.rebble.libpebblecommon.connection.CommonConnectedDevice
import io.rebble.libpebblecommon.connection.LibPebble
import io.rebble.libpebblecommon.connection.PebbleDevice
import io.rebble.libpebblecommon.connection.endpointmanager.FirmwareUpdater.FirmwareUpdateStatus
import io.rebble.libpebblecommon.metadata.WatchColor
import io.rebble.libpebblecommon.metadata.WatchHardwarePlatform
import io.rebble.libpebblecommon.services.FirmwareVersion
import io.rebble.libpebblecommon.services.WatchInfo
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * The firmware check cache. Each GitHub check downloads the release list, and the GUI asked twice per
 * Watch page while its UI thread waited, so a missed cache hit is a frozen window and a used-up rate
 * limit, while a stale one hides a release. Driven through a counting fake source.
 */
class FirmwareCheckCacheTest {
    private val watches = MutableStateFlow<List<PebbleDevice>>(emptyList())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val config = StoandlConfig.load(File("/nonexistent/stoandl.conf"), logResult = false)
        .copy(firmwareGithub = true, firmwareCohorts = true)
    private val clock = AtomicLong(1_000_000L)
    private val source = FakeSource()
    private val control = FirmwareControl(
        AtomicReference(fakeLibPebble()), scope, { config },
        sourceOverride = { source }, nowMs = { clock.get() },
    )

    @AfterTest
    fun tearDown() = scope.cancel()

    @Test
    fun aSecondCheckIsAnsweredFromTheCache() = runBlocking {
        watches.value = listOf(watch("v4.41.1"))
        val first = control.check()
        assertTrue(first.startsWith("ok:"), first)
        assertEquals(first, control.check())
        assertEquals(1, source.lookups.get())
    }

    @Test
    fun theCacheExpiresAfterItsTtl() = runBlocking {
        watches.value = listOf(watch("v4.41.1"))
        control.check()
        clock.addAndGet(FirmwareControl.CHECK_TTL_MS - 1)
        control.check()
        assertEquals(1, source.lookups.get())
        clock.addAndGet(1)
        control.check()
        assertEquals(2, source.lookups.get())
    }

    @Test
    fun anotherRunningVersionAsksAgain() = runBlocking {
        watches.value = listOf(watch("v4.41.1"))
        assertTrue("\tyes\t" in control.check())
        // Flashed to the latest: the answer is recomputed against the new version, from a new lookup.
        watches.value = listOf(watch("v4.41.2"))
        assertTrue("\tno\t" in control.check())
        assertEquals(2, source.lookups.get())
    }

    @Test
    fun forceBypassesAndRefreshesTheCache() = runBlocking {
        watches.value = listOf(watch("v4.41.1"))
        control.check()
        source.latest = "v4.42.0"
        assertTrue("\tv4.41.2\t" in control.check(), "still cached")
        assertTrue("\tv4.42.0\t" in control.check(force = true))
        assertTrue("\tv4.42.0\t" in control.check(), "the forced answer is cached")
        assertEquals(2, source.lookups.get())
    }

    @Test
    fun concurrentChecksShareOneLookup() = runBlocking {
        watches.value = listOf(watch("v4.41.1"))
        val gate = CompletableDeferred<Unit>()
        source.gate = gate
        val a = async(Dispatchers.Default) { control.check() }
        val b = async(Dispatchers.Default) { control.check(force = true) }
        withTimeout(5.seconds) { while (source.lookups.get() == 0) delay(5) }
        delay(100)  // give the second caller time to (wrongly) start its own lookup
        gate.complete(Unit)
        assertEquals(a.await(), b.await())
        assertEquals(1, source.lookups.get())
    }

    @Test
    fun aFailureIsReusedOnlyBriefly() = runBlocking {
        watches.value = listOf(watch("v4.41.1"))
        source.unreachable = true
        assertTrue(control.check().startsWith("error:"))
        assertTrue(control.check().startsWith("error:"))
        assertEquals(1, source.lookups.get())
        source.unreachable = false
        clock.addAndGet(FirmwareControl.ERROR_TTL_MS)
        assertTrue(control.check().startsWith("ok:"))
        assertEquals(2, source.lookups.get())
    }

    @Test
    fun aFailedRefreshKeepsAFreshGoodAnswer() = runBlocking {
        watches.value = listOf(watch("v4.41.1"))
        val good = control.check()
        assertTrue(good.startsWith("ok:"), good)
        // GitHub's 403 after the hourly budget: the refresh answers the cached result, which stays.
        source.unreachable = true
        assertEquals(good, control.check(force = true))
        assertEquals(good, control.check())
        assertEquals(2, source.lookups.get())
        // Once the good answer has expired, the failure is what's left.
        clock.addAndGet(FirmwareControl.CHECK_TTL_MS)
        assertTrue(control.check().startsWith("error:"))
    }

    @Test
    fun updateAsksAfresh() = runBlocking {
        watches.value = listOf(watch("v4.41.2"))
        assertTrue("\tno\t" in control.check())
        assertTrue(control.update().startsWith("uptodate:"))
        assertEquals(2, source.lookups.get())
    }

    @Test
    fun statusNamesACachedUpdateWithoutLookingOnline() = runBlocking {
        watches.value = listOf(watch("v4.41.1"))
        assertEquals("idle:", control.status(), "nothing checked yet: no lookup either")
        assertEquals(0, source.lookups.get())
        control.check()
        assertEquals("idle:available v4.41.2", control.status())
        clock.addAndGet(FirmwareControl.CHECK_TTL_MS)
        assertEquals("idle:", control.status(), "an expired answer isn't offered")
        assertEquals(1, source.lookups.get())
    }

    private class FakeSource : FirmwareSource {
        val lookups = AtomicInteger()
        @Volatile var latest = "v4.41.2"
        @Volatile var unreachable = false
        @Volatile var gate: CompletableDeferred<Unit>? = null
        override val label = "fake"
        override val disabledHint = "fake is off"
        override val cacheKey = "fake"
        override suspend fun resolve(boardRevision: String): FirmwareSource.Resolution {
            lookups.incrementAndGet()
            gate?.await()
            if (unreachable) return FirmwareSource.Resolution.Unreachable("fake is down")
            return FirmwareSource.Resolution.Found(latest, firmwareBundle("normal_${boardRevision}_$latest.pbz", "https://example.invalid/fw.pbz", null))
        }
    }

    private fun fakeLibPebble(): LibPebble =
        Proxy.newProxyInstance(LibPebble::class.java.classLoader, arrayOf(LibPebble::class.java)) { proxy, method, args ->
            when (method.name) {
                "getWatches" -> watches
                "toString" -> "fake LibPebble"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.firstOrNull()
                else -> throw UnsupportedOperationException("LibPebble.${method.name}")
            }
        } as LibPebble

    private fun watch(running: String): CommonConnectedDevice {
        val type = CommonConnectedDevice::class.java
        return Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { proxy, method, args ->
            when (method.name) {
                "getSerial" -> SERIAL
                "getFirmwareUpdateState" -> FirmwareUpdateStatus.NotInProgress.Idle()
                "getWatchInfo" -> watchInfo(version(running))
                "toString" -> "fake watch $running"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.firstOrNull()
                else -> throw UnsupportedOperationException("CommonConnectedDevice.${method.name}")
            }
        } as CommonConnectedDevice
    }

    private companion object {
        const val SERIAL = "Q000000000002"

        fun version(tag: String) = FirmwareVersion.from(
            tag, isRecovery = false, gitHash = "", timestamp = Instant.DISTANT_PAST,
            isDualSlot = true, isSlot0 = true,
        )!!

        fun watchInfo(running: FirmwareVersion) = WatchInfo(
            runningFwVersion = running,
            recoveryFwVersion = null,
            platform = WatchHardwarePlatform.UNKNOWN,
            bootloaderTimestamp = Instant.DISTANT_PAST,
            board = "",
            serial = SERIAL,
            btAddress = "",
            resourceCrc = 0,
            resourceTimestamp = Instant.DISTANT_PAST,
            language = "",
            languageVersion = 0,
            capabilities = emptySet(),
            isUnfaithful = false,
            healthInsightsVersion = null,
            javascriptVersion = null,
            color = WatchColor.Unknown,
        )
    }
}
