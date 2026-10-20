package de.yoxcu.stoandl.config

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.runInterruptible
import java.io.File
import java.nio.file.ClosedWatchServiceException
import java.nio.file.FileSystems
import java.nio.file.Path
import java.nio.file.WatchKey
import java.nio.file.StandardWatchEventKinds.ENTRY_CREATE
import java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY
import java.nio.file.StandardWatchEventKinds.OVERFLOW
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

private val log = KotlinLogging.logger {}

/** The schema keys (secrets aside, which stoandl.conf never holds) whose value differs between [before]
 *  and [after], in schema order: what a reload of stoandl.conf has to apply, log and announce. */
fun changedConfigFields(before: StoandlConfig, after: StoandlConfig): List<ConfigField> =
    GUI_CONFIG_FIELDS.filter { it.type != "secret" && it.value(before) != it.value(after) }

/**
 * Emits once a burst of writes to [file] has settled for [settle]: an editor's save, `stoandl daemon set`,
 * or the daemon's own atomic rewrite (a temp file moved over it). The directory is watched, not the file,
 * so a save that replaces the file by rename is seen too; and so is its parent, for the directory itself
 * being replaced (`stoandl restore` moves it aside and extracts a new one; the old watch would follow the
 * moved one and never fire again). It blocks in [java.nio.file.WatchService.take] rather than polling, so
 * it costs no wakeups while nothing changes. Best effort: if the directory can't be watched, it logs and
 * emits nothing.
 */
@OptIn(FlowPreview::class)
fun fileEdits(file: File, settle: Duration = 500.milliseconds): Flow<Unit> = flow {
    val dir = file.absoluteFile.parentFile ?: return@flow
    dir.mkdirs()
    val ws = FileSystems.getDefault().newWatchService()
    try {
        fun watchDir(): WatchKey? = try {
            dir.toPath().register(ws, ENTRY_CREATE, ENTRY_MODIFY)
        } catch (e: Exception) {
            log.warn { "Can't watch ${dir.path} for edits of ${file.name}: ${e.message}" }
            null
        }
        var dirKey = watchDir() ?: return@flow
        val parentKey = dir.parentFile?.let { p -> runCatching { p.toPath().register(ws, ENTRY_CREATE) }.getOrNull() }
        while (true) {
            val key = try {
                runInterruptible { ws.take() }
            } catch (e: ClosedWatchServiceException) {
                return@flow
            }
            val events = key.pollEvents()
            fun named(name: String) = events.any { it.kind() == OVERFLOW || (it.context() as? Path)?.toString() == name }
            val touched = when (key) {
                dirKey -> named(file.name)
                // A new directory under the old name: watch it instead, and read the file it brought.
                parentKey -> named(dir.name) && dir.isDirectory && watchDir()?.also { k ->
                    if (k != dirKey) dirKey.cancel()
                    dirKey = k
                } != null
                else -> false
            }
            key.reset()
            if (touched) emit(Unit)
        }
    } finally {
        runCatching { ws.close() }
    }
}.flowOn(Dispatchers.IO).debounce(settle)
