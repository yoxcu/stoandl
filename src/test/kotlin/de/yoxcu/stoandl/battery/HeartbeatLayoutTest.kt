package de.yoxcu.stoandl.battery

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [HeartbeatLayouts] turns one metric table into every released record layout. A wrong `since` or
 * a missing row does not fail loudly on the watch — it shifts every offset behind it, and the power
 * pie quietly shows another metric's bytes. So the table is checked here against numbers derived
 * independently from PebbleOS `analytics.def` (`tools/hb_layouts_from_source.py`), not against
 * itself.
 */
class HeartbeatLayoutTest {

    @Test
    fun everyLayoutWalksToItsDeclaredSize() {
        for (l in HeartbeatLayouts.LAYOUTS) {
            assertEquals(l.size, l.derivedSize, "${l.size} B / v${l.version} (fw ${l.fw}) walks to ${l.derivedSize} B")
            assertTrue(l.valid)
        }
    }

    @Test
    fun layoutsAreUniqueAndOldestFirst() {
        val keys = HeartbeatLayouts.LAYOUTS.map { it.size to it.version }
        assertEquals(keys.size, keys.toSet().size, "two rows share a (size, version): $keys")
        HeartbeatLayouts.LAYOUTS.zipWithNext { a, b ->
            assertTrue(compareReleases(a.fw, b.fw) < 0, "${a.fw} is listed before ${b.fw}")
        }
        assertEquals(567 to 3, HeartbeatLayouts.NEWEST.size to HeartbeatLayouts.NEWEST.version)
    }

    @Test
    fun everyMetricBoundaryIsALayoutRow() {
        // A metric that appears or disappears in a release with no row of its own would be read at
        // offsets no firmware ever sent.
        val rows = HeartbeatLayouts.LAYOUTS.map { it.fw }.toSet()
        for (m in HeartbeatLayouts.METRICS) {
            assertTrue(m.since in rows, "${m.name}: since ${m.since} has no layout row")
            m.until?.let { assertTrue(it in rows, "${m.name}: until $it has no layout row") }
        }
        assertEquals(HeartbeatLayouts.METRICS.size, HeartbeatLayouts.METRICS.map { it.name }.toSet().size)
    }

    @Test
    fun releaseNumbersCompareNumerically() {
        assertTrue(compareReleases("4.9.184", "4.13.0") < 0)
        assertTrue(compareReleases("4.32.0", "4.33.0") < 0)
        assertEquals(0, compareReleases("4.33.0", "4.33.0"))
    }

    /** Landmark offsets per layout, from `hb_layouts_from_source.py --offsets <first release>`. */
    @Test
    fun landmarkOffsetsMatchAnalyticsDefAtEachRelease() {
        val names = listOf(
            "battery_soc_pct", "hrm_on_time_ms", "cpu_running_pct", "task_cpu_app_pct",
            "notification_received_count", "watchface_uuid", "settings_health_hrm_enabled",
            "connectivity_connected_time_ms",
        )
        val golden = mapOf(
            (507 to 1) to listOf(94, 166, 186, 232, 290, 351, 459, 499),
            (515 to 1) to listOf(102, 174, 194, 240, 298, 359, 467, 507),
            (523 to 1) to listOf(102, 174, 198, 244, 302, 363, 471, 515),
            (527 to 1) to listOf(102, 174, 198, 244, 302, 363, 475, 519),
            (523 to 2) to listOf(102, 174, 198, 244, 302, 363, 475, 515),
            (567 to 3) to listOf(102, 174, 198, 244, 302, 363, 475, 515),
        )
        assertEquals(golden.keys, HeartbeatLayouts.LAYOUTS.map { it.size to it.version }.toSet())
        for (l in HeartbeatLayouts.LAYOUTS) {
            val want = golden.getValue(l.size to l.version)
            assertEquals(want, names.map { l.off(it) }, "${l.size} B / v${l.version}")
        }
    }

    @Test
    fun v3RecordDecodesEveryMetricAtItsSourceOffset() {
        val p = synthetic567()
        val metrics = HeartbeatLayouts.decodeAll(p)
        assertEquals(V3.map { it.name }, metrics.map { it.name }, "names, in wire order")
        for ((i, f) in V3.withIndex()) {
            val m = metrics[i]
            when (f.kind) {
                "STR" -> assertEquals("s$i", m.text, f.name)
                "I32" -> assertEquals(-(1000L + i), m.raw, f.name)
                "SCALED_I32" -> {
                    assertEquals(-(1000L + i), m.raw, f.name)
                    assertEquals(-(1000.0 + i) / scaleOf(f.name), m.value, f.name)
                }
                "SCALED_U32" -> {
                    assertEquals(1000L + i, m.raw, f.name)
                    assertEquals((1000.0 + i) / scaleOf(f.name), m.value, f.name)
                }
                else -> assertEquals(1000L + i, m.raw, f.name)
            }
        }
        // The ten metrics v3 appended, and the one it dropped.
        assertEquals(101, metrics.size)
        assertNull(metrics.firstOrNull { it.name == "settings_power_mode" })
        val byName = metrics.associateBy { it.name }
        assertEquals(1000L + V3.indexOfFirst { it.name == "touch_gated_touchdown_count" },
            byName.getValue("touch_gated_touchdown_count").raw)
    }

    @Test
    fun unknownLayoutsAreRejectedNotMisdecoded() {
        val cases = listOf(
            567 to 2, 567 to 4, // right size, wrong version
            // fw 4.9.158 … 4.9.184: the struct's packed attribute was ignored, so these records are
            // naturally aligned (PebbleOS fac6968e) and match no packed layout.
            336 to 1, 472 to 1, 480 to 1, 504 to 1, 512 to 1, 520 to 1, 544 to 1, 552 to 1, 560 to 1,
            531 to 3, 563 to 3, // v3 development builds between two releases
            523 to 3, 568 to 3, 0 to 0,
        )
        for ((size, version) in cases) {
            val p = ByteArray(size).also { if (size > 0) it[0] = version.toByte() }
            assertNull(HeartbeatLayouts.of(p), "$size B / v$version")
            assertTrue(HeartbeatLayouts.decodeAll(p).isEmpty(), "$size B / v$version")
        }
        // Known size, but a version byte that never went with it.
        val v1Sized = synthetic567().copyOf(527).also { it[0] = 3 }
        assertNull(HeartbeatLayouts.of(v1Sized))
    }

    @Test
    fun readersReportAbsentMetricsAndRejectTypos() {
        val oldest = HeartbeatLayouts.LAYOUTS.first()
        assertNull(oldest.off("stack_free_app_syscall_bytes"), "no syscall stack metrics before fw 4.13.0")
        assertNotNull(HeartbeatLayouts.NEWEST.off("stack_free_app_syscall_bytes"))
        assertFailsWith<IllegalArgumentException> { oldest.off("stack_free_app_syscal_bytes") }
        // A record too short for the field reads as absent, not as an out-of-bounds read.
        assertNull(HeartbeatLayouts.NEWEST.u32(ByteArray(100), "battery_soc_pct"))
    }

    internal data class F(val off: Int, val name: String, val kind: String)

    internal companion object {
        /** `tools/hb_layouts_from_source.py <PebbleOS> --offsets v4.33.0` (unchanged through v4.38.2). */
        val V3: List<F> = """
             29  memory_pct_max  U32
             33  memory_largest_free_pct  U32
             37  stack_free_kernel_main_bytes  U32
             41  stack_free_kernel_background_bytes  U32
             45  stack_free_newtimers_bytes  U32
             49  stack_free_app_syscall_bytes  U32
             53  stack_free_worker_syscall_bytes  U32
             57  utc_offset_s  I32
             61  fw_version  STR
             94  last_reboot_reason  U32
             98  uptime_s  U32
            102  battery_soc_pct  SCALED_U32
            108  battery_soc_pct_drop  SCALED_U32
            114  battery_voltage  SCALED_U32
            120  battery_voltage_delta  SCALED_I32
            126  battery_tte_s  U32
            130  battery_charge_time_ms  TIMER
            134  battery_discharge_duration_ms  TIMER
            138  backlight_on_time_ms  TIMER
            142  backlight_avg_intensity_pct  U32
            146  vibrator_on_time_ms  TIMER
            150  vibrator_avg_strength_pct  U32
            154  speaker_on_time_ms  TIMER
            158  speaker_play_count  U32
            162  speaker_avg_volume_pct  U32
            166  speaker_preempted_count  U32
            170  speaker_stream_underrun_count  U32
            174  hrm_on_time_ms  TIMER
            178  button_pressed_count  U32
            182  touch_event_count  U32
            186  gesture_tap_count  U32
            190  gesture_double_tap_count  U32
            194  touch_driver_wake_cnt  U32
            198  cpu_running_pct  SCALED_U32
            204  cpu_sleep0_pct  SCALED_U32
            210  cpu_sleep1_pct  SCALED_U32
            216  cpu_sleep2_pct  SCALED_U32
            222  sifli_ipc_not_idle_count  U32
            226  task_cpu_kernel_main_pct  SCALED_U32
            232  task_cpu_kernel_background_pct  SCALED_U32
            238  task_cpu_worker_pct  SCALED_U32
            244  task_cpu_app_pct  SCALED_U32
            250  task_cpu_bt_host_pct  SCALED_U32
            256  task_cpu_bt_controller_pct  SCALED_U32
            262  task_cpu_bt_hci_pct  SCALED_U32
            268  task_cpu_new_timers_pct  SCALED_U32
            274  task_cpu_pulse_pct  SCALED_U32
            280  task_cpu_idle_pct  SCALED_U32
            286  accel_sample_count  U32
            290  accel_shake_count  U32
            294  accel_double_tap_count  U32
            298  accel_peek_count  U32
            302  notification_received_count  U32
            306  notification_received_dnd_count  U32
            310  phone_call_incoming_count  U32
            314  phone_call_time_ms  TIMER
            318  low_power_time_ms  TIMER
            322  stationary_time_ms  TIMER
            326  watchface_time_ms  TIMER
            330  watchface_name  STR
            363  watchface_uuid  STR
            403  watchface_crash_count  U32
            407  watchface_crash_revert_count  U32
            411  pfs_space_free_kb  U32
            415  flash_spi_write_bytes  U32
            419  flash_spi_erase_bytes  U32
            423  ble_adv_short_intvl_time_ms  TIMER
            427  ble_adv_long_intvl_time_ms  TIMER
            431  ble_conn_itvl_min_time_ms  TIMER
            435  ble_conn_itvl_mid_time_ms  TIMER
            439  ble_conn_itvl_max_time_ms  TIMER
            443  ble_disconnect_conn_spvn_tmo_count  U32
            447  ble_disconnect_rem_user_term_count  U32
            451  ble_disconnect_conn_term_local_count  U32
            455  ble_disconnect_lmp_ll_rsp_tmo_count  U32
            459  ble_disconnect_conn_establishment_count  U32
            463  ble_disconnect_other_count  U32
            467  ppog_reversed  U32
            471  settings_health_tracking_enabled  U32
            475  settings_health_hrm_enabled  U32
            479  settings_health_hrm_measurement_interval  U32
            483  settings_health_hrm_activity_tracking_enabled  U32
            487  settings_motion_sensitivity  U32
            491  settings_backlight_intensity_pct  U32
            495  settings_backlight_timeout_s  U32
            499  settings_touch_enabled  U32
            503  app_message_sent_count  U32
            507  app_message_received_count  U32
            511  app_tick_timer_second_subscribed  U32
            515  connectivity_connected_time_ms  TIMER
            519  connectivity_expected_time_ms  TIMER
            523  ble_conn_slave_lat0_time_ms  TIMER
            527  ble_conn_param_update_count  U32
            531  accel_stream_recovery_count  U32
            535  unexpected_reboot_count  U32
            539  battery_temp_c  SCALED_I32
            545  i2c_transfer_error_count  U32
            549  ble_conn_itvl_other_time_ms  TIMER
            553  drv_init_fail_flags  U32
            557  battery_soc_pct_min  SCALED_U32
            563  touch_gated_touchdown_count  U32
        """.trimIndent().lines().map { it.trim().split(Regex("\\s+")).let { (o, n, k) -> F(o.toInt(), n, k) } }

        /** The compile-time scale `analytics.def` gives each SCALED metric. */
        fun scaleOf(name: String): Int =
            if (name in setOf("battery_voltage", "battery_voltage_delta", "battery_temp_c")) 1000 else 100

        /**
         * A 567 B / v3 record written at the [V3] offsets, independently of [HeartbeatLayouts]: metric
         * `i` holds `1000 + i` (negated for signed kinds), strings hold `"s<i>"`, and [overrides] then
         * replace values by name (the u32 at the metric's offset).
         */
        fun synthetic567(watchTs: Long = 1_790_000_000L, overrides: Map<String, Long> = emptyMap()): ByteArray {
            val p = ByteArray(567)
            p[0] = 3
            for (i in 0..7) p[1 + i] = (watchTs ushr (8 * i)).toByte()
            for (i in 0 until 20) p[9 + i] = (0xA0 + i).toByte()
            for ((i, f) in V3.withIndex()) {
                when (f.kind) {
                    "STR" -> "s$i".toByteArray().copyInto(p, f.off)
                    "I32" -> p.putU32(f.off, -(1000L + i))
                    "SCALED_I32" -> { p.putU32(f.off, -(1000L + i)); p.putU16(f.off + 4, scaleOf(f.name)) }
                    "SCALED_U32" -> { p.putU32(f.off, 1000L + i); p.putU16(f.off + 4, scaleOf(f.name)) }
                    else -> p.putU32(f.off, 1000L + i)
                }
            }
            for ((name, v) in overrides) p.putU32(V3.first { it.name == name }.off, v)
            return p
        }

        fun ByteArray.putU32(o: Int, v: Long) { for (i in 0..3) this[o + i] = (v ushr (8 * i)).toByte() }
        fun ByteArray.putU16(o: Int, v: Int) { for (i in 0..1) this[o + i] = (v ushr (8 * i)).toByte() }
    }
}
