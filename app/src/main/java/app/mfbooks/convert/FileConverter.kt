package app.mfbooks.convert

import android.content.Context
import android.media.MediaFormat
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import app.mfbooks.MFBooksApp
import app.mfbooks.data.Book
import app.mfbooks.data.Track
import app.mfbooks.library.DocEntry
import app.mfbooks.library.IMAGE_EXTENSIONS
import app.mfbooks.library.SafSource
import app.mfbooks.library.Sidecar
import app.mfbooks.library.TagInfo
import app.mfbooks.library.children
import app.mfbooks.library.fill
import app.mfbooks.library.parseExtraTags
import app.mfbooks.library.readMedia
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import java.io.File
import org.json.JSONObject
import kotlin.math.abs

sealed interface FileResult {
    data class Done(val savedBytes: Long) : FileResult
    data class Skipped(val reason: String) : FileResult
    data class Failed(val reason: String) : FileResult
}

/**
 * One file through §4.6: capture metadata into `.mfbooks.json` (commit point) → transcode to `<name>.opus.tmp` →
 * verify duration → swap → remap the sidecar → update Room. Blocking; call on Dispatchers.IO.
 * The sidecar is read/written here directly (Sidecar's writer is private), always preserving unknown keys.
 */
class FileConverter(private val context: Context) {
    private val app = MFBooksApp.get(context)
    private val r = context.contentResolver

    private class Folder(val tree: Uri, val uri: Uri, val docId: String)

    private fun folderOf(book: Book): Folder {
        val uri = Uri.parse(book.folderUri)
        return Folder(Uri.parse(book.rootUri), uri, DocumentsContract.getDocumentId(uri))
    }

    private fun list(f: Folder) = context.children(f.tree, f.docId) ?: error("folder unreadable")

    suspend fun convert(book: Book, track: Track, progress: (Float) -> Unit, cancelled: () -> Boolean): FileResult {
        val f = folderOf(book)
        var entries = list(f)
        val src = entries.firstOrNull { it.name == track.fileName } ?: return FileResult.Failed("${track.fileName} is missing")
        val outName = src.name.substringBeforeLast('.') + ".opus"
        val tmpName = "$outName.tmp"
        if (entries.any { it.name == outName }) return FileResult.Failed("$outName already exists")
        entries.filter { it.name == tmpName }.forEach { DocumentsContract.deleteDocument(r, it.uri) }

        val info = r.openFileDescriptor(src.uri, "r")?.use { OpusTranscoder.probe(it.fileDescriptor) }
            ?: return FileResult.Failed("can't read ${src.name}")
        val codec = when (info.mime) {
            MediaFormat.MIMETYPE_AUDIO_MPEG -> "mp3"
            MediaFormat.MIMETYPE_AUDIO_AAC -> "aac"
            else -> return FileResult.Skipped("${src.name} is ${info.mime}, not MP3/AAC")
        }

        // 1–2. Metadata into the sidecar before the original is touched (commit point).
        val media = readMedia(context, src.uri)
        val doc = readDoc(entries)
        val fe = fileEntry(doc, src.name)
        fe.put("name", src.name).put("size", src.size).put("mtime", src.mtime).put("durationMs", track.durationMs)
        // Last chance to read the original's tags: cached values win, the file fills the gaps.
        val extra = runCatching { SafSource(context, src.uri).use { parseExtraTags(it, src.name) } }.getOrNull()
        val fileTags = (media?.tags ?: TagInfo()).fill(extra)
        val tags = tagsJson(fileTags)
        fe.optJSONObject("tags")?.let { old -> old.keys().forEach { k -> if (!old.isNull(k) && old.optString(k).isNotBlank()) tags.put(k, old.get(k)) } }
        fe.put("tags", tags)
        (track.title ?: fileTags.title)?.let { if (!fe.has("title")) fe.put("title", it) }
        // Room's chapters are the merged result the app shows (§4.3.1), incl. a single M4B's embedded chapters.
        val chapters = app.db.library().chapters(book.id).filter { it.trackIndex == track.index }
        fe.put("chapters", JSONArray().also { a -> chapters.forEach { a.put(JSONObject().put("title", it.title).put("startMs", it.startInTrackMs)) } })
        fe.put("converted", JSONObject().put("from", src.name).put("codec", codec)
            .put("bitrateKbps", Eligibility.kbps(src.size, track.durationMs)).put("at", System.currentTimeMillis()))
        // The same metadata goes into the file's OpusTags, so other players (and a full rescan) see tags and chapters too.
        val comments = vorbisComments(tags, fe.optString("title").ifBlank { null }) +
            OggOpusWriter.chapterComments(chapters.map { it.title to it.startInTrackMs })
        // The cover dies with the original's tags: keep it as a folder image if the folder has none.
        val art = media?.art
        if (art != null && entries.none { it.ext in IMAGE_EXTENSIONS }) runCatching {
            val png = art.size > 4 && art[1] == 'P'.code.toByte() && art[2] == 'N'.code.toByte()
            val name = if (png) "cover.png" else "cover.jpg"
            DocumentsContract.createDocument(r, f.uri, if (png) "image/png" else "image/jpeg", name)
                ?.let { u -> r.openOutputStream(u, "wt")!!.use { it.write(art) } }
            entries = list(f)
        }
        try { writeDoc(f, entries, doc) } catch (e: CancellationException) { throw e } catch (e: Exception) {
            Log.w(TAG, "sidecar write failed", e)
            return FileResult.Failed("can't write ${Sidecar.NAME} (read-only folder?)")
        }

        // 3–5. Transcode into the temp file. Finished chunks live in a cache dir keyed by the source, so a stopped run
        // (pause, system stop) resumes mid-file; leftovers of other files are dropped.
        val work = File(File(context.cacheDir, "opus"), "%08x".format("${src.uri}|${src.size}|${src.mtime}".hashCode()))
        work.parentFile?.listFiles()?.filter { it != work }?.forEach { it.deleteRecursively() }
        val tmp = DocumentsContract.createDocument(r, f.uri, "application/octet-stream", tmpName)
            ?: return FileResult.Failed("can't create $tmpName")
        try {
            r.openFileDescriptor(tmp, "rw")!!.use { out ->
                r.openFileDescriptor(src.uri, "r")!!.use { inp -> OpusTranscoder.transcode(inp.fileDescriptor, out.fileDescriptor, comments, progress, cancelled, work) }
            }
        } catch (e: Throwable) {
            runCatching { DocumentsContract.deleteDocument(r, tmp) }
            if (e is CancellationException) throw e
            Log.w(TAG, "transcode failed: ${src.name}", e)
            return FileResult.Failed("encoding failed: ${e.message ?: e.javaClass.simpleName}")
        }
        work.deleteRecursively()

        // 6. Verify.
        val outMs = r.openFileDescriptor(tmp, "r")?.use { OpusTranscoder.durationMs(it.fileDescriptor) } ?: -1
        val srcMs = info.durationMs.takeIf { it > 0 } ?: track.durationMs
        if (abs(outMs - srcMs) > 500) {
            runCatching { DocumentsContract.deleteDocument(r, tmp) }
            return FileResult.Failed("duration check failed (${srcMs} ms → ${outMs} ms)")
        }

        // 7–8. Swap, then sidecar + Room.
        if (!DocumentsContract.deleteDocument(r, src.uri)) {
            runCatching { DocumentsContract.deleteDocument(r, tmp) }
            return FileResult.Failed("can't delete ${src.name}")
        }
        val renamed = DocumentsContract.renameDocument(r, tmp, outName) ?: tmp
        val out = finishSwap(book, track, src.name, renamed) ?: return FileResult.Failed("converted, but the new file wasn't found")
        return FileResult.Done(src.size - out.size)
    }

    /** After the rename: point the sidecar entry, progress and bookmarks at the new file, then Room's Track. */
    private suspend fun finishSwap(book: Book, track: Track, oldName: String, newUri: Uri): DocEntry? {
        val f = folderOf(book)
        val entries = list(f)
        val newId = DocumentsContract.getDocumentId(newUri)
        val out = entries.firstOrNull { it.docId == newId } ?: entries.firstOrNull { it.name == oldName.substringBeforeLast('.') + ".opus" } ?: return null
        val doc = readDoc(entries)
        remap(doc, oldName, out.name, out.size, out.mtime)
        // ponytail: one retry; if both fail, [repair] fixes the sidecar on the next run
        runCatching { writeDoc(f, entries, doc) }.recoverCatching { writeDoc(f, list(f), doc) }
            .onFailure { Log.w(TAG, "sidecar remap failed for ${out.name}", it) }
        app.db.library().insertTracks(listOf(track.copy(uri = out.uri.toString(), fileName = out.name, sizeBytes = out.size, mtime = out.mtime)))
        return out
    }

    /**
     * Crash recovery for one book, run before converting it: a `.opus.tmp` whose original is gone gets renamed, and a
     * sidecar entry still named after a deleted original is pointed at its `.opus`. Room is fixed by the next scan.
     */
    suspend fun repair(book: Book) {
        val f = folderOf(book)
        var entries = list(f)
        val names = entries.mapTo(HashSet()) { it.name }
        val doc = entries.firstOrNull { it.name == Sidecar.NAME }?.let { Sidecar.read(context, it) } ?: return
        val files = doc.optJSONArray("files") ?: return
        var changed = false
        for (i in 0 until files.length()) {
            val fe = files.getJSONObject(i)
            val name = fe.optString("name")
            if (!fe.has("converted") || name in names || name.endsWith(".opus")) continue
            val outName = name.substringBeforeLast('.') + ".opus"
            entries.firstOrNull { it.name == "$outName.tmp" }?.takeIf { outName !in names }?.let { tmp ->
                val ms = r.openFileDescriptor(tmp.uri, "r")?.use { OpusTranscoder.durationMs(it.fileDescriptor) } ?: -1
                if (abs(ms - fe.optLong("durationMs")) <= 500) DocumentsContract.renameDocument(r, tmp.uri, outName)
                else DocumentsContract.deleteDocument(r, tmp.uri)
                entries = list(f)
            }
            val out = entries.firstOrNull { it.name == outName } ?: continue
            remap(doc, name, out.name, out.size, out.mtime); changed = true
        }
        if (changed) runCatching { writeDoc(f, entries, doc) }
    }

    private fun readDoc(entries: List<DocEntry>): JSONObject =
        entries.firstOrNull { it.name == Sidecar.NAME }?.let { Sidecar.read(context, it) } ?: JSONObject().put("v", 1)

    /** The `files[]` entry for [name], created (and appended) if missing. */
    private fun fileEntry(doc: JSONObject, name: String): JSONObject {
        val files = doc.optJSONArray("files") ?: JSONArray().also { doc.put("files", it) }
        return (0 until files.length()).map { files.getJSONObject(it) }.firstOrNull { it.optString("name") == name }
            ?: JSONObject().put("name", name).also { files.put(it) }
    }

    private val lock = Mutex()

    /** Same crash-safe scheme as Sidecar: write `.mfbooks.json.tmp`, delete the old file, rename. */
    private suspend fun writeDoc(f: Folder, entries: List<DocEntry>, doc: JSONObject) = lock.withLock {
        doc.put("v", doc.optInt("v", 1)).put("updatedAt", System.currentTimeMillis())
        val tmpName = Sidecar.NAME + ".tmp"
        entries.firstOrNull { it.name == tmpName }?.let { DocumentsContract.deleteDocument(r, it.uri) }
        val tmp = DocumentsContract.createDocument(r, f.uri, "application/octet-stream", tmpName) ?: error("createDocument failed")
        r.openOutputStream(tmp, "wt")!!.use { it.write(doc.toString(1).toByteArray()) }
        entries.firstOrNull { it.name == Sidecar.NAME }?.let { DocumentsContract.deleteDocument(r, it.uri) }
        DocumentsContract.renameDocument(r, tmp, Sidecar.NAME) ?: error("rename failed")
    }

    companion object {
        private const val TAG = "Convert"

        /** Renames a file everywhere the sidecar mentions it (files[].name, progress.file, bookmarks[].file). Pure JSON. */
        fun remap(doc: JSONObject, oldName: String, newName: String, size: Long, mtime: Long) {
            doc.optJSONArray("files")?.let { a ->
                for (i in 0 until a.length()) a.getJSONObject(i).takeIf { it.optString("name") == oldName }
                    ?.put("name", newName)?.put("size", size)?.put("mtime", mtime)
            }
            doc.optJSONObject("progress")?.takeIf { it.optString("file") == oldName }?.put("file", newName)
            doc.optJSONArray("bookmarks")?.let { a ->
                for (i in 0 until a.length()) a.getJSONObject(i).takeIf { it.optString("file") == oldName }?.put("file", newName)
            }
        }

        private val VORBIS = listOf("title" to "TITLE", "artist" to "ARTIST", "albumArtist" to "ALBUMARTIST", "album" to "ALBUM",
            "composer" to "COMPOSER", "narrator" to "NARRATOR", "year" to "DATE", "genre" to "GENRE", "publisher" to "PUBLISHER",
            "series" to "SERIES", "comment" to "COMMENT", "description" to "DESCRIPTION")

        fun vorbisComments(tags: JSONObject, title: String?): List<Pair<String, String>> = VORBIS.mapNotNull { (k, v) ->
            val value = tags.optString(k).takeIf { !tags.isNull(k) && it.isNotBlank() } ?: title.takeIf { k == "title" }
            value?.let { v to it }
        }

        fun tagsJson(t: TagInfo) = JSONObject().apply {
            put("title", t.title); put("artist", t.artist); put("albumArtist", t.albumArtist); put("album", t.album)
            put("composer", t.composer); put("narrator", t.narrator); put("year", t.year); put("genre", t.genre)
            put("publisher", t.publisher); put("series", t.series); put("comment", t.comment)
            put("description", t.description); put("encoder", t.encoder)
        }
    }
}
