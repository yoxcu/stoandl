package de.yoxcu.stoandl.util

import java.io.File
import java.nio.file.Files
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals

/** The daemon-picked output paths (screenshots, watch logs): xdg-user-dirs' file read the way the desktop
 *  reads it, and a new file never overwrites an earlier one. */
class UserDirsTest {
    @Test
    fun `user-dirs lines expand HOME and skip a disabled dir`() {
        val text = """
            # This file is written by xdg-user-dirs-update
            XDG_DESKTOP_DIR="${'$'}HOME/Desktop"
            XDG_PICTURES_DIR="${'$'}HOME/Bilder"
            XDG_DOCUMENTS_DIR="/data/docs"
            XDG_MUSIC_DIR="${'$'}HOME/"
            XDG_VIDEOS_DIR="relative/videos"
        """.trimIndent()
        assertEquals(
            mapOf(
                "XDG_DESKTOP_DIR" to "/home/u/Desktop",
                "XDG_PICTURES_DIR" to "/home/u/Bilder",
                "XDG_DOCUMENTS_DIR" to "/data/docs",
            ),
            UserDirs.parse(text, "/home/u"),
        )
    }

    @Test
    fun `a new file is numbered when the name is taken`() {
        val dir = Files.createTempDirectory("userdirs").toFile()
        try {
            val now = LocalDateTime.of(2026, 10, 19, 21, 5, 7)
            val first = UserDirs.newFile(File(dir, "Screenshots"), "pebble-screenshot", ".png", now)
            assertEquals("pebble-screenshot-20261019-210507.png", first.name)
            first.writeText("x")
            assertEquals("pebble-screenshot-20261019-210507-1.png", UserDirs.newFile(first.parentFile, "pebble-screenshot", ".png", now).name)
        } finally {
            dir.deleteRecursively()
        }
    }
}
