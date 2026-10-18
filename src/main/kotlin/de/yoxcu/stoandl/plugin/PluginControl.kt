@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package de.yoxcu.stoandl.plugin

import io.github.oshai.kotlinlogging.KotlinLogging
import io.rebble.libpebblecommon.connection.LibPebble
import io.rebble.libpebblecommon.database.dao.LockerAppPermissionDao
import io.rebble.libpebblecommon.database.entity.LockerAppPermission
import io.rebble.libpebblecommon.permissions.AppPermissionStatus
import io.rebble.libpebblecommon.permissions.PermissionKind
import io.rebble.libpebblecommon.permissions.PermissionRegistry
import io.rebble.libpebblecommon.permissions.PermissionStatus
import io.rebble.libpebblecommon.permissions.domainOf
import io.rebble.libpebblecommon.permissions.permissionOf
import io.rebble.libpebblecommon.plugin.ActionResult
import io.rebble.libpebblecommon.plugin.JsPlugin
import io.rebble.libpebblecommon.plugin.NativePlugin
import io.rebble.libpebblecommon.plugin.Plugin
import io.rebble.libpebblecommon.plugin.PluginPermissions
import io.rebble.libpebblecommon.plugin.SourceEnvelope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.uuid.Uuid

private val log = KotlinLogging.logger {}

/** How long a one-shot source read (D-Bus `PluginRead`) waits for the plugin's first envelope. */
private const val READ_TIMEOUT_MS = 15_000L

/** A plugin as stoandl lists it: built into the daemon, shipped in a .pbw, or served by an extension. */
enum class PluginKind { BUILTIN, PBW, EXTENSION }

/** Implemented by the plugins extensions serve, so the list can tell them apart. */
interface ExtensionBackedPlugin {
    val extensionName: String
}

fun kindOf(p: Plugin): PluginKind = when (p) {
    is ExtensionBackedPlugin -> PluginKind.EXTENSION
    is JsPlugin -> PluginKind.PBW
    is NativePlugin -> PluginKind.BUILTIN
    else -> PluginKind.PBW
}

/**
 * The D-Bus face of plugins and app permissions (`Plugin*`, `Perm*`), over libpebble3's PluginRegistry and
 * AppPermissions. The CLI and GUI act for the user, so reading a source or invoking an action here skips the
 * callers' permission checks a watchapp would face; every such call is logged.
 */
class PluginControl(
    private val libPebble: () -> LibPebble?,
    private val permissionDao: () -> LockerAppPermissionDao?,
    private val appName: (Uuid) -> String?,
    private val enabled: () -> Boolean,
) {
    private val json = Json { encodeDefaults = false; explicitNulls = false }

    /** `uuid\tkind\tname\tdescription\tsources\tactions` per plugin (sources as `category/item` and actions
     *  as names, comma-separated). Empty while plugins are off. */
    fun list(): List<String> {
        val lp = libPebble() ?: return emptyList()
        if (!enabled()) return emptyList()
        return lp.getAllPlugins().sortedWith(compareBy({ kindOf(it).ordinal }, { it.name.lowercase() })).map { p ->
            val sources = p.sources.flatMap { s -> s.items.map { "${s.category}/$it" } }.joinToString(",")
            val actions = p.actions.joinToString(",") { it.name }
            listOf(p.pluginUuid.toString(), kindOf(p).name.lowercase(), p.name.clean(), p.description.clean(), sources, actions)
                .joinToString("\t")
        }
    }

    /** One envelope of `category/item` as JSON: `ok:<json>`, `off:`, `notfound:` or `error:`. Blocks. */
    fun read(category: String, item: String, preferredPlugin: String?): String = runBlocking {
        val lp = libPebble() ?: return@runBlocking "notready:no watch daemon state yet"
        if (!enabled()) return@runBlocking "off:plugins are off (plugins.enabled)"
        val plugin = lp.findSourcePlugin(category, item, preferredPlugin?.takeIf { it.isNotBlank() })
            ?: return@runBlocking "notfound:no plugin serves $category/$item"
        log.info { "PluginRead $category/$item from ${plugin.name} (for the CLI/GUI)" }
        val env: SourceEnvelope = withTimeoutOrNull(READ_TIMEOUT_MS) { plugin.observe(category, item).first() }
            ?: return@runBlocking "error:${plugin.name} gave no data within ${READ_TIMEOUT_MS / 1000} s"
        "ok:" + json.encodeToString(SourceEnvelope.serializer(), env)
    }

    /** Invoke [action] of [pluginUuid] with [argsJson]: `ok:<text>`, `failed:<code> <message>`, `off:`,
     *  `notfound:` or `error:`. Blocks. */
    fun invoke(pluginUuid: String, action: String, argsJson: String): String = runBlocking {
        val lp = libPebble() ?: return@runBlocking "notready:no watch daemon state yet"
        if (!enabled()) return@runBlocking "off:plugins are off (plugins.enabled)"
        val plugin = lp.findPlugin(pluginUuid) ?: lp.getAllPlugins().firstOrNull { it.name.equals(pluginUuid, true) }
            ?: return@runBlocking "notfound:no plugin '$pluginUuid'"
        if (plugin.action(action) == null) return@runBlocking "notfound:${plugin.name} has no action '$action'"
        val args = try {
            if (argsJson.isBlank()) JsonObject(emptyMap()) else Json.parseToJsonElement(argsJson).jsonObject
        } catch (e: Exception) {
            return@runBlocking "error:arguments are not a JSON object: ${e.message}"
        }
        log.info { "PluginInvoke ${plugin.name}.$action (for the CLI/GUI)" }
        val r: ActionResult = plugin.invoke(action, args)
        if (r.ok) "ok:${r.text.orEmpty().clean()}" else "failed:${r.code.orEmpty()} ${r.message.orEmpty()}".clean()
    }

    /** `key\tstatus\tkind\tlabel\tlastUsedMs` for each permission [app] asked for or was decided, with the
     *  effective status (see [effectiveStatus]). */
    fun permissions(app: Uuid): List<String> = runBlocking {
        val rows = permissionDao()?.getByAppUuid(app) ?: return@runBlocking emptyList()
        val statuses = libPebble()?.appPermissionStatuses(app)?.first().orEmpty()
        val defined = pluginLabels()
        permissionRecords(rows, statuses).map { (r, status) ->
            listOf(r.permission, status, kindLabel(r.permission), labelOf(r.permission, defined), (r.lastUsedAtMs ?: 0L).toString())
                .joinToString("\t")
        }
    }

    /** Requests waiting for an answer from every installed app, running or not:
     *  `appUuid\tappName\tkey\tlabel\trequestedAtMs\trunning\tseen`. */
    fun pending(): List<String> = runBlocking {
        val lp = libPebble() ?: return@runBlocking emptyList()
        val rows = permissionDao()?.allFlow()?.first() ?: return@runBlocking emptyList()
        val running = lp.activePermissionRequests().first().map { it.appUuid }.toSet()
        val statuses = rows.filter { it.granted == null }.map { it.appUuid }.distinct()
            .associateWith { lp.appPermissionStatuses(it).first() }
        val defined = pluginLabels()
        pendingRequests(rows, statuses, running).map { r ->
            listOf(r.row.appUuid.toString(), (appName(r.row.appUuid) ?: r.row.appUuid.toString()).clean(), r.row.permission,
                labelOf(r.row.permission, defined), r.row.requestedAtMs.toString(), r.running.toString(),
                (r.row.seenAtMs != null).toString()).joinToString("\t")
        }
    }

    /** Decide [key] for [app]: `allow`, `deny`, `reset` (back to undecided) or `later` (stop asking about a
     *  waiting request without deciding it; a blank [key] means all of the app's). `ok:` / `notfound:` / `error:`. */
    fun set(app: Uuid, key: String, decision: String): String = runBlocking {
        val lp = libPebble() ?: return@runBlocking "notready:no watch daemon state yet"
        val d = decision.lowercase()
        if (key.isBlank() && d != "later") return@runBlocking "error:name the permission to $d"
        when (d) {
            "allow", "deny" -> {
                lp.setPermissionDecisions(app, mapOf(key to (d == "allow")))
                log.info { "Permission $key for ${appName(app) ?: app}: $d" }
                "ok:$key ${d}ed"
            }
            "reset" -> {
                val dao = permissionDao() ?: return@runBlocking "error:no permission store"
                val row = dao.getByAppUuid(app).firstOrNull { it.permission == key }
                    ?: return@runBlocking "notfound:${appName(app) ?: app} has no permission $key"
                dao.insertOrReplace(row.copy(granted = null, decidedAtMs = null, seenAtMs = null))
                "ok:$key reset"
            }
            "later" -> {
                val waiting = lp.appPermissionStatuses(app).first()
                    .filter { it.status == PermissionStatus.Waiting }.map { it.permission }
                val keys = if (key.isBlank()) waiting else waiting.filter { it == key }
                if (keys.isEmpty()) return@runBlocking "notfound:${appName(app) ?: app} has nothing waiting${if (key.isBlank()) "" else " for $key"}"
                lp.markPermissionRequestsSeen(app, keys)
                log.info { "Permission request${if (keys.size > 1) "s" else ""} from ${appName(app) ?: app} put off: ${keys.joinToString()}" }
                "ok:${keys.joinToString()} put off"
            }
            else -> "error:decision must be allow, deny, reset or later"
        }
    }

    private suspend fun pluginLabels(): Map<String, String> =
        libPebble()?.pluginPermissions()?.first()?.mapValues { (_, d) -> d.label } ?: emptyMap()

    private fun String.clean() = replace('\t', ' ').replace('\n', ' ')
}

/**
 * What a permission row means now: `granted` / `denied` (the user decided), `auto` (undecided, and the policy
 * allows it), `waiting` (undecided, and the app must ask) or `blocked` (refused whatever the row says: Legacy
 * PebbleKit switched off, global internet for an app that declares its domains, or the app isn't installed).
 */
internal fun effectiveStatus(status: PermissionStatus?): String = when (status) {
    PermissionStatus.Granted -> "granted"
    PermissionStatus.Denied -> "denied"
    PermissionStatus.AllowedAutomatically -> "auto"
    PermissionStatus.Waiting -> "waiting"
    null -> "blocked"
}

/** Each row, sorted by key, with its effective status from libpebble3's [statuses] (which omits the blocked). */
internal fun permissionRecords(
    rows: List<LockerAppPermission>,
    statuses: List<AppPermissionStatus>,
): List<Pair<LockerAppPermission, String>> {
    val byKey = statuses.associate { it.permission to it.status }
    return rows.sortedBy { it.permission }.map { it to effectiveStatus(byKey[it.permission]) }
}

internal data class PendingRequest(val row: LockerAppPermission, val running: Boolean)

/** The rows still [PermissionStatus.Waiting] per app's [statuses], oldest request first. */
internal fun pendingRequests(
    rows: List<LockerAppPermission>,
    statuses: Map<Uuid, List<AppPermissionStatus>>,
    running: Set<Uuid>,
): List<PendingRequest> = rows
    .filter { r -> r.granted == null && statuses[r.appUuid].orEmpty().any { it.permission == r.permission && it.status == PermissionStatus.Waiting } }
    .sortedBy { it.requestedAtMs }
    .map { PendingRequest(it, it.appUuid in running) }

private fun kindLabel(key: String): String =
    PermissionRegistry[permissionOf(key)]?.kind?.name?.lowercase() ?: PermissionKind.Write.name.lowercase()

/** A permission key in words: `Internet/api.example.com` → "Send data to api.example.com". */
internal fun labelOf(key: String, pluginDefined: Map<String, String> = emptyMap()): String {
    pluginDefined[key]?.let { return it }
    domainOf(key)?.let { return "Send data to $it" }
    return when (permissionOf(key)) {
        PluginPermissions.INTERNET -> "Send data over the internet"
        PluginPermissions.GLOBAL_INTERNET -> "Send data anywhere on the internet"
        PluginPermissions.LOCAL_NETWORK -> "Reach devices on the local network"
        PluginPermissions.LOCATION -> "Your location"
        PluginPermissions.NOTIFICATIONS -> "Your notifications"
        PluginPermissions.CALENDAR -> "Your calendar"
        PluginPermissions.CONTACTS -> "Your contacts"
        PluginPermissions.TIMELINE -> "Add timeline pins"
        PluginPermissions.READ_WATCH_SETTINGS -> "Read watch settings"
        PluginPermissions.WRITE_WATCH_SETTINGS -> "Change watch settings"
        PluginPermissions.READ_MUSIC -> "See what's playing"
        PluginPermissions.CONTROL_MUSIC -> "Control music"
        PluginPermissions.LAUNCH_APPS -> "Open apps on the watch"
        PluginPermissions.LEGACY_PEBBLEKIT -> "Talk to a companion app (PebbleKit)"
        PluginPermissions.PEBBLEKIT_2 -> "Talk to companion app ${key.substringAfter('/', "")}".trim()
        else -> key
    }
}
