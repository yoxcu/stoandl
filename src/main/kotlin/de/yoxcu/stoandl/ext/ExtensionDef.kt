package de.yoxcu.stoandl.ext

import de.yoxcu.stoandl.util.LenientJson
import io.github.oshai.kotlinlogging.KotlinLogging
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
)

/**
 * Resolve an extension by name from `<extDir>/<name>/`. The spawn command, in priority order:
 *  1. `cmd` from stoandl.conf ([conf]) — for anything special;
 *  2. `cmd` in `<dir>/manifest.json` — for self-contained (archive-installed) extensions;
 *  3. `python3 <name>.py` if that file exists.
 * Returns null (warned) if the directory is missing or no entry point can be found. [conf] settings
 * overlay the manifest's `config` (conf wins).
 */
fun resolveExtension(name: String, extDir: File, conf: Map<String, String>): ExtensionDef? {
    val dir = File(extDir, name)
    val confCmd = conf["cmd"]?.trim()?.takeIf { it.isNotEmpty() }
    val confConfig = conf.filterKeys { it != "cmd" }

    // An explicit stoandl.conf `cmd` works regardless of the dir layout (back-compat for a flat/custom
    // setup); cwd is the ext dir when it exists, else the ext base dir.
    if (confCmd != null) {
        val wd = if (dir.isDirectory) dir else extDir
        return ExtensionDef(name, confCmd.split(Regex("\\s+")), confConfig, wd)
    }

    // Otherwise the convention is a per-extension directory `<extDir>/<name>/`.
    if (!dir.isDirectory) {
        log.warn { "Extension '$name' enabled but no directory at ${dir.path} (and no extension.$name.cmd)" }
        return null
    }
    val manifest = readManifest(dir)
    val command = when {
        manifest?.cmd?.isNotBlank() == true -> manifest.cmd.trim().split(Regex("\\s+"))
        File(dir, "$name.py").isFile -> listOf("python3", "$name.py")
        else -> {
            log.warn { "Extension '$name': no manifest.json cmd and no $name.py in ${dir.path}" }
            return null
        }
    }
    val config = LinkedHashMap<String, String>()
    manifest?.config?.let { config.putAll(it) }
    config.putAll(confConfig)  // stoandl.conf overrides the manifest
    return ExtensionDef(name, command, config, dir)
}

private class Manifest(val name: String?, val cmd: String?, val config: Map<String, String>)

/** Optional `<dir>/manifest.json`: `{ "name": …, "cmd": …, "config": { … } }`. All fields optional. */
private fun readManifest(dir: File): Manifest? {
    val f = File(dir, "manifest.json")
    if (!f.isFile) return null
    return try {
        val obj = LenientJson.parseToJsonElement(f.readText()).jsonObject
        val config = obj["config"]?.jsonObject?.mapValues { it.value.jsonPrimitive.contentOrNull ?: "" } ?: emptyMap()
        Manifest(obj["name"]?.jsonPrimitive?.contentOrNull, obj["cmd"]?.jsonPrimitive?.contentOrNull, config)
    } catch (e: Exception) {
        log.warn { "Ignoring bad manifest.json in ${dir.path}: ${e.message}" }
        null
    }
}

/** The `name` declared in an extracted archive's `manifest.json`, if any (used to name the install dir). */
fun manifestName(dir: File): String? = readManifest(dir)?.name
