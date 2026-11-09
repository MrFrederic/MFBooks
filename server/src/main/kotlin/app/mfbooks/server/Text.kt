package app.mfbooks.server

import app.mfbooks.sync.BookData
import app.mfbooks.sync.Kind
import app.mfbooks.sync.TextData
import app.mfbooks.sync.decode
import app.mfbooks.sync.toData
import app.mfbooks.text.Approximate
import app.mfbooks.text.BlockKind
import app.mfbooks.text.BookTextDoc
import app.mfbooks.text.ChapterRef
import app.mfbooks.text.SyncDoc
import app.mfbooks.text.SyncTier
import app.mfbooks.text.SyncTrack
import app.mfbooks.text.TextJson
import app.mfbooks.text.TextParseException
import app.mfbooks.text.TextParser
import app.mfbooks.text.sha256Hex
import app.mfbooks.text.toGz
import io.ktor.http.ContentDisposition
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.http.content.LocalFileContent
import io.ktor.server.request.header
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.put
import io.ktor.utils.io.jvm.javaio.toInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.GZIPInputStream

/** Ids become path segments and any client can push a record with any id, so only real UUIDs reach the file system. */
private val BOOK_ID = Regex("[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}")
fun isBookId(id: String) = BOOK_ID.matches(id)

/** Longest first: `.fb2.zip` must not read as `.zip`. */
private val SOURCE_EXTS = listOf("fb2.zip", "fb2", "epub", "txt")
private val NOT_A_BOOK = setOf("info", "readme", "notes", "description", "license", "licence")
private const val MIN_TXT_BOOK = 100_000L

/** An ETag or If-Match value without quotes or `W/`. */
private fun tag(h: String?) = h?.trim()?.removePrefix("W/")?.trim('"')?.ifEmpty { null }

private fun tooLarge(limit: Long): Nothing = fail(HttpStatusCode.PayloadTooLarge, "This file is over the ${limit / (1L shl 20)} MB limit.", "too_large")

/** A book's read-along files: `books/<id>/text/{source.*, text.json.gz, sync.json.gz}`. See server/API.md. */
class Texts(private val app: App) {
    private val locks = ConcurrentHashMap<String, Mutex>()

    private fun dir(id: String) = File(app.books, "$id/text")
    fun contentFile(id: String) = File(dir(id), "text.json.gz")
    fun syncFile(id: String) = File(dir(id), "sync.json.gz")
    fun sourceFile(id: String) = dir(id).listFiles()?.firstOrNull { it.name.startsWith("source.") }

    /** One writer per book, off the request thread: the first-content rule is check-then-write. */
    private suspend fun <T> locked(id: String, block: suspend () -> T): T =
        withContext(Dispatchers.IO) { locks.computeIfAbsent(id) { Mutex() }.withLock { block() } }

    /** Streams a request body to a temp file on the data volume; the cap holds whatever Content-Length claims. */
    suspend fun receive(call: ApplicationCall): File = withContext(Dispatchers.IO) {
        val limit = app.textLimit
        if ((call.request.header(HttpHeaders.ContentLength)?.toLongOrNull() ?: 0) > limit) tooLarge(limit)
        val tmp = File.createTempFile("text-", ".part", app.uploads)
        try {
            val input = call.receiveChannel().toInputStream()
            tmp.outputStream().use { out ->
                val buf = ByteArray(1 shl 16)
                var total = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > limit) tooLarge(limit)
                    out.write(buf, 0, n)
                }
            }
            tmp
        } catch (e: Throwable) { tmp.delete(); throw e }
    }

    private fun commit(tmp: File, dst: File) {
        dst.parentFile.mkdirs()
        Files.move(tmp.toPath(), dst.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    private fun write(bytes: ByteArray, dst: File) {
        val tmp = File.createTempFile("text-", ".part", app.uploads)
        try { tmp.writeBytes(bytes); commit(tmp, dst) } finally { tmp.delete() }
    }

    private fun sha(f: File) = f.takeIf { it.isFile }?.let(::sha256File)

    /** The upload as JSON, bounded after gunzip too: a small gzip can expand to anything. */
    private fun unpack(gz: File): String {
        val limit = app.textLimit
        val bytes = try {
            GZIPInputStream(gz.inputStream()).use { i ->
                val out = ByteArrayOutputStream()
                val buf = ByteArray(1 shl 16)
                while (true) {
                    val n = i.read(buf)
                    if (n < 0) break
                    if (out.size() + n > limit) tooLarge(limit)
                    out.write(buf, 0, n)
                }
                out.toByteArray()
            }
        } catch (e: IOException) { fail(HttpStatusCode.BadRequest, "Not a gzip file", "invalid") }
        return String(bytes)
    }

    private inline fun <reified T> parseDoc(gz: File): T {
        val json = unpack(gz)
        return runCatching { TextJson.decodeFromString<T>(json) }.getOrElse { fail(HttpStatusCode.BadRequest, "Not a valid document", "invalid") }
    }

    // ---- content ----

    suspend fun putContent(id: String, tmp: File, ifMatch: String?): JsonObject = locked(id) {
        parseDoc<BookTextDoc>(tmp)
        val new = sha256File(tmp)
        val current = sha(contentFile(id))
        when {
            current == null -> commit(tmp, contentFile(id))
            new == current -> {}
            ifMatch == current -> {
                // A deliberate replace: what depended on the old text goes with it, and the caller's source and sync follow.
                syncFile(id).delete(); sourceFile(id)?.delete()
                commit(tmp, contentFile(id))
                app.records.patch(Kind.TEXT, id, j("textSha" to new, "syncSha" to null))
                app.live.changed(app.records.seq())
            }
            else -> fail(HttpStatusCode.Conflict, "This book already has text", "exists")
        }
        j("textSha" to new, "syncSha" to sha(syncFile(id)))
    }

    // ---- sync ----

    /** A stale If-Match comes back as `{error, reason, syncSha, updatedAt}`, which the route answers with 412. */
    suspend fun putSync(id: String, tmp: File, ifMatch: String?): JsonObject = locked(id) {
        val doc = parseDoc<SyncDoc>(tmp)
        val text = sha(contentFile(id)) ?: fail(HttpStatusCode.Conflict, "Upload the text first", "no_text")
        if (doc.textSha != text) fail(HttpStatusCode.Conflict, "This map is for a different text", "stale_text")
        val current = sha(syncFile(id))
        if (ifMatch != null && ifMatch != current) return@locked j(
            "error" to "The map changed on the server", "reason" to "stale", "syncSha" to current,
            "updatedAt" to app.records.get(Kind.TEXT, id)?.updatedAt,
        )
        val new = sha256File(tmp)
        commit(tmp, syncFile(id))
        if (app.records.patch(Kind.TEXT, id, j("syncSha" to new, "tier" to doc.tier))) app.live.changed(app.records.seq())
        j("syncSha" to new)
    }

    // ---- source ----

    fun sourceExt(name: String) = SOURCE_EXTS.firstOrNull { name.endsWith(".$it", true) }

    suspend fun putSource(id: String, name: String, tmp: File, ifMatch: String?): JsonObject = locked(id) {
        val ext = sourceExt(name)!!
        val current = sha(contentFile(id))
        when {
            current == null -> ingest(id, name, ext, tmp)
            ifMatch == null -> {
                // The app sends its content first, so this is the source of that content; never a reason to parse.
                val old = sourceFile(id)
                if (old != null && sha256File(old) != sha256File(tmp)) fail(HttpStatusCode.Conflict, "This book already has text", "exists")
                if (old == null) commit(tmp, File(dir(id), "source.$ext"))
                j("textSha" to current, "syncSha" to sha(syncFile(id)), "parsed" to false)
            }
            ifMatch == current -> ingest(id, name, ext, tmp)
            else -> fail(HttpStatusCode.PreconditionFailed, "The text changed on the server", "stale")
        }
    }

    /** Parses a source, writes the three files (content last: it is what "has text" means) and announces the record. */
    private fun ingest(id: String, name: String, ext: String, tmp: File): JsonObject {
        val doc = try { TextParser.parse(name, tmp.readBytes()) } catch (e: TextParseException) { throw parseError(e) }
        val book = app.library.book(id).data.decode<BookData>()
        val gz = doc.toGz()
        val textSha = sha256Hex(gz)
        val map = Approximate.build(
            doc, textSha, book.tracks.map { SyncTrack(it.fileName, it.durationMs) },
            book.chapters.map { ChapterRef(it.title, it.startInBookMs.toInt()) },
        )
        val syncGz = map.toGz()
        val syncSha = sha256Hex(syncGz)
        sourceFile(id)?.delete()
        commit(tmp, File(dir(id), "source.$ext"))
        write(syncGz, syncFile(id))
        write(gz, contentFile(id))
        val words = doc.blocks.filter { it.k != BlockKind.IMAGE }.sumOf { b -> b.s.split(Regex("\\s+")).count { it.isNotEmpty() } }
        app.records.putServer(Kind.TEXT, id, TextData(textSha, syncSha, doc.lang, words, SyncTier.APPROXIMATE, 0f, 0, name).toData(), id)
        app.live.changed(app.records.seq())
        return j("textSha" to textSha, "syncSha" to syncSha, "parsed" to true)
    }

    private fun parseError(e: TextParseException) = when (e.reason) {
        TextParseException.Reason.DRM -> ApiError(HttpStatusCode.BadRequest, "This book is DRM-protected.", "drm")
        TextParseException.Reason.EMPTY -> ApiError(HttpStatusCode.BadRequest, "No book text in this file.", "empty")
        TextParseException.Reason.TOO_LARGE -> ApiError(HttpStatusCode.PayloadTooLarge, "This file is too large.", "too_large")
        TextParseException.Reason.UNSUPPORTED -> ApiError(HttpStatusCode.BadRequest, "This file type isn't supported.", "unsupported")
    }

    /** After a zip import: the ebook in the book folder, if there is one. */
    suspend fun importFrom(id: String, entries: List<File>) {
        fun rank(f: File) = when {
            f.name.startsWith(".") || f.length() > app.textLimit -> 0
            sourceExt(f.name) in setOf("fb2", "fb2.zip", "epub") -> 2
            // ponytail: a .txt next to the audio is often a track list; only one of book size counts.
            sourceExt(f.name) == "txt" && f.length() >= MIN_TXT_BOOK && f.nameWithoutExtension.lowercase() !in NOT_A_BOOK -> 1
            else -> 0
        }
        val pick = entries.filter { rank(it) > 0 }.maxWithOrNull(compareBy({ rank(it) }, { it.length() })) ?: return
        val tmp = File.createTempFile("text-", ".part", app.uploads)
        try {
            pick.copyTo(tmp, overwrite = true)
            locked(id) { if (!contentFile(id).isFile) ingest(id, cleanName(pick.name), sourceExt(pick.name)!!, tmp) }
        } finally { tmp.delete() }
    }

    // ---- delete ----

    suspend fun removeFiles(id: String) { locked(id) { dir(id).deleteRecursively() } }

    suspend fun delete(id: String) {
        removeFiles(id)
        if (app.records.get(Kind.TEXT, id)?.deleted == false) app.records.putServer(Kind.TEXT, id, j(), id, deleted = true)
        app.live.changed(app.records.seq())
    }

    /** The record's fields for `BookDetail.text`. */
    fun info(id: String) = app.records.get(Kind.TEXT, id)?.takeIf { !it.deleted }?.let { JsonObject(it.data + j("updatedAt" to it.updatedAt)) }
}

private suspend fun ApplicationCall.serveGz(f: File) {
    if (!f.isFile) fail(HttpStatusCode.NotFound, "No text")
    val sha = withContext(Dispatchers.IO) { sha256File(f) }
    response.header(HttpHeaders.ETag, "\"$sha\"")
    response.header(HttpHeaders.CacheControl, "private, no-cache")
    val inm = request.header(HttpHeaders.IfNoneMatch)
    if (inm != null && (inm.trim() == "*" || inm.split(',').any { tag(it) == sha })) return respond(HttpStatusCode.NotModified)
    response.header(HttpHeaders.ContentEncoding, "gzip")
    respond(LocalFileContent(f, ContentType.Application.Json))
}

/** A client-chosen name, for showing only: never a path. */
private fun cleanName(raw: String?) = raw?.replace('\\', '/')?.substringAfterLast('/')?.filter { it >= ' ' }?.take(200)?.trim().orEmpty()

fun Route.textRoutes(app: App) {
    val t = app.text
    fun ApplicationCall.bookId(): String {
        val id = parameters["id"]!!
        if (!isBookId(id)) fail(HttpStatusCode.NotFound, "No such book")
        app.library.book(id)
        return id
    }
    suspend fun ApplicationCall.withBody(block: suspend (File) -> Unit) {
        val tmp = t.receive(this)
        try { block(tmp) } finally { tmp.delete() }
    }
    val base = "/books/{id}/text"
    get("$base/content") { call.serveGz(t.contentFile(call.bookId())) }
    get("$base/sync") { call.serveGz(t.syncFile(call.bookId())) }
    get("$base/source") {
        val id = call.bookId()
        val f = t.sourceFile(id) ?: fail(HttpStatusCode.NotFound, "No source file")
        val name = app.records.get(Kind.TEXT, id)?.data?.str("sourceName")?.let(::cleanName)?.ifEmpty { null } ?: f.name
        call.response.header(HttpHeaders.ContentDisposition, ContentDisposition.Attachment.withParameter(ContentDisposition.Parameters.FileName, name).toString())
        call.response.header("X-Content-Type-Options", "nosniff")
        call.respond(LocalFileContent(f, ContentType.Application.OctetStream))
    }
    put("$base/content") {
        val id = call.bookId()
        call.withBody { call.respond(t.putContent(id, it, tag(call.request.header(HttpHeaders.IfMatch)))) }
    }
    put("$base/sync") {
        val id = call.bookId()
        call.withBody {
            val r = t.putSync(id, it, tag(call.request.header(HttpHeaders.IfMatch)))
            if (r.str("error") != null) call.respond(HttpStatusCode.PreconditionFailed, r) else call.respond(r)
        }
    }
    put("$base/source") {
        val id = call.bookId()
        val name = cleanName(call.request.queryParameters["name"])
        if (t.sourceExt(name) == null) fail(HttpStatusCode.BadRequest, "Upload an FB2, EPUB or TXT file", "unsupported")
        call.withBody { call.respond(t.putSource(id, name, it, tag(call.request.header(HttpHeaders.IfMatch)))) }
    }
    delete(base) { val id = call.bookId(); t.delete(id); call.respond(j()) }
}
