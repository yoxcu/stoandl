package de.yoxcu.stoandl.pebble

import de.yoxcu.stoandl.firmware.FirmwareTag
import io.rebble.libpebblecommon.database.entity.HRMonitoringInterval
import io.rebble.libpebblecommon.database.entity.HealthGender
import io.rebble.libpebblecommon.health.HealthSettings
import java.util.Locale

/** The `hrm_interval` spelling of each interval, in display order. */
private val HRM_INTERVAL_NAMES = listOf(
    HRMonitoringInterval.FiveMin to "5min",
    HRMonitoringInterval.TenMin to "10min",
    HRMonitoringInterval.ThirtyMin to "30min",
    HRMonitoringInterval.OneHour to "1h",
    HRMonitoringInterval.Disabled to "off",
)

private fun HRMonitoringInterval.profileName() = HRM_INTERVAL_NAMES.first { it.first == this }.second

/**
 * The `key \t value` records of `GetHealthProfile`, in display order. The `hrm_interval` record has a
 * third field: the intervals `SetHealthProfile` accepts right now, `|`-joined ([connected] as for
 * [healthProfileRefusal]), so a client offers only those.
 */
internal fun healthProfileRecords(s: HealthSettings, connected: Map<String, String>): List<String> {
    fun b(v: Boolean) = if (v) "on" else "off"
    val choices = HRM_INTERVAL_NAMES
        .filter { (interval, _) -> intervalRefusal(s.hrmMeasurementInterval, interval, connected) == null }
        .joinToString("|") { it.second }
    return listOf(
        "tracking\t${b(s.trackingEnabled)}",
        "activity_insights\t${b(s.activityInsightsEnabled)}",
        "sleep_insights\t${b(s.sleepInsightsEnabled)}",
        "hrm\t${b(s.hrmEnabled)}",
        "hrm_interval\t${s.hrmMeasurementInterval.profileName()}\t$choices",
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
            "5min", "5m" -> HRMonitoringInterval.FiveMin
            "10min", "10m" -> HRMonitoringInterval.TenMin
            "30min", "30m" -> HRMonitoringInterval.ThirtyMin
            "1h", "60min", "1hr" -> HRMonitoringInterval.OneHour
            "off", "disabled" -> HRMonitoringInterval.Disabled
            else -> return err("Expected 5min/10min/30min/1h/off")
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

/** The firmware that added the 5-minute heart-rate interval (PebbleOS 2edd1c78c). */
private val FW_HRM_FIVE_MIN = FirmwareTag(listOf(4, 39, 0, 0), null)

/**
 * Why [next] must not go to the watch, or null when it may. [connected] maps each connected watch's
 * display name to its running firmware tag. Keeping 5 minutes (the watch chose it) while another key
 * changes is fine.
 */
internal fun healthProfileRefusal(cur: HealthSettings, next: HealthSettings, connected: Map<String, String>): String? =
    intervalRefusal(cur.hrmMeasurementInterval, next.hrmMeasurementInterval, connected)

/**
 * Why a switch from [current] to [next] must not go to the watch, or null when it may. The 5-minute
 * interval needs firmware 4.39.0 on every connected watch; older firmware treats the code as invalid
 * and shows and measures 10 minutes. With no watch connected the version can't be checked, so the
 * switch waits for one.
 */
private fun intervalRefusal(
    current: HRMonitoringInterval,
    next: HRMonitoringInterval,
    connected: Map<String, String>,
): String? {
    if (next != HRMonitoringInterval.FiveMin || current == HRMonitoringInterval.FiveMin) return null
    if (connected.isEmpty()) {
        return "Connect the watch first: 5min needs firmware 4.39.0 or newer, and stoandl checks the watch's version before sending it"
    }
    val old = connected.entries.firstOrNull { (_, fw) ->
        val tag = FirmwareTag.parse(fw)
        tag == null || tag.compareNumbers(FW_HRM_FIVE_MIN) < 0
    } ?: return null
    return "${old.key} runs firmware ${old.value}; the 5-minute interval needs 4.39.0 or newer (update the firmware, or pick 10min)"
}
