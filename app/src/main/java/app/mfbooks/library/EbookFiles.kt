package app.mfbooks.library

const val TEXT_MIRROR = ".mfbooks.text"
const val SYNC_MIRROR = ".mfbooks.sync"
const val TRANSCRIPT_MIRROR = ".mfbooks.transcript"

/** Read-along files the app keeps next to the audio. `.mfbooks.json` is not one: it changes on every pause. */
val TEXT_MIRRORS = setOf(TEXT_MIRROR, SYNC_MIRROR, TRANSCRIPT_MIRROR)

private const val MIN_TXT_BYTES = 50L * 1024
private val NOT_BOOKS = setOf("info.txt", "libby.txt")

/** A file name the text parser can read: `.fb2`, `.fb2.zip`, `.epub` or `.txt`. */
fun isEbookName(name: String): Boolean = name.lowercase().let { n ->
    n.endsWith(".fb2") || n.endsWith(".fb2.zip") || n.endsWith(".epub") || n.endsWith(".txt")
}

/** A file in a book folder worth offering as the book's text. */
fun isEbookCandidate(name: String, size: Long): Boolean {
    val n = name.lowercase()
    if (n.startsWith(".") || n in NOT_BOOKS || !isEbookName(n)) return false
    return !n.endsWith(".txt") || size >= MIN_TXT_BYTES
}
