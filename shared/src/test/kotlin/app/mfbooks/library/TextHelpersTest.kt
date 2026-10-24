package app.mfbooks.library

import org.junit.Assert.assertEquals
import org.junit.Test

class TextHelpersTest {
    @Test fun decodesUtf8Utf16AndCp1251() {
        assertEquals("Привет", decodeText("Привет".toByteArray()))
        assertEquals("Привет", decodeText(byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + "Привет".toByteArray(Charsets.UTF_16LE)))
        assertEquals("Привет, мир! Это длинный русский текст для проверки.", decodeText("Привет, мир! Это длинный русский текст для проверки.".toByteArray(charset("windows-1251"))))
    }

    @Test fun parsesOpenbookJson() {
        val j = """{"nav":{"toc":[{"title":"Intro","path":"Part%201.mp3#0"},{"title":"B","path":"Part%201.mp3#61.5"},{"title":"C","path":"Part2.mp3"}]}}"""
        val m = parseOpenbookJson(j)
        assertEquals(listOf(RawChapter("Intro", 0), RawChapter("B", 61_500)), m["Part 1.mp3"])
        assertEquals(listOf(RawChapter("C", 0)), m["Part2.mp3"])
    }

    @Test fun ranksCovers() {
        val r = rankCoverCandidates(
            listOf("scan.jpg", "back.jpg", "Book.png", "CD1.jpg", "Folder.jpg", "notes.txt"),
            listOf("Book"), "My Folder",
        )
        assertEquals(listOf("Folder.jpg", "Book.png", "scan.jpg"), r)
    }
}
