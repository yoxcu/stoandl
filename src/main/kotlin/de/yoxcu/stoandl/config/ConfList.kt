package de.yoxcu.stoandl.config

/**
 * The encoding of a list-valued `stoandl.conf` key (and of a `list` value on the `GetConfig`/`SetConfig`
 * wire): items joined by `,`, with `\,` for a comma inside an item and `\\` for a backslash. A backslash
 * before any other character stays literal, so every plain list written before escaping existed reads
 * back unchanged. Items are trimmed and blank ones dropped.
 */
object ConfList {
    fun decode(raw: String?): List<String> {
        if (raw.isNullOrEmpty()) return emptyList()
        val items = ArrayList<String>()
        val cur = StringBuilder()
        var i = 0
        while (i < raw.length) {
            val c = raw[i]
            val next = raw.getOrNull(i + 1)
            when {
                c == '\\' && (next == ',' || next == '\\') -> { cur.append(next); i++ }
                c == ',' -> { items += cur.toString(); cur.clear() }
                else -> cur.append(c)
            }
            i++
        }
        items += cur.toString()
        return items.map { it.trim() }.filter { it.isNotEmpty() }
    }

    fun encode(items: List<String>): String =
        items.map { it.trim() }.filter { it.isNotEmpty() }
            .joinToString(",") { it.replace("\\", "\\\\").replace(",", "\\,") }
}
