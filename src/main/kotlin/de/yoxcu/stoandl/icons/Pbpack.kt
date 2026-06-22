package de.yoxcu.stoandl.icons

/**
 * Minimal reader for a Pebble resource pack (`app_resources.pbpack`), the binary blob inside a
 * `.pbw`'s per-platform subdir that holds an app's packed resources (images, fonts, …).
 *
 * Layout (all little-endian), verified against real `.pbw` files:
 * - Header @0: `uint32 num_files`, `uint32 crc`, `uint32 timestamp`.
 * - Table of contents @12: `num_files` entries of 16 bytes — `uint32 file_id, offset, length, crc`.
 * - Data section base is a fixed `0x100C` (= 12 + 256 × 16); a resource's bytes are
 *   `data[0x100C + entry.offset : … + entry.length]`.
 *
 * A resource's id is its 1-based position in `appinfo.json`'s `resources.media[]` list — so the
 * menu-icon's id is `media.indexOf(menuIconEntry) + 1`.
 */
object Pbpack {
    private const val DATA_SECTION_BASE = 0x100C
    private const val TOC_ENTRY_SIZE = 16
    private const val HEADER_SIZE = 12

    private fun u32(b: ByteArray, off: Int): Long =
        (b[off].toLong() and 0xFF) or
            ((b[off + 1].toLong() and 0xFF) shl 8) or
            ((b[off + 2].toLong() and 0xFF) shl 16) or
            ((b[off + 3].toLong() and 0xFF) shl 24)

    /** Extract the bytes of the resource with [resourceId] from the pbpack [data], or null if the
     *  id isn't present or the offsets fall outside the blob (corrupt/truncated pack). */
    fun resource(data: ByteArray, resourceId: Int): ByteArray? {
        if (data.size < HEADER_SIZE) return null
        val numFiles = u32(data, 0).toInt()
        if (numFiles <= 0 || numFiles > 4096) return null // sanity bound
        for (i in 0 until numFiles) {
            val entry = HEADER_SIZE + i * TOC_ENTRY_SIZE
            if (entry + TOC_ENTRY_SIZE > data.size) return null
            val fileId = u32(data, entry).toInt()
            if (fileId != resourceId) continue
            val offset = u32(data, entry + 4).toInt()
            val length = u32(data, entry + 8).toInt()
            val start = DATA_SECTION_BASE + offset
            val end = start + length
            if (length <= 0 || start < 0 || end > data.size) return null
            return data.copyOfRange(start, end)
        }
        return null
    }
}
