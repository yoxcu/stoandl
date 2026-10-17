package de.yoxcu.stoandl.pebble

import io.rebble.libpebblecommon.notification.DefaultVibePattern
import io.rebble.libpebblecommon.packets.blobdb.TimelineIcon
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The per-app style vocabulary. A name the send path can't resolve used to be stored anyway and buzz
 * the default vibe or show the app's icon, while the GUI reported success, so every name a picker
 * offers must survive the store-and-send round trip.
 */
class NotificationStyleTest {

    @Test
    fun builtInVibesResolveByDisplayOrEnumName() {
        assertEquals(listOf(200u, 75u, 200u), perAppVibe("Double"))
        assertEquals(listOf(200u, 75u, 200u), perAppVibe("double"))
        assertEquals(DefaultVibePattern.OhhhOh.pattern.map { it.toUInt() }, perAppVibe("Ohhh, Oh"))
        assertEquals(DefaultVibePattern.Ole.pattern.map { it.toUInt() }, perAppVibe("ole"))
        assertEquals(listOf(100u, 50u, 100u), perAppVibe("100, 50,100"))
    }

    @Test
    fun unknownVibesAreRefusedNotStored() {
        // The GUI's old list offered these; none is a watch vibration.
        for (name in listOf("Subtle", "Heartbeat", "pulse", "100,x")) {
            assertNull(normalizeVibe(name), name)
            assertNull(perAppVibe(name), name)
        }
        assertEquals("Ohhh, Oh", normalizeVibe("ohhhoh"))
        assertEquals("100,50", normalizeVibe(" 100 , 50 "))
    }

    @Test
    fun iconsAndColoursNormalizeToEnumNames() {
        assertEquals("TimelineCalendar", normalizeIcon("timelinecalendar"))
        assertEquals(TimelineIcon.TimelineCalendar.name, normalizeIcon(TimelineIcon.TimelineCalendar.code))
        assertNull(normalizeIcon("Bell"))
        assertEquals("ChromeYellow", normalizeColor("chromeyellow"))
        assertNull(normalizeColor("Mauve"))
    }

    @Test
    fun everyAdvertisedChoiceRoundTrips() {
        val records = notifStyleChoices().map { it.split('\t') }
        val vibes = records.filter { it[0] == "vibe" }
        val icons = records.filter { it[0] == "icon" }
        assertEquals(DefaultVibePattern.entries.size, vibes.size)
        assertEquals(ICON_CHOICES.size, icons.size)
        for ((kind, value, _) in records) {
            when (kind) {
                "vibe" -> {
                    assertEquals(value, normalizeVibe(value))
                    assertEquals(defaultVibeOf(value)!!.pattern.map { it.toUInt() }, perAppVibe(value))
                }
                "icon" -> assertEquals(value, perAppIcon(normalizeIcon(value), "Some App").name)
            }
        }
    }
}
