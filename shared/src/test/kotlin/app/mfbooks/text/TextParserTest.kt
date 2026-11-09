package app.mfbooks.text

import app.mfbooks.text.TextParseException.Reason
import java.io.ByteArrayOutputStream
import java.nio.charset.Charset
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class TextParserTest {
    private fun zip(vararg files: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            for ((name, data) in files) {
                z.putNextEntry(ZipEntry(name))
                z.write(data)
                z.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun reason(block: () -> Unit): Reason {
        try {
            block()
        } catch (e: TextParseException) {
            return e.reason
        }
        fail("expected TextParseException")
        throw AssertionError()
    }

    private val fb2 = """<?xml version="1.0" encoding="windows-1251"?>
        <FictionBook xmlns="http://www.gribuser.ru/xml/fictionbook/2.0" xmlns:l="http://www.w3.org/1999/xlink">
        <description><title-info><author><first-name>Лев</first-name><last-name>Толстой</last-name></author>
        <book-title>Война и мир</book-title><lang>ru-RU</lang></title-info></description>
        <body><title><p>Война и мир</p></title>
        <section><title><p>Глава 1</p></title>
          <p>Первое   <emphasis>важное</emphasis> предложение. Второе предложение.</p>
          <poem><stanza><v>Строка стиха</v></stanza></poem>
          <cite><p>Цитата</p></cite><empty-line/><image l:href="#c.png"/>
          <section><title><p>Часть 1</p></title><p>Вложенный текст</p></section>
        </section></body>
        <body name="notes"><section><title><p>1</p></title><p>Сноска</p></section></body>
        <binary id="c.png" content-type="image/png">AAAAtextinsidebinary</binary>
        </FictionBook>""".trimIndent()

    @Test fun fb2InWindows1251() {
        val bytes = fb2.toByteArray(Charset.forName("windows-1251"))
        val doc = TextParser.parse("book.fb2", bytes)
        assertEquals("ru", doc.lang)
        assertEquals("Война и мир", doc.title)
        assertEquals("Лев Толстой", doc.author)
        assertEquals(TextSource("book.fb2", "fb2", sha256Hex(bytes)), doc.source)

        val kinds = doc.blocks.map { it.k }
        assertEquals(listOf("h1", "h1", "p", "poem", "quote", "image", "h2", "p", "note", "note"), kinds)
        val p = doc.blocks[2]
        assertEquals("Первое важное предложение. Второе предложение.", p.s)
        assertEquals(listOf(listOf(7, 13)), p.em)
        assertEquals(listOf(0, 27), p.sen)
        assertEquals(listOf(TocEntry("Глава 1", 1), TocEntry("Часть 1", 6)), doc.toc)
        assertEquals("Сноска", doc.blocks.last().s)
        assertFalse(doc.blocks.any { "binary" in it.s })
        assertEquals("", doc.blocks[5].s)
    }

    @Test fun fb2Zip() {
        val doc = TextParser.parse("book.fb2.zip", zip("book.fb2" to fb2.toByteArray(Charset.forName("windows-1251"))))
        assertEquals("Война и мир", doc.title)
        assertEquals("fb2", doc.source.format)
    }

    private val container = """<container><rootfiles><rootfile full-path="OEBPS/content.opf"/></rootfiles></container>""".toByteArray()
    private val opf = """<?xml version="1.0"?>
        <package xmlns="http://www.idpf.org/2007/opf" xmlns:dc="http://purl.org/dc/elements/1.1/">
        <metadata><dc:title>Moby Dick</dc:title><dc:creator>Herman Melville</dc:creator><dc:language>en-US</dc:language></metadata>
        <manifest>
          <item id="b" href="text/ch%202.xhtml" media-type="application/xhtml+xml"/>
          <item id="a" href="text/ch1.xhtml" media-type="application/xhtml+xml"/>
          <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
        </manifest>
        <spine><itemref idref="a"/><itemref idref="b"/></spine></package>""".toByteArray()
    private val nav = """<html xmlns:epub="http://www.idpf.org/2007/ops"><body><nav epub:type="toc"><ol>
        <li><a href="text/ch1.xhtml">One</a></li><li><a href="text/ch%202.xhtml#second">Two</a></li></ol></nav></body></html>""".toByteArray()
    private val ch1 = """<?xml version="1.0"?><html><head><title/><style>p{}</style></head>
        <body><h1>Loomings</h1><p>Call me <i>Ishmael</i>.</p><div><script>x()</script>Bare text</div><p><img src="a.png"/></p></body></html>""".toByteArray()
    private val ch2 = """<html><body><h4>Carpet</h4><h2 id="second">Bag</h2><blockquote><p>Quoted</p></blockquote>
        <aside epub:type="footnote"><p>A note</p></aside></body></html>""".toByteArray()

    private fun epub(encryption: String?) = zip(
        *listOfNotNull(
            "text/unused" to ByteArray(0),
            "OEBPS/text/ch 2.xhtml" to ch2, "OEBPS/text/ch1.xhtml" to ch1, "OEBPS/nav.xhtml" to nav, "OEBPS/content.opf" to opf,
            "META-INF/container.xml" to container, encryption?.let { "META-INF/encryption.xml" to it.toByteArray() },
        ).toTypedArray()
    )

    private fun encrypted(algorithm: String) = """<encryption xmlns="urn:oasis:names:tc:opendocument:xmlns:container" xmlns:enc="http://www.w3.org/2001/04/xmlenc#">
        <enc:EncryptedData><enc:EncryptionMethod Algorithm="$algorithm"/></enc:EncryptedData></encryption>"""

    @Test fun epubSpineTocAndMetadata() {
        val doc = TextParser.parse("book.epub", epub(null))
        assertEquals("Moby Dick", doc.title)
        assertEquals("Herman Melville", doc.author)
        assertEquals("en", doc.lang)
        assertEquals(
            listOf("h1" to "Loomings", "p" to "Call me Ishmael.", "p" to "Bare text", "image" to "", "h3" to "Carpet", "h2" to "Bag", "quote" to "Quoted", "note" to "A note"),
            doc.blocks.map { it.k to it.s },
        )
        assertEquals(listOf(listOf(8, 15)), doc.blocks[1].em)
        assertEquals(listOf(TocEntry("One", 0), TocEntry("Two", 5)), doc.toc)
    }

    @Test fun epubFontObfuscationIsNotDrm() {
        val doc = TextParser.parse("book.epub", epub(encrypted("http://www.idpf.org/2008/embedding")))
        assertEquals("Loomings", doc.blocks[0].s)
    }

    @Test fun epubDrmIsRejected() {
        assertEquals(Reason.DRM, reason { TextParser.parse("book.epub", epub(encrypted("http://www.w3.org/2001/04/xmlenc#aes128-cbc"))) })
    }

    @Test fun txtBlankLineParagraphsAndHeadings() {
        val text = "Глава 1\n\nПервый абзац\nпродолжается здесь.\n\nВторой абзац.\n\nPart of the problem was this.\n\nЭпилог\n\nКонец."
        val doc = TextParser.parse("book.txt", text.toByteArray())
        assertEquals("ru", doc.lang)
        assertEquals(
            listOf("h2" to "Глава 1", "p" to "Первый абзац продолжается здесь.", "p" to "Второй абзац.", "p" to "Part of the problem was this.", "h2" to "Эпилог", "p" to "Конец."),
            doc.blocks.map { it.k to it.s },
        )
        assertEquals(listOf(TocEntry("Глава 1", 0), TocEntry("Эпилог", 4)), doc.toc)
    }

    @Test fun txtSingleNewlineParagraphs() {
        val text = (1..30).joinToString("\n") { "Line number $it of the book." }
        val doc = TextParser.parse("book.txt", text.toByteArray())
        assertEquals(30, doc.blocks.size)
        assertEquals("en", doc.lang)
    }

    @Test fun limitsAndFailures() {
        assertEquals(Reason.UNSUPPORTED, reason { TextParser.parse("book.pdf", ByteArray(4)) })
        assertEquals(Reason.EMPTY, reason { TextParser.parse("book.txt", " \n \n".toByteArray()) })
        assertEquals(Reason.EMPTY, reason { TextParser.parse("book.epub", "not a zip at all".toByteArray()) })
        assertEquals(Reason.TOO_LARGE, reason { TextParser.parse("book.txt", ByteArray(TextParser.MAX_FILE_BYTES.toInt() + 1)) })

        val bomb = ByteArrayOutputStream()
        ZipOutputStream(bomb).use { z ->
            z.putNextEntry(ZipEntry("cover.png"))
            val chunk = ByteArray(1024 * 1024)
            repeat((TextParser.MAX_UNZIPPED_BYTES / chunk.size).toInt() + 1) { z.write(chunk) }
            z.closeEntry()
        }
        assertEquals(Reason.TOO_LARGE, reason { TextParser.parse("book.epub", bomb.toByteArray()) })
    }

    @Test fun languageDetection() {
        assertEquals("ru", TextParser.detectLanguage("Привет, world"))
        assertEquals("en", TextParser.detectLanguage("Hello, мир and everyone"))
        assertTrue(TextParser.detectLanguage("") == "en")
    }
}
