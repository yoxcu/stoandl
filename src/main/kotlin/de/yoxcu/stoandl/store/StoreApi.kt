package de.yoxcu.stoandl.store

import de.yoxcu.stoandl.util.LenientJson
import de.yoxcu.stoandl.util.stoandlHttpClient
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.head
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

private val log = KotlinLogging.logger {}

/** What the store calls a watchface or watchapp — the Algolia tag and the app's `type` field. */
enum class StoreItemType(val wire: String, val noun: String) {
    FACES("watchface", "watchface"),
    APPS("watchapp", "watch app"),
    ;

    companion object {
        /** Parse a CLI/API value: `watchface`, `faces`, `face`, `watchapp`, `apps`, `app`. Null otherwise. */
        fun parse(value: String?): StoreItemType? = when (value?.trim()?.lowercase()) {
            "watchface", "watchfaces", "face", "faces" -> FACES
            "watchapp", "watchapps", "app", "apps" -> APPS
            else -> null
        }
    }
}

/** One row of a store search — the Algolia hit fields the CLI/GUI list needs. */
data class StoreHit(
    val id: String,
    val uuid: String,
    val title: String,
    val author: String,
    val type: String,
    val category: String,
    val hearts: Int,
    /** Hardware codenames the index says this app supports (`aplite`, `basalt`, …). */
    val platforms: List<String>,
    val listImage: String?,
)

/** A page of Algolia search results. */
data class StoreSearchPage(
    val hits: List<StoreHit>,
    val total: Int,
    val page: Int,
    val pages: Int,
)

/** One app's detail, from `apps/id/<id>` (the endpoint the Rebble store front-end itself calls). */
data class StoreApp(
    val id: String,
    val uuid: String,
    val title: String,
    val author: String,
    val type: String,
    val category: String,
    val hearts: Int,
    val description: String,
    /** Hardware codenames with a supported binary, from the REST `compatibility` map. */
    val platforms: List<String>,
    val pbwUrl: String?,
    val version: String?,
    val publishedDate: String?,
    val listImage: String?,
) {
    /** Whether this app carries a binary for [platform]. An empty [platforms] (the API omitted the map)
     *  is treated as unknown rather than incompatible, so sideloading can still validate the PBW. */
    fun supports(platform: String): Boolean = platforms.isEmpty() || platform in platforms
}

/** A user-facing store failure (network, HTTP status, oversized download) — no stack trace needed. */
class StoreException(message: String) : Exception(message)

/**
 * The Rebble app store API. Public, unauthenticated browse/detail/download; only hearts and account
 * endpoints need the Firebase token, and the store feature deliberately doesn't touch those.
 *
 * Two hosts:
 *  - **REST** at [API] — `home/…`, `apps/category/…`, `apps/id/<id>`. Listing and detail. The PBW
 *    itself is a plain public GCS URL in `latest_release.pbw_file`, not signed.
 *  - **Algolia** at [ALGOLIA_URL] — the only text search (`apps/category/…` silently ignores `q`). The
 *    public search-only key is the one the store's web front-end embeds.
 *
 * Everything here returns raw JSON text so [StoreCatalog] can cache exactly what the API said; parsing
 * lives in [parseSearchResponse] / [parseDetailResponse] (pure and unit-tested).
 */
class StoreApi(private val client: HttpClient = stoandlHttpClient()) {

    /** POST the Algolia index and return its JSON response. Pagination is page-based on Algolia;
     *  [StoreCatalog.search] maps its 0-based `offset` onto (page, skip) before calling this. */
    suspend fun searchJson(
        query: String,
        type: StoreItemType,
        platform: String,
        limit: Int,
        page: Int,
    ): String {
        val body = buildJsonObject {
            put("query", query)
            put("hitsPerPage", limit)
            put("page", page)
            // [["watchface","aplite"]] — the type and hardware must both be in the hit's tags.
            put("tagFilters", buildJsonArray {
                add(buildJsonArray {
                    add(JsonPrimitive(type.wire))
                    add(JsonPrimitive(platform))
                })
            })
        }
        val resp = try {
            client.post(ALGOLIA_URL) {
                header("X-Algolia-Application-Id", ALGOLIA_APP_ID)
                header("X-Algolia-API-Key", ALGOLIA_SEARCH_KEY)
                contentType(ContentType.Application.Json)
                setBody(body.toString())
            }
        } catch (e: Exception) {
            log.warn(e) { "Store search failed: could not reach Algolia" }
            throw StoreException("Couldn't reach the app store (${e.message ?: e::class.simpleName})")
        }
        if (!resp.status.isSuccess()) {
            log.warn { "Store search: Algolia HTTP ${resp.status.value}" }
            throw StoreException("The app store returned HTTP ${resp.status.value}")
        }
        return resp.bodyAsText()
    }

    /** GET `apps/id/<id>` and return its JSON, or null when the store doesn't know [id]. */
    suspend fun detailJson(id: String): String? {
        val url = "$API/apps/id/${encodePathSegment(id)}?image_ratio=1"
        val resp = try {
            client.get(url)
        } catch (e: Exception) {
            log.warn(e) { "Store detail failed: could not reach the store" }
            throw StoreException("Couldn't reach the app store (${e.message ?: e::class.simpleName})")
        }
        if (resp.status.value == 404) return null
        if (!resp.status.isSuccess()) {
            log.warn { "Store detail($id): HTTP ${resp.status.value}" }
            throw StoreException("The app store returned HTTP ${resp.status.value}")
        }
        return resp.bodyAsText()
    }

    /** Download a PBW. Refuses (before reading the body when Content-Length is present) anything over
     *  [MAX_PBW_BYTES], so a bad store URL can't fill memory/disk. */
    suspend fun download(url: String): ByteArray {
        require(url.startsWith("https://")) { "Store download URL must be HTTPS" }
        val resp = try {
            client.get(url)
        } catch (e: Exception) {
            log.warn(e) { "PBW download failed: $url" }
            throw StoreException("Couldn't download the app (${e.message ?: e::class.simpleName})")
        }
        if (!resp.status.isSuccess()) {
            log.warn { "PBW download: HTTP ${resp.status.value} for $url" }
            throw StoreException("Couldn't download the app: HTTP ${resp.status.value}")
        }
        val declared = resp.headers[HttpHeaders.ContentLength]?.toLongOrNull()
        if (declared != null && declared > MAX_PBW_BYTES) throw tooLarge(declared)
        val bytes = resp.bodyAsBytes()
        if (bytes.size > MAX_PBW_BYTES) throw tooLarge(bytes.size.toLong())
        return bytes
    }

    /** HEAD a PBW for its size (for `store info`), or null if the host doesn't report one. */
    suspend fun contentLength(url: String): Long? = try {
        val resp = client.head(url)
        if (resp.status.isSuccess()) resp.headers[HttpHeaders.ContentLength]?.toLongOrNull() else null
    } catch (e: Exception) {
        log.debug(e) { "HEAD failed: $url" }
        null
    }

    private fun tooLarge(bytes: Long): StoreException =
        StoreException("The app is ${bytes / (1024 * 1024)} MB — over the ${MAX_PBW_BYTES / (1024 * 1024)} MB limit")

    companion object {
        const val API = "https://appstore-api.rebble.io/api/v1"
        const val ALGOLIA_URL = "https://7683OW76EQ-dsn.algolia.net/1/indexes/rebble-appstore-production/query"
        const val ALGOLIA_APP_ID = "7683OW76EQ"
        const val ALGOLIA_SEARCH_KEY = "252f4938082b8693a8a9fc0157d1d24f"

        /** Hardware codenames the store indexes. Matches `WatchType`'s `codename` exactly. */
        val HARDWARE = setOf("aplite", "basalt", "chalk", "diorite", "emery", "flint", "gabbro")

        /** A Pebble app bundle is well under this; the cap only exists to stop a hostile/broken URL. */
        const val MAX_PBW_BYTES = 16L * 1024 * 1024
    }
}

// ---- Parsing ---------------------------------------------------------------------------------------

/** Parse an Algolia `/query` response. Malformed hits are skipped rather than failing the page. */
internal fun parseSearchResponse(body: String): StoreSearchPage {
    val root = LenientJson.parseToJsonElement(body).jsonObject
    val hits = root["hits"]?.jsonArray.orEmpty().mapNotNull(::parseHit)
    return StoreSearchPage(
        hits = hits,
        total = root.int("nbHits"),
        page = root.int("page"),
        pages = root.int("nbPages"),
    )
}

private fun parseHit(element: JsonElement): StoreHit? {
    val hit = element as? JsonObject ?: return null
    val id = hit.str("id") ?: hit.str("objectID") ?: return null
    return StoreHit(
        id = id,
        uuid = hit.str("uuid").orEmpty(),
        title = hit.str("title") ?: "(untitled)",
        author = hit.str("author").orEmpty(),
        type = hit.str("type").orEmpty(),
        category = hit.str("category").orEmpty(),
        hearts = hit.int("hearts"),
        platforms = platformList(hit["compatibility"]),
        listImage = imageUrl(hit["list_image"]),
    )
}

/** Parse `apps/id/<id>` (or any endpoint returning the same `data[]` shape), or null when empty. */
internal fun parseDetailResponse(body: String): StoreApp? {
    val root = LenientJson.parseToJsonElement(body).jsonObject
    val first = root["data"]?.jsonArray?.firstOrNull() as? JsonObject ?: return null
    val release = first["latest_release"] as? JsonObject
    return StoreApp(
        id = first.str("id") ?: return null,
        uuid = first.str("uuid").orEmpty(),
        title = first.str("title") ?: "(untitled)",
        author = first.str("author").orEmpty(),
        type = first.str("type").orEmpty(),
        category = first.str("category").orEmpty(),
        hearts = first.int("hearts"),
        description = first.str("description").orEmpty(),
        platforms = supportedPlatforms(first["compatibility"]),
        pbwUrl = release?.str("pbw_file")?.takeIf { it.startsWith("https://") },
        version = release?.str("version") ?: first.str("version"),
        publishedDate = release?.str("published_date") ?: first.str("published_date"),
        listImage = imageUrl(first["list_image"]) ?: imageUrl(first["icon_image"]),
    )
}

/** Algolia lists supported platforms as `["ios","android","aplite",…]`; keep the watch ones only. */
private fun platformList(element: JsonElement?): List<String> =
    (element as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        .filter { it in StoreApi.HARDWARE }
        .distinct()

/** REST lists compatibility as `{"aplite":{"supported":true,"has_binary":true,…}, …}`. */
private fun supportedPlatforms(element: JsonElement?): List<String> {
    val map = element as? JsonObject ?: return emptyList()
    return map.entries.mapNotNull { (platform, value) ->
        if (platform !in StoreApi.HARDWARE) return@mapNotNull null
        val entry = value as? JsonObject ?: return@mapNotNull null
        val supported = entry["supported"]?.jsonPrimitive?.contentOrNull != "false"
        val hasBinary = entry["has_binary"]?.let { it.jsonPrimitive.contentOrNull != "false" } ?: true
        platform.takeIf { supported && hasBinary }
    }
}

/** `list_image`/`icon_image` are a URL string on Algolia but a size→URL map on REST. Prefer 144×144. */
private fun imageUrl(element: JsonElement?): String? = when (element) {
    is JsonPrimitive -> element.contentOrNull?.takeIf { it.startsWith("https://") }
    is JsonObject -> listOf("144x144", "80x80", "48x48", "28x28")
        .firstNotNullOfOrNull { key -> element.str(key)?.takeIf { it.startsWith("https://") } }
        ?: element.values.firstNotNullOfOrNull { value ->
            (value as? JsonPrimitive)?.contentOrNull?.takeIf { it.startsWith("https://") }
        }
    else -> null
}

private fun JsonObject.str(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

private fun JsonObject.int(key: String): Int = (this[key] as? JsonPrimitive)?.intOrNull ?: 0

private fun JsonElement?.orEmpty(): List<JsonElement> = (this as? JsonArray)?.toList().orEmpty()

/** Percent-encode a path segment (app ids are hex in practice, but the store is not our API). */
private fun encodePathSegment(s: String): String = java.net.URLEncoder.encode(s, Charsets.UTF_8)
