package de.yoxcu.stoandl.pebble

import io.rebble.libpebblecommon.database.dao.VibePatternDao
import io.rebble.libpebblecommon.database.entity.BaseAttribute
import io.rebble.libpebblecommon.database.entity.VibePatternEntity
import io.rebble.libpebblecommon.notification.DefaultVibePattern
import io.rebble.libpebblecommon.notification.uIntPattern
import io.rebble.libpebblecommon.packets.blobdb.TimelineAttribute
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Named custom vibes share the built-ins' table and the per-app vocabulary, so a name must never
 * shadow a built-in, read as a CSV or a reset word, and a stored name must resolve on the send path.
 */
class VibePatternsTest {

    private class FakeDao(rows: List<VibePatternEntity>) : VibePatternDao {
        val rows = MutableStateFlow(rows)
        override fun getVibePatterns(): Flow<List<VibePatternEntity>> = rows
        override suspend fun getVibePattern(name: String) = rows.value.firstOrNull { it.name == name }
        override suspend fun insertOrIgnore(pattern: VibePatternEntity) {
            if (rows.value.none { it.name == pattern.name }) rows.value += pattern
        }
        override suspend fun insertOrUpdate(pattern: VibePatternEntity) {
            rows.value = rows.value.filterNot { it.name == pattern.name } + pattern
        }
        override suspend fun deleteCustomPattern(name: String) {
            rows.value = rows.value.filterNot { it.name == name && !it.bundled }
        }
    }

    private fun seeded() = FakeDao(DefaultVibePattern.entries.map { VibePatternEntity(it.displayName, it.uIntPattern(), bundled = true) })

    @Test
    fun customVibesResolveOnTheSendPath() {
        val custom: CustomVibes = mapOf("Work chat" to listOf(100u, 50u, 100u))
        assertEquals(listOf(100u, 50u, 100u), perAppVibe("work CHAT", custom))
        assertEquals("Work chat", normalizeVibe(" work chat ", custom))
        assertNull(perAppVibe("Work chat"), "unknown without the store")
        // A built-in wins over nothing else, a CSV still parses.
        assertEquals(DefaultVibePattern.Double.uIntPattern(), perAppVibe("double", custom))
        assertEquals("10,20", normalizeVibe("10, 20", custom))
    }

    @Test
    fun choicesListBuiltInsThenCustomWithTheirPattern() {
        val records = notifStyleChoices(mapOf("b" to listOf(5u), "A" to listOf(1u, 2u))).map { it.split('\t') }
        val vibes = records.filter { it[0] == "vibe" }
        assertEquals(DefaultVibePattern.entries.size + 2, vibes.size)
        assertEquals(listOf("vibe", "Double", "Double", "200,75,200", "builtin"), vibes.first { it[1] == "Double" })
        assertEquals(listOf(listOf("vibe", "A", "A", "1,2", "custom"), listOf("vibe", "b", "b", "5", "custom")), vibes.takeLast(2))
    }

    @Test
    fun customNamesThatWouldBeMisreadAreRefused() {
        for (name in listOf("", "  ", "Double", "ohhhoh", "No vibration", "100,50", "default", "None", "clear", "a\tb", "x".repeat(41))) {
            assertIs<CustomVibeCheck.Refused>(checkCustomVibe(name, "100,50"), "name '$name'")
        }
        for (pattern in listOf("", "fast", "0,0", "0,500,0,500", List(65) { "10" }.joinToString(","), "6000,100,4000")) {
            assertIs<CustomVibeCheck.Refused>(checkCustomVibe("Mine", pattern), "pattern '$pattern'")
        }
        assertEquals(CustomVibeCheck.Ok("Mine", listOf(0u, 100u, 50u)), checkCustomVibe(" Mine ", "0, 100,50"))
    }

    @Test
    fun storeAddsReplacesAndRemoves() = runBlocking {
        val dao = seeded()
        val store = VibePatterns(dao)
        assertTrue(store.set("Work", "100,50").startsWith("ok:Added"))
        assertEquals(mapOf("Work" to listOf(100u, 50u)), store.custom())
        // Any case replaces it, taking the new spelling.
        assertTrue(store.set("WORK", "300").startsWith("ok:Updated"))
        assertEquals(mapOf("WORK" to listOf(300u)), store.custom())
        assertTrue(store.set("Double", "100").startsWith("error:"))
        assertTrue(store.set("work", "").startsWith("ok:Removed"))
        assertEquals(emptyMap(), store.custom())
        assertTrue(store.set("work", "").startsWith("notfound:"))
        assertTrue(store.set("Double", "").startsWith("error:"))
        assertEquals(DefaultVibePattern.entries.size, dao.rows.value.size, "built-ins untouched")
    }

    @Test
    fun aBundledRowTheEnumNoLongerHasStaysBuiltIn() = runBlocking {
        val dao = FakeDao(listOf(VibePatternEntity("Retired", listOf(10u), bundled = true)))
        assertTrue(VibePatterns(dao).set("retired", "100").startsWith("error:"))
        assertEquals(listOf(VibePatternEntity("Retired", listOf(10u), bundled = true)), dao.rows.value)
    }

    @Test
    fun theTestNotificationCarriesOnlyThePattern() {
        fun vibeOf(pattern: List<UInt>?) = vibeTestNotification("x", pattern).content.attributes
            .filterIsInstance<BaseAttribute.UIntListAttribute>()
            .firstOrNull { it.attribute == TimelineAttribute.VibrationPattern }?.list
        assertEquals(listOf(200u, 75u, 200u), vibeOf(listOf(200u, 75u, 200u)))
        assertNull(vibeOf(null), "the watch default sends no pattern")
        assertTrue(vibeTestNotification("x", null).content.actions.isEmpty())
    }
}
