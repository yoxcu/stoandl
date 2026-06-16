package de.yoxcu.stoandl.firmware

import de.yoxcu.stoandl.util.LenientJson
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.net.URLEncoder

private val log = KotlinLogging.logger {}

/**
 * Firmware source for classic / Rebble-served Pebbles (original Pebble, Pebble Time / Time Steel, Time
 * Round, Pebble 2) — the counterpart to [GithubFirmwareSource] for boards whose firmware never shipped
 * to the PebbleOS GitHub releases.
 *
 * Contract (the same one the classic Pebble app used against `cohorts.getpebble.com`):
 *
 *     GET <baseUrl>/cohort?hardware=<board>&select=fw
 *       200 → { "fw": { "normal": { "url", "friendlyVersion", "sha-256", "timestamp", "notes" } } }
 *       400 → no firmware row exists for that hardware
 *
 * `<board>` is the watch's [io.rebble.libpebblecommon.metadata.WatchHardwarePlatform.revision]
 * (e.g. `snowy_dvt`, `v2_0`, `spalding`, `silk`). The service returns the latest row per kind, so we
 * read `fw.normal` and let [FirmwareControl] compare its version against the running firmware.
 *
 * Opt-in egress (gated by `firmware.cohorts`). Network/JSON failures map to
 * [FirmwareSource.Resolution.Unreachable] rather than throwing.
 */
class CohortsFirmwareSource(
    /** Base URL of the cohorts service, no trailing slash (default `https://cohorts.rebble.io`). */
    private val baseUrl: String,
) : FirmwareSource {
    override val label: String = "cohorts.rebble.io"
    override val disabledHint: String =
        "Rebble cohorts firmware updates are off (set firmware.cohorts = true in stoandl.conf)"

    override suspend fun resolve(boardRevision: String): FirmwareSource.Resolution =
        withContext(Dispatchers.IO) {
            val hw = URLEncoder.encode(boardRevision, "UTF-8")
            val url = "$baseUrl/cohort?hardware=$hw&select=fw"
            val resp = FirmwareHttp.getText(url, "application/json")
                ?: return@withContext FirmwareSource.Resolution.Unreachable(
                    "Couldn't reach Rebble cohorts ($baseUrl)"
                )
            when {
                // 400 is the service's "no firmware row for this hardware" signal, not an error.
                resp.statusCode() == 400 -> FirmwareSource.Resolution.NoFirmware
                resp.statusCode() !in 200..299 ->
                    FirmwareSource.Resolution.Unreachable("Rebble cohorts HTTP ${resp.statusCode()}")
                else -> try {
                    val normal = LenientJson.decodeFromString<CohortResponse>(resp.body()).fw?.normal
                    if (normal == null || normal.url.isBlank()) {
                        FirmwareSource.Resolution.NoFirmware
                    } else {
                        val name = normal.url.substringAfterLast('/').substringBefore('?')
                            .ifBlank { "firmware.pbz" }
                        // friendlyVersion is normally present (e.g. "v4.4.3-rbl"); if a row omits it,
                        // we still surface the build — FirmwareControl offers it rather than hide it.
                        FirmwareSource.Resolution.Found(
                            normal.friendlyVersion.ifBlank { "(unknown)" },
                            firmwareBundle(name, normal.url),
                        )
                    }
                } catch (e: Exception) {
                    log.warn(e) { "Failed to parse cohorts response for board '$boardRevision'" }
                    FirmwareSource.Resolution.Unreachable("Couldn't parse Rebble cohorts response")
                }
            }
        }

    @Serializable
    private data class CohortResponse(val fw: Fw? = null)

    @Serializable
    private data class Fw(val normal: FwEntry? = null)

    @Serializable
    private data class FwEntry(
        val url: String = "",
        @SerialName("friendlyVersion") val friendlyVersion: String = "",
    )
}
