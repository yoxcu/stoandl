package de.yoxcu.stoandl.pebble

import io.rebble.libpebblecommon.database.entity.HRMonitoringInterval
import io.rebble.libpebblecommon.database.entity.HealthGender
import io.rebble.libpebblecommon.health.HealthSettings
import java.util.Locale

/** The `key \t value` records of `GetHealthProfile`, in display order. */
internal fun healthProfileRecords(s: HealthSettings): List<String> {
    fun b(v: Boolean) = if (v) "on" else "off"
    val interval = when (s.hrmMeasurementInterval) {
        HRMonitoringInterval.TenMin -> "10min"
        HRMonitoringInterval.ThirtyMin -> "30min"
        HRMonitoringInterval.OneHour -> "1h"
        HRMonitoringInterval.Disabled -> "off"
    }
    return listOf(
        "tracking\t${b(s.trackingEnabled)}",
        "activity_insights\t${b(s.activityInsightsEnabled)}",
        "sleep_insights\t${b(s.sleepInsightsEnabled)}",
        "hrm\t${b(s.hrmEnabled)}",
        "hrm_interval\t$interval",
        "hrm_activity\t${b(s.hrmActivityTrackingEnabled)}",
        "units\t${if (s.imperialUnits) "imperial" else "metric"}",
        "height_cm\t${s.heightMm / 10}",
        "weight_kg\t${String.format(Locale.ROOT, "%.1f", s.weightDag / 100.0)}",
        "age\t${s.ageYears}",
        "gender\t${s.gender.name.lowercase()}",
        "resting_hr\t${s.restingHr}",
        "max_hr\t${s.maxHr}",
    )
}

/** [cur] with one `SetHealthProfile` key applied, or an error message for the caller to report. */
internal fun applyHealthProfileKey(cur: HealthSettings, key: String, value: String): Result<HealthSettings> {
    fun err(msg: String): Result<HealthSettings> = Result.failure(IllegalArgumentException(msg))
    fun bool(v: String): Boolean? = when (v.trim().lowercase()) {
        "on", "true", "yes", "1", "enabled" -> true
        "off", "false", "no", "0", "disabled" -> false
        else -> null
    }
    val v = value.trim()
    val onOff = "Expected on/off"
    val next = when (key.trim().lowercase()) {
        "tracking" -> cur.copy(trackingEnabled = bool(v) ?: return err(onOff))
        "activity_insights" -> cur.copy(activityInsightsEnabled = bool(v) ?: return err(onOff))
        "sleep_insights" -> cur.copy(sleepInsightsEnabled = bool(v) ?: return err(onOff))
        "hrm" -> cur.copy(hrmEnabled = bool(v) ?: return err(onOff))
        "hrm_interval" -> cur.copy(hrmMeasurementInterval = when (v.lowercase()) {
            "10min", "10m" -> HRMonitoringInterval.TenMin
            "30min", "30m" -> HRMonitoringInterval.ThirtyMin
            "1h", "60min", "1hr" -> HRMonitoringInterval.OneHour
            "off", "disabled" -> HRMonitoringInterval.Disabled
            else -> return err("Expected 10min/30min/1h/off")
        })
        "hrm_activity" -> cur.copy(hrmActivityTrackingEnabled = bool(v) ?: return err(onOff))
        "units" -> cur.copy(imperialUnits = when (v.lowercase()) {
            "imperial" -> true
            "metric" -> false
            else -> return err("Expected metric/imperial")
        })
        "height_cm", "height" -> cur.copy(heightMm = ((v.toDoubleOrNull()
            ?: return err("Expected a number (cm)")) * 10).toInt().toShort())
        "weight_kg", "weight" -> cur.copy(weightDag = ((v.toDoubleOrNull()
            ?: return err("Expected a number (kg)")) * 100).toInt().toShort())
        "age" -> cur.copy(ageYears = v.toIntOrNull() ?: return err("Expected a whole number (years)"))
        "gender" -> cur.copy(gender = when (v.lowercase()) {
            "female", "f" -> HealthGender.Female
            "male", "m" -> HealthGender.Male
            "other", "o" -> HealthGender.Other
            else -> return err("Expected female/male/other")
        })
        "resting_hr" -> cur.copy(restingHr = (v.toIntOrNull() ?: return err("Expected a number (bpm)")).toShort())
        "max_hr" -> cur.copy(maxHr = (v.toIntOrNull() ?: return err("Expected a number (bpm)")).toShort())
        else -> return err("Unknown health key '$key' (see 'stoandl health profile')")
    }
    return Result.success(next)
}
