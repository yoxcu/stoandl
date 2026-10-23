@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package de.yoxcu.stoandl.ext

import de.yoxcu.stoandl.calendar.TodoWriter
import de.yoxcu.stoandl.config.ConfList
import de.yoxcu.stoandl.dbus.sendDesktopNotification
import de.yoxcu.stoandl.imaging.ImageSource
import de.yoxcu.stoandl.pebble.NotifAction
import de.yoxcu.stoandl.pebble.NotifOwner
import de.yoxcu.stoandl.pebble.NotifOwnerRegistry
import de.yoxcu.stoandl.pebble.NotifRequest
import de.yoxcu.stoandl.pebble.ReplySpec
import de.yoxcu.stoandl.pebble.WatchNotifier
import de.yoxcu.stoandl.secret.SecretStore
import de.yoxcu.stoandl.util.ConfFile
import de.yoxcu.stoandl.util.LenientJson
import de.yoxcu.stoandl.util.runCommand
import de.yoxcu.stoandl.voice.tts.Synthesis
import io.github.oshai.kotlinlogging.KotlinLogging
import io.rebble.libpebblecommon.connection.ConnectedPebbleDevice
import io.rebble.libpebblecommon.connection.LibPebble
import io.rebble.libpebblecommon.disk.pbw.PbwApp
import io.rebble.libpebblecommon.packets.ProtocolCapsFlag
import io.rebble.libpebblecommon.packets.AppMessage
import io.rebble.libpebblecommon.services.appmessage.AppMessageData
import io.rebble.libpebblecommon.services.appmessage.AppMessageResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.booleanOrNull
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.add
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
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
    /** Whether stoandl's own extension alerts may be posted (`alerts.enabled` + `alerts.extensions`,
     *  read live). Defaults to always-on so tests and any other caller keep the previous behaviour. */
    private val alertsAllowed: () -> Boolean = { true },
    /** `notification.images`, read live: whether pictures attached with `notify` reach the watch. */
    private val notificationImagesOn: () -> Boolean = { false },
    /** Speech to text for `transcribe` (a VoiceControl result string: `ok:<ms>\t<text>`, `off:`, …). */
    private val transcribe: suspend (path: String, language: String?) -> String = { _, _ -> "off:" },
    /** Text to speech for `synthesize` (TtsService.synthesize): text, language, format. */
    private val synthesize: suspend (text: String, language: String?, format: String) -> Synthesis = { _, _, _ -> Synthesis.Error("off") },
    /** `prepareSynthesize` (TtsService.prepare): load the voice for a reading in this language. */
    private val prepareSynthesize: (language: String?) -> Unit = {},
    /** `addTodo`: save a to-do in `calendar.todos` (title, due, note; due and note may be empty). */
    private val addTodo: suspend (title: String, due: String, note: String) -> TodoWriter.Result = { _, _, _ -> TodoWriter.Result.Off },
    /** Where an extension's plugin (declared in its `initialize` result) is registered and removed. */
    private val pluginHost: ExtensionPluginHost? = null,
    /** Where `"secret": true` config fields live (ref [extSecretRef]); never the `config` file. */
    private val secrets: SecretStore,
    /** A logind sleep block for `keepAwake` (SleepGuard.holdAwake in the daemon). */
    private val holdAwake: (String) -> AutoCloseable = { AutoCloseable {} },
    /** Posts a host alert with one action button and runs the callback when it is pressed (the daemon's
     *  desktop prompt). Null: alerts go out as plain notifications, with the command to run instead. */
    private val alertWithAction: ((summary: String, body: String, actionLabel: String, onAction: () -> Unit) -> Unit)? = null,
) {
    private val running = ConcurrentHashMap<String, ExtensionProcess>()
    private val extSecrets = ExtensionSecrets(extDir, secrets)
    // `extension.<name>.<key>` secrets in stoandl.conf already warned about (once per daemon run).
    private val warnedConfSecrets = ConcurrentHashMap.newKeySet<String>()

    /** Invoked after a state change the `ExtList` view reflects (enable/disable/restart/install/
     *  uninstall) — set by PebbleIntegration to emit the `ExtensionsChanged` D-Bus signal so the GUI
     *  re-fetches even when the change came from the CLI or another client. (Crash-driven changes aren't
     *  covered — the GUI re-syncs on navigation/daemon-up.) */
    @Volatile var onChanged: (() -> Unit)? = null
    private fun notifyChanged() = onChanged?.invoke()

    /** Per-extension runtime state transition ((name, state) where state is "ready"/"exited"/
     *  "quarantined") — set by PebbleIntegration to emit the `ExtensionStateChanged` signal. This is the
     *  finer companion to [onChanged]: it surfaces UNSOLICITED changes (a crash, the restart-backoff loop,
     *  quarantine) that the user-op poke can't, so the GUI reflects a crashed/quarantined extension live. */
    @Volatile var onExtensionState: ((String, String) -> Unit)? = null

    fun start() {
        // A secret left in plaintext in any installed extension's config moves to the store now, not at
        // its next spawn: a disabled extension's config file still goes into a backup.
        extDir.listFiles()?.filter { it.isDirectory && !it.name.startsWith(".") && !it.name.startsWith("__") }
            ?.forEach { runCatching { extSecrets.migrate(it.name, extSecrets.flagsOf(it.name).secretKeys) } }
        if (enabledAtStart.isEmpty()) {
            log.info { "No extensions enabled (set extensions.enabled or run `stoandl ext install`)" }
        } else {
            log.info { "Starting ${enabledAtStart.size} extension(s): $enabledAtStart" }
            enabledAtStart.forEach { startProcess(it, awaitKeyring = true) }
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
                            // (Re)install declared watchapps for enabled extensions — idempotent, off the
                            // collector so a slow transfer doesn't stall connect/disconnect handling.
                            scope.launch { syncDeclaredWatchapps() }
                        }
                    }
            }
        }
    }

    // ---- lifecycle (hotplug) --------------------------------------------------------------------

    private enum class StartResult { STARTED, NEEDS_CONFIG, NO_ENTRYPOINT }

    /** [awaitKeyring]: at daemon start, a secret the keyring holds reads as missing until the keyring
     *  is up ([awaitKeyringThenStart]); every later start finds it running. */
    private fun startProcess(name: String, awaitKeyring: Boolean = false): StartResult {
        if (running.containsKey(name)) return StartResult.STARTED
        val flags = extSecrets.flagsOf(name)
        extSecrets.migrate(name, flags.secretKeys)
        val confLayer = extConfig[name] ?: emptyMap()
        val resolved = resolveExtension(name, extDir, confLayer) ?: return StartResult.NO_ENTRYPOINT
        val (def, fromConf) = withSecrets(resolved, flags.secretKeys, extSecrets.stored(name, flags.secretKeys), confLayer)
        fromConf.filter { warnedConfSecrets.add("$name:$it") }.forEach { k ->
            log.warn { "Extension '$name': secret '$k' is read from extension.$name.$k in stoandl.conf — set it in the extension's settings instead, then remove that line" }
        }
        // An extension that can't run until the user supplies settings: don't spawn a doomed child;
        // notify + bail gracefully so it's clear what to do.
        if (!configSatisfied(def, flags.requiredKeys)) {
            if (awaitKeyring && flags.secretKeys.isNotEmpty()) awaitKeyringThenStart(name) else notifyNeedsConfig(name)
            return StartResult.NEEDS_CONFIG
        }
        val proc = ExtensionProcess(def, watchNotifier, owners, libPebbleRef, scope,
            onState = { state ->
                if (state == "quarantined") notifyQuarantined(name, running[name]?.lastStderrLine)
                onExtensionState?.invoke(name, state)
            },
            notificationImagesOn = notificationImagesOn, transcribe = transcribe, synthesize = synthesize, prepareSynthesize = prepareSynthesize, pluginHost = pluginHost,
            holdAwake = holdAwake, addTodo = addTodo)
        running[name] = proc
        proc.launch()
        return StartResult.STARTED
    }

    /** Start [name] once a keyring that wasn't running yet comes up, as long as it is still enabled and
     *  stopped; alert only when none comes or the settings are still missing then. */
    private fun awaitKeyringThenStart(name: String) {
        scope.launch {
            if (!secrets.awaitLateBackend(KEYRING_WAIT)) notifyNeedsConfig(name)
            else if (name in currentEnabled() && !running.containsKey(name)) startProcess(name)
        }
    }

    /** Required-but-unconfigured extension: log it, and post a *desktop* notification (on the phone/
     *  laptop). stoandl's passive monitor bridges that to the watch automatically — so we don't push a
     *  direct watch notification, which would show up twice. */
    private fun notifyNeedsConfig(name: String) {
        val hint = settingsHint(name)
        log.warn { "[$name] requires configuration but none found — not starting. $hint" }
        if (!alertsAllowed()) return
        scope.launch(Dispatchers.IO) {
            sendDesktopNotification("$name needs setup", "$name can't start until it's set up. $hint")
        }
    }

    /** An extension that kept crashing was stopped ([ExtensionProcess] quarantine): say so with its last
     *  stderr line, which usually names the problem, and offer the restart. */
    private fun notifyQuarantined(name: String, lastStderrLine: String?) {
        if (!alertsAllowed()) return
        val summary = "$name stopped"
        val body = "$name kept crashing and was stopped." + (lastStderrLine?.let { " Last error: ${it.take(200)}" } ?: "")
        val prompt = alertWithAction
        scope.launch(Dispatchers.IO) {
            if (prompt != null) prompt(summary, body, "Restart") { restart(name) }
            else sendDesktopNotification(summary, "$body Restart it with: stoandl ext restart $name")
        }
    }

    /** Where [name] is configured, as one sentence for the needs-setup alert, the log and the `stoandl ext`
     *  replies: the GUI's settings form and `stoandl ext config` when its manifest declares a schema, else
     *  its `config` file (whose changes need a restart). */
    private fun settingsHint(name: String): String {
        if (hasConfigSchema(name)) {
            return "Settings: in the stoandl app (Apps → Extensions → $name → Settings) or stoandl ext config $name"
        }
        val config = File(File(extDir, name), "config")
        val start = if (!config.isFile && File(config.parentFile, "config.example").isFile) " (start from config.example)" else ""
        return "Settings: ${config.path}$start, then stoandl ext restart $name"
    }

    private fun hasConfigSchema(name: String): Boolean =
        configKindOf(readExtManifestMeta(File(extDir, name)).configSchemaJson) == "schema"

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
        // Skip dotfiles (e.g. our .install-tmp-*/.install-new-*) and python's __pycache__ — not extensions.
        val installed = extDir.listFiles()
            ?.filter { it.isDirectory && !it.name.startsWith(".") && !it.name.startsWith("__") }
            ?.map { it.name }?.toSet() ?: emptySet()
        return (installed + enabled).sorted().map { n ->
            val meta = readExtManifestMeta(File(extDir, n))
            // config-kind: `schema` when the manifest declares a configSchema, else `none` (there is no
            // `url` backend for extensions). Description is a single line.
            val configKind = configKindOf(meta.configSchemaJson)
            listOf(
                n,
                if (n in installed) "installed" else "missing",
                if (n in enabled) "enabled" else "disabled",
                if (running.containsKey(n)) "running" else "stopped",
                configKind,
                meta.description.replace('\t', ' ').replace('\n', ' '),
                meta.author.replace('\t', ' ').replace('\n', ' '),
                meta.version.replace('\t', ' ').replace('\n', ' '),
                running[n]?.lastStderrLine.orEmpty().replace('\t', ' ').replace('\n', ' '),
            ).joinToString("\t")
        }
    }

    /** The extension's typed config schema (its manifest `configSchema`) as a JSON array, for the GUI's
     *  native config form. `ok:<json-array>`, `none:` (no schema declared), or `notfound:`. */
    fun configSchema(name: String): String {
        val n = sanitize(name) ?: return "error:Invalid name"
        if (!isKnown(n)) return "notfound:'$name' is not installed"
        val json = readExtManifestMeta(File(extDir, n)).configSchemaJson
        return if (configKindOf(json) == "schema") "ok:$json" else "none:"
    }

    /** Install from an archive (.tar.gz/.tgz/.tar/.zip) or an unpacked directory (an extension being
     *  written, a git checkout) into `<extDir>/<name>/`, sideload a bundled `.pbw`, enable it, and
     *  hotplug-start it. A directory is copied (links as links, at most [MAX_INSTALL_BYTES]), so later
     *  edits need another install. */
    fun install(path: String): String {
        val src = File(path)
        if (src.isDirectory) {
            val from = src.canonicalFile
            // The install replaces <extDir>/<name>: a source inside it would be deleted first, and one
            // containing it (a parent of extDir) would be copied into itself.
            val target = File(extDir, nameFor(from, from.name)).canonicalFile
            if (from.startsWith(target) || target.startsWith(from)) {
                return "error:${src.path} overlaps the install location ${target.path}"
            }
            return installFrom(from, from.name)
        }
        if (!src.isFile) return "error:No such file or directory: $path"
        val tmp = File(extDir, ".install-tmp-${System.nanoTime()}")
        extDir.mkdirs(); deleteTree(tmp); tmp.mkdirs()
        try {
            if (!extract(src, tmp)) {
                return "error:Could not extract ${src.name} (need tar/unzip; supported: .tar.gz/.tgz/.tar/.zip, or a directory)"
            }
            // A single top-level dir is the extension root; otherwise the archive's files are the root.
            val entries = tmp.listFiles()?.filter { it.name != "__MACOSX" } ?: emptyList()
            val root = entries.singleOrNull { it.isDirectory } ?: tmp
            val stem = src.name.removeSuffix(".tar.gz").removeSuffix(".tgz").removeSuffix(".tar").removeSuffix(".zip")
            return installFrom(root, stem)
        } finally {
            deleteTree(tmp)
        }
    }

    /** Install the extension rooted at [root] (named by its manifest, else [fallbackName]). */
    private fun installFrom(root: File, fallbackName: String): String {
        val name = nameFor(root, fallbackName)
        if (name.isEmpty()) return "error:Could not determine an extension name from $fallbackName"

        // Copy into a staging dir next to the install and swap it in only once the copy worked, so a
        // failed copy leaves the previous install as it was. The staging and old dirs are dotfiles, which
        // [list] skips.
        extDir.mkdirs()
        val dest = File(extDir, name)
        val stage = File(extDir, ".install-new-${System.nanoTime()}")
        val old = File(extDir, ".install-old-${System.nanoTime()}")
        try {
            copyExtensionTree(root.toPath(), stage.toPath())?.let { why ->
                log.warn { "Copying ${root.path} for '$name' failed: $why" }
                return "error:Could not install '$name': $why"
            }
            // Keep the user's per-extension `config` across a reinstall (the source shouldn't carry one;
            // if it does, the source's wins).
            val config = File(dest, "config")
            if (config.isFile && !File(stage, "config").exists()) {
                Files.copy(config.toPath(), File(stage, "config").toPath(), StandardCopyOption.COPY_ATTRIBUTES)
            }
            stopProcess(name)
            if (dest.exists() && !dest.renameTo(old)) return "error:Could not replace the installed '$name'"
            if (!stage.renameTo(dest)) {
                old.renameTo(dest)
                return "error:Failed to install files for '$name'"
            }
        } finally {
            deleteTree(stage)
            deleteTree(old)
        }

        val pbwMsg = sideloadBundledPbw(dest)
        setEnabled(name, true)
        val result = startProcess(name)
        // Point the user at where this extension is configured (its own dir or form, not stoandl.conf).
        val configMsg = if (File(dest, "config").isFile || File(dest, "config.example").isFile || hasConfigSchema(name)) {
            ". ${settingsHint(name)}"
        } else ""
        notifyChanged()
        return when (result) {
            StartResult.STARTED -> "ok:Installed '$name'$pbwMsg$configMsg"
            StartResult.NEEDS_CONFIG -> "ok:Installed '$name' (not started — needs configuration)$pbwMsg$configMsg"
            StartResult.NO_ENTRYPOINT -> "error:Installed '$name' but couldn't start it (no entry point?)$pbwMsg$configMsg"
        }
    }

    /** The install name: the manifest's `name`, else [fallbackName], cut to letters, digits, `-` and `_`. */
    private fun nameFor(root: File, fallbackName: String): String =
        (manifestName(root) ?: fallbackName).substringAfterLast('/').filter { it.isLetterOrDigit() || it == '-' || it == '_' }

    fun uninstall(name: String, keepConfig: Boolean): String {
        val n = sanitize(name) ?: return "error:Invalid name"
        stopProcess(n)
        setEnabled(n, false)
        val dir = File(extDir, n)
        val existed = dir.isDirectory
        // Read before the directory goes: the manifest names the secret keys.
        if (!keepConfig) extSecrets.flagsOf(n).secretKeys.forEach { extSecrets.remove(n, it) }
        val watchMsg = if (existed) removeBundledWatchapp(dir) else ""
        // Optionally keep the user's `config` (so a later reinstall restores its settings); the install
        // path already preserves an existing config across an overwrite.
        val keptConfig = existed && keepConfig && File(dir, "config").isFile
        if (keptConfig) {
            dir.listFiles()?.forEach { if (it.name != "config") deleteTree(it) }
        } else {
            deleteTree(dir)
        }
        val tail = watchMsg + if (keptConfig) "; kept its config (${File(dir, "config").path})" else ""
        notifyChanged()
        return if (existed) "ok:Uninstalled '$n'$tail" else "ok:'$n' disabled (no files were installed)"
    }

    /** On uninstall, remove the extension's watchapp(s) from the watch: declared `watchapps` if any, else a
     *  bundled `.pbw` (legacy). Reads each pbw's UUID → removeApp. Best-effort status suffix. */
    private fun removeBundledWatchapp(dir: File): String = removeWatchapps(declaredOrBundledPbws(dir))

    /** Remove [pbws]' UUIDs from the watch's locker (best-effort status suffix). */
    private fun removeWatchapps(pbws: List<File>): String {
        if (pbws.isEmpty()) return ""
        val lp = libPebbleRef.get() ?: return "; its watchapp left on the watch (no watch connected)"
        val uuids = pbws.mapNotNull { runCatching { Uuid.parse(PbwApp(kotlinx.io.files.Path(it.absolutePath)).info.uuid) }.getOrNull() }
        if (uuids.isEmpty()) return "; couldn't read watchapp UUID (left on the watch)"
        return try {
            if (runBlocking { uuids.map { lp.removeApp(it) }.any { it } }) "; removed its watchapp from the watch"
            else "; watchapp removal not confirmed"
        } catch (e: Exception) {
            log.warn(e) { "removeApp failed" }
            "; watchapp removal failed (${e.message})"
        }
    }

    fun enable(name: String): String {
        val n = sanitize(name) ?: return "error:Invalid name"
        if (!File(extDir, n).isDirectory) return "error:'$n' is not installed (no ${extDir.path}/$n)"
        setEnabled(n, true)
        val pbwMsg = sideloadBundledPbw(File(extDir, n))
        val result = when (startProcess(n)) {
            StartResult.STARTED -> "ok:Enabled '$n'$pbwMsg"
            StartResult.NEEDS_CONFIG -> "ok:Enabled '$n' (not started — needs configuration)$pbwMsg. ${settingsHint(n)}"
            StartResult.NO_ENTRYPOINT -> "error:Enabled '$n' but couldn't start it (no entry point?)$pbwMsg"
        }
        notifyChanged()
        return result
    }

    fun disable(name: String): String {
        val n = sanitize(name) ?: return "error:Invalid name"
        stopProcess(n)
        setEnabled(n, false)
        // Declared (managed) watchapps come off the watch on disable; an undeclared legacy bundled .pbw
        // is left in place (it's only managed at install/uninstall time).
        val watchMsg = removeWatchapps(declaredWatchappFiles(File(extDir, n)))
        notifyChanged()
        return "ok:Disabled '$n'$watchMsg"
    }

    fun restart(name: String): String {
        val n = sanitize(name) ?: return "error:Invalid name"
        stopProcess(n)
        val result = when (startProcess(n)) {
            StartResult.STARTED -> "ok:Restarted '$n'"
            StartResult.NEEDS_CONFIG -> "ok:'$n' not started — needs configuration. ${settingsHint(n)}"
            StartResult.NO_ENTRYPOINT -> "error:Couldn't start '$n'"
        }
        notifyChanged()
        return result
    }

    /** The extension's effective settings as a JSON object of string values, for the GUI's native config
     *  form: manifest defaults overlaid by its `<extDir>/<name>/config` file, every secret blanked, and
     *  `_secrets` naming the stored ones ([effectiveConfigView]). `ok:<json>` or `notfound:`. */
    fun getConfig(name: String): String {
        val n = sanitize(name) ?: return "error:Invalid name"
        if (!isKnown(n)) return "notfound:'$name' is not installed"
        val values = extSecrets.configView(n)
        val json = buildJsonObject { values.forEach { (k, v) -> put(k, v) } }
        return "ok:" + LenientJson.encodeToString(JsonObject.serializer(), json)
    }

    /** Merge [payload] (a JSON object of key→value) into the extension's settings — secret keys into the
     *  secret store ([splitConfigPayload]: `""` keeps, `null` removes), the rest into its `config` file (an
     *  atomic write that preserves comments and unchanged keys) — then restart it if running so the change
     *  takes effect. `ok:`/`notfound:`/`error:`. */
    fun setConfig(name: String, payload: String): String {
        val n = sanitize(name) ?: return "error:Invalid name"
        if (!isKnown(n)) return "notfound:'$name' is not installed"
        val obj = try {
            LenientJson.parseToJsonElement(payload).jsonObject
        } catch (e: Exception) {
            return "error:Invalid JSON payload: ${e.message}"
        }
        return try {
            val plain = extSecrets.applyPayload(n, obj) ?: return "ok:No changes for '$n'"
            if (plain.isNotEmpty()) writeConfigUpdates(File(extDir, n), plain)
            // Apply immediately if it's running (config is read in the initialize handshake).
            if (running.containsKey(n)) {
                stopProcess(n)
                startProcess(n)
                // A failed restart (the new config removed the entry point / now needs config) flips it
                // running→stopped — an ExtList change, so poke. The common running→running case doesn't.
                if (!running.containsKey(n)) notifyChanged()
                "ok:Saved settings for '$n'"
            } else if (n in currentEnabled()) {
                // Enabled but stopped (typically NEEDS_CONFIG until a required secret is saved): start it now.
                val result = when (startProcess(n)) {
                    StartResult.STARTED -> "ok:Saved settings for '$n' and started it"
                    StartResult.NEEDS_CONFIG -> "ok:Saved settings for '$n' (not started — still needs configuration)"
                    StartResult.NO_ENTRYPOINT -> "ok:Saved settings for '$n' (couldn't start it: no entry point?)"
                }
                notifyChanged()
                result
            } else {
                "ok:Saved settings for '$n'"
            }
        } catch (e: Exception) {
            log.warn(e) { "setConfig($n) failed" }
            "error:${e.message ?: "failed to save settings"}"
        }
    }

    // ---- helpers --------------------------------------------------------------------------------

    /** The GUI config-kind for an extension, derived from its manifest `configSchema`: `schema` when a
     *  non-empty schema is declared (the GUI renders a native form), else `none`. There is no `url`
     *  backend (a hosted config page) for extensions, so it never appears here. Single source of truth for [list] and [configSchema]. */
    private fun configKindOf(configSchemaJson: String?): String =
        if (!configSchemaJson.isNullOrBlank() && configSchemaJson != "[]") "schema" else "none"

    /** A known extension = an installed dir or an enabled name (the same set [list] reports). */
    private fun isKnown(name: String): Boolean =
        File(extDir, name).isDirectory || name in currentEnabled()

    /** Upsert [updates] into `<dir>/config` (atomic, comment- and unchanged-key-preserving). Shares the
     *  writer + lock with [setEnabled] and `SetConfig` so concurrent config writes can't corrupt a file. */
    private fun writeConfigUpdates(dir: File, updates: Map<String, String>) =
        ConfFile.upsert(File(dir, "config"), updates)

    private fun sanitize(name: String): String? =
        name.trim().takeIf { it.isNotEmpty() && it.all { c -> c.isLetterOrDigit() || c == '-' || c == '_' } }

    /** Install the extension's watchapp(s) on the watch: the manifest-declared `watchapps` if any, else a
     *  single bundled `.pbw` (legacy). Idempotent via [ensureSideloaded]. Best-effort status suffix. */
    private fun sideloadBundledPbw(dir: File): String {
        val pbws = declaredOrBundledPbws(dir)
        if (pbws.isEmpty()) return ""
        val lp = libPebbleRef.get() ?: return "; watchapp not installed (no watch connected — `stoandl sideload` it later)"
        val names = pbws.joinToString(", ") { it.name }
        return try {
            val ok = runBlocking { pbws.map { ensureSideloaded(lp, it) }.all { it } }
            if (ok) "; installed $names on the watch" else "; $names sideload failed"
        } catch (e: Exception) {
            log.warn(e) { "sideload watchapp(s) in ${dir.name} failed" }
            "; watchapp sideload failed (${e.message})"
        }
    }

    /** Declared `watchapps` resolved to existing files in [dir]. */
    private fun declaredWatchappFiles(dir: File): List<File> =
        declaredWatchapps(dir).map { File(dir, it) }.filter { it.isFile }

    /** The extension's watchapp pbw files: declared `watchapps` if present, else a single top-level bundled
     *  `.pbw` (legacy back-compat for extensions that don't declare). */
    private fun declaredOrBundledPbws(dir: File): List<File> {
        val declared = declaredWatchappFiles(dir)
        if (declared.isNotEmpty()) return declared
        return listOfNotNull(dir.listFiles()?.firstOrNull { it.extension.equals("pbw", ignoreCase = true) })
    }

    /** (Re)install the declared watchapps of all enabled extensions onto the connected watch. Idempotent
     *  ([ensureSideloaded] skips when the locker already holds the version), so it's safe on every connect.
     *  Declared-only: an undeclared bundled `.pbw` is left to the install/enable-time legacy sideload. */
    private suspend fun syncDeclaredWatchapps() {
        val lp = libPebbleRef.get() ?: return
        val enabled = runCatching { currentEnabled() }.getOrElse { emptyList() }
        enabled.forEach { name ->
            declaredWatchappFiles(File(extDir, name)).forEach { pbw ->
                runCatching { ensureSideloaded(lp, pbw) }
                    .onFailure { log.warn(it) { "[$name] watchapp sync failed for ${pbw.name}" } }
            }
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
        ConfList.decode(line.substringBefore('#').substringAfter('='))

    private fun currentEnabled(): List<String> {
        if (!confFile.isFile) return emptyList()
        return confFile.readLines().firstOrNull { isEnabledLine(it) }?.let { namesIn(it) } ?: emptyList()
    }

    /** Add/remove [name] from the `extensions.enabled` line in stoandl.conf. Read-modify-write under the
     *  shared [ConfFile] lock (so it can't race `SetConfig`/`ExtSetConfig`); the upsert rewrites just that
     *  line in place (preserving its inline comment) or appends it. */
    private fun setEnabled(name: String, enabled: Boolean) = ConfFile.withLock {
        val names = LinkedHashSet(currentEnabled())
        if (enabled) names.add(name) else names.remove(name)
        try {
            ConfFile.upsert(confFile, mapOf(ENABLED_KEY to ConfList.encode(names.toList())))
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
// How long a start waits for a keyring the session hasn't started yet (KWallet came ~10 s after the daemon on op6).
private val KEYRING_WAIT = 2.minutes

/** One supervised extension child: spawn → handshake → relay, restart-with-backoff on exit. Owns a
 *  child scope so [shutdown] cleanly stops the supervise loop, the inbound collectors, and the child. */
/** Where extensions' plugins go: libpebble3's PluginRegistry in the daemon. */
interface ExtensionPluginHost {
    fun register(plugin: io.rebble.libpebblecommon.plugin.Plugin)
    fun unregister(pluginUuid: Uuid)
}

/** How long a host → extension request (readSource, invokeAction) waits for its answer. */
private const val REQUEST_TIMEOUT_MS = 15_000L

private class ExtensionProcess(
    private val def: ExtensionDef,
    private val watchNotifier: WatchNotifier,
    owners: NotifOwnerRegistry,
    private val libPebbleRef: AtomicReference<LibPebble?>,
    parentScope: CoroutineScope,
    // Runtime state transitions for the ExtensionStateChanged signal: "ready" (handshake done), "exited"
    // (process ended unexpectedly, restarting after backoff), "quarantined" (gave up after rapid failures).
    // A graceful shutdown() doesn't emit — these are gated on !shuttingDown, which closes the narrow race
    // where the child exits at the same instant shutdown() lands (cancellation alone wouldn't suppress it).
    private val onState: (String) -> Unit = {},
    private val notificationImagesOn: () -> Boolean = { false },
    private val transcribe: suspend (path: String, language: String?) -> String = { _, _ -> "off:" },
    private val synthesize: suspend (text: String, language: String?, format: String) -> Synthesis = { _, _, _ -> Synthesis.Error("off") },
    private val prepareSynthesize: (language: String?) -> Unit = {},
    private val pluginHost: ExtensionPluginHost? = null,
    holdAwake: (String) -> AutoCloseable = { AutoCloseable {} },
    private val addTodo: suspend (title: String, due: String, note: String) -> TodoWriter.Result = { _, _, _ -> TodoWriter.Result.Off },
) {
    private val tag = def.name
    // Host → extension requests (readSource, invokeAction) awaiting their reply, by JSON-RPC id. The
    // initialize request uses id 0, so these start at 1.
    private val nextRequestId = java.util.concurrent.atomic.AtomicLong(1)
    private val pendingRequests = ConcurrentHashMap<Long, CompletableDeferred<JsonElement>>()
    private val sourceChanges = MutableSharedFlow<Pair<String, String>>(extraBufferCapacity = 16)
    @Volatile private var plugin: ExtensionPlugin? = null
    /** The child's last non-blank stderr line (kept across restarts): what a quarantine alert shows. */
    @Volatile var lastStderrLine: String? = null
        private set
    private val job = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + job)
    @Volatile private var writeChannel: Channel<String>? = null
    @Volatile private var proc: Process? = null
    @Volatile private var ready = false
    // Set at the top of shutdown(); suppresses the unsolicited onState emits so a graceful stop is silent
    // even if the child happens to exit / finish its handshake on the same tick the job is cancelled.
    @Volatile private var shuttingDown = false
    private val owner = ExtensionOwner(def.name) { obj -> enqueue(obj) }.also { owners.register(it) }
    private val registeredApps = ConcurrentHashMap.newKeySet<Uuid>()
    private val awake = AwakeHolds(def.name, scope, holdAwake)
    /** The `synthesize` in flight. A new one replaces it: a reading that asks for its next sentence while one
     *  plays only wants its latest request, and the engine is freed for it at once rather than answering `busy`.
     *  `cancelSynthesize` drops it without a replacement. */
    @Volatile private var synthesizing: Job? = null

    fun onWatchConnected() {
        if (!ready) return
        enqueue(buildJsonObject {
            put("jsonrpc", "2.0"); put("method", "onWatchConnected")
            // Whether a picture attached to `notify` will show on this watch, so the extension can pick its
            // own fallback (a preview watchapp) otherwise. Re-sent on every connect: a different watch may
            // have connected.
            put("params", buildJsonObject {
                put("notificationImages", notificationImagesOn() &&
                    connectedDevice()?.watchInfo?.capabilities?.contains(ProtocolCapsFlag.SupportsNotificationImages) == true)
            })
        })
    }

    fun shutdown() {
        shuttingDown = true      // suppress the unsolicited onState emits — this stop is deliberate
        ready = false
        pluginGone()
        writeChannel = null      // stop new enqueues before tearing down
        val p = proc
        job.cancel()             // cancels the supervise loop + writer/reader/collector coroutines
        awake.close()
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
                pluginGone()
                if (!isActive) break
                val uptime = System.currentTimeMillis() - startedAt
                fastFailures = if (uptime >= STABLE_UPTIME_MS) 0 else fastFailures + 1
                if (fastFailures >= MAX_FAST_FAILURES) {
                    log.error { "[$tag] quarantined after $fastFailures rapid failures — fix it and `stoandl ext restart $tag`" }
                    if (!shuttingDown) onState("quarantined")
                    return@launch
                }
                if (!shuttingDown) onState("exited")
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
            try {
                process.errorStream.bufferedReader().forEachLine { line ->
                    log.info { "[$tag] $line" }
                    if (line.isNotBlank()) lastStderrLine = line.trim()
                }
            } catch (e: Exception) { log.debug { "[$tag] stderr reader ended: ${e.message}" } }
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
        awake.releaseAll()       // no hold outlives its child (shutdown() covers the cancelled path)
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
            method == null && (obj.containsKey("result") || obj.containsKey("error")) &&
                id?.jsonPrimitive?.longOrNull?.let { pendingRequests.remove(it) }?.also { d ->
                    obj["result"]?.let { d.complete(it) }
                        ?: d.completeExceptionally(IllegalStateException(
                            obj["error"]?.jsonObject?.str("message") ?: "the extension answered with an error"))
                } != null -> {}
            method == null && obj.containsKey("result") -> handleResult(obj)
            method == "sourceChanged" -> {
                val c = params?.str("category"); val i = params?.str("item")
                if (c != null && i != null) sourceChanges.tryEmit(c to i)
            }
            method == "notify" -> handleNotify(id, params)
            method == "closeNotification" -> handleClose(params)
            method == "registerApp" -> handleRegisterApp(id, params)
            method == "sendAppMessage" -> handleSendAppMessage(id, params)
            method == "launchApp" -> handleAppRunState(id, params, launch = true)
            method == "stopApp" -> handleAppRunState(id, params, launch = false)
            method == "installPbw" -> handleInstallPbw(id, params)
            // Off the read loop: a transcription or a synthesis takes seconds.
            method == "transcribe" -> scope.launch { handleTranscribe(id, params) }
            method == "addTodo" -> scope.launch { handleAddTodo(id, params) }
            method == "synthesize" -> {
                val before = synthesizing
                val answered = AtomicBoolean(false)
                synthesizing = scope.launch {
                    before?.cancelAndJoin()
                    handleSynthesize(id, params)
                    answered.set(true)
                }.apply {
                    // Every id gets an answer: a newer request can cancel this one before it starts, or while
                    // it waits for the one it replaced, and the extension's timeout must not stand in for it.
                    invokeOnCompletion { e ->
                        if (e == null || answered.get()) return@invokeOnCompletion
                        val cancelled = e is kotlin.coroutines.cancellation.CancellationException
                        replyError(id, if (cancelled) "cancelled" else "synthesize: ${e.message}")
                    }
                }
            }
            // The extension stopped the reading it asked for: free the engine for the next request now. The
            // sentence in progress finishes unheard (a resident Piper keeps its voice loaded for the next
            // reading; ResidentPiper). Answered "cancelled".
            method == "cancelSynthesize" -> { synthesizing?.cancel(); reply(id) {} }
            // A reading is likely soon (its app opened, a question is on its way): load the voice now.
            method == "prepareSynthesize" -> { prepareSynthesize(params?.str("language")); reply(id) {} }
            method == "keepAwake" -> {
                val maxMs = params?.get("maxMs")?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.longOrNull }
                awake.acquire(params?.str("why"), maxMs).fold(
                    onSuccess = { token -> reply(id) { put("token", token) } },
                    onFailure = { replyError(id, it.message ?: "keepAwake failed") },
                )
            }
            method == "release" -> {
                val token = params?.str("token")
                if (token == null) replyError(id, "release: missing token") else { awake.release(token); reply(id) {} }
            }
            method == "log" -> log.info { "[$tag] ${params?.str("message") ?: ""}" }
            method == "initialize" -> {}
            else -> {
                log.debug { "[$tag] unhandled message: method=$method" }
                // A request this daemon doesn't know (an extension newer than it) fails at once, not by timeout.
                if (method != null && id != null) replyError(id, "method not found: $method", code = -32601)
            }
        }
    }

    /** Send a host → extension request and wait for its result (throws on an error reply or no answer). */
    private suspend fun request(method: String, params: JsonObject): JsonElement {
        val id = nextRequestId.getAndIncrement()
        val reply = CompletableDeferred<JsonElement>()
        pendingRequests[id] = reply
        enqueue(buildJsonObject { put("jsonrpc", "2.0"); put("id", id); put("method", method); put("params", params) })
        return try {
            withTimeoutOrNull(REQUEST_TIMEOUT_MS) { reply.await() } ?: error("$tag didn't answer $method within ${REQUEST_TIMEOUT_MS / 1000} s")
        } finally {
            pendingRequests.remove(id)
        }
    }

    private fun pluginGone() {
        pendingRequests.values.forEach { it.completeExceptionally(IllegalStateException("$tag stopped")) }
        pendingRequests.clear()
        plugin?.let { p -> pluginHost?.unregister(p.pluginUuid); plugin = null; log.info { "[$tag] plugin '${p.name}' removed" } }
    }

    private fun handleResult(obj: JsonObject) {
        val manifest = obj["result"]?.jsonObject ?: return
        ExtensionPluginManifest.parse(manifest["plugin"])?.let { m ->
            val p = ExtensionPlugin(tag, m, manifest.str("name") ?: tag, ::request, sourceChanges)
            plugin = p
            pluginHost?.register(p)
            log.info { "[$tag] serves plugin '${p.name}' (${p.pluginUuid}): sources ${p.sources.flatMap { s -> s.items.map { "${s.category}/$it" } }}, actions ${p.actions.map { it.name }}" }
        }
        val caps = manifest["capabilities"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()
        ready = true
        if (!shuttingDown) onState("ready")
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
            // An empty list falls back to notification.canned_replies (in WatchNotifier). "allowVoice" is
            // no longer read: the firmware offers Voice on any watch with a microphone regardless.
            ReplySpec(canned, r.str("label") ?: "Reply")
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
            // A picture: a data: URI (base64), a file: URI or an absolute path.
            image = params.str("image")?.let(ImageSource::parse),
            // Sent even while notification.only_when_locked holds the rest back (an answer the user just
            // asked for from the wrist).
            urgent = params["urgent"]?.jsonPrimitive?.booleanOrNull == true,
        )
        val itemId = watchNotifier.push(req, def.name, extToken)
        if (id != null) reply(id) { put("itemId", itemId?.toString()) }
    }

    /** `transcribe {path, language?}` → `{text}`, or an error `off` / `unavailable: …` / the failure. The
     *  daemon's speech-to-text (voice.*): the extension needs no engine, codec or egress of its own. */
    private suspend fun handleTranscribe(id: JsonElement?, params: JsonObject?) {
        val path = params?.str("path") ?: run { replyError(id, "transcribe: missing path"); return }
        val result = transcribe(path, params.str("language"))
        val colon = result.indexOf(':')
        val kind = result.substring(0, colon.coerceAtLeast(0))
        val body = result.substring(colon + 1)
        if (kind == "ok") reply(id) { put("text", body.substringAfter('\t')) }
        else replyError(id, if (kind == "off") "off" else "$kind: $body")
    }

    /** `addTodo {title, due?, note?}` → `{status: "saved"}` or `{status: "queued", problem}` (kept on this
     *  device for a retry; problem empty when the server couldn't be reached), or an error `off` / why it
     *  failed. The daemon's to-do list (`calendar.todos`): the extension needs no CalDAV sign-in of its own. */
    private suspend fun handleAddTodo(id: JsonElement?, params: JsonObject?) {
        val title = params?.str("title")?.trim().orEmpty()
        if (title.isEmpty()) { replyError(id, "addTodo: missing title"); return }
        when (val r = addTodo(title, params?.str("due").orEmpty(), params?.str("note").orEmpty())) {
            TodoWriter.Result.Saved -> reply(id) { put("status", "saved") }
            is TodoWriter.Result.Queued -> reply(id) { put("status", "queued"); put("problem", r.problem) }
            TodoWriter.Result.Off -> replyError(id, "off")
            is TodoWriter.Result.Failed -> replyError(id, r.message)
        }
    }

    /** `synthesize {text, language?, format?}` → `{blocks:[base64…], ms}` (`pebble-adpcm`, the default) or
     *  `{path, ms}` (`wav`), or an error `off` / `unavailable: …` / `busy` / `cancelled` (a newer `synthesize`
     *  replaced it) / the failure. The daemon's text to speech (tts.*): the extension needs no engine of its own. */
    private suspend fun handleSynthesize(id: JsonElement?, params: JsonObject?) {
        val text = params?.str("text") ?: run { replyError(id, "synthesize: missing text"); return }
        val result = synthesize(text, params.str("language"), params.str("format") ?: "pebble-adpcm")
        synthesisResult(result)?.let { r -> reply(id) { r.forEach { (k, v) -> put(k, v) } } }
            ?: replyError(id, (result as Synthesis.Error).message)
    }

    private suspend fun handleClose(params: JsonObject?) {
        val itemId = params?.str("itemId")?.let { runCatching { Uuid.parse(it) }.getOrNull() } ?: return
        watchNotifier.retract(itemId)
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
        val ok = try { ensureSideloaded(lp, abs) } catch (e: Exception) { replyError(id, e.message ?: "install failed"); return }
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

    private fun replyError(id: JsonElement?, message: String, code: Int? = null) {
        if (id == null) { log.warn { "[$tag] $message" }; return }
        enqueue(buildJsonObject {
            put("jsonrpc", "2.0"); put("id", id)
            put("error", buildJsonObject { code?.let { put("code", it) }; put("message", message) })
        })
    }
}

private fun JsonObject.str(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull

/** A `synthesize` answer's result object, or null for an error (its message is the error). */
internal fun synthesisResult(r: Synthesis): JsonObject? = when (r) {
    is Synthesis.Blocks -> buildJsonObject {
        putJsonArray("blocks") { r.blocks.forEach { add(java.util.Base64.getEncoder().encodeToString(it)) } }
        put("ms", r.ms)
    }
    is Synthesis.WavFile -> buildJsonObject { put("path", r.path); put("ms", r.ms) }
    is Synthesis.Error -> null
}

/**
 * Sideload [pbw] only when the locker doesn't already hold its UUID at the same `versionLabel`. This makes
 * (re)install idempotent so it's safe to call on every watch connect: `sideloadApp` restamps
 * `sideloadeTimestamp` (part of the BlobDB per-watch sync hash), so calling it when nothing changed forces
 * a redundant re-push to the watch. The locker is the source of truth — a reset watch is re-synced from it
 * automatically on reconnect — so skipping a no-op install can't leave the watch missing the app. Bump the
 * .pbw's version to ship an update. Best-effort: if the version can't be read it falls back to installing.
 * Returns true if the app is present at the bundled version afterward.
 */
private suspend fun ensureSideloaded(lp: LibPebble, pbw: File): Boolean {
    val pbwPath = kotlinx.io.files.Path(pbw.absolutePath)
    val info = runCatching { PbwApp(pbwPath).info }.getOrNull()
    val uuid = info?.let { runCatching { Uuid.parse(it.uuid) }.getOrNull() }
    if (info != null && uuid != null) {
        val installed = withTimeoutOrNull(2.seconds) {
            runCatching { lp.getLockerApp(uuid).first()?.properties?.version }.getOrNull()
        }
        if (installed == info.versionLabel) return true // already current — skip the redundant re-push
    }
    return lp.sideloadApp(pbwPath)
}

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
