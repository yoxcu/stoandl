package de.yoxcu.stoandl.pebble

import de.yoxcu.stoandl.config.StoandlConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** stoandl.conf → libpebble3's config: the fixed transport fields survive any config, the rest follow it. */
class LibPebbleConfigProjectionTest {
    private val base = StoandlConfig.load(java.io.File("/nonexistent/stoandl.conf"), logResult = false)

    @Test
    fun `forward PPoG and the LAN developer connection are fixed`() {
        val lp = libPebbleConfigOf(base.copy(calendarReminders = false, notificationCatchUpMinutes = 5))
        assertFalse(lp.bleConfig.legacyReversedPPoG)
        assertFalse(lp.bleConfig.useReversedPpogV2)
        assertTrue(lp.watchConfig.lanDevConnection)
        assertEquals(5 * 60_000L, lp.notificationConfig.missedNotificationCatchUpMs)
    }

    @Test
    fun `calendar knobs follow the config`() {
        val lp = libPebbleConfigOf(base.copy(calendarReminders = false, calendarShowDeclined = true, calendarReminderVibe = "pulses"))
        assertFalse(lp.watchConfig.calendarReminders)
        assertTrue(lp.watchConfig.calendarShowDeclinedEvents)
        assertEquals("Pulses", lp.watchConfig.overrideCalendarVibePattern)
        assertNull(libPebbleConfigOf(base).watchConfig.overrideCalendarVibePattern)
    }

    @Test
    fun `face order (off by default) and podcast seek (on by default) follow the config`() {
        assertFalse(libPebbleConfigOf(base).watchConfig.orderWatchfacesByLastUsed)
        assertTrue(libPebbleConfigOf(base).watchConfig.musicSeekWhenAvailable)
        val flipped = libPebbleConfigOf(base.copy(appsOrderFacesByLastUsed = true, musicSeekWhenAvailable = false))
        assertTrue(flipped.watchConfig.orderWatchfacesByLastUsed)
        assertFalse(flipped.watchConfig.musicSeekWhenAvailable)
    }

    @Test
    fun `vibe names take libpebble3's spelling, custom names pass through`() {
        assertEquals("Olé", vibePatternName("olé"))
        assertEquals("Ohhh, Oh", vibePatternName(" ohhh, oh "))
        assertEquals("Ohhh, Oh", vibePatternName("OhhhOh"))
        assertEquals("my buzz", vibePatternName("my buzz"))
    }
}
