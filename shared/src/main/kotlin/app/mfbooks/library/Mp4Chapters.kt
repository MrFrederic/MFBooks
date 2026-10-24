package app.mfbooks.library

class Box(val type: String, val start: Int, val end: Int)

private fun boxes(b: ByteArray, from: Int, to: Int): List<Box> {
    val out = ArrayList<Box>()
    var p = from
    while (p + 8 <= to) {
        var size = be32(b, p)
        val type = String(b, p + 4, 4, Charsets.ISO_8859_1)
        var hdr = 8
        if (size == 1L) {
            if (p + 16 > to) break
            size = be64(b, p + 8); hdr = 16
        } else if (size == 0L) size = (to - p).toLong()
        if (size < hdr) break
        val end = minOf(p + size, to.toLong()).toInt()
        out += Box(type, p + hdr, end)
        p = end
    }
    return out
}

private fun kids(b: ByteArray, box: Box) = boxes(b, box.start, box.end)
private fun Box.child(b: ByteArray, vararg path: String): Box? {
    var cur: Box = this
    for (n in path) cur = kids(b, cur).firstOrNull { it.type == n } ?: return null
    return cur
}

/** Reads the whole `moov` box (never the media data). */
fun readMoov(src: ByteSource): ByteArray? {
    var off = 0L
    while (off + 8 <= src.size) {
        val h = src.read(off, 16)
        if (h.size < 8) return null
        var size = be32(h, 0)
        var hdr = 8
        if (size == 1L && h.size >= 16) { size = be64(h, 8); hdr = 16 } else if (size == 0L) size = src.size - off
        if (size < hdr) return null
        if (String(h, 4, 4, Charsets.ISO_8859_1) == "moov") {
            val len = size - hdr
            return if (len in 8..(256L shl 20)) src.read(off + hdr, len.toInt()) else null
        }
        off += size
    }
    return null
}

fun isMp4(head: ByteArray) = head.size >= 8 && String(head, 4, 4, Charsets.ISO_8859_1).let { it == "ftyp" || it == "moov" || it == "mdat" || it == "free" }

fun mp4Chapters(src: ByteSource): List<RawChapter>? = readMoov(src)?.let { mp4Chapters(src, it) }

fun mp4Chapters(src: ByteSource, moov: ByteArray): List<RawChapter>? {
    val root = Box("moov", 0, moov.size)
    root.child(moov, "udta", "chpl")?.let { chpl ->
        var p = chpl.start
        val version = moov[p].toInt()
        p += 4
        if (version != 0) p += 4
        if (p < chpl.end) {
            val n = moov[p++].toInt() and 0xFF
            val out = ArrayList<RawChapter>()
            repeat(n) {
                if (p + 9 > chpl.end) return@repeat
                val start = be64(moov, p) / 10_000
                val len = moov[p + 8].toInt() and 0xFF
                p += 9
                val l = minOf(len, chpl.end - p)
                out += RawChapter(String(moov, p, l, Charsets.UTF_8), start)
                p += l
            }
            if (out.isNotEmpty()) return out
        }
    }
    return qtTextChapters(src, moov, root)
}

private fun qtTextChapters(src: ByteSource, m: ByteArray, root: Box): List<RawChapter>? {
    val traks = kids(m, root).filter { it.type == "trak" }
    val chapId = traks.firstNotNullOfOrNull { t -> t.child(m, "tref", "chap")?.takeIf { it.end - it.start >= 4 }?.let { be32(m, it.start) } } ?: return null
    val trak = traks.firstOrNull { t ->
        t.child(m, "tkhd")?.let { be32(m, it.start + if (m[it.start].toInt() == 1) 20 else 12) } == chapId
    } ?: return null
    val mdhd = trak.child(m, "mdia", "mdhd") ?: return null
    val timescale = be32(m, mdhd.start + if (m[mdhd.start].toInt() == 1) 20 else 12).takeIf { it > 0 } ?: return null
    val stbl = trak.child(m, "mdia", "minf", "stbl") ?: return null
    val sb = kids(m, stbl).associateBy { it.type }
    val stts = sb["stts"] ?: return null
    val stsz = sb["stsz"] ?: return null
    val stsc = sb["stsc"] ?: return null
    val co = sb["stco"] ?: sb["co64"] ?: return null

    val count = minOf(be32(m, stsz.start + 8), 100_000L).toInt()
    val fixed = be32(m, stsz.start + 4).toInt()
    val sizes = IntArray(count) { if (fixed != 0) fixed else be32(m, stsz.start + 12 + 4 * it).toInt() }

    val nChunks = be32(m, co.start + 4).toInt()
    val wide = co.type == "co64"
    fun chunkOff(i: Int) = if (wide) be64(m, co.start + 8 + 8 * i) else be32(m, co.start + 8 + 4 * i)

    val nsc = be32(m, stsc.start + 4).toInt()
    val firsts = IntArray(nsc) { be32(m, stsc.start + 8 + 12 * it).toInt() }
    val perChunk = IntArray(nsc) { be32(m, stsc.start + 12 + 12 * it).toInt() }

    val offsets = LongArray(count)
    var s = 0
    for (c in 1..nChunks) {
        if (s >= count) break
        var e = nsc - 1
        while (e > 0 && firsts[e] > c) e--
        var off = chunkOff(c - 1)
        repeat(perChunk[e]) {
            if (s < count) { offsets[s] = off; off += sizes[s]; s++ }
        }
    }

    val times = LongArray(count)
    var idx = 0
    var t = 0L
    val nts = be32(m, stts.start + 4).toInt()
    for (i in 0 until nts) {
        val c = be32(m, stts.start + 8 + 8 * i).toInt()
        val d = be32(m, stts.start + 12 + 8 * i)
        repeat(c) { if (idx < count) { times[idx++] = t * 1000 / timescale; t += d } }
    }

    val out = ArrayList<RawChapter>()
    for (i in 0 until minOf(s, idx)) {
        if (sizes[i] < 2) continue
        val data = src.read(offsets[i], sizes[i])
        if (data.size < 2) continue
        val len = minOf(be16(data, 0), data.size - 2)
        val body = data.copyOfRange(2, 2 + len)
        val title = if (body.size >= 2 && body[0] == 0xFE.toByte() && body[1] == 0xFF.toByte()) String(body, 2, body.size - 2, Charsets.UTF_16BE)
        else if (body.size >= 2 && body[0] == 0xFF.toByte() && body[1] == 0xFE.toByte()) String(body, 2, body.size - 2, Charsets.UTF_16LE)
        else String(body, Charsets.UTF_8)
        out += RawChapter(title.trim('\u0000', ' '), times[i])
    }
    return out.takeIf { it.isNotEmpty() }
}

fun mp4Tags(src: ByteSource): TagInfo? = readMoov(src)?.let(::mp4Tags)

fun mp4Tags(m: ByteArray): TagInfo? {
    val meta = Box("moov", 0, m.size).child(m, "udta", "meta") ?: return null
    // ISO `meta` is a full box (4 version bytes); the QuickTime variant starts with `hdlr` directly.
    val from = meta.start + if (meta.end - meta.start >= 8 && String(m, meta.start + 4, 4, Charsets.ISO_8859_1) == "hdlr") 0 else 4
    val ilst = boxes(m, from, meta.end).firstOrNull { it.type == "ilst" } ?: return null
    val v = HashMap<String, String>()
    val descs = ArrayList<String>()
    fun dataOf(b: Box) = kids(m, b).firstOrNull { it.type == "data" }?.takeIf { it.end - it.start > 8 }
        ?.let { String(m, it.start + 8, it.end - it.start - 8, Charsets.UTF_8).trim() }?.takeIf { it.isNotBlank() }
    for (item in kids(m, ilst)) {
        if (item.type == "----") {
            val name = kids(m, item).firstOrNull { it.type == "name" }?.let { String(m, it.start + 4, it.end - it.start - 4, Charsets.UTF_8).lowercase() }
            val value = dataOf(item) ?: continue
            when (name) {
                "narrator", "reader" -> v["narrator"] = value
                "publisher" -> v["publisher"] = value
                "series", "mvnm" -> v["series"] = value
            }
            continue
        }
        val value = dataOf(item) ?: continue
        when (item.type) {
            "©nrt" -> v["narrator"] = value
            "©wrt" -> v["composer"] = value
            "©des", "desc", "ldes" -> descs += value
            "©too" -> v["encoder"] = value
            "cprt", "©cpy" -> v["copyright"] = value
            "©cmt" -> v["comment"] = value
            "©nam" -> v["title"] = value
            "©ART" -> v["artist"] = value
            "aART" -> v["albumArtist"] = value
            "©alb" -> v["album"] = value
            "©day" -> v["year"] = value
            "©gen" -> v["genre"] = value
            "©pub" -> v["publisher"] = value
            "©grp", "©mvn" -> v.putIfAbsent("series", value)
        }
    }
    return TagInfo(
        title = v["title"], artist = v["artist"], albumArtist = v["albumArtist"], album = v["album"],
        composer = v["composer"], narrator = v["narrator"], year = v["year"], genre = v["genre"],
        publisher = v["publisher"], series = v["series"], comment = v["comment"],
        description = descs.maxByOrNull { it.length }, encoder = v["encoder"], copyright = v["copyright"],
    ).takeIf { it != TagInfo() }
}
