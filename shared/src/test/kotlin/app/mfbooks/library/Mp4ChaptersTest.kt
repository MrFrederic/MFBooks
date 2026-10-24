package app.mfbooks.library

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class Mp4ChaptersTest {
    private fun box(type: String, body: ByteArray = ByteArray(0)): ByteArray =
        ByteBuffer.allocate(8 + body.size).putInt(8 + body.size).put(type.toByteArray(Charsets.ISO_8859_1)).put(body).array()

    private fun cat(vararg parts: ByteArray) = ByteArrayOutputStream().also { o -> parts.forEach { o.write(it) } }.toByteArray()

    private fun chpl(vararg ch: Pair<String, Long>): ByteArray {
        val o = ByteArrayOutputStream()
        o.write(byteArrayOf(1, 0, 0, 0, 0, 0, 0, 0, ch.size.toByte()))
        for ((t, ms) in ch) {
            o.write(ByteBuffer.allocate(8).putLong(ms * 10_000).array())
            val b = t.toByteArray()
            o.write(b.size); o.write(b)
        }
        return box("chpl", o.toByteArray())
    }

    @Test fun readsNeroChpl() {
        val file = cat(
            box("ftyp", "M4B ".toByteArray() + ByteArray(8)),
            box("mdat", ByteArray(100)),
            box("moov", box("mvhd", ByteArray(100)) + box("udta", chpl("Intro" to 0, "Chäpter 2" to 61_500, "Three" to 3_600_250))),
        )
        val ch = parseChapters(ByteArraySource(file), "x.m4b")
        assertEquals(listOf(RawChapter("Intro", 0), RawChapter("Chäpter 2", 61_500), RawChapter("Three", 3_600_250)), ch)
    }

    @Test fun noChaptersIsNull() {
        val file = cat(box("ftyp", ByteArray(12)), box("moov", box("mvhd", ByteArray(100))))
        assertNull(parseChapters(ByteArraySource(file), "x.m4a"))
    }

    @Test fun readsIlstTags() {
        fun item(type: String, text: String) = box(type, box("data", byteArrayOf(0, 0, 0, 1, 0, 0, 0, 0) + text.toByteArray()))
        val ilst = box("ilst", item("©nrt", "Ray Porter") + item("©des", "Long description here") + item("©too", "inAudible 1.0"))
        val meta = box("meta", byteArrayOf(0, 0, 0, 0) + box("hdlr", ByteArray(20)) + ilst)
        val file = cat(box("ftyp", ByteArray(12)), box("moov", box("udta", meta)))
        val t = parseExtraTags(ByteArraySource(file), "x.m4b")!!
        assertEquals("Ray Porter", t.narrator)
        assertEquals("Long description here", t.description)
        assertEquals("inAudible 1.0", t.encoder)
    }
}
