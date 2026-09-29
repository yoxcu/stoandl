package de.yoxcu.stoandl.firmware

import de.yoxcu.stoandl.config.StoandlConfig
import io.rebble.libpebblecommon.connection.CommonConnectedDevice
import io.rebble.libpebblecommon.connection.ConnectedPebbleDeviceInRecovery
import io.rebble.libpebblecommon.connection.FirmwareUpdateCheckResult
import io.rebble.libpebblecommon.connection.LibPebble
import io.rebble.libpebblecommon.connection.PebbleDevice
import io.rebble.libpebblecommon.connection.endpointmanager.FirmwareUpdater.FirmwareUpdateStatus
import io.rebble.libpebblecommon.metadata.WatchColor
import io.rebble.libpebblecommon.metadata.WatchHardwarePlatform
import io.rebble.libpebblecommon.services.FirmwareVersion
import io.rebble.libpebblecommon.services.WatchInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.io.files.Path
import java.io.File
import java.lang.reflect.Proxy
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * A dual-slot watch can't flash an older firmware from normal firmware: libpebble3 then reboots it
 * into recovery (PRF) without transferring anything, and [FirmwareControl] finishes the downgrade from
 * there. The watch side needs hardware (TESTING 5.11e), but the handoff is decision logic, and a
 * mistake in it either reports a flash that never happened or re-flashes an old `.pbz` at some
 * unrelated recovery visit. So it is driven here through fake watches.
 */
class FirmwareControlDowngradeTest {
    private val watches = MutableStateFlow<List<PebbleDevice>>(emptyList())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val config = StoandlConfig.load(File("/nonexistent/stoandl.conf"), logResult = false)
    private val control = FirmwareControl(AtomicReference(fakeLibPebble()), scope, { config })
    private val pbz = File.createTempFile("normal_obelix", ".pbz")

    /** Every sideloadFirmware() call, as "normal <path>" or "recovery <path>". */
    private val flashed = CopyOnWriteArrayList<String>()

    @AfterTest
    fun tearDown() {
        scope.cancel()
        pbz.delete()
    }

    @Test
    fun downgradeIsReportedAsPrfAndFlashedFromRecovery() = runBlocking {
        control.resumeDowngradesInRecovery()
        watches.value = listOf(watch(DUAL_SLOT_4_38))
        assertEquals("ok:Flashing ${pbz.name}", control.sideload(pbz.path))
        assertEquals(listOf("normal ${pbz.path}"), flashed)

        // libpebble3 decided on the PRF route: rebooting, nothing flashed yet. Not "reboot:" (success).
        watches.value = listOf(watch(DUAL_SLOT_4_38, FirmwareUpdateStatus.WaitingForReboot(found("v4.37.0"))))
        assertEquals("prf:v4.37.0", control.status())
        // The latest release must not be offered meanwhile: one tap would undo the downgrade.
        awaitUntil { control.update().startsWith("busy:") }

        watches.value = emptyList()
        watches.value = listOf(watch(RECOVERY, recovery = true))
        awaitUntil { flashed.size == 2 }
        assertEquals("recovery ${pbz.path}", flashed[1])

        // Once only: the next emission for the same recovery session flashes nothing more.
        watches.value = listOf(watch(RECOVERY, recovery = true))
        delay(200)
        assertEquals(2, flashed.size)
        assertTrue(control.update().startsWith("disabled:"), "the pending downgrade was consumed")
    }

    @Test
    fun singleSlotDowngradeIsAnOrdinaryFlash() = runBlocking {
        control.resumeDowngradesInRecovery()
        watches.value = listOf(watch(SINGLE_SLOT_4_38))
        control.sideload(pbz.path)
        // Single-slot watches take the older image directly; WaitingForReboot is the usual success.
        watches.value = listOf(watch(SINGLE_SLOT_4_38, FirmwareUpdateStatus.WaitingForReboot(found("v4.37.0"))))
        assertEquals("reboot:", control.status())

        watches.value = emptyList()
        watches.value = listOf(watch(RECOVERY, recovery = true))
        delay(200)
        assertEquals(listOf("normal ${pbz.path}"), flashed)
    }

    @Test
    fun backOnNormalFirmwareDropsThePendingDowngrade() = runBlocking {
        control.resumeDowngradesInRecovery()
        watches.value = listOf(watch(DUAL_SLOT_4_38))
        control.sideload(pbz.path)
        watches.value = listOf(watch(DUAL_SLOT_4_38, FirmwareUpdateStatus.WaitingForReboot(found("v4.37.0"))))
        awaitUntil { control.update().startsWith("busy:") }

        // The watch left recovery without the downgrade (or never got there).
        watches.value = emptyList()
        watches.value = listOf(watch(DUAL_SLOT_4_38))
        awaitUntil { control.update().startsWith("disabled:") }

        // A later, unrelated recovery visit must not flash the old .pbz.
        watches.value = emptyList()
        watches.value = listOf(watch(RECOVERY, recovery = true))
        delay(200)
        assertEquals(listOf("normal ${pbz.path}"), flashed)
    }

    private suspend fun awaitUntil(condition: suspend () -> Boolean) = withTimeout(5.seconds) {
        while (!condition()) delay(10)
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

    /** A connected watch as one `watches` emission sees it: a new object per call, as in libpebble3. */
    private fun watch(
        running: FirmwareVersion,
        state: FirmwareUpdateStatus = FirmwareUpdateStatus.NotInProgress.Idle(),
        recovery: Boolean = false,
    ): CommonConnectedDevice {
        val type = if (recovery) ConnectedPebbleDeviceInRecovery::class.java else CommonConnectedDevice::class.java
        return Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { proxy, method, args ->
            when (method.name) {
                "getSerial" -> SERIAL
                "getFirmwareUpdateState" -> state
                "getWatchInfo" -> watchInfo(running)
                "sideloadFirmware" -> flashed.add("${if (recovery) "recovery" else "normal"} ${args!![0] as Path}").let { null }
                "toString" -> "fake watch $running"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.firstOrNull()
                else -> throw UnsupportedOperationException("CommonConnectedDevice.${method.name}")
            }
        } as CommonConnectedDevice
    }

    private companion object {
        const val SERIAL = "Q000000000001"

        fun version(tag: String, dualSlot: Boolean, recovery: Boolean = false) = FirmwareVersion.from(
            tag, isRecovery = recovery, gitHash = "", timestamp = Instant.DISTANT_PAST,
            isDualSlot = dualSlot, isSlot0 = true,
        )!!

        val DUAL_SLOT_4_38 = version("v4.38.2", dualSlot = true)
        val SINGLE_SLOT_4_38 = version("v4.38.2", dualSlot = false)
        val RECOVERY = version("v4.9.9", dualSlot = true, recovery = true)

        fun found(tag: String) = FirmwareUpdateCheckResult.FoundUpdate(
            version = version(tag, dualSlot = true), url = "", notes = "Sideloaded", canDowngrade = true,
        )

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
