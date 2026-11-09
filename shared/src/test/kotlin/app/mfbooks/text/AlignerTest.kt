package app.mfbooks.text

import kotlin.math.abs
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AlignerTest {
    private val msPerWord = 400

    /** A book of [words] random words in 100-word paragraphs, with each word's start offset. */
    private class Book(val doc: BookTextDoc, val words: List<String>, val starts: IntArray)

    private fun book(count: Int, seed: Int = 1): Book {
        val rnd = Random(seed)
        val vocab = List(3000) { i -> "w" + (0 until 3 + i % 6).map { 'a' + rnd.nextInt(26) }.joinToString("") }
        val words = List(count) { vocab[rnd.nextInt(vocab.size)] }
        val starts = IntArray(count)
        val blocks = ArrayList<Block>()
        var global = 0
        for (para in words.indices step 100) {
            val sb = StringBuilder()
            for (k in para until minOf(para + 100, count)) {
                if (sb.isNotEmpty()) sb.append(' ')
                starts[k] = global + sb.length
                sb.append(words[k])
            }
            sb.append('.')
            blocks += Block(BlockKind.P, sb.toString())
            global += sb.length
        }
        val doc = BookTextDoc(lang = "en", source = TextSource("b.txt", "txt", "x"), blocks = blocks)
        return Book(doc, words, starts)
    }

    private fun undelta(d: List<Int>): IntArray {
        var sum = 0
        return IntArray(d.size) { sum += d[it]; sum }
    }

    private fun assertMonotonic(doc: SyncDoc) {
        assertEquals(doc.chars.size, doc.ms.size)
        assertTrue("chars ascend", doc.chars.drop(1).all { it > 0 })
        assertTrue("ms ascend", doc.ms.drop(1).all { it > 0 })
    }

    private fun track(ms: Long) = listOf(SyncTrack("a.m4b", ms))

    @Test fun robustToRecognitionErrors() {
        val b = book(6000)
        val rnd = Random(7)
        val introWords = 400
        val truth = { k: Int -> (introWords + k) * msPerWord }
        val heard = ArrayList<TimedWord>()
        repeat(introWords) { heard += TimedWord("intro${rnd.nextInt(100000)}", it * msPerWord) }
        val shuffled = (2000 until 2100).toList().shuffled(rnd)
        for (k in b.words.indices) {
            val source = if (k in 2000 until 2100) shuffled[k - 2000] else k
            when {
                rnd.nextFloat() < 0.05f -> Unit // deleted
                rnd.nextFloat() < 0.15f -> heard += TimedWord("noise${rnd.nextInt(100000)}", truth(k))
                else -> heard += TimedWord(b.words[source].uppercase() + ",", truth(k))
            }
        }
        val total = truth(b.words.size).toLong()
        val doc = Aligner.align(b.doc, "sha", heard, listOf(0..total.toInt()), track(total), emptyList(), "vosk")
        assertEquals(SyncTier.ALIGNED, doc.tier)
        assertEquals("vosk", doc.engine)
        assertMonotonic(doc)
        assertTrue("coverage ${doc.coverage()}", doc.coverage() > 0.9f)

        val map = assertNotNullMap(doc, track(total))
        val errors = b.words.indices.map { abs(map.msAt(b.starts[it]) - truth(it)) }.sorted()
        assertTrue("median ${errors[errors.size / 2]}", errors[errors.size / 2] <= 300)
        assertTrue("p95 ${errors[errors.size * 95 / 100]}", errors[errors.size * 95 / 100] <= 2500)
        assertEquals("narration before the book starts is unsynced", RangeKind.NONE, map.at(30_000).kind)
        assertEquals(RangeKind.WORD, map.at(truth(1000) + 100).kind)
    }

    @Test fun missingNarrationBecomesUnsyncedRange() {
        val b = book(3000)
        val kept = b.words.indices.filter { it !in 1000 until 1500 }
        val heard = kept.map { TimedWord(b.words[it], it * msPerWord) }
        val total = (b.words.size * msPerWord).toLong()
        val doc = Aligner.align(b.doc, "sha", heard, listOf(0..total.toInt()), track(total), emptyList(), "e")
        assertMonotonic(doc)
        val none = doc.ranges.single { it.kind == RangeKind.NONE }
        assertTrue(none.fromChar in b.starts[999]..b.starts[1000])
        assertEquals(b.starts[1500], none.toChar)
        assertEquals(RangeKind.NONE, assertNotNullMap(doc, track(total)).at(1200 * msPerWord).kind)
        assertEquals(1f, doc.ranges.first().quality, 0.001f)
    }

    @Test fun partialCoverageFallsBackToApproximate() {
        val b = book(4000)
        val from = 1000 * msPerWord
        val to = 2000 * msPerWord
        val heard = (1000 until 2000).map { TimedWord(b.words[it], it * msPerWord) }
        val total = (b.words.size * msPerWord).toLong()
        val doc = Aligner.align(b.doc, "sha", heard, listOf(from..to), track(total), emptyList(), "e")
        assertEquals(SyncTier.ALIGNED, doc.tier)
        assertMonotonic(doc)
        assertEquals(listOf(RangeKind.APPROX, RangeKind.WORD, RangeKind.APPROX), doc.ranges.map { it.kind })
        assertEquals(0.25f, doc.coverage(), 0.02f)
        val map = assertNotNullMap(doc, track(total))
        assertEquals(RangeKind.APPROX, map.at(100 * msPerWord).kind)
        assertEquals(RangeKind.WORD, map.at(1500 * msPerWord).kind)
        assertEquals(RangeKind.APPROX, map.at(3000 * msPerWord).kind)
        assertEquals(1500 * msPerWord, map.msAt(b.starts[1500]))
        assertEquals(0, map.msAt(0))
        assertEquals(total.toInt(), map.msAt(Int.MAX_VALUE))
    }

    @Test fun noMatchesStayApproximate() {
        val b = book(500)
        val heard = List(500) { TimedWord("other$it", it * msPerWord) }
        val total = 200_000L
        val doc = Aligner.align(b.doc, "sha", heard, listOf(0..200_000), track(total), emptyList(), "e")
        assertEquals(SyncTier.APPROXIMATE, doc.tier)
        assertEquals(0f, doc.coverage(), 0f)
    }

    @Test fun userKnotsWin() {
        val b = book(1000)
        val heard = b.words.indices.map { TimedWord(b.words[it], it * msPerWord) }
        val total = (b.words.size * msPerWord).toLong()
        val user = UserKnot(b.starts[500], 500 * msPerWord + 30_000)
        val doc = Aligner.align(b.doc, "sha", heard, listOf(0..total.toInt()), track(total), emptyList(), "e", listOf(user))
        assertMonotonic(doc)
        assertEquals(listOf(user), doc.user)
        assertEquals(user.ms, assertNotNullMap(doc, track(total)).msAt(user.char))
    }

    @Test fun largeBookIsFast() {
        val b = book(500_000)
        val rnd = Random(3)
        val heard = ArrayList<TimedWord>(500_000)
        for (k in b.words.indices) if (rnd.nextFloat() > 0.1f) heard += TimedWord(if (rnd.nextFloat() < 0.15f) "x${rnd.nextInt()}" else b.words[k], k * msPerWord)
        val total = (b.words.size * msPerWord).toLong()
        val started = System.nanoTime()
        val doc = Aligner.align(b.doc, "sha", heard, listOf(0..total.toInt()), track(total), emptyList(), "e")
        val seconds = (System.nanoTime() - started) / 1e9
        println("aligned 500k words in $seconds s, ${doc.chars.size} knots")
        assertTrue("took $seconds s", seconds < 15)
        assertMonotonic(doc)
        assertTrue(doc.coverage() > 0.95f)
    }

    private val titled = BookTextDoc(
        lang = "en", source = TextSource("b", "txt", "x"),
        toc = listOf(TocEntry("Chapter 1: Start", 0), TocEntry("Chapter 2", 2), TocEntry("Chapter 3", 4)),
        blocks = listOf(
            Block("h2", "Chapter 1"), Block("p", "a".repeat(991)),
            Block("h2", "Chapter 2"), Block("p", "b".repeat(991)),
            Block("h2", "Chapter 3"), Block("p", "c".repeat(991)),
            Block("note", "n".repeat(500)),
        ),
    )

    @Test fun approximateUsesMatchedChapters() {
        val chapters = listOf(ChapterRef("chapter 1", 0), ChapterRef("Chapter 2", 100_000), ChapterRef("CHAPTER 3!", 500_000))
        val doc = Approximate.build(titled, "sha", track(600_000), chapters)
        assertEquals(SyncTier.APPROXIMATE, doc.tier)
        assertEquals(listOf(0, 1000, 2000, 3000), undelta(doc.chars).toList())
        assertEquals(listOf(0, 100_000, 500_000, 600_000), undelta(doc.ms).toList())
        assertEquals(listOf(SyncRange(0, 3000, 0f, RangeKind.APPROX)), doc.ranges)
        val map = assertNotNullMap(doc, track(600_000))
        assertEquals(RangeKind.APPROX, map.at(50_000).kind)
        assertEquals(500, map.at(50_000).char)
    }

    @Test fun approximateFallsBackToEndsAndSkipsNotes() {
        val chapters = listOf(ChapterRef("Intro", 0), ChapterRef("Chapter 2", 100_000), ChapterRef("Other", 500_000))
        val doc = Approximate.build(titled, "sha", track(600_000), chapters)
        assertEquals(listOf(0, 3000), undelta(doc.chars).toList())
        assertEquals(listOf(0, 600_000), undelta(doc.ms).toList())
    }

    @Test fun midBookNotesTakeNoTime() {
        val doc = BookTextDoc(
            lang = "en", source = TextSource("b", "txt", "x"),
            blocks = listOf(Block("p", "a".repeat(1000)), Block("note", "n".repeat(8000)), Block("p", "b".repeat(1000))),
        )
        val map = assertNotNullMap(Approximate.build(doc, "sha", track(200_000), emptyList()), track(200_000))
        assertEquals(100_000.0, map.msAt(1000).toDouble(), 2.0)
        assertEquals(150_000.0, map.msAt(9500).toDouble(), 2.0)
    }

    @Test fun withUserKnotIsIdempotentAndMonotonic() {
        val base = Approximate.build(titled, "sha", track(600_000), listOf(ChapterRef("Chapter 1", 0), ChapterRef("Chapter 2", 100_000), ChapterRef("Chapter 3", 500_000)))
        val knot = UserKnot(1500, 550_000) // later than the Chapter 3 knot at char 2000, ms 500000
        val once = base.withUserKnot(knot)
        assertEquals(once, once.withUserKnot(knot))
        assertMonotonic(once)
        assertFalse("conflicting chapter knot removed", 2000 in undelta(once.chars).toList())

        val moved = once.withUserKnot(UserKnot(1500, 400_000))
        assertEquals(listOf(UserKnot(1500, 400_000)), moved.user)
        assertMonotonic(moved)
        val map = assertNotNullMap(moved, track(600_000))
        assertEquals(400_000, map.msAt(1500))
    }

    private fun assertNotNullMap(doc: SyncDoc, tracks: List<SyncTrack>): SyncMap = SyncMap.of(doc, tracks).also { assertNotNull(it) }!!
}
