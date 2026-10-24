package app.mfbooks.library

import org.junit.Assert.assertEquals
import org.junit.Test

class CueParserTest {
    @Test fun framesAreOneSeventyFifth() {
        val s = parseCue("""
            PERFORMER "Someone"
            TITLE "Album"
            FILE "a.mp3" MP3
              TRACK 01 AUDIO
                TITLE "One"
                INDEX 01 00:00:00
              TRACK 02 AUDIO
                TITLE "Two"
                INDEX 00 04:59:00
                INDEX 01 05:00:37
        """.trimIndent())
        assertEquals("Someone", s.performer)
        assertEquals("Album", s.title)
        assertEquals(1, s.files.size)
        assertEquals("a.mp3", s.files[0].name)
        assertEquals(listOf(CueTrack("One", null, 0), CueTrack("Two", null, 300_493)), s.files[0].tracks)
    }

    @Test fun hundredthsAndLooseFields() {
        val s = parseCue("﻿FILE \"x.m4b\" WAVE\r\nTRACK 1 AUDIO\r\nTITLE A\r\nINDEX 01 0:0:0\r\nTRACK 2 AUDIO\r\nTITLE \"B\"\r\nINDEX 01 122:4:93\r\n")
        assertEquals(listOf(0L, (122 * 60 + 4) * 1000L + 930), s.files[0].tracks.map { it.startMs })
        assertEquals("B", s.files[0].tracks[1].title)
    }
}
