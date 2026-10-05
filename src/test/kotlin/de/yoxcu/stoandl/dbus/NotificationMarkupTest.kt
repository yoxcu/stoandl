package de.yoxcu.stoandl.dbus

import kotlin.test.Test
import kotlin.test.assertEquals

/** Notify body markup → the plain text the watch draws: an escaped `&amp;` or a raw `<b>` on the wrist is
 *  the failure, and only visible there. */
class NotificationMarkupTest {
    @Test
    fun `named and numeric character references are decoded`() {
        // NeoChat escapes with QString::toHtmlEscaped.
        assertEquals("Tom & Jerry <3 \"hi\"", markupToPlainText("Tom &amp; Jerry &lt;3 &quot;hi&quot;"))
        assertEquals("it's 'ok' 'x' >", markupToPlainText("it&#39;s &#x27;ok&#X27; &apos;x&apos; &gt;"))
        assertEquals("a b", markupToPlainText("a&nbsp;b"))
        assertEquals("😀", markupToPlainText("&#128512;"))
    }

    @Test
    fun `references are decoded once`() {
        assertEquals("&lt;", markupToPlainText("&amp;lt;"))
        // An escaped tag is text, not a tag to strip.
        assertEquals("<b>not bold</b>", markupToPlainText("&lt;b&gt;not bold&lt;/b&gt;"))
    }

    @Test
    fun `unknown or invalid references and a bare ampersand stay as they are`() {
        assertEquals("AT&T &copy; &#0; &#xD800; &#99999999; &amp", markupToPlainText("AT&T &copy; &#0; &#xD800; &#99999999; &amp"))
    }

    @Test
    fun `line breaks become newlines`() {
        assertEquals("one\ntwo\nthree\nfour\nfive", markupToPlainText("one<br>two<br/>three<BR />four<br class=\"x\">five"))
        // A raw newline is kept; a closing </br> adds nothing.
        assertEquals("a\nb\nc", markupToPlainText("a\nb<br></br>c"))
    }

    @Test
    fun `formatting tags are dropped and links keep their text`() {
        assertEquals("bold it under", markupToPlainText("<b>bold</b> <i>it</i> <u>under</u>"))
        assertEquals("see the link now", markupToPlainText("see <a href=\"https://example.org/?a=1&amp;b=2\">the link</a> now"))
        assertEquals("x", markupToPlainText("<A HREF='https://example.org'>x</A>"))
        assertEquals("quoted", markupToPlainText("<span style=\"color: red\">quoted</span>"))
    }

    @Test
    fun `an image becomes its alt text`() {
        assertEquals("smile hi", markupToPlainText("<img src=\"file:///x.png\" alt=\"smile\"/> hi"))
        assertEquals("[pic]", markupToPlainText("<img alt='[pic]' src=x.png>"))
        assertEquals("Tom & Jerry", markupToPlainText("<img src=\"x.png\" alt=\"Tom &amp; Jerry\">"))
        assertEquals(" hi", markupToPlainText("<img src=\"x.png\"> hi"))
    }

    @Test
    fun `nested and mismatched tags are all removed`() {
        assertEquals("x", markupToPlainText("<b><i>x</b></i>"))
        assertEquals("unclosed", markupToPlainText("<b>unclosed"))
        assertEquals("stray", markupToPlainText("</i>stray"))
    }

    @Test
    fun `a less-than that opens no HTML tag is text`() {
        listOf(
            "a < b",
            "<3",
            "x<y",
            "1 <2 and 3> 0",
            "Usage: cmd <file>",
            "<- back",
            "a<",
            "plain text, no markup at all",
        ).forEach { assertEquals(it, markupToPlainText(it)) }
    }

    @Test
    fun `a long run of attributes doesn't overflow the stack`() {
        // A greedy attribute group overflowed a 1 MB stack at about 1500 of these.
        val words = " word".repeat(50_000)
        assertEquals("x", markupToPlainText("<a$words>x"))
        assertEquals("<a$words", markupToPlainText("<a$words"))
    }

    @Test
    fun `an empty body stays empty`() {
        assertEquals("", markupToPlainText(""))
        assertEquals("", markupToPlainText("<b></b>"))
    }
}
