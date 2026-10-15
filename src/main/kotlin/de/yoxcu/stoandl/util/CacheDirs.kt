package de.yoxcu.stoandl.util

import java.io.File

/** Where stoandl keeps regenerable data: `$XDG_CACHE_HOME/stoandl` (default `~/.cache/stoandl`). Nothing
 *  here is in backups or the support bundle, and deleting it loses nothing but time. */
object CacheDirs {
    fun root(): File {
        val xdg = System.getenv("XDG_CACHE_HOME")?.takeIf { it.isNotBlank() }
        return File(xdg ?: (System.getProperty("user.home") + "/.cache"), "stoandl")
    }

    /** A subdirectory of [root], created if needed. */
    fun dir(name: String): File = File(root(), name).apply { mkdirs() }
}
