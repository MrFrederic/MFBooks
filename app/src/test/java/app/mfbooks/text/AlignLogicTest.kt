package app.mfbooks.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class AlignLogicTest {
    private fun member(from: Int, until: Int, vararg words: Pair<Int, String>, audio: Int = 1_000_000) =
        Transcript.Member(from, until, audio, "vosk-small-ru-0.22", words.map { TimedWord(it.second, it.first) })

    private fun tmpFile() = Files.createTempFile("transcript", ".gz").toFile().also { it.delete(); it.deleteOnExit() }

    @Test fun transcriptRoundTripsAcrossAppends() {
        val f = tmpFile()
        Transcript.append(f, member(0, 300_000, 10 to "первый", 2500 to "second word"))
        Transcript.append(f, member(300_000, 600_000))
        val back = Transcript.load(f, 1_000_000, 1)
        assertEquals(2, back.size)
        assertEquals(listOf(TimedWord("первый", 10), TimedWord("second word", 2500)), back[0].words)
        assertEquals("vosk-small-ru-0.22", back[0].engine)
        assertEquals(300_000, back[1].fromMs)
        assertTrue(back[1].words.isEmpty())
    }

    @Test fun tornTailIsCutOffAtEveryPossibleLength() {
        val good = Transcript.encode(member(0, 300_000, 1 to "a", 2 to "b"))
        val next = Transcript.encode(member(300_000, 600_000, 300_001 to "c"))
        for (cut in 1 until next.size) {
            val f = tmpFile()
            f.writeBytes(good + next.copyOf(cut))
            assertEquals("cut at $cut", 1, Transcript.load(f, 1_000_000, 1).size)
            assertEquals("cut at $cut", good.size.toLong(), f.length())
            Transcript.append(f, member(300_000, 600_000, 300_002 to "d"))
            assertEquals("cut at $cut", 2, Transcript.load(f, 1_000_000, 1).size)
            f.delete()
        }
    }

    @Test fun corruptedMemberStopsTheRead() {
        val good = Transcript.encode(member(0, 300_000, 1 to "a"))
        val bad = Transcript.encode(member(300_000, 600_000, 300_001 to "c")).also { it[it.size / 2] = (it[it.size / 2].toInt() xor 0x55).toByte() }
        val parsed = Transcript.decode(good + bad)
        assertEquals(1, parsed.members.size)
        assertEquals(good.size.toLong(), parsed.validBytes)
    }

    @Test fun membersOfOtherAudioAreIgnoredAndKept() {
        val f = tmpFile()
        Transcript.append(f, member(0, 1000, audio = 5_000_000))
        val size = f.length()
        assertTrue(Transcript.load(f, 1_000_000, 2).isEmpty())
        assertEquals(size, f.length())
        assertEquals(1, Transcript.load(f, 5_000_000 + 2000, 2).size) // inside 1.5 s per track
    }

    @Test fun coverageMergesAndFindsGaps() {
        val merged = Coverage.merge(listOf(500..999, 0..99, 100..199, 900..1200))
        assertEquals(listOf(0..199, 500..1200), merged)
        assertEquals(200 + 701, Coverage.totalMs(merged))
        assertEquals(listOf(200..499, 1201..1499), Coverage.gaps(0, 1500, merged))
        assertTrue(Coverage.gaps(0, 150, merged).isEmpty())
    }

    @Test fun planStartsAtListenerAndWraps() {
        val plan = ChunkPlan.plan(1_000_000, 450_000, 200_000, emptyList())
        assertEquals(listOf(Chunk(400_000, 600_000), Chunk(600_000, 800_000), Chunk(800_000, 1_000_000), Chunk(0, 200_000), Chunk(200_000, 400_000)), plan)
    }

    @Test fun planSkipsCoveredAndDropsSlivers() {
        val covered = listOf(Coverage.range(0, 300_000), Coverage.range(600_000, 800_000))
        assertEquals(listOf(Chunk(300_000, 600_000), Chunk(800_000, 900_000), Chunk(900_000, 1_000_500)),
            ChunkPlan.plan(1_000_500, 0, 300_000, covered))
        assertEquals(listOf(Chunk(300_000, 600_000)), ChunkPlan.plan(600_400, 0, 300_000, listOf(Coverage.range(0, 300_000))))
        assertTrue(ChunkPlan.plan(1_000, 0, 300_000, listOf(0..999)).isEmpty())
        assertTrue(ChunkPlan.plan(0, 0, 300_000, emptyList()).isEmpty())
    }

    @Test fun spansCrossTracks() {
        assertEquals(listOf(TrackSpan(0, 900, 1000), TrackSpan(1, 0, 2000), TrackSpan(2, 0, 100)),
            AudioSpans.of(listOf(1000, 2000, 500), 900, 3100))
        assertTrue(AudioSpans.of(listOf(1000), 2000, 3000).isEmpty())
    }

    private val book = setOf("the", "quick", "brown", "fox", "ежик")
    private fun spoken(n: Int, step: Int = 300, word: String = "the") = List(n) { SpokenWord(word, it * step) }

    @Test fun probeAcceptsOnlyAUsableEngine() {
        assertTrue(ProbeRules.accepts(spoken(20), book, 60_000, 10_000))
        assertFalse("too few words", ProbeRules.accepts(spoken(19), book, 60_000, 10_000))
        assertFalse("flat timestamps", ProbeRules.accepts(spoken(30, step = 0), book, 60_000, 10_000))
        assertFalse("wrong text", ProbeRules.accepts(spoken(30, word = "zzz"), book, 60_000, 10_000))
        assertFalse("too slow", ProbeRules.accepts(spoken(30), book, 60_000, 30_000))
    }

    @Test fun normalisationFoldsCaseAndYo() {
        assertEquals("ежик", normalizeWord("Ёжик,"))
        assertEquals("dont", normalizeWord("Don't"))
        assertEquals("", normalizeWord("—"))
    }

    @Test fun matchedRatioAndEarlyStop() {
        val ranges = listOf(SyncRange(0, 100, 0.9f, RangeKind.WORD), SyncRange(100, 400, 0f, RangeKind.NONE), SyncRange(400, 9000, 0f, RangeKind.APPROX))
        assertEquals(0.25f, Outcome.matchedRatio(ranges), 0.001f)
        assertEquals(0f, Outcome.matchedRatio(emptyList()), 0f)
        val hour = Outcome.EARLY_AFTER_MS
        assertTrue(Outcome.stopEarly(hour - 1, hour, 0.2f))
        assertFalse("still before the hour", Outcome.stopEarly(0, hour - 1, 0.2f))
        assertFalse("already judged", Outcome.stopEarly(hour, hour + 1, 0.2f))
        assertFalse("matching fine", Outcome.stopEarly(0, hour, 0.8f))
    }

    @Test fun languageMajority() {
        assertEquals("ru", majorityLanguage(listOf("ru-RU", null, "ru-RU")))
        assertEquals("en", majorityLanguage(listOf("en-US", "en-GB", "ru-RU")))
        assertNull(majorityLanguage(listOf(null, null)))
        assertNull(majorityLanguage(listOf("ru-RU", "en-US")))
        assertNull(majorityLanguage(listOf("de-DE")))
    }

    @Test fun voskJsonParses() {
        val words = parseVoskResult("""{"result":[{"conf":1.0,"end":1.02,"start":0.6,"word":"привет"},{"conf":0.9,"end":1.5,"start":1.05,"word":"мир"}],"text":"привет мир"}""")
        assertEquals(listOf(SpokenWord("привет", 600), SpokenWord("мир", 1050)), words)
        assertTrue(parseVoskResult("""{"text":""}""").isEmpty())
    }

    private fun zip(vararg names: String) = ByteArrayOutputStream().also { o ->
        ZipOutputStream(o).use { z -> names.forEach { z.putNextEntry(ZipEntry(it)); z.write(1); z.closeEntry() } }
    }.toByteArray()

    @Test fun unzipExtractsAndRejectsTraversal() {
        val dir = Files.createTempDirectory("unzip").toFile()
        ModelStore.unzipTo(zip("m/a.bin", "m/b/c.bin").inputStream(), File(dir, "ok"))
        assertTrue(File(dir, "ok/m/b/c.bin").isFile)
        assertThrows(IOException::class.java) { ModelStore.unzipTo(zip("../evil.bin").inputStream(), File(dir, "bad")) }
        assertFalse(File(dir, "evil.bin").exists())
        dir.deleteRecursively()
    }
}
