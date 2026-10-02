package de.yoxcu.stoandl.power

import io.rebble.libpebblecommon.database.dao.WatchPreference
import io.rebble.libpebblecommon.database.entity.AlertMask
import io.rebble.libpebblecommon.database.entity.BoolWatchPref
import io.rebble.libpebblecommon.database.entity.EnumWatchPref
import io.rebble.libpebblecommon.database.entity.QuietTimeSchedule
import io.rebble.libpebblecommon.database.entity.ScheduleWatchPref
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class QuietTimeScheduleTest {
    // 2026-10-02 is a Friday.
    private fun at(day: Int, hour: Int, minute: Int = 0) = LocalDateTime.of(2026, 10, day, hour, minute)
    private val friday = 2
    private val saturday = 3
    private val monday = 5

    private val night = QuietTimeSchedule(22, 0, 7, 0)
    private val lateMorning = QuietTimeSchedule(0, 0, 9, 30)

    private fun prefs(
        weekday: QuietTimeSchedule? = null,
        weekend: QuietTimeSchedule? = null,
        mask: AlertMask? = null,
    ) = buildList<WatchPreference<*>> {
        add(WatchPreference(BoolWatchPref.QuietTimeWeekdayScheduleEnabled, weekday != null))
        add(WatchPreference(BoolWatchPref.QuietTimeWeekendScheduleEnabled, weekend != null))
        weekday?.let { add(WatchPreference(ScheduleWatchPref.QuietTimeWeekdaySchedule, it)) }
        weekend?.let { add(WatchPreference(ScheduleWatchPref.QuietTimeWeekendSchedule, it)) }
        mask?.let { add(WatchPreference(EnumWatchPref.QuietTimeInterruptions, it)) }
    }

    @Test
    fun `an overnight window covers both sides of midnight`() {
        val p = prefs(weekday = night)
        assertEquals(night, scheduledQuietTime(p, at(monday, 23)))
        assertEquals(night, scheduledQuietTime(p, at(monday + 1, 6, 59)))
        assertNull(scheduledQuietTime(p, at(monday + 1, 7, 0)))
        assertNull(scheduledQuietTime(p, at(monday, 21, 59)))
    }

    @Test
    fun `a same-day window ends exclusively`() {
        val p = prefs(weekday = lateMorning)
        assertEquals(lateMorning, scheduledQuietTime(p, at(monday, 0, 0)))
        assertEquals(lateMorning, scheduledQuietTime(p, at(monday, 9, 29)))
        assertNull(scheduledQuietTime(p, at(monday, 9, 30)))
    }

    @Test
    fun `start equal to end is never`() {
        assertNull(scheduledQuietTime(prefs(weekday = QuietTimeSchedule(22, 0, 22, 0)), at(monday, 22, 0)))
    }

    @Test
    fun `the day's own schedule decides, like the watch`() {
        // Friday 22:00 starts the weekday window; after midnight it is Saturday, whose schedule is off.
        val weekdayOnly = prefs(weekday = night)
        assertEquals(night, scheduledQuietTime(weekdayOnly, at(friday, 23)))
        assertNull(scheduledQuietTime(weekdayOnly, at(saturday, 1)))
        // With a weekend schedule, Saturday 01:00 uses it.
        val both = prefs(weekday = night, weekend = lateMorning)
        assertEquals(lateMorning, scheduledQuietTime(both, at(saturday, 1)))
    }

    @Test
    fun `a disabled schedule is never active, and the default schedule applies when only enabled`() {
        assertNull(scheduledQuietTime(prefs(), at(monday, 3)))
        val enabledDefault = listOf<WatchPreference<*>>(WatchPreference(BoolWatchPref.QuietTimeWeekdayScheduleEnabled, true))
        // libpebble3's default weekday schedule is 00:00–06:00.
        assertEquals(ScheduleWatchPref.QuietTimeWeekdaySchedule.defaultValue, scheduledQuietTime(enabledDefault, at(monday, 3)))
    }

    @Test
    fun `calls interrupt only with a mask that includes phone calls`() {
        assertFalse(callsMayInterrupt(prefs()))
        assertFalse(callsMayInterrupt(prefs(mask = AlertMask.AllOff)))
        assertTrue(callsMayInterrupt(prefs(mask = AlertMask.PhoneCalls)))
        assertTrue(callsMayInterrupt(prefs(mask = AlertMask.AllOn)))
    }
}
