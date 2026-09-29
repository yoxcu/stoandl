package de.yoxcu.stoandl.firmware

import io.rebble.libpebblecommon.services.FirmwareVersion

/**
 * A firmware version tag as stoandl orders them: up to four numeric parts, then an optional `-suffix`
 * (`v4.38.2`, `v4.9.142.4`, `v4.4.3-rbl`, `v4.39.0-rc1`). A missing numeric part counts as 0.
 *
 * Four parts, because PebbleOS publishes backports as `v4.9.142.1` … `v4.9.142.4`. libpebble3's
 * [FirmwareVersion] keeps only three, so there a backport compares equal to the release it patches.
 */
internal data class FirmwareTag(val parts: List<Int>, val suffix: String?) : Comparable<FirmwareTag> {

    /** Compare the numeric parts only. This decides "newer than the watch runs": a build suffix on the
     *  running version (e.g. a dev build) must not make the same release look newer. */
    fun compareNumbers(other: FirmwareTag): Int {
        for (i in 0 until PARTS) {
            val c = parts[i].compareTo(other.parts[i])
            if (c != 0) return c
        }
        return 0
    }

    /** Release order: numbers first; for equal numbers a suffixed tag (a pre-release) sorts below the
     *  plain one, and two suffixes compare as text. */
    override fun compareTo(other: FirmwareTag): Int {
        val byNumbers = compareNumbers(other)
        if (byNumbers != 0) return byNumbers
        return when {
            suffix == other.suffix -> 0
            suffix == null -> 1
            other.suffix == null -> -1
            else -> suffix.compareTo(other.suffix)
        }
    }

    companion object {
        private const val PARTS = 4
        private val TAG = Regex("""v?(\d+)\.(\d+)(?:\.(\d+))?(?:\.(\d+))?(?:-(\S+))?""")

        /** Parse [tag], or null when it holds no `major.minor` version. */
        fun parse(tag: String): FirmwareTag? {
            val m = TAG.find(tag) ?: return null
            val parts = (1..PARTS).map { m.groupValues[it].toIntOrNull() ?: 0 }
            return FirmwareTag(parts, m.groupValues[PARTS + 1].ifEmpty { null })
        }

        /** The version [running] reports, with all four parts when its tag has them. */
        fun of(running: FirmwareVersion): FirmwareTag =
            parse(running.stringVersion)
                ?: FirmwareTag(listOf(running.major, running.minor, running.patch, 0), running.suffix?.ifEmpty { null })
    }
}
