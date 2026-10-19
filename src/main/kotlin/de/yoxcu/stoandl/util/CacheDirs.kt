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

    /** Scratch for files that live seconds (an engine's WAV): `$XDG_RUNTIME_DIR/stoandl/<name>`, a tmpfs only
     *  this user can read, else a [dir] of the cache. Created if needed. */
    fun runtime(name: String): File {
        val xdg = System.getenv("XDG_RUNTIME_DIR")?.takeIf { it.isNotBlank() && File(it).isDirectory }
            ?: return dir(name)
        return File(xdg, "stoandl/$name").apply { mkdirs() }
    }
}
