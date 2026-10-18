package de.yoxcu.stoandl.ext

import de.yoxcu.stoandl.secret.SecretBackend
import de.yoxcu.stoandl.secret.SecretStore
import de.yoxcu.stoandl.util.ConfFile
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.File

private val log = KotlinLogging.logger {}

/**
 * Extensions' `"secret": true` config fields: kept in the [store] under [extSecretRef], handed only to
 * that extension's child, and never left in its `<extDir>/<name>/config` file (a plaintext value found
 * there is moved into the store by [migrate]).
 */
class ExtensionSecrets(private val extDir: File, private val store: SecretStore) {

    fun flagsOf(name: String): SchemaFlags = schemaFlags(readExtManifestMeta(File(extDir, name)).configSchemaJson)

    /** The non-empty stored secrets of [name] among [keys]. */
    fun stored(name: String, keys: Set<String>): Map<String, String> =
        keys.mapNotNull { k -> store.get(extSecretRef(name, k))?.takeIf { it.isNotEmpty() }?.let { k to it } }.toMap()

    fun put(name: String, key: String, value: String) {
        val ref = extSecretRef(name, key)
        val backend = store.put(ref, value)
        log.info { "Secret $ref stored (${if (backend == SecretBackend.KEYRING) "keyring" else "file"})" }
    }

    fun remove(name: String, key: String) {
        val ref = extSecretRef(name, key)
        if (store.get(ref) == null) return
        store.remove(ref)
        log.info { "Secret $ref removed" }
    }

    /** Move any of [secretKeys] still written in plaintext in `<extDir>/<name>/config` into the store and
     *  delete its line. A value the store refuses (it threw) stays where it is. */
    fun migrate(name: String, secretKeys: Set<String>) {
        if (secretKeys.isEmpty()) return
        val file = File(File(extDir, name), "config")
        if (!file.isFile) return
        ConfFile.withLock {
            val raw = ConfFile.read(file)
            val moved = secretKeys.filter { it in raw }.filter { k ->
                val v = raw.getValue(k)
                v.isEmpty() || runCatching { put(name, k, v) }
                    .onFailure { log.warn { "Extension '$name': couldn't move secret '$k' out of ${file.path}: ${it.message}" } }
                    .isSuccess
            }.toSet()
            if (moved.isNotEmpty()) {
                ConfFile.remove(file, moved)
                log.info { "Extension '$name': moved ${moved.joinToString(", ")} out of ${file.path} into the secret store" }
            }
        }
    }

    /** What `ExtGetConfig` reports for [name] ([effectiveConfigView]); migrates first. */
    fun configView(name: String): Map<String, String> {
        val dir = File(extDir, name)
        val flags = flagsOf(name)
        migrate(name, flags.secretKeys)
        val defaults = readExtManifestMeta(dir).config.filterKeys { it !in flags.secretKeys }
        val file = File(dir, "config").let { if (it.isFile) ConfFile.read(it) else emptyMap() }
        return effectiveConfigView(defaults, file, flags.secretKeys, stored(name, flags.secretKeys).keys)
    }

    /** Apply the secret half of an `ExtSetConfig` [payload] for [name] and return the plain keys left for
     *  the `config` file, or null when the payload changes nothing. Migrates first. */
    fun applyPayload(name: String, payload: JsonObject): Map<String, String>? {
        val flags = flagsOf(name)
        val split = splitConfigPayload(payload, flags.secretKeys)
        if (split.plain.isEmpty() && split.put.isEmpty() && split.remove.isEmpty()) return null
        migrate(name, flags.secretKeys)
        split.put.forEach { (k, v) -> put(name, k, v) }
        split.remove.forEach { remove(name, it) }
        return split.plain
    }
}

/** The secret-store ref of extension [name]'s secret config [key]. */
fun extSecretRef(name: String, key: String) = "ext:$name:$key"

/**
 * [def] with its secret keys filled for the child: a [stored] secret wins, else a leftover
 * `extension.<name>.<key>` in stoandl.conf ([confLayer], reported in the second value so the caller can
 * warn), else the key is dropped (a manifest default for a secret is never used). [ExtensionDef.userConfigured]
 * also counts a stored secret, since a secret no longer shows up in the `config` file.
 */
fun withSecrets(
    def: ExtensionDef,
    secretKeys: Set<String>,
    stored: Map<String, String>,
    confLayer: Map<String, String>,
): Pair<ExtensionDef, List<String>> {
    if (secretKeys.isEmpty()) return def to emptyList()
    val config = LinkedHashMap(def.config)
    val fromConf = ArrayList<String>()
    secretKeys.forEach { k ->
        val s = stored[k]
        when {
            !s.isNullOrEmpty() -> config[k] = s
            !confLayer[k].isNullOrEmpty() -> { config[k] = confLayer.getValue(k); fromConf += k }
            else -> config.remove(k)
        }
    }
    val configured = def.userConfigured || secretKeys.any { !stored[it].isNullOrEmpty() }
    return def.copy(config = config, userConfigured = configured) to fromConf
}

/** Whether [def] may start: with any `required` key, every one must have a non-empty value; otherwise a
 *  `requiresConfig` extension needs the user's own settings ([ExtensionDef.userConfigured]). */
fun configSatisfied(def: ExtensionDef, requiredKeys: Set<String>): Boolean =
    if (requiredKeys.isNotEmpty()) requiredKeys.all { !def.config[it].isNullOrBlank() }
    else !def.requiresConfig || def.userConfigured

/**
 * What `ExtGetConfig` reports: manifest [defaults] overlaid by the `config` [file] values, every secret
 * key blanked, plus `_secrets` listing the secret keys that are [stored] (comma-separated).
 */
fun effectiveConfigView(
    defaults: Map<String, String>,
    file: Map<String, String>,
    secretKeys: Set<String>,
    stored: Set<String>,
): Map<String, String> {
    val out = LinkedHashMap<String, String>()
    out.putAll(defaults); out.putAll(file)
    secretKeys.forEach { out[it] = "" }
    out.remove(SECRETS_KEY)
    out[SECRETS_KEY] = secretKeys.filter { it in stored }.joinToString(",")
    return out
}

/** The reserved `ExtGetConfig` key listing which secret keys are stored. */
const val SECRETS_KEY = "_secrets"

/** An `ExtSetConfig` payload split by destination: [plain] keys go to the `config` file, [put] secrets
 *  to the secret store and [remove] secrets out of it. */
data class ConfigPayload(val plain: Map<String, String>, val put: Map<String, String>, val remove: Set<String>)

/** Per secret key: `""` leaves it unchanged (the GUI form posts every field), JSON `null` removes it, any
 *  other value stores it. [SECRETS_KEY] is ignored. Every other key is a plain string (null = `""`). */
fun splitConfigPayload(payload: JsonObject, secretKeys: Set<String>): ConfigPayload {
    val plain = LinkedHashMap<String, String>(); val put = LinkedHashMap<String, String>(); val remove = LinkedHashSet<String>()
    payload.forEach { (k, v) ->
        val p = v as? JsonPrimitive
        val text = if (p == null || p is JsonNull) null else p.contentOrNull
        when {
            k == SECRETS_KEY -> {}
            k in secretKeys -> when {
                v is JsonNull -> remove += k
                !text.isNullOrEmpty() -> put[k] = text
            }
            else -> plain[k] = text ?: ""
        }
    }
    return ConfigPayload(plain, put, remove)
}
