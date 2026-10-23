package de.yoxcu.stoandl.pebble

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import de.yoxcu.stoandl.util.LOOPBACK_V4
import de.yoxcu.stoandl.util.decodeDataUri
import de.yoxcu.stoandl.util.loopbackOrigin
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.net.InetSocketAddress
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.SecureRandom
import java.time.Duration
import java.util.HexFormat
import java.util.concurrent.ConcurrentHashMap

private val log = KotlinLogging.logger {}

/** Largest page→script message read. */
private const val MAX_CALL_BYTES = 1 shl 20

/**
 * Serves PKJS config pages to an ordinary browser and carries Save back to the app.
 *
 * A config page "closes" by navigating to `pebblejs://close#<settings>`, a scheme only the official
 * phone app's webview understands; browsers refuse it, and Clay pages arrive as a `data:` URL that
 * xdg-open won't hand a browser. So the daemon serves each page from loopback under a random token,
 * with that close target rewritten to its own `/close?` endpoint, and returns the local URL from
 * `OpenConfig`. The CLI and both GUIs just open it. Save arrives as a plain GET; [open]'s `onClose`
 * hands the settings to the app and the session ends.
 *
 * The server starts with the first session and stops with the last. Sessions end on close, on a
 * newer [open] for the same app, or after [ttlMs].
 */
class ConfigPageServer(private val scope: CoroutineScope, private val ttlMs: Long = 60 * 60_000L) {
    private class Session(
        val app: String,
        val sourceUrl: String,
        val onClose: (String) -> Unit,
        val expiry: Job,
        /** A settings page that talks to the app's scripts (plugins on), or null. */
        val bridge: ConfigPageBridge?,
    )

    private val sessions = ConcurrentHashMap<String, Session>()
    private var server: HttpServer? = null
    private val random = SecureRandom()
    private val http: HttpClient by lazy {
        HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(Duration.ofSeconds(15)).build()
    }

    /** Serve [sourceUrl] (the app's `data:` or `http(s):` config URL) for [app] and return the local URL
     *  to open. [onClose] gets the settings the page returned, or "" if it closed without any. */
    @Synchronized
    fun open(app: String, sourceUrl: String, bridge: ConfigPageBridge? = null, onClose: (String) -> Unit): String {
        sessions.entries.filter { it.value.app == app }.forEach { end(it.key) }
        val port = (server ?: startServer()).address.port
        val token = HexFormat.of().formatHex(ByteArray(16).also(random::nextBytes))
        val expiry = scope.launch {
            delay(ttlMs)
            if (end(token) != null) log.info { "Config page for $app expired unsaved" }
        }
        sessions[token] = Session(app, sourceUrl, onClose, expiry, bridge?.takeUnless { it.isEmpty })
        val base = "${loopbackOrigin(port)}/$token/"
        return localPageUrl(base, sourceUrl, closeUrl(base))
    }

    @Synchronized
    private fun end(token: String): Session? {
        val session = sessions.remove(token) ?: return null
        session.expiry.cancel()
        session.bridge?.close()
        // Stop off this thread and a moment later: a Save's own reply is still on its way out.
        if (sessions.isEmpty()) scope.launch { delay(1_000); stopIfIdle() }
        return session
    }

    @Synchronized
    private fun stopIfIdle() {
        if (sessions.isNotEmpty()) return
        server?.stop(0)
        server = null
    }

    private fun startServer(): HttpServer {
        // Loopback only: the page and its Save carry the user's settings, and the token keeps other
        // local users and websites from guessing a session.
        val s = HttpServer.create(InetSocketAddress(LOOPBACK_V4, 0), 0)
        s.createContext("/") { exchange -> exchange.use { handle(it) } }
        // One thread per request: a page's event stream stays open while it is shown.
        s.executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()
        s.start()
        server = s
        log.debug { "Config page server listening on ${loopbackOrigin(s.address.port)}" }
        return s
    }

    private fun handle(exchange: HttpExchange) {
        // Path is /<token>/ (the page), /<token>/close (Save/Cancel), and for a page that talks to the app's
        // scripts POST /<token>/call and GET /<token>/events.
        val parts = exchange.requestURI.rawPath.trim('/').split('/')
        val token = parts.first()
        val session = sessions[token] ?: return exchange.reply(404, "This settings page has expired. Open it again.")
        val bridge = session.bridge
        if (exchange.requestMethod == "POST" && parts.drop(1) == listOf("call") && bridge != null) {
            val body = exchange.requestBody.readNBytes(MAX_CALL_BYTES).toString(Charsets.UTF_8)
            return exchange.replyJson(bridge.call(body))
        }
        if (exchange.requestMethod != "GET") return exchange.sendResponseHeaders(405, -1)
        if (parts.drop(1) == listOf("events") && bridge != null) return streamEvents(exchange, bridge)
        when (parts.drop(1)) {
            emptyList<String>() -> {
                val page = try {
                    fetchPage(session.sourceUrl)
                } catch (e: Exception) {
                    log.warn { "Config page for ${session.app}: can't load ${describeConfigUrl(session.sourceUrl)}: ${e.message}" }
                    return exchange.reply(502, "Couldn't load the settings page: ${e.message}")
                }
                val base = "${loopbackOrigin(exchange.localAddress.port)}/$token/"
                exchange.reply(200, rewriteConfigPage(page, session.sourceUrl, closeUrl(base), bridge?.let { configPageShim(base) }), html = true)
            }
            listOf("close") -> {
                val ended = end(token) ?: return exchange.reply(404, "This settings page has already closed.")
                val settings = closeData(exchange.requestURI.rawQuery)
                log.info { "Config page for ${ended.app} closed (${if (settings.isEmpty()) "no settings" else "settings saved"})" }
                exchange.reply(200, if (settings.isEmpty()) "Closed without saving." else "Settings saved — you can close this tab.")
                ended.onClose(settings)
            }
            else -> exchange.reply(404, "Not found")
        }
    }

    private fun fetchPage(sourceUrl: String): String =
        if (sourceUrl.startsWith("data:")) {
            decodeDataUrl(sourceUrl)
        } else {
            val response = http.send(
                HttpRequest.newBuilder(URI(sourceUrl.substringBefore('#'))).timeout(Duration.ofSeconds(30)).build(),
                HttpResponse.BodyHandlers.ofString(),
            )
            check(response.statusCode() in 200..299) { "HTTP ${response.statusCode()}" }
            response.body()
        }

    /** Server-sent events for a page's `ready`/`message` until the page goes away or the session ends. */
    private fun streamEvents(exchange: HttpExchange, bridge: ConfigPageBridge) {
        exchange.responseHeaders.add("Content-Type", "text/event-stream")
        exchange.responseHeaders.add("Cache-Control", "no-store")
        exchange.sendResponseHeaders(200, 0)
        val out = exchange.responseBody
        val lock = Any()
        val gone = java.util.concurrent.CountDownLatch(1)
        fun send(line: String) = synchronized(lock) {
            try { out.write("data: $line\n\n".toByteArray()); out.flush() } catch (e: java.io.IOException) { gone.countDown() }
        }
        val detach = bridge.attach(::send)
        try {
            // A comment line now and then notices a closed tab (the write fails) and keeps proxies quiet.
            while (!gone.await(15, java.util.concurrent.TimeUnit.SECONDS)) {
                synchronized(lock) { try { out.write(": \n\n".toByteArray()); out.flush() } catch (e: java.io.IOException) { gone.countDown() } }
            }
        } finally {
            detach()
        }
    }

    private fun HttpExchange.replyJson(json: String) {
        val bytes = json.toByteArray(Charsets.UTF_8)
        responseHeaders.add("Content-Type", "application/json; charset=utf-8")
        responseHeaders.add("Cache-Control", "no-store")
        sendResponseHeaders(200, bytes.size.toLong())
        responseBody.write(bytes)
    }

    private fun HttpExchange.reply(status: Int, body: String, html: Boolean = false) {
        val text = if (html) body else "<!doctype html><meta charset=\"utf-8\"><title>stoandl</title>" +
            "<body style=\"font-family:sans-serif;padding:2em\">${body.replace("&", "&amp;").replace("<", "&lt;")}</body>"
        val bytes = text.toByteArray(Charsets.UTF_8)
        responseHeaders.add("Content-Type", "text/html; charset=utf-8")
        responseHeaders.add("Cache-Control", "no-store")
        sendResponseHeaders(status, bytes.size.toLong())
        responseBody.write(bytes)
    }
}

/** The local endpoint a page's Save/Cancel lands on. It ends in `?` so the settings, appended as
 *  `encodeURIComponent(json)`, become the whole query string, which the browser sends (a `#`
 *  fragment it wouldn't). */
internal fun closeUrl(base: String) = "${base}close?"

/** The URL to open: [base], plus the source URL's own query and fragment (pages read their current
 *  settings from `location.search`/`location.hash`), plus `return_to` (the SDK emulator's convention
 *  for pages that build the close URL themselves). */
internal fun localPageUrl(base: String, sourceUrl: String, closeUrl: String): String {
    val returnTo = "return_to=" + URLEncoder.encode(closeUrl, Charsets.UTF_8)
    if (sourceUrl.startsWith("data:")) return "$base?$returnTo"
    val fragment = sourceUrl.substringAfter('#', "")
    val query = sourceUrl.substringBefore('#').substringAfter('?', "")
    return base + "?" + (if (query.isEmpty()) returnTo else "$query&$returnTo") + (if (fragment.isEmpty()) "" else "#$fragment")
}

/** Point the page's close target at [closeUrl]. `pebblejs://close#` carries settings; a bare
 *  `pebblejs://close` is Cancel. The page also gets [autoCloseScript] first in its `<head>`, and
 *  http(s) pages a `<base>` so relative resources resolve against their real origin (`data:` pages
 *  inline everything). */
internal fun rewriteConfigPage(page: String, sourceUrl: String, closeUrl: String, shim: String? = null): String {
    val rewritten = page.replace("pebblejs://close#", closeUrl).replace("pebblejs://close", closeUrl)
    // The Pebble API shim must run before any of the page's own scripts.
    var headStart = shim.orEmpty() + autoCloseScript(closeUrl)
    if (!sourceUrl.startsWith("data:"))
        headStart += "<base href=\"${sourceUrl.substringBefore('#').substringBefore('?').substringBeforeLast('/')}/\">"
    val head = Regex("<head[^>]*>", RegexOption.IGNORE_CASE).find(rewritten)
        ?: return headStart + rewritten
    return rewritten.substring(0, head.range.last + 1) + headStart + rewritten.substring(head.range.last + 1)
}

/** Close the tab on Save/Cancel. A browser lets a page close only a tab with one history entry, and
 *  navigating to [closeUrl] would add a second; so the script catches that navigation (Navigation
 *  API), makes the same request with `fetch` instead, and closes the tab. If the tab won't close
 *  (it had history before) it shows the server's reply in place. Without the Navigation API, or if
 *  the fetch fails, the page navigates as it would have, and the user closes the tab. */
internal fun autoCloseScript(closeUrl: String): String = """<script>(function () {
var c = "$closeUrl", sent = false;
if (!window.navigation) return;
navigation.addEventListener("navigate", function (e) {
  var u = e.destination.url;
  if (sent || u.indexOf(c) !== 0 || !e.cancelable) return;
  sent = true;
  e.preventDefault();
  fetch(u).then(function (r) { return r.text(); }).then(function (h) {
    window.close();
    setTimeout(function () { document.open(); document.write(h); document.close(); }, 300);
  }, function () { location.href = u; });
});
})();</script>"""

/** The body of a `data:` URL as text (the page's HTML): see [decodeDataUri]. */
internal fun decodeDataUrl(url: String): String = decodeDataUri(url).bytes.toString(Charsets.UTF_8)

/** The settings a page sent to [closeUrl]: the whole query, `encodeURIComponent`-decoded. A query
 *  that isn't valid percent-encoding is passed on as it came. */
internal fun closeData(rawQuery: String?): String {
    if (rawQuery.isNullOrEmpty()) return ""
    return try { percentDecode(rawQuery) } catch (_: IllegalArgumentException) { rawQuery }
}

private fun percentDecode(s: String): String = URLDecoder.decode(s.replace("+", "%2B"), Charsets.UTF_8)

/** A config URL for the log: the address, or just the kind and size of a `data:` page (those run to
 *  megabytes). */
internal fun describeConfigUrl(url: String): String =
    if (url.startsWith("data:")) "${url.substringBefore(',')} page (${url.length / 1024} KiB)" else url.substringBefore('?')
