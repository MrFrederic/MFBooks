package app.mfbooks.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MergeBookTest {
    private fun res(name: String) = decodeText(javaClass.getResourceAsStream("/phm/$name")!!.readBytes())

    @Test fun projectHailMary() {
        val cue = parseCue(res("Andy Weir - Project Hail Mary.cue"))
        val nfo = parseNfo(res("Andy Weir - Project Hail Mary.nfo"))
        assertEquals(31, cue.files[0].tracks.size)
        assertEquals(2_224_930L, cue.files[0].tracks[1].startMs)
        val fileName = "Andy Weir - Project Hail Mary.m4b"
        // Real embedded chapter starts of the m4b (ffprobe), titled "001".."030" by inAudible.
        val starts = listOf(0L, 2224936, 3946417, 6187630, 8648411, 11837765, 14488369, 16295067, 18484755, 20244294, 22365065,
            23931924, 25676115, 27630724, 30035940, 31465708, 33415976, 35344509, 37208212, 39384526, 41369321, 43906194,
            46302679, 48028850, 49701453, 52078016, 53368047, 53981309, 55172609, 57371538)
        val embedded = starts.mapIndexed { i, t -> RawChapter("%03d".format(i + 1), t) }
        val file = FileInput(
            fileName, 58_253_080,
            TagInfo(title = "Project Hail Mary", artist = "Andy Weir", album = "Project Hail Mary", composer = "Ray Porter", genre = "Audiobook", year = "2021", encoder = "inAudible 1.97"),
            embedded,
        )
        val m = mergeBook(
            MergeInput("Andy Weir - Project Hail Mary", "Audiobooks", listOf(file), nfo, listOf("Andy Weir - Project Hail Mary.cue" to cue), null),
        )
        assertEquals("Project Hail Mary", m.title)
        assertEquals("Andy Weir", m.author)
        assertEquals("Ray Porter", m.narrator)
        assertEquals("2021", m.year)
        assertEquals("Audible Studios", m.publisher)
        assertNotEquals("Audiobook", m.genre)
        assertNull(m.genre)
        assertTrue(m.description!!.startsWith("Ryland Grace is the sole survivor"))
        val ch = m.chapters.single()
        assertEquals(30, ch.size)
        assertEquals((1..30).map { "Chapter %02d".format(it) }, ch.map { it.title })
        assertEquals(embedded.map { it.startMs }, ch.map { it.startMs })
        assertEquals("embedded-timing+cue-titles", m.fieldSources["chapters"])
        assertEquals("nfo", m.fieldSources["title"])
        // The real cue says FILE "Project Hail Mary.m4b" (no author prefix) and has a 31st track past the end of the audio.
        assertEquals("Project Hail Mary.m4b", cue.files[0].name)
        assertEquals(30, nfo.expectedChapters)
        assertEquals(58_253_000L, nfo.expectedDurationMs)
        assertEquals(null, m.fieldSources["nfo.chapters"])
    }

    @Test fun witcherSeriesFolderAlbumIsSeries() {
        val tags = TagInfo(title = "Последнее желание", artist = "Сапковский Анджей", album = "Ведьмак", composer = "Кузнецов Всеволод",
            genre = "Фэнтези", description = "Открывает цикл «Ведьмак»", copyright = "ООО \"Издательство АСТ\", 2023")
        val m = mergeBook(MergeInput("1. Последнее желание", "Цикл  Ведьмак", listOf(f("Последнее желание.m4b", tags = tags)), null, emptyList(), null))
        assertEquals("Последнее желание", m.title)
        assertEquals("Сапковский Анджей", m.author)
        assertEquals("Кузнецов Всеволод", m.narrator)
        assertEquals("Ведьмак", m.series)
        assertEquals("1", m.seriesIndex)
        assertEquals("2023", m.year)
        assertEquals("ООО \"Издательство АСТ\"", m.publisher)
        // Two parts sharing the title tag.
        val parts = listOf(f("Владычица озера, часть 1.m4b", tags = tags.copy(title = "Владычица озера")), f("Владычица озера, часть 2.m4b", tags = tags.copy(title = "Владычица озера")))
        val m7 = mergeBook(MergeInput("7. Владычица озера", "Цикл  Ведьмак", parts, null, emptyList(), null))
        assertEquals("Владычица озера", m7.title)
        assertEquals("7", m7.seriesIndex)
    }

    @Test fun discworldGroupingHtmlAndCopyright() {
        val tags = TagInfo(title = "Mort", artist = "Terry Pratchett", albumArtist = "Terry Pratchett", album = "Mort", composer = "Sian Clifford, Bill Nighy",
            series = "Discworld #4; Discworld: Death #1", copyright = "Transworld Digital", year = "2022",
            description = "<p><strong>Over 1 million</strong> sold</p><p>Narrated by Sian&nbsp;Clifford &amp; others.<br />Death&#8217;s apprentice</p>")
        val m = mergeBook(MergeInput("Terry Pretchett - Mort", null, listOf(f("Mort.m4b", tags = tags)), null, emptyList(), null))
        assertEquals("Mort", m.title)
        assertEquals("Discworld", m.series)
        assertEquals("4", m.seriesIndex)
        assertEquals("Transworld Digital", m.publisher)
        assertEquals("Sian Clifford, Bill Nighy", m.narrator)
        assertEquals("Over 1 million sold\n\nNarrated by Sian Clifford & others.\nDeath’s apprentice", m.description)
    }

    @Test fun martianUnabridgedAndPhonogramCopyright() {
        val tags = TagInfo(title = "The Martian (Unabridged)", artist = "Andy Weir", albumArtist = "Andy Weir", album = "The Martian (Unabridged)",
            year = "2020", copyright = "&#169;2011, 2014, 2019 by Andy Weir;(P)2019 Audible, Inc.")
        val m = mergeBook(MergeInput("Andy Weir - The Martian", null, listOf(f("The Martian.m4b", tags = tags)), null, emptyList(), null))
        assertEquals("The Martian", m.title)
        assertEquals("Audible, Inc.", m.publisher)
        assertEquals("2020", m.year)
        assertNull(m.series)
        assertEquals(null to "2011", copyrightInfo("©2011 by Andy Weir"))
    }

    @Test fun seriesTagForms() {
        assertEquals("Foo" to "2", parseSeries("Foo, Book 2"))
        assertEquals("Ведьмак" to "3", parseSeries("Ведьмак, книга 3"))
        assertEquals("Plain" to null, parseSeries("Plain"))
        val untagged = mergeBook(MergeInput("Andy Weir - Artemis", null, listOf(f("a.mp3", tags = TagInfo(artist = "Andy Weir"))), null, emptyList(), null))
        assertEquals("Artemis", untagged.title)
    }

    private fun f(name: String, dur: Long = 600_000, tags: TagInfo = TagInfo(), emb: List<RawChapter>? = null) = FileInput(name, dur, tags, emb)

    @Test fun fileWithoutChaptersIsOneChapter() {
        val m = mergeBook(MergeInput("Folder", "Author Dir", listOf(f("01 Intro.mp3", tags = TagInfo(title = "The Intro")), f("02 Next.mp3")), null, emptyList(), null))
        assertEquals("Folder", m.title)
        assertEquals("Author Dir", m.author)
        assertEquals(listOf(listOf(RawChapter("The Intro", 0)), listOf(RawChapter("02 Next", 0))), m.chapters)
        val off = mergeBook(MergeInput("F", null, listOf(f("a.mp3", tags = TagInfo(title = "T"))), null, emptyList(), null, useTagTitles = false))
        assertEquals("a", off.chapters[0][0].title)
    }

    @Test fun cueBindingAndSanity() {
        val sheetA = CueSheet(null, "Perf", listOf(CueFile("whatever.wav", listOf(CueTrack("A1", null, 0), CueTrack("A2", null, 100_000), CueTrack("bad", null, 50_000), CueTrack("end", null, 599_500)))))
        val files = listOf(f("Book Part 1.mp3"), f("Book Part 2.mp3"))
        val m = mergeBook(MergeInput("B", null, files, null, listOf("Book Part 1.mp3.cue" to sheetA, "zzz.cue" to CueSheet(null, null, listOf(CueFile("nomatch", emptyList())))), null))
        assertEquals(listOf(RawChapter("A1", 0), RawChapter("A2", 100_000)), m.chapters[0])
        assertEquals("Perf", m.author)
        assertEquals(1, m.chapters[1].size)
        assertTrue(m.fieldSources.containsKey("cue:zzz.cue"))
    }

    @Test fun unalignedPicksMoreChaptersInRange() {
        val emb = listOf(RawChapter("Intro", 0), RawChapter("Real title", 60_000))
        val cue = CueSheet(null, null, listOf(CueFile("a.mp3", (0 until 5).map { CueTrack("T$it", null, it * 100_000L) })))
        val m = mergeBook(MergeInput("B", null, listOf(f("a.mp3", emb = emb)), null, listOf("a.cue" to cue), null))
        assertEquals(5, m.chapters[0].size)
        assertEquals("cue", m.fieldSources["chapters"])
    }

    @Test fun nonGenericEmbeddedTitleWins() {
        val emb = listOf(RawChapter("Opening Credits", 0), RawChapter("2", 60_000))
        val cue = CueSheet(null, null, listOf(CueFile("a.mp3", listOf(CueTrack("Chapter 01", null, 500), CueTrack("The Storm", null, 61_000)))))
        val m = mergeBook(MergeInput("B", null, listOf(f("a.mp3", emb = emb)), null, listOf("a.cue" to cue), null))
        assertEquals(listOf(RawChapter("Opening Credits", 0), RawChapter("The Storm", 60_000)), m.chapters[0])
    }
}
