package de.yoxcu.stoandl

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WeatherSearchArgsTest {
    @Test
    fun `the query is every word that is not an option`() {
        assertEquals(WeatherSearchArgs("New York", null, null), parseWeatherSearchArgs(listOf("New", "York")))
        assertEquals(WeatherSearchArgs("Paris", 1, null), parseWeatherSearchArgs(listOf("Paris", "--add")))
        assertEquals(WeatherSearchArgs("Paris", 3, "Work"), parseWeatherSearchArgs(listOf("--add", "3", "Paris", "--as", "Work")))
    }

    @Test
    fun `a missing query or a malformed option is a usage error`() {
        assertNull(parseWeatherSearchArgs(emptyList()))
        assertNull(parseWeatherSearchArgs(listOf("--add", "2")))
        assertNull(parseWeatherSearchArgs(listOf("Paris", "--add", "0")))
        assertNull(parseWeatherSearchArgs(listOf("Paris", "--as")))
    }
}
