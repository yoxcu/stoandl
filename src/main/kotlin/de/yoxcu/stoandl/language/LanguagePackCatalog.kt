package de.yoxcu.stoandl.language

import de.yoxcu.stoandl.pebble.isCoreDevice
import de.yoxcu.stoandl.util.LenientJson
import io.github.oshai.kotlinlogging.KotlinLogging
import io.rebble.libpebblecommon.metadata.WatchHardwarePlatform
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

private val log = KotlinLogging.logger {}

/**
 * The Pebble language-pack catalog: the same manifest the official Core app ships. It's read from the
 * resource `language-packs.json`, which the Gradle build extracts at compile time from libpebble3's
 * `LanguagePackRepository` (in the Compose `pebble` module stoandl doesn't depend on, so we can't
 * reference it directly) — see `generateLanguagePackCatalog` in build.gradle.kts. Generating it from
 * the submodule means it can't drift from the fork: bump the submodule and the catalog refreshes.
 *
 * Each `.pbl` pack is keyed by (ISO locale, hardware board, source). Most come from Rebble's CDN
 * (`binaries.rebble.io/lp/`); a handful of community packs (Japanese, Hebrew, Bulgarian, …) are
 * raw GitHub URLs. Picking a pack and installing it is all the connected watch needs — installation
 * itself is libpebble3's `installLanguagePack(url, name)` (it downloads, then PutBytes-transfers it).
 *
 * The catalog is read-only and offline; only [LanguageControl.install] (which downloads) is egress.
 */
class LanguagePackCatalog private constructor(private val packs: List<LanguagePack>) {

    /** The full deduped catalog (every locale and board), for offline browse/search — no watch or
     *  daemon needed, since the manifest is a bundled resource. */
    fun all(): List<LanguagePack> = packs

    /**
     * Packs applicable to [watch]'s board, best-first for [locale]: an exact locale match first,
     * then a same-language match (e.g. `de_DE` when the watch wants `de_AT`), then the rest. Boards
     * filter out packs built for other hardware; board-agnostic community packs (`hardware == null`)
     * always apply.
     */
    fun forWatch(watch: WatchHardwarePlatform, locale: String): List<LanguagePack> {
        val board = watch.languagePackBoard()
        return packs
            .filter { it.hardware == null || it.hardware == board }
            .sortedWith(
                compareByDescending<LanguagePack> { it.isoLocal.equals(locale, ignoreCase = true) }
                    .thenByDescending { it.isoLocal.take(2).equals(locale.take(2), ignoreCase = true) }
                    .thenBy { it.name },
            )
    }

    companion object {
        /** Load and parse the bundled catalog. Returns an empty catalog (not an error) if it's
         *  missing or malformed, so the daemon still starts — language packs just won't be listed. */
        fun load(): LanguagePackCatalog {
            val stream = LanguagePackCatalog::class.java.getResourceAsStream("/language-packs.json")
            if (stream == null) {
                log.error { "language-packs.json resource not found; language catalog is empty" }
                return LanguagePackCatalog(emptyList())
            }
            return try {
                val file = LenientJson.decodeFromString<LanguagePackFile>(stream.bufferedReader().use { it.readText() })
                LanguagePackCatalog(dedupe(file.languages))
            } catch (e: Exception) {
                log.error(e) { "Failed to parse language-packs.json; language catalog is empty" }
                LanguagePackCatalog(emptyList())
            }
        }

        /** Keep one pack per (locale, board, source): the one built for the newest firmware. Mirrors
         *  the official app, which collapses Rebble's many per-firmware revisions to a single entry. */
        private fun dedupe(all: List<LanguagePack>): List<LanguagePack> =
            all.groupBy { "${it.isoLocal}/${it.hardware}/${it.source()}" }
                .values
                // `values` groups are never empty, so maxBy always returns an element.
                .map { group -> group.maxBy { semver(it.firmwareVersion) } }

        /** Rebble publishes every pack under one host; collapse its URLs to that host so all of a
         *  locale+board's firmware revisions group together (community GitHub URLs stay distinct). */
        private fun LanguagePack.source(): String =
            if (file.startsWith("https://binaries.rebble.io")) "https://binaries.rebble.io" else file

        /** A comparable numeric key for a `major.minor.patch` string (so `3.10.0` > `3.8.0`). */
        private fun semver(s: String): Long {
            val p = s.split('.')
            fun part(i: Int) = p.getOrNull(i)?.takeWhile { it.isDigit() }?.toLongOrNull() ?: 0L
            return part(0) * 1_000_000 + part(1) * 1_000 + part(2)
        }
    }
}

/**
 * Core devices (Pebble 2 Duo, Pebble Time 2, …) run the modern firmware and share the Diorite
 * (`silk`) language packs; classic Pebbles map straight to their own board revision. This mirrors
 * the official app's `languagePackPlatform()` so a Time 2 picks the `silk` pack, not nothing.
 */
fun WatchHardwarePlatform.languagePackBoard(): String =
    if (isCoreDevice()) WatchHardwarePlatform.PEBBLE_SILK.revision else revision

@Serializable
data class LanguagePack(
    @SerialName("ISOLocal") val isoLocal: String,
    val file: String,
    @SerialName("firmware") val firmwareVersion: String = "",
    val hardware: String? = null,
    val localName: String = "",
    val name: String = "",
    val version: Int = 0,
    val id: String = "",
) {
    /** What the watch shows / what we report, e.g. `Deutsch (German) v34`. */
    fun displayName(): String = "$localName ($name) v$version"
}

@Serializable
private data class LanguagePackFile(val languages: List<LanguagePack>)
