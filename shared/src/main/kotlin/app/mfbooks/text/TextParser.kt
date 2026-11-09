package app.mfbooks.text

import app.mfbooks.library.decodeText
import app.mfbooks.text.TextParseException.Reason
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URLDecoder
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.text.BreakIterator
import java.util.Locale
import java.util.zip.ZipException
import java.util.zip.ZipInputStream
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.parser.Parser
import org.jsoup.select.NodeTraversor
import org.jsoup.select.NodeVisitor

object TextParser {
    const val MAX_FILE_BYTES = 64L * 1024 * 1024
    const val MAX_UNZIPPED_BYTES = 256L * 1024 * 1024

    /** [fileName] picks the format: `.fb2`, `.fb2.zip`, `.epub`, `.txt`. Throws [TextParseException]. */
    fun parse(fileName: String, bytes: ByteArray): BookTextDoc {
        if (bytes.size > MAX_FILE_BYTES) throw TextParseException(Reason.TOO_LARGE, "File is over the size limit")
        val name = fileName.lowercase()
        val source = { format: String -> TextSource(fileName, format, sha256Hex(bytes)) }
        return try {
            when {
                name.endsWith(".fb2.zip") -> {
                    val fb2 = unzip(bytes) { it.lowercase().endsWith(".fb2") }.values.firstOrNull() ?: throw empty()
                    parseFb2(fb2, source("fb2"))
                }
                name.endsWith(".fb2") -> parseFb2(bytes, source("fb2"))
                name.endsWith(".epub") -> parseEpub(bytes, source("epub"))
                name.endsWith(".txt") -> parseTxt(bytes, source("txt"))
                else -> throw TextParseException(Reason.UNSUPPORTED, "Unsupported file type: $fileName")
            }
        } catch (_: StackOverflowError) {
            // ponytail: the walkers recurse with the markup. Only hostile files nest deep enough to get here.
            throw empty()
        }
    }

    /** BCP 47 base language from the alphabet: "ru" or "en". */
    fun detectLanguage(text: String): String {
        var cyrillic = 0
        var latin = 0
        for (c in text) when (c) {
            in 'a'..'z', in 'A'..'Z' -> latin++
            in 'Ѐ'..'ӿ' -> cyrillic++
        }
        return if (cyrillic > latin) "ru" else "en"
    }
}

private fun empty() = TextParseException(Reason.EMPTY, "No book text found")

/** Actual bytes are counted, never the sizes the zip declares. Entries failing [keep] are counted and dropped. */
private fun unzip(bytes: ByteArray, keep: (String) -> Boolean): Map<String, ByteArray> {
    val out = LinkedHashMap<String, ByteArray>()
    var total = 0L
    val buf = ByteArray(64 * 1024)
    try {
        ZipInputStream(bytes.inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val sink = if (!entry.isDirectory && keep(entry.name)) ByteArrayOutputStream() else null
                while (true) {
                    val n = zip.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > TextParser.MAX_UNZIPPED_BYTES) throw TextParseException(Reason.TOO_LARGE, "Archive unpacks to more than the limit")
                    sink?.write(buf, 0, n)
                }
                if (sink != null) out[entry.name] = sink.toByteArray()
            }
        }
    } catch (_: ZipException) {
        throw empty()
    } catch (_: IOException) {
        throw empty()
    } catch (_: IllegalArgumentException) { // malformed entry name
        throw empty()
    }
    return out
}

private val PROLOG_ENCODING = Regex("""<\?xml[^>]*encoding\s*=\s*["']([\w.:-]+)["']""")

private fun isUtf8(b: ByteArray) = try {
    Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(b))
    true
} catch (_: CharacterCodingException) {
    false
}

/** Bytes that are valid UTF-8 are UTF-8 whatever the prolog claims. Otherwise the prolog beats the statistical guess. */
private fun decodeXml(b: ByteArray): String {
    val head = String(b, 0, minOf(b.size, 256), Charsets.ISO_8859_1)
    val declared = PROLOG_ENCODING.find(head)?.groupValues?.get(1)?.let { runCatching { Charset.forName(it) }.getOrNull() }
    return if (declared == null || declared == Charsets.UTF_8 || isUtf8(b)) decodeText(b) else String(b, declared)
}

private fun xml(b: ByteArray): Document = Jsoup.parse(decodeXml(b), "", Parser.xmlParser())

private fun normaliseLang(raw: String?): String? {
    val base = raw?.trim()?.lowercase()?.split('-', '_')?.first().orEmpty()
    return when (base.length) {
        2 -> base
        3 -> Locale.getISOLanguages().firstOrNull { runCatching { Locale.forLanguageTag(it).isO3Language }.getOrNull() == base }
        else -> null
    }
}

private val EM_TAGS = setOf("emphasis", "strong", "i", "em", "b")
private val BLOCK_TAGS = setOf(
    "p", "div", "section", "article", "aside", "main", "header", "footer", "nav", "figure", "figcaption", "center", "address",
    "ul", "ol", "li", "dl", "dt", "dd", "table", "caption", "thead", "tbody", "tfoot", "tr", "td", "th", "pre", "hr",
)
private val BREAK_TAGS = BLOCK_TAGS + setOf("br", "v", "subtitle", "text-author")

/** Collects blocks as a book is walked. [anchors] maps `file` and `file#id` to the block that follows them (EPUB TOC). */
private class Out {
    val blocks = ArrayList<Block>()
    val toc = ArrayList<TocEntry>()
    val anchors = HashMap<String, Int>()
    var file = ""

    fun anchor(e: Element) {
        if (e.id().isNotEmpty()) anchors.putIfAbsent("$file#${e.id()}", blocks.size)
    }

    /** Appends one block from [nodes] with whitespace collapsed. Index of the new block, or -1 if it had no text. */
    fun add(kind: String, nodes: List<Node>): Int {
        val sb = StringBuilder()
        val em = ArrayList<IntArray>()
        var depth = 0
        var emStart = 0
        fun space() {
            if (sb.isNotEmpty() && sb.last() != ' ') sb.append(' ')
        }
        val visitor = object : NodeVisitor {
            override fun head(n: Node, d: Int) {
                if (n is TextNode) {
                    for (c in n.wholeText) if (c.isWhitespace()) space() else sb.append(c)
                } else if (n is Element) {
                    anchor(n)
                    if (n.normalName() in BREAK_TAGS) space()
                    if (n.normalName() in EM_TAGS && depth++ == 0) emStart = sb.length
                }
            }

            override fun tail(n: Node, d: Int) {
                if (n !is Element) return
                if (n.normalName() in EM_TAGS && --depth == 0) em += intArrayOf(emStart, sb.length)
                if (n.normalName() in BREAK_TAGS) space()
            }
        }
        for (n in nodes) NodeTraversor.traverse(visitor, n)
        val s = sb.trimEnd().toString()
        if (s.isEmpty()) return -1
        val ranges = em.mapNotNull { (from, to) ->
            var a = from
            var b = minOf(to, s.length)
            while (a < b && s[a] == ' ') a++
            while (b > a && s[b - 1] == ' ') b--
            if (a < b) listOf(a, b) else null
        }
        blocks += Block(kind, s, em = ranges)
        return blocks.lastIndex
    }

    fun add(kind: String, e: Element) = add(kind, listOf<Node>(e))

    fun heading(kind: String, nodes: List<Node>) {
        val i = add(kind, nodes)
        if (i >= 0) toc += TocEntry(blocks[i].s, i)
    }

    /** Zero length, so an image shifts no offsets and the reader draws its own marker. */
    fun image() {
        blocks += Block(BlockKind.IMAGE, "")
    }

    fun finish(source: TextSource, title: String?, author: String?, language: String?): BookTextDoc {
        if (blocks.all { it.s.isEmpty() }) throw empty()
        val lang = normaliseLang(language) ?: TextParser.detectLanguage(blocks.joinToString("") { it.s })
        val breaks = BreakIterator.getSentenceInstance(Locale.forLanguageTag(lang))
        val withSentences = blocks.map { b ->
            if (b.s.isEmpty()) return@map b
            breaks.setText(b.s)
            val sen = ArrayList<Int>()
            var p = breaks.first()
            while (p != BreakIterator.DONE && p < b.s.length) {
                sen += p
                p = breaks.next()
            }
            b.copy(sen = sen)
        }
        return BookTextDoc(lang = lang, title = title?.takeIf { it.isNotBlank() }, author = author?.takeIf { it.isNotBlank() }, source = source, toc = toc, blocks = withSentences)
    }
}

// FB2

private val NOTE_BODIES = setOf("notes", "comments", "footnotes")

/** A base64 cover is most of a typical FB2, so it goes before Jsoup sees it. */
private fun stripBinary(xml: String): String {
    var i = xml.indexOf("<binary")
    if (i < 0) return xml
    val sb = StringBuilder(xml.length)
    var from = 0
    while (i >= 0) {
        sb.append(xml, from, i)
        val end = xml.indexOf("</binary>", i)
        if (end < 0) return sb.toString()
        from = end + "</binary>".length
        i = xml.indexOf("<binary", from)
    }
    return sb.append(xml, from, xml.length).toString()
}

private fun headingAt(level: Int) = when {
    level <= 1 -> BlockKind.H1
    level == 2 -> BlockKind.H2
    else -> BlockKind.H3
}

/** [level] is section depth, 0 for the body itself. [kind] is what plain paragraphs become here. */
private fun Out.walkFb2(el: Element, level: Int, kind: String) {
    fun inner(k: String) = if (kind == BlockKind.NOTE) kind else k
    for (c in el.children()) when (c.normalName()) {
        "title" -> when {
            kind != BlockKind.P -> add(kind, c)
            level == 0 -> add(BlockKind.H1, c)
            else -> heading(headingAt(level), listOf(c))
        }
        "section" -> walkFb2(c, level + 1, kind)
        "subtitle" -> add(inner(BlockKind.H3), c)
        "image" -> image()
        "empty-line" -> Unit
        "poem", "stanza" -> walkFb2(c, level, inner(BlockKind.POEM))
        "cite", "epigraph", "annotation" -> walkFb2(c, level, inner(BlockKind.QUOTE))
        else -> add(kind, c) // p, v, text-author, date, tables
    }
}

private fun parseFb2(bytes: ByteArray, source: TextSource): BookTextDoc {
    val root = Jsoup.parse(stripBinary(decodeXml(bytes)), "", Parser.xmlParser())
    val out = Out()
    for (body in root.select("body")) {
        val notes = body.attr("name").lowercase() in NOTE_BODIES
        out.walkFb2(body, 0, if (notes) BlockKind.NOTE else BlockKind.P)
    }
    val info: Element? = root.selectFirst("title-info")
    val a: Element? = info?.selectFirst("author")
    val author: String? = a?.let { el ->
        listOf("first-name", "middle-name", "last-name").mapNotNull { el.selectFirst(it)?.text()?.takeIf(String::isNotBlank) }
            .joinToString(" ").ifEmpty { el.selectFirst("nickname")?.text() }
    }
    return out.finish(source, info?.selectFirst("book-title")?.text(), author, info?.selectFirst("lang")?.text())
}

// EPUB

private val FONT_OBFUSCATION = setOf("http://www.idpf.org/2008/embedding", "http://ns.adobe.com/pdf/enc#RC")
private val NOT_TEXT = Regex("""\.(jpe?g|png|gif|webp|svg|bmp|ttf|otf|woff2?|mp3|m4a|mp4|aac|ogg|css|js)$""", RegexOption.IGNORE_CASE)
private val HEADINGS = mapOf("h1" to BlockKind.H1, "h2" to BlockKind.H2, "h3" to BlockKind.H3, "h4" to BlockKind.H3, "h5" to BlockKind.H3, "h6" to BlockKind.H3)
private val NOTE_TYPES = Regex("""\b(footnotes?|endnotes?|rearnotes?)\b""")
private val VOID_TAGS = setOf("area", "base", "br", "col", "embed", "hr", "img", "input", "link", "meta", "param", "source", "track", "wbr")
private val SELF_CLOSED = Regex("""<([a-zA-Z][\w:-]*)((?:\s[^<>]*)?)/>""")

/** Prefix-blind, because OPF and encryption files come with and without namespace prefixes. */
private fun Iterable<Element>.named(name: String) = filter { it.tagName().substringAfterLast(':').equals(name, ignoreCase = true) }

/** The HTML parser ignores `/>` on non-void tags, and an XHTML `<title/>` would then swallow the whole document. */
private fun html(b: ByteArray): Document {
    val doc = Jsoup.parse(SELF_CLOSED.replace(decodeXml(b)) { m ->
        val tag = m.groupValues[1]
        if (tag.lowercase() in VOID_TAGS) m.value else "<$tag${m.groupValues[2]}></$tag>"
    })
    doc.select("script, style, template, svg *").remove()
    return doc
}

private fun resolve(base: String, href: String): String {
    val rel = href.substringBefore('#').replace("+", "%2B").let { runCatching { URLDecoder.decode(it, "UTF-8") }.getOrDefault(it) }
    val parts = ArrayList<String>()
    for (p in "${base.substringBeforeLast('/', "")}/$rel".split('/')) when (p) {
        "", "." -> Unit
        ".." -> parts.removeLastOrNull()
        else -> parts += p
    }
    return parts.joinToString("/")
}

private fun target(base: String, href: String): String {
    val fragment = href.substringAfter('#', "")
    return resolve(base, href) + if (fragment.isEmpty()) "" else "#$fragment"
}

private fun hasImage(n: Node) = n is Element && (n.normalName() in setOf("img", "image", "svg") || n.selectFirst("img, image, svg") != null)

private fun Out.walkHtml(el: Element, kind: String) {
    fun inner(k: String) = if (kind == BlockKind.NOTE) kind else k
    val run = ArrayList<Node>()
    fun flush() {
        if (add(kind, run) < 0 && run.any(::hasImage)) image()
        run.clear()
    }
    for (n in el.childNodes()) {
        val tag = (n as? Element)?.normalName()
        when {
            tag == null -> run += n
            tag in HEADINGS -> { flush(); add(inner(HEADINGS.getValue(tag)), n as Element) }
            tag == "blockquote" -> { flush(); anchor(n as Element); walkHtml(n, inner(BlockKind.QUOTE)) }
            tag in BLOCK_TAGS -> {
                flush()
                anchor(n as Element)
                walkHtml(n, if (NOTE_TYPES.containsMatchIn(n.attr("epub:type"))) BlockKind.NOTE else kind)
            }
            else -> run.add(n) // += is ambiguous here: a smart-cast Element is also an Iterable
        }
    }
    flush()
}

private fun rejectDrm(encryption: ByteArray) {
    for (data in xml(encryption).allElements.named("EncryptedData")) {
        val algorithm = data.allElements.named("EncryptionMethod").firstOrNull()?.attr("Algorithm")
        if (algorithm !in FONT_OBFUSCATION) throw TextParseException(Reason.DRM, "This book is DRM-protected")
    }
}

private fun navToc(path: String, bytes: ByteArray): List<Pair<String, String>> {
    val doc = html(bytes)
    val root = doc.select("nav").firstOrNull { "toc" in it.attr("epub:type") } ?: doc.selectFirst("nav") ?: doc
    return root.select("a[href]").map { it.text() to target(path, it.attr("href")) }
}

private fun ncxToc(path: String, bytes: ByteArray): List<Pair<String, String>> = xml(bytes).allElements.named("navPoint").mapNotNull { p ->
    val label = p.children().named("navLabel").firstOrNull()?.text() ?: return@mapNotNull null
    val src = p.children().named("content").firstOrNull()?.attr("src") ?: return@mapNotNull null
    label to target(path, src)
}

private fun parseEpub(bytes: ByteArray, source: TextSource): BookTextDoc {
    val files = unzip(bytes) { !NOT_TEXT.containsMatchIn(it) }
    files["META-INF/encryption.xml"]?.let(::rejectDrm)
    val opfPath = files["META-INF/container.xml"]?.let { xml(it).allElements.named("rootfile").firstOrNull()?.attr("full-path") }?.takeIf { it in files }
        ?: files.keys.firstOrNull { it.endsWith(".opf", ignoreCase = true) } ?: throw empty()
    val opf = xml(files.getValue(opfPath)).allElements

    val items = opf.named("item").associateBy { it.attr("id") }
    fun pathOf(item: Element) = resolve(opfPath, item.attr("href"))
    val spine = opf.named("spine").firstOrNull()

    val out = Out()
    for (ref in opf.named("itemref")) {
        val item = items[ref.attr("idref")] ?: continue
        val path = pathOf(item)
        val content = files[path] ?: continue
        if (item.attr("media-type").let { it.isNotEmpty() && "html" !in it && "xml" !in it }) continue
        out.file = path
        out.anchors.putIfAbsent(path, out.blocks.size)
        html(content).body()?.let { out.walkHtml(it, BlockKind.P) }
    }

    val nav = items.values.firstOrNull { "nav" in it.attr("properties").split(' ') }?.let { i -> files[pathOf(i)]?.let { navToc(pathOf(i), it) } }
    val ncxItem = items[spine?.attr("toc").orEmpty()] ?: items.values.firstOrNull { "ncx" in it.attr("media-type") }
    val entries = nav?.takeIf { it.isNotEmpty() } ?: ncxItem?.let { i -> files[pathOf(i)]?.let { ncxToc(pathOf(i), it) } }.orEmpty()
    for ((title, to) in entries) {
        val block = out.anchors[to] ?: out.anchors[to.substringBefore('#')] ?: continue
        if (title.isNotBlank() && block < out.blocks.size) out.toc += TocEntry(title, block)
    }
    val meta = { name: String -> opf.named(name).firstOrNull()?.text() }
    return out.finish(source, meta("title"), meta("creator"), meta("language"))
}

// TXT

// ponytail: chapter words in English and Russian only, matching the books this app targets.
private val TXT_HEADING = Regex(
    """^(?:(?:chapter|part|глава|часть)\s+(?:(?:\d+|[ivxlcdm]+)(?!\p{L}).*|\p{L}+(?:\s*[.:—–-].*)?)|(?:prologue|epilogue|пролог|эпилог)(?:\s*[.:—–-].*)?)$""",
    setOf(RegexOption.IGNORE_CASE),
)
private const val TXT_HEADING_MAX = 60

private fun parseTxt(bytes: ByteArray, source: TextSource): BookTextDoc {
    val lines = decodeText(bytes).split("\r\n", "\n", "\r").map { it.trim() }
    val filled = lines.count { it.isNotEmpty() }
    val onePerLine = lines.size - filled < filled / 20 // almost no blank lines: every line is a paragraph
    val out = Out()
    val para = StringBuilder()
    fun flush() {
        if (para.isNotEmpty()) out.add(BlockKind.P, listOf<Node>(TextNode(para.toString())))
        para.setLength(0)
    }
    for (line in lines) {
        when {
            line.isEmpty() -> flush()
            line.length <= TXT_HEADING_MAX && TXT_HEADING.matches(line) -> {
                flush()
                out.heading(BlockKind.H2, listOf(TextNode(line)))
            }
            else -> {
                if (para.isNotEmpty()) para.append(' ')
                para.append(line)
                if (onePerLine) flush()
            }
        }
    }
    flush()
    return out.finish(source, null, null, null)
}
