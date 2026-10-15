@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package de.yoxcu.stoandl.weather

import io.rebble.libpebblecommon.database.entity.TimelinePin
import io.rebble.libpebblecommon.weather.WeatherLocationData
import io.rebble.libpebblecommon.weather.WeatherType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant
import kotlin.uuid.Uuid

class WeatherPinsTest {
    private val inserted = mutableListOf<TimelinePin>()
    private val deleted = mutableListOf<Uuid>()
    private val pins = WeatherPins({ inserted += it }, { deleted += it })

    private fun day(i: Int, high: Int) = DayForecast(
        Instant.fromEpochSeconds(1_792_000_000L + i * 86_400L), Instant.fromEpochSeconds(1_792_040_000L + i * 86_400L),
        DayPart(high, WeatherType.Sun, "Sunny"), DayPart(high - 10, WeatherType.Sun, "Clear"),
    )

    private fun forecast(vararg highs: Int) = LocationForecast(
        WeatherLocationData.WeatherLocationDataFailed(Uuid.random()), "Here", true, highs.mapIndexed { i, h -> day(i, h) },
    )

    private val failed = LocationForecast(WeatherLocationData.WeatherLocationDataFailed(Uuid.random()), "Here", true, emptyList())

    @Test
    fun `an unchanged forecast writes nothing to the watch`() {
        pins.sync(listOf(forecast(20, 21, 22)))
        assertEquals(6, inserted.size)
        pins.sync(listOf(forecast(20, 21, 22)))
        assertEquals(6, inserted.size)
        // Tomorrow's high changes: only its sunrise and sunset pins are rewritten.
        pins.sync(listOf(forecast(20, 25, 22)))
        assertEquals(8, inserted.size)
        assertEquals(0, deleted.size)
    }

    @Test
    fun `a failed fetch keeps the pins`() {
        pins.sync(listOf(forecast(20, 21, 22)))
        pins.sync(listOf(failed))
        assertEquals(0, deleted.size)
        // The retry brings the same forecast: nothing to resend either.
        pins.sync(listOf(forecast(20, 21, 22)))
        assertEquals(6, inserted.size)
    }

    @Test
    fun `a day without forecast is removed once`() {
        pins.sync(listOf(forecast(20, 21, 22)))
        pins.sync(listOf(forecast(20, 21)))
        assertEquals(2, deleted.size)
        pins.sync(listOf(forecast(20, 21)))
        assertEquals(2, deleted.size)
    }
}
