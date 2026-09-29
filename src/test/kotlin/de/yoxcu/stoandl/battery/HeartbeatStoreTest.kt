package de.yoxcu.stoandl.battery

import de.yoxcu.stoandl.battery.HeartbeatLayoutTest.Companion.putU16
import de.yoxcu.stoandl.battery.HeartbeatLayoutTest.Companion.putU32
import de.yoxcu.stoandl.battery.HeartbeatLayoutTest.Companion.synthetic567
import java.io.File
import java.nio.file.Files
import kotlin.math.abs
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The heartbeat store end to end: a record goes in through [HeartbeatStore.record] exactly as the
 * watch sends it, and comes back out through the read surface the D-Bus methods use. Fw ≥ 4.33
 * sends 567 B / v3, which no layout row covered, so the activity views read nothing from it.
 */
class HeartbeatStoreTest {
    private val dir: File = Files.createTempDirectory("stoandl-hb").toFile()
    private val store = HeartbeatStore(retentionDays = 36500, baseDir = dir).also { it.start() }

    @AfterTest
    fun tearDown() {
        dir.deleteRecursively()
    }

    /** A plausible hour on fw 4.38: 73.42 %, 3.912 V, 1.5 % measured drop, discharging all hour. */
    private val hour = mapOf(
        "battery_soc_pct" to 7342L,
        "battery_soc_pct_drop" to 150L,
        "battery_voltage" to 3912L,
        "battery_voltage_delta" to 12L,
        "battery_tte_s" to 36_000L,
        "battery_charge_time_ms" to 0L,
        "battery_discharge_duration_ms" to 3_600_000L,
        "backlight_on_time_ms" to 60_000L,
        "backlight_avg_intensity_pct" to 50L,
        "hrm_on_time_ms" to 900_000L,
        "notification_received_count" to 7L,
        "notification_received_dnd_count" to 2L,
        "connectivity_connected_time_ms" to 3_500_000L,
    )

    @Test
    fun v3RecordFeedsEveryView() {
        store.record(synthetic567(overrides = hour), "SER1", "v4.38.2")

        val point = store.history("SER1", 0).single()
        assertEquals(73.42, point.level)
        assertEquals(3.912, point.voltage)

        val a = store.activity("SER1", 0).single()
        assertEquals(1_790_000_000L, a.ts)
        assertEquals(1.5, a.socDropPct)
        assertEquals(3_600_000L, a.intervalMs)
        assertEquals(60_000L, a.backlightMs)
        assertEquals(50, a.backlightIntensityPct)
        assertEquals(900_000L, a.hrmMs)
        assertEquals(7L, a.notifCount)
        assertEquals(2L, a.notifDndCount)
        assertEquals(3_500_000L, a.btConnectedMs, "connectivity_connected_time_ms @515 on v3, not @519")

        val slices = store.power("SER1", 0)
        assertTrue(slices.isNotEmpty())
        assertTrue(abs(slices.sumOf { it.estDrainPct } - 1.5) < 1e-9, "slices sum to the measured drop")

        val dump = assertNotNull(store.latestDump("SER1"))
        assertTrue(dump.known)
        assertEquals(567 to 3, dump.size to dump.version)
        assertEquals(101, dump.metrics.size)
        assertEquals((0 until 20).joinToString("") { "%02x".format(0xA0 + it) }, dump.buildId)
    }

    @Test
    fun oldLayoutReadsTheBatteryBlockAtItsOwnOffsets() {
        // fw 4.9.184 (507 B / v1) has no stack_free_*_syscall_bytes, so its battery block starts
        // at 94, not 102. Offsets from `hb_layouts_from_source.py --offsets v4.9.184`.
        val p = ByteArray(507).also { it[0] = 1 }
        p.putU32(94, 5000); p.putU16(98, 100) // battery_soc_pct
        p.putU32(100, 100); p.putU16(104, 100) // battery_soc_pct_drop
        p.putU32(106, 3800); p.putU16(110, 1000) // battery_voltage
        p.putU16(116, 1000) // battery_voltage_delta scale
        p.putU32(126, 3_600_000) // battery_discharge_duration_ms
        p.putU16(190, 100) // cpu_running_pct scale @186
        p.putU32(290, 4) // notification_received_count
        store.record(p, "OLD", "v4.9.184")

        assertEquals(50.0, store.history("OLD", 0).single().level)
        val a = store.activity("OLD", 0).single()
        assertEquals(1.0, a.socDropPct)
        assertEquals(4L, a.notifCount)
        assertEquals(0L, a.speakerMs, "fw 4.9.184 emits speaker metrics, all zero here")
        assertEquals(87, assertNotNull(store.latestDump("OLD")).metrics.size)
    }

    @Test
    fun unknownLayoutKeepsBatteryButDecodesNoMetrics() {
        // A future layout that appended metrics: the battery block still sits where the newest
        // known layout has it and proves itself, but nothing past it is trusted.
        val p = synthetic567(overrides = hour).copyOf(571).also { it[0] = 4 }
        store.record(p, "NEW", "v4.40.0")

        assertEquals(73.42, store.history("NEW", 0).single().level)
        assertTrue(store.activity("NEW", 0).isEmpty())
        assertTrue(store.power("NEW", 0).isEmpty())
        val dump = assertNotNull(store.latestDump("NEW"))
        assertFalse(dump.known)
        assertTrue(dump.metrics.isEmpty())
    }

    @Test
    fun unknownLayoutWithoutAValidBatteryBlockIsNotDecoded() {
        // Charge + discharge is not about an hour, so the structural fallback refuses.
        val p = synthetic567(overrides = hour + ("battery_discharge_duration_ms" to 60_000L)).copyOf(571)
            .also { it[0] = 4 }
        store.record(p, "BAD", "v4.40.0")

        assertFalse(store.hasData("BAD"))
        assertTrue(store.history("BAD", 0).isEmpty())
        assertFalse(assertNotNull(store.latestDump("BAD")).known)
    }
}
