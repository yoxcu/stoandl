package de.yoxcu.stoandl.pebble

import de.yoxcu.stoandl.config.GUI_CONFIG_FIELDS
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** A config write re-applies only what it changes: each calendar re-sync costs a re-read of every calendar. */
class ServiceForConfigKeyTest {
    private val refresh = listOf("calendar.enabled", "calendar.discover", "calendar.sync_interval")
    private val projected = listOf("calendar.reminders", "calendar.show_declined", "calendar.reminder_vibe")

    @Test
    fun `every calendar key is mapped on purpose`() {
        assertEquals(
            (refresh + projected + "calendar.todos").sorted(),
            GUI_CONFIG_FIELDS.map { it.key }.filter { it.startsWith("calendar.") }.sorted(),
        )
    }

    @Test
    fun `only the keys that change what is read refresh the calendar`() {
        for (key in refresh) {
            assertEquals("calendar", serviceForConfigKey(key), key)
        }
    }

    @Test
    fun `the pin knobs only re-project the libpebble3 config, which re-syncs by itself`() {
        for (key in projected) {
            assertEquals("libpebble", serviceForConfigKey(key), key)
        }
    }

    @Test
    fun `the to-do list is read live by the writer`() {
        assertNull(serviceForConfigKey("calendar.todos"))
    }
}
