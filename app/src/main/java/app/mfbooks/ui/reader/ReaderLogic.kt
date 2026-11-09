package app.mfbooks.ui.reader

/** Where the current line sits in the viewport while following. */
internal const val FOLLOW_ANCHOR = 0.33f

// Ranges are packed into a Long so the per-frame path allocates nothing.
internal fun packRange(start: Int, end: Int): Long = (start.toLong() shl 32) or (end.toLong() and 0xFFFFFFFFL)
internal fun rangeStart(r: Long): Int = (r ushr 32).toInt()
internal fun rangeEnd(r: Long): Int = r.toInt()

/** Block holding [char]. [starts] is `blockStarts()`: one entry per block plus the total. Empty blocks are skipped over. */
internal fun blockIndexOfChar(starts: IntArray, char: Int): Int {
    var lo = 0; var hi = starts.size - 2
    while (lo < hi) {
        val mid = (lo + hi + 1) ushr 1
        if (starts[mid] <= char) lo = mid else hi = mid - 1
    }
    return lo
}

/** Index in [sen] (sentence starts, ascending) of the sentence containing [offset]; 0 when there are none. */
internal fun sentenceIndexAt(sen: List<Int>, offset: Int): Int {
    var lo = 0; var hi = sen.size - 1
    while (lo < hi) {
        val mid = (lo + hi + 1) ushr 1
        if (sen[mid] <= offset) lo = mid else hi = mid - 1
    }
    return lo
}

/** The sentence containing [offset] in [s], without surrounding whitespace. No sentence data = the whole block. */
internal fun sentenceRangeAt(s: String, sen: List<Int>, offset: Int): Long {
    val i = sentenceIndexAt(sen, offset)
    return trimmedRange(s, if (i == 0) 0 else sen[i], if (i + 1 < sen.size) sen[i + 1] else s.length)
}

internal fun trimmedRange(s: String, from: Int, to: Int): Long {
    var a = from.coerceIn(0, s.length); var b = to.coerceIn(a, s.length)
    while (a < b && s[a].isWhitespace()) a++
    while (b > a && s[b - 1].isWhitespace()) b--
    return packRange(a, b)
}

private fun isWordChar(c: Char) = c.isLetterOrDigit() || c == '\'' || c == '’' || c == '-' || c == '­' || c == '́'

/** The word at [offset], or the nearest one after it (before it at the end of the text). Punctuation isn't part of a word. */
internal fun wordRangeAt(s: String, offset: Int): Long {
    if (s.isEmpty()) return 0L
    var i = offset.coerceIn(0, s.lastIndex)
    if (!isWordChar(s[i])) {
        var j = i
        while (j < s.length && !isWordChar(s[j])) j++
        if (j == s.length) { j = i; while (j >= 0 && !isWordChar(s[j])) j-- }
        if (j < 0) return 0L
        i = j
    }
    var a = i; while (a > 0 && isWordChar(s[a - 1])) a--
    var b = i + 1; while (b < s.length && isWordChar(s[b])) b++
    return packRange(a, b)
}

/** Pixels to scroll so a line now at [lineTopPx] lands on the anchor. Positive scrolls the text up. */
internal fun followDelta(lineTopPx: Float, viewportPx: Int, anchor: Float): Float = lineTopPx - viewportPx * anchor

/** -1 if the line is entirely above the viewport, 1 if below, 0 if any of it shows. */
internal fun narrationSide(topPx: Float, bottomPx: Float, viewportPx: Int): Int = when {
    bottomPx <= 0f -> -1
    topPx >= viewportPx -> 1
    else -> 0
}
