package de.yoxcu.stoandl.util

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Replace this file's content in one step: write a sibling `.tmp`, then move it over the file (atomically
 *  where the filesystem can). A reader never sees half a file. */
fun File.writeTextAtomic(text: String) {
    val tmp = File(absoluteFile.parentFile, "$name.tmp")
    tmp.writeText(text)
    try {
        Files.move(tmp.toPath(), toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    } catch (e: Exception) {
        Files.move(tmp.toPath(), toPath(), StandardCopyOption.REPLACE_EXISTING)
    }
}
