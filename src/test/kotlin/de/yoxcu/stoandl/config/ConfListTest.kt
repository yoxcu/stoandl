package de.yoxcu.stoandl.config

import kotlin.test.Test
import kotlin.test.assertEquals

class ConfListTest {

    @Test
    fun `plain lists written before escaping read back unchanged`() {
        assertEquals(listOf("Ok", "Yes", "Call me"), ConfList.decode("Ok, Yes ,Call me"))
        assertEquals(listOf("a", "b"), ConfList.decode("a,,b, ,"))
        assertEquals(emptyList(), ConfList.decode(""))
        assertEquals(emptyList(), ConfList.decode(null))
        // A backslash before anything but ',' or '\' is literal (a hand-written path or regex stays as is).
        assertEquals(listOf("C:\\pebble", "a\\nb"), ConfList.decode("C:\\pebble,a\\nb"))
    }

    @Test
    fun `escaped commas and backslashes stay inside their item`() {
        assertEquals(listOf("Call me, later", "Ok"), ConfList.decode("Call me\\, later,Ok"))
        assertEquals(listOf("dir\\", "x"), ConfList.decode("dir\\\\,x"))
        assertEquals("Call me\\, later,Ok", ConfList.encode(listOf("Call me, later", "Ok")))
        assertEquals("dir\\\\,x", ConfList.encode(listOf("dir\\", "x")))
    }

    @Test
    fun `encode then decode is the identity on trimmed non-blank items`() {
        val cases = listOf(
            listOf("Ok", "Yes", "No"),
            listOf("Sure, see you then", "Not now, sorry"),
            listOf("trailing\\", "\\,", ",", "\\\\,,\\"),
            listOf("Ünïcödé, ok", "a\\b,c"),
            emptyList(),
        )
        cases.forEach { assertEquals(it, ConfList.decode(ConfList.encode(it)), it.toString()) }
        assertEquals(listOf("a", "b"), ConfList.decode(ConfList.encode(listOf(" a ", "", "  ", "b"))))
    }

    @Test
    fun `decode then encode normalises spacing but keeps escapes`() {
        assertEquals("a\\, b,c", ConfList.encode(ConfList.decode(" a\\, b , c ,")))
    }
}
