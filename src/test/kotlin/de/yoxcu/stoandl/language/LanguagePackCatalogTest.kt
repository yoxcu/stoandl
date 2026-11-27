package de.yoxcu.stoandl.language

import io.rebble.libpebblecommon.metadata.WatchHardwarePlatform
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The bundled catalog is a frozen, checked-in resource, and [LanguagePackCatalog.load] answers a malformed one
 * with an empty catalog (the daemon must still start): a bad edit or merge of it would only log at runtime and
 * quietly empty `language list` and every install. This keeps the build failing on it instead, as the old
 * generator task did.
 */
class LanguagePackCatalogTest {
    private val raw = LanguagePackCatalog::class.java.getResource("/language-packs.json")!!.readText()

    @Test
    fun `the frozen catalog parses whole`() {
        val root = Json.parseToJsonElement(raw).jsonObject
        assertTrue("_origin" in root, "the resource says where it was frozen from")
        assertEquals(137, root.getValue("languages").jsonArray.size, "the packs of the frozen copy")

        val packs = LanguagePackCatalog.load().all()
        // One per (locale, board, source) after dedupe; an empty list means the parse failed.
        assertEquals(98, packs.size)
        assertEquals(17, packs.map { it.isoLocal }.distinct().size)
        packs.forEach {
            assertTrue(it.isoLocal.isNotBlank() && it.name.isNotBlank() && it.version > 0, "incomplete pack $it")
            assertTrue(it.file.startsWith("https://"), "pack ${it.id} has no download URL: ${it.file}")
        }
    }

    @Test
    fun `a core watch gets the silk packs, its own locale first`() {
        val packs = LanguagePackCatalog.load().forWatch(WatchHardwarePlatform.CORE_ASTERIX, "de_DE")
        assertTrue(packs.isNotEmpty())
        assertTrue(packs.all { it.hardware == null || it.hardware == "silk" }, packs.toString())
        assertEquals("de_DE", packs.first().isoLocal)
    }
}
