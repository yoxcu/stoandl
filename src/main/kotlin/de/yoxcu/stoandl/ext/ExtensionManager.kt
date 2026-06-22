@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package de.yoxcu.stoandl.ext

import de.yoxcu.stoandl.dbus.sendDesktopNotification
import de.yoxcu.stoandl.pebble.NotifAction
import de.yoxcu.stoandl.pebble.NotifOwner
import de.yoxcu.stoandl.pebble.NotifOwnerRegistry
import de.yoxcu.stoandl.pebble.NotifRequest
import de.yoxcu.stoandl.pebble.ReplySpec
import de.yoxcu.stoandl.pebble.WatchNotifier
import de.yoxcu.stoandl.util.LenientJson
import de.yoxcu.stoandl.util.runCommand
import io.github.oshai.kotlinlogging.KotlinLogging
import io.rebble.libpebblecommon.connection.ConnectedPebbleDevice
import io.rebble.libpebblecommon.connection.LibPebble
import io.rebble.libpebblecommon.disk.pbw.PbwApp
import io.rebble.libpebblecommon.packets.AppMessage
import io.rebble.libpebblecommon.services.appmessage.AppMessageData
import io.rebble.libpebblecommon.services.appmessage.AppMessageResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import kotlin.uuid.Uuid

private val log = KotlinLogging.logger {}

/**
 * Spawns and supervises user "extensions" — host-side companion processes (Matrix, Signal, SMS,
 * find-my-phone, …) in any language — that drive watch notifications and/or act as a PebbleKit-style
 * companion to a watchapp, over a newline-delimited JSON-RPC stdio protocol (docs/extensions.md).
 *
 * Each enabled extension is a directory `<extDir>/<name>/`; the default entry is `<name>.py` (run with
 * its own dir as cwd, so `import stoandl_ext` works). `extensions.enabled` in stoandl.conf is the
 * authoritative run-list, which the `stoandl ext` verbs ([install]/[uninstall]/[enable]/[disable]/
 * [restart]/[list]) edit live — start/stop is **hotplug**, no daemon restart. There is no sandbox and
 * no capability grant: an extension runs as you (like any script), and self-declares what it does.
 *
 * Every extension's notifications go through the shared [WatchNotifier], so per-app mute/style applies.
 */
class ExtensionManager(
    private val extDir: File,
    private val confFile: File,
    private val enabledAtStart: List<String>,
    private val extConfig: Map<String, Map<String, String>>,
    private val watchNotifier: WatchNotifier,
    private val owners: NotifOwnerRegistry,
    private val libPebbleRef: AtomicReference<LibPebble?>,
    private val scope: CoroutineScope,
) {
    private val running = ConcurrentHashMap<String, ExtensionProcess>()
    private val confLock = Any()

    fun start() {
        if (enabledAtStart.isEmpty()) {
            log.info { "No extensions enabled (set extensions.enabled or run `stoandl ext install`)" }
        } else {
            log.info { "Starting ${enabledAtStart.size} extension(s): $enabledAtStart" }
            enabledAtStart.forEach { startProcess(it) }
        }
        // Tell running extensions when a watch (re)connects so they can re-arm. The watch reports
        // Connected before its BlobDB is ready, so settle briefly (and re-check) before broadcasting.
        libPebbleRef.get()?.let { lp ->
            scope.launch {
                lp.watches
                    .map { devices -> devices.any { it is ConnectedPebbleDevice } }
                    .distinctUntilChanged()
                    .collect { connected ->
                        if (!connected) return@collect
                        delay(CONNECT_SETTLE_MS)
                        if (lp.watches.value.any { it is ConnectedPebbleDevice }) {
                            running.values.forEach { it.onWatchConnected() }
                        }
                    }
            }
        }
    }

    // ---- lifecycle (hotplug) --------------------------------------------------------------------

    private enum class StartResult { STARTED, NEEDS_CONFIG, NO_ENTRYPOINT }

    private fun startProcess(name: String): StartResult {
        if (running.containsKey(name)) return StartResult.STARTED
        val def = resolveExtension(name, extDir, extConfig[name] ?: emptyMap()) ?: return StartResult.NO_ENTRYPOINT
        // A manifest that declares requiresConfig can't run until the user supplies settings — don't
        // spawn a doomed child; notify + bail gracefully so it's clear what to do.
        if (def.requiresConfig && !def.userConfigured) {
            notifyNeedsConfig(name, File(def.workingDir, "config"))
            return StartResult.NEEDS_CONFIG
        }
        val proc = ExtensionProcess(def, watchNotifier, owners, libPebbleRef, scope)
        running[name] = proc
        proc.launch()
        return StartResult.STARTED
    }

    /** Required-but-unconfigured extension: log it, and post a *desktop* notification (on the phone/
     *  laptop). stoandl's passive monitor bridges that to the watch automatically — so we don't push a
     *  direct watch notification, which would show up twice. */
    private fun notifyNeedsConfig(name: String, configFile: File) {
        log.warn { "[$name] requires configuration but none found — not starting. Edit ${configFile.path} (copy config.example), then: stoandl ext restart $name" }
        scope.launch(Dispatchers.IO) {
            sendDesktopNotification(
                "$name needs setup",
                "Configure it on the host (${configFile.path}), then: stoandl ext restart $name",
            )
        }
    }

    private fun stopProcess(name: String): Boolean = running.remove(name)?.let { it.shutdown(); true } ?: false

    /** Stop every running extension (called on daemon shutdown so child processes don't orphan). */
    fun shutdownAll() {
        val names = running.keys.toList()
        names.forEach { stopProcess(it) }
        if (names.isNotEmpty()) log.info { "Stopped ${names.size} extension(s)" }
    }

    // ---- `stoandl ext` operations (return status-prefixed strings) ------------------------------

    fun list(): List<String> {
        val enabled = currentEnabled().toSet()
        // Skip dotfiles (e.g. our .install-tmp-*) and python's __pycache__ — not extensions.
        val installed = extDir.listFiles()
            ?.filter { it.isDirectory && !it.name.startsWith(".") && !it.name.startsWith("__") }
            ?.map { it.name }?.toSet() ?: emptySet()
        return (installed + enabled).sorted().map { n ->
            val meta = readExtManifestMeta(File(extDir, n))
            // config-kind: `schema` when the manifest declares a configSchema, else `none` (the `url`
            // backend isn't implemented — no embedded HTTP server). Description is a single line.
            val configKind = if (!meta.configSchemaJson.isNullOrBlank() && meta.configSchemaJson != "[]") "schema" else "none"
            listOf(
                n,
                if (n in installed) "installed" else "missing",
                if (n in enabled) "enabled" else "disabled",
                if (running.containsKey(n)) "running" else "stopped",
                configKind,
                meta.description.replace('\t', ' ').replace('\n', ' '),
            ).joinToString("\t")
        }
    }

    /** The extension's typed config schema (its manifest `configSchema`) as a JSON array, for the GUI's
     *  native config form. `ok:<json-array>`, `none:` (no schema declared), or `notfound:`. */
    fun configSchema(name: String): String {
        val n = sanitize(name) ?: return "error:Invalid name"
        if (!isKnown(n)) return "notfound:'$name' is not installed"
        val json = readExtManifestMeta(File(extDir, n)).configSchemaJson
        return if (json.isNullOrBlank() || json == "[]") "none:" else "ok:$json"
    }

    /** Extract an archive (.tar.gz/.tgz/.tar/.zip) into `<extDir>/<name>/`, sideload a bundled `.pbw`,
     *  enable it, and hotplug-start it. */
    fun install(archivePath: String): String {
        val archive = File(archivePath)
        if (!archive.isFile) return "error:No such file: $archivePath"
        val tmp = File(extDir, ".install-tmp-${System.nanoTime()}")
        extDir.mkdirs(); tmp.deleteRecursively(); tmp.mkdirs()
        try {
            if (!extract(archive, tmp)) {
                return "error:Could not extract ${archive.name} (need tar/unzip; supported: .tar.gz/.tgz/.tar/.zip)"
            }
            // A single top-level dir is the extension root; otherwise the archive's files are the root.
            val entries = tmp.listFiles()?.filter { it.name != "__MACOSX" } ?: emptyList()
            val root = entries.singleOrNull { it.isDirectory } ?: tmp
            val rawName = manifestName(root) ?: archive.name
                .removeSuffix(".tar.gz").removeSuffix(".tgz").removeSuffix(".tar").removeSuffix(".zip")
            val name = rawName.substringAfterLast('/').filter { it.isLetterOrDigit() || it == '-' || it == '_' }
            if (name.isEmpty()) return "error:Could not determine an extension name from ${archive.name}"

            stopProcess(name)
            val dest = File(extDir, name)
            // Preserve the user's per-extension `config` across a reinstall (the archive shouldn't carry
            // one; if it does, the archive's wins).
            val savedConfig = File(dest, "config").takeIf { it.isFile }?.readText()
            dest.deleteRecursively()
            dest.parentFile?.mkdirs()
            if (!root.copyRecursively(dest, overwrite = true)) return "error:Failed to install files for '$name'"
            if (savedConfig != null && !File(dest, "config").isFile) File(dest, "config").writeText(savedConfig)
            // Kotlin's copyRecursively drops the executable bit, so a native entry point (e.g. a Go binary
            // launched via `cmd: ./stoandl-matrix`) would land non-executable → exec fails with errno 13.
            // Re-apply +x for anything that was executable in the extracted archive.
            root.walkTopDown().forEach { src ->
                if (src.isFile && src.canExecute()) File(dest, src.relativeTo(root).path).setExecutable(true)
            }

            val pbwMsg = sideloadBundledPbw(dest)
            setEnabled(name, true)
            val result = startProcess(name)
            // Point the user at where this extension is configured (its own dir, not stoandl.conf).
            val configFile = File(dest, "config")
            val exampleFile = File(dest, "config.example")
            val configMsg = when {
                configFile.isFile -> "; settings: ${configFile.path}"
                exampleFile.isFile ->
                    "; settings: cp ${exampleFile.path} ${configFile.path}, edit it, then: stoandl ext restart $name"
                else -> ""
            }
            return when (result) {
                StartResult.STARTED -> "ok:Installed '$name'$pbwMsg$configMsg"
                StartResult.NEEDS_CONFIG -> "ok:Installed '$name' (not started — needs configuration)$pbwMsg$configMsg"
                StartResult.NO_ENTRYPOINT -> "error:Installed '$name' but couldn't start it (no entry point?)$pbwMsg$configMsg"
            }
        } finally {
            tmp.deleteRecursively()
        }
    }

    fun uninstall(name: String, keepConfig: Boolean): String {
        val n = sanitize(name) ?: return "error:Invalid name"
        stopProcess(n)
        setEnabled(n, false)
        val dir = File(extDir, n)
        val existed = dir.isDirectory
        val watchMsg = if (existed) removeBundledWatchapp(dir) else ""
        // Optionally keep the user's `config` (so a later reinstall restores its settings); the install
        // path already preserves an existing config across an overwrite.
        val keptConfig = existed && keepConfig && File(dir, "config").isFile
        if (keptConfig) {
            dir.listFiles()?.forEach { if (it.name != "config") it.deleteRecursively() }
        } else {
            dir.deleteRecursively()
        }
        val tail = watchMsg + if (keptConfig) "; kept its config (${File(dir, "config").path})" else ""
        return if (existed) "ok:Uninstalled '$n'$tail" else "ok:'$n' disabled (no files were installed)"
    }

    /** If the extension bundled a `.pbw`, remove that watchapp from the watch's locker too (read its
     *  UUID from the pbw). Best-effort — returns a status suffix. */
    private fun removeBundledWatchapp(dir: File): String {
        val pbw = dir.listFiles()?.firstOrNull { it.extension.equals("pbw", ignoreCase = true) } ?: return ""
        val lp = libPebbleRef.get() ?: return "; its watchapp left on the watch (no watch connected)"
        val uuid = runCatching { Uuid.parse(PbwApp(kotlinx.io.files.Path(pbw.absolutePath)).info.uuid) }.getOrNull()
            ?: return "; couldn't read ${pbw.name} UUID (watchapp left on the watch)"
        return try {
            if (runBlocking { lp.removeApp(uuid) }) "; removed its watchapp from the watch"
            else "; watchapp removal not confirmed"
        } catch (e: Exception) {
            log.warn(e) { "removeApp($uuid) failed" }
            "; watchapp removal failed (${e.message})"
        }
    }

    fun enable(name: String): String {
        val n = sanitize(name) ?: return "error:Invalid name"
        if (!File(extDir, n).isDirectory) return "error:'$n' is not installed (no ${extDir.path}/$n)"
        setEnabled(n, true)
        return when (startProcess(n)) {
            StartResult.STARTED -> "ok:Enabled '$n'"
            StartResult.NEEDS_CONFIG -> "ok:Enabled '$n' (not started — needs configuration; edit ${File(File(extDir, n), "config").path}, then: stoandl ext restart $n)"
            StartResult.NO_ENTRYPOINT -> "error:Enabled '$n' but couldn't start it (no entry point?)"
        }
    }

    fun disable(name: String): String {
        val n = sanitize(name) ?: return "error:Invalid name"
        stopProcess(n)
        setEnabled(n, false)
        return "ok:Disabled '$n'"
    }

    fun restart(name: String): String {
        val n = sanitize(name) ?: return "error:Invalid name"
        stopProcess(n)
        return when (startProcess(n)) {
            StartResult.STARTED -> "ok:Restarted '$n'"
            StartResult.NEEDS_CONFIG -> "ok:'$n' not started — needs configuration (edit ${File(File(extDir, n), "config").path}, then re-run this)"
            StartResult.NO_ENTRYPOINT -> "error:Couldn't start '$n'"
        }
    }

    /** The extension's current settings (its `<extDir>/<name>/config` file) as a JSON object of string
     *  values, for the GUI's native config form. `ok:<json>` (or `ok:{}` if it has no config yet) or
     *  `notfound:`. */
    fun getConfig(name: String): String {
        val n = sanitize(name) ?: return "error:Invalid name"
        if (!isKnown(n)) return "notfound:'$name' is not installed"
        val values = readRawConfig(File(File(extDir, n), "config"))
        val json = buildJsonObject { values.forEach { (k, v) -> put(k, v) } }
        return "ok:" + LenientJson.encodeToString(JsonObject.serializer(), json)
    }

    /** Merge [payload] (a JSON object of key→value) into the extension's `config` file — an atomic write
     *  that preserves comments and unchanged keys (so unsent secrets aren't clobbered) — then restart it
     *  if running so the change takes effect. `ok:`/`notfound:`/`error:`. */
    fun setConfig(name: String, payload: String): String {
        val n = sanitize(name) ?: return "error:Invalid name"
        if (!isKnown(n)) return "notfound:'$name' is not installed"
        val obj = try {
            LenientJson.parseToJsonElement(payload).jsonObject
        } catch (e: Exception) {
            return "error:Invalid JSON payload: ${e.message}"
        }
        val updates = obj.mapValues { it.value.jsonPrimitive.contentOrNull ?: "" }
        if (updates.isEmpty()) return "ok:No changes for '$n'"
        return try {
            writeConfigUpdates(File(extDir, n), updates)
            // Apply immediately if it's running (config is read in the initialize handshake); no-op if stopped.
            if (running.containsKey(n)) { stopProcess(n); startProcess(n) }
            "ok:Saved settings for '$n'"
        } catch (e: Exception) {
            log.warn(e) { "setConfig($n) failed" }
            "error:${e.message ?: "failed to save settings"}"
        }
    }

    // ---- helpers --------------------------------------------------------------------------------

    /** A known extension = an installed dir or an enabled name (the same set [list] reports). */
    private fun isKnown(name: String): Boolean =
        File(extDir, name).isDirectory || name in currentEnabled()

    /** Raw `key = value` pairs from a config file (no `~`/`%h` expansion — we round-trip the user's exact
     *  values, unlike the spawn-time read which expands them). */
    private fun readRawConfig(file: File): Map<String, String> {
        if (!file.isFile) return emptyMap()
        val out = LinkedHashMap<String, String>()
        file.readLines().forEach { raw ->
            val line = raw.substringBefore('#').trim()
            val idx = line.indexOf('=')
            if (idx > 0) out[line.substring(0, idx).trim()] = line.substring(idx + 1).trim()
        }
        return out
    }

    /** Upsert [updates] into `<dir>/config`: rewrite each matched key in place (preserving its inline
     *  comment), append new keys, and replace the file atomically (temp + ATOMIC_MOVE, like [setEnabled]).
     *  Untouched keys, comments and blank lines are preserved. */
    private fun writeConfigUpdates(dir: File, updates: Map<String, String>) = synchronized(confLock) {
        val file = File(dir, "config")
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
        dir.mkdirs()
        val tmp = File(dir, "config.tmp")
        tmp.writeText(lines.joinToString("\n").trimEnd('\n') + "\n")
        val src = tmp.toPath(); val dst = file.toPath()
        try {
            java.nio.file.Files.move(src, dst, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
        } catch (e: Exception) {
            java.nio.file.Files.move(src, dst, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun sanitize(name: String): String? =
        name.trim().takeIf { it.isNotEmpty() && it.all { c -> c.isLetterOrDigit() || c == '-' || c == '_' } }

    private fun sideloadBundledPbw(dir: File): String {
        val pbw = dir.listFiles()?.firstOrNull { it.extension.equals("pbw", ignoreCase = true) } ?: return ""
        val lp = libPebbleRef.get() ?: return "; ${pbw.name} not installed (no watch connected — `stoandl sideload` it later)"
        return try {
            val ok = runBlocking { lp.sideloadApp(kotlinx.io.files.Path(pbw.absolutePath)) }
            if (ok) "; installed ${pbw.name} on the watch" else "; ${pbw.name} sideload failed"
        } catch (e: Exception) {
            log.warn(e) { "sideload ${pbw.name} failed" }
            "; ${pbw.name} sideload failed (${e.message})"
        }
    }

    private fun extract(archive: File, dest: File): Boolean {
        val n = archive.name.lowercase()
        val cmd = when {
            n.endsWith(".zip") -> listOf("unzip", "-q", "-o", archive.absolutePath, "-d", dest.absolutePath)
            n.endsWith(".tar.gz") || n.endsWith(".tgz") -> listOf("tar", "-xzf", archive.absolutePath, "-C", dest.absolutePath)
            n.endsWith(".tar") -> listOf("tar", "-xf", archive.absolutePath, "-C", dest.absolutePath)
            else -> return false
        }
        return runCommand(cmd, 60) != null
    }

    // True for an uncommented `extensions.enabled [=...] ` line, with a real key boundary so we don't
    // match `extensions.enabled_backup` etc.
    private fun isEnabledLine(line: String): Boolean {
        val code = line.substringBefore('#').trimStart()
        if (!code.startsWith(ENABLED_KEY)) return false
        val after = code.getOrNull(ENABLED_KEY.length)
        return (after == null || after == ' ' || after == '\t' || after == '=') && code.contains('=')
    }

    private fun namesIn(line: String): List<String> =
        line.substringBefore('#').substringAfter('=').split(',').map { it.trim() }.filter { it.isNotEmpty() }

    private fun currentEnabled(): List<String> {
        if (!confFile.isFile) return emptyList()
        return confFile.readLines().firstOrNull { isEnabledLine(it) }?.let { namesIn(it) } ?: emptyList()
    }

    /** Add/remove [name] from the `extensions.enabled` line in stoandl.conf, rewriting only that line
     *  (its inline comment is preserved) and replacing the file atomically (temp + rename). */
    private fun setEnabled(name: String, enabled: Boolean) = synchronized(confLock) {
        val lines = if (confFile.isFile) confFile.readLines().toMutableList() else mutableListOf()
        val idx = lines.indexOfFirst { isEnabledLine(it) }
        val names = LinkedHashSet<String>()
        var comment = ""
        if (idx >= 0) {
            namesIn(lines[idx]).forEach { names.add(it) }
            lines[idx].indexOf('#').takeIf { it >= 0 }?.let { comment = "  " + lines[idx].substring(it) }
        }
        if (enabled) names.add(name) else names.remove(name)
        val newLine = "extensions.enabled = " + names.joinToString(", ") + comment
        if (idx >= 0) {
            lines[idx] = newLine
        } else {
            if (lines.isNotEmpty() && lines.last().isNotBlank()) lines.add("")
            lines.add(newLine)
        }
        try {
            confFile.parentFile?.mkdirs()
            val tmp = File(confFile.parentFile, confFile.name + ".tmp")
            tmp.writeText(lines.joinToString("\n").trimEnd('\n') + "\n")
            val src = tmp.toPath(); val dst = confFile.toPath()
            try {
                java.nio.file.Files.move(src, dst, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
            } catch (e: Exception) {
                java.nio.file.Files.move(src, dst, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (e: Exception) {
            log.warn { "Couldn't update extensions.enabled in ${confFile.path}: ${e.message}" }
        }
    }

    companion object {
        private const val ENABLED_KEY = "extensions.enabled"
    }
}

private const val MAX_FAST_FAILURES = 5
private const val STABLE_UPTIME_MS = 60_000L
private const val BACKOFF_BASE_MS = 1_000L
private const val BACKOFF_MAX_MS = 60_000L
private const val INIT_TIMEOUT_MS = 10_000L
private const val CONNECT_SETTLE_MS = 3_000L

/** One supervised extension child: spawn → handshake → relay, restart-with-backoff on exit. Owns a
 *  child scope so [shutdown] cleanly stops the supervise loop, the inbound collectors, and the child. */
private class ExtensionProcess(
    private val def: ExtensionDef,
    private val watchNotifier: WatchNotifier,
    owners: NotifOwnerRegistry,
    private val libPebbleRef: AtomicReference<LibPebble?>,
    parentScope: CoroutineScope,
) {
    private val tag = def.name
    private val job = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + job)
    @Volatile private var writeChannel: Channel<String>? = null
    @Volatile private var proc: Process? = null
    @Volatile private var ready = false
    private val owner = ExtensionOwner(def.name) { obj -> enqueue(obj) }.also { owners.register(it) }
    private val registeredApps = ConcurrentHashMap.newKeySet<Uuid>()

    fun onWatchConnected() {
        if (!ready) return
        enqueue(buildJsonObject {
            put("jsonrpc", "2.0"); put("method", "onWatchConnected"); put("params", buildJsonObject {})
        })
    }

    fun shutdown() {
        ready = false
        writeChannel = null      // stop new enqueues before tearing down
        val p = proc
        job.cancel()             // cancels the supervise loop + writer/reader/collector coroutines
        p?.destroy()             // then actually kill the child (waitFor was just cancelled, not the proc)
        log.info { "[$tag] stopped" }
    }

    private fun enqueue(obj: JsonObject) {
        val ch = writeChannel ?: run { log.debug { "[$tag] drop outgoing (no live process): ${obj["method"]}" }; return }
        ch.trySend(LenientJson.encodeToString(JsonObject.serializer(), obj))
    }

    fun launch() {
        scope.launch {
            var fastFailures = 0
            while (isActive) {
                val startedAt = System.currentTimeMillis()
                try {
                    runOnce()
                } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.warn(e) { "[$tag] extension crashed: ${e.message}" }
                }
                ready = false
                if (!isActive) break
                val uptime = System.currentTimeMillis() - startedAt
                fastFailures = if (uptime >= STABLE_UPTIME_MS) 0 else fastFailures + 1
                if (fastFailures >= MAX_FAST_FAILURES) {
                    log.error { "[$tag] quarantined after $fastFailures rapid failures — fix it and `stoandl ext restart $tag`" }
                    return@launch
                }
                val backoff = (BACKOFF_BASE_MS shl (fastFailures - 1).coerceAtLeast(0)).coerceAtMost(BACKOFF_MAX_MS)
                log.info { "[$tag] exited (uptime ${uptime}ms); restarting in ${backoff}ms" }
                delay(backoff)
            }
        }
    }

    private suspend fun runOnce() {
        log.info { "[$tag] spawning: ${def.command.joinToString(" ")} (cwd ${def.workingDir.path})" }
        val process = ProcessBuilder(def.command)
            .apply { if (def.workingDir.isDirectory) directory(def.workingDir) }  // ProcessBuilder throws on a missing cwd
            .redirectErrorStream(false)
            .start()
        proc = process
        val ch = Channel<String>(Channel.UNLIMITED)
        writeChannel = ch

        val writer = process.outputStream.bufferedWriter()
        val writerJob = scope.launch(Dispatchers.IO) {
            try {
                for (line in ch) { writer.write(line); writer.write("\n"); writer.flush() }
            } catch (e: Exception) {
                log.debug { "[$tag] writer ended: ${e.message}" }
            }
        }
        val stderrJob = scope.launch(Dispatchers.IO) {
            try { process.errorStream.bufferedReader().forEachLine { log.info { "[$tag] $it" } } } catch (e: Exception) { log.debug { "[$tag] stderr reader ended: ${e.message}" } }
        }
        val inbound = Channel<String>(Channel.UNLIMITED)
        val readerJob = scope.launch(Dispatchers.IO) {
            try { process.inputStream.bufferedReader().forEachLine { inbound.trySend(it) } } finally { inbound.close() }
        }
        val processorJob = scope.launch { for (line in inbound) handleLine(line) }

        sendInitialize()
        scope.launch { delay(INIT_TIMEOUT_MS); if (!ready) log.warn { "[$tag] no initialize response after ${INIT_TIMEOUT_MS}ms" } }

        withContext(Dispatchers.IO) { process.waitFor() }
        ch.close()
        writeChannel = null
        proc = null
        writerJob.cancel(); stderrJob.cancel(); readerJob.cancel(); processorJob.cancel()
        try { writer.close() } catch (_: Exception) {}
    }

    private fun sendInitialize() {
        enqueue(buildJsonObject {
            put("jsonrpc", "2.0"); put("id", 0); put("method", "initialize")
            put("params", buildJsonObject {
                put("protocolVersion", 1)
                put("config", buildJsonObject { def.config.forEach { (k, v) -> put(k, v) } })
                put("watch", buildJsonObject {
                    put("connected", libPebbleRef.get()?.watches?.value?.any { it is ConnectedPebbleDevice } ?: false)
                })
            })
        })
    }

    private suspend fun handleLine(line: String) {
        val obj = try {
            LenientJson.parseToJsonElement(line).jsonObject
        } catch (e: Exception) {
            log.warn { "[$tag] ignoring non-JSON line: ${line.take(200)}" }; return
        }
        val method = obj["method"]?.jsonPrimitive?.contentOrNull
        val id = obj["id"]
        val params = obj["params"]?.jsonObject
        when {
            method == null && obj.containsKey("result") -> handleResult(obj)
            method == "notify" -> handleNotify(id, params)
            method == "closeNotification" -> handleClose(params)
            method == "registerApp" -> handleRegisterApp(id, params)
            method == "sendAppMessage" -> handleSendAppMessage(id, params)
            method == "launchApp" -> handleAppRunState(id, params, launch = true)
            method == "stopApp" -> handleAppRunState(id, params, launch = false)
            method == "installPbw" -> handleInstallPbw(id, params)
            method == "log" -> log.info { "[$tag] ${params?.str("message") ?: ""}" }
            method == "initialize" -> {}
            else -> log.debug { "[$tag] unhandled message: method=$method" }
        }
    }

    private fun handleResult(obj: JsonObject) {
        val manifest = obj["result"]?.jsonObject ?: return
        val caps = manifest["capabilities"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()
        ready = true
        log.info { "[$tag] initialized (name='${manifest.str("name") ?: tag}', capabilities=$caps)" }
    }

    private suspend fun handleNotify(id: JsonElement?, params: JsonObject?) {
        if (params == null) { replyError(id, "missing params"); return }
        val extToken = params.str("extToken")
        val actions = params["actions"]?.jsonArray?.mapNotNull { e ->
            val o = e.jsonObject
            val aid = o.str("id"); val label = o.str("label")
            if (aid != null && label != null) NotifAction(aid, label) else null
        } ?: emptyList()
        val reply = params["reply"]?.jsonObject?.let { r ->
            val canned = r["cannedReplies"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()
            ReplySpec(canned, r["allowVoice"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false)
        }
        val requestedId = params.str("itemId")?.let { runCatching { Uuid.parse(it) }.getOrNull() }
        val req = NotifRequest(
            appName = params.str("appName") ?: def.name,
            title = params.str("title") ?: "",
            body = params.str("body") ?: "",
            subtitle = params.str("subtitle"),
            actions = actions,
            reply = reply,
            iconCode = params.str("iconCode"),
            colorName = params.str("color"),
            vibeName = params.str("vibe"),
            itemId = requestedId,
        )
        val itemId = watchNotifier.push(req, def.name, extToken)
        if (id != null) reply(id) { put("itemId", itemId?.toString()) }
    }

    private suspend fun handleClose(params: JsonObject?) {
        val itemId = params?.str("itemId")?.let { runCatching { Uuid.parse(it) }.getOrNull() } ?: return
        watchNotifier.close(itemId)
    }

    private fun connectedDevice(): ConnectedPebbleDevice? =
        libPebbleRef.get()?.watches?.value?.filterIsInstance<ConnectedPebbleDevice>()?.firstOrNull()

    private fun handleRegisterApp(id: JsonElement?, params: JsonObject?) {
        val uuid = params?.str("uuid")?.let { runCatching { Uuid.parse(it) }.getOrNull() }
        if (uuid == null) { replyError(id, "registerApp: missing/invalid uuid"); return }
        if (registeredApps.add(uuid)) {
            scope.launch {
                val lp = libPebbleRef.get() ?: return@launch
                // BROADCAST inbound packet flow, not inboundAppMessages(uuid): the latter is a single
                // Channel that CompanionAppLifecycleManager also drains for every running app, which
                // would steal our messages. The SharedFlow lets us observe + ACK independently.
                lp.watches
                    .map { devices -> devices.filterIsInstance<ConnectedPebbleDevice>().firstOrNull() }
                    .distinctUntilChanged()
                    .flatMapLatest { dev -> dev?.inboundMessages?.map { dev to it } ?: emptyFlow() }
                    .collect { (dev, pkt) ->
                        if (pkt !is AppMessage.AppMessagePush || pkt.uuid.get() != uuid) return@collect
                        val txn = pkt.transactionId.get()
                        runCatching { dev.sendAppMessageResult(AppMessageResult.ACK(txn)) }
                        val data = pkt.dictionary.list.associate { it.key.get().toInt() to it.getTypedData() }
                        enqueue(buildJsonObject {
                            put("jsonrpc", "2.0"); put("method", "onAppMessage")
                            put("params", buildJsonObject {
                                put("uuid", uuid.toString()); put("transactionId", txn.toInt()); put("data", encodeAppData(data))
                            })
                        })
                    }
            }
            log.info { "[$tag] registered for AppMessages from $uuid" }
        }
        reply(id) { put("ok", true) }
    }

    private suspend fun handleSendAppMessage(id: JsonElement?, params: JsonObject?) {
        val uuid = params?.str("uuid")?.let { runCatching { Uuid.parse(it) }.getOrNull() }
        if (uuid == null) { replyError(id, "sendAppMessage: missing/invalid uuid"); return }
        val dev = connectedDevice() ?: run { reply(id) { put("result", "timeout") }; return }
        val data = decodeAppData(params["data"]?.jsonObject)
        val result = try {
            dev.sendAppMessage(AppMessageData(transactionId = dev.transactionSequence.next(), uuid = uuid, data = data))
        } catch (e: Exception) {
            log.warn(e) { "[$tag] sendAppMessage failed: ${e.message}" }
            reply(id) { put("result", "error") }; return
        }
        reply(id) { put("result", if (result is AppMessageResult.ACK) "ack" else "nack") }
    }

    private suspend fun handleAppRunState(id: JsonElement?, params: JsonObject?, launch: Boolean) {
        val verb = if (launch) "launchApp" else "stopApp"
        val uuid = params?.str("uuid")?.let { runCatching { Uuid.parse(it) }.getOrNull() }
        if (uuid == null) { replyError(id, "$verb: missing/invalid uuid"); return }
        val lp = libPebbleRef.get() ?: run { replyError(id, "no watch"); return }
        try { if (launch) lp.launchApp(uuid) else lp.stopApp(uuid) } catch (e: Exception) { replyError(id, e.message ?: "$verb failed"); return }
        reply(id) { put("ok", true) }
    }

    private suspend fun handleInstallPbw(id: JsonElement?, params: JsonObject?) {
        val path = params?.str("path")
        if (path.isNullOrEmpty()) { replyError(id, "installPbw: missing path"); return }
        val lp = libPebbleRef.get() ?: run { replyError(id, "no watch"); return }
        // Resolve a relative path against the extension's own dir.
        val abs = File(path).let { if (it.isAbsolute) it else File(def.workingDir, path) }
        val ok = try { lp.sideloadApp(kotlinx.io.files.Path(abs.absolutePath)) } catch (e: Exception) { replyError(id, e.message ?: "install failed"); return }
        reply(id) { put("ok", ok) }
    }

    private fun encodeAppData(data: Map<Int, Any>): JsonObject = buildJsonObject {
        data.forEach { (k, v) ->
            put(k.toString(), buildJsonObject {
                when (v) {
                    is ULong -> { put("t", "uint"); put("v", v.toLong()) }
                    is Long -> { put("t", "int"); put("v", v) }
                    is UByte -> { put("t", "uint8"); put("v", v.toInt()) }
                    is UShort -> { put("t", "uint16"); put("v", v.toInt()) }
                    is UInt -> { put("t", "uint"); put("v", v.toLong()) }
                    is Byte -> { put("t", "int8"); put("v", v.toInt()) }
                    is Short -> { put("t", "int16"); put("v", v.toInt()) }
                    is Int -> { put("t", "int"); put("v", v) }
                    is String -> { put("t", "cstring"); put("v", v) }
                    is ByteArray -> { put("t", "bytes"); put("v", java.util.Base64.getEncoder().encodeToString(v)) }
                    is UByteArray -> { put("t", "bytes"); put("v", java.util.Base64.getEncoder().encodeToString(v.toByteArray())) }
                    else -> { put("t", "cstring"); put("v", v.toString()) }
                }
            })
        }
    }

    private fun decodeAppData(obj: JsonObject?): Map<Int, Any> {
        if (obj == null) return emptyMap()
        val out = LinkedHashMap<Int, Any>()
        obj.forEach { (k, el) ->
            val key = k.toIntOrNull() ?: return@forEach
            val o = el as? JsonObject ?: return@forEach
            val t = o["t"]?.jsonPrimitive?.contentOrNull ?: "int"
            val raw = o["v"]?.jsonPrimitive?.contentOrNull ?: return@forEach
            val value: Any? = when (t) {
                "uint8" -> raw.toUByteOrNull()
                "uint16" -> raw.toUShortOrNull()
                "uint", "uint32" -> raw.toUIntOrNull()
                "int8" -> raw.toByteOrNull()
                "int16" -> raw.toShortOrNull()
                "int", "int32" -> raw.toIntOrNull()
                "bool" -> raw.toBooleanStrictOrNull()
                "bytes" -> runCatching { java.util.Base64.getDecoder().decode(raw) }.getOrNull()
                else -> raw
            }
            if (value != null) out[key] = value
        }
        return out
    }

    private fun reply(id: JsonElement?, result: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit) {
        if (id == null) return
        enqueue(buildJsonObject { put("jsonrpc", "2.0"); put("id", id); put("result", buildJsonObject(result)) })
    }

    private fun replyError(id: JsonElement?, message: String) {
        if (id == null) { log.warn { "[$tag] $message" }; return }
        enqueue(buildJsonObject { put("jsonrpc", "2.0"); put("id", id); put("error", buildJsonObject { put("message", message) }) })
    }
}

private fun JsonObject.str(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull

/** [NotifOwner] for an extension: forwards the user's wrist action/reply/dismiss as a JSON-RPC
 *  notification to the child (enqueued — never blocks libpebble3's action flow). The correlation token
 *  comes from the route (router), so the extension then acts via its own service account. */
private class ExtensionOwner(
    private val name: String,
    private val send: (JsonObject) -> Unit,
) : NotifOwner {
    override val id = name

    override suspend fun onAction(itemId: Uuid, token: String?, actionId: String) =
        notify("onAction", itemId, token) { put("action", actionId) }

    override suspend fun onReply(itemId: Uuid, token: String?, text: String) =
        notify("onReply", itemId, token) { put("text", text) }

    override suspend fun onDismiss(itemId: Uuid, token: String?) =
        notify("onDismiss", itemId, token) {}

    private inline fun notify(
        method: String,
        itemId: Uuid,
        token: String?,
        extra: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit,
    ) {
        send(buildJsonObject {
            put("jsonrpc", "2.0")
            put("method", method)
            put("params", buildJsonObject {
                token?.let { put("extToken", it) }
                put("itemId", itemId.toString())
                extra()
            })
        })
    }
}
