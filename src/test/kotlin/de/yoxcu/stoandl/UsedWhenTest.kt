package de.yoxcu.stoandl

import kotlin.test.Test
import kotlin.test.assertEquals

/** S13: `stoandl daemon get` says when a key matters, from the schema's requires column. */
class UsedWhenTest {
    private val labels = mapOf("weather.enabled" to "Weather on the watch", "tts.backend" to "Text to speech")

    @Test
    fun `a toggle reads on and off, alternatives join with or`() {
        assertEquals("Weather on the watch is on", usedWhen("weather.enabled=true", labels::get))
        assertEquals(
            "Text to speech isn't Off or Weather on the watch is off",
            usedWhen("tts.backend!=Off|weather.enabled=false", labels::get),
        )
    }

    @Test
    fun `an unknown key is named by itself`() {
        assertEquals("firmware.github is on", usedWhen("firmware.github=true", labels::get))
    }
}
