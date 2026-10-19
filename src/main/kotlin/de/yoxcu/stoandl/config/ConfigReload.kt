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
import java.nio.file.StandardWatchEventKinds.ENTRY_CREATE
import java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY
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
 * so a save that replaces the file by rename is seen too. It blocks in [java.nio.file.WatchService.take]
 * rather than polling, so it costs no wakeups while nothing changes. Best effort: if the directory can't
 * be watched, it logs and emits nothing.
 */
@OptIn(FlowPreview::class)
fun fileEdits(file: File, settle: Duration = 500.milliseconds): Flow<Unit> = flow {
    val dir = file.absoluteFile.parentFile ?: return@flow
    dir.mkdirs()
    val ws = FileSystems.getDefault().newWatchService()
    try {
        try {
            dir.toPath().register(ws, ENTRY_CREATE, ENTRY_MODIFY)
        } catch (e: Exception) {
            log.warn { "Can't watch ${dir.path} for edits of ${file.name}: ${e.message}" }
            return@flow
        }
        while (true) {
            val key = try {
                runInterruptible { ws.take() }
            } catch (e: ClosedWatchServiceException) {
                return@flow
            }
            val touched = key.pollEvents().any { (it.context() as? Path)?.toString() == file.name }
            key.reset()
            if (touched) emit(Unit)
        }
    } finally {
        runCatching { ws.close() }
    }
}.flowOn(Dispatchers.IO).debounce(settle)
