package de.yoxcu.stoandl.weather

import de.yoxcu.stoandl.config.StoandlConfig
import de.yoxcu.stoandl.config.StoandlConfig.WeatherLocation
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WeatherPlacesTest {
    private val defaults = StoandlConfig.load(File("/nonexistent/stoandl.conf"), logResult = false)
    private val berlin = FoundPlace("Berlin", 52.52437, 13.41053, "Land Berlin", "Germany", "DE")

    private class Recorder(val places: List<FoundPlace>) : PlaceSearch {
        val queries = mutableListOf<String>()
        override suspend fun search(name: String): List<FoundPlace> { queries += name; return places }
    }

    private fun control(cfg: StoandlConfig, search: PlaceSearch = Recorder(listOf(berlin)), written: MutableList<String> = mutableListOf()) =
        WeatherPlaces(search, { cfg }) { written += it; "ok:weather.locations set" }

    @Test
    fun `search makes no request unless weather and place search are both on`() = runBlocking {
        val search = Recorder(listOf(berlin))
        assertTrue(control(defaults, search).search("Berlin").startsWith("disabled:"))
        assertTrue(control(defaults.copy(weatherPlaceSearch = true, weatherEnabled = false), search)
            .search("Berlin").startsWith("disabled:"))
        assertEquals(emptyList(), search.queries)
        assertEquals(false, defaults.weatherPlaceSearch)

        val on = defaults.copy(weatherPlaceSearch = true)
        assertEquals("ok:Berlin\t52.5244\t13.4105\tLand Berlin\tGermany", control(on, search).search("  Berlin "))
        assertEquals(listOf("Berlin"), search.queries)
        assertTrue(control(on, Recorder(emptyList())).search("Nowhere").startsWith("none:"))
        assertTrue(control(on, search).search(" ").startsWith("error:"))
    }

    @Test
    fun `adding replaces a same-named location and keeps the others in order`() {
        val written = mutableListOf<String>()
        val cfg = defaults.copy(weatherLocations = listOf(WeatherLocation("berlin", 1.0, 2.0), WeatherLocation("Home", 48.1, 11.5)))
        assertEquals("ok:Updated Berlin (52.5244, 13.4105)", control(cfg, written = written).add("Berlin", 52.52437, 13.41053))
        assertEquals(listOf("Berlin:52.5244:13.4105,Home:48.1:11.5"), written)
        assertEquals("ok:Added Paris (48.8534, 2.3488)", control(cfg, written = written).add("Paris", 48.85341, 2.3488))
        assertEquals("berlin:1:2,Home:48.1:11.5,Paris:48.8534:2.3488", written.last())
    }

    @Test
    fun `what is written reads back as the same locations`() {
        val written = mutableListOf<String>()
        control(defaults, written = written).add("Frankfurt, am Main #1", 0.00004, -0.0001)
        val entries = written.single().split(',')
        assertEquals(listOf(WeatherLocation("Frankfurt am Main 1", 0.0, -0.0001)), StoandlConfig.parseWeatherLocations(entries))
    }

    @Test
    fun `bad names and coordinates are refused before anything is written`() {
        val written = mutableListOf<String>()
        val c = control(defaults, written = written)
        assertTrue(c.add(" ,# ", 1.0, 1.0).startsWith("error:"))
        assertTrue(c.add("X", 91.0, 1.0).startsWith("error:"))
        assertTrue(c.add("X", 1.0, Double.NaN).startsWith("error:"))
        assertTrue(written.isEmpty())
    }

    @Test
    fun `a refused write is passed through`() {
        val c = WeatherPlaces(Recorder(emptyList()), { defaults }) { "error:weather.locations: nope" }
        assertEquals("error:weather.locations: nope", c.add("X", 1.0, 1.0))
    }

    @Test
    fun `coordinates print in plain decimal notation`() {
        assertEquals("0.0001", coordinateText(roundCoordinate(0.0001)))
        assertEquals("0", coordinateText(roundCoordinate(-0.00001)))
        assertEquals("-122.4194", coordinateText(roundCoordinate(-122.41942)))
        assertNull(locationLabel("\t\n"))
    }
}
