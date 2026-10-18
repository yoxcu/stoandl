package de.yoxcu.stoandl.pebble

import io.rebble.libpebblecommon.database.entity.HRMonitoringInterval
import io.rebble.libpebblecommon.database.entity.HealthGender
import io.rebble.libpebblecommon.health.HealthSettings
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** `GetHealthProfile` records and `SetHealthProfile` key parsing. */
class HealthProfileTest {
    private val base = HealthSettings(
        heightMm = 1780, weightDag = 7450, trackingEnabled = true, activityInsightsEnabled = false,
        sleepInsightsEnabled = false, ageYears = 31, gender = HealthGender.Other, imperialUnits = false,
        hrmEnabled = true, hrmMeasurementInterval = HRMonitoringInterval.TenMin, hrmActivityTrackingEnabled = false,
        restingHr = 58, elevatedHr = 100, maxHr = 189, hrZone1Threshold = 130, hrZone2Threshold = 150,
        hrZone3Threshold = 170,
    )

    private fun records(s: HealthSettings) = healthProfileRecords(s).associate { it.substringBefore('\t') to it.substringAfter('\t') }

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
                "height_cm", "weight_kg", "age", "gender", "resting_hr", "max_hr"),
            healthProfileRecords(base).map { it.substringBefore('\t') },
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
        healthProfileRecords(base).forEach { rec ->
            val (k, v) = rec.split('\t')
            assertEquals(base, applyHealthProfileKey(base, k, v).getOrThrow(), k)
        }
    }

    @Test
    fun `bad values and unknown keys explain themselves`() {
        assertContains(applyHealthProfileKey(base, "hrm_activity", "maybe").exceptionOrNull()!!.message!!, "on/off")
        assertContains(applyHealthProfileKey(base, "hrm_interval", "5min").exceptionOrNull()!!.message!!, "10min")
        assertContains(applyHealthProfileKey(base, "shoe_size", "44").exceptionOrNull()!!.message!!, "Unknown health key")
    }
}
