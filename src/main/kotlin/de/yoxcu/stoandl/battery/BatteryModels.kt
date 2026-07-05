package de.yoxcu.stoandl.battery

import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Process-wide per-file locks for the battery NDJSON stores. Append, prune (read→truncate→rewrite) and
 * read all run on different threads (libpebble's DataLogging thread, the collector scope, the D-Bus
 * incoming thread), and a store may be rebuilt on a `battery.*` config change while the previous
 * instance still has an append in flight. Keying the lock by canonical path (not by instance) serialises
 * all access to a given file across every store instance, so a reader never sees a half-rewritten file
 * and a prune never clobbers a concurrent append. Locks are cheap and few (one per watch file).
 */
internal object BatteryFileLocks {
    private val locks = ConcurrentHashMap<String, Any>()
    private fun lockFor(file: File): Any = locks.getOrPut(runCatching { file.canonicalPath }.getOrDefault(file.path)) { Any() }
    fun <T> withLock(file: File, block: () -> T): T = synchronized(lockFor(file), block)
}

/**
 * Shared data shapes for the battery-insights feature, so the two data sources present a single,
 * uniform surface to the D-Bus/GUI layer.
 *
 * There are two sources, in priority order:
 *  - **heartbeat** ([HeartbeatStore]) — the watch's analytics native-heartbeat blob (soc at
 *    centi-percent, real voltage/mV, the firmware's own time-to-empty, a measured charge signal).
 *    This is the source of truth, and the only one that also works over Bluetooth Classic and
 *    backfills across disconnects.
 *  - **gatt** ([BatteryStore]) — the BLE GATT 0x180F battery level (integer %, level-only). A lean
 *    fallback used only when the heartbeat has no validated data for a watch (e.g. before the first
 *    hourly heartbeat arrives, or if a firmware layout change makes the blob undecodable).
 */

/** One point in a battery %-over-time series. [level] is 0–100 percent (heartbeat carries fractional
 *  centi-percent; GATT integers). [voltage] (volts) is present only for the heartbeat source. */
data class BatteryPoint(
    /** Epoch seconds — the watch's own record timestamp for heartbeat, host receive time for GATT. */
    val ts: Long,
    val level: Double,
    /** `heartbeat` or `gatt`. */
    val source: String,
    val voltage: Double? = null,
)

/** Derived battery insights, computed by whichever source is chosen for a watch. */
data class BatteryInsights(
    val level: Double,
    val charging: Boolean,
    /** Percent per hour while discharging; 0.0 when charging or not enough data. */
    val dischargePerHour: Double,
    /** Estimated hours until empty; -1.0 when charging or unknown. For the heartbeat source this is
     *  the firmware's own `battery_tte_s` when available, else a computed estimate. */
    val hoursRemaining: Double,
    val chargeSessions7d: Int,
    /** Epoch seconds of the most recent observed charge; -1 when never seen. */
    val lastChargedEpoch: Long,
    val min24h: Double,
    val max24h: Double,
    val sampleCount: Int,
    /** Battery voltage in volts; null for the GATT source (level-only). */
    val voltage: Double?,
    /** `heartbeat` or `gatt` — which source produced these insights. */
    val source: String,
)

/** The latest decoded battery fields from a single native-heartbeat record. */
data class BatterySnapshot(
    /** The watch's own record timestamp (epoch seconds). */
    val watchTs: Long,
    /** State of charge, 0–100 %. */
    val socPct: Double,
    /** Battery voltage in volts. */
    val voltage: Double,
    /** Change in voltage over the heartbeat interval, volts (signed). */
    val voltageDelta: Double,
    /** Firmware's own estimated time-to-empty in seconds (0 when not estimated / charging). */
    val tteSeconds: Long,
    /** Time spent charging during the interval, ms (>0 ⇒ was on charger). */
    val chargeMs: Long,
    /** Time spent discharging during the interval, ms. */
    val dischargeMs: Long,
    /** True when the watch was on the charger during the interval (`chargeMs > 0`). */
    val charging: Boolean,
    /** Host receive time (epoch seconds). */
    val rxTs: Long,
)
