package de.yoxcu.stoandl.pebble

import io.rebble.libpebblecommon.plugin.ConfigMessageTarget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A settings page that talks to its app's plugin through the daemon (upstream's v1.15 pages, in a browser). */
class ConfigPageBridgeTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val http = HttpClient.newHttpClient()

    @AfterTest fun tearDown() { scope.cancel() }

    private class FakePlugin : ConfigMessageTarget {
        val pushes = MutableSharedFlow<String>(extraBufferCapacity = 4)
        @Volatile var opened = 0
        @Volatile var closed = 0
        override val configMessages: SharedFlow<String> = pushes
        override suspend fun openConfigSession() { opened++ }
        override suspend fun closeConfigSession() { closed++ }
        override suspend fun onConfigMessage(json: String): String? =
            if (json.contains("listTickers")) """{"tickers":["AAPL"]}""" else null
    }

    @Test
    fun `the page gets the Pebble shim, its calls reach the plugin, and ready arrives on the event stream`() {
        val plugin = FakePlugin()
        val server = ConfigPageServer(scope)
        val page = "<html><head><title>x</title></head><body>hi</body></html>"
        val url = server.open("app", "data:text/html,${java.net.URLEncoder.encode(page, Charsets.UTF_8).replace("+", "%20")}",
            ConfigPageBridge(mapOf("plugin" to ConfigScript("Stocks", plugin)), scope)) {}
        val base = url.substringBefore('?')

        val html = http.send(HttpRequest.newBuilder(URI(url)).build(), HttpResponse.BodyHandlers.ofString()).body()
        assertContains(html, "EventSource(BASE + 'events')")
        assertTrue(html.indexOf("EventSource") < html.indexOf("<title>"), "the shim comes before the page's own head")

        // The event stream starts the plugin and reports it ready.
        val events = http.sendAsync(HttpRequest.newBuilder(URI(base + "events")).build(), HttpResponse.BodyHandlers.ofLines())
        val lines = events.get(5, TimeUnit.SECONDS).body().iterator()
        var ready = false
        repeat(5) { if (!ready && lines.hasNext()) ready = lines.next().contains("\"ready\"") }
        assertTrue(ready, "no ready event")
        assertEquals(1, plugin.opened)

        val reply = http.send(
            HttpRequest.newBuilder(URI(base + "call"))
                .POST(HttpRequest.BodyPublishers.ofString("""{"id":1,"method":"sendMessage","target":"plugin","message":{"type":"listTickers"}}"""))
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        ).body()
        assertEquals("""{"tickers":["AAPL"]}""", reply)
        val unknown = http.send(
            HttpRequest.newBuilder(URI(base + "call"))
                .POST(HttpRequest.BodyPublishers.ofString("""{"id":2,"method":"sendMessage","target":"pkjs","message":{}}""")).build(),
            HttpResponse.BodyHandlers.ofString(),
        ).body()
        assertContains(unknown, "no script called 'pkjs'")
    }
}
