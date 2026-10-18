package app.mfbooks.convert

import app.mfbooks.library.ByteArraySource
import app.mfbooks.library.RawChapter
import app.mfbooks.library.readVorbisComments
import app.mfbooks.library.vorbisChapters
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class OggOpusWriterTest {
    private val head = byteArrayOf(*"OpusHead".toByteArray(), 1, 2, 0x38, 0x01, 0x80.toByte(), 0xBB.toByte(), 0, 0, 0, 0, 0) // pre-skip 312, 48 kHz

    private class Page(val flags: Int, val granule: Long, val seq: Int, val segments: Int, val crcOk: Boolean)

    private fun pages(b: ByteArray): List<Page> {
        val out = ArrayList<Page>(); var p = 0
        while (p < b.size) {
            assertEquals("OggS", String(b, p, 4))
            val n = b[p + 26].toInt() and 0xFF
            val len = (0 until n).sumOf { b[p + 27 + it].toInt() and 0xFF }
            val page = b.copyOfRange(p, p + 27 + n + len)
            val stored = (0 until 4).sumOf { (page[22 + it].toInt() and 0xFF) shl (8 * it) }
            for (i in 22 until 26) page[i] = 0
            val g = (0 until 8).fold(0L) { acc, i -> acc or ((b[p + 6 + i].toLong() and 0xFF) shl (8 * i)) }
            val seq = (0 until 4).sumOf { (b[p + 18 + it].toInt() and 0xFF) shl (8 * it) }
            out += Page(b[p + 5].toInt(), g, seq, n, OggOpusWriter.crc(0, page) == stored)
            p += page.size
        }
        return out
    }

    @Test fun pagesTagsAndGranules() {
        val bos = ByteArrayOutputStream()
        val w = OggOpusWriter(bos)
        val chapters = listOf("Opening" to 0L, "Middle" to 20_000L, "End" to 3_725_045L)
        w.writeHeaders(head, listOf("TITLE" to "Part 1", "ARTIST" to "Ana") + OggOpusWriter.chapterComments(chapters))
        val packet = ByteArray(80).also { it[0] = (31 shl 3).toByte() } // CELT 20 ms, one frame
        repeat(3000) { w.writePacket(packet) }                          // 60 s
        w.finish(60L * 48_000)
        val bytes = bos.toByteArray()
        val ps = pages(bytes)
        assertTrue(ps.all { it.crcOk })
        assertEquals(0x02, ps[0].flags)                       // BOS, OpusHead alone
        assertEquals(0x04, ps.last().flags and 0x04)          // EOS
        assertEquals((0 until ps.size).toList(), ps.map { it.seq })
        assertEquals(312L + 60 * 48_000, ps.last().granule)   // exact duration after pre-skip
        assertTrue("pages: ${ps.size}", ps.size < 3000 / 40)  // many packets per page, unlike MediaMuxer
        val comments = readVorbisComments(ByteArraySource(bytes), bytes.copyOf(16))!!
        assertEquals("Part 1", comments.first { it.first == "TITLE" }.second)
        assertEquals(listOf(RawChapter("Opening", 0), RawChapter("Middle", 20_000), RawChapter("End", 3_725_045)), vorbisChapters(comments))
    }

    @Test fun packetSamplesFromToc() {
        fun s(toc: Int, vararg rest: Int) = OggOpusWriter.packetSamples(byteArrayOf(toc.toByte(), *rest.map { it.toByte() }.toByteArray()))
        assertEquals(960, s(31 shl 3))            // CELT FB 20 ms
        assertEquals(480, s(30 shl 3))            // CELT 10 ms
        assertEquals(2880, s((3 shl 3)))          // SILK 60 ms
        assertEquals(1920, s((31 shl 3) or 1))    // two frames
        assertEquals(960 * 3, s((31 shl 3) or 3, 3))
    }
}
