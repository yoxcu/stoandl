package de.yoxcu.stoandl.firmware

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpResponse.BodyHandlers
import java.time.Duration

private val log = KotlinLogging.logger {}

/**
 * Shared HTTP for the firmware sources ([GithubFirmwareSource], [CohortsFirmwareSource]): one client
 * (one connection pool) for the daemon, a text GET, and the temp-`.pbz` download. The sources differ
 * only in their URLs and JSON shapes, so this keeps the transport in one place.
 */
internal object FirmwareHttp {
    private const val USER_AGENT = "stoandl-firmware-updater"

    private val client = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .connectTimeout(Duration.ofSeconds(15))
        .build()

    /** GET [url] as text with the given [accept] header. Returns the response (inspect its status),
     *  or null if the request itself failed (network/bad-URL/exception). */
    fun getText(url: String, accept: String): HttpResponse<String>? = try {
        val req = HttpRequest.newBuilder(URI.create(url))
            .header("User-Agent", USER_AGENT)
            .header("Accept", accept)
            .timeout(Duration.ofSeconds(30))
            .GET()
            .build()
        client.send(req, BodyHandlers.ofString())
    } catch (e: Exception) {
        log.warn(e) { "GET failed: $url" }
        null
    }

    /** Download [url] to a temp `.pbz` file (logged as [label]). Returns the file, or null on failure. */
    suspend fun downloadToTempPbz(url: String, label: String): File? = withContext(Dispatchers.IO) {
        val out = File.createTempFile("stoandl-fw-", ".pbz")
        out.deleteOnExit()
        try {
            val req = HttpRequest.newBuilder(URI.create(url))
                .header("User-Agent", USER_AGENT)
                .timeout(Duration.ofMinutes(2))
                .GET()
                .build()
            val resp = client.send(req, BodyHandlers.ofFile(out.toPath()))
            if (resp.statusCode() !in 200..299) {
                log.warn { "Firmware download failed: HTTP ${resp.statusCode()} for $url" }
                out.delete()
                null
            } else {
                log.info { "Downloaded $label (${out.length()} bytes)" }
                out
            }
        } catch (e: Exception) {
            log.warn(e) { "Failed to download firmware from $url" }
            out.delete()
            null
        }
    }
}
