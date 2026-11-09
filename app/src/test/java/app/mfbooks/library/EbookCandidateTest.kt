package app.mfbooks.library

import app.mfbooks.ui.text.sameBook
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EbookCandidateTest {
    @Test fun `ebook extensions are candidates`() {
        for (n in listOf("Book.fb2", "BOOK.EPUB", "book.fb2.zip")) assertTrue(n, isEbookCandidate(n, 1))
    }

    @Test fun `txt needs 50 KB and known names never count`() {
        assertFalse(isEbookCandidate("book.txt", 50L * 1024 - 1))
        assertTrue(isEbookCandidate("book.txt", 50L * 1024))
        assertFalse(isEbookCandidate("Info.TXT", 1_000_000))
        assertFalse(isEbookCandidate("libby.txt", 1_000_000))
    }

    @Test fun `other files and hidden copies are not candidates`() {
        for (n in listOf("book.zip", "cover.jpg", ".mfbooks.text", "._book.epub", "book.m4b")) assertFalse(n, isEbookCandidate(n, 1_000_000))
    }

    @Test fun `titles compare loosely and authors by a shared name`() {
        assertTrue(sameBook("The Amber Coast: A Novel", "Vance, Ursula", "The Amber Coast", "Ursula Vance"))
        assertFalse(sameBook("The Amber Coast", "Ursula Vance", "Dune", "Ursula Vance"))
        assertFalse(sameBook("Dune", "Frank Herbert", "Dune", "Ursula Vance"))
        assertTrue(sameBook(null, null, "Dune", "Frank Herbert"))
    }
}
