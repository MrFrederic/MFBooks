package app.mfbooks.server

import android.util.Log
import app.mfbooks.MFBooksApp
import app.mfbooks.data.Book
import app.mfbooks.data.PendingChange
import app.mfbooks.sync.Change
import app.mfbooks.sync.Kind
import app.mfbooks.sync.TextData
import app.mfbooks.sync.decode
import app.mfbooks.sync.toData
import app.mfbooks.text.sha256Hex
import kotlinx.serialization.json.JsonObject
import java.net.URLEncoder

/**
 * The read-along bytes of a book against `/books/{id}/text/...` (server/API.md). The `text` record says which version is
 * current; this keeps the files behind it in step, always uploading before the record so it never points at missing bytes.
 * What the server last held is remembered per book ([Server.known]): an If-Match needs it, and it spares re-uploads.
 */
object TextSync {
    /** Asking for gzip ourselves keeps OkHttp from unpacking it; the store wants the file as stored. */
    private val RAW = mapOf("Accept-Encoding" to "gzip")

    /** Uploads what changed and returns the record to push; null when there is nothing to push. Only call when the server has the feature. */
    suspend fun push(app: MFBooksApp, bookId: String, sid: String, at: Long): Change? {
        val t = app.db.text().text(bookId)
        if (t == null) { forget(sid); return Change(Kind.TEXT, sid, sid, at, deleted = true) } // the server drops the files with the record
        val store = app.textStore
        val base = "/books/$sid/text"

        if (t.textSha != Server.known("t.$sid")) {
            val file = store.textFile(bookId).takeIf { it.exists() } ?: return null
            val (code, r) = Server.putFile("$base/content", file, Server.known("t.$sid"))
            when (code) {
                200 -> { Server.setKnown("t.$sid", t.textSha); Server.setKnown("s.$sid", r.str("syncSha")) }
                404 -> return null // the book isn't on the server any more
                409 -> { adopt(app, bookId, sid); return null } // someone's text was first, and that is the one every device uses
                else -> throw ServerError(code, r.str("error") ?: "HTTP $code")
            }
        }

        val source = store.sourceFile(bookId)
        if (source != null && Server.known("f.$sid") != t.textSha) {
            val (code, r) = Server.putFile("$base/source?name=${URLEncoder.encode(t.sourceName, "UTF-8")}", source, null)
            if (code >= 500) throw ServerError(code, r.str("error") ?: "HTTP $code")
            Server.setKnown("f.$sid", t.textSha) // a refused source is no reason to keep trying: only the reader's files matter
        }

        if (t.syncSha != null && t.syncSha != Server.known("s.$sid")) {
            val file = store.syncFile(bookId).takeIf { it.exists() } ?: return null
            var (code, r) = Server.putFile("$base/sync", file, Server.known("s.$sid"))
            // Another device's map arrived since we last looked: last writer wins, like the record.
            if (code == 412 && t.updatedAt > (r.num("updatedAt")?.toLong() ?: 0)) {
                val second = Server.putFile("$base/sync", file, null)
                code = second.first; r = second.second
            }
            when (code) {
                200 -> Server.setKnown("s.$sid", t.syncSha)
                412 -> { adopt(app, bookId, sid); return null }
                404, 409 -> return null // no text there to attach it to, or it is someone else's
                else -> throw ServerError(code, r.str("error") ?: "HTTP $code")
            }
        }
        return Change(Kind.TEXT, sid, sid, maxOf(at, t.updatedAt),
            data = TextData(t.textSha, t.syncSha, t.lang, t.words, t.syncTier, t.coverage, t.offsetMs, t.sourceName).toData())
    }

    /** A pulled `text` record. Runs outside the database transaction: it may download. */
    suspend fun applyRemote(app: MFBooksApp, c: Change, local: Book) {
        apply(app, local.id, local.serverId ?: c.id, c.deleted, c.updatedAt, c.data)
    }

    /** `BookDetail.text`, for a cloud book that only now became a library book (its record was pulled before the book existed here). */
    suspend fun applyDetail(app: MFBooksApp, sid: String, text: JsonObject) {
        apply(app, sid, sid, false, text.num("updatedAt")?.toLong() ?: 0, text)
    }

    private suspend fun apply(app: MFBooksApp, bookId: String, sid: String, deleted: Boolean, updatedAt: Long, data: JsonObject) {
        val dao = app.db.text()
        val cur = dao.text(bookId)
        if (deleted) {
            forget(sid)
            if (cur == null) return
            if (updatedAt > cur.updatedAt) quietly(app, bookId) { app.textStore.remove(bookId) }
            else app.db.sync().addPending(PendingChange(tbl = Kind.TEXT, key = bookId, at = cur.updatedAt)) // ours is newer: say so again
            return
        }
        if (cur != null && updatedAt <= cur.updatedAt) return
        val t = data.decode<TextData>()
        val text = if (cur?.textSha != t.textSha) gz("/books/$sid/text/content") ?: return else null
        val sync = if (t.syncSha != null && (t.syncSha != cur?.syncSha || text != null)) gz("/books/$sid/text/sync") else null
        // A replace can be between its bytes and its record; the matching record follows, so wait for that one.
        if (text != null && sha256Hex(text) != t.textSha || sync != null && sha256Hex(sync) != t.syncSha) {
            Log.w("TextSync", "$sid: server files don't match the record yet")
            return
        }
        quietly(app, bookId) {
            if (text != null || sync != null) app.textStore.storeFromServer(bookId, text, sync, t.sourceName)
            if (cur?.offsetMs != t.offsetMs) dao.setOffset(bookId, t.offsetMs, updatedAt)
            if (cur?.lang != t.lang) dao.setLang(bookId, t.lang, updatedAt)
        }
        Server.setKnown("t.$sid", t.textSha)
        Server.setKnown("s.$sid", t.syncSha)
        Server.setKnown("f.$sid", t.textSha)
    }

    /** Takes whatever the server has in place of ours. */
    private suspend fun adopt(app: MFBooksApp, bookId: String, sid: String) {
        val text = gz("/books/$sid/text/content") ?: return
        val sync = gz("/books/$sid/text/sync")
        val name = app.db.text().text(bookId)?.sourceName
        quietly(app, bookId) { app.textStore.storeFromServer(bookId, text, sync, name) }
        Server.setKnown("t.$sid", sha256Hex(text))
        Server.setKnown("s.$sid", sync?.let(::sha256Hex))
        Server.setKnown("f.$sid", sha256Hex(text))
    }

    private fun forget(sid: String) { for (k in listOf("t", "s", "f")) Server.setKnown("$k.$sid", null) }

    private suspend fun gz(path: String): ByteArray? = try {
        Server.download(path, RAW).use { it.body.bytes() }
    } catch (e: ServerError) { if (e.code == 404) null else throw e }

    /** Changes that came from the server must not echo back; only this book's rows are dropped, other writes keep theirs. */
    private suspend fun quietly(app: MFBooksApp, bookId: String, block: suspend () -> Unit) {
        val dao = app.db.sync()
        val before = dao.maxPending()
        block()
        dao.deletePendingOf(Kind.TEXT, bookId, before)
    }
}
