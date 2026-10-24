package app.mfbooks.library

import java.io.ByteArrayOutputStream

class Id3Frame(val id: String, val data: ByteArray)
class Id3Tag(val version: Int, val frames: List<Id3Frame>)

private fun syncsafe(b: ByteArray, p: Int) =
    ((b[p].toInt() and 0x7F) shl 21) or ((b[p + 1].toInt() and 0x7F) shl 14) or ((b[p + 2].toInt() and 0x7F) shl 7) or (b[p + 3].toInt() and 0x7F)

private fun desync(b: ByteArray): ByteArray {
    val o = ByteArrayOutputStream(b.size)
    var i = 0
    while (i < b.size) {
        o.write(b[i].toInt())
        if (b[i] == 0xFF.toByte() && i + 1 < b.size && b[i + 1] == 0.toByte()) i++
        i++
    }
    return o.toByteArray()
}

fun readId3(src: ByteSource): Id3Tag? {
    val h = src.read(0, 10)
    if (h.size < 10 || String(h, 0, 3, Charsets.ISO_8859_1) != "ID3") return null
    val ver = h[3].toInt()
    if (ver != 3 && ver != 4) return null
    val flags = h[5].toInt()
    val size = minOf(syncsafe(h, 6), 48 shl 20)
    var body = src.read(10, size)
    val tagUnsync = flags and 0x80 != 0
    if (tagUnsync && ver == 3) body = desync(body)
    var p = 0
    if (flags and 0x40 != 0 && body.size >= 4) p = if (ver == 3) 4 + be32(body, 0).toInt() else syncsafe(body, 0)
    return Id3Tag(ver, id3Frames(body, p, body.size, ver, tagUnsync && ver == 4))
}

fun id3Frames(b: ByteArray, from: Int, to: Int, ver: Int, allUnsync: Boolean = false): List<Id3Frame> {
    val out = ArrayList<Id3Frame>()
    var p = from
    while (p + 10 <= to && b[p] != 0.toByte()) {
        val id = String(b, p, 4, Charsets.ISO_8859_1)
        val size = if (ver == 4) syncsafe(b, p + 4) else be32(b, p + 4).toInt()
        val f2 = b[p + 9].toInt()
        val start = p + 10
        val end = start + size
        if (size < 0 || end > to) break
        var data = b.copyOfRange(start, end)
        var skip = false
        var unsync = allUnsync
        if (ver == 4) {
            if (f2 and 0x0C != 0) skip = true // compression / encryption
            if (f2 and 0x01 != 0 && data.size >= 4) data = data.copyOfRange(4, data.size)
            if (f2 and 0x02 != 0) unsync = true
        } else if (f2 and 0xC0 != 0) skip = true
        if (!skip) out += Id3Frame(id, if (unsync && ver == 4) desync(data) else data)
        p = end
    }
    return out
}

private fun decodeId3(enc: Int, b: ByteArray, from: Int, to: Int): String {
    if (to <= from) return ""
    return when (enc) {
        1 -> {
            val bom = to - from >= 2 && b[from] == 0xFF.toByte() && b[from + 1] == 0xFE.toByte()
            val be = to - from >= 2 && b[from] == 0xFE.toByte() && b[from + 1] == 0xFF.toByte()
            val s = if (bom || be) from + 2 else from
            String(b, s, to - s, if (be) Charsets.UTF_16BE else Charsets.UTF_16LE)
        }
        2 -> String(b, from, to - from, Charsets.UTF_16BE)
        3 -> String(b, from, to - from, Charsets.UTF_8)
        else -> {
            val bytes = b.copyOfRange(from, to)
            if (bytes.any { it < 0 }) decodeText(bytes) else String(bytes, Charsets.ISO_8859_1)
        }
    }
}

/** Index of the terminator for [enc] at or after [from], or [to] if none; [next] is where the following text starts. */
private fun terminator(enc: Int, b: ByteArray, from: Int, to: Int): Pair<Int, Int> {
    if (enc == 1 || enc == 2) {
        var i = from
        while (i + 1 < to) { if (b[i] == 0.toByte() && b[i + 1] == 0.toByte()) return i to i + 2; i += 2 }
    } else for (i in from until to) if (b[i] == 0.toByte()) return i to i + 1
    return to to to
}

/** First value of a text frame. */
fun id3Text(f: Id3Frame): String? {
    if (f.data.size < 2) return null
    val enc = f.data[0].toInt()
    val (e, _) = terminator(enc, f.data, 1, f.data.size)
    return decodeId3(enc, f.data, 1, e).trim().takeIf { it.isNotEmpty() }
}

/** TXXX → (description, value). */
private fun txxx(f: Id3Frame): Pair<String, String>? {
    if (f.data.size < 2) return null
    val enc = f.data[0].toInt()
    val (e, n) = terminator(enc, f.data, 1, f.data.size)
    val desc = decodeId3(enc, f.data, 1, e)
    return desc to decodeId3(enc, f.data, n, f.data.size).trimEnd('\u0000')
}

private fun comm(f: Id3Frame): String? {
    if (f.data.size < 5) return null
    val enc = f.data[0].toInt()
    val (_, n) = terminator(enc, f.data, 4, f.data.size)
    return decodeId3(enc, f.data, n, f.data.size).trim('\u0000', ' ').takeIf { it.isNotEmpty() }
}

fun id3Chapters(tag: Id3Tag): List<RawChapter>? {
    val chaps = tag.frames.filter { it.id == "CHAP" }.mapNotNull { f ->
        val d = f.data
        val z = d.indexOf(0.toByte())
        if (z < 0 || z + 17 > d.size) return@mapNotNull null
        val start = be32(d, z + 1)
        val subs = id3Frames(d, z + 17, d.size, tag.version)
        val title = (subs.firstOrNull { it.id == "TIT2" } ?: subs.firstOrNull { it.id == "TIT3" })?.let(::id3Text) ?: ""
        RawChapter(title, start)
    }.sortedBy { it.startMs }
    if (chaps.isNotEmpty()) return chaps
    for (f in tag.frames) {
        if (f.id != "TXXX") continue
        val (desc, value) = txxx(f) ?: continue
        if (desc.contains("OverDrive MediaMarkers", true)) overDriveMarkers(value)?.let { return it }
    }
    return null
}

private val MARKER = Regex("<Marker>(.*?)</Marker>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
private val M_NAME = Regex("<Name>(.*?)</Name>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
private val M_TIME = Regex("<Time>(.*?)</Time>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))

fun overDriveMarkers(xml: String): List<RawChapter>? {
    val out = MARKER.findAll(xml).mapNotNull { m ->
        val name = M_NAME.find(m.groupValues[1])?.groupValues?.get(1)?.let(::unescapeXml)?.trim() ?: ""
        val time = M_TIME.find(m.groupValues[1])?.groupValues?.get(1)?.let { parseClock(it) } ?: return@mapNotNull null
        RawChapter(name, time)
    }.toList()
    return out.takeIf { it.isNotEmpty() }
}

fun unescapeXml(s: String) = s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&")

fun id3Tags(tag: Id3Tag): TagInfo? {
    val t = HashMap<String, String>()
    val comments = ArrayList<String>()
    for (f in tag.frames) when (f.id) {
        "TXXX" -> txxx(f)?.let { (d, v) ->
            val value = v.trim().takeIf { it.isNotEmpty() } ?: return@let
            when (d.trim().lowercase()) {
                "narrator", "reader", "narrated by", "read by" -> t["narrator"] = value
                "series", "mvnm", "series name" -> t["series"] = value
                "publisher" -> t["publisher"] = value
            }
        }
        "TIT2", "TPE1", "TPE2", "TALB", "TCOM", "TCON", "TPUB", "TSSE", "TYER", "TDRC", "TCOP" -> id3Text(f)?.let { t.putIfAbsent(f.id, it) }
        "COMM", "USLT" -> comm(f)?.let { comments += it }
    }
    return TagInfo(
        title = t["TIT2"], artist = t["TPE1"], albumArtist = t["TPE2"], album = t["TALB"], composer = t["TCOM"],
        narrator = t["narrator"], year = t["TDRC"] ?: t["TYER"], genre = t["TCON"], publisher = t["TPUB"] ?: t["publisher"],
        series = t["series"], comment = comments.maxByOrNull { it.length }, encoder = t["TSSE"], copyright = t["TCOP"],
    ).takeIf { it != TagInfo() }
}
