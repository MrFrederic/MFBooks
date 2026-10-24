package app.mfbooks.library

private val KV = Regex("""^\s*([^:]{2,40}):\s*(.+)$""")
private val UNDERLINE = Regex("""^\s*([=\-])\1{2,}\s*$""")
private val YEAR = Regex("""\b(1[5-9]\d\d|20\d\d)\b""")
private val DESC_HEADERS = setOf("description", "book description", "summary", "plot", "publisher's summary", "publishers summary", "publisher summary")
private val TECH_WORDS = listOf("media information", "codec", "bitrate", "ripper", "rip info", "encod", "technical", "file info")

private val ALIASES = mapOf(
    "title" to "title", "book title" to "title",
    "author" to "author", "written by" to "author", "writer" to "author",
    "read by" to "narrator", "narrator" to "narrator", "narrated by" to "narrator", "reader" to "narrator",
    "copyright" to "year", "year" to "year", "release date" to "year", "audiobook copyright" to "year",
    "publisher" to "publisher", "genre" to "genre", "series" to "series",
    "series index" to "seriesIndex", "book" to "seriesIndex", "part" to "seriesIndex",
    "chapters" to "chapters", "duration" to "duration",
)

fun parseNfo(text: String): NfoData {
    val lines = text.removePrefix("﻿").replace("\r\n", "\n").replace('\r', '\n').split('\n')
    val raw = LinkedHashMap<String, String>()
    val fields = HashMap<String, String>()
    val desc = StringBuilder()
    var section = ""
    var inDesc = false
    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        i++
        if (line.isBlank()) { if (inDesc && desc.isNotEmpty() && !desc.endsWith("\n\n")) desc.append("\n\n"); continue }
        if (UNDERLINE.matches(line)) continue
        if (i < lines.size && UNDERLINE.matches(lines[i]) && !UNDERLINE.matches(line)) {
            section = line.trim().trimEnd(':').lowercase()
            inDesc = section in DESC_HEADERS
            continue
        }
        if (inDesc) {
            if (desc.isNotEmpty() && !desc.endsWith("\n\n") && !desc.endsWith(" ")) desc.append(' ')
            desc.append(line.trim().replace(Regex("\\s+"), " "))
            continue
        }
        val key0 = line.trim().trimEnd(':').lowercase()
        if (line.trimEnd().endsWith(":") && key0 in DESC_HEADERS) { inDesc = true; continue }
        val m = KV.matchEntire(line) ?: continue
        val key = m.groupValues[1].trim()
        val value = m.groupValues[2].trim()
        raw.putIfAbsent(key, value)
        val field = ALIASES[key.lowercase()] ?: continue
        val tech = TECH_WORDS.any { section.contains(it) }
        if (tech && field != "duration" && field != "chapters") continue
        if (key.lowercase() in DESC_HEADERS) { if (desc.isEmpty()) desc.append(value); continue }
        fields.putIfAbsent(field, value)
    }
    val description = desc.toString().trim().takeIf { it.isNotEmpty() }
    return NfoData(
        title = fields["title"], author = fields["author"], narrator = fields["narrator"],
        year = fields["year"]?.let { YEAR.find(it)?.value },
        publisher = fields["publisher"],
        genre = fields["genre"]?.takeUnless { it.equals("audiobook", true) || it.equals("audiobooks", true) },
        series = fields["series"], seriesIndex = fields["seriesIndex"],
        expectedChapters = fields["chapters"]?.let { Regex("\\d+").find(it)?.value?.toIntOrNull() },
        expectedDurationMs = fields["duration"]?.let(::parseDuration),
        description = description, raw = raw,
    )
}

private val UNIT = Regex("""(\d+(?:\.\d+)?)\s*(h|hr|hrs|hours?|m|min|mins|minutes?|s|sec|secs|seconds?)\b""", RegexOption.IGNORE_CASE)

private fun parseDuration(s: String): Long? {
    val units = UNIT.findAll(s).toList()
    if (units.isNotEmpty()) {
        return units.sumOf {
            val n = it.groupValues[1].toDouble()
            val mult = when (it.groupValues[2].lowercase().first()) { 'h' -> 3600.0; 'm' -> 60.0; else -> 1.0 }
            Math.round(n * mult * 1000)
        }
    }
    return Regex("""\d+(:\d+){1,2}(\.\d+)?""").find(s)?.value?.let { parseClock(it) }
}
