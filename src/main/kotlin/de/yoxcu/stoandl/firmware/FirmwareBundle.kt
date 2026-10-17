package de.yoxcu.stoandl.firmware

import java.io.File
import java.security.MessageDigest

/**
 * A source-agnostic handle to one downloadable firmware bundle. Both [GithubFirmwareSource] (Core
 * devices) and [CohortsFirmwareSource] (classic / Rebble devices) resolve a match to one of these,
 * so [FirmwareControl] flashes either the same way: show [name], [download] to a temp `.pbz`, hand
 * it to `sideloadFirmware`.
 */
interface FirmwareBundle {
    /** Display name for the bundle — the `.pbz` filename (used in status/notification text). */
    val name: String

    /** Download the bundle to a temp `.pbz` file. Throws [FirmwareDownloadException] saying why not. */
    suspend fun download(): File
}

/** A firmware download that failed; the message is shown to the user as the update's failure. */
class FirmwareDownloadException(message: String) : Exception(message)

/**
 * A [FirmwareBundle] that downloads [url] (named [name]) via the shared [FirmwareHttp] client and, when
 * the source publishes one, checks it against [sha256] (hex) before anything reaches the watch.
 */
fun firmwareBundle(name: String, url: String, sha256: String?): FirmwareBundle = object : FirmwareBundle {
    override val name: String = name
    override suspend fun download(): File = FirmwareHttp.downloadToTempPbz(url, name).also { file ->
        if (sha256 != null && !sha256Matches(file, sha256)) {
            file.delete()
            throw FirmwareDownloadException("$name doesn't match its published SHA-256; the download is damaged")
        }
    }
}

internal fun sha256Matches(file: File, expectedHex: String): Boolean {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            digest.update(buf, 0, n)
        }
    }
    return digest.digest().toHexString().equals(expectedHex.trim(), ignoreCase = true)
}
