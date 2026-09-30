package de.yoxcu.stoandl.pebble

import kotlin.test.Test
import kotlin.test.assertEquals

class CannedRepliesTest {
    @Test
    fun `whole replies are kept while the NUL-joined list fits`() {
        // "aaaa\u0000bbbb" = 9 bytes; a third 4-byte item needs 5 more.
        assertEquals(listOf("aaaa", "bbbb"), trimCannedReplies(listOf("aaaa", "bbbb", "cccc"), maxBytes = 12))
        assertEquals(listOf("aaaa", "bbbb", "cccc"), trimCannedReplies(listOf("aaaa", "bbbb", "cccc"), maxBytes = 14))
    }

    @Test
    fun `blank items are dropped and items are trimmed`() {
        assertEquals(listOf("Ok", "Yes"), trimCannedReplies(listOf(" Ok ", "", "  ", "Yes")))
    }

    @Test
    fun `sizes count UTF-8 bytes, not characters`() {
        // "ä" is 2 bytes; "ää" + NUL + "ä" = 7 bytes.
        assertEquals(listOf("ää"), trimCannedReplies(listOf("ää", "ä"), maxBytes = 6))
        assertEquals(listOf("ää", "ä"), trimCannedReplies(listOf("ää", "ä"), maxBytes = 7))
    }

    @Test
    fun `an item that doesn't fit ends the list rather than being cut`() {
        assertEquals(emptyList(), trimCannedReplies(listOf("x".repeat(600), "Ok")))
    }

    @Test
    fun `labels are cut on character boundaries`() {
        assertEquals("abc", trimUtf8("abc", 3))
        assertEquals("ab", trimUtf8("abä", 3))
        assertEquals("a", trimUtf8("a😀", 4)) // the emoji is 4 bytes; no half surrogate left
        assertEquals("a😀", trimUtf8("a😀", 5))
    }
}
