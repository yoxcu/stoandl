package de.yoxcu.stoandl.store

import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The D-Bus store payloads are built here, so this pins the JSON the GUI will parse — including the
 * nulls that mean "unknown" (no watch → no compatibility verdict) rather than false.
 */
class StoreControlTest {

    @Test
    fun `search payload carries the page, the platform and a per-hit compatibility verdict`() {
        val page = StoreSearchPage(
            hits = listOf(
                hit(id = "aaa", title = "TicToc+", platforms = listOf("aplite", "basalt")),
                hit(id = "bbb", title = "Emery only", platforms = listOf("emery")),
                hit(id = "ccc", title = "Unknown hardware", platforms = emptyList()),
            ),
            total = 42,
            page = 0,
            pages = 3,
        )

        val json = searchJson(page, "aplite", "tictoc").jsonObject

        assertEquals("tictoc", json["query"]?.jsonPrimitive?.content)
        assertEquals("aplite", json["platform"]?.jsonPrimitive?.content)
        assertEquals(42, json["total"]?.jsonPrimitive?.long)
        assertEquals(3, json["pages"]?.jsonPrimitive?.long)
        val hits = json["hits"]!!.jsonArray
        assertEquals(3, hits.size)

        val first = hits[0].jsonObject
        assertEquals("aaa", first["id"]?.jsonPrimitive?.content)
        assertEquals("TicToc+", first["title"]?.jsonPrimitive?.content)
        assertEquals(listOf("aplite", "basalt"), first["platforms"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertTrue(first["compatible"]!!.jsonPrimitive.boolean)

        assertFalse(hits[1].jsonObject["compatible"]!!.jsonPrimitive.boolean)
        // A hit with no hardware list is "unknown" — null, not a false "yes".
        assertEquals("null", hits[2].jsonObject["compatible"].toString())
    }

    @Test
    fun `a hit without an image reports null rather than an empty string`() {
        val json = searchJson(
            StoreSearchPage(listOf(hit(id = "aaa", title = "No art", platforms = listOf("aplite"), image = null)), 1, 0, 1),
            "aplite",
            "",
        ).jsonObject
        assertEquals("null", json["hits"]!!.jsonArray[0].jsonObject["image"].toString())
    }

    @Test
    fun `detail payload names the watch platform and judges compatibility`() {
        val json = appJson(app(), watchPlatform = "aplite", sizeBytes = 339_235).jsonObject

        assertEquals("60803d141342ae009528070f", json["id"]?.jsonPrimitive?.content)
        assertEquals(339_235, json["sizeBytes"]?.jsonPrimitive?.long)
        assertEquals("aplite", json["platform"]?.jsonPrimitive?.content)
        assertTrue(json["compatible"]!!.jsonPrimitive.boolean)
        assertEquals(
            "https://storage.googleapis.com/rebble-pbws/pbw/6241bec931bc3600098fec19.pbw",
            json["pbw"]?.jsonPrimitive?.content,
        )
        assertEquals("An extension of the default watchface.", json["description"]?.jsonPrimitive?.content)
    }

    @Test
    fun `with no watch the detail's compatibility fields are null, not false`() {
        val json = appJson(app(), watchPlatform = null, sizeBytes = null).jsonObject
        assertEquals("null", json["platform"].toString())
        assertEquals("null", json["compatible"].toString())
        assertEquals("null", json["sizeBytes"].toString())
    }

    @Test
    fun `detail reports an incompatible app for the connected board`() {
        val json = appJson(app(), watchPlatform = "emery", sizeBytes = 1).jsonObject
        assertFalse(json["compatible"]!!.jsonPrimitive.boolean)
    }

    private fun hit(
        id: String,
        title: String,
        platforms: List<String>,
        image: String? = "https://example.invalid/$id.png",
    ) = StoreHit(
        id = id,
        uuid = "$id-uuid",
        title = title,
        author = "Author",
        type = "watchface",
        category = "Faces",
        hearts = 7,
        platforms = platforms,
        listImage = image,
    )

    private fun app() = StoreApp(
        id = "60803d141342ae009528070f",
        uuid = "0bcee2c3-a857-40ea-a0fb-7efd2dcc0125",
        title = "TicToc+",
        author = "Nikki",
        type = "watchface",
        category = "Faces",
        hearts = 68,
        description = "An extension of the default watchface.",
        platforms = listOf("aplite", "basalt"),
        pbwUrl = "https://storage.googleapis.com/rebble-pbws/pbw/6241bec931bc3600098fec19.pbw",
        version = "1.3",
        publishedDate = "Mon, 28 Mar 2022 13:57:29 GMT",
        listImage = "https://assets2.rebble.io/exact/144x144/60803d251342ae009528071a",
    )

    @Test
    fun `an app without a release serializes its missing optional fields as null`() {
        val json = appJson(
            app().copy(pbwUrl = null, version = null, publishedDate = null, listImage = null),
            watchPlatform = "aplite",
            sizeBytes = null,
        ).jsonObject
        assertNull(json["pbw"]?.jsonPrimitive?.contentOrNull)
        assertNull(json["version"]?.jsonPrimitive?.contentOrNull)
        assertNull(json["image"]?.jsonPrimitive?.contentOrNull)
    }
}
