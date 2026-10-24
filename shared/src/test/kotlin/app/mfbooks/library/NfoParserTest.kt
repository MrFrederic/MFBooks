package app.mfbooks.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NfoParserTest {
    @Test fun parsesSectionsAndAliases() {
        val n = parseNfo(
            "Book Info\r\n=========\r\nBook Title: Dune\r\nWritten By: Frank Herbert\r\nNarrated By: Scott Brick\r\n" +
                "Release Date: 10 Jan 2007\r\nGenre: Audiobook\r\nSeries: Dune Chronicles\r\nBook: 1\r\n" +
                "Duration: 21:02:03\r\nChapters: 48\r\n\r\nSummary\r\n-------\r\nA desert\r\n  planet.\r\n\r\nSecond para.\r\n",
        )
        assertEquals("Dune", n.title)
        assertEquals("Frank Herbert", n.author)
        assertEquals("Scott Brick", n.narrator)
        assertEquals("2007", n.year)
        assertNull(n.genre)
        assertEquals("Dune Chronicles", n.series)
        assertEquals("1", n.seriesIndex)
        assertEquals(48, n.expectedChapters)
        assertEquals(((21 * 60 + 2) * 60 + 3) * 1000L, n.expectedDurationMs)
        assertEquals("A desert planet.\n\nSecond para.", n.description)
    }

    @Test fun wordyDuration() {
        assertEquals((16 * 3600 + 10 * 60 + 53) * 1000L, parseNfo("Duration: 16 hours, 10 minutes, 53 seconds").expectedDurationMs)
        assertTrue(parseNfo("nothing here").raw.isEmpty())
    }
}
