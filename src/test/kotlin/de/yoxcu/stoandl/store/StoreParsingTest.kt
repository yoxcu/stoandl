package de.yoxcu.stoandl.store

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The store has two JSON dialects: Algolia (search; `compatibility` is a per-platform object and
 * `list_image` a URL string) and the REST detail endpoint (`compatibility` is the same object and
 * `list_image` a size→URL map). Both are normalized here, and this is what fixes the field layout the
 * CLI depends on — the earlier plan had them backwards. The index used to send `compatibility` as a
 * plain array of platform names; that shape is still parsed for older cached pages and fixtures.
 */
class StoreParsingTest {

    @Test
    fun `an Algolia hit keeps the id, author and hardware platforms`() {
        val page = parseSearchResponse(ALGOLIA)
        assertEquals(2, page.total)
        assertEquals(20, page.pages)
        assertEquals(1, page.page)
        assertEquals(listOf("60803d141342ae009528070f", "6241bec931bc3600098fec19"), page.hits.map { it.id })

        val hit = page.hits.first()
        assertEquals("TicToc+", hit.title)
        assertEquals("Nikki", hit.author)
        assertEquals("watchface", hit.type)
        assertEquals(68, hit.hearts)
        // ios/android are in compatibility too, and emery has no binary; only installable watch
        // hardware belongs in the platform list.
        assertEquals(listOf("aplite", "basalt", "chalk", "diorite"), hit.platforms)
        assertEquals("https://assets2.rebble.io/exact/144x144/60803d251342ae009528071a", hit.listImage)
    }

    @Test
    fun `an older Algolia page with a compatibility array still parses`() {
        val hit = parseSearchResponse(
            """{"hits":[{"id":"abc","title":"Legacy",
               "compatibility":["ios","aplite","emery"]}]}"""
        ).hits.single()
        assertEquals(listOf("aplite", "emery"), hit.platforms)
    }

    @Test
    fun `a hit without a usable id is skipped instead of failing the page`() {
        val page = parseSearchResponse(
            """{"hits":[{"title":"No id"},{"id":"ddeeff","title":"Good"}],"nbHits":2}"""
        )
        assertEquals(listOf("ddeeff"), page.hits.map { it.id })
    }

    @Test
    fun `REST detail normalizes the map-shaped images, compatibility and PBW URL`() {
        val app = parseDetailResponse(DETAIL)!!
        assertEquals("60803d141342ae009528070f", app.id)
        assertEquals("TicToc+", app.title)
        assertEquals(68, app.hearts)
        assertEquals("1.3", app.version)
        assertEquals("https://storage.googleapis.com/rebble-pbws/pbw/6241bec931bc3600098fec19.pbw", app.pbwUrl)
        // 144x144 is preferred over 80x80.
        assertEquals("https://assets2.rebble.io/exact/144x144/60803d251342ae009528071a", app.listImage)
        // has_binary=false and unsupported entries are not platforms you can install on.
        assertEquals(listOf("aplite", "basalt"), app.platforms)
        assertTrue(app.supports("aplite"))
        assertFalse(app.supports("chalk"))
    }

    @Test
    fun `an app with no release has no PBW and an omitted compatibility map is not incompatible`() {
        val app = parseDetailResponse(
            """{"data":[{"id":"abc","title":"Bare","type":"watchface"}]}"""
        )!!
        assertNull(app.pbwUrl)
        // Unknown, not "unsupported": sideloading still validates the bundle.
        assertTrue(app.supports("aplite"))
        assertEquals(0, app.hearts)
    }

    @Test
    fun `a detail response with no data is a missing app`() {
        assertNull(parseDetailResponse("""{"data":[]}"""))
    }

    @Test
    fun `store type accepts the CLI spellings and rejects junk`() {
        assertEquals(StoreItemType.FACES, StoreItemType.parse("watchface"))
        assertEquals(StoreItemType.FACES, StoreItemType.parse("Faces"))
        assertEquals(StoreItemType.APPS, StoreItemType.parse("apps"))
        assertNull(StoreItemType.parse("bogus"))
    }

    @Test
    fun `WatchDetails platform names map to store hardware codenames`() {
        assertEquals("aplite", storePlatformFromWatchTypeName("APLITE"))
        assertEquals("emery", storePlatformFromWatchTypeName("emery"))
        assertNull(storePlatformFromWatchTypeName(""))
        assertNull(storePlatformFromWatchTypeName("not-a-watch"))
    }

    private companion object {
        val ALGOLIA = """
            {
              "hits": [
                {
                  "id": "60803d141342ae009528070f",
                  "uuid": "0bcee2c3-a857-40ea-a0fb-7efd2dcc0125",
                  "title": "TicToc+",
                  "author": "Nikki",
                  "type": "watchface",
                  "category": "Faces",
                  "hearts": 68,
                  "compatibility": {
                    "ios": {"supported": true},
                    "android": {"supported": true},
                    "aplite": {"firmware": {"major": 3}, "has_binary": true, "supported": true},
                    "basalt": {"firmware": {"major": 3}, "has_binary": true, "supported": true},
                    "chalk": {"firmware": {"major": 3}, "has_binary": true, "supported": true},
                    "diorite": {"firmware": {"major": 3}, "has_binary": true, "supported": true},
                    "emery": {"firmware": {"major": 3}, "has_binary": false, "supported": true}
                  },
                  "list_image": "https://assets2.rebble.io/exact/144x144/60803d251342ae009528071a"
                },
                {
                  "objectID": "6241bec931bc3600098fec19",
                  "uuid": "0bcee2c3-a857-40ea-a0fb-7efd2dcc0125",
                  "title": "Second",
                  "author": "Someone",
                  "type": "watchface",
                  "hearts": 3,
                  "compatibility": {"aplite": {"supported": true, "has_binary": true}},
                  "list_image": "https://example.invalid/second"
                }
              ],
              "nbHits": 2,
              "page": 1,
              "nbPages": 20
            }
        """.trimIndent()

        val DETAIL = """
            {
              "data": [{
                "id": "60803d141342ae009528070f",
                "uuid": "0bcee2c3-a857-40ea-a0fb-7efd2dcc0125",
                "title": "TicToc+",
                "author": "Nikki",
                "type": "watchface",
                "category": "Faces",
                "hearts": 68,
                "description": "An extension of the default watchface.",
                "compatibility": {
                  "android": {"supported": true},
                  "aplite": {"firmware": {"major": 3}, "has_binary": true, "supported": true},
                  "basalt": {"firmware": {"major": 3}, "has_binary": true, "supported": true},
                  "chalk": {"firmware": {"major": 3}, "has_binary": false, "supported": true},
                  "diorite": {"firmware": {"major": 3}, "has_binary": true, "supported": false}
                },
                "list_image": {
                  "144x144": "https://assets2.rebble.io/exact/144x144/60803d251342ae009528071a",
                  "80x80": "https://assets2.rebble.io/exact/80x80/60803d251342ae009528071a"
                },
                "latest_release": {
                  "id": "6241bec931bc3600098fec19",
                  "pbw_file": "https://storage.googleapis.com/rebble-pbws/pbw/6241bec931bc3600098fec19.pbw",
                  "published_date": "Mon, 28 Mar 2022 13:57:29 GMT",
                  "version": "1.3"
                }
              }]
            }
        """.trimIndent()
    }
}
