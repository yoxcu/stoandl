package de.yoxcu.stoandl.ext

import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively

/** The most an extension install copies (a directory install of a checkout with its build output). */
internal const val MAX_INSTALL_BYTES = 512L * 1024 * 1024

private val VCS_DIRS = setOf(".git", ".hg", ".svn")

/**
 * Copy the extension tree [root] to [dest] (created), skipping version-control metadata. Returns null, or
 * why the copy failed (a partial [dest] is the caller's to remove).
 *
 * Symbolic links are copied as links, not followed: a `link -> .` loop in a checkout can't recurse, and a
 * link to a big tree outside it isn't copied. Files keep their permissions, so a native entry point (e.g.
 * a Go binary launched via `cmd: ./stoandl-matrix`) stays executable. Sockets and other special files are
 * skipped. More than [maxBytes] of files stops the copy: an install runs on a D-Bus call the CLI waits on,
 * and a dev checkout's build directory can be gigabytes.
 */
internal fun copyExtensionTree(root: Path, dest: Path, maxBytes: Long = MAX_INSTALL_BYTES): String? {
    var bytes = 0L
    var tooBig = false
    try {
        Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (dir != root && dir.fileName.toString() in VCS_DIRS) return FileVisitResult.SKIP_SUBTREE
                Files.createDirectories(dest.resolve(root.relativize(dir).toString()))
                return FileVisitResult.CONTINUE
            }

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                val target = dest.resolve(root.relativize(file).toString())
                when {
                    attrs.isSymbolicLink -> Files.createSymbolicLink(target, Files.readSymbolicLink(file))
                    attrs.isRegularFile -> {
                        bytes += attrs.size()
                        if (bytes > maxBytes) { tooBig = true; return FileVisitResult.TERMINATE }
                        Files.copy(file, target, StandardCopyOption.COPY_ATTRIBUTES)
                    }
                }
                return FileVisitResult.CONTINUE
            }
        })
    } catch (e: IOException) {
        return "${e.javaClass.simpleName}: ${e.message}"
    }
    return if (tooBig) "it holds more than ${maxBytes / (1024 * 1024)} MB of files (install an archive of just the extension)" else null
}

/** Delete [f] and everything under it without following symbolic links (Kotlin's `File.deleteRecursively`
 *  descends into a linked directory and empties it), which extension trees may hold since [copyExtensionTree]
 *  keeps links. False when something couldn't be deleted. */
@OptIn(ExperimentalPathApi::class)
internal fun deleteTree(f: File): Boolean = runCatching { f.toPath().deleteRecursively() }.isSuccess
