package de.yoxcu.stoandl.firmware

import de.yoxcu.stoandl.config.StoandlConfig
import io.rebble.libpebblecommon.connection.CommonConnectedDevice
import io.rebble.libpebblecommon.connection.ConnectedPebbleDeviceInRecovery
import io.rebble.libpebblecommon.connection.FirmwareUpdateCheckResult
import io.rebble.libpebblecommon.connection.LibPebble
import io.rebble.libpebblecommon.connection.PebbleDevice
import io.rebble.libpebblecommon.connection.endpointmanager.FirmwareUpdateTrigger
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
import kotlinx.coroutines.flow.first
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
 * into recovery (PRF) without transferring anything (`WaitingForReboot.viaPrf`) and installs the same
 * `.pbz` itself once the watch reconnects there (upstream 5f24440b). [FirmwareControl] only reports
 * that and keeps out of its way. The watch side needs hardware (TESTING 5.11e), but the daemon's part
 * is decision logic, and a mistake in it either reports a flash that never happened, offers the latest
 * release mid-downgrade, or flashes a second time next to libpebble3's own recovery install. So it is
 * driven here through fake watches.
 */
class FirmwareControlDowngradeTest {
    private val watches = MutableStateFlow<List<PebbleDevice>>(emptyList())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val config = StoandlConfig.load(File("/nonexistent/stoandl.conf"), logResult = false)
    private val control = FirmwareControl(AtomicReference(fakeLibPebble()), scope, { config })
    private val pbz = File.createTempFile("normal_obelix", ".pbz")

    /** Every sideloadFirmware() call, as "normal <path> <trigger>" or "recovery <path> <trigger>". */
    private val flashed = CopyOnWriteArrayList<String>()

    @AfterTest
    fun tearDown() {
        scope.cancel()
        pbz.delete()
    }

    @Test
    fun downgradeIsReportedAsPrfAndInstalledByLibpebble3Alone() = runBlocking {
        watches.value = listOf(watch(DUAL_SLOT_4_38))
        assertEquals("ok:Flashing ${pbz.name}", control.sideload(pbz.path))
        assertEquals(listOf("normal ${pbz.path} Sideload"), flashed)

        // libpebble3 decided on the PRF route: rebooting, nothing flashed yet. Not "reboot:" (success).
        watches.value = listOf(watch(DUAL_SLOT_4_38, rebooting(viaPrf = true)))
        assertEquals("prf:v4.37.0", control.status())
        withTimeout(5.seconds) { control.statusFlow().first { it == "prf:v4.37.0" } }
        // The latest release must not be offered meanwhile: one tap would undo the downgrade. Nor
        // may a second sideload start: libpebble3 would drop it silently.
        assertTrue(control.update().startsWith("busy:"))
        assertTrue(control.sideload(pbz.path).startsWith("busy:"))

        // Back in recovery, libpebble3 starts the pending install itself.
        watches.value = emptyList()
        watches.value = listOf(watch(RECOVERY, FirmwareUpdateStatus.WaitingToStart(found("v4.37.0"), SIDELOAD), recovery = true))
        assertEquals("waiting:", control.status())
        assertTrue(control.update().startsWith("busy:"), "in recovery the latest release must not replace the downgrade")
        watches.value = listOf(watch(RECOVERY, FirmwareUpdateStatus.InProgress(found("v4.37.0"), MutableStateFlow(0.5f), SIDELOAD), recovery = true))
        withTimeout(5.seconds) { control.statusFlow().first { it == "inprogress:50" } }
        assertTrue(control.update().startsWith("busy:"))

        // One owner of the recovery step: stoandl never flashes from recovery on its own.
        delay(200)
        assertEquals(listOf("normal ${pbz.path} Sideload"), flashed)
    }

    @Test
    fun aDirectInstallIsAnOrdinaryReboot() = runBlocking {
        watches.value = listOf(watch(SINGLE_SLOT_4_38))
        control.sideload(pbz.path)
        // Single-slot watches take the older image directly; WaitingForReboot is the usual success,
        // whatever the version: the route is libpebble3's call, not a rule of ours.
        watches.value = listOf(watch(SINGLE_SLOT_4_38, rebooting(viaPrf = false)))
        assertEquals("reboot:", control.status())

        watches.value = emptyList()
        watches.value = listOf(watch(RECOVERY, recovery = true))
        delay(200)
        assertEquals(listOf("normal ${pbz.path} Sideload"), flashed)
        assertTrue(control.update().startsWith("disabled:"), "nothing pending: update goes to the (off) source")
    }

    @Test
    fun backOnNormalFirmwareIsATerminalFailure() = runBlocking {
        watches.value = listOf(watch(DUAL_SLOT_4_38))
        control.sideload(pbz.path)
        watches.value = listOf(watch(DUAL_SLOT_4_38, rebooting(viaPrf = true)))
        assertTrue(control.update().startsWith("busy:"))

        // The watch left recovery without the downgrade (or never got there): libpebble3 reports it
        // as the reconnect's failure. Said as `failed:`, not a plain `idle:` a client following `prf`
        // would wait out, and on the FirmwareProgress flow as well.
        watches.value = emptyList()
        watches.value = listOf(watch(DUAL_SLOT_4_38, FirmwareUpdateStatus.NotInProgress.Idle(
            IllegalStateException(DID_NOT_REBOOT), SIDELOAD)))
        assertEquals("failed:$DID_NOT_REBOOT", control.status())
        withTimeout(5.seconds) { control.statusFlow().first { it.startsWith("failed:") } }
        assertTrue(control.update().startsWith("disabled:"), "nothing is in flight any more")

        // A later, unrelated recovery visit flashes nothing.
        watches.value = emptyList()
        watches.value = listOf(watch(RECOVERY, recovery = true))
        delay(200)
        assertEquals(listOf("normal ${pbz.path} Sideload"), flashed)

        // The next flash is accepted again.
        watches.value = listOf(watch(DUAL_SLOT_4_38))
        assertEquals("ok:Flashing ${pbz.name}", control.sideload(pbz.path))
        assertEquals("idle:", control.status())
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
                "sideloadFirmware" -> flashed.add("${if (recovery) "recovery" else "normal"} ${args!![0] as Path} ${args[1] as FirmwareUpdateTrigger}").let { null }
                "toString" -> "fake watch $running"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.firstOrNull()
                else -> throw UnsupportedOperationException("CommonConnectedDevice.${method.name}")
            }
        } as CommonConnectedDevice
    }

    private companion object {
        const val SERIAL = "Q000000000001"
        val SIDELOAD = FirmwareUpdateTrigger.Sideload

        /** libpebble3's own failure for a watch that came back on its normal firmware (continuePending). */
        const val DID_NOT_REBOOT = "The watch didn't reboot into recovery to install the update"

        fun rebooting(viaPrf: Boolean) = FirmwareUpdateStatus.WaitingForReboot(found("v4.37.0"), SIDELOAD, viaPrf)

        fun version(tag: String, dualSlot: Boolean, recovery: Boolean = false) = FirmwareVersion.from(
            tag, isRecovery = recovery, gitHash = "", timestamp = Instant.DISTANT_PAST,
            isDualSlot = dualSlot, isSlot0 = true,
        )!!

        val DUAL_SLOT_4_38 = version("v4.38.2", dualSlot = true)
        val SINGLE_SLOT_4_38 = version("v4.38.2", dualSlot = false)
        val RECOVERY = version("v4.9.9", dualSlot = true, recovery = true)

        fun found(tag: String) = FirmwareUpdateCheckResult.FoundUpdate(
            version = version(tag, dualSlot = true), url = "", notes = "Sideloaded", installsDirectly = false,
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
