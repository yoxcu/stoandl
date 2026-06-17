@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package de.yoxcu.stoandl.ext

import de.yoxcu.stoandl.config.StoandlConfig.ExtensionDef
import de.yoxcu.stoandl.pebble.NotifAction
import de.yoxcu.stoandl.pebble.NotifOwner
import de.yoxcu.stoandl.pebble.NotifOwnerRegistry
import de.yoxcu.stoandl.pebble.NotifRequest
import de.yoxcu.stoandl.pebble.ReplySpec
import de.yoxcu.stoandl.pebble.WatchNotifier
import io.github.oshai.kotlinlogging.KotlinLogging
import io.rebble.libpebblecommon.connection.ConnectedPebbleDevice
import io.rebble.libpebblecommon.connection.LibPebble
import io.rebble.libpebblecommon.services.appmessage.AppMessageData
import io.rebble.libpebblecommon.services.appmessage.AppMessageResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
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
    private val owners: NotifOwnerRegistry,
    private val libPebbleRef: AtomicReference<LibPebble?>,
    private val scope: CoroutineScope,
) {
    fun start() {
        if (defs.isEmpty()) {
            log.info { "No extensions configured (set extensions.enabled + extension.<name>.cmd)" }
            return
        }
        log.info { "Starting ${defs.size} extension(s): ${defs.map { it.name }}" }
        val processes = defs.map { def -> ExtensionProcess(def, watchNotifier, owners, libPebbleRef, scope).also { it.launch() } }
        // Tell extensions when a watch (re)connects so they can re-arm their notifications. Until the
        // Phase-2 re-push lands, a notify() issued while disconnected is dropped, so this edge is how a
        // long-lived extension (e.g. find-my-phone) gets its notification back onto a fresh connection.
        //
        // The device reports `Connected` BEFORE its timeline/app BlobDB finishes negotiating, and a
        // notification sent in that window is silently dropped by the watch (its parent app isn't synced
        // yet). So settle for a moment after the connect edge — and re-check we're still connected —
        // before broadcasting. (Phase 2's readiness-gated re-push will replace this heuristic.)
        libPebbleRef.get()?.let { lp ->
            scope.launch {
                lp.watches
                    .map { devices -> devices.any { it is ConnectedPebbleDevice } }
                    .distinctUntilChanged()
                    .collect { connected ->
                        if (!connected) return@collect
                        delay(CONNECT_SETTLE_MS)
                        if (lp.watches.value.any { it is ConnectedPebbleDevice }) {
                            processes.forEach { it.onWatchConnected() }
                        }
                    }
            }
        }
    }
}

// The watch reports Connected before its BlobDB is ready to accept timeline notifications; give it this
// long to settle before telling extensions to (re)send. Empirically the BlobDB is up well under 1s
// after connect; 3s leaves margin for a slow phone.
private const val CONNECT_SETTLE_MS = 3_000L

private const val MAX_FAST_FAILURES = 5
private const val STABLE_UPTIME_MS = 60_000L
private const val BACKOFF_BASE_MS = 1_000L
private const val BACKOFF_MAX_MS = 60_000L
private const val INIT_TIMEOUT_MS = 10_000L

/** One supervised extension child: spawn → handshake → relay, restart-with-backoff on exit. */
private class ExtensionProcess(
    private val def: ExtensionDef,
    private val watchNotifier: WatchNotifier,
    owners: NotifOwnerRegistry,
    private val libPebbleRef: AtomicReference<LibPebble?>,
    private val scope: CoroutineScope,
) {
    private val tag = def.name
    // The write channel is swapped on each spawn.
    @Volatile private var writeChannel: Channel<String>? = null
    @Volatile private var ready = false
    // Registered under the extension name so routes resolve to it.
    private val owner = ExtensionOwner(def.name) { obj -> enqueue(obj) }.also { owners.register(it) }
    // Watchapp UUIDs this extension has registered an inbound-AppMessage collector for (once each).
    private val registeredApps = java.util.concurrent.ConcurrentHashMap.newKeySet<Uuid>()

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
        // Optional stable id so a re-send replaces the same notification (across restarts too).
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
        // The ext's correlation token rides in the persisted route, so callbacks survive a restart.
        val itemId = watchNotifier.push(req, def.name, extToken)
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
    }

    // ---- watchapp companion (AppMessage / launch / install) -------------------------------------

    private fun connectedDevice(): ConnectedPebbleDevice? =
        libPebbleRef.get()?.watches?.value?.filterIsInstance<ConnectedPebbleDevice>()?.firstOrNull()

    private fun appmessageAllowed(uuid: Uuid): Boolean =
        def.allow.contains("appmessage") || def.allow.contains("appmessage:$uuid")

    /** Arm a per-connection inbound-AppMessage collector for [uuid]: ACK each message to the watch and
     *  forward it to the child as an `onAppMessage` notification. Idempotent (one collector per uuid). */
    private fun handleRegisterApp(id: JsonElement?, params: JsonObject?) {
        val uuid = params?.str("uuid")?.let { runCatching { Uuid.parse(it) }.getOrNull() }
        if (uuid == null) { replyError(id, "registerApp: missing/invalid uuid"); return }
        if (!appmessageAllowed(uuid)) { replyError(id, "not permitted: grant 'appmessage:$uuid' via extension.$tag.allow"); return }
        if (registeredApps.add(uuid)) {
            scope.launch {
                val lp = libPebbleRef.get() ?: return@launch
                lp.watches
                    .map { devices -> devices.filterIsInstance<ConnectedPebbleDevice>().firstOrNull() }
                    .distinctUntilChanged()
                    .flatMapLatest { dev -> dev?.inboundAppMessages(uuid)?.map { dev to it } ?: emptyFlow() }
                    .collect { (dev, msg) ->
                        runCatching { dev.sendAppMessageResult(AppMessageResult.ACK(msg.transactionId)) }
                        enqueue(buildJsonObject {
                            put("jsonrpc", "2.0")
                            put("method", "onAppMessage")
                            put("params", buildJsonObject {
                                put("uuid", uuid.toString())
                                put("transactionId", msg.transactionId.toInt())
                                put("data", encodeAppData(msg.data))
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
        if (!appmessageAllowed(uuid)) { replyError(id, "not permitted: grant 'appmessage:$uuid'"); return }
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
        if (!appmessageAllowed(uuid)) { replyError(id, "not permitted: grant 'appmessage:$uuid'"); return }
        val lp = libPebbleRef.get() ?: run { replyError(id, "no watch"); return }
        try {
            if (launch) lp.launchApp(uuid) else lp.stopApp(uuid)
        } catch (e: Exception) {
            replyError(id, e.message ?: "$verb failed"); return
        }
        reply(id) { put("ok", true) }
    }

    private suspend fun handleInstallPbw(id: JsonElement?, params: JsonObject?) {
        val path = params?.str("path")
        if (path.isNullOrEmpty()) { replyError(id, "installPbw: missing path"); return }
        if (def.allow.none { it == "appmessage" || it.startsWith("appmessage:") }) {
            replyError(id, "not permitted: installPbw needs an 'appmessage:<uuid>' grant"); return
        }
        val lp = libPebbleRef.get() ?: run { replyError(id, "no watch"); return }
        val ok = try {
            lp.sideloadApp(kotlinx.io.files.Path(path))
        } catch (e: Exception) {
            replyError(id, e.message ?: "install failed"); return
        }
        reply(id) { put("ok", ok) }
    }

    /** Inbound AppMessage dict (typed Kotlin values) → typed-tagged JSON for the child. */
    private fun encodeAppData(data: Map<Int, Any>): JsonObject = buildJsonObject {
        data.forEach { (k, v) ->
            put(k.toString(), buildJsonObject {
                when (v) {
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

    /** Child's typed-tagged JSON → AppMessage dict with the right Kotlin runtime types (so libpebble3
     *  emits the correct tuple width — JSON has one number type, the watchapp's dict_read_* doesn't). */
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
                else -> raw // cstring/string
            }
            if (value != null) out[key] = value
        }
        return out
    }

    private fun reply(id: JsonElement?, result: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit) {
        if (id == null) return
        enqueue(buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            put("result", buildJsonObject(result))
        })
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
 * [NotifOwner] for an extension: forwards the user's wrist action/reply/dismiss as a JSON-RPC
 * notification to the child (enqueued — never blocks libpebble3's action flow). The correlation token
 * is supplied by the router from the persisted route (so it survives a restart); the extension then
 * acts via its own service account.
 */
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
