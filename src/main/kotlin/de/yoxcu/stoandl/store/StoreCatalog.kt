package de.yoxcu.stoandl.store

import de.yoxcu.stoandl.config.StoandlConfig
import de.yoxcu.stoandl.util.LenientJson
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.Serializable
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

private val log = KotlinLogging.logger {}

/**
 * The Rebble store as the rest of stoandl uses it: search, detail, and a `.pbw` download, with a small
 * on-disk cache so repeated CLI/GUI calls don't re-hit the network.
 *
 * Cache layout (under `<configDir>/store/`, honouring `XDG_CONFIG_HOME`):
 *  - `cache/search-<hash>.json`, `cache/app-<id>.json` — the API's own JSON wrapped with a fetch time;
 *    reused while younger than [ttlMillis] (default 1 h). A malformed entry is ignored and refetched.
 *  - `pbw/<releaseId>.pbw` — downloaded bundles, so an interrupted install can be retried without a
 *    second download. The install path still goes through [de.yoxcu.stoandl.dbus.StoandlControl.SideloadApp],
 *    exactly like `apps install`; there is no parallel upload path.
 *
 * Nothing here is account-bound: browse, detail and the PBW URL are public. Egress happens only when a
 * caller explicitly searches/fetches/downloads.
 */
class StoreCatalog(
    private val api: StoreApi = StoreApi(),
    private val dir: File = File(StoandlConfig.configDir(), "store"),
    private val ttlMillis: Long = DEFAULT_TTL_MILLIS,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val cacheDir = File(dir, "cache")
    private val pbwDir = File(dir, "pbw")

    /** Search by text, hardware- and type-filtered. Algolia is the store's only search backend. */
    suspend fun search(
        query: String,
        type: StoreItemType,
        platform: String,
        limit: Int = 15,
        offset: Int = 0,
    ): StoreSearchPage {
        require(platform in StoreApi.HARDWARE) { "Unknown watch platform '$platform'" }
        val size = limit.coerceIn(1, MAX_HITS)
        val start = offset.coerceAtLeast(0)
        val page = start / size
        val skip = start % size
        val key = "search-" + sha256("$query|${type.wire}|$platform|$size|$start")

        fun parse(body: String): StoreSearchPage {
            val result = parseSearchResponse(body)
            return if (skip == 0) result else result.copy(hits = result.hits.drop(skip))
        }

        readCache(key)?.let { body -> runCatching { parse(body) }.getOrNull()?.let { return it } }
        val body = api.searchJson(query.trim(), type, platform, size, page)
        writeCache(key, body)
        return parse(body)
    }

    /** One app's detail, or null when the store has no such id. */
    suspend fun info(id: String): StoreApp? {
        val key = appCacheKey(id)
        readCache(key)?.let { body -> runCatching { parseDetailResponse(body) }.getOrNull()?.let { return it } }
        val body = api.detailJson(id) ?: return null
        // The REST API answers an unknown id with 200 + `data: []`; don't cache that as a real miss.
        val app = parseDetailResponse(body) ?: return null
        writeCache(key, body)
        return app
    }

    /** HEAD the app's PBW for its size, for `store info` (null when the host doesn't report one). */
    suspend fun downloadSize(app: StoreApp): Long? = app.pbwUrl?.let { api.contentLength(it) }

    /**
     * Download [app]'s PBW into the store's cache, reusing a previous copy. Returns the absolute file,
     * ready to hand to `SideloadApp`. [onProgress] follows the network read (a cached copy reports
     * nothing — it is a pure disk hit).
     */
    suspend fun download(app: StoreApp, onProgress: (Long, Long) -> Unit = { _, _ -> }): File {
        val url = app.pbwUrl ?: throw StoreException("'${app.title}' has no downloadable PBW")
        val releaseId = url.substringAfterLast('/').substringBefore('?').removeSuffix(".pbw")
            .takeIf { it.matches(SAFE_NAME) } ?: sha256(url)
        val dest = File(pbwDir, "$releaseId.pbw")
        if (dest.isFile && dest.length() > 0) {
            log.info { "Store: reusing cached PBW ${dest.path} (${dest.length()} bytes)" }
            return dest
        }
        val bytes = api.download(url, onProgress)
        if (!pbwDir.isDirectory && !pbwDir.mkdirs()) throw StoreException("Couldn't create ${pbwDir.path}")
        val tmp = File(pbwDir, "$releaseId.pbw.part")
        tmp.writeBytes(bytes)
        Files.move(tmp.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        log.info { "Store: downloaded '${app.title}' → ${dest.path} (${bytes.size} bytes)" }
        return dest
    }

    /** A cache entry that is still fresh, or null when it is absent, stale, or unreadable. */
    private fun readCache(key: String): String? {
        val file = File(cacheDir, "$key.json")
        if (!file.isFile) return null
        return try {
            val entry = LenientJson.decodeFromString(CacheEntry.serializer(), file.readText())
            if (now() - entry.fetchedAt > ttlMillis) null else entry.body
        } catch (e: Exception) {
            log.warn(e) { "Store: ignoring malformed cache entry ${file.path}" }
            null
        }
    }

    private fun writeCache(key: String, body: String) {
        try {
            if (!cacheDir.isDirectory && !cacheDir.mkdirs()) return
            val file = File(cacheDir, "$key.json")
            val tmp = File(cacheDir, "$key.json.tmp")
            tmp.writeText(LenientJson.encodeToString(CacheEntry.serializer(), CacheEntry(now(), body)))
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: Exception) {
            log.warn(e) { "Store: couldn't write cache entry $key" }
        }
    }

    private fun appCacheKey(id: String): String =
        "app-" + if (id.matches(SAFE_NAME)) id else sha256(id)

    @Serializable
    private data class CacheEntry(val fetchedAt: Long, val body: String)

    companion object {
        const val DEFAULT_TTL_MILLIS = 60L * 60 * 1000

        /** Algolia caps a page at 1000 hits. */
        const val MAX_HITS = 1000

        private val SAFE_NAME = Regex("[A-Za-z0-9._-]{1,80}")

        private fun sha256(value: String): String =
            MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
                .joinToString("") { "%02x".format(it) }
    }
}
