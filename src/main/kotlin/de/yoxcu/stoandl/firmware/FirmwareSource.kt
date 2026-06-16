package de.yoxcu.stoandl.firmware

/**
 * A place to look up over-the-air firmware for a connected watch's board. There is one source per
 * firmware ecosystem — [GithubFirmwareSource] for Core devices, [CohortsFirmwareSource] for classic /
 * Rebble devices — and [FirmwareControl] picks the matching one by the watch's generation. Keeping the
 * per-source differences (URL, JSON shape, identity) behind this interface lets the check/update/notify
 * flow stay source-agnostic.
 */
interface FirmwareSource {
    /** Human-readable name of this source, shown in CLI output (e.g. `cohorts.rebble.io`). */
    val label: String

    /** Message telling the user how to enable this source, shown when its config gate is off. */
    val disabledHint: String

    /** Look up the latest firmware for [boardRevision]. Never throws — failures map to [Resolution]. */
    suspend fun resolve(boardRevision: String): Resolution

    sealed class Resolution {
        /** The source offers [bundle] (latest firmware) for the board, labelled [version]. */
        data class Found(val version: String, val bundle: FirmwareBundle) : Resolution()

        /** The source is reachable but ships nothing for this board (distinct from unreachable). */
        object NoFirmware : Resolution()

        /** We couldn't reach or read the source; [message] explains (surfaced as an `error:`). */
        data class Unreachable(val message: String) : Resolution()
    }
}
