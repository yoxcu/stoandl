package de.yoxcu.stoandl.contacts

import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.File

/**
 * DE-agnostic caller-ID resolution by reading vCard (`.vcf`) files. There is no contacts D-Bus API
 * shared across GNOME (evolution-data-server) and Plasma/KDE (Akonadi/KPeople); the common
 * denominator both export — and that Plasma Mobile's `kpeoplevcard` backend stores natively in
 * `~/.local/share/kpeoplevcard/` — is vCard. So we parse `.vcf` directly.
 *
 * Numbers are matched digits-only by suffix, so a stored `0151 23456789` resolves an incoming
 * `+4915123456789` and vice versa. Files are re-read when their size/mtime changes.
 */
class ContactResolver(private val vcardPaths: List<String>) {
    private val log = KotlinLogging.logger {}

    // normalized (digits-only) number -> display name
    @Volatile private var index: Map<String, String> = emptyMap()
    @Volatile private var signature: String = "<unloaded>"

    val enabled: Boolean get() = vcardPaths.isNotEmpty()

    fun resolve(number: String): String? {
        if (!enabled) return null
        reloadIfChanged()
        val n = normalize(number)
        if (n.isEmpty()) return null
        index[n]?.let { return it }
        // Suffix match handles +country-code vs leading-0 local form.
        return index.entries.firstOrNull { (k, _) ->
            val kt = k.takeLast(SUFFIX_LEN)
            kt.isNotEmpty() && (k.endsWith(n.takeLast(SUFFIX_LEN)) || n.endsWith(kt))
        }?.value
    }

    private fun reloadIfChanged() {
        val files = vcardFiles()
        val sig = files.joinToString("|") { "${it.path}:${it.lastModified()}:${it.length()}" }
        if (sig == signature) return
        val newIndex = HashMap<String, String>()
        files.forEach { f ->
            try {
                parseVcards(f.readText(), newIndex)
            } catch (e: Exception) {
                log.warn { "Failed to parse vCard ${f.path}: ${e.message}" }
            }
        }
        index = newIndex
        signature = sig
        log.info { "Contact index loaded: ${newIndex.size} number(s) from ${files.size} file(s)" }
    }

    private fun vcardFiles(): List<File> = vcardPaths.flatMap { p ->
        val f = File(p)
        when {
            f.isDirectory -> f.listFiles { file -> file.isFile && file.name.endsWith(".vcf", ignoreCase = true) }
                ?.sorted() ?: emptyList()
            f.isFile -> listOf(f)
            else -> emptyList()
        }
    }

    private fun parseVcards(text: String, out: MutableMap<String, String>) {
        // Unfold RFC 6350 folded lines (a continuation line begins with a space or tab).
        val unfolded = text.replace(Regex("\\r?\\n[ \\t]"), "")
        var fn: String? = null
        val tels = mutableListOf<String>()
        unfolded.lineSequence().forEach { line ->
            when {
                line.startsWith("END:VCARD", ignoreCase = true) -> {
                    fn?.let { name -> tels.forEach { t -> normalize(t).takeIf { it.isNotEmpty() }?.let { out[it] = name } } }
                    fn = null; tels.clear()
                }
                line.startsWith("BEGIN:VCARD", ignoreCase = true) -> { fn = null; tels.clear() }
                line.startsWith("FN", ignoreCase = true) ->
                    line.substringAfter(':', "").trim().takeIf { it.isNotEmpty() }?.let { fn = it }
                line.startsWith("TEL", ignoreCase = true) -> {
                    var v = line.substringAfter(':', "").trim()
                    if (v.startsWith("tel:", ignoreCase = true)) v = v.substring(4)
                    if (v.isNotEmpty()) tels.add(v)
                }
            }
        }
    }

    private fun normalize(s: String): String = s.filter { it.isDigit() }

    companion object {
        private const val SUFFIX_LEN = 9
    }
}
