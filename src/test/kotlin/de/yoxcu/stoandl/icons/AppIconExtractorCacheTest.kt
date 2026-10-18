package de.yoxcu.stoandl.icons

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * GetAppIcon for an app without a `.pbw` (most locker rows) used to re-list `pbw-cache` on every call,
 * and the GUI asks for every row on every rebuild. The listing is now kept until the directory changes;
 * a stale one would hide the icon of an app installed after it.
 */
class AppIconExtractorCacheTest {
    private val dir = createTempDirectory("stoandl-icons").toFile()
    private val pbwCache = File(dir, "pbw-cache").apply { mkdirs() }
    private val extractor = AppIconExtractor(dir)

    @AfterTest
    fun tearDown() { dir.deleteRecursively() }

    @Test
    fun noPbwCacheDirectoryMeansNoIcon() {
        pbwCache.deleteRecursively()
        assertNull(extractor.findCachedPbw(UUID))
        assertNull(extractor.iconPngPath(UUID))
    }

    @Test
    fun aPbwAddedOrRemovedAfterTheListingIsSeen() {
        File(pbwCache, "${OTHER}_1.0.pbw").writeText("x")
        File(pbwCache, "${UUID}.txt").writeText("x")
        assertNull(extractor.findCachedPbw(UUID))
        val pbw = File(pbwCache, "${UUID}_1.0.pbw").apply { writeText("x") }
        bumpMtime()
        assertEquals(pbw, extractor.findCachedPbw(UUID))
        pbw.delete()
        bumpMtime()
        assertNull(extractor.findCachedPbw(UUID))
        // A .pbw that can't be read gives no icon, and isn't remembered as icon-less.
        File(pbwCache, "${UUID}_1.1.pbw").writeText("not a zip")
        bumpMtime()
        assertNull(extractor.iconPngPath(UUID))
    }

    @Test
    fun theListingIsReusedWhileTheDirectoryIsUnchanged() {
        File(pbwCache, "${OTHER}_1.0.pbw").writeText("x")
        val mtime = pbwCache.lastModified()
        assertNull(extractor.findCachedPbw(UUID))
        // A file that appears without the directory's mtime moving (only possible by hand) isn't seen:
        // proof the listing was reused rather than re-read.
        File(pbwCache, "${UUID}_1.0.pbw").writeText("x")
        pbwCache.setLastModified(mtime)
        assertNull(extractor.findCachedPbw(UUID))
        bumpMtime()
        assertEquals("${UUID}_1.0.pbw", extractor.findCachedPbw(UUID)?.name)
    }

    @Test
    fun withTwoVersionsTheLaterNameWins() {
        File(pbwCache, "${UUID}_1.0.pbw").writeText("x")
        File(pbwCache, "${UUID}_1.2.pbw").writeText("x")
        assertEquals("${UUID}_1.2.pbw", extractor.findCachedPbw(UUID)?.name)
    }

    /** Move the directory's mtime on, as the file change would on a filesystem with coarse mtimes. */
    private var bumps = 0L
    private fun bumpMtime() { pbwCache.setLastModified(pbwCache.lastModified() + 2_000 * ++bumps) }

    private companion object {
        const val UUID = "0b73b76a-cd65-4dc2-9585-aaa213320858"
        const val OTHER = "11111111-2222-3333-4444-555555555555"
    }
}
