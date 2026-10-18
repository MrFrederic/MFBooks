package app.mfbooks.convert

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConvertLogicTest {
    @Test fun eligibility() {
        val hour = 3_600_000L
        assertTrue(Eligibility.isEligible("01.mp3", 64_000L / 8 * 3600, hour))      // 64 kb/s MP3
        assertTrue(Eligibility.isEligible("Book.M4B", 128_000L / 8 * 3600, hour))   // AAC, case-insensitive
        assertFalse(Eligibility.isEligible("01.mp3", 48_000L / 8 * 3600, hour))     // ≤ 50 kb/s
        assertFalse(Eligibility.isEligible("01.opus", 128_000L / 8 * 3600, hour))
        assertFalse(Eligibility.isEligible("01.ogg", 128_000L / 8 * 3600, hour))
        assertFalse(Eligibility.isEligible("01.flac", 900_000L / 8 * 3600, hour))
        assertFalse(Eligibility.isEligible("01.mp3", 1000, 0))
        assertEquals(14_688_000L, Eligibility.estimatedOutBytes(hour)) // 4 kB/s × 3600 s × 1.02
    }

    @Test fun remapRenamesEverywhereAndKeepsUnknownKeys() {
        val doc = JSONObject("""{"v":1,"future":{"x":1},
            "progress":{"file":"02.mp3","positionMs":5},
            "files":[{"name":"01.mp3","size":1},{"name":"02.mp3","size":2,"chapters":[{"title":"A","startMs":0}],"converted":{"from":"02.mp3"}}],
            "bookmarks":[{"file":"02.mp3","positionMs":7},{"file":"01.mp3","positionMs":8}]}""")
        FileConverter.remap(doc, "02.mp3", "02.opus", 99, 1234)
        assertEquals("02.opus", doc.getJSONObject("progress").getString("file"))
        val f = doc.getJSONArray("files").getJSONObject(1)
        assertEquals("02.opus", f.getString("name")); assertEquals(99, f.getLong("size")); assertEquals(1234, f.getLong("mtime"))
        assertEquals("A", f.getJSONArray("chapters").getJSONObject(0).getString("title"))
        assertEquals("01.mp3", doc.getJSONArray("files").getJSONObject(0).getString("name"))
        assertEquals("02.opus", doc.getJSONArray("bookmarks").getJSONObject(0).getString("file"))
        assertEquals("01.mp3", doc.getJSONArray("bookmarks").getJSONObject(1).getString("file"))
        assertEquals(1, doc.getJSONObject("future").getInt("x"))
    }
}
