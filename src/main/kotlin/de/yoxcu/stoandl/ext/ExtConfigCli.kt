package de.yoxcu.stoandl.ext

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull

/*
 * `stoandl ext config <name> [<key> [<value>]]`: the CLI's view of an extension's settings, over the same
 * ExtConfigSchema / ExtGetConfig / ExtSetConfig calls the GUI's settings form uses. Pure, so the table and
 * the value checks are unit-tested; Main.kt does the D-Bus calls and the prompting.
 */

/** The settings table: one row per schema field (or, without a schema, per key the extension has), with
 *  its value, a secret's stored state, `required`, and an enum's choices. [values] is ExtGetConfig's
 *  object (secrets read `""`; `_secrets` lists the stored ones). */
fun extConfigTable(fields: List<ExtConfigField>, values: JsonObject): List<String> {
    val stored = values["_secrets"].str().split(',').filter { it.isNotBlank() }.toSet()
    val rows = fields.ifEmpty {
        values.keys.filter { it != "_secrets" }.sorted().map { ExtConfigField(it, "string", it, false, false, emptyList()) }
    }
    if (rows.isEmpty()) return emptyList()
    val wKey = rows.maxOf { it.key.length }
    val wLabel = rows.maxOf { it.label.length }
    return rows.map { f ->
        val value = when {
            f.secret -> if (f.key in stored) "(set)" else "(not set)"
            else -> values[f.key].str().ifEmpty { "—" }
        }
        val notes = listOfNotNull(
            "required".takeIf { f.required },
            "secret".takeIf { f.secret },
            f.options.joinToString("|").takeIf { f.type == "enum" && it.isNotEmpty() },
            "true|false".takeIf { f.type == "bool" },
            "number".takeIf { f.type == "int" },
        )
        "  ${f.key.padEnd(wKey)}  ${f.label.padEnd(wLabel)}  $value" + if (notes.isEmpty()) "" else "  (${notes.joinToString(", ")})"
    }
}

/** [raw] as the value [field] stores (`bool` → `true`/`false`, an `enum` choice in its declared case), or
 *  an error message. A key without a schema field takes any text. */
fun extConfigValue(field: ExtConfigField?, raw: String): Result<String> {
    val v = raw.trim()
    return when (field?.type) {
        "bool" -> when (v.lowercase()) {
            "true", "on", "yes", "1" -> Result.success("true")
            "false", "off", "no", "0" -> Result.success("false")
            else -> Result.failure(IllegalArgumentException("${field.key} is true or false"))
        }
        "int" -> if (v.toLongOrNull() != null) Result.success(v)
            else Result.failure(IllegalArgumentException("${field.key} is a whole number"))
        "enum" -> field.options.firstOrNull { it.equals(v, ignoreCase = true) }?.let { Result.success(it) }
            ?: Result.failure(IllegalArgumentException("${field.key} is one of: ${field.options.joinToString(", ")}"))
        else -> Result.success(raw)
    }
}

/** The ExtSetConfig payload that sets [key] to [value], or with null removes it (a stored secret is
 *  deleted; a plain key is written empty). */
fun extConfigPayload(key: String, value: String?, secret: Boolean): String = buildJsonObject {
    put(key, if (value == null) (if (secret) JsonNull else JsonPrimitive("")) else JsonPrimitive(value))
}.toString()

private fun kotlinx.serialization.json.JsonElement?.str(): String = (this as? JsonPrimitive)?.contentOrNull ?: ""
