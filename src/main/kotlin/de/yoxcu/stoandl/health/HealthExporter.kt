package de.yoxcu.stoandl.health

import de.yoxcu.stoandl.config.StoandlConfig
import io.github.oshai.kotlinlogging.KotlinLogging
import io.rebble.libpebblecommon.connection.LibPebble
import io.rebble.libpebblecommon.health.OverlayType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.seconds

private val log = KotlinLogging.logger {}

/**
 * Projects the watch's synced health/activity data (steps, sleep, heart rate, workouts) into readable
 * NDJSON files so headless stoandl has a local "store to write to" (the official app's equivalent is
 * syncing to Apple Health / Google Fit — there's no dashboard here).
 *
 * libpebble3 already ingests the data: [io.rebble.libpebblecommon.datalogging.HealthDataProcessor]
 * parses the watch's health datalog frames straight into the shared `libpebble3.db`. This class only
 * reads it back out — via [LibPebble]'s HealthDataApi (which owns the aggregation/sleep-grouping
 * logic) — whenever new data lands ([LibPebble.healthDataUpdated]), debounced so a sync burst yields a
 * single re-projection.
 *
 * Layout under `<configDir>/health/`:
 *
 *     daily.ndjson         one object per day (steps/distance/calories + sleep + heart rate)
 *     activities.ndjson    one object per workout overlay (Walk / Run / OpenWorkout)
 *     samples/<date>.ndjson   minute-level steps + heart rate (only when health.export_samples=on)
 *
 * `daily.ndjson` and `activities.ndjson` are upserts keyed by day / session start: each run re-projects
 * the last [exportDays] days and merges over existing rows, so older days already written stay put.
 * Units are normalised for consumers: distance in metres, energy in kcal, durations in minutes.
 */
class HealthExporter(
    private val libPebble: LibPebble,
    private val scope: CoroutineScope,
    private val exportDays: Int,
    private val exportSamples: Boolean,
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val baseDir: File = defaultDir(),
) {
    // Conflated: a sync emits healthDataUpdated many times; we only need to know "something changed".
    private val requests = Channel<Unit>(Channel.CONFLATED)

    fun start() {
        libPebble.healthDataUpdated.onEach { requests.trySend(Unit) }.launchIn(scope)
        scope.launch {
            // Project whatever is already in the DB at startup (data may predate this run), then react
            // to updates. The short delay lets the connection/DB settle first.
            delay(2.seconds)
            runExport()
            for (r in requests) {
                delay(DEBOUNCE) // coalesce a sync burst into one projection
                while (requests.tryReceive().isSuccess) { /* drain */ }
                runExport()
            }
        }
        log.info {
            "Health export enabled → ${baseDir.path} (last ${exportDays}d" +
                (if (exportSamples) ", + minute-level samples" else "") + ")"
        }
    }

    /** Re-project now (used by the `health sync` D-Bus call after requesting fresh data). */
    suspend fun exportNow() = runExport()

    private suspend fun runExport() =
        runCatching { export() }.onFailure { log.warn(it) { "health export failed" } }

    private suspend fun export() {
        baseDir.mkdirs()
        val today = LocalDate.now(zone)
        val days = (0 until exportDays).map { today.minusDays(it.toLong()) }.sortedBy { it }
        val windowStart = days.first().atStartOfDay(zone).toEpochSecond()
        val windowEnd = today.plusDays(1).atStartOfDay(zone).toEpochSecond()

        // --- daily summary -------------------------------------------------------------------
        val daily = LinkedHashMap<String, JsonObject>()
        for (date in days) {
            val dayStart = date.atStartOfDay(zone).toEpochSecond()
            val dayEnd = date.plusDays(1).atStartOfDay(zone).toEpochSecond()
            buildDay(date.toString(), dayStart, dayEnd)?.let { daily[date.toString()] = it }
        }
        upsert(File(baseDir, "daily.ndjson"), "date", daily)

        // --- activity sessions ---------------------------------------------------------------
        val activities = LinkedHashMap<String, JsonObject>()
        for (s in libPebble.getActivitySessions(windowStart, windowEnd)) {
            val obj = buildJsonObject {
                put("start", s.startTime)
                put("end", s.startTime + s.duration)
                put("type", (OverlayType.fromValue(s.type)?.name ?: s.type.toString()))
                put("duration_min", (s.duration / 60.0).roundToInt())
                put("steps", s.steps)
                put("distance_m", (s.distanceCm / 100.0).roundToInt())
                put("active_kcal", s.activeKiloCalories)
            }
            activities[s.startTime.toString()] = obj
        }
        upsert(File(baseDir, "activities.ndjson"), "start", activities)

        // --- minute-level samples (opt-in) ---------------------------------------------------
        if (exportSamples) {
            val samplesDir = File(baseDir, "samples").apply { mkdirs() }
            for (date in days) {
                val dayStart = date.atStartOfDay(zone).toEpochSecond()
                val dayEnd = date.plusDays(1).atStartOfDay(zone).toEpochSecond()
                val rows = libPebble.getHealthDataForRange(dayStart, dayEnd)
                    .filter { it.steps > 0 || it.heartRate > 0 }
                if (rows.isEmpty()) continue
                val text = buildString {
                    for (r in rows) {
                        append(Json.encodeToString(JsonObject.serializer(), buildJsonObject {
                            put("ts", r.timestamp)
                            put("steps", r.steps)
                            put("hr", r.heartRate)
                            put("hr_zone", r.heartRateZone)
                        }))
                        append('\n')
                    }
                }
                File(samplesDir, "${date}.ndjson").writeText(text)
            }
        }
    }

    /** Build one daily-summary object, or null when the day has no movement, sleep or heart-rate data. */
    private suspend fun buildDay(date: String, dayStart: Long, dayEnd: Long): JsonObject? {
        val move = libPebble.getTotalHealthData(dayStart, dayEnd)
        val sleep = libPebble.getDailySleepSession(dayStart)
        val avgHr = libPebble.getAverageHeartRate(dayStart, dayEnd)
        val restingHr = libPebble.getRestingHeartRate(dayStart)
        val zones = libPebble.getHRZoneMinutes(dayStart, dayEnd)

        val steps = move?.steps ?: 0
        val hasData = steps > 0 || sleep != null || avgHr != null || zones.isNotEmpty()
        if (!hasData) return null

        return buildJsonObject {
            put("date", date)
            put("steps", steps)
            put("distance_m", ((move?.distanceCm ?: 0) / 100.0).roundToInt())
            put("active_minutes", move?.activeMinutes ?: 0)
            put("active_kcal", ((move?.activeGramCalories ?: 0) / 1000.0).roundToInt())
            put("resting_kcal", ((move?.restingGramCalories ?: 0) / 1000.0).roundToInt())
            if (sleep != null) {
                put("sleep_total_min", (sleep.totalSleep / 60.0).roundToInt())
                put("sleep_deep_min", (sleep.deepSleep / 60.0).roundToInt())
                put("sleep_start", sleep.firstStart)
                put("sleep_end", sleep.lastEnd)
            }
            restingHr?.let { put("resting_hr", it) }
            avgHr?.let { put("avg_hr", it.roundToInt()) }
            if (zones.isNotEmpty()) {
                put("hr_zone_min", buildJsonObject {
                    zones.toSortedMap().forEach { (zoneIdx, minutes) -> put(zoneIdx.toString(), minutes) }
                })
            }
        }
    }

    /**
     * Merge [rows] (keyed by [keyField]) into an NDJSON [file], overwriting any existing entry with the
     * same key and preserving rows outside the projected window. Output is sorted by the key so the file
     * stays chronological.
     */
    private fun upsert(file: File, keyField: String, rows: Map<String, JsonObject>) {
        if (rows.isEmpty() && !file.exists()) return
        val merged = LinkedHashMap<String, JsonObject>()
        if (file.exists()) {
            file.readLines().forEach { raw ->
                val line = raw.trim()
                if (line.isEmpty()) return@forEach
                runCatching {
                    val obj = Json.parseToJsonElement(line).jsonObject
                    val key = (obj[keyField] as? JsonPrimitive)?.content ?: return@runCatching
                    merged[key] = obj
                }
            }
        }
        merged.putAll(rows)
        // Numeric keys (session start times) sort numerically; date strings sort lexicographically,
        // which is also chronological for YYYY-MM-DD.
        val sorted = merged.entries.sortedWith(compareBy({ it.key.toLongOrNull() == null }, { it.key.toLongOrNull() ?: 0L }, { it.key }))
        val text = buildString {
            for ((_, obj) in sorted) {
                append(Json.encodeToString(JsonObject.serializer(), obj))
                append('\n')
            }
        }
        file.writeText(text)
    }

    companion object {
        private val DEBOUNCE = 3.seconds

        fun defaultDir(): File = File(StoandlConfig.configDir(), "health")
    }
}
