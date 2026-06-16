package de.yoxcu.stoandl.util

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * Lenient JSON decoder shared by every reader of third-party JSON (weather, the firmware sources, the
 * language-pack catalog). `ignoreUnknownKeys` lets the upstream APIs add fields without breaking us.
 */
val LenientJson = Json { ignoreUnknownKeys = true }

/**
 * Render a sequence of [JsonObject]s as NDJSON — one compact object per line, each terminated by a
 * newline (so an empty sequence yields an empty string). Shared by the health export and datalog sinks.
 */
fun Iterable<JsonObject>.toNdjson(): String = buildString {
    for (obj in this@toNdjson) {
        append(Json.encodeToString(JsonObject.serializer(), obj))
        append('\n')
    }
}
