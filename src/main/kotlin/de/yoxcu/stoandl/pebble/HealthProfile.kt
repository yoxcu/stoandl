package de.yoxcu.stoandl.pebble

import io.rebble.libpebblecommon.database.entity.HRMonitoringInterval
import io.rebble.libpebblecommon.database.entity.HealthGender
import io.rebble.libpebblecommon.database.entity.currentYear
import io.rebble.libpebblecommon.health.HealthSettings
import java.util.Locale

/** The `hrm_interval` spelling of each interval, in display order. A code the watch sent that libpebble3
 *  can't name never reaches this table: libpebble3 keeps it verbatim on the wire and shows the last named
 *  interval here (the fork's `HrmPreferencesValue.unknownMeasurementInterval`). */
internal val HRM_INTERVAL_NAMES = listOf(
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
 * [healthProfileRefusal]), so a client offers only those. `birth_year` is [HealthSettings.birthYear], or
 * the one [age][HealthSettings.ageYears] implies in [year] while libpebble3 hasn't stored one yet.
 * `max_hr` is derived (see [applyHealthProfileKey]).
 */
internal fun healthProfileRecords(
    s: HealthSettings,
    connected: Map<String, String>,
    year: Int = currentYear(),
): List<String> {
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
        "birth_year\t${s.birthYear ?: (year - s.ageYears)}",
        "gender\t${s.gender.name.lowercase()}",
        "resting_hr\t${s.restingHr}",
        "max_hr\t${s.maxHr}",
    )
}

/**
 * [cur] with one `SetHealthProfile` key applied, or an error message for the caller to report.
 *
 * The profile's age is libpebble3's birth year: it re-derives the age from [HealthSettings.birthYear] on
 * every write (upstream 6a1b3357), so `age` and `birth_year` both set the two together, in [year]. The
 * maximum heart rate (and the zones) follow from the age, 220 − age but at least the elevated heart rate
 * (libpebble3's `HeartRatePreferencesValue.forAge`), and libpebble3 writes that whatever
 * it is given, so `max_hr` is read-only: its current value is taken as a no-op (a record round-trips), any
 * other is refused instead of reported as set.
 */
internal fun applyHealthProfileKey(
    cur: HealthSettings,
    key: String,
    value: String,
    year: Int = currentYear(),
): Result<HealthSettings> {
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
        "age" -> (v.toIntOrNull()?.takeIf { it in 1..120 } ?: return err("Expected a whole number of years (1-120)"))
            .let { age -> cur.copy(ageYears = age, birthYear = year - age) }
        "birth_year" -> (v.toIntOrNull()?.takeIf { it in (year - 120)..(year - 1) }
            ?: return err("Expected a year from ${year - 120} to ${year - 1}"))
            .let { born -> cur.copy(birthYear = born, ageYears = year - born) }
        "gender" -> cur.copy(gender = when (v.lowercase()) {
            "female", "f" -> HealthGender.Female
            "male", "m" -> HealthGender.Male
            "other", "o" -> HealthGender.Other
            else -> return err("Expected female/male/other")
        })
        "resting_hr" -> cur.copy(restingHr = (v.toIntOrNull() ?: return err("Expected a number (bpm)")).toShort())
        "max_hr" -> {
            val bpm = v.toIntOrNull() ?: return err("Expected a number (bpm)")
            if (bpm != cur.maxHr.toInt()) {
                // libpebble3's HeartRatePreferencesValue.forAge: 220 - age, at least the elevated heart rate.
                return err("max_hr follows from the age (220 - age, at least the elevated heart rate of " +
                    "${cur.elevatedHr} bpm; now ${cur.maxHr} bpm) and the watch gets that value whatever is set; " +
                    "change age or birth_year instead")
            }
            cur
        }
        else -> return err("Unknown health key '$key' (see 'stoandl health profile')")
    }
    return Result.success(next)
}

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
    // libpebble3's own rule for the interval (HRMonitoringInterval.isSupportedBy), not a copy of it.
    val old = connected.entries.firstOrNull { (_, fw) ->
        WatchPrefsControl.firmwareOf(fw)?.let(next::isSupportedBy) != true
    } ?: return null
    return "${old.key} runs firmware ${old.value}; the 5-minute interval needs 4.39.0 or newer (update the firmware, or pick 10min)"
}
