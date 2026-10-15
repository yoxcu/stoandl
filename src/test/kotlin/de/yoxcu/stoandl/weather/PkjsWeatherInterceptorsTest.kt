@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package de.yoxcu.stoandl.weather

import de.yoxcu.stoandl.config.StoandlConfig.WeatherLocation
import io.rebble.libpebblecommon.js.InterceptResponse
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/**
 * A wrong parse silently leaves a watchface on "--" (the request goes to the dead service), and a wrong
 * field name or unit shows nonsense on the wrist; neither is visible from the daemon log. No network: the
 * Open-Meteo fetcher, the place search, Nominatim and the OWM pass-through are all fakes.
 */
class PkjsWeatherInterceptorsTest {

    // A real Open-Meteo reply (Berlin, 2026-10-15 20:00 CEST), as OpenMeteoNow requests it.
    private val sample = """{"latitude":52.512604,"longitude":13.419517,"utc_offset_seconds":7200,"timezone":"Europe/Berlin",
        "current":{"time":1792087200,"interval":900,"temperature_2m":13.8,"relative_humidity_2m":78,"apparent_temperature":12.9,
        "is_day":0,"weather_code":3,"cloud_cover":92,"pressure_msl":1024.6,"surface_pressure":1020.1,"wind_speed_10m":1.62,
        "wind_direction_10m":158,"wind_gusts_10m":3.80,"visibility":32780.00},
        "daily":{"time":[1792015200,1792101600,1792188000,1792274400,1792360800],"weather_code":[3,45,80,45,3],
        "temperature_2m_max":[16.5,18.6,13.7,13.4,10.6],"temperature_2m_min":[6.7,8.0,6.6,4.5,5.9],
        "sunrise":[1792042295,1792128802,1792215310,1792301818,1792388326],
        "sunset":[1792080708,1792166975,1792253242,1792339510,1792425779]}}"""

    private val app = Uuid.parse("01234567-89ab-cdef-0123-456789abcdef")
    private val weatherLand = Uuid.parse("1f0b0701-cc8f-47ec-86e7-7181397f9a25")

    /** Records what was asked of the network-facing collaborators. */
    private inner class Fakes(
        var enabled: Boolean = true,
        var reverseOn: Boolean = false,
        val known: List<WeatherLocation> = listOf(WeatherLocation("Home", 52.52, 13.405)),
        val found: Map<String, List<FoundPlace>> = emptyMap(),
        val owm: InterceptResponse? = InterceptResponse("", 401),
    ) {
        val weatherCalls = mutableListOf<Triple<Pair<Double, Double>, TempUnit, WindUnit>>()
        val searches = mutableListOf<String>()
        val reverseCalls = mutableListOf<Pair<Double, Double>>()
        val passedThrough = mutableListOf<String>()

        val source = CurrentWeatherSource { lat, lon, t, w ->
            weatherCalls += Triple(lat to lon, t, w)
            parseOpenMeteoNow(sample)
        }
        val places = PkjsPlaces(
            knownLocations = { known },
            reverseGeocodeEnabled = { reverseOn },
            reverseGeocode = { lat, lon -> reverseCalls += lat to lon; "Nominatimville" },
            search = { name -> searches += name; found[name].orEmpty() },
        )
        val owmInterceptor = OpenWeather25Interceptor({ enabled }, source, places) { url -> passedThrough += url; owm }
        val yahoo = YahooWeatherInterceptor({ enabled }, source, places)
    }

    private fun json(r: InterceptResponse): JsonObject {
        assertEquals(200, r.status, r.result)
        return Json.parseToJsonElement(r.result).jsonObject
    }

    private fun JsonElement.at(vararg path: Any): JsonElement = path.fold(this) { e, k ->
        when (k) {
            is Int -> e.jsonArray[k]
            else -> e.jsonObject[k as String] ?: error("no $k in $e")
        }
    }

    private fun JsonElement.str() = jsonPrimitive.content

    private fun yql(q: String) = "https://query.yahooapis.com/v1/public/yql?q=" +
        java.net.URLEncoder.encode(q, Charsets.UTF_8) + "&format=json"

    // ── Yahoo request parsing (the first five URLs are the official app's test cases) ────────────────

    @Test
    fun `yql place lookup by name`() {
        val url = "http://query.yahooapis.com/v1/public/yql?q=select%20woeid,%20name%20from%20geo.places(1)%20where%20text=%22mountain%20view%22&format=json"
        assertEquals(YahooRequest.Location(YahooPlaceParams.PlaceName("mountain view")), parseYahooRequest(url))
    }

    @Test
    fun `yql place lookup by coordinates`() {
        val url = "http://query.yahooapis.com/v1/public/yql?q=select%20woeid,%20admin1,%20locality1,%20name%20%20from%20geo.places%20where%20text%20=%20%22(34.12345,-123.12345)%22&format=json"
        assertEquals(YahooRequest.Location(YahooPlaceParams.LatLon(34.12345, -123.12345)), parseYahooRequest(url))
    }

    @Test
    fun `yql weather by nested place name keeps the name's case`() {
        val url = "https://query.yahooapis.com/v1/public/yql?q=select%20*%20from%20weather.forecast%20where%20woeid%20in%20(select%20woeid%20from%20geo.places(1)%20where%20text%3D%22Mission%20District%2C%20San%20Francisco%2C%20California%2C%2090110%2C%20United%20States%22)%20and%20u%3D%22f%22%20limit%201&format=json&env=store://datatables.org/alltableswithkeys"
        assertEquals(
            YahooRequest.Weather(
                YahooPlaceParams.PlaceName("Mission District, San Francisco, California, 90110, United States"),
                fahrenheit = true, multiItems = false,
            ),
            parseYahooRequest(url),
        )
    }

    @Test
    fun `yql weather by woeid in celsius without limit is multi-item`() {
        val url = "https://query.yahooapis.com/v1/public/yql?q=select%20%20item.condition%2C%20item.forecast%2C%20astronomy%2C%20wind%2C%20atmosphere%20from%20weather.forecast%20where%20woeid%20%3D%20123456%20and%20u%20%3D%20%22c%22&format=json&env=store://datatables.org/alltableswithkeys"
        assertEquals(
            YahooRequest.Weather(YahooPlaceParams.Woeid(123456), fahrenheit = false, multiItems = true),
            parseYahooRequest(url),
        )
    }

    @Test
    fun `yql weather by nested coordinates`() {
        val url = "https://query.yahooapis.com/v1/public/yql?q=select%20%2A%20from%20weather.forecast%20where%20woeid%20in%20%28select%20woeid%20from%20geo.places%281%29%20where%20text%20%3D%20%22%2834.12345%2C-123.12345%29%22%29%20and%20u%3D%22f%22%20limit%201&format=json&env=store://datatables.org/alltableswithkeys"
        assertEquals(
            YahooRequest.Weather(YahooPlaceParams.LatLon(34.12345, -123.12345), fahrenheit = true, multiItems = false),
            parseYahooRequest(url),
        )
    }

    @Test
    fun `yql with raw spaces, single quotes and no unit defaults to fahrenheit`() {
        val url = "https://query.yahooapis.com/v1/public/yql?q=select * from weather.forecast where woeid in " +
            "(select woeid from geo.places(1) where text='( 52.5 , 13.4 )')&format=json"
        assertEquals(
            YahooRequest.Weather(YahooPlaceParams.LatLon(52.5, 13.4), fahrenheit = true, multiItems = true),
            parseYahooRequest(url),
        )
    }

    @Test
    fun `other yahoo and non-yahoo urls are left alone`() {
        assertNull(parseYahooRequest("https://query.yahooapis.com/v1/public/yql?q=" +
            java.net.URLEncoder.encode("select * from flickr.photos where text=\"cat\"", Charsets.UTF_8)))
        assertNull(parseYahooRequest("https://query.yahooapis.com/v1/public/other?q=select%20*%20from%20weather.forecast%20where%20woeid=1"))
        assertNull(parseYahooRequest("https://example.com/v1/public/yql?q=select%20*%20from%20weather.forecast%20where%20woeid=1"))
        assertNull(parseYahooRequest("https://query.yahooapis.com/v1/public/yql"))
        assertNull(parseYahooRequest("not a url"))
    }

    // ── OWM request parsing ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `owm coordinates with and without units`() {
        assertEquals(Owm25Request(52.52, 13.4, "metric"),
            parseOwm25Request("http://api.openweathermap.org/data/2.5/weather?lat=52.52&lon=13.4&units=metric&appid=abc"))
        val kelvin = parseOwm25Request("https://api.openweathermap.org/data/2.5/weather?lat=-33.9&lon=151.2&APPID=ABC")
        assertEquals(Owm25Request(-33.9, 151.2, null), kelvin)
        assertTrue(kelvin!!.kelvin)
        val imperial = parseOwm25Request("https://API.openweathermap.org/data/2.5/weather?lat=1&lon=2&units=Imperial&mode=json")!!
        assertEquals(TempUnit.FAHRENHEIT, imperial.tempUnit)
        assertEquals(WindUnit.MPH, imperial.windUnit)
        assertFalse(imperial.kelvin)
    }

    @Test
    fun `owm xml mode, city queries and other endpoints are left alone`() {
        assertNull(parseOwm25Request("https://api.openweathermap.org/data/2.5/weather?lat=1&lon=2&mode=xml"))
        assertNull(parseOwm25Request("https://api.openweathermap.org/data/2.5/weather?q=London&appid=x"))
        assertNull(parseOwm25Request("https://api.openweathermap.org/data/2.5/forecast?lat=1&lon=2"))
        assertNull(parseOwm25Request("https://api.openweathermap.org/data/3.0/onecall?lat=1&lon=2"))
    }

    // ── The off switch ───────────────────────────────────────────────────────────────────────────────

    @Test
    fun `switched off neither interceptor claims a request`() = runBlocking<Unit> {
        val f = Fakes(enabled = false)
        val owmUrl = "https://api.openweathermap.org/data/2.5/weather?lat=1&lon=2"
        val yahooUrl = yql("select * from weather.forecast where woeid=1 and u=\"c\"")
        assertFalse(f.owmInterceptor.shouldIntercept(owmUrl))
        assertFalse(f.yahoo.shouldIntercept(yahooUrl))
        assertEquals(500, f.owmInterceptor.onIntercepted(owmUrl, "GET", null, app).status)
        assertTrue(f.passedThrough.isEmpty() && f.weatherCalls.isEmpty())
        f.enabled = true
        assertTrue(f.owmInterceptor.shouldIntercept(owmUrl))
        assertTrue(f.yahoo.shouldIntercept(yahooUrl))
    }

    // ── OWM answers ──────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `a working owm key gets owm's own reply and no open-meteo call`() = runBlocking<Unit> {
        val real = InterceptResponse("""{"cod":200,"name":"Real"}""", 200)
        val f = Fakes(owm = real)
        val url = "https://api.openweathermap.org/data/2.5/weather?lat=52.52&lon=13.4&appid=good"
        assertEquals(real, f.owmInterceptor.onIntercepted(url, "GET", null, app))
        assertEquals(listOf(url), f.passedThrough)
        assertTrue(f.weatherCalls.isEmpty())
    }

    @Test
    fun `an owm error other than 401 is passed on`() = runBlocking<Unit> {
        val f = Fakes(owm = InterceptResponse("""{"cod":429}""", 429))
        val r = f.owmInterceptor.onIntercepted("https://api.openweathermap.org/data/2.5/weather?lat=1&lon=2", "GET", null, app)
        assertEquals(429, r.status)
        assertTrue(f.weatherCalls.isEmpty())
    }

    @Test
    fun `a rejected owm key is answered from open-meteo in kelvin`() = runBlocking<Unit> {
        val f = Fakes()
        val j = json(f.owmInterceptor.onIntercepted(
            "https://api.openweathermap.org/data/2.5/weather?lat=52.51&lon=13.42&appid=dead", "GET", null, app))
        assertEquals(listOf(Triple(52.51 to 13.42, TempUnit.CELSIUS, WindUnit.MS)), f.weatherCalls)
        assertEquals(52.51, j.at("coord", "lat").jsonPrimitive.double)
        assertEquals(804, j.at("weather", 0, "id").jsonPrimitive.int)
        assertEquals("Clouds", j.at("weather", 0, "main").str())
        assertEquals("overcast", j.at("weather", 0, "description").str())
        assertEquals("04n", j.at("weather", 0, "icon").str())
        assertEquals(286.95, j.at("main", "temp").jsonPrimitive.double)
        assertEquals(286.05, j.at("main", "feels_like").jsonPrimitive.double)
        assertEquals(279.85, j.at("main", "temp_min").jsonPrimitive.double)
        assertEquals(289.65, j.at("main", "temp_max").jsonPrimitive.double)
        assertEquals(1025, j.at("main", "pressure").jsonPrimitive.int)
        assertEquals(78, j.at("main", "humidity").jsonPrimitive.int)
        assertEquals(1020, j.at("main", "grnd_level").jsonPrimitive.int)
        assertEquals(10_000, j.at("visibility").jsonPrimitive.int)
        assertEquals(1.62, j.at("wind", "speed").jsonPrimitive.double)
        assertEquals(158, j.at("wind", "deg").jsonPrimitive.int)
        assertEquals(3.8, j.at("wind", "gust").jsonPrimitive.double)
        assertEquals(92, j.at("clouds", "all").jsonPrimitive.int)
        assertEquals(1792087200, j.at("dt").jsonPrimitive.long)
        assertEquals(1792042295, j.at("sys", "sunrise").jsonPrimitive.long)
        assertEquals(1792080708, j.at("sys", "sunset").jsonPrimitive.long)
        assertEquals(7200, j.at("timezone").jsonPrimitive.int)
        assertEquals(200, j.at("cod").jsonPrimitive.int)
        // 1.4 km from the configured "Home"; reverse geocoding is off, so Nominatim is never asked.
        assertEquals("Home", j.at("name").str())
        assertTrue(f.reverseCalls.isEmpty())
    }

    @Test
    fun `owm metric and imperial keep open-meteo's temperatures and ask for the matching units`() = runBlocking<Unit> {
        for ((units, expected) in listOf(
            "metric" to Triple(TempUnit.CELSIUS, WindUnit.MS, 13.8),
            "imperial" to Triple(TempUnit.FAHRENHEIT, WindUnit.MPH, 13.8),
        )) {
            val f = Fakes()
            val j = json(f.owmInterceptor.onIntercepted(
                "https://api.openweathermap.org/data/2.5/weather?lat=1&lon=2&units=$units", "GET", null, app))
            assertEquals(expected.first, f.weatherCalls.single().second)
            assertEquals(expected.second, f.weatherCalls.single().third)
            // The fake returns the same numbers whatever is asked: no further conversion happened.
            assertEquals(expected.third, j.at("main", "temp").jsonPrimitive.double)
            // Far from "Home", reverse geocoding off: no name.
            assertEquals("Unknown", j.at("name").str())
        }
    }

    @Test
    fun `reverse geocoding names the place only when switched on`() = runBlocking<Unit> {
        val f = Fakes(reverseOn = true)
        val j = json(f.owmInterceptor.onIntercepted(
            "https://api.openweathermap.org/data/2.5/weather?lat=52.51&lon=13.42", "GET", null, app))
        assertEquals("Nominatimville", j.at("name").str())
        assertEquals(listOf(52.51 to 13.42), f.reverseCalls)
    }

    @Test
    fun `no owm reply at all is an error, not a substitute`() = runBlocking<Unit> {
        val f = Fakes(owm = null)
        assertEquals(500, f.owmInterceptor.onIntercepted(
            "https://api.openweathermap.org/data/2.5/weather?lat=1&lon=2", "GET", null, app).status)
        assertTrue(f.weatherCalls.isEmpty())
    }

    // ── Yahoo answers ────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `a yahoo place lookup hands out a woeid the weather query then uses`() = runBlocking<Unit> {
        val f = Fakes()
        val place = json(f.yahoo.onIntercepted(
            yql("select woeid, admin1, locality1, name from geo.places where text=\"(52.51,13.42)\""), "GET", null, app))
            .at("query", "results", "place")
        assertEquals("Home", place.at("name").str())
        assertEquals("Home", place.at("locality1", "content").str())
        assertEquals("Unknown", place.at("admin1", "content").str())
        val woeid = place.at("woeid").jsonPrimitive.int
        assertTrue(f.weatherCalls.isEmpty())

        val ch = json(f.yahoo.onIntercepted(
            yql("select * from weather.forecast where woeid = $woeid and u = \"c\""), "GET", null, weatherLand))
            .at("query", "results", "channel")
        assertEquals(listOf(Triple(52.51 to 13.42, TempUnit.CELSIUS, WindUnit.KMH)), f.weatherCalls)
        // Weather Land reads the standard single channel even without "limit 1".
        assertEquals(14, ch.at("item", "condition", "temp").jsonPrimitive.int)
        assertEquals(26, ch.at("item", "condition", "code").jsonPrimitive.int)
        assertEquals("Overcast", ch.at("item", "condition", "text").str())
        assertEquals("2026/10/15", ch.at("item", "condition", "date").str())
        val forecast = ch.at("item", "forecast").jsonArray
        assertEquals(5, forecast.size)
        assertEquals("Thu", forecast[0].at("day").str())
        assertEquals("2026/10/15", forecast[0].at("date").str())
        assertEquals(7, forecast[0].at("low").jsonPrimitive.int)
        assertEquals(17, forecast[0].at("high").jsonPrimitive.int)
        assertEquals("Fri", forecast[1].at("day").str())
        assertEquals(20, forecast[1].at("code").jsonPrimitive.int) // fog
        assertEquals(40, forecast[2].at("code").jsonPrimitive.int) // scattered showers
        assertEquals(13, ch.at("wind", "chill").jsonPrimitive.int)
        assertEquals(158, ch.at("wind", "direction").jsonPrimitive.int)
        assertEquals(78, ch.at("atmosphere", "humidity").jsonPrimitive.int)
        assertEquals(32.8, ch.at("atmosphere", "visibility").jsonPrimitive.double)
        assertEquals(1024.6, ch.at("atmosphere", "pressure").jsonPrimitive.double)
        assertEquals("Home", ch.at("location", "city").str())
        assertEquals("C", ch.at("units", "temperature").str())
        assertEquals("km/h", ch.at("units", "speed").str())
        assertEquals("7:31 am", ch.at("astronomy", "sunrise").str())
        assertEquals("6:11 pm", ch.at("astronomy", "sunset").str())
    }

    @Test
    fun `a yahoo query without limit 1 gets one channel per day in fahrenheit units`() = runBlocking<Unit> {
        val f = Fakes()
        val channels = json(f.yahoo.onIntercepted(
            yql("select * from weather.forecast where woeid in (select woeid from geo.places(1) where text=\"(52.51,13.42)\") and u=\"f\""),
            "GET", null, app)).at("query", "results", "channel").jsonArray
        assertEquals(TempUnit.FAHRENHEIT, f.weatherCalls.single().second)
        assertEquals(WindUnit.MPH, f.weatherCalls.single().third)
        assertEquals(5, channels.size)
        assertEquals("Thu", channels[0].at("item", "forecast", "day").str())
        assertEquals("Mon", channels[4].at("item", "forecast", "day").str())
        assertEquals(14, channels[4].at("item", "condition", "temp").jsonPrimitive.int)
        assertEquals("F", channels[0].at("units", "temperature").str())
        assertEquals("mi", channels[0].at("units", "distance").str())
        assertEquals("in", channels[0].at("units", "pressure").str())
        assertEquals(30.26, channels[0].at("atmosphere", "pressure").jsonPrimitive.double)
        assertEquals(20.4, channels[0].at("atmosphere", "visibility").jsonPrimitive.double)
    }

    @Test
    fun `a yahoo query with limit 1 gets the standard channel`() = runBlocking<Unit> {
        val f = Fakes()
        val ch = json(f.yahoo.onIntercepted(
            yql("select * from weather.forecast where woeid in (select woeid from geo.places(1) where text=\"(52.51,13.42)\") and u=\"c\" limit 1"),
            "GET", null, app)).at("query", "results", "channel")
        assertTrue(ch is JsonObject)
        assertEquals(5, ch.at("item", "forecast").jsonArray.size)
    }

    @Test
    fun `a yahoo place name is searched part by part and the matching region wins`() = runBlocking<Unit> {
        val springfieldIl = FoundPlace("Springfield", 39.8, -89.6, "Illinois", "United States", "US")
        val springfieldMo = FoundPlace("Springfield", 37.2, -93.3, "Missouri", "United States", "US")
        val f = Fakes(found = mapOf("Springfield" to listOf(springfieldMo, springfieldIl)))
        val place = json(f.yahoo.onIntercepted(
            yql("select woeid from geo.places(1) where text=\"Springfield, Illinois\""), "GET", null, app))
            .at("query", "results", "place")
        assertEquals("Springfield", place.at("name").str())
        assertEquals("Illinois", place.at("admin1", "content").str())
        assertEquals("United States", place.at("country", "content").str())
        assertEquals(listOf("Springfield"), f.searches)

        // The same text again is served from the woeid cache: no second search.
        val woeid = place.at("woeid").jsonPrimitive.int
        val again = json(f.yahoo.onIntercepted(
            yql("select woeid from geo.places(1) where text=\"springfield, illinois\""), "GET", null, app))
        assertEquals(woeid, again.at("query", "results", "place", "woeid").jsonPrimitive.int)
        assertEquals(1, f.searches.size)
    }

    @Test
    fun `a district nobody knows falls through to the city`() = runBlocking<Unit> {
        val sf = FoundPlace("San Francisco", 37.77, -122.42, "California", "United States", "US")
        val f = Fakes(found = mapOf("San Francisco" to listOf(sf)))
        json(f.yahoo.onIntercepted(
            yql("select * from weather.forecast where woeid in (select woeid from geo.places(1) where " +
                "text=\"Mission District, San Francisco, California\") and u=\"f\" limit 1"), "GET", null, app))
        assertEquals(listOf("Mission District", "San Francisco"), f.searches)
        assertEquals(37.77 to -122.42, f.weatherCalls.single().first)
    }

    @Test
    fun `a configured location name answers without a search`() = runBlocking<Unit> {
        val f = Fakes()
        json(f.yahoo.onIntercepted(
            yql("select * from weather.forecast where woeid in (select woeid from geo.places(1) where text=\"home\") and u=\"c\""),
            "GET", null, app))
        assertTrue(f.searches.isEmpty())
        assertEquals(52.52 to 13.405, f.weatherCalls.single().first)
    }

    @Test
    fun `an unknown woeid or an unfound place is an error`() = runBlocking<Unit> {
        val f = Fakes()
        assertEquals(500, f.yahoo.onIntercepted(
            yql("select * from weather.forecast where woeid=2459115 and u=\"c\""), "GET", null, app).status)
        assertEquals(500, f.yahoo.onIntercepted(
            yql("select woeid from geo.places(1) where text=\"Atlantis\""), "GET", null, app).status)
        assertTrue(f.weatherCalls.isEmpty())
    }

    // ── Open-Meteo reply ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun `the open-meteo reply decodes`() {
        val now = assertNotNull(parseOpenMeteoNow(sample))
        assertEquals(13.8, now.temperature)
        assertEquals(3, now.weatherCode)
        assertFalse(now.isDay)
        assertEquals(7200, now.utcOffsetSec)
        assertEquals(5, now.days.size)
        assertEquals(45, now.days[1].weatherCode)
        assertEquals(1792080708L, now.days[0].sunsetSec)
    }

    @Test
    fun `an open-meteo reply without current conditions or days is no answer`() {
        assertNull(parseOpenMeteoNow("""{"utc_offset_seconds":0,"daily":{"time":[1],"weather_code":[0],"temperature_2m_max":[1.0],"temperature_2m_min":[0.0]}}"""))
        assertNull(parseOpenMeteoNow("""{"current":{"temperature_2m":1.0,"weather_code":0},"daily":{"time":[1],"weather_code":[null],"temperature_2m_max":[1.0],"temperature_2m_min":[0.0]}}"""))
        assertNotNull(parseOpenMeteoNow("""{"current":{"temperature_2m":1.0,"weather_code":0},"daily":{"time":[1],"weather_code":[0],"temperature_2m_max":[1.0],"temperature_2m_min":[0.0]}}"""))
    }

    @Test
    fun `responses carry no json nulls`() = runBlocking<Unit> {
        val f = Fakes()
        val r = f.owmInterceptor.onIntercepted("https://api.openweathermap.org/data/2.5/weather?lat=1&lon=2", "GET", null, app)
        assertFalse(Json.parseToJsonElement(r.result).toString().contains(JsonNull.toString()))
    }
}
