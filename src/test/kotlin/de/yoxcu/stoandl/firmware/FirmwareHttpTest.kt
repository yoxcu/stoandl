package de.yoxcu.stoandl.firmware

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.zip.GZIPOutputStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The firmware sources' transport: gzip (java.net.http neither asks for nor decodes it, and the GitHub
 * release list shrinks about tenfold), ETag revalidation (a 304 reuses the kept body), and the GitHub
 * page size. Served by a local HttpServer.
 */
class FirmwareHttpTest {
    /** One request as the server saw it. */
    private data class Seen(val path: String, val query: String?, val acceptEncoding: String?, val ifNoneMatch: String?)

    private val seen = CopyOnWriteArrayList<Seen>()
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val base get() = "http://127.0.0.1:${server.address.port}"

    /** Answer each request with this; it sees the request. */
    @Volatile private var respond: (HttpExchange) -> Unit = { it.sendResponseHeaders(404, -1) }

    init {
        server.createContext("/") { ex ->
            seen += Seen(
                ex.requestURI.path, ex.requestURI.query,
                ex.requestHeaders.getFirst("Accept-Encoding"), ex.requestHeaders.getFirst("If-None-Match"),
            )
            try { respond(ex) } finally { ex.close() }
        }
        server.start()
    }

    @AfterTest
    fun tearDown() = server.stop(0)

    @Test
    fun aGzippedBodyIsDecoded() {
        val text = """[{"tag_name": "v4.41.2"}] äöü"""
        respond = { ex -> send(ex, 200, gzip(text), "Content-Encoding" to "gzip") }
        val resp = assertNotNull(FirmwareHttp.getText("$base/gz", "application/json"))
        assertEquals(200, resp.status)
        assertEquals(text, resp.body)
        assertEquals("gzip", seen.single().acceptEncoding)
    }

    @Test
    fun aPlainBodyStillWorks() {
        respond = { ex -> send(ex, 200, "plain".toByteArray()) }
        assertEquals("plain", FirmwareHttp.getText("$base/plain", "application/json")?.body)
    }

    @Test
    fun a304ReusesTheKeptBody() {
        respond = { ex ->
            if (ex.requestHeaders.getFirst("If-None-Match") == "W/\"v1\"") send(ex, 304, ByteArray(0))
            else send(ex, 200, gzip("first"), "Content-Encoding" to "gzip", "ETag" to "W/\"v1\"")
        }
        val url = "$base/etag"
        val first = assertNotNull(FirmwareHttp.getText(url, "application/json"))
        assertFalse(first.notModified)
        val second = assertNotNull(FirmwareHttp.getText(url, "application/json"))
        assertTrue(second.notModified)
        assertEquals(200, second.status)
        assertEquals("first", second.body)
        assertEquals(listOf(null, "W/\"v1\""), seen.map { it.ifNoneMatch })
    }

    @Test
    fun aChangedBodyReplacesTheKeptOne() {
        var version = 1
        respond = { ex ->
            val etag = "\"v$version\""
            if (ex.requestHeaders.getFirst("If-None-Match") == etag) send(ex, 304, ByteArray(0))
            else send(ex, 200, "body $version".toByteArray(), "ETag" to etag)
        }
        val url = "$base/changing"
        assertEquals("body 1", FirmwareHttp.getText(url, "application/json")?.body)
        version = 2
        val resp = assertNotNull(FirmwareHttp.getText(url, "application/json"))
        assertFalse(resp.notModified)
        assertEquals("body 2", resp.body)
        assertTrue(FirmwareHttp.getText(url, "application/json")!!.notModified)
    }

    @Test
    fun anUnknownEncodingIsAnError() {
        assertFailsWith<IOException> { FirmwareHttp.decodeBody(byteArrayOf(1, 2, 3), "br") }
        respond = { ex -> send(ex, 200, byteArrayOf(1, 2, 3), "Content-Encoding" to "br") }
        assertNull(FirmwareHttp.getText("$base/br", "application/json"))
    }

    @Test
    fun githubAsksForTenReleasesFirst() = runBlocking {
        respond = { ex -> send(ex, 200, gzip(releases("v4.41.2" to "obelix_pvt")), "Content-Encoding" to "gzip") }
        val r = GithubFirmwareSource("coredevices/pebbleos", false, apiBase = base).resolve("obelix_pvt")
        assertEquals("v4.41.2", assertIs<FirmwareSource.Resolution.Found>(r).version)
        assertEquals(listOf("/repos/coredevices/pebbleos/releases" to "per_page=10"), seen.map { it.path to it.query })
    }

    @Test
    fun githubLooksFurtherOnlyWhenTheFirstPageHasNothingForTheBoard() = runBlocking {
        respond = { ex ->
            val body = if (ex.requestURI.query == "per_page=10") releases("v4.42.0" to "getafix_dvt2")
            else releases("v4.42.0" to "getafix_dvt2", "v4.40.1" to "obelix_pvt")
            send(ex, 200, body.toByteArray())
        }
        val source = GithubFirmwareSource("coredevices/pebbleos", false, apiBase = base)
        assertEquals("v4.40.1", assertIs<FirmwareSource.Resolution.Found>(source.resolve("obelix_pvt")).version)
        assertEquals(listOf("per_page=10", "per_page=30"), seen.map { it.query })
        seen.clear()
        assertEquals(FirmwareSource.Resolution.NoFirmware, source.resolve("snowy_dvt"))
        assertEquals(listOf("per_page=10", "per_page=30"), seen.map { it.query })
    }

    @Test
    fun githubIsUnreachableOnAnHttpError() = runBlocking {
        respond = { ex -> send(ex, 403, "rate limited".toByteArray()) }
        val r = GithubFirmwareSource("coredevices/pebbleos", false, apiBase = base).resolve("obelix_pvt")
        assertIs<FirmwareSource.Resolution.Unreachable>(r)
        assertEquals(1, seen.size)
    }

    private fun send(ex: HttpExchange, status: Int, body: ByteArray, vararg headers: Pair<String, String>) {
        headers.forEach { (k, v) -> ex.responseHeaders.add(k, v) }
        ex.sendResponseHeaders(status, if (body.isEmpty()) -1 else body.size.toLong())
        if (body.isNotEmpty()) ex.responseBody.use { it.write(body) }
    }

    private fun gzip(text: String): ByteArray =
        ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(text.toByteArray()) } }.toByteArray()

    private fun releases(vararg tagToBoard: Pair<String, String>): String = tagToBoard.joinToString(",", "[", "]") { (tag, board) ->
        """{"tag_name": "$tag", "assets": [{"name": "normal_${board}_$tag.pbz", "browser_download_url": "https://example.invalid/$tag.pbz"}]}"""
    }
}
