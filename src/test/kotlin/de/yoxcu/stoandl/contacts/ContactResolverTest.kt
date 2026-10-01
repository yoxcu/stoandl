package de.yoxcu.stoandl.contacts

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The folder walk decides which address books count at all; a miss is silent (no caller name). */
class ContactResolverTest {
    private val root: File = Files.createTempDirectory("vcards").toFile()

    @AfterTest
    fun cleanUp() { root.deleteRecursively() }

    private fun vcard(path: String, name: String, tel: String) = File(root, path).apply {
        parentFile.mkdirs()
        writeText("BEGIN:VCARD\r\nVERSION:3.0\r\nFN:$name\r\nTEL;TYPE=cell:$tel\r\nEND:VCARD\r\n")
    }

    @Test
    fun `walks folders recursively and takes vcf and vcard only`() {
        vcard("own/contact (1).vcard", "A", "0151 1111111")
        vcard("yoxcu/personal/b.VCF", "B", "0151 2222222")
        vcard(".hidden/c.vcf", "C", "0151 3333333")
        vcard("own/.d.vcf", "D", "0151 4444444")
        vcard("own/e.vcf.tmpXY", "E", "0151 5555555")
        vcard("own/f.txt", "F", "0151 6666666")

        assertEquals(
            listOf("own/contact (1).vcard", "yoxcu/personal/b.VCF"),
            ContactResolver.vcardFilesUnder(root).map { it.relativeTo(root).path },
        )
        val r = ContactResolver { listOf(root.path) }
        assertEquals("A", r.resolve("+491511111111"))
        assertEquals("B", r.resolve("01512222222"))
        assertNull(r.resolve("01513333333"))
        assertNull(r.resolve("01515555555"))
    }

    @Test
    fun `keeps every vCard of a file`() {
        File(root, "all.vcf").writeText(
            "BEGIN:VCARD\nFN:A\nTEL:0151 1111111\nEND:VCARD\nBEGIN:VCARD\nFN:B\nTEL:0151 2222222\nEND:VCARD\n",
        )
        val r = ContactResolver { listOf(root.path) }
        assertEquals("A", r.resolve("01511111111"))
        assertEquals("B", r.resolve("01512222222"))
    }

    @Test
    fun `a missing folder is just empty`() {
        assertNull(ContactResolver { listOf(File(root, "nope").path) }.resolve("01511111111"))
    }
}
