package de.yoxcu.stoandl.battery

import de.yoxcu.stoandl.config.StoandlConfig
import de.yoxcu.stoandl.util.toNdjson
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import java.io.File
import java.util.Base64

private val log = KotlinLogging.logger {}

/**
 * The **primary** battery source: the watch's analytics *native heartbeat*.
 *
 * PebbleOS emits one `native_heartbeat_record` per hour over the DataLogging Service (system tag 87,
 * all-zero UUID). libpebble3 already routes those items to `WebServices.uploadAnalyticsHeartbeat`,
 * which stoandl owns — so capturing them needs no fork change. The official Core app forwards the same
 * blob to a cloud service that computes its battery insights; stoandl decodes the battery fields
 * locally instead.
 *
 * The record is a `struct PACKED native_heartbeat_record` (little-endian ARM, copied raw to the DLS
 * byte array). Layout, verified against `coredevices/PebbleOS@main`
 * (`src/fw/services/analytics/native.c`, `include/pbl/services/analytics/analytics.def`):
 *
 *     header (29 B): version:u8 @0 (=1) | timestamp:u64 @1 | build_id:u8[20] @9
 *     battery block (struct offsets): soc_pct:u32 @102 (÷scale @106 =100)
 *                                     soc_pct_drop:u32 @108 (÷scale @112 =100)
 *                                     voltage:u32 @114 (÷scale @118 =1000, → volts)
 *                                     voltage_delta:i32 @120 (÷scale @124 =1000)
 *                                     tte_s:u32 @126 | charge_time_ms:u32 @130 | discharge_ms:u32 @134
 *     total sizeof = 523 B (== one uploadAnalyticsHeartbeat payload).
 *
 * The same record also carries per-subsystem on-time timers, CPU residency/per-task CPU %, and event
 * counters (notifications, etc.). Those are decoded on demand by [decodeActivity] (for the drop /
 * power-attribution / notification views) from the stored raw blob — see `analytics.def` for the full
 * 91-metric layout; the offsets used live in that method.
 *
 * The blob layout is firmware-version-specific (any reordered/added metric in `analytics.def` shifts
 * every offset, and the version byte does not necessarily change). So decoding is **strictly guarded**:
 * we decode only when `size == 523 && version == 1`, the on-wire scale fields match the compile-time
 * constants, and the values are physically plausible. On any mismatch we still persist the **raw**
 * blob (base64) + header so the exact firmware build can be identified and the offsets finalized from a
 * hardware dump — we never emit a guessed value. Every record (decoded or not) keeps its raw bytes, so
 * the file is a lossless local capture of the analytics heartbeat.
 *
 * Layout: one append-only NDJSON file per watch, keyed by serial:
 *
 *     <configDir>/battery/heartbeat/<serial>.ndjson
 */
class HeartbeatStore(
    private val retentionDays: Int,
    private val baseDir: File = defaultDir(),
) {
    fun start() {
        baseDir.mkdirs()
        runCatching { baseDir.listFiles { f -> f.isFile && f.name.endsWith(".ndjson") }?.forEach { pruneFile(it.name.removeSuffix(".ndjson")) } }
            .onFailure { log.warn(it) { "heartbeat prune failed" } }
        log.info { "Battery analytics-heartbeat capture enabled → ${baseDir.path} (retention ${retentionDays}d)" }
    }

    /** Handle one `native_heartbeat_record` payload (called from the WebServices override). Decodes the
     *  battery fields when the layout is trusted; always persists the raw blob + header. */
    fun record(payload: ByteArray, serial: String, fwVersion: String) {
        runCatching { store(payload, serial, fwVersion) }.onFailure { log.warn(it) { "heartbeat record failed" } }
    }

    private fun store(payload: ByteArray, serial: String, fwVersion: String) {
        val rx = System.currentTimeMillis() / 1000
        val key = keyFor(serial)
        val version = if (payload.isNotEmpty()) payload[0].toInt() and 0xFF else -1
        val hasHeader = payload.size >= HEADER_SIZE
        val buildId = if (hasHeader) payload.hex(9, BUILD_ID_LEN) else ""
        val snap = decode(payload, rx)

        val row = buildJsonObject {
            put("rx", rx)
            put("decoded", snap != null)
            if (version >= 0) put("version", version)
            if (buildId.isNotEmpty()) put("build_id", buildId)
            put("fw", fwVersion)
            put("size", payload.size)
            if (snap != null) {
                put("watch_ts", snap.watchTs)
                put("soc", snap.socPct)
                put("voltage", snap.voltage)
                put("voltage_delta", snap.voltageDelta)
                put("tte_s", snap.tteSeconds)
                put("charge_ms", snap.chargeMs)
                put("discharge_ms", snap.dischargeMs)
                put("charging", snap.charging)
            } else if (hasHeader) {
                put("watch_ts", payload.u64le(1))
            }
            put("raw", Base64.getEncoder().encodeToString(payload))
        }
        File(baseDir, "$key.ndjson").let { f -> BatteryFileLocks.withLock(f) { f.appendText(listOf(row).toNdjson()) } }

        if (snap != null) {
            log.debug { "heartbeat $serial: ${snap.socPct}% ${snap.voltage}V charging=${snap.charging} (tte=${snap.tteSeconds}s)" }
        } else {
            log.warn {
                "analytics heartbeat undecodable (size=${payload.size} version=$version " +
                    "first16=${payload.hex(0, minOf(16, payload.size))}) — captured raw for $serial"
            }
        }
        runCatching { pruneFile(key) }.onFailure { log.debug(it) { "heartbeat prune failed" } }
    }

    /** Decode the battery block, or null when the layout isn't trusted (see class docs). */
    private fun decode(p: ByteArray, rx: Long): BatterySnapshot? {
        if (p.size != NATIVE_HB_SIZE) return null
        if ((p[0].toInt() and 0xFF) != NATIVE_HB_VERSION) return null
        // Wire scale fields must match the compile-time constants (a mismatch signals layout drift).
        if (p.u16le(106) != 100 || p.u16le(118) != 1000) return null
        val soc = p.u32le(102) / 100.0
        val voltage = p.u32le(114) / 1000.0
        if (soc !in 0.0..100.0) return null
        if (voltage !in VOLT_MIN..VOLT_MAX) return null
        val chargeMs = p.u32le(130)
        return BatterySnapshot(
            watchTs = p.u64le(1),
            socPct = soc,
            voltage = voltage,
            voltageDelta = p.i32le(120) / 1000.0,
            tteSeconds = p.u32le(126),
            chargeMs = chargeMs,
            dischargeMs = p.u32le(134),
            charging = chargeMs > 0,
            rxTs = rx,
        )
    }

    /** Decode the richer subsystem-activity + event-counter fields (drop / power / notifications), or null
     *  when the layout isn't trusted. Same trust gate as [decode] plus the CPU-percent scale field; every
     *  value read here lives in the same guarded 523-byte record, so it backfills from the stored raw. */
    private fun decodeActivity(p: ByteArray): HeartbeatActivity? {
        if (p.size != NATIVE_HB_SIZE) return null
        if ((p[0].toInt() and 0xFF) != NATIVE_HB_VERSION) return null
        if (p.u16le(106) != 100 || p.u16le(118) != 1000) return null
        if (p.u16le(202) != 100) return null // cpu_running_pct scale — a mismatch signals layout drift
        val soc = p.u32le(102) / 100.0
        if (soc !in 0.0..100.0) return null
        // Scaled percentages carry an inline u16 scale right after the u32 value; divide by it.
        fun pct(o: Int): Double { val s = p.u16le(o + 4); return if (s > 0) p.u32le(o).toDouble() / s else 0.0 }
        val dropScale = p.u16le(112)
        val drop = if (dropScale > 0) p.u32le(108).toDouble() / dropScale else null
        return HeartbeatActivity(
            ts = p.u64le(1),
            socPct = soc,
            socDropPct = drop?.takeIf { it in 0.0..100.0 },
            intervalMs = p.u32le(130) + p.u32le(134), // charge_time_ms + discharge_duration_ms
            backlightMs = p.u32le(138),
            backlightIntensityPct = p.u32le(142).toInt().coerceIn(0, 100),
            vibratorMs = p.u32le(146),
            vibratorStrengthPct = p.u32le(150).toInt().coerceIn(0, 100),
            speakerMs = p.u32le(154),
            speakerVolumePct = p.u32le(162).toInt().coerceIn(0, 100),
            hrmMs = p.u32le(174),
            phoneCallMs = p.u32le(314),
            watchfaceMs = p.u32le(326),
            btConnectedMs = p.u32le(515),
            cpuRunningPct = pct(198),
            cpuAppPct = pct(244),
            cpuBtPct = pct(250) + pct(256) + pct(262), // bt_host + bt_controller + bt_hci
            cpuWorkerPct = pct(238),
            cpuKernelPct = pct(226) + pct(232), // kernel_main + kernel_background
            notifCount = p.u32le(302),
            notifDndCount = p.u32le(306),
            phoneCallCount = p.u32le(310),
        )
    }

    /** Whether we have at least one *decoded* heartbeat for [serialQuery] — the gate the read layer uses
     *  to decide "heartbeat is the source of truth for this watch" vs. falling back to the GATT series. */
    fun hasData(serialQuery: String): Boolean = readDecoded(resolveKey(serialQuery)).isNotEmpty()

    /** The battery %-over-time series (soc + voltage), newest last, filtered to `ts >= sinceEpoch`. */
    fun history(serialQuery: String, sinceEpoch: Long): List<BatteryPoint> =
        readDecoded(resolveKey(serialQuery)).filter { it.ts >= sinceEpoch }.sortedBy { it.ts }
            .map { BatteryPoint(ts = it.ts, level = it.soc, source = SOURCE, voltage = it.voltage) }

    /** Derived insights from the heartbeat series, or null when there are no decoded records. */
    fun insights(serialQuery: String): BatteryInsights? {
        val rows = readDecoded(resolveKey(serialQuery)).sortedBy { it.ts }
        if (rows.isEmpty()) return null
        val last = rows.last()
        val now = last.ts

        // Discharge rate from soc drops over discharging intervals in the recent window.
        var dropSum = 0.0
        var dischargeSecs = 0.0
        val w = rows.filter { it.ts >= now - RATE_WINDOW_S }
        for (k in 1 until w.size) {
            val dl = w[k].soc - w[k - 1].soc
            val dt = (w[k].ts - w[k - 1].ts).toDouble()
            if (dl < 0 && dt > 0) { dropSum += -dl; dischargeSecs += dt }
        }
        val dischargePerHour = if (dischargeSecs > 0) dropSum / (dischargeSecs / 3600.0) else 0.0
        val hoursRemaining = when {
            last.charging -> -1.0
            last.tteS > 0 -> last.tteS / 3600.0 // firmware's own time-to-empty estimate
            dischargePerHour > 0 -> last.soc / dischargePerHour
            else -> -1.0
        }

        // Charge sessions in the last 7 days: maximal runs of charging heartbeats (charge_ms > 0).
        var sessions = 0
        var lastCharged = -1L
        var inCharge = false
        for (r in rows.filter { it.ts >= now - WEEK_S }) {
            if (r.chargeMs > 0) { if (!inCharge) { sessions++; inCharge = true }; lastCharged = r.ts }
            else inCharge = false
        }
        if (lastCharged < 0) rows.lastOrNull { it.chargeMs > 0 }?.let { lastCharged = it.ts }

        val day = rows.filter { it.ts >= now - DAY_S }
        return BatteryInsights(
            level = last.soc,
            charging = last.charging,
            dischargePerHour = dischargePerHour,
            hoursRemaining = hoursRemaining,
            chargeSessions7d = sessions,
            lastChargedEpoch = lastCharged,
            min24h = day.minOfOrNull { it.soc } ?: last.soc,
            max24h = day.maxOfOrNull { it.soc } ?: last.soc,
            sampleCount = rows.size,
            voltage = last.voltage,
            source = SOURCE,
        )
    }

    /** Per-interval subsystem activity (drop, notifications, on-times), newest last, `ts >= sinceEpoch`.
     *  Re-decoded from each row's stored raw blob, so it backfills across the whole retained history. */
    fun activity(serialQuery: String, sinceEpoch: Long): List<HeartbeatActivity> =
        readRaw(resolveKey(serialQuery)).mapNotNull { decodeActivity(it) }
            .filter { it.ts >= sinceEpoch }.sortedBy { it.ts }

    /**
     * Aggregate the window's subsystem activity into a power/usage-attribution breakdown (the pie).
     *
     * Each subsystem's contribution is an **active-time proxy**: analog loads by on-time × intensity
     * (backlight/speaker) or raw on-time (vibration/HRM), compute by CPU-active fraction × interval
     * (`task_cpu_*_pct`). BT uses the BT-stack CPU time (`bt_host+controller+hci`), not the connected
     * on-time — an idle-but-connected link draws little, so connected time would dominate misleadingly.
     * Shares are of the total. This is a usage estimate, not measured energy (see [PowerSlice]).
     * Returns slices sorted by share desc, dropping zero contributors; empty when there's no data.
     */
    fun power(serialQuery: String, sinceEpoch: Long): List<PowerSlice> {
        val rows = activity(serialQuery, sinceEpoch)
        if (rows.isEmpty()) return emptyList()
        val acc = LinkedHashMap<String, Double>() // insertion order = stable legend order on ties
        fun add(cat: String, v: Double) { if (v > 0) acc[cat] = (acc[cat] ?: 0.0) + v }
        for (r in rows) {
            val period = if (r.intervalMs > 0) r.intervalMs.toDouble() else HEARTBEAT_PERIOD_MS
            add(CAT_DISPLAY, r.backlightMs * (r.backlightIntensityPct / 100.0))
            add(CAT_VIBRATION, r.vibratorMs.toDouble()) // motor on-time (short, high-draw bursts)
            add(CAT_SPEAKER, r.speakerMs * (r.speakerVolumePct / 100.0))
            add(CAT_HRM, r.hrmMs.toDouble())
            add(CAT_BLUETOOTH, (r.cpuBtPct / 100.0) * period)
            add(CAT_CPU, ((r.cpuAppPct + r.cpuWorkerPct + r.cpuKernelPct) / 100.0) * period)
        }
        val total = acc.values.sum()
        if (total <= 0) return emptyList()
        return acc.entries.map { PowerSlice(it.key, it.value.toLong(), 100.0 * it.value / total) }
            .sortedByDescending { it.sharePct }
    }

    // ---- internals -----------------------------------------------------------------------------

    private data class HbRow(
        val ts: Long, val soc: Double, val voltage: Double, val tteS: Long, val chargeMs: Long, val charging: Boolean,
    )

    private fun readDecoded(key: String): List<HbRow> {
        val file = File(baseDir, "$key.ndjson")
        if (!file.isFile) return emptyList()
        return BatteryFileLocks.withLock(file) { file.readLines() }.mapNotNull { raw ->
            val line = raw.trim()
            if (line.isEmpty()) return@mapNotNull null
            runCatching {
                val o = Json.parseToJsonElement(line).jsonObject
                if (o["decoded"]?.jsonPrimitive?.booleanOrNull != true) return@runCatching null
                HbRow(
                    ts = o.getValue("watch_ts").jsonPrimitive.long,
                    soc = o.getValue("soc").jsonPrimitive.double,
                    voltage = o.getValue("voltage").jsonPrimitive.double,
                    tteS = o["tte_s"]?.jsonPrimitive?.long ?: 0L,
                    chargeMs = o["charge_ms"]?.jsonPrimitive?.long ?: 0L,
                    charging = o["charging"]?.jsonPrimitive?.booleanOrNull ?: false,
                )
            }.getOrNull()
        }
    }

    /** The raw (base64-decoded) blob of every stored record for [key], in file order. Used by
     *  [activity]/[power] to re-decode the richer fields retroactively — every row keeps its raw bytes. */
    private fun readRaw(key: String): List<ByteArray> {
        val file = File(baseDir, "$key.ndjson")
        if (!file.isFile) return emptyList()
        return BatteryFileLocks.withLock(file) { file.readLines() }.mapNotNull { raw ->
            val line = raw.trim()
            if (line.isEmpty()) return@mapNotNull null
            runCatching {
                val b64 = Json.parseToJsonElement(line).jsonObject["raw"]?.jsonPrimitive?.content ?: return@runCatching null
                Base64.getDecoder().decode(b64)
            }.getOrNull()
        }
    }

    private fun resolveKey(query: String): String {
        val want = keyFor(query)
        val keys = baseDir.listFiles { f -> f.isFile && f.name.endsWith(".ndjson") }
            ?.map { it.name.removeSuffix(".ndjson") } ?: emptyList()
        return keys.firstOrNull { it == want }
            ?: keys.firstOrNull { it.contains(want, ignoreCase = true) }
            ?: want
    }

    private fun pruneFile(key: String) {
        val file = File(baseDir, "$key.ndjson")
        if (!file.isFile) return
        val cutoff = System.currentTimeMillis() / 1000 - retentionDays.toLong() * DAY_S
        BatteryFileLocks.withLock(file) {
            val lines = file.readLines()
            val kept = lines.filter { raw ->
                val rx = runCatching { Json.parseToJsonElement(raw.trim()).jsonObject["rx"]?.jsonPrimitive?.long }.getOrNull()
                rx == null || rx >= cutoff // keep unparseable lines rather than lose data
            }
            if (kept.size != lines.size) file.writeText(kept.joinToString("") { "$it\n" })
        }
    }

    companion object {
        private const val SOURCE = "heartbeat"
        private const val DAY_S = 24L * 3600
        private const val WEEK_S = 7L * DAY_S
        private const val RATE_WINDOW_S = DAY_S
        private const val HEARTBEAT_PERIOD_MS = 3_600_000.0 // firmware HEARTBEAT_PERIOD_SEC, ms

        // Power/usage-attribution categories (see `power`). Display is proxied by the backlight (the
        // record has no display-on-time metric); Bluetooth is the BT-stack CPU time.
        internal const val CAT_DISPLAY = "Display"
        internal const val CAT_VIBRATION = "Vibration"
        internal const val CAT_SPEAKER = "Speaker"
        internal const val CAT_HRM = "Heart rate"
        internal const val CAT_BLUETOOTH = "Bluetooth"
        internal const val CAT_CPU = "CPU"

        // native_heartbeat_record layout (coredevices/PebbleOS@main, PACKED little-endian).
        private const val NATIVE_HB_VERSION = 1
        private const val NATIVE_HB_SIZE = 523
        private const val BUILD_ID_LEN = 20
        private const val HEADER_SIZE = 1 + 8 + BUILD_ID_LEN // 29
        private const val VOLT_MIN = 3.0 // plausible single-cell Li-ion range
        private const val VOLT_MAX = 4.5

        fun defaultDir(): File = File(StoandlConfig.configDir(), "battery/heartbeat")

        /** Filesystem-safe form of a watch serial (the per-watch file key). */
        internal fun keyFor(serial: String): String =
            serial.map { if (it.isLetterOrDigit() || it == '-' || it == '_' || it == '.') it else '_' }
                .joinToString("").ifEmpty { "watch" }
    }
}

// Little-endian readers over the packed record (no byte-swap anywhere in the firmware→DLS path).
private fun ByteArray.u16le(o: Int) = (this[o].toInt() and 0xFF) or ((this[o + 1].toInt() and 0xFF) shl 8)
private fun ByteArray.u32le(o: Int): Long {
    var v = 0L
    for (i in 0..3) v = v or ((this[o + i].toLong() and 0xFF) shl (8 * i))
    return v
}
private fun ByteArray.i32le(o: Int): Int = u32le(o).toInt()
private fun ByteArray.u64le(o: Int): Long {
    var v = 0L
    for (i in 0..7) v = v or ((this[o + i].toLong() and 0xFF) shl (8 * i))
    return v
}
private fun ByteArray.hex(o: Int, len: Int): String = buildString { for (i in 0 until len) append("%02x".format(this@hex[o + i])) }
