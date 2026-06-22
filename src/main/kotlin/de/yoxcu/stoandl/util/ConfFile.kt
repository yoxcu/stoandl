package de.yoxcu.stoandl.util

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Atomic, comment-preserving `key = value` upsert for the stoandl-style config files — the main
 * `stoandl.conf` and the per-extension `<extDir>/<name>/config` files (same simple format).
 *
 * Every writer goes through the one [lock] here, so concurrent edits from `SetConfig`, `ext
 * enable/disable` ([upsert] on `extensions.enabled`) and `ExtSetConfig` can't interleave and corrupt a
 * file. The lock is reentrant (a plain object monitor), so [upsert] is safe to call inside [withLock].
 */
object ConfFile {
    private val lock = Any()

    /** Run [block] holding the shared config-write lock. Use it to make a read-modify-write sequence —
     *  read the current file, compute new values, then [upsert] — atomic against other writers. */
    fun <T> withLock(block: () -> T): T = synchronized(lock) { block() }

    /**
     * Upsert [updates] into [file]: rewrite each matching `key = …` line in place (keeping its inline
     * `# comment`), append any keys not already present, and replace the file atomically (temp +
     * `ATOMIC_MOVE`, falling back to a plain replace if the filesystem can't do an atomic move).
     * Untouched lines, comments and blanks are preserved. The file and its parent dir are created if
     * absent. Lock-held (reentrant).
     */
    fun upsert(file: File, updates: Map<String, String>): Unit = synchronized(lock) {
        val lines = if (file.isFile) file.readLines().toMutableList() else mutableListOf()
        val remaining = LinkedHashMap(updates)
        for (i in lines.indices) {
            val code = lines[i].substringBefore('#')
            val idx = code.indexOf('=')
            if (idx <= 0) continue
            val key = code.substring(0, idx).trim()
            if (key in remaining) {
                val comment = lines[i].indexOf('#').takeIf { it >= 0 }?.let { "  " + lines[i].substring(it) } ?: ""
                lines[i] = "$key = ${remaining.remove(key)}$comment"
            }
        }
        remaining.forEach { (k, v) -> lines.add("$k = $v") }
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(lines.joinToString("\n").trimEnd('\n') + "\n")
        val src = tmp.toPath(); val dst = file.toPath()
        try {
            Files.move(src, dst, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: Exception) {
            Files.move(src, dst, StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
