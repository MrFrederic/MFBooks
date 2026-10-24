package app.mfbooks.library

import org.junit.Assert.assertEquals
import org.junit.Test

class OverDriveMarkersTest {
    private val xml = "<Markers><Marker><Name>Chapter 1</Name><Time>0:00.000</Time></Marker>" +
        "<Marker><Name>Tom &amp; Jerry</Name><Time>12:34.567</Time></Marker>" +
        "<Marker><Name>Long</Name><Time>1:02:03.500</Time></Marker></Markers>"

    @Test fun parsesMarkersTxxx() {
        val txxx = id3Frame("TXXX", byteArrayOf(3) + "OverDrive MediaMarkers".toByteArray() + 0 + xml.toByteArray(), true)
        val ch = parseChapters(ByteArraySource(id3Tag(4, txxx)), "a.mp3")!!
        assertEquals(listOf(RawChapter("Chapter 1", 0), RawChapter("Tom & Jerry", 754_567), RawChapter("Long", 3_723_500)), ch)
    }

    @Test fun parsesUtf16Txxx() {
        val u16 = { s: String -> s.toByteArray(Charsets.UTF_16LE) }
        val bom = byteArrayOf(0xFF.toByte(), 0xFE.toByte())
        val data = byteArrayOf(1) + bom + u16("OverDrive MediaMarkers") + byteArrayOf(0, 0) + bom + u16(xml)
        val ch = parseChapters(ByteArraySource(id3Tag(3, id3Frame("TXXX", data, false))), "a.mp3")!!
        assertEquals(3, ch.size)
        assertEquals(754_567L, ch[1].startMs)
    }
}
