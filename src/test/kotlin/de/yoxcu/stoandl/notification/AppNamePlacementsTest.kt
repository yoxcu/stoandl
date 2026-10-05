package de.yoxcu.stoandl.notification

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull

/** The per-app app-name overrides file: what one daemon run writes, the next must read back the same. */
class AppNamePlacementsTest {
    private val dir: File = Files.createTempDirectory("stoandl-appnames").toFile()
    private val file = File(dir, "notification-app-name")

    @AfterTest
    fun cleanup() {
        dir.deleteRecursively()
    }

    @Test
    fun `overrides survive a restart`() {
        val store = AppNamePlacements(file)
        store.set("NeoChat", AppNamePlacement.SMALL)
        store.set("Network Management", AppNamePlacement.OFF)
        store.set("Matrix", AppNamePlacement.SUBTITLE)
        val reloaded = AppNamePlacements(file)
        assertEquals(AppNamePlacement.SMALL, reloaded.get("NeoChat"))
        assertEquals(AppNamePlacement.OFF, reloaded.get("Network Management"))
        assertEquals(AppNamePlacement.SUBTITLE, reloaded.get("Matrix"))
        // Keyed by the exact tracked name.
        assertNull(reloaded.get("neochat"))
    }

    @Test
    fun `setting null drops the override`() {
        val store = AppNamePlacements(file)
        store.set("NeoChat", AppNamePlacement.OFF)
        store.set("Matrix", AppNamePlacement.SMALL)
        store.set("NeoChat", null)
        assertNull(store.get("NeoChat"))
        assertNull(AppNamePlacements(file).get("NeoChat"))
        assertEquals(AppNamePlacement.SMALL, AppNamePlacements(file).get("Matrix"))
        store.set("Matrix", null)
        assertEquals("", file.readText())
    }

    @Test
    fun `a name starting with a hash survives a restart`() {
        val store = AppNamePlacements(file)
        store.set("#dev", AppNamePlacement.OFF)
        store.set(" #x", AppNamePlacement.SUBTITLE)
        val reloaded = AppNamePlacements(file)
        assertEquals(AppNamePlacement.OFF, reloaded.get("#dev"))
        assertEquals(AppNamePlacement.SUBTITLE, reloaded.get(" #x"))
    }

    @Test
    fun `nothing is written until something changes`() {
        val store = AppNamePlacements(file)
        store.set("NeoChat", null)
        assertFalse(file.exists())
    }

    @Test
    fun `blank, tab-less, malformed and unknown lines are skipped`() {
        file.writeText(
            listOf(
                "",
                "# a comment",
                "no tab here",
                "\tsmall",
                "Element\thuge",
                "My App\t Subtitle ",
                "Firefox\toff",
            ).joinToString("\n") + "\n",
        )
        val store = AppNamePlacements(file)
        assertEquals(AppNamePlacement.SUBTITLE, store.get("My App"))
        assertEquals(AppNamePlacement.OFF, store.get("Firefox"))
        assertNull(store.get("Element"))
        assertNull(store.get("no tab here"))
        assertNull(store.get(""))
    }

    @Test
    fun `an app name the file can't hold is refused`() {
        val store = AppNamePlacements(file)
        assertFailsWith<IllegalArgumentException> { store.set("bad\tname", AppNamePlacement.OFF) }
        assertFailsWith<IllegalArgumentException> { store.set("bad\nname", AppNamePlacement.OFF) }
        assertFailsWith<IllegalArgumentException> { store.set("", AppNamePlacement.OFF) }
        assertFalse(file.exists())
    }
}
