package app.mfbooks.library

import app.mfbooks.data.BookState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/** Real files written by Smart AudioBook Player (copied from the owner's library). */
class SabpTest {
    private fun bytes(name: String) = javaClass.getResourceAsStream("/sabp/$name")!!.readBytes()

    @Test fun martianPosition() {
        val p = parseSabpPosition(bytes("martian.sabp.dat"))!!
        assertEquals("The Martian.m4b", p.fileName)
        assertEquals(23L, p.filePosition)
        assertEquals(BookState.STARTED, p.state)
        assertNull(p.speed?.takeIf { it != 1f })
        assertFalse(p.skipSilence)
        assertEquals(0x1a14aeda2bfL, p.lastPlaybackTime)
        assertEquals(0x1a14a4d541cL, p.creationTime)
        assertEquals(23_000L, sabpPositionMs(p.filePosition, 39_568_982))
    }

    @Test fun reaperManAndCyrillicFileName() {
        val r = parseSabpPosition(bytes("reaperman.sabp.dat"))!!
        assertEquals("Reaper Man.m4b", r.fileName)
        assertEquals(1936L, r.filePosition)
        assertEquals(0x1a102207c70L, r.creationTime)
        val s = parseSabpPosition(bytes("sezon.sabp.dat"))!!
        assertEquals("Сезон гроз.m4b", s.fileName)
        assertEquals(54L, s.filePosition)
        assertEquals(1f, s.speed)
    }

    @Test fun genericReaderKeepsFieldNames() {
        val o = readJavaSerialized(bytes("martian.sabp.dat")) as JavaObject
        assertEquals("ak.alizandro.smartaudiobookplayer.BookDataBackup", o.className)
        assertEquals("Started", (o["mBookState"] as JavaEnum).name)
        assertNull(o["mCoverName"])
        assertEquals(0x9a90, o["mTotalPlaybackTimeCache"])
    }

    @Test fun positionUnits() {
        assertEquals(1_936_000L, sabpPositionMs(1936, 32_100_983))
        assertEquals(5_000_000L, sabpPositionMs(5_000_000, 32_100_983)) // too big for seconds → already ms
    }

    @Test fun statistics() {
        val s = parseSabpStatistics(String(bytes("statistics.xml")))
        assertEquals(12, s.size)
        assertEquals(SabpStat("Good Omens Chapterized", 2026, 6, 20695), s[2])
        assertEquals(SabpStat("Цикл  Ведьмак/8. Сезон гроз", 2025, 3, 53), s.last())
    }

    @Test fun bookmarks() {
        val xml = """<?xml version="1.0" encoding="UTF-8"?><bookmarks>
            <bookmark><title>Glas &amp; co</title><description></description><fileName>01.mp3</fileName><filePosition>125</filePosition></bookmark>
            <bookmark><title>Two</title><description>note</description><fileName>02.mp3</fileName><filePosition>7</filePosition></bookmark>
            </bookmarks>"""
        assertEquals(
            listOf(SabpBookmark("Glas & co", null, "01.mp3", 125), SabpBookmark("Two", "note", "02.mp3", 7)),
            parseSabpBookmarks(xml),
        )
    }
}
