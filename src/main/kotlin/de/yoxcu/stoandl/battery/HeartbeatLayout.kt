package de.yoxcu.stoandl.battery

import io.github.oshai.kotlinlogging.KotlinLogging

private val log = KotlinLogging.logger {}

/**
 * The `native_heartbeat_record` metric table — the single source of truth for its layout.
 *
 * PebbleOS generates the record by X-macro expansion of
 * `include/pbl/services/analytics/analytics.def`, so the wire layout is simply *the declaration
 * order of that file*. [HeartbeatLayouts.METRICS] mirrors that order once, each metric tagged with
 * the first release that emits it; every released layout is then one `(size, version, release)` row
 * in [HeartbeatLayouts.LAYOUTS], and every byte offset is **derived** by walking the metrics that
 * release has, never hardcoded per field. A firmware that adds or removes a metric is one table line
 * plus one layout row, not an offset hunt.
 *
 * Derived from `analytics.def` at every PebbleOS release tag through v4.38.2 by
 * `tools/hb_layouts_from_source.py`, which prints these lines. See `docs/heartbeat-metrics.md` for
 * the full map and `docs/battery-insights.md` for how it is used.
 *
 * Only packed records (v4.10.0 on) are here. v4.9.158 … v4.9.184 declared the struct
 * `__attribute__((packed)) struct …`, which GCC ignores, so their records are naturally aligned
 * (timestamp @8, padding after every scale) and fit none of these walks; they are kept raw, not decoded.
 *
 * Self-checking: walking the table for a layout must reproduce that layout's declared record size
 * exactly. A mismatch means the table or the row is wrong, so the layout is rejected (logged once)
 * rather than silently yielding shifted values.
 */
internal enum class MKind { U32, I32, TIMER, SCALED_U32, SCALED_I32, STR }

/**
 * One `analytics.def` metric. [since] is the first PebbleOS release that emits it and [until] the
 * first that no longer does (null while it is still emitted).
 */
internal data class M(
    val name: String,
    val kind: MKind,
    val strLen: Int = 0,
    val since: String = HeartbeatLayouts.FIRST_RELEASE,
    val until: String? = null,
) {
    /** Bytes this metric occupies on the wire. SCALED_* carry a u16 scale right after the value. */
    val size: Int
        get() = when (kind) {
            MKind.U32, MKind.I32, MKind.TIMER -> 4
            MKind.SCALED_U32, MKind.SCALED_I32 -> 6
            MKind.STR -> strLen + 1
        }

    /** Whether release [fw] emits this metric. */
    fun inRelease(fw: String): Boolean =
        compareReleases(since, fw) <= 0 && (until == null || compareReleases(fw, until) < 0)
}

/** Compare two PebbleOS release numbers numerically, so "4.10.0" sorts before "4.13.0". */
internal fun compareReleases(a: String, b: String): Int {
    val x = a.split('.').map(String::toInt)
    val y = b.split('.').map(String::toInt)
    for (i in 0 until maxOf(x.size, y.size)) {
        val c = x.getOrElse(i) { 0 }.compareTo(y.getOrElse(i) { 0 })
        if (c != 0) return c
    }
    return 0
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

    /** The first release whose heartbeat record is packed (PebbleOS fac6968e); [M.since] defaults to
     *  it. Earlier ones (from 4.9.158) are naturally aligned, see the file's KDoc. */
    const val FIRST_RELEASE = "4.10.0"

    /** `version:u8 @0 | timestamp:u64 @1 | build_id:u8[20] @9` (packed records only). */
    const val BUILD_ID_OFF = 9
    const val BUILD_ID_LEN = 20
    const val HEADER_SIZE = 1 + 8 + BUILD_ID_LEN

    /**
     * Declaration order from `analytics.def`: every metric any release has emitted (102), each
     * tagged with the release range that emits it. A layout is the subset one release emits, in
     * this order — never a second table.
     */
    internal val METRICS: List<M> = listOf(
        M("memory_pct_max", U32),
        M("memory_largest_free_pct", U32),
        M("stack_free_kernel_main_bytes", U32),
        M("stack_free_kernel_background_bytes", U32),
        M("stack_free_newtimers_bytes", U32),
        M("stack_free_app_syscall_bytes", U32, since = "4.13.0"),
        M("stack_free_worker_syscall_bytes", U32, since = "4.13.0"),
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
        M("touch_driver_wake_cnt", U32, since = "4.20.0"),
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
        M("ppog_reversed", U32, since = "4.26.0"),
        M("settings_health_tracking_enabled", U32),
        M("settings_health_hrm_enabled", U32),
        M("settings_health_hrm_measurement_interval", U32),
        M("settings_health_hrm_activity_tracking_enabled", U32),
        M("settings_power_mode", U32, until = "4.32.0"),
        M("settings_motion_sensitivity", U32),
        M("settings_backlight_intensity_pct", U32),
        M("settings_backlight_timeout_s", U32),
        M("settings_touch_enabled", U32, since = "4.20.0"),
        M("app_message_sent_count", U32),
        M("app_message_received_count", U32),
        M("app_tick_timer_second_subscribed", U32),
        M("connectivity_connected_time_ms", TIMER),
        M("connectivity_expected_time_ms", TIMER),
        M("ble_conn_slave_lat0_time_ms", TIMER, since = "4.33.0"),
        M("ble_conn_param_update_count", U32, since = "4.33.0"),
        M("accel_stream_recovery_count", U32, since = "4.33.0"),
        M("unexpected_reboot_count", U32, since = "4.33.0"),
        M("battery_temp_c", SCALED_I32, since = "4.33.0"),
        M("i2c_transfer_error_count", U32, since = "4.33.0"),
        M("ble_conn_itvl_other_time_ms", TIMER, since = "4.33.0"),
        M("drv_init_fail_flags", U32, since = "4.33.0"),
        M("battery_soc_pct_min", SCALED_U32, since = "4.33.0"),
        M("touch_gated_touchdown_count", U32, since = "4.33.0"),
    )

    /**
     * Every released record layout, oldest first, as `(size, version)` plus the first release that
     * emits it (later releases up to the next row, backports included, emit the same bytes). Things
     * worth knowing:
     *
     *  - The version byte stayed 1 through four layouts, so for v1 only the size tells them
     *    apart. No two releases ever filled the same `(size, version)` differently, which is what
     *    makes that key safe (`tools/hb_layouts_from_source.py` checks it).
     *  - `527 B / v1` (4.26.0) added `ppog_reversed` @467 without touching the version byte.
     *  - `523 B / v2` (4.32.0) removed `settings_power_mode` and bumped the version: back to 523 B
     *    with a different tail than `523 B / v1`, which is why the key is the pair and not the size.
     *  - `567 B / v3` (4.33.0, unchanged through 4.38.2) appended ten metrics.
     *
     * Untagged development builds between releases can emit layouts that are not here; they are
     * rejected, not decoded.
     */
    internal val LAYOUTS: List<Layout> = listOf(
        Layout(size = 507, version = 1, fw = "4.10.0"),
        Layout(size = 515, version = 1, fw = "4.13.0"),
        Layout(size = 523, version = 1, fw = "4.20.0"),
        Layout(size = 527, version = 1, fw = "4.26.0"),
        Layout(size = 523, version = 2, fw = "4.32.0"),
        Layout(size = 567, version = 3, fw = "4.33.0"),
    )

    private val byKey: Map<Pair<Int, Int>, Layout> = LAYOUTS.associateBy { it.size to it.version }

    /** The newest layout; an unknown record's battery block is looked for at its offsets. */
    val NEWEST: Layout = LAYOUTS.last()

    private val metricNames: Set<String> = METRICS.mapTo(HashSet()) { it.name }

    internal class Layout(val size: Int, val version: Int, val fw: String) {
        /** Metrics this release actually emits, in wire order. */
        val metrics: List<M> = METRICS.filter { it.inRelease(fw) }

        /** name -> byte offset, derived by walking [metrics] from the end of the header. */
        val offsets: Map<String, Int> = buildMap {
            var o = HEADER_SIZE
            for (m in metrics) { put(m.name, o); o += m.size }
        }

        /** Total record size implied by the table; must equal [size] or the table is wrong. */
        val derivedSize: Int = HEADER_SIZE + metrics.sumOf { it.size }

        val valid: Boolean = derivedSize == size

        /**
         * Offset of [name] in this layout, or null when this release lacks the metric. A name that
         * is in no layout at all is a typo in the caller, so it throws instead of reading as absent.
         */
        fun off(name: String): Int? {
            require(name in metricNames) { "no heartbeat metric named '$name'" }
            return offsets[name]
        }

        /** The value of [name] in [p], or null when this release lacks it or [p] is too short. */
        fun u32(p: ByteArray, name: String): Long? = at(p, name, 4)?.let { p.u32le(it) }
        fun i32(p: ByteArray, name: String): Int? = at(p, name, 4)?.let { p.i32le(it) }

        /** The inline u16 scale of a SCALED_* metric. */
        fun scale(p: ByteArray, name: String): Int? = at(p, name, 6)?.let { p.u16le(it + 4) }

        /** A SCALED_* metric divided by its inline scale; null when absent or the scale is 0. */
        fun scaled(p: ByteArray, name: String): Double? {
            val o = at(p, name, 6) ?: return null
            val scale = p.u16le(o + 4)
            if (scale == 0) return null
            val raw = if (metric(name).kind == MKind.SCALED_I32) p.i32le(o).toLong() else p.u32le(o)
            return raw.toDouble() / scale
        }

        private fun metric(name: String): M = metrics.first { it.name == name }

        private fun at(p: ByteArray, name: String, width: Int): Int? =
            off(name)?.takeIf { it + width <= p.size }
    }

    private val warnedBadTable = java.util.concurrent.ConcurrentHashMap.newKeySet<Pair<Int, Int>>()

    /** The verified layout for a record, or null when we do not trust its bytes. */
    fun of(p: ByteArray): Layout? {
        if (p.isEmpty()) return null
        val key = p.size to (p[0].toInt() and 0xFF)
        val l = byKey[key] ?: return null
        if (!l.valid) {
            if (warnedBadTable.add(key)) {
                log.error {
                    "heartbeat metric table is inconsistent for ${l.size}B/v${l.version}: walking it " +
                        "yields ${l.derivedSize} B. Refusing to decode — fix METRICS/LAYOUTS in HeartbeatLayout.kt."
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
                    val end = (o until o + m.strLen + 1).firstOrNull { p[it].toInt() == 0 } ?: (o + m.strLen + 1)
                    HeartbeatMetric(m.name, null, String(p, o, end - o, Charsets.UTF_8).trim(), null)
                }
                MKind.I32 -> {
                    val v = p.i32le(o).toLong()
                    HeartbeatMetric(m.name, v.toDouble(), null, v)
                }
                MKind.U32, MKind.TIMER -> {
                    val v = p.u32le(o)
                    HeartbeatMetric(m.name, v.toDouble(), null, v)
                }
                MKind.SCALED_U32, MKind.SCALED_I32 -> {
                    val raw = if (m.kind == MKind.SCALED_I32) p.i32le(o).toLong() else p.u32le(o)
                    // Scale 0 never comes from the firmware (it writes the compile-time constant);
                    // show the undivided value rather than dropping the metric.
                    val scale = p.u16le(o + 4)
                    HeartbeatMetric(m.name, if (scale > 0) raw.toDouble() / scale else raw.toDouble(), null, raw)
                }
            }
        }
    }
}

// Little-endian readers over the packed record (no byte-swap anywhere in the firmware→DLS path).
internal fun ByteArray.u16le(o: Int): Int = (this[o].toInt() and 0xFF) or ((this[o + 1].toInt() and 0xFF) shl 8)
internal fun ByteArray.u32le(o: Int): Long {
    var v = 0L
    for (i in 0..3) v = v or ((this[o + i].toLong() and 0xFF) shl (8 * i))
    return v
}
internal fun ByteArray.i32le(o: Int): Int = u32le(o).toInt()
internal fun ByteArray.u64le(o: Int): Long {
    var v = 0L
    for (i in 0..7) v = v or ((this[o + i].toLong() and 0xFF) shl (8 * i))
    return v
}
