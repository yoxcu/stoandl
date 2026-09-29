package de.yoxcu.stoandl.battery

import io.github.oshai.kotlinlogging.KotlinLogging

private val log = KotlinLogging.logger {}

/**
 * The `native_heartbeat_record` metric table — the single source of truth for its layout.
 *
 * PebbleOS generates the record by X-macro expansion of
 * `include/pbl/services/analytics/analytics.def`, so the wire layout is simply *the declaration
 * order of that file*. [METRICS] mirrors that order once; every byte offset is **derived** by
 * walking it, rather than being hardcoded per field. A firmware that adds or removes a metric is
 * then a one-line delta in [LAYOUTS] instead of an offset hunt.
 *
 * Derived from `analytics.def` at PebbleOS `31e3ea8e1` (the 92-metric / 527 B superset). See
 * `docs/heartbeat-metrics.md` for the full map and `docs/battery-insights.md` for how it is used.
 *
 * Self-checking: walking the table for a layout must reproduce that layout's declared record size
 * exactly. A mismatch means the table or the delta is wrong, so the layout is rejected (logged
 * once) rather than silently yielding shifted values.
 */
internal enum class MKind { U32, I32, TIMER, SCALED_U32, SCALED_I32, STR }

internal data class M(val name: String, val kind: MKind, val strLen: Int = 0) {
    /** Bytes this metric occupies on the wire. SCALED_* carry a u16 scale right after the value. */
    val size: Int
        get() = when (kind) {
            MKind.U32, MKind.I32, MKind.TIMER -> 4
            MKind.SCALED_U32, MKind.SCALED_I32 -> 6
            MKind.STR -> strLen + 1
        }
}

/** One decoded metric: [value] is already scale-divided; [text] is set for STR metrics. */
data class HeartbeatMetric(
    val name: String,
    val value: Double?,
    val text: String?,
    val raw: Long?,
)

/** One stored heartbeat record, fully decoded — the debug surface (`Debug → Heartbeat`). */
data class HeartbeatDump(
    /** The watch's own record timestamp (epoch seconds), 0 when the header was truncated. */
    val watchTs: Long,
    /** When stoandl received it (epoch seconds). */
    val rx: Long,
    val size: Int,
    val version: Int,
    /** GNU build-id of the firmware binary (hex) — identifies the exact build, NOT a git SHA. */
    val buildId: String,
    val fw: String,
    /** False when `(size, version)` names no known layout, in which case [metrics] is empty. */
    val known: Boolean,
    val metrics: List<HeartbeatMetric>,
)

internal object HeartbeatLayouts {
    private val U32 = MKind.U32
    private val I32 = MKind.I32
    private val TIMER = MKind.TIMER
    private val SCALED_U32 = MKind.SCALED_U32
    private val SCALED_I32 = MKind.SCALED_I32
    private val STR = MKind.STR

    /** `version:u8 @0 | timestamp:u64 @1 | build_id:u8[20] @9`. */
    const val HEADER_SIZE = 1 + 8 + 20

    /**
     * Declaration order from `analytics.def` — the 527 B / v1 superset (92 metrics).
     * Older/newer layouts are expressed as omissions in [LAYOUTS], never as a second table.
     */
    internal val METRICS: List<M> = listOf(
        M("memory_pct_max", U32),
        M("memory_largest_free_pct", U32),
        M("stack_free_kernel_main_bytes", U32),
        M("stack_free_kernel_background_bytes", U32),
        M("stack_free_newtimers_bytes", U32),
        M("stack_free_app_syscall_bytes", U32),
        M("stack_free_worker_syscall_bytes", U32),
        M("utc_offset_s", I32),
        M("fw_version", STR, strLen = 32),
        M("last_reboot_reason", U32),
        M("uptime_s", U32),
        M("battery_soc_pct", SCALED_U32),
        M("battery_soc_pct_drop", SCALED_U32),
        M("battery_voltage", SCALED_U32),
        M("battery_voltage_delta", SCALED_I32),
        M("battery_tte_s", U32),
        M("battery_charge_time_ms", TIMER),
        M("battery_discharge_duration_ms", TIMER),
        M("backlight_on_time_ms", TIMER),
        M("backlight_avg_intensity_pct", U32),
        M("vibrator_on_time_ms", TIMER),
        M("vibrator_avg_strength_pct", U32),
        M("speaker_on_time_ms", TIMER),
        M("speaker_play_count", U32),
        M("speaker_avg_volume_pct", U32),
        M("speaker_preempted_count", U32),
        M("speaker_stream_underrun_count", U32),
        M("hrm_on_time_ms", TIMER),
        M("button_pressed_count", U32),
        M("touch_event_count", U32),
        M("gesture_tap_count", U32),
        M("gesture_double_tap_count", U32),
        M("touch_driver_wake_cnt", U32),
        M("cpu_running_pct", SCALED_U32),
        M("cpu_sleep0_pct", SCALED_U32),
        M("cpu_sleep1_pct", SCALED_U32),
        M("cpu_sleep2_pct", SCALED_U32),
        M("sifli_ipc_not_idle_count", U32),
        M("task_cpu_kernel_main_pct", SCALED_U32),
        M("task_cpu_kernel_background_pct", SCALED_U32),
        M("task_cpu_worker_pct", SCALED_U32),
        M("task_cpu_app_pct", SCALED_U32),
        M("task_cpu_bt_host_pct", SCALED_U32),
        M("task_cpu_bt_controller_pct", SCALED_U32),
        M("task_cpu_bt_hci_pct", SCALED_U32),
        M("task_cpu_new_timers_pct", SCALED_U32),
        M("task_cpu_pulse_pct", SCALED_U32),
        M("task_cpu_idle_pct", SCALED_U32),
        M("accel_sample_count", U32),
        M("accel_shake_count", U32),
        M("accel_double_tap_count", U32),
        M("accel_peek_count", U32),
        M("notification_received_count", U32),
        M("notification_received_dnd_count", U32),
        M("phone_call_incoming_count", U32),
        M("phone_call_time_ms", TIMER),
        M("low_power_time_ms", TIMER),
        M("stationary_time_ms", TIMER),
        M("watchface_time_ms", TIMER),
        M("watchface_name", STR, strLen = 32),
        M("watchface_uuid", STR, strLen = 39),
        M("watchface_crash_count", U32),
        M("watchface_crash_revert_count", U32),
        M("pfs_space_free_kb", U32),
        M("flash_spi_write_bytes", U32),
        M("flash_spi_erase_bytes", U32),
        M("ble_adv_short_intvl_time_ms", TIMER),
        M("ble_adv_long_intvl_time_ms", TIMER),
        M("ble_conn_itvl_min_time_ms", TIMER),
        M("ble_conn_itvl_mid_time_ms", TIMER),
        M("ble_conn_itvl_max_time_ms", TIMER),
        M("ble_disconnect_conn_spvn_tmo_count", U32),
        M("ble_disconnect_rem_user_term_count", U32),
        M("ble_disconnect_conn_term_local_count", U32),
        M("ble_disconnect_lmp_ll_rsp_tmo_count", U32),
        M("ble_disconnect_conn_establishment_count", U32),
        M("ble_disconnect_other_count", U32),
        M("ppog_reversed", U32),
        M("settings_health_tracking_enabled", U32),
        M("settings_health_hrm_enabled", U32),
        M("settings_health_hrm_measurement_interval", U32),
        M("settings_health_hrm_activity_tracking_enabled", U32),
        M("settings_power_mode", U32),
        M("settings_motion_sensitivity", U32),
        M("settings_backlight_intensity_pct", U32),
        M("settings_backlight_timeout_s", U32),
        M("settings_touch_enabled", U32),
        M("app_message_sent_count", U32),
        M("app_message_received_count", U32),
        M("app_tick_timer_second_subscribed", U32),
        M("connectivity_connected_time_ms", TIMER),
        M("connectivity_expected_time_ms", TIMER),
    )

    /**
     * Known record layouts, keyed by `(size, version)`, each expressed as which metrics of
     * [METRICS] that firmware does **not** have:
     *
     *  - `523 B / v1` — before 2026-07-14; `ppog_reversed` did not exist yet.
     *  - `527 B / v1` — PebbleOS `31e3ea8e1` (2026-07-14) added `ppog_reversed` @467. Note the
     *    version byte did NOT change, so size is what distinguishes it from the row above.
     *  - `523 B / v2` — PebbleOS `5ef38b9e9` (2026-07-22) removed `settings_power_mode` and bumped
     *    the version byte; back to 523 B but with a different tail than `523 B / v1` — which is
     *    exactly why this map is keyed on the pair and not on size alone.
     */
    internal val LAYOUTS: Map<Pair<Int, Int>, Layout> = listOf(
        Layout(size = 523, version = 1, omit = setOf("ppog_reversed")),
        Layout(size = 527, version = 1, omit = emptySet()),
        Layout(size = 523, version = 2, omit = setOf("settings_power_mode")),
    ).associateBy { it.size to it.version }

    internal class Layout(val size: Int, val version: Int, val omit: Set<String>) {
        /** Metrics this firmware actually emits, in wire order. */
        val metrics: List<M> = METRICS.filter { it.name !in omit }

        /** name -> byte offset, derived by walking [metrics] from the end of the header. */
        val offsets: Map<String, Int> = buildMap {
            var o = HEADER_SIZE
            for (m in metrics) { put(m.name, o); o += m.size }
        }

        /** Total record size implied by the table; must equal [size] or the delta is wrong. */
        val derivedSize: Int = HEADER_SIZE + metrics.sumOf { it.size }

        val valid: Boolean = derivedSize == size

        /** Offset of [name] in this layout, or null when this firmware lacks the metric. */
        fun off(name: String): Int? = offsets[name]
    }

    private val warnedBadTable = java.util.concurrent.ConcurrentHashMap.newKeySet<Pair<Int, Int>>()

    /** The verified layout for a record, or null when we do not trust its bytes. */
    fun of(p: ByteArray): Layout? {
        if (p.isEmpty()) return null
        val key = p.size to (p[0].toInt() and 0xFF)
        val l = LAYOUTS[key] ?: return null
        if (!l.valid) {
            if (warnedBadTable.add(key)) {
                log.error {
                    "heartbeat metric table is inconsistent for ${l.size}B/v${l.version}: walking it " +
                        "yields ${l.derivedSize} B. Refusing to decode — fix METRICS/omit in HeartbeatLayout.kt."
                }
            }
            return null
        }
        return l
    }

    /** Decode every metric this layout defines, scale-divided, for the debug surface. */
    fun decodeAll(p: ByteArray): List<HeartbeatMetric> {
        val l = of(p) ?: return emptyList()
        return l.metrics.map { m ->
            val o = l.offsets.getValue(m.name)
            when (m.kind) {
                MKind.STR -> {
                    val end = (o until minOf(o + m.strLen + 1, p.size)).firstOrNull { p[it].toInt() == 0 }
                        ?: minOf(o + m.strLen + 1, p.size)
                    HeartbeatMetric(m.name, null, String(p, o, end - o, Charsets.UTF_8).trim(), null)
                }
                MKind.I32 -> {
                    val v = p.i32(o).toLong()
                    HeartbeatMetric(m.name, v.toDouble(), null, v)
                }
                MKind.U32, MKind.TIMER -> {
                    val v = p.u32(o)
                    HeartbeatMetric(m.name, v.toDouble(), null, v)
                }
                MKind.SCALED_U32, MKind.SCALED_I32 -> {
                    val raw = if (m.kind == MKind.SCALED_I32) p.i32(o).toLong() else p.u32(o)
                    val scale = p.u16(o + 4)
                    HeartbeatMetric(m.name, if (scale > 0) raw.toDouble() / scale else raw.toDouble(), null, raw)
                }
            }
        }
    }

    private fun ByteArray.u16(o: Int) = (this[o].toInt() and 0xFF) or ((this[o + 1].toInt() and 0xFF) shl 8)
    private fun ByteArray.u32(o: Int): Long {
        var v = 0L
        for (i in 3 downTo 0) v = (v shl 8) or (this[o + i].toLong() and 0xFF)
        return v
    }
    private fun ByteArray.i32(o: Int): Int = u32(o).toInt()
}
