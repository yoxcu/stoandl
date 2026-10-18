package de.yoxcu.stoandl.firmware

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse.BodyHandlers
import java.time.Duration
import java.util.zip.GZIPInputStream

private val log = KotlinLogging.logger {}

/** A text GET's outcome: the HTTP [status] and the decoded [body]. [notModified] when the server
 *  answered 304 to our `If-None-Match` and [body] is the copy kept from the earlier 200. */
internal data class TextResponse(val status: Int, val body: String, val notModified: Boolean = false)

/**
 * Shared HTTP for the firmware sources ([GithubFirmwareSource], [CohortsFirmwareSource]): one client
 * (one connection pool) for the daemon, a text GET, and the temp-`.pbz` download. The sources differ
 * only in their URLs and JSON shapes, so this keeps the transport in one place.
 *
 * The text GET asks for gzip (the GitHub release list is about 0.25 MB per release uncompressed and a
 * tenth of that gzipped) and revalidates with the response's `ETag`: a 304 reuses the body kept from
 * the last 200, which saves the transfer and the parse. It does not save rate limit: GitHub exempts a
 * 304 only for a request with an `Authorization` header, and these requests are unauthenticated, so
 * each one still counts against the 60/h limit (the check cache in [FirmwareControl] is what keeps
 * the count down). `java.net.http` neither adds `Accept-Encoding` nor decodes it, so both happen here.
 */
internal object FirmwareHttp {
    private const val USER_AGENT = "stoandl-firmware-updater"

    /** How many URLs keep a body for revalidation. The sources use one or two each. */
    private const val MAX_VALIDATED = 8

    private val client = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .connectTimeout(Duration.ofSeconds(15))
        .build()

    /** The last 200 per URL that carried an `ETag`, kept as received (still gzipped) to stay small. */
    private class Validated(val etag: String, val raw: ByteArray, val encoding: String?)

    private val validated = object : LinkedHashMap<String, Validated>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Validated>?) = size > MAX_VALIDATED
    }

    /** GET [url] as text with the given [accept] header. Returns the response (inspect its status),
     *  or null if the request itself failed (network/bad-URL/undecodable body/exception). */
    fun getText(url: String, accept: String): TextResponse? = try {
        val kept = synchronized(validated) { validated[url] }
        val req = HttpRequest.newBuilder(URI.create(url))
            .header("User-Agent", USER_AGENT)
            .header("Accept", accept)
            .header("Accept-Encoding", "gzip")
            .apply { kept?.let { header("If-None-Match", it.etag) } }
            .timeout(Duration.ofSeconds(30))
            .GET()
            .build()
        val resp = client.send(req, BodyHandlers.ofByteArray())
        val status = resp.statusCode()
        val encoding = resp.headers().firstValue("Content-Encoding").orElse(null)
        when {
            status == 304 && kept != null -> {
                log.debug { "Not modified: $url" }
                TextResponse(200, decodeBody(kept.raw, kept.encoding), notModified = true)
            }
            status == 200 -> {
                val body = decodeBody(resp.body(), encoding)
                val etag = resp.headers().firstValue("ETag").orElse(null)
                synchronized(validated) {
                    if (etag != null) validated[url] = Validated(etag, resp.body(), encoding) else validated.remove(url)
                }
                TextResponse(status, body)
            }
            // An error body is only ever logged; don't fail the request over its encoding.
            else -> TextResponse(status, runCatching { decodeBody(resp.body(), encoding) }.getOrDefault(""))
        }
    } catch (e: Exception) {
        log.warn(e) { "GET failed: $url" }
        null
    }

    /** Decode a response body by its `Content-Encoding` (only gzip is ever asked for) as UTF-8. */
    internal fun decodeBody(raw: ByteArray, encoding: String?): String {
        val bytes = when (encoding?.trim()?.lowercase()) {
            null, "", "identity" -> raw
            "gzip", "x-gzip" -> GZIPInputStream(raw.inputStream()).use { it.readBytes() }
            else -> throw IOException("Unsupported Content-Encoding: $encoding")
        }
        return bytes.decodeToString()
    }

    /** Download [url] to a temp `.pbz` file (logged as [label]). Throws [FirmwareDownloadException]. */
    suspend fun downloadToTempPbz(url: String, label: String): File = withContext(Dispatchers.IO) {
        val out = File.createTempFile("stoandl-fw-", ".pbz")
        out.deleteOnExit()
        val status = try {
            val req = HttpRequest.newBuilder(URI.create(url))
                .header("User-Agent", USER_AGENT)
                .timeout(Duration.ofMinutes(2))
                .GET()
                .build()
            client.send(req, BodyHandlers.ofFile(out.toPath())).statusCode()
        } catch (e: Exception) {
            log.warn(e) { "Failed to download firmware from $url" }
            out.delete()
            throw FirmwareDownloadException("Couldn't download $label: ${e.message ?: e::class.simpleName}")
        }
        if (status !in 200..299) {
            log.warn { "Firmware download failed: HTTP $status for $url" }
            out.delete()
            throw FirmwareDownloadException("Couldn't download $label: HTTP $status")
        }
        log.info { "Downloaded $label (${out.length()} bytes)" }
        out
    }
}
