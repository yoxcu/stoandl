package de.yoxcu.stoandl.battery

import de.yoxcu.stoandl.config.StoandlConfig
import de.yoxcu.stoandl.util.toNdjson
import io.github.oshai.kotlinlogging.KotlinLogging
import io.rebble.libpebblecommon.connection.ConnectedPebbleDevice
import io.rebble.libpebblecommon.connection.LibPebble
import io.rebble.libpebblecommon.connection.PebbleDevice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import java.io.File
import java.util.concurrent.ConcurrentHashMap

private val log = KotlinLogging.logger {}

/**
 * The **fallback** battery source: a time-series of the BLE GATT 0x180F battery level.
 *
 * The primary source is [HeartbeatStore] (the analytics native-heartbeat, which carries voltage, the
 * firmware's own time-to-empty and a measured charge signal, works over Bluetooth Classic, and
 * backfills across disconnects). This store exists only to guarantee *something* when the heartbeat
 * has no validated data for a watch — before its first hourly frame arrives, or if a firmware layout
 * change makes the blob undecodable. It records only the integer battery level, level-only, BLE-only.
 *
 * Unlike [io.rebble.libpebblecommon.datalogging.HealthDataProcessor]'s health data there is no backing
 * DB to re-project from: `batteryLevel` is an in-memory `StateFlow` that resets to null on disconnect,
 * so — like [de.yoxcu.stoandl.datalog.DatalogStore] — we own the raw series and collect it live.
 *
 * Layout: one append-only NDJSON file per watch, keyed by a filesystem-safe form of its display name:
 *
 *     <configDir>/battery/<watchKey>.ndjson
 *     {"ts":1718200000,"level":74,"transport":"ble"}
 *
 * A row is written only when the level *changes* (a `StateFlow` already suppresses equal consecutive
 * emissions; we also dedup because reconnects re-seed the same value and `watches` re-emits for
 * unrelated reasons). Rows older than the retention window are pruned.
 */
class BatteryStore(
    private val libPebble: LibPebble,
    parentScope: CoroutineScope,
    private val retentionDays: Int,
    private val baseDir: File = defaultDir(),
) {
    // Own child scope so [stop] cancels the collector as a unit when battery.history is turned off at
    // runtime. Child of the parent job (daemon shutdown still cancels it).
    private val scope = CoroutineScope(parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job]))
    // Last level we WROTE per watch key — dedups unchanged samples across reconnects / re-emissions.
    private val lastLevel = ConcurrentHashMap<String, Int>()

    fun start() {
        baseDir.mkdirs()
        seedLastLevels()
        runCatching { pruneAll() }.onFailure { log.warn(it) { "battery prune failed" } }
        libPebble.watches
            .onEach { devices -> runCatching { record(devices) }.onFailure { log.warn(it) { "battery record failed" } } }
            .launchIn(scope)
        log.info { "Battery level history enabled → ${baseDir.path} (retention ${retentionDays}d)" }
    }

    /** Stop collecting; already-written NDJSON stays. Re-enabling builds a fresh collector. */
    fun stop() = scope.cancel()

    private fun record(devices: List<PebbleDevice>) {
        val now = System.currentTimeMillis() / 1000
        for (d in devices) {
            val dev = d as? ConnectedPebbleDevice ?: continue
            val level = dev.batteryLevel ?: continue // null over Classic / before the first GATT read
            val key = keyFor(dev.displayName())
            if (lastLevel[key] == level) continue
            lastLevel[key] = level
            val transport = if (dev.usingBtClassic) "classic" else "ble"
            append(key, now, level, transport)
        }
    }

    private fun append(key: String, ts: Long, level: Int, transport: String) {
        val row = buildJsonObject {
            put("ts", ts)
            put("level", level)
            put("transport", transport)
        }
        val file = File(baseDir, "$key.ndjson")
        BatteryFileLocks.withLock(file) { file.appendText(listOf(row).toNdjson()) }
        log.debug { "battery $key = $level% ($transport)" }
    }

    /** The battery %-over-time series for [watchName] (its display name), newest points last, filtered
     *  to `ts >= sinceEpoch`. Empty list when the watch has no recorded history. */
    fun history(watchName: String, sinceEpoch: Long): List<BatteryPoint> =
        readRows(resolveKey(watchName)).filter { it.ts >= sinceEpoch }
            .map { BatteryPoint(ts = it.ts, level = it.level.toDouble(), source = SOURCE) }

    /** Whether we have at least one recorded sample for [watchName]. */
    fun hasData(watchName: String): Boolean = readRows(resolveKey(watchName)).isNotEmpty()

    /** Derived insights for [watchName] from the level series, or null when there are too few samples. */
    fun insights(watchName: String): BatteryInsights? {
        val rows = readRows(resolveKey(watchName)).sortedBy { it.ts }
        if (rows.size < 2) return null
        val now = rows.last().ts
        val level = rows.last().level

        // Rows are written only on a level *change*, so consecutive levels always differ and the most
        // recent change is a rise iff last > prev. But a plateau after unplugging writes no rows, so a
        // *stale* last-rise no longer means charging — gate it on wall-clock recency (the level series
        // can't distinguish "still on charger, full" from "unplugged at the top", but the heartbeat
        // source, which this only falls back from, measures charging directly).
        val nowWall = System.currentTimeMillis() / 1000
        val charging = level > rows[rows.size - 2].level && (nowWall - now) < CHARGING_STALE_S

        // Discharge rate: net drops over discharging intervals within the recent window.
        var dropSum = 0.0
        var dischargeSecs = 0.0
        val w = rows.filter { it.ts >= now - RATE_WINDOW_S }
        for (k in 1 until w.size) {
            val dl = w[k].level - w[k - 1].level
            val dt = (w[k].ts - w[k - 1].ts).toDouble()
            if (dl < 0 && dt > 0) { dropSum += -dl; dischargeSecs += dt }
        }
        val dischargePerHour = if (dischargeSecs > 0) dropSum / (dischargeSecs / 3600.0) else 0.0
        val hoursRemaining = if (!charging && dischargePerHour > 0) level / dischargePerHour else -1.0

        // Charge sessions in the last 7 days + the most recent charge time.
        var sessions = 0
        var lastCharged = -1L
        var rising = false
        val wk = rows.filter { it.ts >= now - WEEK_S }
        for (k in 1 until wk.size) {
            val dl = wk[k].level - wk[k - 1].level
            when {
                dl > 0 -> { if (!rising) { sessions++; rising = true }; lastCharged = wk[k].ts }
                dl < 0 -> rising = false
            }
        }
        if (lastCharged < 0) for (k in 1 until rows.size) if (rows[k].level > rows[k - 1].level) lastCharged = rows[k].ts

        val day = rows.filter { it.ts >= now - DAY_S }
        return BatteryInsights(
            level = level.toDouble(),
            charging = charging,
            dischargePerHour = dischargePerHour,
            hoursRemaining = hoursRemaining,
            chargeSessions7d = sessions,
            lastChargedEpoch = lastCharged,
            min24h = (day.minOfOrNull { it.level } ?: level).toDouble(),
            max24h = (day.maxOfOrNull { it.level } ?: level).toDouble(),
            sampleCount = rows.size,
            voltage = null,
            source = SOURCE,
        )
    }

    // ---- internals -----------------------------------------------------------------------------

    private data class Row(val ts: Long, val level: Int)

    private fun readRows(key: String): List<Row> {
        val file = File(baseDir, "$key.ndjson")
        if (!file.isFile) return emptyList()
        return BatteryFileLocks.withLock(file) { file.readLines() }.mapNotNull { raw ->
            val line = raw.trim()
            if (line.isEmpty()) return@mapNotNull null
            runCatching {
                val obj = Json.parseToJsonElement(line).jsonObject
                Row(obj.getValue("ts").jsonPrimitive.long, obj.getValue("level").jsonPrimitive.int)
            }.getOrNull()
        }
    }

    /** Resolve a display-name query to a stored file key: exact match, then unique substring, else the
     *  sanitized query (which yields an empty series if no such file exists). */
    private fun resolveKey(query: String): String {
        val want = keyFor(query)
        val keys = baseDir.listFiles { f -> f.isFile && f.name.endsWith(".ndjson") }
            ?.map { it.name.removeSuffix(".ndjson") } ?: emptyList()
        return keys.firstOrNull { it == want }
            ?: keys.firstOrNull { it.contains(want, ignoreCase = true) }
            ?: want
    }

    /** Seed [lastLevel] from the last recorded sample of each file so a restart doesn't append a
     *  duplicate row for an unchanged level. */
    private fun seedLastLevels() {
        baseDir.listFiles { f -> f.isFile && f.name.endsWith(".ndjson") }?.forEach { file ->
            val key = file.name.removeSuffix(".ndjson")
            readRows(key).lastOrNull()?.let { lastLevel[key] = it.level }
        }
    }

    private fun pruneAll() {
        val cutoff = System.currentTimeMillis() / 1000 - retentionDays.toLong() * DAY_S
        baseDir.listFiles { f -> f.isFile && f.name.endsWith(".ndjson") }?.forEach { file ->
            BatteryFileLocks.withLock(file) {
                val lines = file.readLines()
                val kept = lines.filter { raw ->
                    val ts = runCatching {
                        Json.parseToJsonElement(raw.trim()).jsonObject.getValue("ts").jsonPrimitive.long
                    }.getOrNull()
                    ts == null || ts >= cutoff // keep unparseable lines rather than lose data
                }
                if (kept.size != lines.size) file.writeText(kept.joinToString("") { "$it\n" })
            }
        }
    }

    companion object {
        private const val SOURCE = "gatt"
        private const val DAY_S = 24L * 3600
        private const val WEEK_S = 7L * DAY_S
        private const val RATE_WINDOW_S = DAY_S // discharge-rate estimation window
        private const val CHARGING_STALE_S = 45L * 60 // a rise older than this is a plateau, not charging

        fun defaultDir(): File = File(StoandlConfig.configDir(), "battery")

        /** Filesystem-safe form of a watch display name (the per-watch file key). */
        internal fun keyFor(name: String): String =
            name.map { if (it.isLetterOrDigit() || it == '-' || it == '_' || it == '.') it else '_' }
                .joinToString("").ifEmpty { "watch" }
    }
}
