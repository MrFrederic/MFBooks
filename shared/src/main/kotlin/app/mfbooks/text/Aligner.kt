package app.mfbooks.text

import java.text.Normalizer
import java.util.Arrays
import kotlin.math.abs

object Approximate {
    /** Knots at matched chapters when at least 70% of [chapters] match the TOC, otherwise only the book's two ends. */
    fun build(doc: BookTextDoc, textSha: String, tracks: List<SyncTrack>, chapters: List<ChapterRef>, user: List<UserKnot> = emptyList()): SyncDoc {
        val totalMs = tracks.sumOf { it.durationMs }.toInt()
        val starts = doc.blockStarts()
        val spine = spineRuns(doc, starts)
        val spineEnd = spine.lastOrNull()?.to ?: 0

        val toc = doc.toc.filter { doc.blocks.getOrNull(it.block)?.let { b -> b.k != BlockKind.NOTE } == true }
        var knots = ArrayList<IntArray>()
        var nextToc = 0
        for (ch in chapters) {
            val wanted = titleKey(ch.title)
            val at = (nextToc until toc.size).firstOrNull { titlesMatch(wanted, titleKey(toc[it].title)) } ?: continue
            nextToc = at + 1
            knots += intArrayOf(starts[toc[at].block], ch.startInBookMs)
        }
        if (chapters.isEmpty() || knots.size < chapters.size * 0.7) knots.clear()
        knots = ArrayList(knots.sortedBy { it[0] })
        if (knots.isEmpty() || (knots[0][0] > 0 && knots[0][1] > 0)) knots.add(0, intArrayOf(0, 0))
        if (spineEnd > knots.last()[0] && totalMs > knots.last()[1]) knots += intArrayOf(spineEnd, totalMs)

        val out = Knots()
        for ((c, m) in collapseNotes(knots, spine)) out.add(c, m)
        val ranges = if (spineEnd > 0) listOf(SyncRange(0, spineEnd, 0f, RangeKind.APPROX)) else emptyList()
        return finish(textSha, SyncTier.APPROXIMATE, null, tracks, out, ranges, user)
    }

    /** Adds knots either side of mid-book note runs so they take up no audio time: char-proportional counts spine chars only. [knots] ascend. */
    private fun collapseNotes(knots: List<IntArray>, spine: List<Run>): List<IntArray> {
        if (spine.size < 2) return knots
        val skipped = IntArray(spine.size)
        for (i in 1 until spine.size) skipped[i] = skipped[i - 1] + spine[i].from - spine[i - 1].to
        var run = 0
        val spineChar = IntArray(knots.size) { k ->
            while (run + 1 < spine.size && spine[run + 1].from <= knots[k][0]) run++
            knots[k][0] - skipped[run]
        }
        val extra = ArrayList<IntArray>()
        var right = 0
        for (i in 1 until spine.size) {
            val at = spine[i - 1].to - skipped[i - 1]
            while (right < knots.size && spineChar[right] < at) right++
            if (right == 0 || right == knots.size) continue
            val m0 = knots[right - 1][1]
            val ms = m0 + ((at - spineChar[right - 1]).toLong() * (knots[right][1] - m0) / maxOf(1, spineChar[right] - spineChar[right - 1])).toInt()
            extra += intArrayOf(spine[i - 1].to, ms)
            extra += intArrayOf(spine[i].from, ms + 1)
        }
        return (knots + extra).sortedBy { it[0] }
    }
}

object Aligner {
    private const val NGRAM = 5
    private const val MAX_GAP_MS = 90_000
    private const val MAX_GAP_WORDS = 300
    private const val COVER_TOLERANCE_MS = 1_000
    private const val MS_PER_CHAR = 80

    /**
     * [words] are sorted by time; [coveredMs] are the stretches of audio already transcribed. Audio outside them falls back to
     * [Approximate] knots with [RangeKind.APPROX] ranges, so a partial transcript gives a usable map. [user] knots always win.
     */
    fun align(
        doc: BookTextDoc, textSha: String, words: List<TimedWord>, coveredMs: List<IntRange>,
        tracks: List<SyncTrack>, chapters: List<ChapterRef>, engine: String, user: List<UserKnot> = emptyList(),
    ): SyncDoc {
        val approx = Approximate.build(doc, textSha, tracks, chapters)
        val approxChars = undelta(approx.chars)
        val approxMs = undelta(approx.ms)
        val totalMs = tracks.sumOf { it.durationMs }.toInt()
        val starts = doc.blockStarts()
        val spineEnd = spineRuns(doc, starts).lastOrNull()?.to ?: 0

        val dict = HashMap<String, Int>()
        val book = bookTokens(doc, starts, dict)
        val heard = heardTokens(words, dict)
        val bookIds = book.ids.toArray()
        val heardIds = heard.ids.toArray()
        val matches = matchAnchors(bookIds, heardIds)
        val bookStart = book.starts.toArray()
        val bookEnd = book.ends.toArray()
        val heardMs = heard.ms.toArray()

        val covered = mergeCovered(coveredMs)
        var coverIndex = 0
        fun isCovered(fromMs: Int, toMs: Int): Boolean {
            while (coverIndex < covered.size && covered[coverIndex].last < toMs - COVER_TOLERANCE_MS) coverIndex++
            return coverIndex < covered.size && covered[coverIndex].first <= fromMs + COVER_TOLERANCE_MS
        }

        val out = Knots()
        val ranges = ArrayList<SyncRange>()
        var wordsFrom = 0
        var wordsFromIndex = 0
        var hits = 0
        var totalHits = 0
        fun closeWords(toChar: Int, toIndex: Int) {
            if (toChar > wordsFrom) ranges += SyncRange(wordsFrom, toChar, hits.toFloat() / maxOf(1, toIndex - wordsFromIndex), RangeKind.WORD)
        }

        val table = ShortArray((MAX_GAP_WORDS + 1) * (MAX_GAP_WORDS + 1))
        var approxAt = 0
        var leftB = -1
        var leftT = -1
        var leftC = 0
        var leftM = 0
        val count = matches.size
        for (i in 0..count) {
            val last = i == count
            val rightB = if (last) bookIds.size else matches.book[i]
            val rightT = if (last) heardIds.size else matches.heard[i]
            val rightC = if (last) spineEnd else bookStart[rightB]
            val rightM = if (last) totalMs else heardMs[rightT]
            if (i == 0 && rightC > 0) out.add(0, 0)

            val gapB = rightB - leftB - 1
            val gapT = rightT - leftT - 1
            val gapMs = rightM - leftM
            val kind = when {
                !isCovered(leftM, rightM) -> RangeKind.APPROX
                gapMs > MAX_GAP_MS || gapB > MAX_GAP_WORDS || gapT > MAX_GAP_WORDS -> RangeKind.NONE
                else -> null
            }
            if (kind == null) {
                if (gapB > 0 && gapT > 0) editMatches(bookIds, leftB + 1, gapB, heardIds, leftT + 1, gapT, table) { b, t ->
                    out.add(bookStart[b], heardMs[t])
                    hits++
                    totalHits++
                }
            } else {
                // The gap starts just after the last timed word, so that word keeps a short stretch of real timing.
                val wordLen = if (i == 0) 0 else bookEnd[leftB] - leftC
                val split = if (i == 0) 0 else minOf(leftC + wordLen, rightC - 1)
                if (rightC > split && (i == 0 || split > leftC)) {
                    closeWords(split, leftB + 1)
                    if (i != 0) out.add(split, leftM + minOf(wordLen * MS_PER_CHAR, gapMs / 2))
                    if (kind == RangeKind.APPROX) {
                        while (approxAt < approxChars.size && approxChars[approxAt] <= split) approxAt++
                        while (approxAt < approxChars.size && approxChars[approxAt] < rightC) {
                            if (approxMs[approxAt] < rightM) out.add(approxChars[approxAt], approxMs[approxAt])
                            approxAt++
                        }
                    }
                    ranges += SyncRange(split, rightC, 0f, kind)
                    wordsFrom = rightC
                    wordsFromIndex = rightB
                    hits = 0
                }
            }
            if (!last) {
                out.add(rightC, rightM)
                hits++
                totalHits++
            }
            leftB = rightB
            leftT = rightT
            leftC = rightC
            leftM = rightM
        }
        if (totalHits == 0) return approx.withUserKnots(user)
        if (totalMs > 0 && spineEnd > 0) out.add(spineEnd, totalMs)
        closeWords(spineEnd, bookIds.size)
        return finish(textSha, SyncTier.ALIGNED, engine, tracks, out, ranges, user)
    }

    private class Tokens {
        val ids = IntBuf()
        val starts = IntBuf()
        val ends = IntBuf()
        val ms = IntBuf()
    }

    private fun bookTokens(doc: BookTextDoc, starts: IntArray, dict: HashMap<String, Int>): Tokens {
        val out = Tokens()
        for ((i, b) in doc.blocks.withIndex()) {
            if (b.k == BlockKind.NOTE || b.k == BlockKind.IMAGE) continue
            forEachWord(b.s) { from, to ->
                val key = normaliseWord(b.s.substring(from, to))
                if (key.isNotEmpty()) {
                    out.ids.add(dict.getOrPut(key) { dict.size })
                    out.starts.add(starts[i] + from)
                    out.ends.add(starts[i] + to)
                }
            }
        }
        return out
    }

    private fun heardTokens(words: List<TimedWord>, dict: HashMap<String, Int>): Tokens {
        val out = Tokens()
        for (w in words) forEachWord(w.w) { from, to ->
            val key = normaliseWord(w.w.substring(from, to))
            if (key.isNotEmpty()) {
                out.ids.add(dict.getOrPut(key) { dict.size })
                out.ms.add(w.ms)
            }
        }
        return out
    }

    private class Matches(val book: IntArray, val heard: IntArray, val size: Int)

    /** Words of the n-grams unique to both sides that stay in order (longest increasing subsequence). */
    private fun matchAnchors(book: IntArray, heard: IntArray): Matches {
        val pairs = uniquePairs(book, heard)
        val chain = longestIncreasing(pairs)
        val outB = IntArray(minOf(book.size, heard.size))
        val outT = IntArray(outB.size)
        var size = 0
        var lastB = -1
        var lastT = -1
        for (i in chain) {
            val t = (pairs[i] ushr 32).toInt()
            val b = pairs[i].toInt()
            for (k in 0 until NGRAM) if (b + k > lastB && t + k > lastT) {
                outB[size] = b + k
                outT[size++] = t + k
                lastB = b + k
                lastT = t + k
            }
        }
        return Matches(outB, outT, size)
    }

    /** `(heard index << 32) | book index` for every matching n-gram, ascending by heard index. */
    private fun uniquePairs(book: IntArray, heard: IntArray): LongArray {
        val sortedB = grams(book)
        val sortedT = grams(heard)
        val out = LongArray(minOf(sortedB.size, sortedT.size))
        var size = 0
        var i = 0
        var j = 0
        while (i < sortedB.size && j < sortedT.size) {
            val hb = sortedB[i] ushr INDEX_BITS
            val ht = sortedT[j] ushr INDEX_BITS
            if (hb < ht) i++
            else if (ht < hb) j++
            else {
                val iEnd = runEnd(sortedB, i)
                val jEnd = runEnd(sortedT, j)
                if (iEnd == i + 1 && jEnd == j + 1) {
                    val b = (sortedB[i] and INDEX_MASK).toInt()
                    val t = (sortedT[j] and INDEX_MASK).toInt()
                    if ((0 until NGRAM).all { book[b + it] == heard[t + it] }) out[size++] = (t.toLong() shl 32) or b.toLong()
                }
                i = iEnd
                j = jEnd
            }
        }
        return out.copyOf(size).also { it.sort() }
    }

    // ponytail: a 38-bit hash packed with a 26-bit index so one LongArray sort finds the unique n-grams with no hash map.
    // A hash collision just loses that anchor (or fails the id check). Words past 2^26 (67M, far over any book) are ignored.
    private const val INDEX_BITS = 26
    private const val INDEX_MASK = (1L shl INDEX_BITS) - 1

    private fun grams(ids: IntArray): LongArray {
        val n = minOf(ids.size - NGRAM + 1, 1 shl INDEX_BITS)
        if (n <= 0) return LongArray(0)
        return LongArray(n) { i ->
            var h = 0L
            for (k in 0 until NGRAM) h = (h + ids[i + k] + 1) * -7046029254386353131L
            h = h xor (h ushr 29)
            h = h * -7046029254386353131L
            ((h ushr INDEX_BITS) shl INDEX_BITS) or i.toLong()
        }.also { it.sort() }
    }

    private fun runEnd(sorted: LongArray, from: Int): Int {
        val h = sorted[from] ushr INDEX_BITS
        var e = from + 1
        while (e < sorted.size && sorted[e] ushr INDEX_BITS == h) e++
        return e
    }

    /** Indices into [pairs] of a longest strictly increasing subsequence of the book indices. */
    private fun longestIncreasing(pairs: LongArray): IntArray {
        if (pairs.isEmpty()) return IntArray(0)
        val tails = IntArray(pairs.size)
        val previous = IntArray(pairs.size)
        var len = 0
        for (i in pairs.indices) {
            val b = pairs[i].toInt()
            var lo = 0
            var hi = len
            while (lo < hi) {
                val mid = (lo + hi) ushr 1
                if (pairs[tails[mid]].toInt() < b) lo = mid + 1 else hi = mid
            }
            previous[i] = if (lo > 0) tails[lo - 1] else -1
            tails[lo] = i
            if (lo == len) len++
        }
        val out = IntArray(len)
        var at = tails[len - 1]
        for (k in len - 1 downTo 0) {
            out[k] = at
            at = previous[at]
        }
        return out
    }

    /** Calls [match] in order for the identical words on a minimum edit path between the two stretches. Both are at most [MAX_GAP_WORDS]. */
    private inline fun editMatches(b: IntArray, bFrom: Int, n: Int, t: IntArray, tFrom: Int, m: Int, d: ShortArray, match: (Int, Int) -> Unit) {
        val w = m + 1
        for (j in 0..m) d[j] = j.toShort()
        for (i in 1..n) {
            d[i * w] = i.toShort()
            for (j in 1..m) {
                val diag = d[(i - 1) * w + j - 1] + if (b[bFrom + i - 1] == t[tFrom + j - 1]) 0 else 1
                d[i * w + j] = minOf(diag, d[(i - 1) * w + j] + 1, d[i * w + j - 1] + 1).toShort()
            }
        }
        val found = IntArray(minOf(n, m))
        var size = 0
        var i = n
        var j = m
        while (i > 0 && j > 0) {
            val same = b[bFrom + i - 1] == t[tFrom + j - 1]
            val here = d[i * w + j].toInt()
            when {
                here == d[(i - 1) * w + j - 1] + (if (same) 0 else 1) -> {
                    if (same) found[size++] = (i - 1 shl 16) or (j - 1)
                    i--
                    j--
                }
                here == d[(i - 1) * w + j] + 1 -> i--
                else -> j--
            }
        }
        for (k in size - 1 downTo 0) match(bFrom + (found[k] ushr 16), tFrom + (found[k] and 0xFFFF))
    }

    private fun mergeCovered(ranges: List<IntRange>): List<IntRange> {
        val out = ArrayList<IntRange>()
        for (r in ranges.sortedBy { it.first }) {
            val last = out.lastOrNull()
            if (last != null && r.first <= last.last + COVER_TOLERANCE_MS) out[out.lastIndex] = last.first..maxOf(last.last, r.last) else out += r
        }
        return out
    }

    private inline fun forEachWord(s: String, word: (Int, Int) -> Unit) {
        var i = 0
        while (i < s.length) {
            while (i < s.length && !isWordChar(s[i])) i++
            val from = i
            while (i < s.length && (isWordChar(s[i]) || (isApostrophe(s[i]) && i > from && i + 1 < s.length && isWordChar(s[i + 1])))) i++
            if (i > from) word(from, i)
        }
    }

    private fun isApostrophe(c: Char) = c == '\'' || c == '’' || c == 'ʼ'

    /** Letters, digits, and the marks and format characters that sit inside words (stress accents, soft hyphens). */
    private fun isWordChar(c: Char) = when (Character.getType(c).toByte()) {
        Character.NON_SPACING_MARK, Character.COMBINING_SPACING_MARK, Character.FORMAT -> true
        Character.SURROGATE -> true
        else -> c.isLetterOrDigit()
    }

    private fun normaliseWord(raw: String): String {
        val n = Normalizer.normalize(raw, Normalizer.Form.NFKC).lowercase()
        val sb = StringBuilder(n.length)
        for (c in n) if (c.isLetterOrDigit()) sb.append(if (c == 'ё') 'е' else c)
        return sb.toString()
    }
}

/** Runtime lookup. Binary search over two [IntArray]s, cheap enough to call every frame. */
class SyncMap private constructor(
    private val chars: IntArray,
    private val ms: IntArray,
    private val rangeFrom: IntArray,
    private val rangeTo: IntArray,
    private val rangeKind: Array<String>,
    private val outside: String,
) {
    /** Text position for book time [ms]. Kind [RangeKind.NONE] means the narration isn't in the text here. */
    fun at(ms: Int): SyncPosition {
        val times = this.ms
        if (times.isEmpty()) return SyncPosition(0, RangeKind.NONE)
        if (ms < times[0]) return SyncPosition(chars[0], RangeKind.NONE)
        if (ms > times.last()) return SyncPosition(chars.last(), RangeKind.NONE)
        val found = Arrays.binarySearch(times, ms)
        if (found >= 0) return SyncPosition(chars[found], kindAt(chars[found]))
        val i = -found - 2
        val char = chars[i] + ((ms - times[i]).toLong() * (chars[i + 1] - chars[i]) / (times[i + 1] - times[i])).toInt()
        return SyncPosition(char, kindAt(chars[i]))
    }

    /** Book time at which text position [char] is read. */
    fun msAt(char: Int): Int {
        if (chars.isEmpty()) return 0
        if (char <= chars[0]) return ms[0]
        if (char >= chars.last()) return ms.last()
        val found = Arrays.binarySearch(chars, char)
        if (found >= 0) return ms[found]
        val i = -found - 2
        return ms[i] + ((char - chars[i]).toLong() * (ms[i + 1] - ms[i]) / (chars[i + 1] - chars[i])).toInt()
    }

    private fun kindAt(char: Int): String {
        val found = Arrays.binarySearch(rangeFrom, char)
        val i = if (found >= 0) found else -found - 2
        return if (i >= 0 && char < rangeTo[i]) rangeKind[i] else outside
    }

    companion object {
        private const val MAX_TRACK_DRIFT_MS = 1_500L

        /**
         * Null if [current] isn't the audio [doc] was made from: track count differs, or a duration is off by over 1.5 s.
         * Knots are rebased per track on [current] so small conversion errors don't add up across files.
         */
        fun of(doc: SyncDoc, current: List<SyncTrack>): SyncMap? {
            if (doc.tracks.size != current.size) return null
            if (doc.tracks.indices.any { abs(doc.tracks[it].durationMs - current[it].durationMs) > MAX_TRACK_DRIFT_MS }) return null

            val docChars = undelta(doc.chars)
            val docMs = undelta(doc.ms)
            val out = Knots()
            var track = 0
            var docStart = 0L
            var curStart = 0L
            for (i in docChars.indices) {
                while (track < doc.tracks.lastIndex && docMs[i] >= docStart + doc.tracks[track].durationMs) {
                    docStart += doc.tracks[track].durationMs
                    curStart += current[track].durationMs
                    track++
                }
                out.add(docChars[i], (docMs[i] - docStart + curStart).toInt())
            }
            val outside = if (doc.ranges.isEmpty()) RangeKind.APPROX else RangeKind.NONE
            return SyncMap(
                out.chars.toArray(), out.ms.toArray(),
                IntArray(doc.ranges.size) { doc.ranges[it].fromChar }, IntArray(doc.ranges.size) { doc.ranges[it].toChar },
                Array(doc.ranges.size) { doc.ranges[it].kind }, outside,
            )
        }
    }
}

fun SyncDoc.withUserKnot(knot: UserKnot): SyncDoc = withUserKnots(user + knot)

/** Fraction of the audio with word-level timing, 0..1. By time, so licence pages and appendices nobody narrates don't count against a book. */
fun SyncDoc.coverage(): Float {
    val totalMs = tracks.sumOf { it.durationMs }
    val cs = undelta(chars)
    val ts = undelta(ms)
    if (totalMs == 0L || cs.isEmpty()) return 0f
    fun at(c: Int): Long {
        val i = cs.binarySearch(c).let { if (it >= 0) it else -it - 2 }
        if (i < 0) return ts.first().toLong()
        if (i >= cs.lastIndex) return ts.last().toLong()
        return ts[i] + (c - cs[i]).toLong() * (ts[i + 1] - ts[i]) / maxOf(1, cs[i + 1] - cs[i])
    }
    return (ranges.filter { it.kind == RangeKind.WORD }.sumOf { at(it.toChar) - at(it.fromChar) }.toFloat() / totalMs).coerceIn(0f, 1f)
}

private fun SyncDoc.withUserKnots(knots: List<UserKnot>): SyncDoc {
    val cs = undelta(chars)
    val ts = undelta(ms)
    val base = Knots()
    for (i in cs.indices) base.add(cs[i], ts[i])
    val kept = consistentUser(knots)
    val merged = applyUser(base, kept)
    return copy(chars = delta(merged.chars.toArray()), ms = delta(merged.ms.toArray()), user = kept)
}

/** User knots that don't contradict a later one, by char. A later knot beats any earlier one it conflicts with. */
private fun consistentUser(user: List<UserKnot>): List<UserKnot> {
    val kept = ArrayList<UserKnot>()
    for (k in user.asReversed()) {
        if (kept.all { (it.char < k.char && it.ms < k.ms) || (it.char > k.char && it.ms > k.ms) }) kept += k
    }
    return kept.sortedBy { it.char }
}

/** Keeps every knot in [user] (already consistent), dropping base knots that would no longer be strictly increasing around them. */
private fun applyUser(base: Knots, user: List<UserKnot>): Knots {
    if (user.isEmpty()) return base
    val cs = base.chars.toArray()
    val ts = base.ms.toArray()
    val out = Knots()
    var j = 0
    for (i in cs.indices) {
        while (j < user.size && user[j].char < cs[i]) {
            out.add(user[j].char, user[j].ms)
            j++
        }
        if (j < user.size && (cs[i] >= user[j].char || ts[i] >= user[j].ms)) continue
        out.add(cs[i], ts[i])
    }
    while (j < user.size) {
        out.add(user[j].char, user[j].ms)
        j++
    }
    return out
}

private fun finish(
    textSha: String, tier: String, engine: String?, tracks: List<SyncTrack>, knots: Knots, ranges: List<SyncRange>, user: List<UserKnot>,
): SyncDoc {
    val kept = consistentUser(user)
    val final = applyUser(knots, kept)
    return SyncDoc(
        textSha = textSha, tier = tier, engine = engine, tracks = tracks,
        chars = delta(final.chars.toArray()), ms = delta(final.ms.toArray()), ranges = ranges, user = kept,
    )
}

/** Consecutive non-note blocks, as global offsets with [to] exclusive. */
private class Run(val from: Int, val to: Int)

private fun spineRuns(doc: BookTextDoc, starts: IntArray): List<Run> {
    val out = ArrayList<Run>()
    var runStart = -1
    for (i in doc.blocks.indices) {
        if (doc.blocks[i].k == BlockKind.NOTE) {
            if (runStart >= 0) out += Run(runStart, starts[i])
            runStart = -1
        } else if (runStart < 0) runStart = starts[i]
    }
    if (runStart >= 0) out += Run(runStart, starts[doc.blocks.size])
    return out
}

private fun titleKey(title: String): String {
    val n = Normalizer.normalize(title, Normalizer.Form.NFKC).lowercase().replace('ё', 'е')
    return n.map { if (it.isLetterOrDigit()) it else ' ' }.joinToString("").trim().split(Regex("\\s+")).joinToString(" ")
}

/** Equal, or one title is the other plus a subtitle ("chapter 1" and "chapter 1 the boy", never "chapter 10"). */
private fun titlesMatch(a: String, b: String) = a.isNotEmpty() && b.isNotEmpty() && (a == b || a.startsWith("$b ") || b.startsWith("$a "))

private fun undelta(d: List<Int>): IntArray {
    val out = IntArray(d.size)
    var sum = 0
    for (i in d.indices) {
        sum += d[i]
        out[i] = sum
    }
    return out
}

private fun delta(a: IntArray): List<Int> = List(a.size) { if (it == 0) a[0] else a[it] - a[it - 1] }

private class IntBuf {
    private var a = IntArray(1024)
    var size = 0
        private set

    fun add(v: Int) {
        if (size == a.size) a = a.copyOf(size * 2)
        a[size++] = v
    }

    fun toArray(): IntArray = a.copyOf(size)
}

/** Knots in strictly increasing order on both axes: anything else is dropped as it arrives. */
private class Knots {
    val chars = IntBuf()
    val ms = IntBuf()
    private var lastC = -1
    private var lastM = -1

    fun add(c: Int, m: Int) {
        if (c <= lastC || m <= lastM) return
        chars.add(c)
        ms.add(m)
        lastC = c
        lastM = m
    }
}
