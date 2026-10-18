package app.mfbooks.playback

import app.mfbooks.data.AutoRewind
import app.mfbooks.data.Chapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BookPlaylistTest {
    // Two files: file 0 holds chapters 0 (0–60 s) and 1 (60–100 s); file 1 is chapter 2 (100–150 s).
    private val chapters = listOf(
        Chapter("b", 0, "One", trackIndex = 0, startInTrackMs = 0, durationMs = 60_000, startInBookMs = 0),
        Chapter("b", 1, "Two", trackIndex = 0, startInTrackMs = 60_000, durationMs = 40_000, startInBookMs = 60_000),
        Chapter("b", 2, "Three", trackIndex = 1, startInTrackMs = 0, durationMs = 50_000, startInBookMs = 100_000),
    )
    private val spans = buildSpans(chapters)

    @Test fun forwardAcrossChapterBoundary() = assertEquals(1 to 20_000L, seekByMs(spans, 0, 50_000, 30_000))

    @Test fun backwardAcrossChapterBoundary() = assertEquals(0 to 40_000L, seekByMs(spans, 1, 10_000, -30_000))

    @Test fun backwardAcrossFileBoundary() = assertEquals(1 to 35_000L, seekByMs(spans, 2, 5_000, -10_000))

    @Test fun clampedAtBookStart() = assertEquals(0 to 0L, seekByMs(spans, 0, 10_000, -30_000))

    @Test fun clampedAtBookEnd() = assertEquals(2 to 50_000L, seekByMs(spans, 2, 40_000, 30_000))

    @Test fun withinChapter() = assertEquals(0 to 40_000L, seekByMs(spans, 0, 10_000, 30_000))

    @Test fun positionInBookHelper() = assertEquals(125_000L, positionInBook(spans, 2, 25_000))

    @Test fun skipStartEndTrimsFileEdgesOnly() {
        val t = buildSpans(chapters, skipStartMs = 5_000, skipEndMs = 3_000)
        assertEquals(ItemSpan(5_000, 55_000, 0, 5_000), t[0])       // first in file 0: start trimmed
        assertEquals(ItemSpan(60_000, 37_000, 0, 60_000), t[1])     // last in file 0: end trimmed
        assertEquals(ItemSpan(105_000, 42_000, 1, 5_000), t[2])     // only chapter of file 1: both
        assertEquals(2 to 0L, seekByMs(t, 1, 36_000, 2_000))        // lands in the trimmed gap → next item start
    }

    @Test fun locateSavedTrackPosition() {
        assertEquals(1 to 15_000L, locateInTrack(spans, 0, 75_000))
        assertEquals(2 to 10_000L, locateInTrack(spans, 1, 10_000))
        assertEquals(0 to 0L, locateInTrack(spans, 9, 1_000))
    }

    @Test fun autoRewindAuto() {
        assertEquals(1_000L, autoRewindMs(200, AutoRewind.AUTO))
        assertEquals(9_295L, autoRewindMs(60_000, AutoRewind.AUTO))
        assertEquals(60_000L, autoRewindMs(3_600_000, AutoRewind.AUTO))
    }

    @Test fun autoRewindTable() {
        val cases = listOf(
            500L to (1 to 1), 9_999L to (1 to 2), 30_000L to (2 to 5), 90_000L to (5 to 10), 299_999L to (7 to 15),
            599_999L to (10 to 20), 1_199_999L to (12 to 25), 1_200_000L to (15 to 30), 86_400_000L to (15 to 30),
        )
        for ((pause, expected) in cases) {
            assertEquals("small @ $pause", expected.first * 1000L, autoRewindMs(pause, AutoRewind.SMALL))
            assertEquals("medium @ $pause", expected.second * 1000L, autoRewindMs(pause, AutoRewind.MEDIUM))
            assertEquals(0L, autoRewindMs(pause, AutoRewind.OFF))
        }
        assertEquals(1_000L, autoRewindMs(1_000 - 1, AutoRewind.MEDIUM))
        assertEquals(2_000L, autoRewindMs(1_000, AutoRewind.MEDIUM))
    }

    @Test fun actionMapping() {
        assertEquals(MappedAction(Command.SEEK_BY, 30_000), parseAction("FWD:30"))
        assertEquals(MappedAction(Command.SEEK_BY, -300_000), parseAction("REWIND:300"))
        assertEquals(MappedAction(Command.NEXT_CHAPTER), parseAction("NEXT_CHAPTER"))
        assertNull(parseAction("NOPE"))
    }

    @Test fun cycles() {
        assertEquals(1.25f, nextCycleSpeed(1f))
        assertEquals(0.75f, nextCycleSpeed(2f))
        assertEquals(1f, nextCycleSpeed(0.75f))
        assertEquals(SleepMode.Duration(15), nextSleepCycle(SleepMode.Off))
        assertEquals(SleepMode.EndOfChapter, nextSleepCycle(SleepMode.Duration(60)))
        assertEquals(SleepMode.Off, nextSleepCycle(SleepMode.EndOfChapter))
    }
}
