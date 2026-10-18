package app.mfbooks.library

private val GENERIC = Regex("""^\s*(\d+|chapter\s*\d+|track\s*\d+|part\s*\d+)\s*$""", RegexOption.IGNORE_CASE)
private val MP4_EXTS = setOf("m4b", "m4a", "mp4", "m4v", "3gp")
private val YEAR = Regex("""\b(1[5-9]\d\d|20\d\d)\b""")
private const val MATCH_MS = 2000L

private fun String?.clean() = this?.trim()?.takeIf { it.isNotEmpty() }
private fun isGeneric(t: String) = t.isBlank() || GENERIC.matches(t)

/** Merge rules of §4.3.1 (fields + chapter merge + cue binding + sanity). */
fun mergeBook(input: MergeInput): MergedBook {
    val files = input.files
    val nfo = input.nfo
    val src = LinkedHashMap<String, String>()

    fun pick(field: String, vararg cands: Pair<String, String?>): String? {
        for ((source, v) in cands) v.clean()?.let { src[field] = source; return it }
        return null
    }
    fun tag(sel: (TagInfo) -> String?) = files.firstNotNullOfOrNull { sel(it.tags).clean() }
    fun yearOf(s: String?) = s.clean()?.let { YEAR.find(it)?.value ?: it }
    fun genreOf(s: String?) = s.clean()?.takeUnless { it.equals("audiobook", true) || it.equals("audiobooks", true) }

    val cueBound = bindCues(input, src)
    val cuePerformer = input.cues.firstNotNullOfOrNull { it.second.performer.clean() }

    val folder = folderSeries(input.folderName, input.parentFolderName)
    val tagSeries = tag { it.series }?.let(::parseSeries)
    val album = tag { it.album }?.let(::cleanTitle)
    // A tag title shared by all files (one-file book, or "Book, part 1/2" with the same title).
    val tagTitle = files.map { it.tags.title.clean() }.distinct().singleOrNull()?.let(::cleanTitle)
    // Album is the series ("Ведьмак") and the title tag is the book: prefer the title when it fits the folder better.
    val albumIsSeries = album != null && tagTitle != null && !same(album, tagTitle) &&
        (same(album, folder.series) || same(album, tagSeries?.first) || (fits(tagTitle, folder.title) && !fits(album, folder.title)))
    val copyright = tag { it.copyright }?.let(::copyrightInfo)

    val author = pick(
        "author", "nfo" to nfo?.author, "tag:albumArtist" to tag { it.albumArtist }, "tag:artist" to tag { it.artist },
        "cue" to cuePerformer, "parentFolder" to input.parentFolderName?.takeIf { folder.series == null },
    )
    val folderTitle = author?.let { a -> folder.title.takeIf { it.startsWith("$a - ", true) }?.substring(a.length + 3) } ?: folder.title
    val title = pick(
        "title", "nfo" to nfo?.title, "tag:title" to tagTitle?.takeIf { albumIsSeries }, "tag:album" to album,
        "tag:title" to tagTitle?.takeIf { files.size == 1 },
    ) ?: (if (folder.index != null || folderTitle != input.folderName) folderTitle else input.folderName).also { src["title"] = "folder" }
    val narrator = pick(
        "narrator", "nfo" to nfo?.narrator, "tag:narrator" to tag { it.narrator },
        "tag:composer" to files.firstNotNullOfOrNull { f ->
            val enc = f.tags.encoder.orEmpty().lowercase()
            if (extOf(f.name) in MP4_EXTS || "inaudible" in enc || "audible" in enc) f.tags.composer.clean() else null
        },
    )
    val year = pick("year", "nfo" to yearOf(nfo?.year), "tag" to yearOf(tag { it.year }), "tag:copyright" to copyright?.second)
    val publisher = pick(
        "publisher", "nfo" to nfo?.publisher, "tag" to tag { it.publisher },
        "tag:copyright" to copyright?.first?.takeUnless { same(it, author) },
    )
    val genre = pick("genre", "nfo" to genreOf(nfo?.genre), "tag" to genreOf(tag { it.genre }))
    val series = pick(
        "series", "nfo" to nfo?.series, "tag" to tagSeries?.first, "folder" to folder.series,
        "tag:album" to album?.takeIf { albumIsSeries },
    )
    val seriesIndex = if (series == null) null else pick(
        "seriesIndex", "nfo" to nfo?.seriesIndex?.takeIf { src["series"] == "nfo" },
        "tag" to tagSeries?.second?.takeIf { src["series"] == "tag" }, "folder" to folder.index,
        "nfo" to nfo?.seriesIndex, "tag" to tagSeries?.second,
    )
    val description = pick(
        "description", "info.txt" to input.infoTxt, "nfo" to nfo?.description,
        "tag" to files.flatMap { listOf(it.tags.description, it.tags.comment) }.mapNotNull { it.clean()?.let(::htmlToText) }.maxByOrNull { it.length },
    )

    val perFile = files.mapIndexed { i, f ->
        val libby = input.libbyChapters.entries.firstOrNull { it.key.equals(f.name, true) }?.value
        mergeChapters(f, input.useTagTitles, cueBound[i]?.let(::cueChapters), libby)
    }
    val used = perFile.map { it.second }.distinct()
    src["chapters"] = used.joinToString(",")
    if (files.size > 1) files.forEachIndexed { i, f -> src["chapters:${f.name}"] = perFile[i].second }
    val expected = nfo?.expectedChapters
    val total = perFile.sumOf { it.first.size }
    if (expected != null && expected != total) src["nfo.chapters"] = "expected $expected, found $total"

    return MergedBook(title, author, narrator, year, publisher, genre, series, seriesIndex, description, perFile.map { it.first }, src)
}

private fun cueChapters(tracks: List<CueTrack>) =
    tracks.mapIndexed { i, t -> RawChapter(t.title ?: "Chapter ${i + 1}", t.startMs) }

/** Strictly increasing, non-negative starts that lie inside the file. */
private fun sanitize(list: List<RawChapter>?, durationMs: Long, margin: Long = 0): List<RawChapter> {
    val out = ArrayList<RawChapter>()
    for (c in list.orEmpty()) {
        if (c.startMs < 0 || (durationMs > 0 && c.startMs >= durationMs - margin)) continue
        if (out.isNotEmpty() && c.startMs <= out.last().startMs) continue
        out += c
    }
    return out
}

private fun aligned(e: List<RawChapter>, c: List<RawChapter>): Boolean {
    if (e.isEmpty() || c.isEmpty() || Math.abs(e.size - c.size) > 1) return false
    val matches = e.count { x -> c.any { Math.abs(it.startMs - x.startMs) <= MATCH_MS } }
    return matches >= 0.8 * maxOf(e.size, c.size)
}

private fun mergeChapters(f: FileInput, useTagTitles: Boolean, cueRaw: List<RawChapter>?, libbyRaw: List<RawChapter>?): Pair<List<RawChapter>, String> {
    val d = f.durationMs
    val emb = sanitize(f.embeddedChapters, d)
    val cue = sanitize(cueRaw, d, 1000) // tracks starting in the last second are bogus
    val libby = sanitize(libbyRaw, d)
    for ((name, other) in listOf("cue" to cue, "libby" to libby)) {
        if (!aligned(emb, other)) continue
        val merged = emb.map { e ->
            val o = other.minByOrNull { Math.abs(it.startMs - e.startMs) }?.takeIf { Math.abs(it.startMs - e.startMs) <= MATCH_MS }
            val title = when {
                o == null -> e.title
                isGeneric(e.title) && !isGeneric(o.title) -> o.title
                !isGeneric(e.title) -> e.title
                else -> o.title.ifBlank { e.title }
            }
            RawChapter(title, e.startMs)
        }
        return merged to "embedded-timing+$name-titles"
    }
    val best = listOf("embedded" to emb, "cue" to cue, "libby" to libby).maxByOrNull { it.second.size }!! // first max wins ties
    if (best.second.isNotEmpty()) return best.second to best.first
    val cached = sanitize(f.cachedChapters, d)
    if (cached.isNotEmpty()) return cached to "cached"
    val title = f.tags.title.clean()?.takeIf { useTagTitles } ?: baseName(f.name)
    return listOf(RawChapter(title, 0)) to "file"
}

/** file index → cue tracks, per the binding rules of §4.3.1. */
private fun bindCues(input: MergeInput, src: MutableMap<String, String>): Map<Int, List<CueTrack>> {
    val names = input.files.map { it.name }
    val bound = HashMap<Int, List<CueTrack>>()
    for ((cueName, sheet) in input.cues) {
        if (sheet.files.isEmpty()) continue
        val cueBase = baseName(cueName)
        // A cue named after one audio file ("Book.m4b.cue" / "Book.cue") applies to that file only.
        val named = if (sheet.files.size == 1) {
            names.indexOfFirst { it.equals(cueBase, true) }.takeIf { it >= 0 }
                ?: names.indices.filter { baseName(names[it]).equals(cueBase, true) }.singleOrNull()
        } else null
        if (named != null) { bound.putIfAbsent(named, sheet.files[0].tracks); continue }
        for (cf in sheet.files) {
            val idx = bindFile(cf.name, names)
            if (idx == null) src["cue:$cueName"] = "ignored: no matching audio file for \"${cf.name}\""
            else bound.putIfAbsent(idx, cf.tracks)
        }
    }
    return bound
}

private fun bindFile(cueFile: String, names: List<String>): Int? {
    names.indexOf(cueFile).takeIf { it >= 0 }?.let { return it }
    val base = baseName(cueFile)
    names.indexOfFirst { baseName(it).equals(base, true) }.takeIf { it >= 0 }?.let { return it }
    if (names.size == 1) return 0
    val a = base.lowercase()
    var best = -1
    var bestLen = 0
    names.forEachIndexed { i, n ->
        val b = baseName(n).lowercase()
        val l = longestCommonSubstring(a, b)
        if (l > bestLen && l >= 0.6 * minOf(a.length, b.length)) { best = i; bestLen = l }
    }
    return best.takeIf { it >= 0 }
}

private fun longestCommonSubstring(a: String, b: String): Int {
    var best = 0
    var prev = IntArray(b.length + 1)
    for (i in 1..a.length) {
        val cur = IntArray(b.length + 1)
        for (j in 1..b.length) if (a[i - 1] == b[j - 1]) { cur[j] = prev[j - 1] + 1; best = maxOf(best, cur[j]) }
        prev = cur
    }
    return best
}

private val SERIES_WORD = Regex("""^\s*(цикл|серия|сага|series|saga|cycle|reihe)(?=[\s.:\-–—]|$)[\s.:\-–—]*""", RegexOption.IGNORE_CASE)
private val NUMBERED = Regex("""^\s*(?:(?:book|vol\.?|volume|part|книга|том|часть)\s*)?(\d{1,3}(?:\.\d)?)\s*[.\-–—_)]\s*(.+)$""", RegexOption.IGNORE_CASE)
private val SERIES_TAG = Regex("""^(.*?)[\s,:]*(?:#|(?<!\p{L})(?:book|vol\.?|volume|part|книга|том|часть)\s*)(\d+(?:\.\d+)?)\s*\)?$""", RegexOption.IGNORE_CASE)
private val UNABRIDGED = Regex("""\s*[(\[](un)?abridged[)\]]\s*$""", RegexOption.IGNORE_CASE)

internal class FolderSeries(val series: String?, val index: String?, val title: String)

/** "Цикл  Ведьмак/1. Последнее желание" → series "Ведьмак", index "1", title "Последнее желание". */
internal fun folderSeries(folder: String, parent: String?): FolderSeries {
    val num = NUMBERED.matchEntire(folder)
    val series = parent?.let { p -> SERIES_WORD.find(p)?.let { p.substring(it.range.last + 1).trim() }?.takeIf { it.isNotEmpty() } }
    // ponytail: a numbered folder under a plain parent ("Author/1. Book") is not enough to call the parent a series
    return FolderSeries(series, if (series != null) num?.groupValues?.get(1) else null, num?.groupValues?.get(2)?.trim() ?: folder)
}

/** "Discworld #4; Discworld: Death #1" → ("Discworld", "4"); "Foo, Book 2" → ("Foo", "2"). */
internal fun parseSeries(s: String): Pair<String, String?>? {
    val first = s.split(';').first().trim().takeIf { it.isNotEmpty() } ?: return null
    val m = SERIES_TAG.matchEntire(first)?.takeIf { it.groupValues[1].isNotBlank() }
    return if (m != null) m.groupValues[1].trim().trimEnd(',', ':', '-', '(').trim() to m.groupValues[2] else first to null
}

internal fun cleanTitle(s: String) = s.replace(UNABRIDGED, "").trim().ifEmpty { s }

private fun norm(s: String?) = s.orEmpty().lowercase().filter { it.isLetterOrDigit() }
private fun same(a: String?, b: String?) = norm(a).isNotEmpty() && norm(a) == norm(b)
private fun fits(a: String, b: String): Boolean {
    val x = norm(a); val y = norm(b)
    return x.isNotEmpty() && y.isNotEmpty() && (x == y || (minOf(x.length, y.length) >= 4 && (x in y || y in x)))
}

/** ("Audible, Inc.", "2019") from "©2011 by Andy Weir;(P)2019 Audible, Inc."; ("Transworld Digital", null). */
internal fun copyrightInfo(s: String): Pair<String?, String?>? {
    val parts = htmlToText(s).split(';').map { it.trim() }.filter { it.isNotEmpty() }
    val p = parts.firstOrNull { Regex("""\(P\)|℗""", RegexOption.IGNORE_CASE).containsMatchIn(it) }
    val seg = p ?: parts.firstOrNull() ?: return null
    val year = YEAR.findAll(seg).lastOrNull()?.value
    var pub = seg.replace(Regex("""\(P\)|℗|\(C\)|©|copyright""", RegexOption.IGNORE_CASE), " ").replace(YEAR, " ")
        .replace(Regex("""^[\s,;:\-]+|[\s,;:\-]+$"""), "")
    if (Regex("""^by\s""", RegexOption.IGNORE_CASE).containsMatchIn(pub) || pub.none { it.isLetter() }) pub = ""
    return pub.ifBlank { null } to year
}

private val ENTITIES = mapOf(
    "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ", "mdash" to "—", "ndash" to "–",
    "hellip" to "…", "rsquo" to "’", "lsquo" to "‘", "ldquo" to "“", "rdquo" to "”", "laquo" to "«", "raquo" to "»", "copy" to "©",
)

/** Audible/Penguin descriptions come as HTML: keep paragraphs, drop tags, decode entities. Plain text passes through. */
internal fun htmlToText(s: String): String {
    if (!Regex("""<[a-zA-Z/][^>]*>|&#?\w+;""").containsMatchIn(s)) return s.trim()
    var t = s.replace(Regex("""(?i)<br\s*/?>"""), "\n").replace(Regex("""(?i)</(p|div|h\d|li|ul|ol)>"""), "\n\n")
        .replace(Regex("""(?i)<li[^>]*>"""), "• ").replace(Regex("""<[^>]*>"""), "")
    t = Regex("""&(#x?[0-9a-fA-F]+|\w+);""").replace(t) { m ->
        val e = m.groupValues[1]
        when {
            e.startsWith("#x", true) -> e.substring(2).toIntOrNull(16)?.let { String(Character.toChars(it)) }
            e.startsWith("#") -> e.substring(1).toIntOrNull()?.let { String(Character.toChars(it)) }
            else -> ENTITIES[e]
        } ?: m.value
    }
    return t.replace(Regex("""[ \t\u00A0]+"""), " ").replace(Regex(""" *\n *"""), "\n").replace(Regex("""\n{3,}"""), "\n\n").trim()
}
