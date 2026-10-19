package de.yoxcu.stoandl

import java.io.File
import kotlin.test.assertEquals

/**
 * A `gui/tools/<name>` file the mock daemon serves instead of a hand copy, checked against [rendered]: the
 * daemon's real contract text. `STOANDL_UPDATE_GOLDEN=1` rewrites it (commit it in the gui submodule and bump
 * the gitlink). Skipped where the gui submodule isn't checked out (CI, the release archive) or its recorded
 * commit predates the file.
 */
fun assertGuiFixture(name: String, rendered: String) {
    val golden = File("gui/tools/$name")
    if (System.getenv("STOANDL_UPDATE_GOLDEN") == "1" && golden.parentFile.isDirectory) golden.writeText(rendered)
    if (!golden.isFile) return
    assertEquals(rendered, golden.readText(), "${golden.path} is stale: rerun the test with STOANDL_UPDATE_GOLDEN=1")
}
