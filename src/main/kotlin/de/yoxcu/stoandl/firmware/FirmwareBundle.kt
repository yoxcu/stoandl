package de.yoxcu.stoandl.firmware

import java.io.File

/**
 * A source-agnostic handle to one downloadable firmware bundle. Both [GithubFirmwareSource] (Core
 * devices) and [CohortsFirmwareSource] (classic / Rebble devices) resolve a match to one of these,
 * so [FirmwareControl] flashes either the same way: show [name], [download] to a temp `.pbz`, hand
 * it to `sideloadFirmware`.
 */
interface FirmwareBundle {
    /** Display name for the bundle — the `.pbz` filename (used in status/notification text). */
    val name: String

    /** Download the bundle to a temp `.pbz` file, or null on any network/IO failure. */
    suspend fun download(): File?
}

/** A [FirmwareBundle] that downloads [url] (named [name]) via the shared [FirmwareHttp] client. */
fun firmwareBundle(name: String, url: String): FirmwareBundle = object : FirmwareBundle {
    override val name: String = name
    override suspend fun download(): File? = FirmwareHttp.downloadToTempPbz(url, name)
}
