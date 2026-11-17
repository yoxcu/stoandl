package de.yoxcu.stoandl.store

import de.yoxcu.stoandl.util.connectedDevice
import io.github.oshai.kotlinlogging.KotlinLogging
import io.rebble.libpebblecommon.connection.ConnectedPebbleDevice
import io.rebble.libpebblecommon.connection.LibPebble
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.io.files.Path
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.atomic.AtomicReference

private val log = KotlinLogging.logger {}

/**
 * The Rebble store behind the D-Bus control interface: browse/search, detail, and an asynchronous
 * install. [StoreCatalog] does the API + cache work; this class owns the small amount of state the
 * interface needs — the connected watch's platform and the install progress string.
 *
 * **Install is asynchronous**, mirroring [de.yoxcu.stoandl.language.LanguageControl]: [install]
 * validates and kicks the work off, then returns `ok:<title>`; the GUI/CLI polls [status] (or
 * subscribes to the store progress signal) until `done:`/`failed:`. The PBW is downloaded into the
 * same `<configDir>/store/pbw/` cache the CLI uses, then pushed through libpebble3's
 * `sideloadApp` — the exact call behind `StoandlControl.SideloadApp`, so there is one install path.
 *
 * Browse and detail are public and need no watch; they take an explicit platform instead. Only
 * [install] requires a connected watch (that is what supplies the platform and receives the app).
 * Browsing is opt-in egress by use: nothing here runs until a client asks for a search or a detail.
 */
class StoreControl(
    private val libPebbleRef: AtomicReference<LibPebble?>,
    private val scope: CoroutineScope,
    private val catalog: StoreCatalog = StoreCatalog(),
) {
    /** The install progress string: `idle:`, `downloading:<percent>`, `installing:<title>`,
     *  `done:<title>` or `failed:<reason>`. Same shape as `LanguageStatus`, so one client poll loop
     *  can serve both. */
    private val state = MutableStateFlow(IDLE)

    /** Reactive [status], one emission per phase and per download percentage tick. */
    val statusFlow: Flow<String> = state.asStateFlow()

    private val lock = Any()
    private var job: Job? = null
    private var installingTitle: String = ""

    /** The connected watch's store hardware (`aplite`, `emery`, …), or null when none is connected. */
    fun platform(): String? =
        libPebbleRef.connectedDevice<ConnectedPebbleDevice>()?.watchInfo?.platform?.storePlatform()

    /**
     * Text search, hardware-filtered, as `ok:<json>`:
     * `{query, platform, total, page, pages, hits:[{id, uuid, type, title, author, category, hearts,
     * image, platforms[], compatible}]}`. A blank [platform] uses the connected watch's (then
     * `notready:` if there is none); [type] is `watchface`/`watchapp` (also accepts `faces`/`apps`).
     * [limit] is clamped to 1..1000, [offset] is the number of hits to skip.
     */
    fun search(type: String, query: String, platform: String, limit: Int, offset: Int): String {
        val itemType = StoreItemType.parse(type)
            ?: return "error:Unknown type '$type' (watchface or watchapp)"
        val hw = platform.trim().lowercase().ifEmpty { platform().orEmpty() }
        if (hw.isEmpty()) return NO_WATCH
        if (hw !in StoreApi.HARDWARE) return "error:Unknown platform '$hw' — one of: ${hardwareList()}"
        val page = try {
            runBlocking {
                catalog.search(query.trim(), itemType, hw, limit.coerceIn(1, StoreCatalog.MAX_HITS), offset)
            }
        } catch (e: StoreException) {
            return "error:${e.message}"
        } catch (e: Exception) {
            log.warn(e) { "SearchStore('$query') failed" }
            return "error:${e.message ?: e::class.simpleName}"
        }
        return "ok:${searchJson(page, hw, query.trim())}"
    }

    /**
     * One app's full listing as `ok:<json>`: every search-hit field plus `description`, `version`,
     * `published`, `pbw`, `sizeBytes` (null when the host reports no length), and `compatible`/`platform`
     * for the connected watch (both null with no watch). `notfound:` when the store has no such id.
     */
    fun details(id: String): String {
        val clean = id.trim()
        if (clean.isEmpty()) return "error:No app id"
        val app = try {
            runBlocking { catalog.info(clean) }
        } catch (e: StoreException) {
            return "error:${e.message}"
        } catch (e: Exception) {
            log.warn(e) { "StoreDetails('$clean') failed" }
            return "error:${e.message ?: e::class.simpleName}"
        } ?: return "notfound:No app with id '$clean' in the Rebble store"
        val size = runCatching { runBlocking { catalog.downloadSize(app) } }.getOrNull()
        return "ok:${appJson(app, platform(), size)}"
    }

    /**
     * Start installing store app [id]: download its PBW into the store cache, then sideload it. Returns
     * `ok:<title>` once kicked off (poll [status]), `busy:<title>` while another install runs,
     * `notfound:`, `incompatible:<supported platforms>`, `notready:` (no watch) or `error:`.
     *
     * The detail lookup is synchronous so a bad id/unsupported app fails immediately; it is normally a
     * cache hit because the client just showed the app's detail. The download + push run on the daemon
     * scope, so a slow link never ties up the D-Bus call.
     */
    fun install(id: String): String {
        val clean = id.trim()
        if (clean.isEmpty()) return "error:No app id"
        val dev = libPebbleRef.connectedDevice<ConnectedPebbleDevice>() ?: return NO_WATCH
        val hw = dev.watchInfo.platform.storePlatform()
        val app = try {
            runBlocking { catalog.info(clean) }
        } catch (e: StoreException) {
            return "error:${e.message}"
        } catch (e: Exception) {
            log.warn(e) { "InstallStoreApp('$clean') failed" }
            return "error:${e.message ?: e::class.simpleName}"
        } ?: return "notfound:No app with id '$clean' in the Rebble store"
        if (!app.supports(hw)) {
            return "incompatible:'${app.title}' is not available for $hw — it supports: " +
                app.platforms.joinToString(", ").ifEmpty { "nothing" }
        }
        synchronized(lock) {
            job?.takeIf { it.isActive }?.let { return "busy:$installingTitle" }
            installingTitle = app.title
            state.value = "downloading:-1"
            job = scope.launch { runInstall(app) }
        }
        return "ok:${app.title}"
    }

    /** Current install progress (see [state]); polled after [install]. */
    fun status(): String = state.value

    private suspend fun runInstall(app: StoreApp) {
        try {
            val file = catalog.download(app) { done, total ->
                val percent = if (total > 0) ((done * 100) / total).toInt().coerceIn(0, 100) else -1
                state.value = "downloading:$percent"
            }
            state.value = "installing:${app.title}"
            val lp = libPebbleRef.get() ?: throw StoreException("libPebble is not ready")
            if (!lp.sideloadApp(Path(file.absolutePath))) {
                throw StoreException("the watch rejected '${app.title}' (not a valid .pbw, or no watch connected)")
            }
            log.info { "Store install: ${app.title} (${file.name}) sideloaded" }
            state.value = "done:${app.title}"
        } catch (e: Exception) {
            log.warn(e) { "Store install of '${app.title}' failed" }
            state.value = "failed:${e.message ?: e::class.simpleName}"
        } finally {
            synchronized(lock) { job = null }
        }
    }

    private fun hardwareList(): String = StoreApi.HARDWARE.sorted().joinToString(", ")

    private companion object {
        const val IDLE = "idle:"
        const val NO_WATCH = "notready:No watch connected"
    }
}

// ---- JSON payloads ---------------------------------------------------------------------------------

/** The search page as the GUI sees it. [platform] is the hardware the results were filtered to, so
 *  `compatible` is a boolean per hit — or **null when the hit carries no hardware list** (Algolia omits
 *  `compatibility` for some documents, so "unknown" must not read as "yes"; the detail endpoint has the
 *  authoritative map). This mirrors [StoreApp.supports], which treats unknown as installable. */
internal fun searchJson(page: StoreSearchPage, platform: String, query: String): JsonObject = buildJsonObject {
    put("query", query)
    put("platform", platform)
    put("total", page.total)
    put("page", page.page)
    put("pages", page.pages)
    put("hits", buildJsonArray {
        page.hits.forEach { hit ->
            add(buildJsonObject {
                put("id", hit.id)
                put("uuid", hit.uuid)
                put("type", hit.type)
                put("title", hit.title)
                put("author", hit.author)
                put("category", hit.category)
                put("hearts", hit.hearts)
                put("image", hit.listImage)
                put("platforms", buildJsonArray { hit.platforms.forEach { add(it) } })
                put("compatible", if (hit.platforms.isEmpty()) null else platform in hit.platforms)
            })
        }
    })
}

/** One app's detail. [watchPlatform] null = no watch connected, so `compatible` is null (unknown). */
internal fun appJson(app: StoreApp, watchPlatform: String?, sizeBytes: Long?): JsonObject = buildJsonObject {
    put("id", app.id)
    put("uuid", app.uuid)
    put("type", app.type)
    put("title", app.title)
    put("author", app.author)
    put("category", app.category)
    put("hearts", app.hearts)
    put("description", app.description)
    put("platforms", buildJsonArray { app.platforms.forEach { add(it) } })
    put("platform", watchPlatform)
    put("compatible", watchPlatform?.let { app.supports(it) })
    put("version", app.version)
    put("published", app.publishedDate)
    put("pbw", app.pbwUrl)
    put("sizeBytes", sizeBytes)
    put("image", app.listImage)
}
