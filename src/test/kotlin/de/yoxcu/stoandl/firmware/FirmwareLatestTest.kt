package de.yoxcu.stoandl.firmware

import io.rebble.libpebblecommon.services.FirmwareVersion
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * Which release the online update offers. PebbleOS publishes backports (v4.27.3, v4.30.3, v4.9.142.4)
 * after newer releases, so GitHub's "latest" flag can point at one of them. A watch in recovery (PRF)
 * is offered whatever the source resolves, so it would have flashed the backport.
 */
class FirmwareLatestTest {

    @Test
    fun `tags order by all four numeric parts, then a pre-release below its release`() {
        val ordered = listOf("v4.9.142", "v4.9.142.3", "v4.9.142.4", "v4.27.3", "v4.38.2", "v4.39.0-rc1", "v4.39.0")
            .map { assertNotNull(FirmwareTag.parse(it), it) }
        assertEquals(ordered, ordered.shuffled(java.util.Random(7)).sorted())
        assertEquals(FirmwareTag(listOf(4, 38, 0, 0), null), FirmwareTag.parse("v4.38"))
        assertEquals(FirmwareTag(listOf(4, 4, 3, 0), "rbl"), FirmwareTag.parse("v4.4.3-rbl"))
        assertNull(FirmwareTag.parse("nightly"))
    }

    @Test
    fun `the fourth part of a backport counts when comparing with the watch`() {
        // The old three-part parse read v4.9.142.4 as v4.9.142, so a watch on .3 was never offered .4.
        assertTrue(FirmwareControl.needsUpdate("v4.9.142.4", running("v4.9.142.3")))
        assertFalse(FirmwareControl.needsUpdate("v4.9.142.4", running("v4.9.142.4")))
        assertFalse(FirmwareControl.needsUpdate("v4.30.3", running("v4.38.2")))
        assertTrue(FirmwareControl.needsUpdate("v4.38.2", running("v4.30.3")))
        // A dev build's suffix doesn't make its own release look newer.
        assertFalse(FirmwareControl.needsUpdate("v4.38.2", running("v4.38.2-12-gdeadbee")))
    }

    @Test
    fun `latest is the highest release with a bundle for the board, not the last published`() {
        val picked = assertNotNull(GithubFirmwareSource.newestRelease(RELEASES, "obelix_pvt", includePrereleases = false))
        assertEquals("v4.38.2", picked.tag)
        assertEquals("normal_obelix_pvt_v4.38.2.pbz", picked.asset.name)
        assertEquals("https://example.invalid/normal_obelix_pvt_v4.38.2.pbz", picked.asset.url)
    }

    @Test
    fun `a watch in recovery is offered the highest release, not the backport`() {
        val prf = running("v4.9.9", recovery = true)
        val picked = assertNotNull(GithubFirmwareSource.newestRelease(RELEASES, "obelix_pvt", includePrereleases = false))
        assertTrue(FirmwareControl.needsUpdate(picked.tag, prf))
        assertEquals("v4.38.2", picked.tag)
    }

    @Test
    fun `pre-releases count only when enabled, drafts never`() {
        val picked = GithubFirmwareSource.newestRelease(RELEASES, "obelix_pvt", includePrereleases = true)
        assertEquals("v4.39.0-rc1", picked?.tag)
    }

    @Test
    fun `releases without a normal bundle for the board are skipped`() {
        // v4.40.0 ships only getafix; the asterix-only backport is the newest one for asterix.
        assertEquals("v4.9.142.4", GithubFirmwareSource.newestRelease(RELEASES, "asterix", false)?.tag)
        assertNull(GithubFirmwareSource.newestRelease(RELEASES, "snowy_dvt", false))
    }

    @Test
    fun `unversioned tags are used only when no versioned release qualifies`() {
        val body = """[
            ${release("nightly-2026-09-02", "normal_obelix_pvt_nightly.pbz")},
            ${release("nightly-2026-09-01", "normal_obelix_pvt_nightly.pbz")}
        ]"""
        assertEquals("nightly-2026-09-02", GithubFirmwareSource.newestRelease(body, "obelix_pvt", false)?.tag)
        val mixed = "[${release("nightly-2026-09-02", "normal_obelix_pvt_nightly.pbz")}, " +
            "${release("v4.38.2", "normal_obelix_pvt_v4.38.2.pbz")}]"
        assertEquals("v4.38.2", GithubFirmwareSource.newestRelease(mixed, "obelix_pvt", false)?.tag)
    }

    private companion object {
        fun running(tag: String, recovery: Boolean = false): FirmwareVersion = FirmwareVersion.from(
            tag, isRecovery = recovery, gitHash = "", timestamp = Instant.DISTANT_PAST,
            isDualSlot = true, isSlot0 = true,
        )!!

        fun release(tag: String, vararg assets: String, draft: Boolean = false, prerelease: Boolean = false) =
            """{"tag_name": "$tag", "draft": $draft, "prerelease": $prerelease, "assets": [""" +
                assets.joinToString(", ") {
                    """{"name": "$it", "browser_download_url": "https://example.invalid/$it"}"""
                } + "]}"

        /** Newest-published first, as the API lists them: the September backports came out last. */
        val RELEASES = listOf(
            release("v4.40.0", "normal_getafix_dvt2_v4.40.0.pbz"),
            release("v4.39.1", "normal_obelix_pvt_v4.39.1.pbz", draft = true),
            release("v4.39.0-rc1", "normal_obelix_pvt_v4.39.0-rc1.pbz", prerelease = true),
            release("v4.27.3", "normal_obelix_pvt_v4.27.3.pbz"),
            release("v4.30.3", "normal_obelix_pvt_v4.30.3.pbz"),
            release("v4.9.142.4", "normal_asterix_v4.9.142.4.pbz"),
            release(
                "v4.38.2",
                "normal_obelix_pvt_v4.38.2_slot0.pbz",
                "normal_obelix_pvt_v4.38.2.pbz",
                "recovery_obelix_pvt_v4.38.2.pbz",
            ),
            release("v4.38.1", "normal_obelix_pvt_v4.38.1.pbz"),
        ).joinToString(",\n", "[\n", "\n]")
    }
}
