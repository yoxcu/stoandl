package de.yoxcu.stoandl.firmware

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A damaged firmware download must stop before the watch, so the published SHA-256 has to be read and checked. */
class FirmwareChecksumTest {

    @Test
    fun `a GitHub asset's sha256 digest is carried to the bundle`() {
        fun sha(digest: String?) = assertNotNull(GithubFirmwareSource.newestRelease(release(digest), "obelix_pvt", false)).asset.sha256
        assertEquals("ABC123", sha("sha256:ABC123"))
        // Assets uploaded before GitHub computed digests have none; another algorithm isn't ours to check.
        assertNull(sha(null))
        assertNull(sha("md5:ABC123"))
    }

    private fun release(digest: String?): String {
        val d = digest?.let { ", \"digest\": \"$it\"" }.orEmpty()
        return """[{"tag_name": "v4.41.1", "assets": [{"name": "normal_obelix_pvt_v4.41.1.pbz", """ +
            """"browser_download_url": "https://example.invalid/a.pbz"$d}]}]"""
    }

    @Test
    fun `the checksum compares the whole file, in either hex case`() {
        val file = File.createTempFile("fw-check", ".pbz").apply { deleteOnExit(); writeText("abc") }
        val abc = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
        assertTrue(sha256Matches(file, abc))
        assertTrue(sha256Matches(file, " ${abc.uppercase()}\n"))
        file.appendText("d")
        assertFalse(sha256Matches(file, abc))
    }
}
