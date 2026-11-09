package app.mfbooks.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class SyncMapTest {
    private val tracks = listOf(SyncTrack("1", 10_000), SyncTrack("2", 10_000))

    /** Knots at chars 0, 100, 200, 300 and ms 0, 5000, 15000, 19000: the middle two sit in different tracks. */
    private val doc = SyncDoc(
        textSha = "x", tier = SyncTier.ALIGNED, tracks = tracks,
        chars = listOf(0, 100, 100, 100), ms = listOf(0, 5000, 10_000, 4000),
        ranges = listOf(SyncRange(0, 100, 1f), SyncRange(100, 200, 0f, RangeKind.NONE), SyncRange(200, 400, 0.5f)),
    )

    @Test fun lookupInterpolates() {
        val map = SyncMap.of(doc, tracks)!!
        assertEquals(SyncPosition(50, RangeKind.WORD), map.at(2500))
        assertEquals(SyncPosition(150, RangeKind.NONE), map.at(10_000))
        assertEquals(SyncPosition(100, RangeKind.NONE), map.at(5000))
        assertEquals(2500, map.msAt(50))
        assertEquals(0, map.msAt(-5))
        assertEquals(19_000, map.msAt(1000))
    }

    @Test fun outsideTheKnotsIsUnsynced() {
        val late = doc.copy(chars = listOf(10, 10), ms = listOf(1000, 1000), ranges = listOf(SyncRange(0, 400, 1f)))
        val map = SyncMap.of(late, tracks)!!
        assertEquals(SyncPosition(10, RangeKind.NONE), map.at(500))
        assertEquals(SyncPosition(20, RangeKind.NONE), map.at(5000))
        assertEquals(SyncPosition(10, RangeKind.WORD), map.at(1000))
    }

    @Test fun rebasesPerTrack() {
        val current = listOf(SyncTrack("1", 9_500), SyncTrack("2", 10_400))
        val map = SyncMap.of(doc, current)!!
        // Track 1's knots keep their ms; track 2's move with its start.
        assertEquals(5000, map.msAt(100))
        assertEquals(14_500, map.msAt(200))
        assertEquals(18_500, map.msAt(300))
    }

    @Test fun rebaseKeepsKnotsStrictlyIncreasing() {
        val edge = doc.copy(chars = listOf(0, 100, 100), ms = listOf(9_900, 10, 200), ranges = emptyList())
        val map = SyncMap.of(edge, listOf(SyncTrack("1", 8_600), SyncTrack("2", 10_000)))
        assertNotNull(map)
        assertEquals(SyncPosition(0, RangeKind.APPROX), map!!.at(9_900))
    }

    @Test fun rejectsDifferentAudio() {
        assertNull(SyncMap.of(doc, tracks.take(1)))
        assertNull(SyncMap.of(doc, listOf(SyncTrack("1", 10_000), SyncTrack("2", 11_501))))
        assertNotNull(SyncMap.of(doc, listOf(SyncTrack("renamed", 10_000), SyncTrack("2", 11_500))))
    }

    @Test fun coverageIsTheShareOfAudioInWordRanges() {
        assertEquals(0.45f, doc.coverage(), 0.001f) // 5 s + 4 s of 20 s
        assertEquals(0f, doc.copy(ranges = emptyList()).coverage(), 0f)
    }

    @Test fun emptyMapIsSafe() {
        val map = SyncMap.of(doc.copy(chars = emptyList(), ms = emptyList(), ranges = emptyList()), tracks)!!
        assertEquals(SyncPosition(0, RangeKind.NONE), map.at(100))
        assertEquals(0, map.msAt(100))
    }
}
