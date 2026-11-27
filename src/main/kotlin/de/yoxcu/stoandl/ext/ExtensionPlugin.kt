@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package de.yoxcu.stoandl.ext

import de.yoxcu.stoandl.plugin.ExtensionBackedPlugin
import io.rebble.libpebblecommon.plugin.ActionDeclaration
import io.rebble.libpebblecommon.plugin.ActionResult
import io.rebble.libpebblecommon.plugin.IconPixelSize
import io.rebble.libpebblecommon.plugin.NativePlugin
import io.rebble.libpebblecommon.plugin.PluginErrors
import io.rebble.libpebblecommon.plugin.SourceDeclaration
import io.rebble.libpebblecommon.plugin.SourceEnvelope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.security.MessageDigest
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false; explicitNulls = false }

/**
 * What an extension declares in its `initialize` result to serve plugin sources and actions: the same
 * `sources` / `actions` blocks a .pbw's `plugin` manifest has (see the upstream plugin docs), plus an
 * optional fixed `uuid`.
 */
@Serializable
internal data class ExtensionPluginManifest(
    val uuid: String? = null,
    val description: String = "",
    val sources: List<SourceDeclaration> = emptyList(),
    val actions: List<ActionDeclaration> = emptyList(),
) {
    companion object {
        fun parse(element: JsonElement?): ExtensionPluginManifest? =
            element?.let { runCatching { json.decodeFromJsonElement(serializer(), it) }.getOrNull() }
                ?.takeIf { it.sources.isNotEmpty() || it.actions.isNotEmpty() }
    }
}

/** The uuid an extension's plugin goes by unless it fixes one: stable per extension name, and outside the
 *  pbw uuid space in practice (a .pbw that removes "its" plugin must not unregister the extension's). */
internal fun extensionPluginUuid(name: String): Uuid {
    val d = MessageDigest.getInstance("SHA-256").digest("stoandl-extension-plugin:$name".toByteArray())
    d[6] = ((d[6].toInt() and 0x0F) or 0x50).toByte()   // version 5-style
    d[8] = ((d[8].toInt() and 0x3F) or 0x80).toByte()   // RFC 4122 variant
    return Uuid.fromByteArray(d.copyOf(16))
}

/**
 * A plugin an extension serves: sources read and actions invoked over its JSON-RPC pipe (`readSource`,
 * `invokeAction`, host → extension requests); a `sourceChanged {category, item}` notification from the
 * extension triggers a fresh read, and so does the declared refresh interval. In libpebble3's registry it is
 * one more plugin, so watchapps, the CLI and the GUI see it like any other. An extension is trusted by
 * being installed; its declared `callerPermissions` guard what watchapps may read or invoke.
 *
 * A [NativePlugin], because that is what libpebble3's public `registerPlugin` takes: like a built-in it
 * never prompts on its own (the extension has no permission checks of its own to ask about), while the
 * callers' permissions are still enforced by libpebble3 before a read or an action reaches it.
 */
internal class ExtensionPlugin(
    override val extensionName: String,
    manifest: ExtensionPluginManifest,
    override val name: String,
    private val request: suspend (method: String, params: JsonObject) -> JsonElement,
    private val changes: SharedFlow<Pair<String, String>>,
) : NativePlugin, ExtensionBackedPlugin {
    override val pluginUuid: Uuid = manifest.uuid?.let { runCatching { Uuid.parse(it) }.getOrNull() } ?: extensionPluginUuid(extensionName)
    override val description: String = manifest.description
    override val sources: List<SourceDeclaration> = manifest.sources
    override val actions: List<ActionDeclaration> = manifest.actions
    private val refreshAll = kotlinx.coroutines.flow.MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** Re-read every subscribed source now (the host resumed from suspend: the refresh delay didn't run). */
    fun refreshAllSources() { refreshAll.tryEmit(Unit) }

    override fun observe(
        category: String,
        item: String,
        properties: List<String>?,
        iconPixelSize: IconPixelSize?,
        limit: Int?,
    ): Flow<SourceEnvelope> {
        val decl = sources.firstOrNull { it.serves(category, item) } ?: return emptyFlow()
        val params = buildJsonObject {
            put("category", category); put("item", item)
            properties?.let { p -> put("properties", JsonArray(p.map(::JsonPrimitive))) }
            iconPixelSize?.let { put("iconPixelSize", json.encodeToJsonElement(IconPixelSize.serializer(), it)) }
            // A hint, as for a pbw plugin: libpebble3 drops whatever comes back past it anyway.
            limit?.let { put("limit", it) }
        }
        return channelFlow {
            suspend fun read() {
                val r = runCatching { request("readSource", params) }.getOrNull() ?: return
                runCatching { json.decodeFromJsonElement(SourceEnvelope.serializer(), r) }.getOrNull()
                    ?.let { send(it.copy(pluginUuid = pluginUuid.toString())) }
            }
            read()
            launch { changes.filter { it.first == category && it.second == item }.collect { read() } }
            launch { refreshAll.collect { read() } }
            while (true) {
                delay(decl.suggestedRefreshIntervalSec.coerceAtLeast(10).seconds)
                read()
            }
        }
    }

    override suspend fun invoke(action: String, args: JsonObject): ActionResult {
        if (action(action) == null) return ActionResult.error(PluginErrors.PLUGIN_UNAVAILABLE, "$name has no action $action")
        val r = try {
            request("invokeAction", buildJsonObject { put("action", action); put("args", args) })
        } catch (e: Exception) {
            return ActionResult.error(PluginErrors.PLUGIN_UNAVAILABLE, e.message ?: "$extensionName didn't answer")
        }
        return runCatching { json.decodeFromJsonElement(ActionResult.serializer(), r) }.getOrElse {
            ActionResult.error(PluginErrors.UNKNOWN, "$extensionName answered something that isn't an action result")
        }
    }
}
