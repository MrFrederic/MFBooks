package app.mfbooks.library

import org.junit.Assert.assertEquals
import org.junit.Test

class NaturalOrderTest {
    @Test fun sortsNumbersNumerically() {
        val l = listOf("10.mp3", "2.mp3", "1.mp3", "Chapter 02.mp3", "chapter 1.mp3", "chapter 10.mp3")
        assertEquals(listOf("1.mp3", "2.mp3", "10.mp3", "chapter 1.mp3", "Chapter 02.mp3", "chapter 10.mp3"), l.sortedWith(NaturalOrder))
    }
}
