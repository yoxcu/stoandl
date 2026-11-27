// Ported from the official Pebble app (coredevices/libpebble3 pebble/…/weather/WeatherPlugin.kt, GPLv3, at
// de47e36f): stoandl has no saved-locations table, so instances come in the order WeatherSync writes them,
// and temperatures are labelled in the unit WeatherSync fetched them in.
package de.yoxcu.stoandl.plugin

import io.rebble.libpebblecommon.connection.LibPebble
import io.rebble.libpebblecommon.database.dao.WatchPreference
import io.rebble.libpebblecommon.database.entity.EnumWatchPref
import io.rebble.libpebblecommon.database.entity.WeatherAppEntry
import io.rebble.libpebblecommon.database.entity.WindUnits
import io.rebble.libpebblecommon.imaging.EncodedImage
import io.rebble.libpebblecommon.plugin.BitmapSeries
import io.rebble.libpebblecommon.plugin.IconPixelSize
import io.rebble.libpebblecommon.plugin.IconShape
import io.rebble.libpebblecommon.plugin.ImageShape
import io.rebble.libpebblecommon.plugin.LongTextShape
import io.rebble.libpebblecommon.plugin.NativePlugin
import io.rebble.libpebblecommon.plugin.NumericSeries
import io.rebble.libpebblecommon.plugin.NumericValueShape
import io.rebble.libpebblecommon.plugin.PluginPermission
import io.rebble.libpebblecommon.plugin.PluginPermissions
import io.rebble.libpebblecommon.plugin.SeriesShape
import io.rebble.libpebblecommon.plugin.ShortTextShape
import io.rebble.libpebblecommon.plugin.SourceDeclaration
import io.rebble.libpebblecommon.plugin.SourceEnvelope
import io.rebble.libpebblecommon.plugin.SourceInstance
import io.rebble.libpebblecommon.plugin.SourceShapeNames
import io.rebble.libpebblecommon.plugin.TextSeries
import io.rebble.libpebblecommon.plugin.TimestampSeries
import io.rebble.libpebblecommon.plugin.TimestampShape
import io.rebble.libpebblecommon.weather.WeatherType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map
import kotlinx.datetime.TimeZone
import kotlinx.datetime.offsetAt
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.math.roundToInt
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * The condition as one of a fixed set of names — the vocabulary `condition_code` publishes and
 * the built-in glyphs are keyed by. Anything this doesn't know about is `unknown` rather than
 * a name a consumer has no artwork for.
 */
internal fun conditionCodeOf(code: Byte) = when (WeatherType.entries.firstOrNull { it.code == code }) {
    WeatherType.Sun -> WeatherPlugin.ICON_SUN
    WeatherType.PartlyCloudy -> WeatherPlugin.ICON_PARTLY_CLOUDY
    WeatherType.CloudyDay -> WeatherPlugin.ICON_CLOUDY
    WeatherType.LightRain -> WeatherPlugin.ICON_LIGHT_RAIN
    WeatherType.HeavyRain -> WeatherPlugin.ICON_HEAVY_RAIN
    WeatherType.LightSnow -> WeatherPlugin.ICON_LIGHT_SNOW
    WeatherType.HeavySnow -> WeatherPlugin.ICON_HEAVY_SNOW
    WeatherType.RainAndSnow -> WeatherPlugin.ICON_RAIN_AND_SNOW
    else -> WeatherPlugin.ICON_UNKNOWN
}

/**
 * Built-in weather plugin, on the official app's contract: one `weather/location` instance per weather
 * location in WeatherSync's order (GPS location first, then `weather.locations`), carrying today's scalars and
 * the hourly and daily series. It reads whatever WeatherSync last wrote to the weather blobdb — no fetching of
 * its own, so subscribing never triggers network traffic; a property the forecast didn't carry is left off.
 *
 * [imperialUnits] is the unit the temperatures were fetched in (WeatherSync follows the watch's distance
 * units), so the labels always match the numbers; [watchPrefs] gives the wind unit the watch shows.
 */
class WeatherPlugin internal constructor(
    private val entries: Flow<List<WeatherAppEntry>>,
    private val imperialUnits: Flow<Boolean>,
    private val watchPrefs: Flow<List<WatchPreference<*>>>,
) : NativePlugin {

    constructor(libPebble: LibPebble) : this(
        libPebble.currentWeather,
        libPebble.healthSettings.map { it.imperialUnits },
        libPebble.watchPrefs,
    )

    override val pluginUuid: Uuid = BUILT_IN_WEATHER_UUID
    override val name: String = "Weather"
    override val description: String = "Current conditions and forecast for the user's saved weather locations."

    override val sources: List<SourceDeclaration> = listOf(
        SourceDeclaration(
            category = CATEGORY,
            items = listOf(ITEM_LOCATION),
            properties = mapOf(
                SourceInstance.PROPERTY_NAME to listOf(SourceShapeNames.SHORT_TEXT),
                PROPERTY_TEMPERATURE to TEMPERATURE_SHAPES,
                PROPERTY_FEELS_LIKE to TEMPERATURE_SHAPES,
                PROPERTY_HIGH to TEMPERATURE_SHAPES,
                PROPERTY_LOW to TEMPERATURE_SHAPES,
                PROPERTY_CONDITION to TEXT_SHAPES + CONDITION_ICON_SHAPES,
                PROPERTY_CONDITION_CODE to listOf(SourceShapeNames.LONG_TEXT),
                PROPERTY_UV_INDEX to listOf(SourceShapeNames.NUMERIC_VALUE, SourceShapeNames.SHORT_TEXT),
                PROPERTY_PRECIPITATION to listOf(SourceShapeNames.NUMERIC_VALUE, SourceShapeNames.SHORT_TEXT),
                PROPERTY_HOURLY_TEMPERATURE to NUMERIC_SERIES_SHAPES,
                PROPERTY_HOURLY_CONDITION to CONDITION_SERIES_SHAPES,
                PROPERTY_HOURLY_CONDITION_CODE to CODE_SERIES_SHAPES,
                PROPERTY_HOURLY_PRECIPITATION to NUMERIC_SERIES_SHAPES,
                PROPERTY_HOURLY_PRECIPITATION_AMOUNT to NUMERIC_SERIES_SHAPES,
                PROPERTY_DAILY_HIGH to NUMERIC_SERIES_SHAPES,
                PROPERTY_DAILY_LOW to NUMERIC_SERIES_SHAPES,
                PROPERTY_DAILY_CONDITION to CONDITION_SERIES_SHAPES,
                PROPERTY_DAILY_CONDITION_CODE to CODE_SERIES_SHAPES,
                PROPERTY_DAILY_PRECIPITATION to NUMERIC_SERIES_SHAPES,
                PROPERTY_DAILY_SUNRISE to TIMESTAMP_SERIES_SHAPES,
                PROPERTY_DAILY_SUNSET to TIMESTAMP_SERIES_SHAPES,
                PROPERTY_WIND_SPEED to listOf(SourceShapeNames.NUMERIC_VALUE, SourceShapeNames.SHORT_TEXT),
                PROPERTY_WIND_DIRECTION to listOf(SourceShapeNames.NUMERIC_VALUE),
                PROPERTY_HUMIDITY to listOf(SourceShapeNames.NUMERIC_VALUE, SourceShapeNames.SHORT_TEXT),
                PROPERTY_UTC_OFFSET to listOf(SourceShapeNames.NUMERIC_VALUE),
                PROPERTY_SUNRISE to listOf(SourceShapeNames.TIMESTAMP),
                PROPERTY_SUNSET to listOf(SourceShapeNames.TIMESTAMP),
            ),
            supportsMultiple = true,
            callerPermissions = listOf(PluginPermission(PluginPermissions.LOCATION)),
            suggestedRefreshIntervalSec = 900,
        ),
    )

    // No default cap to lift: every location is an instance, and libpebble3 drops any past [limit].
    override fun observe(
        category: String,
        item: String,
        properties: List<String>?,
        iconPixelSize: IconPixelSize?,
        limit: Int?,
    ): Flow<SourceEnvelope> {
        if (!serves(category, item)) return emptyFlow()
        val wanted = { property: String -> properties == null || property in properties }
        return combine(entries, imperialUnits, watchPrefs) { ordered, imperial, prefs ->
            val unit = if (imperial) "°F" else "°C"
            val wind = (prefs.firstOrNull { it.pref == EnumWatchPref.WindSpeed }?.valueOrDefault() as? WindUnits
                ?: WindUnits.FromDistance).resolved(imperial)
            SourceEnvelope(
                pluginUuid = pluginUuid.toString(),
                validUntilMs = null,
                instances = ordered.map { entry -> toInstance(entry, iconPixelSize, unit, wind, wanted) },
            )
        }
    }

    private fun toInstance(
        entry: WeatherAppEntry,
        iconSize: IconPixelSize?,
        unit: String,
        wind: WindUnits,
        wanted: (String) -> Boolean,
    ): SourceInstance = SourceInstance(
        instanceId = entry.key.toString(),
        properties = mapOf(
            SourceInstance.PROPERTY_NAME to mapOf(
                SourceShapeNames.SHORT_TEXT to encode(ShortTextShape(entry.locationName)),
            ),
            PROPERTY_TEMPERATURE to tempShapes(entry.currentTemp, unit),
            PROPERTY_FEELS_LIKE to tempShapes(entry.todayFeelsLikeTemp ?: entry.currentTemp, unit),
            PROPERTY_HIGH to tempShapes(entry.todayHighTemp, unit),
            PROPERTY_LOW to tempShapes(entry.todayLowTemp, unit),
            PROPERTY_CONDITION to conditionShapes(entry.currentWeatherType, entry.forecastShort, iconSize),
            PROPERTY_CONDITION_CODE to conditionCode(entry.currentWeatherType),
        ) + uvShapes(entry) + precipitationShapes(entry) + todayShapes(entry, wind) +
            seriesShapes(entry, iconSize, unit, wanted),
    )

    /**
     * Today's and tomorrow's hours, and the days ahead, from local midnight on the day the forecast
     * was fetched: `start` keeps them true however long ago that was. Built only when asked for,
     * since a series of glyphs is the most expensive thing an emission can carry. A series whose
     * readings the forecast had none of is left off rather than sent as nothing but gaps.
     */
    private fun seriesShapes(
        entry: WeatherAppEntry,
        iconSize: IconPixelSize?,
        unit: String,
        wanted: (String) -> Boolean,
    ): Map<String, Map<String, JsonElement>> = buildMap {
        val start = entry.forecastDayStart()
        val hours = entry.todayHourly.orEmpty() + entry.tomorrowHourly.orEmpty()
        val days = entry.dailyForecast.orEmpty()
        fun series(property: String, values: List<*>, step: Long, build: SeriesShape.() -> SeriesShape) {
            if (values.isNotEmpty() && wanted(property)) {
                put(property, mapOf(SourceShapeNames.SERIES to encode(SeriesShape(start, step).build())))
            }
        }
        fun <T> List<T>.ifAny(reading: (T) -> Any?) = takeIf { it.any { v -> reading(v) != null } }.orEmpty()
        series(PROPERTY_HOURLY_TEMPERATURE, hours, SECONDS_PER_HOUR) {
            temperatureSeries(hours.map { it.temp.toShort() }, unit)
        }
        series(PROPERTY_HOURLY_CONDITION, hours, SECONDS_PER_HOUR) {
            conditionSeries(hours.map { it.weatherType.code }, iconSize)
        }
        series(PROPERTY_HOURLY_CONDITION_CODE, hours, SECONDS_PER_HOUR) {
            copy(longText = TextSeries(hours.map { conditionCodeOf(it.weatherType.code) }))
        }
        series(PROPERTY_HOURLY_PRECIPITATION, hours.ifAny { it.precipProbability }, SECONDS_PER_HOUR) {
            percentSeries(hours.map { it.precipProbability?.toDouble() })
        }
        series(PROPERTY_HOURLY_PRECIPITATION_AMOUNT, hours.ifAny { it.precipMm }, SECONDS_PER_HOUR) {
            val mm = hours.map { it.precipMm }
            copy(
                numericValue = NumericSeries(mm, unit = "mm", min = 0.0),
                shortText = TextSeries(mm.map { it?.let { amount -> "${(amount * 10).roundToInt() / 10.0} mm" } }),
            )
        }
        series(PROPERTY_DAILY_HIGH, days, SECONDS_PER_DAY) {
            temperatureSeries(days.map { it.highTemp.takeUnless { temp -> temp == TEMP_NO_VALUE } }, unit)
        }
        series(PROPERTY_DAILY_LOW, days, SECONDS_PER_DAY) {
            temperatureSeries(days.map { it.lowTemp }, unit)
        }
        series(PROPERTY_DAILY_CONDITION, days, SECONDS_PER_DAY) {
            conditionSeries(days.map { it.weatherType.code }, iconSize)
        }
        series(PROPERTY_DAILY_CONDITION_CODE, days, SECONDS_PER_DAY) {
            copy(longText = TextSeries(days.map { conditionCodeOf(it.weatherType.code) }))
        }
        series(PROPERTY_DAILY_SUNRISE, days.ifAny { it.sunriseUtcSecs }, SECONDS_PER_DAY) {
            copy(timestamp = TimestampSeries(days.map { it.sunriseUtcSecs }))
        }
        series(PROPERTY_DAILY_SUNSET, days.ifAny { it.sunsetUtcSecs }, SECONDS_PER_DAY) {
            copy(timestamp = TimestampSeries(days.map { it.sunsetUtcSecs }))
        }
        series(PROPERTY_DAILY_PRECIPITATION, days.ifAny { it.precipProbability }, SECONDS_PER_DAY) {
            percentSeries(days.map { it.precipProbability?.toDouble() })
        }
    }

    private fun SeriesShape.percentSeries(percents: List<Double?>) = copy(
        numericValue = NumericSeries(percents, unit = "%", min = 0.0, max = 100.0),
        shortText = TextSeries(percents.map { it?.let { percent -> "${percent.roundToInt()}%" } }),
    )

    private fun SeriesShape.temperatureSeries(temps: List<Short?>, unit: String) = copy(
        numericValue = NumericSeries(temps.map { it?.toDouble() }, unit = unit),
        shortText = TextSeries(temps.map { it?.let { temp -> "$temp$unit" } }),
    )

    private fun SeriesShape.conditionSeries(codes: List<Byte>, iconSize: IconPixelSize?) = copy(
        shortText = TextSeries(codes.map(::conditionName)),
        icon = iconSize?.let { size -> BitmapSeries(codes.map { iconShape(it, size) }) },
        image = iconSize?.let { size -> BitmapSeries(codes.map { imageShape(it, size) }) },
    )

    /** Wind, humidity, the place's UTC offset and today's sun, each left off when the forecast lacked it. */
    private fun todayShapes(entry: WeatherAppEntry, wind: WindUnits): Map<String, Map<String, JsonElement>> = buildMap {
        val today = entry.dailyForecast?.firstOrNull()
        entry.todayWindSpeed?.let { mph ->
            val speed = when (wind) {
                WindUnits.FromDistance, WindUnits.Mph -> mph.toDouble()
                WindUnits.KmH -> mph * 1.609344
                WindUnits.MetersPerSecond -> mph * 0.44704
                WindUnits.Knots -> mph * 0.868976
            }
            put(
                PROPERTY_WIND_SPEED,
                mapOf(
                    SourceShapeNames.NUMERIC_VALUE to encode(NumericValueShape(value = mph.toDouble(), unit = "mph")),
                    SourceShapeNames.SHORT_TEXT to encode(ShortTextShape("${speed.roundToInt()} ${wind.displayName}")),
                ),
            )
        }
        entry.todayWindDirection?.let {
            put(PROPERTY_WIND_DIRECTION, mapOf(SourceShapeNames.NUMERIC_VALUE to encode(NumericValueShape(it.toDouble(), "°", 0.0, 360.0))))
        }
        today?.humidityPct?.let {
            put(
                PROPERTY_HUMIDITY,
                mapOf(
                    SourceShapeNames.NUMERIC_VALUE to encode(NumericValueShape(it.toDouble(), "%", 0.0, 100.0)),
                    SourceShapeNames.SHORT_TEXT to encode(ShortTextShape("$it%")),
                ),
            )
        }
        entry.locationUtcOffsetMin?.let {
            put(PROPERTY_UTC_OFFSET, mapOf(SourceShapeNames.NUMERIC_VALUE to encode(NumericValueShape(it.toDouble(), "min"))))
        }
        today?.sunriseUtcSecs?.let { put(PROPERTY_SUNRISE, mapOf(SourceShapeNames.TIMESTAMP to encode(TimestampShape(it)))) }
        today?.sunsetUtcSecs?.let { put(PROPERTY_SUNSET, mapOf(SourceShapeNames.TIMESTAMP to encode(TimestampShape(it)))) }
    }

    /** Absent rather than zero when the forecast didn't carry one. */
    private fun precipitationShapes(entry: WeatherAppEntry): Map<String, Map<String, JsonElement>> {
        val percent = entry.todayPrecipProbability?.toDouble() ?: return emptyMap()
        return mapOf(
            PROPERTY_PRECIPITATION to mapOf(
                SourceShapeNames.NUMERIC_VALUE to encode(NumericValueShape(value = percent, unit = "%", min = 0.0, max = 100.0)),
                SourceShapeNames.SHORT_TEXT to encode(ShortTextShape("${percent.roundToInt()}%")),
            ),
        )
    }

    /** The UV of the hour the forecast was fetched in, not the day's peak; absent when the forecast had no hourly UV. */
    private fun uvShapes(entry: WeatherAppEntry): Map<String, Map<String, JsonElement>> {
        val hour = ((entry.lastUpdateTimeUtcSecs - entry.forecastDayStart()) / SECONDS_PER_HOUR).toInt()
        val hours = entry.todayHourly.orEmpty() + entry.tomorrowHourly.orEmpty()
        val uv = hours.getOrNull(hour)?.uvIndexX10?.let { it / 10.0 } ?: return emptyMap()
        return mapOf(
            PROPERTY_UV_INDEX to mapOf(
                // 11 is the top of the published scale; anything above it is still "extreme".
                SourceShapeNames.NUMERIC_VALUE to encode(NumericValueShape(value = uv, min = 0.0, max = 11.0)),
                SourceShapeNames.SHORT_TEXT to encode(ShortTextShape(uv.roundToInt().toString())),
            ),
        )
    }

    // The numericValue carries no min or max: a temperature has nothing to fill a gauge against,
    // but a consumer that wants the number rather than the string still gets it.
    private fun tempShapes(temp: Short, unit: String) = mapOf(
        SourceShapeNames.SHORT_TEXT to encode(ShortTextShape("$temp$unit")),
        SourceShapeNames.LONG_TEXT to encode(LongTextShape("$temp$unit")),
        SourceShapeNames.NUMERIC_VALUE to encode(NumericValueShape(value = temp.toDouble(), unit = unit)),
    )

    /**
     * The glyphs are only drawn when a subscriber asked for them: rasterising and encoding is
     * the expensive half of an emission, and a tile showing the word doesn't want them.
     */
    private fun conditionShapes(code: Byte, forecast: String?, iconSize: IconPixelSize?) = buildMap {
        put(SourceShapeNames.SHORT_TEXT, encode(ShortTextShape(conditionName(code))))
        forecast?.let { put(SourceShapeNames.LONG_TEXT, encode(LongTextShape(it))) }
        iconSize?.let { size ->
            put(SourceShapeNames.ICON, encode(iconShape(code, size)))
            put(SourceShapeNames.IMAGE, encode(imageShape(code, size)))
        }
    }

    private fun iconShape(code: Byte, size: IconPixelSize): IconShape {
        val icon = WeatherIcons.icon(conditionCodeOf(code), minOf(size.w, size.h))
        return IconShape(icon.base64Pixels(), icon.base64Palette(), icon.width, icon.height)
    }

    private fun imageShape(code: Byte, size: IconPixelSize): ImageShape {
        val image = WeatherIcons.image(conditionCodeOf(code), minOf(size.w, size.h))
        return ImageShape(image.base64Pixels(), image.base64Palette(), image.width, image.height)
    }

    @OptIn(ExperimentalEncodingApi::class, ExperimentalUnsignedTypes::class)
    private fun EncodedImage.base64Pixels() = Base64.encode(pixels.toByteArray())

    @OptIn(ExperimentalEncodingApi::class, ExperimentalUnsignedTypes::class)
    private fun EncodedImage.base64Palette() = Base64.encode(palette.toByteArray())

    /**
     * The condition as one of a fixed set of names, for a consumer that would rather draw its
     * own artwork than take the plugin's glyph. The same vocabulary the built-in glyphs are
     * keyed by, so a plugin serving weather from somewhere else can be swapped in behind a
     * watchface that ships a picture per code.
     */
    // longText, not shortText: `partly_cloudy` is twice the few characters shortText promises.
    private fun conditionCode(code: Byte) =
        mapOf(SourceShapeNames.LONG_TEXT to encode(LongTextShape(conditionCodeOf(code))))

    private fun conditionName(code: Byte) = when (WeatherType.entries.firstOrNull { it.code == code }) {
        WeatherType.PartlyCloudy -> "Partly cloudy"
        WeatherType.CloudyDay -> "Cloudy"
        WeatherType.LightSnow -> "Light snow"
        WeatherType.LightRain -> "Light rain"
        WeatherType.HeavyRain -> "Heavy rain"
        WeatherType.HeavySnow -> "Heavy snow"
        WeatherType.Sun -> "Sunny"
        WeatherType.RainAndSnow -> "Rain and snow"
        else -> "Unknown"
    }

    private inline fun <reified T> encode(value: T): JsonElement = Json.encodeToJsonElement(value)

    companion object {
        const val CATEGORY = "weather"
        const val ITEM_LOCATION = "location"
        const val PROPERTY_TEMPERATURE = "temperature"
        const val PROPERTY_FEELS_LIKE = "feels_like"
        const val PROPERTY_HIGH = "high"
        const val PROPERTY_LOW = "low"
        const val PROPERTY_CONDITION = "condition"
        const val PROPERTY_CONDITION_CODE = "condition_code"
        const val PROPERTY_UV_INDEX = "uv_index"
        const val PROPERTY_PRECIPITATION = "precipitation"
        const val PROPERTY_HOURLY_TEMPERATURE = "hourly_temperature"
        const val PROPERTY_HOURLY_CONDITION = "hourly_condition"
        const val PROPERTY_HOURLY_CONDITION_CODE = "hourly_condition_code"
        const val PROPERTY_HOURLY_PRECIPITATION = "hourly_precipitation"
        const val PROPERTY_HOURLY_PRECIPITATION_AMOUNT = "hourly_precipitation_amount"
        const val PROPERTY_DAILY_HIGH = "daily_high"
        const val PROPERTY_DAILY_LOW = "daily_low"
        const val PROPERTY_DAILY_CONDITION = "daily_condition"
        const val PROPERTY_DAILY_CONDITION_CODE = "daily_condition_code"
        const val PROPERTY_DAILY_PRECIPITATION = "daily_precipitation"

        // Shared `weather/location` names, which a weather plugin needs to feed the watch's
        // weather app and timeline (upstream's `pluginWeatherResponse`).
        const val PROPERTY_WIND_SPEED = "wind_speed"
        const val PROPERTY_WIND_DIRECTION = "wind_direction"
        const val PROPERTY_HUMIDITY = "humidity"
        const val PROPERTY_UTC_OFFSET = "utc_offset"
        const val PROPERTY_SUNRISE = "sunrise"
        const val PROPERTY_SUNSET = "sunset"
        const val PROPERTY_DAILY_SUNRISE = "daily_sunrise"
        const val PROPERTY_DAILY_SUNSET = "daily_sunset"

        // The weather icon vocabulary, published in `new-plugin-api.md` for consumers to draw.
        const val ICON_SUN = "sun"
        const val ICON_PARTLY_CLOUDY = "partly_cloudy"
        const val ICON_CLOUDY = "cloudy"
        const val ICON_LIGHT_RAIN = "light_rain"
        const val ICON_HEAVY_RAIN = "heavy_rain"
        const val ICON_LIGHT_SNOW = "light_snow"
        const val ICON_HEAVY_SNOW = "heavy_snow"
        const val ICON_RAIN_AND_SNOW = "rain_and_snow"
        const val ICON_UNKNOWN = "unknown"

        private val TEXT_SHAPES = listOf(SourceShapeNames.SHORT_TEXT, SourceShapeNames.LONG_TEXT)
        private val CONDITION_ICON_SHAPES = listOf(SourceShapeNames.ICON, SourceShapeNames.IMAGE)
        private val TEMPERATURE_SHAPES = TEXT_SHAPES + SourceShapeNames.NUMERIC_VALUE
        private val NUMERIC_SERIES_SHAPES =
            listOf(SourceShapeNames.NUMERIC_VALUE, SourceShapeNames.SHORT_TEXT).map(SourceShapeNames::series)
        private val CONDITION_SERIES_SHAPES =
            listOf(SourceShapeNames.SHORT_TEXT, SourceShapeNames.ICON, SourceShapeNames.IMAGE).map(SourceShapeNames::series)
        private val CODE_SERIES_SHAPES = listOf(SourceShapeNames.series(SourceShapeNames.LONG_TEXT))
        private val TIMESTAMP_SERIES_SHAPES = listOf(SourceShapeNames.series(SourceShapeNames.TIMESTAMP))

        // Reserved built-in UUID namespace: prefix 00000000-0000-0000-0001-* for built-ins.
        val BUILT_IN_WEATHER_UUID: Uuid = Uuid.parse("00000000-0000-0000-0001-000000000002")
    }
}

/**
 * Local midnight, in epoch seconds, on the day the forecast was fetched: where its hourly and daily
 * series start. The location's own offset if it reported one; this machine's otherwise, since the
 * location that matters is usually the user's own.
 */
internal fun WeatherAppEntry.forecastDayStart(): Long {
    val offsetSec = locationUtcOffsetMin?.let { it * 60L }
        ?: TimeZone.currentSystemDefault().offsetAt(Instant.fromEpochSeconds(lastUpdateTimeUtcSecs)).totalSeconds.toLong()
    val local = lastUpdateTimeUtcSecs + offsetSec
    return local - local.mod(SECONDS_PER_DAY) - offsetSec
}

private const val SECONDS_PER_HOUR = 3600L
private const val SECONDS_PER_DAY = 86_400L

/** A day without a high (upstream's WeatherFetcher writes this when the forecast had none). */
private const val TEMP_NO_VALUE = Short.MAX_VALUE
