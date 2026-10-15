package de.yoxcu.stoandl.pebble

import io.github.oshai.kotlinlogging.KotlinLogging
import io.rebble.libpebblecommon.plugin.ConfigMessageTarget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

private val log = KotlinLogging.logger {}

/** How long a page's `sendMessage` waits for the script's answer. */
private const val CALL_TIMEOUT_MS = 15_000L

/** A script a settings page can talk to: the app's PKJS (`pkjs`) or its plugin (`plugin`). */
class ConfigScript(val name: String, val target: ConfigMessageTarget)

/**
 * The daemon half of a settings page's `Pebble` API (upstream's v1.15 "V2" config pages): `sendMessage` to
 * the app's PKJS or plugin, `ready` and pushed `message` events back. The official app wires this into its
 * WebView; stoandl serves pages to a browser, so the page calls `POST <base>call` and listens on a
 * server-sent event stream at `<base>events` (see [configPageShim]). A plugin only runs while its page is
 * open: the first event stream starts the scripts, [close] stops them.
 */
class ConfigPageBridge(private val scripts: Map<String, ConfigScript>, private val scope: CoroutineScope) {
    private val ready = ConcurrentHashMap.newKeySet<String>()
    private val sinks = CopyOnWriteArrayList<(String) -> Unit>()
    @Volatile private var started: Job? = null

    val isEmpty: Boolean get() = scripts.isEmpty()

    /** A page's call (one JSON object: id, method, target, message) to the JSON reply. Blocks. */
    fun call(json: String): String {
        val call = try {
            Json { ignoreUnknownKeys = true }.decodeFromString(Call.serializer(), json)
        } catch (e: Exception) {
            return error("malformed call")
        }
        // Payloads can carry API keys: only the shape is logged.
        log.debug { "config page -> ${call.method} ${call.target}" }
        return when (call.method) {
            "hello" -> Json.encodeToString(Hello.serializer(), Hello(ready.toList()))
            "targets" -> Json.encodeToString(kotlinx.serialization.builtins.ListSerializer(Target.serializer()),
                scripts.map { (id, s) -> Target(id, s.name) })
            "sendMessage" -> {
                val script = scripts[call.target] ?: return error("no script called '${call.target}' (have: ${scripts.keys.joinToString()})")
                runBlocking {
                    withTimeoutOrNull(CALL_TIMEOUT_MS) { script.target.onConfigMessage(call.message.toString()) }
                } ?: error("${script.name} did not answer")
            }
            "webSignIn" -> error("web sign-in is not available in stoandl")
            else -> error("unknown method '${call.method}'")
        }
    }

    /** A page's event stream connected; [sink] gets each event as one JSON line. The first one starts the
     *  scripts. Returns the detach action. */
    fun attach(sink: (String) -> Unit): () -> Unit {
        sinks += sink
        ready.forEach { sink(event("ready", it)) }
        synchronized(this) {
            if (started == null) started = scope.launch {
                scripts.forEach { (id, s) ->
                    launch { s.target.configMessages.collect { m -> broadcast(event("message", id, m)) } }
                    runCatching { s.target.openConfigSession() }.onFailure { log.warn { "Config page: ${s.name} ($id) didn't start: ${it.message}" } }
                    ready += id
                    broadcast(event("ready", id))
                }
            }
        }
        return { sinks -= sink }
    }

    /** The page is gone: stop what only runs while it is open. */
    fun close() {
        val job = synchronized(this) { started.also { started = null } } ?: return
        job.cancel()
        scope.launch { scripts.values.forEach { runCatching { it.target.closeConfigSession() } } }
        ready.clear()
    }

    private fun broadcast(e: String) = sinks.forEach { runCatching { it(e) } }

    private fun event(type: String, target: String, json: String? = null): String =
        Json.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), buildJsonObject {
            put("type", type); put("target", target); json?.let { put("json", it) }
        })

    private fun error(message: String) =
        Json.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), buildJsonObject { put("error", message) })

    @Serializable
    private data class Call(val id: Int = 0, val method: String, val target: String = "", val message: JsonElement = JsonNull)

    @Serializable
    private data class Target(val id: String, val name: String)

    @Serializable
    private data class Hello(val ready: List<String>)
}

/**
 * The page-side `Pebble` API for a browser, injected first into the page's `<head>`: upstream's config page
 * shim, with the native bridge replaced by `fetch` to [base]`call` and an `EventSource` on [base]`events`.
 * [base] is absolute: a proxied page carries a `<base>` pointing at its real origin.
 */
internal fun configPageShim(base: String): String = """<script>(function (global) {
  var BASE = ${kotlinx.serialization.json.JsonPrimitive(base)};
  var nextId = 1, pending = {}, listeners = { message: [], ready: [] }, readyTargets = [];
  function call(method, target, message) {
    var id = nextId++;
    var payload = JSON.stringify({ id: id, method: method, target: target === undefined ? '' : target,
      message: message === undefined ? null : message });
    return fetch(BASE + 'call', { method: 'POST', body: payload, headers: { 'Content-Type': 'application/json' } })
      .then(function (r) { return r.json(); })
      .catch(function () { return { error: 'stoandl is not reachable' }; });
  }
  function emit(type, event) { listeners[type].forEach(function (cb) { cb(event); }); }
  function markReady(target) {
    if (readyTargets.indexOf(target) === -1) readyTargets.push(target);
    emit('ready', { type: 'ready', target: target });
  }
  global.Pebble = global.Pebble || {};
  global.Pebble.sendMessage = function (target, message) {
    if (typeof target !== 'string') return Promise.reject(new Error('sendMessage(target, message): target is required'));
    return call('sendMessage', target, message);
  };
  global.Pebble.targets = function () { return call('targets'); };
  global.Pebble.webSignIn = function () { return Promise.reject(new Error('web sign-in is not available in stoandl')); };
  global.Pebble.readyTargets = function () { return readyTargets.slice(); };
  global.Pebble.addEventListener = function (type, callback) {
    if (!listeners[type] || typeof callback !== 'function') return;
    listeners[type].push(callback);
    if (type === 'ready') readyTargets.forEach(function (t) { callback({ type: 'ready', target: t }); });
  };
  global.Pebble.removeEventListener = function (type, callback) {
    if (!listeners[type]) return;
    var i = listeners[type].indexOf(callback);
    if (i !== -1) listeners[type].splice(i, 1);
  };
  var events = new EventSource(BASE + 'events');
  events.onmessage = function (e) {
    var ev; try { ev = JSON.parse(e.data); } catch (x) { return; }
    if (ev.type === 'ready') markReady(ev.target);
    else if (ev.type === 'message') {
      var data = null; try { data = JSON.parse(ev.json); } catch (x) {}
      emit('message', { type: 'message', target: ev.target, data: data });
    }
  };
})(window);</script>"""
