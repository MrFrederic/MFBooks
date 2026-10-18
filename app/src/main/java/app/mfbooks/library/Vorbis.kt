package app.mfbooks.library

import java.io.ByteArrayOutputStream

/** Vorbis comments as (KEY upper-case, value) pairs from an Ogg (Vorbis/Opus) or FLAC file; null if not those. */
internal fun readVorbisComments(src: ByteSource, head: ByteArray): List<Pair<String, String>>? {
    val text = String(head, 0, minOf(4, head.size), Charsets.ISO_8859_1)
    return when (text) {
        "OggS" -> oggCommentPacket(src)?.let { parseComments(it) }
        "fLaC" -> flacComment(src)?.let { parseComments(it, 0) }
        else -> null
    }
}

private fun oggCommentPacket(src: ByteSource): ByteArray? {
    var off = 0L
    var packetNo = 0
    val cur = ByteArrayOutputStream()
    while (off + 27 <= src.size && off < (16 shl 20)) {
        val h = src.read(off, 27)
        if (h.size < 27 || String(h, 0, 4, Charsets.ISO_8859_1) != "OggS") return null
        val nseg = h[26].toInt() and 0xFF
        val lacing = src.read(off + 27, nseg)
        var pos = off + 27 + nseg
        for (l in lacing) {
            val len = l.toInt() and 0xFF
            if (packetNo == 1) cur.write(src.read(pos, len))
            pos += len
            if (len < 255) {
                if (packetNo == 1) return cur.toByteArray()
                packetNo++
            }
        }
        off = pos
    }
    return null
}

private fun flacComment(src: ByteSource): ByteArray? {
    var off = 4L
    while (off + 4 <= src.size) {
        val h = src.read(off, 4)
        val last = h[0].toInt() and 0x80 != 0
        val type = h[0].toInt() and 0x7F
        val len = ((h[1].toInt() and 0xFF) shl 16) or ((h[2].toInt() and 0xFF) shl 8) or (h[3].toInt() and 0xFF)
        if (type == 4) return src.read(off + 4, len)
        if (last) return null
        off += 4 + len
    }
    return null
}

/** [prefix] bytes to skip: auto-detected for Ogg packets ("OpusTags" / 0x03 "vorbis"). */
private fun parseComments(b: ByteArray, prefix: Int = -1): List<Pair<String, String>>? {
    var p = when {
        prefix >= 0 -> prefix
        b.size > 8 && String(b, 0, 8, Charsets.ISO_8859_1) == "OpusTags" -> 8
        b.size > 7 && b[0] == 3.toByte() && String(b, 1, 6, Charsets.ISO_8859_1) == "vorbis" -> 7
        else -> return null
    }
    if (p + 4 > b.size) return null
    p += 4 + le32(b, p).toInt()
    if (p < 0 || p + 4 > b.size) return null
    val n = le32(b, p).toInt()
    p += 4
    val out = ArrayList<Pair<String, String>>()
    repeat(n) {
        if (p + 4 > b.size) return@repeat
        val len = le32(b, p).toInt()
        p += 4
        if (len < 0 || p + len > b.size) return@repeat
        val kv = String(b, p, len, Charsets.UTF_8)
        p += len
        val eq = kv.indexOf('=')
        if (eq > 0) out += kv.substring(0, eq).uppercase() to kv.substring(eq + 1)
    }
    return out
}

private val CH_KEY = Regex("CHAPTER(\\d+)(NAME)?")

internal fun vorbisChapters(c: List<Pair<String, String>>): List<RawChapter>? {
    val times = sortedMapOf<Int, Long>()
    val names = HashMap<Int, String>()
    for ((k, v) in c) {
        val m = CH_KEY.matchEntire(k) ?: continue
        val n = m.groupValues[1].toInt()
        if (m.groupValues[2].isEmpty()) parseClock(v)?.let { times[n] = it } else names[n] = v
    }
    val out = times.map { (n, t) -> RawChapter(names[n] ?: "", t) }.sortedBy { it.startMs }
    return out.takeIf { it.isNotEmpty() }
}

internal fun vorbisTags(c: List<Pair<String, String>>): TagInfo? {
    fun v(vararg keys: String) = keys.firstNotNullOfOrNull { k -> c.firstOrNull { it.first == k }?.second?.trim()?.takeIf { it.isNotEmpty() } }
    return TagInfo(
        title = v("TITLE"), artist = v("ARTIST"), albumArtist = v("ALBUMARTIST", "ALBUM ARTIST"), album = v("ALBUM"),
        composer = v("COMPOSER"), narrator = v("NARRATOR", "READER"), year = v("DATE", "YEAR"), genre = v("GENRE"),
        publisher = v("PUBLISHER", "ORGANIZATION"), series = v("SERIES"), comment = v("COMMENT"),
        description = v("DESCRIPTION"), encoder = v("ENCODER"), copyright = v("COPYRIGHT"),
    ).takeIf { it != TagInfo() }
}
