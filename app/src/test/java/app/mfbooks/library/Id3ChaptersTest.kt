package app.mfbooks.library

import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Test

internal fun id3Frame(id: String, data: ByteArray, v4: Boolean): ByteArray {
    val o = ByteArrayOutputStream()
    o.write(id.toByteArray())
    val n = data.size
    if (v4) o.write(byteArrayOf(((n shr 21) and 0x7F).toByte(), ((n shr 14) and 0x7F).toByte(), ((n shr 7) and 0x7F).toByte(), (n and 0x7F).toByte()))
    else o.write(byteArrayOf((n shr 24).toByte(), (n shr 16).toByte(), (n shr 8).toByte(), n.toByte()))
    o.write(byteArrayOf(0, 0)); o.write(data)
    return o.toByteArray()
}

internal fun id3Tag(version: Int, vararg frames: ByteArray): ByteArray {
    val body = frames.fold(ByteArray(0)) { a, b -> a + b }
    val n = body.size
    return "ID3".toByteArray() + byteArrayOf(version.toByte(), 0, 0, ((n shr 21) and 0x7F).toByte(), ((n shr 14) and 0x7F).toByte(), ((n shr 7) and 0x7F).toByte(), (n and 0x7F).toByte()) + body +
        ByteArray(64) // fake audio
}

internal fun text(s: String) = byteArrayOf(3) + s.toByteArray()

class Id3ChaptersTest {
    private fun chap(id: String, start: Long, title: String, v4: Boolean): ByteArray {
        fun u32(v: Long) = byteArrayOf((v shr 24).toByte(), (v shr 16).toByte(), (v shr 8).toByte(), v.toByte())
        val data = id.toByteArray() + 0 + u32(start) + u32(start + 1000) + u32(-1) + u32(-1) + id3Frame("TIT2", text(title), v4)
        return id3Frame("CHAP", data, v4)
    }

    @Test fun readsChapV24() {
        val f = id3Tag(4, id3Frame("TIT2", text("Book"), true), chap("c2", 90_000, "Second", true), chap("c1", 0, "First", true))
        assertEquals(listOf(RawChapter("First", 0), RawChapter("Second", 90_000)), parseChapters(ByteArraySource(f), "a.mp3"))
    }

    @Test fun readsChapV23() {
        val f = id3Tag(3, chap("c1", 0, "Prólogo", false), chap("c2", 300_500, "Uno", false))
        assertEquals(listOf(RawChapter("Prólogo", 0), RawChapter("Uno", 300_500)), parseChapters(ByteArraySource(f), "a.mp3"))
    }

    @Test fun readsExtraTags() {
        fun txxx(d: String, v: String) = id3Frame("TXXX", byteArrayOf(3) + d.toByteArray() + 0 + v.toByteArray(), true)
        val f = id3Tag(4, txxx("NARRATOR", "Jane Doe"), id3Frame("TSSE", text("Lavf58"), true), id3Frame("TCOM", text("Composer X"), true))
        val t = parseExtraTags(ByteArraySource(f), "a.mp3")!!
        assertEquals("Jane Doe", t.narrator)
        assertEquals("Lavf58", t.encoder)
        assertEquals("Composer X", t.composer)
    }
}
