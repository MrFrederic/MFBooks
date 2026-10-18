package app.mfbooks.convert

import java.io.OutputStream

/**
 * Minimal Ogg Opus muxer (RFC 7845). Replaces MediaMuxer's OGG output, which puts every 20 ms packet in its own page:
 * that's ~11 kb/s of page headers on a 32 kb/s stream (+34 %). Here pages carry up to 255 segments / ~1 s.
 * Also writes real OpusTags (tags + CHAPTERxxx Vorbis chapters), which MediaMuxer can't.
 */
class OggOpusWriter(private val out: OutputStream, private val serial: Int = 0x4D46424B /* "MFBK" */) {
    private var seq = 0
    private var granule = 0L
    private var preSkip = 0
    private val segs = ArrayList<Int>()
    private val body = java.io.ByteArrayOutputStream()
    private var pageGranule = 0L

    /** [opusHead] = the encoder's csd-0 (starts with "OpusHead"). */
    fun writeHeaders(opusHead: ByteArray, comments: List<Pair<String, String>>, vendor: String = "MFBooks") {
        require(opusHead.size >= 19 && String(opusHead, 0, 8, Charsets.US_ASCII) == "OpusHead") { "not an OpusHead" }
        preSkip = (opusHead[10].toInt() and 0xFF) or ((opusHead[11].toInt() and 0xFF) shl 8)
        writePage(lace(opusHead.size), opusHead, 0, flags = 0x02)
        val tags = java.io.ByteArrayOutputStream().apply {
            write("OpusTags".toByteArray())
            val v = vendor.toByteArray(); le32(v.size); write(v)
            le32(comments.size)
            for ((k, value) in comments) { val c = "$k=$value".toByteArray(); le32(c.size); write(c) }
        }.toByteArray()
        // A comment packet can exceed one page (255 × 255 bytes); split it over continuation pages.
        var off = 0; var first = true
        while (true) {
            val chunk = minOf(255 * 255, tags.size - off)
            val last = off + chunk == tags.size && chunk < 255 * 255
            val lacing = if (last) lace(chunk) else List(255) { 255 }
            writePage(lacing, tags.copyOfRange(off, off + chunk), 0, flags = if (first) 0 else 0x01)
            off += chunk; first = false
            if (last) break
            if (off == tags.size) { writePage(listOf(0), ByteArray(0), 0, flags = 0x01); break }
        }
        granule = preSkip.toLong()
    }

    /** One encoded packet; samples are derived from its TOC byte. */
    fun writePacket(packet: ByteArray) {
        val l = lace(packet.size)
        if (segs.size + l.size > 255 || body.size() > 8192 || granule - pageGranule >= 48_000) flush(0)
        segs += l; body.write(packet)
        granule += packetSamples(packet)
    }

    /** Last page gets EOS; [totalSamples] (48 kHz, excluding pre-skip) trims the encoder's padding exactly. */
    fun finish(totalSamples: Long) {
        if (totalSamples > 0) granule = minOf(granule, preSkip + totalSamples)
        flush(0x04)
        out.flush()
    }

    private fun flush(flags: Int) {
        if (segs.isEmpty() && flags and 0x04 == 0) return
        writePage(segs.toList(), body.toByteArray(), granule, flags)
        segs.clear(); body.reset(); pageGranule = granule
    }

    private fun lace(n: Int) = List(n / 255) { 255 } + (n % 255)

    private fun writePage(lacing: List<Int>, data: ByteArray, granulePos: Long, flags: Int) {
        val h = ByteArray(27 + lacing.size)
        "OggS".toByteArray().copyInto(h)
        h[4] = 0; h[5] = flags.toByte()
        for (i in 0 until 8) h[6 + i] = (granulePos ushr (8 * i)).toByte()
        for (i in 0 until 4) h[14 + i] = (serial ushr (8 * i)).toByte()
        for (i in 0 until 4) h[18 + i] = (seq ushr (8 * i)).toByte()
        h[26] = lacing.size.toByte()
        lacing.forEachIndexed { i, v -> h[27 + i] = v.toByte() }
        val crc = crc(crc(0, h), data)
        for (i in 0 until 4) h[22 + i] = (crc ushr (8 * i)).toByte()
        out.write(h); out.write(data)
        seq++
    }

    private fun java.io.ByteArrayOutputStream.le32(v: Int) { for (i in 0 until 4) write(v ushr (8 * i) and 0xFF) }

    companion object {
        private val CRC = IntArray(256) { i ->
            var r = i shl 24
            repeat(8) { r = if (r and 0x80000000.toInt() != 0) (r shl 1) xor 0x04C11DB7 else r shl 1 }
            r
        }

        fun crc(start: Int, b: ByteArray): Int {
            var c = start
            for (x in b) c = (c shl 8) xor CRC[((c ushr 24) xor (x.toInt() and 0xFF)) and 0xFF]
            return c
        }

        /** Samples (at 48 kHz) in one Opus packet, from the TOC byte (RFC 6716 §3.1). */
        fun packetSamples(p: ByteArray): Int {
            if (p.isEmpty()) return 0
            val toc = p[0].toInt() and 0xFF
            val cfg = toc shr 3
            val frame = when {
                cfg < 12 -> intArrayOf(480, 960, 1920, 2880)[cfg and 3]
                cfg < 16 -> if (cfg and 1 == 0) 480 else 960
                else -> intArrayOf(120, 240, 480, 960)[cfg and 3]
            }
            val count = when (toc and 3) { 0 -> 1; 1, 2 -> 2; else -> if (p.size > 1) p[1].toInt() and 0x3F else 0 }
            return frame * count
        }

        /** Vorbis-comment chapter tags: CHAPTER001=00:01:02.345, CHAPTER001NAME=Title. */
        fun chapterComments(chapters: List<Pair<String, Long>>): List<Pair<String, String>> = chapters.flatMapIndexed { i, (title, ms) ->
            val n = "%03d".format(i + 1)
            listOf("CHAPTER$n" to "%02d:%02d:%02d.%03d".format(ms / 3_600_000, ms / 60_000 % 60, ms / 1000 % 60, ms % 1000), "CHAPTER${n}NAME" to title)
        }
    }
}
