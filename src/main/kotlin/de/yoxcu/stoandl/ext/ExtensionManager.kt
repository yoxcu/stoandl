@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package de.yoxcu.stoandl.ext

import de.yoxcu.stoandl.config.StoandlConfig.ExtensionDef
import de.yoxcu.stoandl.pebble.NotifAction
import de.yoxcu.stoandl.pebble.NotifOwner
import de.yoxcu.stoandl.pebble.NotifRequest
import de.yoxcu.stoandl.pebble.ReplySpec
import de.yoxcu.stoandl.pebble.WatchNotifier
import io.github.oshai.kotlinlogging.KotlinLogging
import io.rebble.libpebblecommon.connection.ConnectedPebbleDevice
import io.rebble.libpebblecommon.connection.LibPebble
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.concurrent.atomic.AtomicReference
import kotlin.uuid.Uuid
import de.yoxcu.stoandl.util.LenientJson

private val log = KotlinLogging.logger {}

/**
 * Spawns and supervises user "extensions" — host-side companion processes (Matrix, Signal, SMS,
 * find-my-phone, …) in any language — that drive watch notifications without a watchapp. Each is a
 * child process speaking newline-delimited JSON-RPC over stdio (see docs/extensions.md). This is the
 * Phase-0/1 MVP: notify + reply/action/dismiss callbacks. AppMessage/watchapp companions (Phase 3) are
 * not wired yet.
 *
 * Every extension's notifications go through the shared [WatchNotifier] choke point, so per-app
 * mute/style applies to them exactly as it does to desktop notifications.
 */
class ExtensionManager(
    private val defs: List<ExtensionDef>,
    private val watchNotifier: WatchNotifier,
    private val libPebbleRef: AtomicReference<LibPebble?>,
    private val scope: CoroutineScope,
) {
    fun start() {
        if (defs.isEmpty()) {
            log.info { "No extensions configured (set extensions.enabled + extension.<name>.cmd)" }
            return
        }
        log.info { "Starting ${defs.size} extension(s): ${defs.map { it.name }}" }
        val processes = defs.map { def -> ExtensionProcess(def, watchNotifier, libPebbleRef, scope).also { it.launch() } }
        // Tell extensions when a watch (re)connects so they can re-arm their notifications. Until the
        // Phase-2 re-push lands, a notify() issued while disconnected is dropped, so this edge is how a
        // long-lived extension (e.g. find-my-phone) gets its notification back onto a fresh connection.
        libPebbleRef.get()?.let { lp ->
            scope.launch {
                lp.watches
                    .map { devices -> devices.any { it is ConnectedPebbleDevice } }
                    .distinctUntilChanged()
                    .collect { connected -> if (connected) processes.forEach { it.onWatchConnected() } }
            }
        }
    }
}

private const val MAX_FAST_FAILURES = 5
private const val STABLE_UPTIME_MS = 60_000L
private const val BACKOFF_BASE_MS = 1_000L
private const val BACKOFF_MAX_MS = 60_000L
private const val INIT_TIMEOUT_MS = 10_000L

/** One supervised extension child: spawn → handshake → relay, restart-with-backoff on exit. */
private class ExtensionProcess(
    private val def: ExtensionDef,
    private val watchNotifier: WatchNotifier,
    private val libPebbleRef: AtomicReference<LibPebble?>,
    private val scope: CoroutineScope,
) {
    private val tag = def.name
    // The owner is stable across restarts; the write channel is swapped on each spawn.
    @Volatile private var writeChannel: Channel<String>? = null
    @Volatile private var ready = false
    private val owner = ExtensionOwner(def.name) { obj -> enqueue(obj) }

    /** Notify the child that a watch connected (so it can re-arm). No-op until the handshake completes. */
    fun onWatchConnected() {
        if (!ready) return
        enqueue(buildJsonObject {
            put("jsonrpc", "2.0")
            put("method", "onWatchConnected")
            put("params", buildJsonObject {})
        })
    }

    private fun enqueue(obj: JsonObject) {
        val ch = writeChannel
        if (ch == null) {
            log.debug { "[$tag] drop outgoing (no live process): ${obj["method"]}" }
            return
        }
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
                val uptime = System.currentTimeMillis() - startedAt
                fastFailures = if (uptime >= STABLE_UPTIME_MS) 0 else fastFailures + 1
                if (fastFailures >= MAX_FAST_FAILURES) {
                    log.error { "[$tag] quarantined after $fastFailures rapid failures — not restarting (fix it and restart the daemon)" }
                    return@launch
                }
                val backoff = (BACKOFF_BASE_MS shl (fastFailures - 1).coerceAtLeast(0)).coerceAtMost(BACKOFF_MAX_MS)
                log.info { "[$tag] exited (uptime ${uptime}ms); restarting in ${backoff}ms" }
                delay(backoff)
            }
        }
    }

    private suspend fun runOnce() {
        val cmd = if (def.confine) {
            listOf("systemd-run", "--user", "--scope", "--quiet", "--collect", "-p", "MemoryMax=256M") + def.command
        } else {
            def.command
        }
        log.info { "[$tag] spawning: ${cmd.joinToString(" ")}${if (def.confine) " (confined)" else ""}" }
        val proc = ProcessBuilder(cmd).redirectErrorStream(false).start()
        val ch = Channel<String>(Channel.UNLIMITED)
        writeChannel = ch

        val writer = proc.outputStream.bufferedWriter()
        val writerJob = scope.launch(Dispatchers.IO) {
            try {
                for (line in ch) { writer.write(line); writer.write("\n"); writer.flush() }
            } catch (e: Exception) {
                log.debug { "[$tag] writer ended: ${e.message}" }
            }
        }
        val stderrJob = scope.launch(Dispatchers.IO) {
            try {
                proc.errorStream.bufferedReader().forEachLine { log.info { "[$tag] $it" } }
            } catch (_: Exception) {
            }
        }
        // Read stdout off-thread into a channel, process sequentially (preserves per-extension order
        // while keeping blocking IO off the suspend handlers).
        val inbound = Channel<String>(Channel.UNLIMITED)
        val readerJob = scope.launch(Dispatchers.IO) {
            try {
                proc.inputStream.bufferedReader().forEachLine { inbound.trySend(it) }
            } finally {
                inbound.close()
            }
        }
        val processorJob = scope.launch {
            for (line in inbound) handleLine(line)
        }

        sendInitialize()
        scope.launch {
            delay(INIT_TIMEOUT_MS)
            if (!ready) log.warn { "[$tag] no initialize response after ${INIT_TIMEOUT_MS}ms — extension may be stuck" }
        }

        withContext(Dispatchers.IO) { proc.waitFor() }
        ch.close()
        writeChannel = null
        writerJob.cancel(); stderrJob.cancel(); readerJob.cancel(); processorJob.cancel()
        try { writer.close() } catch (_: Exception) {}
    }

    private fun sendInitialize() {
        enqueue(buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", 0)
            put("method", "initialize")
            put("params", buildJsonObject {
                put("protocolVersion", 1)
                put("config", buildJsonObject { def.config.forEach { (k, v) -> put(k, v) } })
                put("granted", buildJsonArray { def.allow.forEach { add(it) } })
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
            log.warn { "[$tag] ignoring non-JSON line: ${line.take(200)}" }
            return
        }
        val method = obj["method"]?.jsonPrimitive?.contentOrNull
        when {
            method == null && obj.containsKey("result") -> handleResult(obj)
            method == "notify" -> handleNotify(obj["id"], obj["params"]?.jsonObject)
            method == "closeNotification" -> handleClose(obj["params"]?.jsonObject)
            method == "log" -> log.info { "[$tag] ${obj["params"]?.jsonObject?.str("message") ?: ""}" }
            method == "initialize" -> {} // host→ext only; ignore an echo
            else -> log.debug { "[$tag] unhandled message: method=$method" }
        }
    }

    private fun handleResult(obj: JsonObject) {
        // The only host→ext request in this phase is initialize (id 0); its result is the manifest.
        val manifest = obj["result"]?.jsonObject ?: return
        val claimed = manifest["capabilities"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }?.toSet() ?: emptySet()
        val beyond = claimed.filterNot { cap -> def.allow.any { it == cap || it.substringBefore(':') == cap } }
        if (beyond.isNotEmpty()) {
            log.warn { "[$tag] manifest claims ungranted capabilities $beyond — ignored (grant them via extension.$tag.allow)" }
        }
        ready = true
        log.info { "[$tag] initialized (granted: ${def.allow}; name='${manifest.str("name") ?: tag}')" }
    }

    private suspend fun handleNotify(id: JsonElement?, params: JsonObject?) {
        if (params == null) { replyError(id, "missing params"); return }
        if (!def.allow.contains("notify")) {
            replyError(id, "not permitted: grant 'notify' via extension.$tag.allow")
            return
        }
        val extToken = params.str("extToken")
        val actions = params["actions"]?.jsonArray?.mapNotNull { e ->
            val o = e.jsonObject
            val aid = o.str("id"); val label = o.str("label")
            if (aid != null && label != null) NotifAction(aid, label) else null
        } ?: emptyList()
        val reply = params["reply"]?.jsonObject?.let { r ->
            val canned = r["cannedReplies"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()
            ReplySpec(canned, r["allowVoice"]?.jsonPrimitive?.booleanOrNull ?: false)
        }
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
        )
        val itemId = watchNotifier.push(req, owner)
        if (itemId != null && extToken != null) owner.bind(itemId, extToken)
        if (id != null) {
            enqueue(buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", id)
                put("result", buildJsonObject { put("itemId", itemId?.toString()) })
            })
        }
    }

    private suspend fun handleClose(params: JsonObject?) {
        val itemId = params?.str("itemId")?.let { runCatching { Uuid.parse(it) }.getOrNull() } ?: return
        watchNotifier.close(itemId)
        owner.unbind(itemId)
    }

    private fun replyError(id: JsonElement?, message: String) {
        if (id == null) { log.warn { "[$tag] $message" }; return }
        enqueue(buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            put("error", buildJsonObject { put("message", message) })
        })
    }
}

private fun JsonObject.str(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull

/**
 * [NotifOwner] for an extension: maps the watch item back to the extension's own correlation token and
 * forwards the user's wrist action/reply/dismiss as a JSON-RPC notification to the child (enqueued —
 * never blocks libpebble3's action flow). The extension then acts via its own service account.
 */
private class ExtensionOwner(
    private val name: String,
    private val send: (JsonObject) -> Unit,
) : NotifOwner {
    override val id = name
    private val itemToToken = java.util.concurrent.ConcurrentHashMap<Uuid, String>()

    fun bind(itemId: Uuid, extToken: String) { itemToToken[itemId] = extToken }
    fun unbind(itemId: Uuid) { itemToToken.remove(itemId) }

    override suspend fun onAction(itemId: Uuid, actionId: String) =
        notify("onAction", itemId) { put("action", actionId) }

    override suspend fun onReply(itemId: Uuid, text: String) =
        notify("onReply", itemId) { put("text", text) }

    override suspend fun onDismiss(itemId: Uuid) {
        notify("onDismiss", itemId) {}
        itemToToken.remove(itemId)
    }

    private inline fun notify(method: String, itemId: Uuid, extra: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit) {
        send(buildJsonObject {
            put("jsonrpc", "2.0")
            put("method", method)
            put("params", buildJsonObject {
                itemToToken[itemId]?.let { put("extToken", it) }
                put("itemId", itemId.toString())
                extra()
            })
        })
    }
}
