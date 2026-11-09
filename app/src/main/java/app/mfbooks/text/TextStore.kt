package app.mfbooks.text

import android.net.Uri
import android.provider.DocumentsContract
import app.mfbooks.MFBooksApp
import app.mfbooks.data.BookText
import app.mfbooks.data.TextState
import app.mfbooks.library.DocEntry
import app.mfbooks.library.SYNC_MIRROR
import app.mfbooks.library.Sidecar
import app.mfbooks.library.TEXT_MIRROR
import app.mfbooks.library.TEXT_MIRRORS
import app.mfbooks.library.TRANSCRIPT_MIRROR
import app.mfbooks.library.children
import app.mfbooks.library.displayName
import app.mfbooks.library.isEbookCandidate
import app.mfbooks.library.isEbookName
import app.mfbooks.library.readBytes
import app.mfbooks.text.TextParseException.Reason
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/** A file picked for a book, parsed but not saved yet, shown in the review sheet. */
class ParsedText(val fileName: String, val bytes: ByteArray, val doc: BookTextDoc)

/** Whitespace-separated words, images excluded. */
fun BookTextDoc.wordCount(): Int = blocks.filter { it.k != BlockKind.IMAGE }.sumOf { b ->
    var n = 0; var inWord = false
    for (c in b.s) { val w = !c.isWhitespace(); if (w && !inWord) n++; inWord = w }
    n
}

/** Owns a book's read-along files: filesDir/text/<bookId>/, mirrored into the book folder when writable. */
class TextStore(private val app: MFBooksApp) {
    private val dao get() = app.db.text()
    private val lib get() = app.db.library()
    private val locks = ConcurrentHashMap<String, Mutex>()

    fun dir(bookId: String): File = File(app.filesDir, "text/$bookId").apply { mkdirs() }
    fun sourceFile(bookId: String): File? = dir(bookId).listFiles()?.firstOrNull { it.name.startsWith("source.") }
    fun textFile(bookId: String) = File(dir(bookId), "text.json.gz")
    fun syncFile(bookId: String) = File(dir(bookId), "sync.json.gz")
    fun transcriptFile(bookId: String) = File(dir(bookId), "transcript.gz")

    private suspend fun <T> locked(bookId: String, block: suspend () -> T): T =
        withContext(Dispatchers.IO) { locks.getOrPut(bookId) { Mutex() }.withLock { block() } }

    /** Read and parse without saving. Throws [TextParseException]. */
    suspend fun parse(uri: Uri): ParsedText = withContext(Dispatchers.IO) {
        val name = app.displayName(uri) ?: uri.lastPathSegment?.substringAfterLast('/') ?: "book"
        if (!isEbookName(name)) throw TextParseException(Reason.UNSUPPORTED, "Not an FB2, EPUB or TXT file")
        val bytes = readCapped(uri)
        ParsedText(name, bytes, TextParser.parse(name, bytes))
    }

    private fun readCapped(uri: Uri): ByteArray = try {
        app.contentResolver.openInputStream(uri)?.use { input ->
            val out = ByteArrayOutputStream()
            val buf = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
                if (out.size() > TextParser.MAX_FILE_BYTES) throw TextParseException(Reason.TOO_LARGE, "Over the 64 MB limit")
            }
            out.toByteArray()
        } ?: throw TextParseException(Reason.EMPTY, "Unreadable file")
    } catch (e: IOException) { throw TextParseException(Reason.EMPTY, "Unreadable file") }
    catch (e: SecurityException) { throw TextParseException(Reason.EMPTY, "Unreadable file") }

    /** Save the text, build the approximate map, create the [app.mfbooks.data.BookText] row in state PARSED. Replaces earlier text, keeps the transcript. */
    suspend fun save(bookId: String, parsed: ParsedText, lang: String): Unit = locked(bookId) {
        val doc = parsed.doc.copy(lang = lang) // ponytail: carries the choice through a reinstall; later changes live in the row only
        val gz = doc.toGz()
        val sha = sha256Hex(gz)
        val dir = dir(bookId)
        dir.listFiles()?.filter { it.name.startsWith("source.") }?.forEach { it.delete() }
        val ext = if (parsed.fileName.endsWith(".fb2.zip", true)) "fb2.zip" else parsed.fileName.substringAfterLast('.').lowercase()
        File(dir, "source.$ext").writeAtomic(parsed.bytes)
        textFile(bookId).writeAtomic(gz)
        val syncGz = approximate(bookId, doc, sha).toGz()
        syncFile(bookId).writeAtomic(syncGz)
        dao.upsert(BookText(
            bookId, parsed.fileName, doc.source.format, lang, sha, doc.wordCount(), TextState.PARSED, SyncTier.APPROXIMATE,
            coverage = 0f, syncedAudioMs = 0, syncSha = sha256Hex(syncGz), updatedAt = System.currentTimeMillis(),
        ))
        mirror(bookId, textFile(bookId) to TEXT_MIRROR, syncFile(bookId) to SYNC_MIRROR)
    }

    suspend fun readText(bookId: String): BookTextDoc? = withContext(Dispatchers.IO) { decode(textFile(bookId), ::textDocFromGz) }
    suspend fun readSync(bookId: String): SyncDoc? = withContext(Dispatchers.IO) { decode(syncFile(bookId), ::syncDocFromGz) }

    private fun <T> decode(f: File, parse: (ByteArray) -> T): T? = if (f.exists()) runCatching { parse(f.readBytes()) }.getOrNull() else null

    /** Replace the map, update the row's tier, coverage and syncSha, mirror to the book folder. */
    suspend fun writeSync(bookId: String, doc: SyncDoc, syncedAudioMs: Long): Unit = locked(bookId) {
        val gz = doc.toGz()
        syncFile(bookId).writeAtomic(gz)
        dao.setSync(bookId, doc.tier, doc.coverage(), syncedAudioMs, sha256Hex(gz), doc.engine, System.currentTimeMillis())
        mirror(bookId, syncFile(bookId) to SYNC_MIRROR)
    }

    /** Mirror the transcript to the book folder after a checkpoint. */
    suspend fun mirrorTranscript(bookId: String): Unit = locked(bookId) { mirror(bookId, transcriptFile(bookId) to TRANSCRIPT_MIRROR) }

    /** Bytes that arrived from the server. Keeps the row consistent; never rebuilds [textSha]. */
    suspend fun storeFromServer(bookId: String, textGz: ByteArray?, syncGz: ByteArray?, sourceName: String?): Unit = locked(bookId) {
        val old = dao.text(bookId)
        if (textGz == null && old == null) return@locked
        val doc = textGz?.let(::textDocFromGz)
        val sha = textGz?.let(::sha256Hex) ?: old!!.textSha
        val incoming = syncGz?.let(::syncDocFromGz)?.takeIf { it.textSha == sha }
        if (doc != null) {
            if (old?.textSha == sha && incoming == null) return@locked // already have this text and its map
            val sync = incoming ?: approximate(bookId, doc, sha)
            val bytes = if (incoming != null) syncGz else sync.toGz()
            textFile(bookId).writeAtomic(textGz)
            syncFile(bookId).writeAtomic(bytes)
            dao.upsert(row(bookId, doc, sha, sync, bytes, sourceName ?: old?.sourceName ?: doc.source.name, old))
            mirror(bookId, textFile(bookId) to TEXT_MIRROR, syncFile(bookId) to SYNC_MIRROR)
        } else if (incoming != null) {
            syncFile(bookId).writeAtomic(syncGz)
            val now = System.currentTimeMillis()
            dao.setSync(bookId, incoming.tier, incoming.coverage(), old!!.syncedAudioMs, sha256Hex(syncGz), incoming.engine, now)
            if (old.state !in ACTIVE) dao.setState(bookId, stateOf(incoming, currentTracks(bookId)), null, now)
            mirror(bookId, syncFile(bookId) to SYNC_MIRROR)
        }
    }

    /** Delete the files and the row. Never touches audio or the original file in the book folder. */
    suspend fun remove(bookId: String): Unit = locked(bookId) {
        try {
            folder(bookId)?.second?.filter { it.name.removeSuffix(".tmp") in TEXT_MIRRORS }
                ?.forEach { DocumentsContract.deleteDocument(app.contentResolver, it.uri) }
        } catch (e: CancellationException) { throw e } catch (e: Exception) { /* read-only tree: the mirrors stay */ }
        forgetFiles(bookId)
    }

    /** The book is gone from the library: drop the local copy only, its folder (if any) has moved or vanished. */
    suspend fun forget(bookId: String): Unit = locked(bookId) { forgetFiles(bookId) }

    private suspend fun forgetFiles(bookId: String) {
        File(app.filesDir, "text/$bookId").deleteRecursively()
        dao.delete(bookId)
    }

    /** After a scan: pick up `.mfbooks.text` sidecars for books without a row, and mark rows AUDIO_CHANGED when tracks no longer match. */
    suspend fun reconcile(bookId: String): Unit = locked(bookId) {
        val row = dao.text(bookId) ?: return@locked restoreFromFolder(bookId)
        val sync = decode(syncFile(bookId), ::syncDocFromGz) ?: return@locked
        val tracks = currentTracks(bookId)
        val matches = SyncMap.of(sync, tracks) != null
        // ponytail: the old map stays on disk, so a card that was briefly unreadable doesn't cost hours of matching
        if (!matches && row.state != TextState.AUDIO_CHANGED) dao.setState(bookId, TextState.AUDIO_CHANGED, null, System.currentTimeMillis())
        else if (matches && row.state == TextState.AUDIO_CHANGED) dao.setState(bookId, stateOf(sync, tracks), null, System.currentTimeMillis())
    }

    private suspend fun restoreFromFolder(bookId: String) {
        val entries = folder(bookId)?.second ?: return
        fun bytes(name: String) = entries.firstOrNull { it.name == name }?.let { app.readBytes(it.uri) }
        val gz = bytes(TEXT_MIRROR) ?: return
        val doc = runCatching { textDocFromGz(gz) }.getOrNull() ?: return
        val sha = sha256Hex(gz)
        val mirrored = bytes(SYNC_MIRROR)?.takeIf { b -> runCatching { syncDocFromGz(b).textSha == sha }.getOrDefault(false) }
        val sync = mirrored?.let(::syncDocFromGz) ?: approximate(bookId, doc, sha)
        val syncBytes = mirrored ?: sync.toGz()
        textFile(bookId).writeAtomic(gz)
        syncFile(bookId).writeAtomic(syncBytes)
        bytes(TRANSCRIPT_MIRROR)?.let { transcriptFile(bookId).writeAtomic(it) }
        dao.upsert(row(bookId, doc, sha, sync, syncBytes, doc.source.name, null))
    }

    private suspend fun row(bookId: String, doc: BookTextDoc, sha: String, sync: SyncDoc, syncBytes: ByteArray, sourceName: String, old: BookText?): BookText {
        val state = stateOf(sync, currentTracks(bookId)).let { if (old != null && old.state in ACTIVE) old.state else it }
        val audio = if (state == TextState.READY) lib.book(bookId)?.durationMs ?: 0 else old?.syncedAudioMs ?: 0
        return BookText(bookId, sourceName, doc.source.format, old?.lang ?: doc.lang, sha, doc.wordCount(), state, sync.tier, sync.coverage(), audio,
            old?.offsetMs ?: 0, sha256Hex(syncBytes), sync.engine, null, System.currentTimeMillis())
    }

    private fun stateOf(sync: SyncDoc, tracks: List<SyncTrack>) = when {
        SyncMap.of(sync, tracks) == null -> TextState.AUDIO_CHANGED
        sync.tier == SyncTier.ALIGNED && sync.ranges.none { it.kind == RangeKind.APPROX } -> TextState.READY
        else -> TextState.PARSED
    }

    private suspend fun currentTracks(bookId: String) = lib.tracks(bookId).map { SyncTrack(it.fileName, it.durationMs) }

    private suspend fun approximate(bookId: String, doc: BookTextDoc, sha: String) =
        Approximate.build(doc, sha, currentTracks(bookId), lib.chapters(bookId).map { ChapterRef(it.title, it.startInBookMs.toInt()) })

    /** Book folder and its listing; null for cloud-only books and unreadable trees. */
    private suspend fun folder(bookId: String): Pair<Uri, List<DocEntry>>? {
        val b = lib.book(bookId) ?: return null
        if (b.folderUri.isEmpty()) return null
        val folder = Uri.parse(b.folderUri)
        return app.children(Uri.parse(b.rootUri), DocumentsContract.getDocumentId(folder))?.let { folder to it }
    }

    private suspend fun mirror(bookId: String, vararg files: Pair<File, String>) {
        try {
            if (!app.prefs.now().saveTextInFolders) return
            val (folder, entries) = folder(bookId) ?: return
            for ((f, name) in files) if (f.exists()) Sidecar.writeFile(app, folder, entries, name) { out -> f.inputStream().use { it.copyTo(out) } }
        } catch (e: CancellationException) { throw e } catch (e: Exception) { /* read-only tree: filesDir still has it */ }
    }

    /** Text files in the book's folder, best guess first. */
    suspend fun folderCandidates(bookId: String): List<DocEntry> = withContext(Dispatchers.IO) {
        folder(bookId)?.second.orEmpty().filter { !it.isDir && isEbookCandidate(it.name, it.size) }
            .sortedWith(compareBy({ it.ext == "txt" }, { -it.size }))
    }

    private fun dismissedMarker(bookId: String) = File(app.filesDir, "text/$bookId/suggestion-dismissed")
    suspend fun suggestionDismissed(bookId: String): Boolean = withContext(Dispatchers.IO) { dismissedMarker(bookId).exists() }
    suspend fun dismissSuggestion(bookId: String): Unit = withContext(Dispatchers.IO) { dir(bookId); dismissedMarker(bookId).createNewFile(); Unit }

    suspend fun sizeOnDisk(bookId: String): Long = withContext(Dispatchers.IO) { dir(bookId).listFiles()?.sumOf { it.length() } ?: 0 }

    private fun File.writeAtomic(bytes: ByteArray) {
        val tmp = File(parentFile, ".$name.tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(this)) throw IOException("rename failed: $name")
    }

    private companion object { val ACTIVE = setOf(TextState.QUEUED, TextState.ALIGNING, TextState.PAUSED) }
}
