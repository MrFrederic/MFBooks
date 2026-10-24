package app.mfbooks.library

private val CUE_LINE = Regex("""^\s*(\w+)\s*(.*?)\s*$""")

private fun unquote(s: String): String {
    val t = s.trim()
    if (t.startsWith("\"")) {
        val end = t.indexOf('"', 1)
        return if (end > 0) t.substring(1, end) else t.substring(1)
    }
    return t
}

private class RawTrack(var title: String? = null, var performer: String? = null, var time: List<Long>? = null)

fun parseCue(text: String): CueSheet {
    var sheetTitle: String? = null
    var sheetPerformer: String? = null
    val files = ArrayList<Pair<String, MutableList<RawTrack>>>()
    var track: RawTrack? = null
    for (line in text.removePrefix("﻿").lineSequence()) {
        val m = CUE_LINE.matchEntire(line) ?: continue
        val arg = m.groupValues[2]
        when (m.groupValues[1].uppercase()) {
            "FILE" -> {
                // `FILE "name" WAVE` or unquoted `FILE name.wav WAVE`
                val name = if (arg.trim().startsWith("\"")) unquote(arg) else arg.trim().substringBeforeLast(' ', arg.trim())
                files += name to ArrayList()
                track = null
            }
            "TRACK" -> {
                if (files.isEmpty()) files += "" to ArrayList()
                track = RawTrack().also { files.last().second += it }
            }
            "TITLE" -> if (track != null) track.title = unquote(arg) else sheetTitle = unquote(arg)
            "PERFORMER" -> if (track != null) track.performer = unquote(arg) else sheetPerformer = unquote(arg)
            "INDEX" -> {
                val parts = arg.trim().split(Regex("\\s+"))
                if (track != null && parts.size >= 2 && parts[0].toIntOrNull() == 1) {
                    val f = parts[1].split(':').map { it.trim().toLongOrNull() }
                    if (f.size in 2..3 && null !in f) track.time = f.map { it!! }
                }
            }
        }
    }
    // One unit decision per sheet: any last field >= 75 means hundredths, else CD frames.
    val hundredths = files.any { (_, ts) -> ts.any { t -> t.time?.takeIf { it.size == 3 }?.let { it[2] >= 75 } == true } }
    fun ms(t: List<Long>): Long {
        val frac = if (t.size == 3) t[2] * 1000 / (if (hundredths) 100 else 75) else 0
        return (t[0] * 60 + t[1]) * 1000 + frac
    }
    return CueSheet(sheetTitle, sheetPerformer, files.map { (name, ts) ->
        CueFile(name, ts.mapNotNull { t -> t.time?.let { CueTrack(t.title?.takeIf(String::isNotBlank), t.performer, ms(it)) } })
    })
}
