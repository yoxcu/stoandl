package de.yoxcu.stoandl.ext

import de.yoxcu.stoandl.util.LenientJson
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

private val log = KotlinLogging.logger {}

/**
 * A resolved extension ready to spawn. [command] runs with the working directory set to [workingDir]
 * (the extension's own `<configDir>/ext/<name>/`), so the default `python3 <name>.py` finds its
 * sibling `stoandl_ext.py` on `sys.path`. [config] is handed to the child in its initialize handshake.
 */
data class ExtensionDef(
    val name: String,
    val command: List<String>,
    val config: Map<String, String>,
    val workingDir: File,
    /** Declared in `manifest.json` (`"requiresConfig": true`): the extension can't run until configured. */
    val requiresConfig: Boolean = false,
    /** Whether the user actually supplied any settings (a non-empty `config` file or `extension.<name>.*`
     *  in stoandl.conf) — i.e. excluding the manifest's own author defaults. */
    val userConfigured: Boolean = false,
)

/**
 * Resolve an extension by name from `<extDir>/<name>/`.
 *
 * Settings come from three layers, each overriding the previous: the archive's `manifest.json` `config`
 * (author defaults) < the per-extension **`<extDir>/<name>/config`** file (the user's place — a simple
 * `key = value` file co-located with the extension, no `extension.<name>.` prefix) < any leftover
 * `extension.<name>.<key>` in stoandl.conf ([conf], a back-compat override). stoandl.conf should normally
 * carry only `extensions.enabled`. The spawn command, in priority order: `cmd` from stoandl.conf, the
 * `config` file, `manifest.json`, then `python3 <name>.py`.
 *
 * Returns null (warned) if the directory is missing and no `cmd` resolves, or no entry point is found.
 */
fun resolveExtension(name: String, extDir: File, conf: Map<String, String>): ExtensionDef? {
    val dir = File(extDir, name)
    val fileConf = if (dir.isDirectory) readConfigFile(dir) else emptyMap()
    val manifest = if (dir.isDirectory) readManifest(dir) else null

    // `cmd` precedence: stoandl.conf > ext/<name>/config > manifest.json (then the python default below).
    val cmd = (conf["cmd"] ?: fileConf["cmd"] ?: manifest?.cmd)?.trim()?.takeIf { it.isNotEmpty() }

    // Child config = manifest defaults < config file < stoandl.conf override, with `cmd` (meta) stripped.
    val config = LinkedHashMap<String, String>()
    manifest?.config?.let { config.putAll(it) }
    config.putAll(fileConf)
    config.putAll(conf)
    config.remove("cmd")

    val requiresConfig = manifest?.requiresConfig == true
    // "Configured" = the user supplied settings via the config file or stoandl.conf (manifest defaults
    // don't count — a required extension still needs the user's own values).
    val userConfigured = fileConf.isNotEmpty() || conf.keys.any { it != "cmd" }

    // An explicit `cmd` (from any layer) works regardless of layout; cwd is the ext dir if it exists.
    if (cmd != null) {
        return ExtensionDef(name, cmd.split(Regex("\\s+")), config, if (dir.isDirectory) dir else extDir, requiresConfig, userConfigured)
    }
    if (!dir.isDirectory) {
        log.warn { "Extension '$name' enabled but no directory at ${dir.path} (and no cmd configured)" }
        return null
    }
    if (File(dir, "$name.py").isFile) {
        return ExtensionDef(name, listOf("python3", "$name.py"), config, dir, requiresConfig, userConfigured)
    }
    log.warn { "Extension '$name': no cmd (stoandl.conf / config / manifest.json) and no $name.py in ${dir.path}" }
    return null
}

/** Read the per-extension `<dir>/config` file: `key = value` lines, `#` comments, leading `~`/`%h` in
 *  values expand to home (matching stoandl.conf). No `extension.<name>.` prefix — it's already scoped. */
private fun readConfigFile(dir: File): Map<String, String> {
    val f = File(dir, "config")
    if (!f.isFile) return emptyMap()
    val out = LinkedHashMap<String, String>()
    try {
        f.readLines().forEach { raw ->
            val line = raw.substringBefore('#').trim()
            val idx = line.indexOf('=')
            if (idx > 0) out[line.substring(0, idx).trim()] = expandHome(line.substring(idx + 1).trim())
        }
    } catch (e: Exception) {
        log.warn { "Couldn't read ${f.path}: ${e.message}" }
    }
    return out
}

private fun expandHome(v: String): String {
    val home = System.getProperty("user.home")
    return when {
        v == "~" || v == "%h" -> home
        v.startsWith("~/") -> home + v.substring(1)
        v.startsWith("%h/") -> home + v.substring(2)
        else -> v
    }
}

private class Manifest(
    val name: String?,
    val cmd: String?,
    val config: Map<String, String>,
    val requiresConfig: Boolean,
    val description: String,
    /** The raw `configSchema` JSON array (a typed form descriptor), or null if not declared. */
    val configSchemaJson: String?,
    /** Bundled `.pbw` filenames (relative to the extension dir) stoandl should keep installed on the
     *  watch for this extension — installed on connect/enable, removed on disable/uninstall. */
    val watchapps: List<String>,
    /** Author/developer name, for display (empty if not declared). */
    val author: String,
    /** Extension version string, for display (empty if not declared). */
    val version: String,
)

/** Optional `<dir>/manifest.json`: `{ "name": …, "cmd": …, "config": {…}, "requiresConfig": true,
 *  "description": "…", "author": "…", "version": "…", "configSchema": [{key,type,label,secret?,options?}],
 *  "watchapps": ["app.pbw"] }`. All fields optional; `author`/`version` are display-only.
 *  `requiresConfig` lets an extension declare it can't run until configured;
 *  `configSchema` (when present) declares a typed config form the GUI renders natively (its presence is
 *  the `schema` config-kind); `watchapps` declares bundled `.pbw`s stoandl manages on the watch. */
private fun readManifest(dir: File): Manifest? {
    val f = File(dir, "manifest.json")
    if (!f.isFile) return null
    return try {
        val obj = LenientJson.parseToJsonElement(f.readText()).jsonObject
        val config = obj["config"]?.jsonObject?.mapValues { it.value.jsonPrimitive.contentOrNull ?: "" } ?: emptyMap()
        val requiresConfig = (obj["requiresConfig"]?.jsonPrimitive?.contentOrNull).toBoolean()
        val description = obj["description"]?.jsonPrimitive?.contentOrNull ?: ""
        // Keep the array verbatim — the GUI parses the JSON itself (ExtConfigSchema returns it as-is).
        val configSchemaJson = (obj["configSchema"] as? JsonArray)?.toString()
        // Defensive: skip non-string elements rather than let a bad entry throw and sink the whole manifest.
        val watchapps = (obj["watchapps"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: emptyList()
        val author = obj["author"]?.jsonPrimitive?.contentOrNull ?: ""
        val version = obj["version"]?.jsonPrimitive?.contentOrNull ?: ""
        Manifest(obj["name"]?.jsonPrimitive?.contentOrNull, obj["cmd"]?.jsonPrimitive?.contentOrNull,
            config, requiresConfig, description, configSchemaJson, watchapps, author, version)
    } catch (e: Exception) {
        log.warn { "Ignoring bad manifest.json in ${dir.path}: ${e.message}" }
        null
    }
}

/** The `.pbw` filenames an extension's `manifest.json` declares via `watchapps` (empty if none/no manifest).
 *  These are the watchapps stoandl keeps installed for the extension (install on connect/enable, remove on
 *  disable/uninstall) — as opposed to a bundled-but-undeclared `.pbw`, which is only sideloaded on install. */
fun declaredWatchapps(dir: File): List<String> =
    (if (dir.isDirectory) readManifest(dir) else null)?.watchapps ?: emptyList()

/** The `name` declared in an extracted archive's `manifest.json`, if any (used to name the install dir). */
fun manifestName(dir: File): String? = readManifest(dir)?.name

/** GUI-surfaced manifest fields for an installed extension: a human [description], the typed
 *  [configSchemaJson] (a JSON array of `{key,type,label,secret?,options?}`; null when no schema is
 *  declared), and the display [author]/[version]. All empty/null when there's no (readable) manifest. */
data class ExtManifestMeta(
    val description: String,
    val configSchemaJson: String?,
    val author: String,
    val version: String,
)

fun readExtManifestMeta(dir: File): ExtManifestMeta {
    val m = if (dir.isDirectory) readManifest(dir) else null
    return ExtManifestMeta(m?.description ?: "", m?.configSchemaJson, m?.author ?: "", m?.version ?: "")
}
