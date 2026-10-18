package app.mfbooks.library

import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Test

class VorbisChaptersTest {
    private fun le(n: Int) = byteArrayOf(n.toByte(), (n shr 8).toByte(), (n shr 16).toByte(), (n shr 24).toByte())
    private fun comments(vararg kv: String): ByteArray {
        val o = ByteArrayOutputStream()
        val vendor = "test".toByteArray()
        o.write(le(vendor.size)); o.write(vendor); o.write(le(kv.size))
        for (s in kv) { val b = s.toByteArray(); o.write(le(b.size)); o.write(b) }
        return o.toByteArray()
    }
    private val kv = arrayOf(
        "TITLE=Hello", "NARRATOR=Some One", "CHAPTER001=00:00:00.000", "CHAPTER001NAME=Intro",
        "CHAPTER002=01:02:03.250", "CHAPTER002NAME=Second", "PAD=" + "x".repeat(600),
    )
    private val expected = listOf(RawChapter("Intro", 0), RawChapter("Second", 3_723_250))

    @Test fun flac() {
        val vc = comments(*kv)
        val flac = "fLaC".toByteArray() + byteArrayOf(0x00, 0, 0, 4, 0, 0, 0, 0) + // STREAMINFO stub (4 bytes)
            byteArrayOf(0x84.toByte(), 0, (vc.size shr 8).toByte(), vc.size.toByte()) + vc
        assertEquals(expected, parseChapters(ByteArraySource(flac), "a.flac"))
        assertEquals("Some One", parseExtraTags(ByteArraySource(flac), "a.flac")!!.narrator)
    }

    private fun page(seq: Int, packet: ByteArray, continued: Boolean = false, last: Boolean = true): ByteArray {
        val segs = ArrayList<Int>()
        var n = packet.size
        while (n >= 255) { segs += 255; n -= 255 }
        if (last) segs += n
        return "OggS".toByteArray() + byteArrayOf(0, if (continued) 1 else 0) + ByteArray(8) + le(1) + le(seq) + ByteArray(4) +
            byteArrayOf(segs.size.toByte()) + ByteArray(segs.size) { segs[it].toByte() } + packet
    }

    @Test fun opusAcrossPages() {
        val tags = "OpusTags".toByteArray() + comments(*kv)
        val head = "OpusHead".toByteArray() + ByteArray(11)
        val cut = 255 * 2 // first comment page ends exactly on a 255 lacing run → packet continues
        val ogg = page(0, head) + page(1, tags.copyOf(cut), last = false) + page(2, tags.copyOfRange(cut, tags.size), continued = true)
        assertEquals(expected, parseChapters(ByteArraySource(ogg), "a.opus"))
    }
}
