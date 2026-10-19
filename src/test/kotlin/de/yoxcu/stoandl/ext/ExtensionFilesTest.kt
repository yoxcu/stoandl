package de.yoxcu.stoandl.ext

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createSymbolicLinkPointingTo
import kotlin.io.path.exists
import kotlin.io.path.isExecutable
import kotlin.io.path.isSymbolicLink
import kotlin.io.path.readSymbolicLink
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Copying an extension tree for `stoandl ext install`, and deleting one: links stay links, never followed. */
class ExtensionFilesTest {
    private val tmp: Path = Files.createTempDirectory("ext-files")
    private val src = tmp.resolve("src").createDirectories()
    private val dest = tmp.resolve("dest")

    @AfterTest
    fun cleanup() { deleteTree(tmp.toFile()) }

    @Test
    fun `copies files and keeps the executable bit, skipping version control`() {
        src.resolve("manifest.json").writeText("{}")
        src.resolve("bin").createDirectories().resolve("ext").apply { writeText("#!/bin/sh"); toFile().setExecutable(true) }
        src.resolve(".git").createDirectories().resolve("HEAD").writeText("ref")

        assertNull(copyExtensionTree(src, dest))
        assertEquals("{}", dest.resolve("manifest.json").readText())
        assertTrue(dest.resolve("bin/ext").isExecutable())
        assertFalse(dest.resolve(".git").exists())
    }

    @Test
    fun `a link loop is copied as a link, not walked`() {
        src.resolve("a.txt").writeText("a")
        src.resolve("loop").createSymbolicLinkPointingTo(Path.of("."))

        assertNull(copyExtensionTree(src, dest))
        assertTrue(dest.resolve("loop").isSymbolicLink())
        assertEquals(Path.of("."), dest.resolve("loop").readSymbolicLink())
        assertEquals("a", dest.resolve("a.txt").readText())
    }

    @Test
    fun `a tree over the size limit fails`() {
        src.resolve("big").writeText("x".repeat(2000))
        val why = assertNotNull(copyExtensionTree(src, dest, maxBytes = 1000))
        assertTrue("more than" in why, why)
    }

    @Test
    fun `deleting a tree leaves a linked directory's contents alone`() {
        val outside = tmp.resolve("outside").createDirectories()
        outside.resolve("keep.txt").writeText("keep")
        src.resolve("link").createSymbolicLinkPointingTo(outside)

        assertTrue(deleteTree(src.toFile()))
        assertFalse(src.exists())
        assertEquals("keep", outside.resolve("keep.txt").readText())
    }
}
