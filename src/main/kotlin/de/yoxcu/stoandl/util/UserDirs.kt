package de.yoxcu.stoandl.util

import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Where files the user asked for land when the caller names no path: a watch screenshot in the user's
 * Pictures/Screenshots, watch logs and core dumps in Documents/stoandl. The daemon picks them, not the
 * client, because a Flatpak GUI's /tmp and home differ from the host's; it hands the path back.
 */
object UserDirs {
    /** `XDG_<key>_DIR` (PICTURES, DOCUMENTS, …) from `$XDG_CONFIG_HOME/user-dirs.dirs`, else [fallback]
     *  under the home directory (xdg-user-dirs' own default names). */
    fun dir(key: String, fallback: String): File {
        val home = System.getProperty("user.home")
        val configHome = System.getenv("XDG_CONFIG_HOME")?.takeIf { it.isNotBlank() } ?: "$home/.config"
        val text = runCatching { File(configHome, "user-dirs.dirs").readText() }.getOrNull()
        return File(text?.let { parse(it, home)["XDG_${key}_DIR"] } ?: "$home/$fallback")
    }

    /** The `XDG_*_DIR="…"` lines of a user-dirs.dirs file, with a leading `$HOME` expanded. Other lines,
     *  and a value that is the bare home directory (xdg-user-dirs' "disabled"), are left out. */
    internal fun parse(text: String, home: String): Map<String, String> =
        text.lineSequence().mapNotNull { line ->
            val m = USER_DIR_LINE.matchEntire(line.trim()) ?: return@mapNotNull null
            val raw = m.groupValues[2]
            val path = if (raw.startsWith("\$HOME")) home + raw.removePrefix("\$HOME") else raw
            if (!path.startsWith("/") || path.trimEnd('/') == home.trimEnd('/')) null else m.groupValues[1] to path
        }.toMap()

    private val USER_DIR_LINE = Regex("""(XDG_[A-Z]+_DIR)="([^"]*)"""")

    /** A new file `<dir>/<prefix>-<yyyyMMdd-HHmmss><ext>` (numbered when one exists), its directory created. */
    fun newFile(dir: File, prefix: String, ext: String, now: LocalDateTime = LocalDateTime.now()): File {
        dir.mkdirs()
        val stem = "$prefix-${now.format(STAMP)}"
        return generateSequence(0) { it + 1 }
            .map { n -> File(dir, if (n == 0) "$stem$ext" else "$stem-$n$ext") }
            .first { !it.exists() }
    }

    /** The XDG data directories, most important first: `$XDG_DATA_HOME` (else `~/.local/share`), then
     *  `$XDG_DATA_DIRS` (else `/usr/local/share:/usr/share`), per the base directory spec. */
    fun dataDirs(env: (String) -> String? = System::getenv, home: String = System.getProperty("user.home")): List<File> {
        val dataHome = env("XDG_DATA_HOME")?.takeIf { it.isNotBlank() } ?: "$home/.local/share"
        val dataDirs = env("XDG_DATA_DIRS")?.takeIf { it.isNotBlank() } ?: "/usr/local/share:/usr/share"
        return (listOf(dataHome) + dataDirs.split(':')).filter { it.startsWith("/") }.map(::File)
    }

    private val STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
}
