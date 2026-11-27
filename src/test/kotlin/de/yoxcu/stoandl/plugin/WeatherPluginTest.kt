package de.yoxcu.stoandl.plugin

import io.rebble.libpebblecommon.database.dao.WatchPreference
import io.rebble.libpebblecommon.database.entity.EnumWatchPref
import io.rebble.libpebblecommon.database.entity.WeatherAppEntry
import io.rebble.libpebblecommon.database.entity.WindUnits
import io.rebble.libpebblecommon.plugin.IconPixelSize
import io.rebble.libpebblecommon.plugin.NumericValueShape
import io.rebble.libpebblecommon.plugin.SeriesShape
import io.rebble.libpebblecommon.plugin.ShortTextShape
import io.rebble.libpebblecommon.plugin.SourceEnvelope
import io.rebble.libpebblecommon.plugin.SourceShapeNames
import io.rebble.libpebblecommon.plugin.TimestampShape
import io.rebble.libpebblecommon.weather.WeatherDailyForecast
import io.rebble.libpebblecommon.weather.WeatherHourlyForecast
import io.rebble.libpebblecommon.weather.WeatherType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/** The built-in Weather plugin on the official `weather/location` contract (F40): scalars, series, units. */
class WeatherPluginTest {
    private val here = Uuid.parse("00000000-0000-0000-0003-000000000001")
    private val there = Uuid.parse("00000000-0000-0000-0003-000000000002")

    /** Local midnight (UTC+0) and three hours and a bit into the day: the hour the forecast was fetched in. */
    private val midnight = 1_699_920_000L
    private val fetched = midnight + 3 * 3600 + 100

    /** What WeatherSync writes today: the v3 fields only. */
    private fun v3(key: Uuid, name: String) = WeatherAppEntry(
        key = key, currentTemp = 10, currentWeatherType = WeatherType.LightRain.code, todayHighTemp = 12,
        todayLowTemp = 5, tomorrowWeatherType = WeatherType.Sun.code, tomorrowHighTemp = 13, tomorrowLowTemp = 6,
        lastUpdateTimeUtcSecs = fetched, isCurrentLocation = false, locationName = name, forecastShort = "Light rain",
    )

    private fun full(key: Uuid, name: String) = v3(key, name).copy(
        locationUtcOffsetMin = 0,
        todayHourly = (0 until 24).map { h ->
            WeatherHourlyForecast(WeatherType.CloudyDay, temp = h.toByte(), uvIndexX10 = (h * 10).toShort())
        },
        tomorrowHourly = listOf(WeatherHourlyForecast(WeatherType.Sun, temp = 20)),
        dailyForecast = listOf(
            WeatherDailyForecast(12, 5, WeatherType.LightRain, humidityPct = 81, sunriseUtcSecs = midnight + 7 * 3600, sunsetUtcSecs = midnight + 16 * 3600),
            WeatherDailyForecast(Short.MAX_VALUE, 6, WeatherType.Sun, sunriseUtcSecs = midnight + 86_400 + 7 * 3600),
        ),
        todayWindSpeed = 10,
        todayWindDirection = 270,
    )

    private fun plugin(
        entries: List<WeatherAppEntry>,
        imperial: Boolean = false,
        prefs: List<WatchPreference<*>> = emptyList(),
    ) = WeatherPlugin(flowOf(entries), flowOf(imperial), flowOf(prefs))

    private fun read(p: WeatherPlugin, properties: List<String>? = null, icons: IconPixelSize? = null): SourceEnvelope =
        runBlocking { p.observe(WeatherPlugin.CATEGORY, WeatherPlugin.ITEM_LOCATION, properties, icons).first() }

    private fun SourceEnvelope.props(i: Int = 0) = instances[i].properties

    private inline fun <reified T> Map<String, JsonElement>.shape(name: String): T = Json.decodeFromJsonElement(assertNotNull(this[name], name))

    private fun series(env: SourceEnvelope, property: String): SeriesShape =
        assertNotNull(env.props()[property], property).shape(SourceShapeNames.SERIES)

    @Test
    fun `what WeatherSync writes today reads as before, one instance per location in its order`() {
        val env = read(plugin(listOf(v3(here, "Here"), v3(there, "There"))))
        assertEquals(listOf(here.toString(), there.toString()), env.instances.map { it.instanceId })
        assertEquals(WeatherPlugin.BUILT_IN_WEATHER_UUID.toString(), env.pluginUuid)
        val p = env.props()
        assertEquals(
            setOf("name", "temperature", "feels_like", "high", "low", "condition", "condition_code"),
            p.keys,
            "nothing the forecast didn't carry: no series, UV, precipitation, wind, humidity or sun",
        )
        assertEquals("10°C", p.getValue("temperature").shape<ShortTextShape>(SourceShapeNames.SHORT_TEXT).text)
        assertEquals("10°C", p.getValue("feels_like").shape<ShortTextShape>(SourceShapeNames.SHORT_TEXT).text, "feels like falls back to now")
        assertEquals("Light rain", p.getValue("condition").shape<ShortTextShape>(SourceShapeNames.SHORT_TEXT).text)
        assertNull(p.getValue("condition")[SourceShapeNames.ICON], "no glyph unless asked for")
        val f = read(plugin(listOf(v3(here, "Here")), imperial = true)).props()
        assertEquals("°F", f.getValue("high").shape<NumericValueShape>(SourceShapeNames.NUMERIC_VALUE).unit,
            "labelled in the unit WeatherSync fetched in")
    }

    @Test
    fun `weather hour is gone, as upstream removed it`() {
        val p = plugin(listOf(full(here, "Here")))
        assertFalse(p.serves(WeatherPlugin.CATEGORY, "hour"))
        assertTrue(runBlocking { p.observe(WeatherPlugin.CATEGORY, "hour").toList() }.isEmpty())
        assertEquals(listOf(listOf("location")), p.sources.map { it.items })
    }

    @Test
    fun `hourly and daily series start at local midnight on the day it was fetched`() {
        val env = read(plugin(listOf(full(here, "Here"))))
        val hourly = series(env, WeatherPlugin.PROPERTY_HOURLY_TEMPERATURE)
        assertEquals(midnight, hourly.start)
        assertEquals(3600L, hourly.stepSec)
        assertEquals((0 until 24).map { it.toDouble() } + 20.0, hourly.numericValue?.values, "today's hours, then tomorrow's")
        assertEquals("°C", hourly.numericValue?.unit)
        assertEquals("cloudy", series(env, WeatherPlugin.PROPERTY_HOURLY_CONDITION_CODE).longText?.values?.first())
        val highs = series(env, WeatherPlugin.PROPERTY_DAILY_HIGH)
        assertEquals(86_400L, highs.stepSec)
        assertEquals(listOf(12.0, null), highs.numericValue?.values, "a day without a high is a gap, not 32767")
        assertEquals(listOf(midnight + 7 * 3600, midnight + 86_400 + 7 * 3600), series(env, WeatherPlugin.PROPERTY_DAILY_SUNRISE).timestamp?.values)
        assertEquals(listOf("Light rain", "Sunny"), series(env, WeatherPlugin.PROPERTY_DAILY_CONDITION).shortText?.values)
        val p = env.props()
        assertNull(p[WeatherPlugin.PROPERTY_HOURLY_PRECIPITATION], "no hour had a precipitation reading")
        assertNull(p[WeatherPlugin.PROPERTY_HOURLY_PRECIPITATION_AMOUNT])
        assertNull(p[WeatherPlugin.PROPERTY_DAILY_PRECIPITATION], "no day had one either")
    }

    @Test
    fun `today's scalars come from the forecast when it has them`() {
        val p = read(plugin(listOf(full(here, "Here")))).props()
        assertEquals(3.0, p.getValue(WeatherPlugin.PROPERTY_UV_INDEX).shape<NumericValueShape>(SourceShapeNames.NUMERIC_VALUE).value,
            "the UV of the hour it was fetched in, not the day's peak")
        assertEquals(81.0, p.getValue(WeatherPlugin.PROPERTY_HUMIDITY).shape<NumericValueShape>(SourceShapeNames.NUMERIC_VALUE).value)
        assertEquals(0.0, p.getValue(WeatherPlugin.PROPERTY_UTC_OFFSET).shape<NumericValueShape>(SourceShapeNames.NUMERIC_VALUE).value)
        assertEquals(midnight + 7 * 3600, p.getValue(WeatherPlugin.PROPERTY_SUNRISE).shape<TimestampShape>(SourceShapeNames.TIMESTAMP).value)
        assertEquals(midnight + 16 * 3600, p.getValue(WeatherPlugin.PROPERTY_SUNSET).shape<TimestampShape>(SourceShapeNames.TIMESTAMP).value)
        assertEquals(270.0, p.getValue(WeatherPlugin.PROPERTY_WIND_DIRECTION).shape<NumericValueShape>(SourceShapeNames.NUMERIC_VALUE).value)
    }

    @Test
    fun `wind speed is mph as a number and the watch's unit as text`() {
        fun windText(imperial: Boolean, prefs: List<WatchPreference<*>> = emptyList()) =
            read(plugin(listOf(full(here, "Here")), imperial, prefs)).props()
                .getValue(WeatherPlugin.PROPERTY_WIND_SPEED).shape<ShortTextShape>(SourceShapeNames.SHORT_TEXT).text
        assertEquals("16 km/h", windText(imperial = false), "automatic follows metric distance")
        assertEquals("10 mph", windText(imperial = true))
        assertEquals("9 knots", windText(imperial = false, listOf(WatchPreference(EnumWatchPref.WindSpeed, WindUnits.Knots))))
        val number = read(plugin(listOf(full(here, "Here")))).props()
            .getValue(WeatherPlugin.PROPERTY_WIND_SPEED).shape<NumericValueShape>(SourceShapeNames.NUMERIC_VALUE)
        assertEquals(10.0, number.value)
        assertEquals("mph", number.unit)
    }

    @Test
    fun `series are built only when asked for, glyphs only at a size`() {
        val only = read(plugin(listOf(full(here, "Here"))), properties = listOf(WeatherPlugin.PROPERTY_HOURLY_CONDITION)).props()
        assertNotNull(only[WeatherPlugin.PROPERTY_HOURLY_CONDITION])
        assertNull(only[WeatherPlugin.PROPERTY_HOURLY_TEMPERATURE], "a series nobody asked for isn't built")
        assertNotNull(only["temperature"], "scalars are cheap and always there")
        val plain = series(read(plugin(listOf(full(here, "Here")))), WeatherPlugin.PROPERTY_HOURLY_CONDITION)
        assertNull(plain.icon)
        val drawn = series(read(plugin(listOf(full(here, "Here"))), icons = IconPixelSize(25, 25)), WeatherPlugin.PROPERTY_HOURLY_CONDITION)
        assertEquals(25, drawn.icon?.values?.size)
        assertEquals(25, drawn.image?.values?.size)
    }
}
