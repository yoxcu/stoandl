package de.yoxcu.stoandl.dbus

/** HTML element names a notification body may carry. A `<word>` that isn't one is left as text. */
private val HTML_TAGS = setOf(
    "a", "abbr", "b", "big", "blockquote", "body", "br", "cite", "code", "del", "div", "em", "font",
    "h1", "h2", "h3", "h4", "h5", "h6", "hr", "html", "i", "img", "ins", "kbd", "li", "mark", "ol", "p",
    "pre", "q", "s", "small", "span", "strike", "strong", "sub", "sup", "table", "td", "th", "tr", "tt",
    "u", "ul",
)

/** `<name attr="v" attr2='v' attr3=v flag>`, `</name>` or `<name/>`: groups 1 = `/` of a closing tag,
 *  2 = the name, 3 = the attributes. A `<` followed by a space, a digit or punctuation never matches.
 *  The attribute run is possessive (`*+`): a greedy group recurses once per attribute, so a long run of
 *  words after `<a` would overflow the stack; giving one back can never help a match anyway. */
private val TAG = Regex(
    """<(/?)([A-Za-z][A-Za-z0-9]*)((?:\s+[^\s"'<>/=]+(?:\s*=\s*(?:"[^"]*"|'[^']*'|[^\s"'<>=`]+))?)*+)\s*/?>""",
)
private val ALT = Regex("""(?:^|\s)alt\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s"'<>=`]+))""", RegexOption.IGNORE_CASE)
private val ENTITY = Regex("""&(?:#([0-9]{1,7})|#[xX]([0-9A-Fa-f]{1,6})|(amp|lt|gt|quot|apos|nbsp));""")

// &nbsp; as a plain space: that is what the watch fonts can draw.
private val NAMED_ENTITIES = mapOf("amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ")

/**
 * A notification body's markup as the plain text the watch draws. `Notify` bodies are markup: GNOME Shell
 * and Plasma both advertise `body-markup`, so KNotifications apps escape `&`, `<` and `>` (NeoChat sends
 * `Tom &amp; Jerry`) and other apps send `<b>` or `<a href>`. `<br>` becomes a line break, an image its
 * `alt` text, and every other HTML tag is dropped (a link keeps its text); then character references
 * are decoded, once. A `<` that doesn't open an HTML tag (`a < b`, `<3`, `<file>`) stays as it is.
 */
internal fun markupToPlainText(markup: String): String {
    if ('<' !in markup && '&' !in markup) return markup
    val text = TAG.replace(markup) { m ->
        val closing = m.groupValues[1].isNotEmpty()
        when (m.groupValues[2].lowercase()) {
            !in HTML_TAGS -> m.value
            "br" -> if (closing) "" else "\n"
            "img" -> if (closing) "" else ALT.find(m.groupValues[3])?.groupValues?.drop(1)?.firstOrNull { it.isNotEmpty() }.orEmpty()
            else -> ""
        }
    }
    return ENTITY.replace(text) { m ->
        val (dec, hex, name) = m.destructured
        if (name.isNotEmpty()) return@replace NAMED_ENTITIES.getValue(name)
        val cp = if (dec.isNotEmpty()) dec.toInt() else hex.toInt(16)
        if (cp == 0 || cp > 0x10FFFF || cp in 0xD800..0xDFFF) m.value else String(Character.toChars(cp))
    }
}
