package app.mfbooks.ui.text

import app.mfbooks.text.BookTextDoc
import app.mfbooks.text.RangeKind
import app.mfbooks.text.SyncRange
import app.mfbooks.text.blockStarts
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/** A stretch of text ([from] until [to], in chars) and how much of it has word timing ([word]) or is missing from the audio ([none]). */
class ChapterStat(val title: String, val from: Int, val to: Int, val word: Float, val none: Float) {
    val length get() = to - from
}

fun chapterStats(doc: BookTextDoc, ranges: List<SyncRange>): List<ChapterStat> {
    val starts = doc.blockStarts()
    val marks = doc.toc.filter { it.block in doc.blocks.indices }.sortedBy { it.block }
    val spans = buildList {
        val first = marks.firstOrNull()?.block ?: doc.blocks.size
        if (first > 0) add(Triple(if (marks.isEmpty()) doc.title ?: "Text" else "Start", 0, starts[first]))
        marks.forEachIndexed { i, m -> add(Triple(m.title, starts[m.block], starts[marks.getOrNull(i + 1)?.block ?: doc.blocks.size])) }
    }.filter { it.third > it.second }
    fun share(kind: String, from: Int, to: Int) =
        ranges.filter { it.kind == kind }.sumOf { (min(to, it.toChar) - max(from, it.fromChar)).coerceAtLeast(0) }.toFloat() / (to - from)
    return spans.map { (title, from, to) -> ChapterStat(title, from, to, share(RangeKind.WORD, from, to), share(RangeKind.NONE, from, to)) }
}

/** Joins neighbours so a book with hundreds of TOC entries still fits the bar map. */
fun mergeBars(stats: List<ChapterStat>, maxBars: Int = 60): List<ChapterStat> {
    if (stats.size <= maxBars) return stats
    return stats.chunked(ceil(stats.size.toDouble() / maxBars).toInt()).map { c ->
        val total = c.sumOf { it.length }.toFloat()
        ChapterStat(c.first().title, c.first().from, c.last().to, c.sumOf { (it.word * it.length).toDouble() }.toFloat() / total, c.sumOf { (it.none * it.length).toDouble() }.toFloat() / total)
    }
}

private fun words(s: String) = s.lowercase().replace('ё', 'е').split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }

/** False only when both sides name a title or author and they clearly disagree. */
fun sameBook(title1: String?, author1: String?, title2: String?, author2: String?): Boolean {
    val titles = title1 == null || title2 == null || run {
        val a = words(title1).joinToString(" "); val b = words(title2).joinToString(" ")
        a.contains(b) || b.contains(a)
    }
    val authors = author1 == null || author2 == null || words(author1).any { it.length >= 3 && it in words(author2) }
    return titles && authors
}

val MATCHABLE_LANGS = listOf("ru", "en")

fun langName(code: String): String = when (code) {
    "ru" -> "Russian"
    "en" -> "English"
    else -> Locale.forLanguageTag(code).getDisplayLanguage(Locale.ENGLISH).ifBlank { code }
}

fun megabytes(bytes: Long): String = "${Math.round(bytes / 1_000_000.0)} MB"
