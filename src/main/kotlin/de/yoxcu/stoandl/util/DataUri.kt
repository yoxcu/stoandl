package de.yoxcu.stoandl.util

import java.io.ByteArrayOutputStream
import java.util.Base64

/** A decoded `data:` URI: its declared media type (may lie; sniff the bytes) and its body. */
class DataUri(val mime: String, val bytes: ByteArray)

/**
 * The one `data:` parser: base64 or percent-encoded bodies, decoded byte-exact (a `+` is a literal plus,
 * not a space; a fragment is dropped). Throws [IllegalArgumentException] on a malformed URI.
 */
fun decodeDataUri(uri: String): DataUri {
    val comma = uri.indexOf(',')
    require(uri.startsWith("data:", ignoreCase = true) && comma >= 0) { "malformed data: URL" }
    val meta = uri.substring(5, comma).split(';')
    val body = percentDecodeBytes(uri.substring(comma + 1).substringBefore('#'))
    val bytes = if (meta.drop(1).any { it.equals("base64", ignoreCase = true) })
        Base64.getMimeDecoder().decode(body)
    else body
    return DataUri(meta.first().ifBlank { "text/plain" }, bytes)
}

/** `%XX` escapes to bytes; everything else is taken as its UTF-8 bytes. */
fun percentDecodeBytes(s: String): ByteArray {
    val out = ByteArrayOutputStream(s.length)
    var i = 0
    while (i < s.length) {
        val c = s[i]
        if (c == '%') {
            require(i + 3 <= s.length) { "truncated % escape" }
            val hex = s.substring(i + 1, i + 3)
            out.write(hex.toIntOrNull(16) ?: throw IllegalArgumentException("bad % escape '%$hex'"))
            i += 3
        } else {
            // A run of plain characters, written as UTF-8 (surrogate pairs stay together).
            val end = s.indexOf('%', i).let { if (it < 0) s.length else it }
            out.write(s.substring(i, end).toByteArray(Charsets.UTF_8))
            i = end
        }
    }
    return out.toByteArray()
}
