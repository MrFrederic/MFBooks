package app.mfbooks.ui.reader

import org.junit.Assert.assertEquals
import org.junit.Test

class ReaderLogicTest {
    private fun word(s: String, at: Int) = wordRangeAt(s, at).let { s.substring(rangeStart(it), rangeEnd(it)) }
    private fun sentence(s: String, sen: List<Int>, at: Int) = sentenceRangeAt(s, sen, at).let { s.substring(rangeStart(it), rangeEnd(it)) }

    @Test fun `block by char skips empty blocks`() {
        val starts = intArrayOf(0, 10, 10, 25, 30) // block 1 is empty
        assertEquals(0, blockIndexOfChar(starts, 0))
        assertEquals(0, blockIndexOfChar(starts, 9))
        assertEquals(2, blockIndexOfChar(starts, 10))
        assertEquals(3, blockIndexOfChar(starts, 29))
    }

    @Test fun `sentence range drops whitespace and handles missing data`() {
        val s = "One two. Three four.  "
        val sen = listOf(0, 9)
        assertEquals("One two.", sentence(s, sen, 3))
        assertEquals("Three four.", sentence(s, sen, 9))
        assertEquals("Three four.", sentence(s, sen, 21))
        assertEquals("One two. Three four.", sentence(s, emptyList(), 5))
    }

    @Test fun `word range excludes punctuation and finds the nearest word`() {
        val s = "“You’ll want boots,” he said."
        assertEquals("You’ll", word(s, 0))
        assertEquals("You’ll", word(s, 5))
        assertEquals("boots", word(s, 14))
        assertEquals("he", word(s, 19))
        assertEquals("said", word(s, s.lastIndex))
        assertEquals(0L, wordRangeAt("...", 1))
    }

    @Test fun `follow delta and side`() {
        assertEquals(170f, followDelta(500f, 1000, 0.33f), 0.01f)
        assertEquals(-30f, followDelta(300f, 1000, 0.33f), 0.01f)
        assertEquals(-1, narrationSide(-40f, 0f, 1000))
        assertEquals(0, narrationSide(-10f, 20f, 1000))
        assertEquals(1, narrationSide(1000f, 1040f, 1000))
    }
}
