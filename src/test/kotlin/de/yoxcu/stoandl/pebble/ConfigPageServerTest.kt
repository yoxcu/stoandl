package de.yoxcu.stoandl.pebble

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.Base64
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A wrong decode or close rewrite shows the page but silently drops Save (or mangles the settings). */
class ConfigPageServerTest {
    private val close = "http://127.0.0.1:1/t/close?"

    @Test
    fun `a percent-encoded data page keeps a literal plus`() {
        val html = "<p>a+b & ü</p>"
        val url = "data:text/html;charset=utf-8," + URLEncoder.encode(html, Charsets.UTF_8).replace("+", "%20") + "+"
        assertEquals("$html+", decodeDataUrl(url))
    }

    @Test
    fun `a base64 data page decodes`() {
        val html = "<!doctype html><p>ü</p>"
        val url = "data:text/html;base64," + Base64.getEncoder().encodeToString(html.toByteArray())
        assertEquals(html, decodeDataUrl(url))
    }

    @Test
    fun `save and cancel targets both land on the close endpoint`() {
        val page = "<head></head>location.href = 'pebblejs://close#' + x; cancel = 'pebblejs://close';"
        assertEquals(
            "<head>${autoCloseScript(close)}</head>location.href = '$close' + x; cancel = '$close';",
            rewriteConfigPage(page, "data:text/html,", close),
        )
    }

    @Test
    fun `an http page resolves relative resources against its own origin`() {
        val page = "<html><head lang=\"en\"><script src=\"app.js\"></script></head></html>"
        val out = rewriteConfigPage(page, "https://example.org/cfg/index.html?v=1#{}", close)
        val expected = "<html><head lang=\"en\">${autoCloseScript(close)}<base href=\"https://example.org/cfg/\">" +
            "<script src=\"app.js\"></script></head></html>"
        assertEquals(expected, out)
    }

    @Test
    fun `the local URL keeps the page's query and fragment and adds return_to`() {
        val base = "http://127.0.0.1:1/t/"
        val returnTo = "return_to=" + URLEncoder.encode(close, Charsets.UTF_8)
        assertEquals("$base?$returnTo", localPageUrl(base, "data:text/html,%3Cp%3E", close))
        assertEquals("$base?a=1&$returnTo#%7B%7D", localPageUrl(base, "https://example.org/c.html?a=1#%7B%7D", close))
    }

    @Test
    fun `the settings are the whole close query, decoded`() {
        val json = """{"name":"a+b c","ü":1}"""
        assertEquals(json, closeData(URLEncoder.encode(json, Charsets.UTF_8).replace("+", "%20")))
        assertEquals("", closeData(null))
        assertEquals("", closeData(""))
        assertEquals("%zz", closeData("%zz"))
    }

    @Test
    fun `serves the page, hands Save to the app once, and expires the token`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val saved = ArrayBlockingQueue<String>(2)
            val server = ConfigPageServer(scope)
            val html = "<button onclick=\"location.href='pebblejs://close#'+encodeURIComponent('{}')\">"
            val url = server.open("app", "data:text/html," + URLEncoder.encode(html, Charsets.UTF_8)) { saved.add(it) }
            assertTrue(url.startsWith("http://127.0.0.1:"))

            val http = HttpClient.newHttpClient()
            fun get(u: String) = http.send(HttpRequest.newBuilder(URI(u)).build(), HttpResponse.BodyHandlers.ofString())
            val page = get(url)
            assertEquals(200, page.statusCode())
            val closeUrl = url.substringBefore('?') + "close?"
            assertTrue(page.body().contains(closeUrl), page.body())
            assertFalse(page.body().contains("pebblejs://"))
            assertTrue(page.body().contains("navigation.addEventListener"))

            assertEquals(200, get(closeUrl + "%7B%22k%22%3A1%7D").statusCode())
            assertEquals("""{"k":1}""", saved.poll(5, TimeUnit.SECONDS))
            assertEquals(404, get(closeUrl + "%7B%7D").statusCode())
            assertTrue(saved.isEmpty())
        } finally {
            scope.cancel()
        }
    }
}
