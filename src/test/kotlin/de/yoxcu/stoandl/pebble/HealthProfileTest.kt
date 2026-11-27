package de.yoxcu.stoandl.pebble

import io.rebble.libpebblecommon.database.entity.HRMonitoringInterval
import io.rebble.libpebblecommon.database.entity.HealthGender
import io.rebble.libpebblecommon.health.HealthSettings
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** `GetHealthProfile` records and `SetHealthProfile` key parsing. */
class HealthProfileTest {
    private val year = 2026
    private val base = HealthSettings(
        heightMm = 1780, weightDag = 7450, trackingEnabled = true, activityInsightsEnabled = false,
        sleepInsightsEnabled = false, ageYears = 31, birthYear = 1995, gender = HealthGender.Other, imperialUnits = false,
        hrmEnabled = true, hrmMeasurementInterval = HRMonitoringInterval.TenMin, hrmActivityTrackingEnabled = false,
        restingHr = 58, elevatedHr = 100, maxHr = 189, hrZone1Threshold = 130, hrZone2Threshold = 150,
        hrZone3Threshold = 170,
    )

    private val time2 = mapOf("Time 2" to "v4.41.1")

    private fun rows(s: HealthSettings, connected: Map<String, String> = time2) =
        healthProfileRecords(s, connected, year).map { it.split('\t') }

    private fun records(s: HealthSettings) = rows(s).associate { it[0] to it[1] }

    private fun choices(s: HealthSettings, connected: Map<String, String>) =
        rows(s, connected).single { it[0] == "hrm_interval" }[2].split('|')

    @Test
    fun `records carry every key with its wire spelling`() {
        val r = records(base)
        assertEquals("off", r["hrm_activity"])
        assertEquals("10min", r["hrm_interval"])
        assertEquals("178", r["height_cm"])
        assertEquals("74.5", r["weight_kg"])
        assertEquals("other", r["gender"])
        assertEquals(
            listOf("tracking", "activity_insights", "sleep_insights", "hrm", "hrm_interval", "hrm_activity", "units",
                "height_cm", "weight_kg", "age", "birth_year", "gender", "resting_hr", "max_hr"),
            rows(base).map { it[0] },
        )
    }

    @Test
    fun `hr during activities is set by hrm_activity`() {
        val on = applyHealthProfileKey(base, "hrm_activity", " On ").getOrThrow()
        assertTrue(on.hrmActivityTrackingEnabled)
        assertEquals(base.copy(hrmActivityTrackingEnabled = true), on)
        assertEquals("on", records(on)["hrm_activity"])
        assertEquals(base, applyHealthProfileKey(on, "HRM_ACTIVITY", "off").getOrThrow())
    }

    @Test
    fun `every record round-trips through its own key`() {
        rows(base).forEach { (k, v) ->
            assertEquals(base, applyHealthProfileKey(base, k, v, year).getOrThrow(), k)
        }
    }

    /** libpebble3 re-derives the age from the birth year on every write, so the two move together. */
    @Test
    fun `age and birth year set each other`() {
        val older = applyHealthProfileKey(base, "age", "40", year).getOrThrow()
        assertEquals(40 to 1986, older.ageYears to older.birthYear)
        val born = applyHealthProfileKey(base, "birth_year", "1990", year).getOrThrow()
        assertEquals(36 to 1990, born.ageYears to born.birthYear)
        assertContains(applyHealthProfileKey(base, "birth_year", "2026", year).exceptionOrNull()!!.message!!, "1906 to 2025")
        assertContains(applyHealthProfileKey(base, "age", "0", year).exceptionOrNull()!!.message!!, "1-120")
        // Before libpebble3 has stored a birth year, the record shows the one the age implies.
        assertEquals("1995", records(base.copy(birthYear = null))["birth_year"])
    }

    /** The watch gets 220 - age whatever max_hr says: setting another value must not report success. */
    @Test
    fun `max_hr is derived and refuses a value of its own`() {
        assertEquals(base, applyHealthProfileKey(base, "max_hr", "189", year).getOrThrow())
        val refused = applyHealthProfileKey(base, "max_hr", "200", year).exceptionOrNull()!!.message!!
        assertContains(refused, "220 - age, at least the elevated heart rate of 100 bpm")
        assertContains(refused, "189 bpm")
        assertContains(refused, "birth_year")
        // resting_hr still reaches the watch (the zones are derived from it and the age).
        assertEquals(64, applyHealthProfileKey(base, "resting_hr", "64", year).getOrThrow().restingHr.toInt())
    }

    /** A new interval upstream adds must get a spelling here, or GetHealthProfile could not name it. */
    @Test
    fun `every interval libpebble3 names has a spelling`() {
        assertEquals(HRMonitoringInterval.entries.toSet(), HRM_INTERVAL_NAMES.map { it.first }.toSet())
        HRM_INTERVAL_NAMES.forEach { (interval, name) ->
            assertEquals(interval, applyHealthProfileKey(base, "hrm_interval", name, year).getOrThrow().hrmMeasurementInterval)
        }
        // Another key leaves the interval alone, so a code libpebble3 keeps verbatim behind it survives.
        assertEquals(base.hrmMeasurementInterval, applyHealthProfileKey(base, "units", "imperial", year).getOrThrow().hrmMeasurementInterval)
    }

    @Test
    fun `bad values and unknown keys explain themselves`() {
        assertContains(applyHealthProfileKey(base, "hrm_activity", "maybe").exceptionOrNull()!!.message!!, "on/off")
        assertContains(applyHealthProfileKey(base, "hrm_interval", "2min").exceptionOrNull()!!.message!!, "5min/10min")
        assertContains(applyHealthProfileKey(base, "shoe_size", "44").exceptionOrNull()!!.message!!, "Unknown health key")
    }

    @Test
    fun `five minutes reads and writes as 5min`() {
        val five = applyHealthProfileKey(base, "hrm_interval", "5min").getOrThrow()
        assertEquals(HRMonitoringInterval.FiveMin, five.hrmMeasurementInterval)
        assertEquals("5min", records(five)["hrm_interval"])
        assertEquals(five, applyHealthProfileKey(base, "hrm_interval", "5m").getOrThrow())
    }

    @Test
    fun `five minutes needs a connected watch on 4_39_0 or newer`() {
        val five = base.copy(hrmMeasurementInterval = HRMonitoringInterval.FiveMin)
        assertNull(healthProfileRefusal(base, five, mapOf("Time 2" to "v4.39.0")))
        assertNull(healthProfileRefusal(base, five, mapOf("Time 2" to "v4.41.1")))
        assertNull(healthProfileRefusal(base, five, mapOf("Time 2" to "v4.39.0-rc1")))
        val old = healthProfileRefusal(base, five, mapOf("Time Steel" to "v4.38.4"))!!
        assertContains(old, "Time Steel runs firmware v4.38.4")
        assertContains(old, "4.39.0")
        assertContains(healthProfileRefusal(base, five, emptyMap())!!, "Connect the watch first")
        assertContains(healthProfileRefusal(base, five, mapOf("Odd" to "garbage"))!!, "Odd")
    }

    @Test
    fun `other changes are never refused`() {
        val five = base.copy(hrmMeasurementInterval = HRMonitoringInterval.FiveMin)
        // The watch chose 5 min itself; toggling another key keeps it, whatever the firmware says.
        assertNull(healthProfileRefusal(five, five.copy(hrmEnabled = false), emptyMap()))
        assertNull(healthProfileRefusal(base, base.copy(hrmMeasurementInterval = HRMonitoringInterval.OneHour), emptyMap()))
    }

    @Test
    fun `hrm_interval advertises the intervals the daemon accepts`() {
        val all = listOf("5min", "10min", "30min", "1h", "off")
        assertEquals(all, choices(base, time2))
        assertEquals(all - "5min", choices(base, mapOf("Time Steel" to "v4.4.2")))
        assertEquals(all - "5min", choices(base, emptyMap()))
        // Every advertised choice is one SetHealthProfile takes.
        for (connected in listOf(time2, emptyMap())) {
            choices(base, connected).forEach { v ->
                val next = applyHealthProfileKey(base, "hrm_interval", v).getOrThrow()
                assertNull(healthProfileRefusal(base, next, connected), v)
            }
        }
        // The watch's own 5 minutes stays selectable whatever is connected.
        assertEquals(all, choices(base.copy(hrmMeasurementInterval = HRMonitoringInterval.FiveMin), emptyMap()))
        // Only hrm_interval carries a third field.
        assertEquals(listOf("hrm_interval"), rows(base).filter { it.size > 2 }.map { it[0] })
    }
}
