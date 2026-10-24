package app.mfbooks.library

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import app.mfbooks.MFBooksApp
import app.mfbooks.data.Book
import app.mfbooks.data.BookProgress
import app.mfbooks.data.BookSettings
import app.mfbooks.data.BookState
import app.mfbooks.data.LibraryDao
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** `.mfbooks.json` per book folder (§4.5). Unknown keys are preserved on rewrite. */
object Sidecar {
    const val NAME = ".mfbooks.json"
    private const val TMP = "$NAME.tmp"
    private val lock = Mutex()

    fun read(context: Context, entry: DocEntry): JSONObject? =
        context.readBytes(entry.uri)?.let { runCatching { JSONObject(String(it)) }.getOrNull() }

    fun fileEntry(sc: JSONObject?, name: String): JSONObject? {
        val a = sc?.optJSONArray("files") ?: return null
        return (0 until a.length()).map { a.getJSONObject(it) }.firstOrNull { it.optString("name") == name }
    }

    /** Bump when the parsers learn new tags, so older caches are re-read once. */
    private const val SCAN_V = 2

    /** Cached entry only if name + size + mtime all match (and it was written by a current parser). */
    fun cachedFile(sc: JSONObject?, e: DocEntry): JSONObject? =
        fileEntry(sc, e.name)?.takeIf { it.optLong("size", -1) == e.size && it.optLong("mtime", -1) == e.mtime && it.optInt("scanV") >= SCAN_V }

    fun chapters(f: JSONObject): List<RawChapter>? = f.optJSONArray("chapters")?.let { a ->
        (0 until a.length()).map { a.getJSONObject(it).let { o -> RawChapter(o.optString("title"), o.optLong("startMs")) } }
    }

    private fun JSONObject.s(k: String) = if (has(k) && !isNull(k)) getString(k).ifBlank { null } else null

    fun tags(f: JSONObject): TagInfo = f.optJSONObject("tags")?.let { t ->
        TagInfo(t.s("title"), t.s("artist"), t.s("albumArtist"), t.s("album"), t.s("composer"), t.s("narrator"), t.s("year"),
            t.s("genre"), t.s("publisher"), t.s("series"), t.s("comment"), t.s("description"), t.s("encoder"), t.s("copyright"))
    } ?: TagInfo()

    private fun TagInfo.json() = JSONObject().also {
        it.put("title", title); it.put("artist", artist); it.put("albumArtist", albumArtist); it.put("album", album)
        it.put("composer", composer); it.put("narrator", narrator); it.put("year", year); it.put("genre", genre)
        it.put("publisher", publisher); it.put("series", series); it.put("comment", comment)
        it.put("description", description); it.put("encoder", encoder); it.put("copyright", copyright)
    }

    private fun settingsJson(s: BookSettings) = JSONObject().also {
        it.put("speed", s.speed?.toDouble()); it.put("savedToggleSpeed", s.savedToggleSpeed?.toDouble())
        it.put("pitch", s.pitch?.toDouble()); it.put("boostMb", s.boostMb)
        it.put("eqBandsDb", s.eqBandsDb?.let { e -> JSONArray(e.split(',').map { v -> v.trim().toDoubleOrNull() ?: 0.0 }) })
        it.put("balance", s.balance?.toDouble()); it.put("mono", s.mono); it.put("skipSilence", s.skipSilence)
        it.put("skipStartMs", s.skipStartMs); it.put("skipEndMs", s.skipEndMs); it.put("repeatMode", s.repeatMode)
    }

    private fun JSONObject.f(k: String) = if (has(k) && !isNull(k)) getDouble(k).toFloat() else null
    private fun JSONObject.l(k: String) = if (has(k) && !isNull(k)) getLong(k) else null

    private fun settingsFrom(bookId: String, j: JSONObject) = BookSettings(
        bookId, j.f("speed"), j.f("savedToggleSpeed"), j.f("pitch"), j.l("boostMb")?.toInt(),
        j.optJSONArray("eqBandsDb")?.let { a -> (0 until a.length()).joinToString(",") { a.getDouble(it).toString().removeSuffix(".0") } },
        j.f("balance"), if (j.has("mono")) j.getBoolean("mono") else null,
        if (j.has("skipSilence")) j.getBoolean("skipSilence") else null,
        j.l("skipStartMs"), j.l("skipEndMs"), j.s("repeatMode"),
    )

    private fun progressJson(p: BookProgress, file: String?, state: BookState) = JSONObject().also {
        it.put("file", file); it.put("positionMs", p.positionInTrackMs); it.put("state", state.name); it.put("at", p.updatedAt)
    }

    /** Full document after a scan: merged metadata + file cache; progress/settings from Room. Skips the write if nothing changed. */
    suspend fun writeScanned(context: Context, c: Candidate, s: Scanned, sc: JSONObject?, dao: LibraryDao) {
        val doc = sc?.let { JSONObject(it.toString()) } ?: JSONObject()
        val b = s.book
        doc.put("v", 1)
        b.serverId?.let { doc.put("serverId", it) } // survives renames, re-tags and a reinstall (book identity)
        doc.put("book", (doc.optJSONObject("book") ?: JSONObject()).also {
            it.put("title", b.title); it.put("author", b.author); it.put("narrator", b.narrator); it.put("year", b.year)
            it.put("genre", b.genre); it.put("publisher", b.publisher); it.put("series", b.series)
            it.put("seriesIndex", b.seriesIndex); it.put("description", b.description)
        })
        doc.put("files", JSONArray().also { arr ->
            for (f in s.files) arr.put((fileEntry(sc, f.entry.name)?.let { JSONObject(it.toString()) } ?: JSONObject()).also {
                it.put("name", f.entry.name); it.put("size", f.entry.size); it.put("mtime", f.entry.mtime); it.put("scanV", SCAN_V)
                it.put("durationMs", f.durationMs); it.put("title", f.tags.title); it.put("tags", f.tags.json())
                it.put("chapters", JSONArray().also { ca -> f.chapters.forEach { ch -> ca.put(JSONObject().put("title", ch.title).put("startMs", ch.startMs)) } })
            })
        })
        dao.progress(b.id)?.let { p -> doc.put("progress", progressJson(p, s.tracks.getOrNull(p.trackIndex)?.fileName, b.state)) }
        dao.settings(b.id)?.let { doc.put("settings", settingsJson(it)) }
        if (sc != null && JSONObject(doc.toString()).put("updatedAt", sc.optLong("updatedAt")).toString() == sc.toString()) return
        doc.put("updatedAt", System.currentTimeMillis())
        write(context, c.folderUri, c.entries, doc)
    }

    /** For playback: call on pause / stop / book switch (not every tick; SAF writes are slow). Silent on any failure. */
    suspend fun writeProgress(context: Context, book: Book, progress: BookProgress, settings: BookSettings?) = withContext(Dispatchers.IO) {
        try {
            val app = MFBooksApp.get(context)
            if (!app.prefs.now().saveInBookFolders) return@withContext
            val folder = Uri.parse(book.folderUri)
            val entries = context.children(Uri.parse(book.rootUri), DocumentsContract.getDocumentId(folder)) ?: return@withContext
            val doc = entries.firstOrNull { it.name == NAME }?.let { read(context, it) } ?: JSONObject()
            val file = app.db.library().tracks(book.id).getOrNull(progress.trackIndex)?.fileName
            doc.put("v", 1).put("updatedAt", System.currentTimeMillis())
            doc.put("progress", progressJson(progress, file, book.state))
            settings?.let { doc.put("settings", settingsJson(it)) }
            write(context, folder, entries, doc)
        } catch (e: CancellationException) { throw e } catch (e: Exception) { /* not writable: fall back to Room only */ }
    }

    /** Import progress/settings when Room has none or the sidecar is newer. [entries] is the folder listing. */
    suspend fun importIfNewer(context: Context, dao: LibraryDao, bookId: String, entries: List<DocEntry>, loaded: JSONObject?) {
        val e = entries.firstOrNull { it.name == NAME } ?: return
        val p = dao.progress(bookId)
        // ponytail: skip reading when the file isn't clearly newer than Room's progress (our own writes trail it by seconds)
        if (loaded == null && p != null && e.mtime <= p.updatedAt + 10_000) return
        val j = (loaded ?: read(context, e))?.optJSONObject("progress") ?: return
        val at = j.optLong("at", e.mtime)
        if (p != null && at <= p.updatedAt) return
        val tracks = dao.tracks(bookId)
        val idx = tracks.indexOfFirst { it.fileName == j.optString("file") }.takeIf { it >= 0 } ?: return
        dao.upsertProgress(BookProgress(bookId, idx, j.optLong("positionMs"), at))
        BookState.entries.firstOrNull { it.name == j.optString("state") }?.let { dao.setState(bookId, it) }
        if (dao.book(bookId)?.lastPlayedAt == null) dao.setLastPlayed(bookId, at)
        if (dao.settings(bookId) == null) (loaded ?: read(context, e))?.optJSONObject("settings")?.let { dao.upsertSettings(settingsFrom(bookId, it)) }
    }

    /** tmp + rename; the provider is asked to delete the old file first since rename doesn't overwrite. */
    private suspend fun write(context: Context, folder: Uri, entries: List<DocEntry>, doc: JSONObject) = lock.withLock {
        val r = context.contentResolver
        entries.firstOrNull { it.name == TMP }?.let { DocumentsContract.deleteDocument(r, it.uri) }
        val tmp = DocumentsContract.createDocument(r, folder, "application/octet-stream", TMP) ?: return@withLock
        r.openOutputStream(tmp, "wt")!!.use { it.write(doc.toString(1).toByteArray()) }
        entries.firstOrNull { it.name == NAME }?.let { DocumentsContract.deleteDocument(r, it.uri) }
        DocumentsContract.renameDocument(r, tmp, NAME)
    }
}
