package app.mfbooks.text

import android.net.Uri
import app.mfbooks.MFBooksApp
import java.io.File

/** A file picked for a book, parsed but not saved yet, shown in the review sheet. */
class ParsedText(val fileName: String, val bytes: ByteArray, val doc: BookTextDoc)

/** Owns a book's read-along files: filesDir/text/<bookId>/, mirrored into the book folder when writable. */
class TextStore(private val app: MFBooksApp) {
    fun dir(bookId: String): File = File(app.filesDir, "text/$bookId").apply { mkdirs() }
    fun sourceFile(bookId: String): File? = dir(bookId).listFiles()?.firstOrNull { it.name.startsWith("source.") }
    fun textFile(bookId: String) = File(dir(bookId), "text.json.gz")
    fun syncFile(bookId: String) = File(dir(bookId), "sync.json.gz")
    fun transcriptFile(bookId: String) = File(dir(bookId), "transcript.gz")

    /** Read and parse without saving. Throws [TextParseException]. */
    suspend fun parse(uri: Uri): ParsedText = TODO("import agent")

    /** Save the text, build the approximate map, create the [app.mfbooks.data.BookText] row in state PARSED. Replaces earlier text, keeps the transcript. */
    suspend fun save(bookId: String, parsed: ParsedText, lang: String): Unit = TODO("import agent")

    suspend fun readText(bookId: String): BookTextDoc? = TODO("import agent")
    suspend fun readSync(bookId: String): SyncDoc? = TODO("import agent")

    /** Replace the map, update the row's tier, coverage and syncSha, mirror to the book folder. */
    suspend fun writeSync(bookId: String, doc: SyncDoc, syncedAudioMs: Long): Unit = TODO("import agent")

    /** Mirror the transcript to the book folder after a checkpoint. */
    suspend fun mirrorTranscript(bookId: String): Unit = TODO("import agent")

    /** Bytes that arrived from the server. Keeps the row consistent; never rebuilds [textSha]. */
    suspend fun storeFromServer(bookId: String, textGz: ByteArray?, syncGz: ByteArray?, sourceName: String?): Unit = TODO("import agent")

    /** Delete the files and the row. Never touches audio or the original file in the book folder. */
    suspend fun remove(bookId: String): Unit = TODO("import agent")

    /** After a scan: pick up `.mfbooks.text` sidecars for books without a row, and mark rows AUDIO_CHANGED when tracks no longer match. */
    suspend fun reconcile(bookId: String): Unit = TODO("import agent")
}
