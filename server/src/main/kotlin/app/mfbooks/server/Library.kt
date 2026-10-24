package app.mfbooks.server

import app.mfbooks.library.AUDIO_EXTENSIONS
import app.mfbooks.library.ByteSource
import app.mfbooks.library.FileInput
import app.mfbooks.library.IMAGE_EXTENSIONS
import app.mfbooks.library.MergeInput
import app.mfbooks.library.NaturalOrder
import app.mfbooks.library.RawChapter
import app.mfbooks.library.TagInfo
import app.mfbooks.library.decodeText
import app.mfbooks.library.fill
import app.mfbooks.library.mergeBook
import app.mfbooks.library.parseCue
import app.mfbooks.library.parseFile
import app.mfbooks.library.parseNfo
import app.mfbooks.library.parseOpenbookJson
import app.mfbooks.library.rankCoverCandidates
import app.mfbooks.sync.BookData
import app.mfbooks.sync.Change
import app.mfbooks.sync.ChapterData
import app.mfbooks.sync.Kind
import app.mfbooks.sync.SyncJson
import app.mfbooks.sync.TrackData
import app.mfbooks.sync.decode
import app.mfbooks.sync.toData
import io.ktor.http.ContentDisposition
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.http.content.LocalFileContent
import io.ktor.http.defaultForFileExtension
import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.origin
import io.ktor.server.request.contentType
import io.ktor.server.request.header
import io.ktor.server.request.receive
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondOutputStream
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.utils.io.jvm.javaio.toInputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

private const val MAX_CHUNK = 16L shl 20
private const val LINK_TTL_MS = 6 * 3600_000L

data class FileRow(val bookId: String, val idx: Int, val path: String, val size: Long, val sha256: String?, val received: Long,
                   val state: String, val format: String, val original: Boolean, val verified: Boolean)

class Library(private val app: App) {
    private val db = app.db
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun files(bookId: String) = db.query("SELECT * FROM files WHERE book_id = ? ORDER BY idx", bookId) {
        FileRow(it.getString("book_id"), it.getInt("idx"), it.getString("path"), it.getLong("size"), it.getString("sha256"), it.getLong("received"),
            it.getString("state"), it.getString("format"), it.getInt("original") != 0, it.getInt("verified") != 0)
    }

    fun cover(bookId: String) = File(app.books, bookId).listFiles()?.firstOrNull { it.name.startsWith("cover.") }

    fun book(id: String): Change = app.records.get(Kind.BOOK, id)?.takeIf { !it.deleted } ?: fail(HttpStatusCode.NotFound, "No such book")

    private fun cloudJson(id: String, data: JsonObject, files: List<FileRow>): JsonObject? {
        if (files.isEmpty()) return null
        val c = db.query("SELECT conversion, progress, error FROM cloud WHERE book_id = ?", id) { Triple(it.getString(1), it.getDouble(2), it.getString(3)) }.firstOrNull()
        val tracks = (data["tracks"] as? JsonArray)?.size ?: files.size
        val ready = files.count { it.state == "ready" }
        return j("files" to tracks, "ready" to ready, "bytes" to files.sumOf { it.received }, "verified" to (ready == tracks && files.all { it.verified }),
            "conversion" to (c?.first ?: "none"), "progress" to (c?.second ?: 0.0), "error" to c?.third)
    }

    private fun progressJson(p: Change?, book: JsonObject): JsonObject? {
        p ?: return null
        val track = p.data.long("trackIndex") ?: 0
        val before = (book["tracks"] as? JsonArray).orEmpty().map { it as JsonObject }.filter { (it.long("index") ?: 0) < track }.sumOf { it.long("durationMs") ?: 0 }
        val pos = p.data.long("positionInTrackMs") ?: 0
        return j("trackIndex" to track, "positionInTrackMs" to pos, "positionInBookMs" to before + pos, "updatedAt" to p.updatedAt, "seq" to p.seq)
    }

    fun summary(r: Change, progress: Change?, files: List<FileRow>) = JsonObject(
        r.data.filterKeys { it != "tracks" && it != "chapters" && it != "description" } + j(
            "id" to r.id, "updatedAt" to r.updatedAt, "cover" to (cover(r.id) != null),
            "cloud" to cloudJson(r.id, r.data, files), "progress" to progressJson(progress, r.data),
        )
    )

    fun summaries(): List<JsonObject> {
        val progress = app.records.all(Kind.PROGRESS).associateBy { it.id }
        return app.records.all(Kind.BOOK).map { summary(it, progress[it.id], files(it.id)) }
    }

    fun detail(id: String): JsonObject {
        val r = book(id)
        val p = app.records.get(Kind.PROGRESS, id)
        val files = files(id).associateBy { it.idx }
        val tracks = (r.data["tracks"] as? JsonArray).orEmpty().map { t ->
            val o = t as JsonObject
            val f = files[o.long("index")?.toInt()]
            JsonObject(o + j("cloud" to f?.let { j("state" to it.state, "format" to it.format, "bytes" to it.received, "received" to it.received) }))
        }
        val bookmarks = app.records.ofBook(Kind.BOOKMARK, id).map { JsonObject(it.data + j("id" to it.id)) }
        val conflict = p?.conflict?.let { j("deviceName" to it.deviceName, "trackIndex" to it.data["trackIndex"], "positionInTrackMs" to it.data["positionInTrackMs"], "updatedAt" to it.updatedAt) }
        return JsonObject(summary(r, p, files.values.toList()) + j(
            "description" to r.data["description"], "tracks" to tracks, "chapters" to (r.data["chapters"] ?: JsonArray(emptyList())),
            "settings" to app.records.get(Kind.SETTINGS, id)?.data, "bookmarks" to bookmarks, "conflict" to conflict,
        ))
    }

    /** The app's upload: the manifest first; one empty cloud file per track, filled by chunks. */
    fun register(id: String, data: BookData, deviceId: String) {
        app.records.put(Change(Kind.BOOK, id, id, System.currentTimeMillis(), data = data.toData(), deviceId = deviceId))
        File(app.books, id).mkdirs()
        for (t in data.tracks) db.update(
            "INSERT OR IGNORE INTO files(book_id, idx, path, size, sha256, state, format) VALUES (?, ?, ?, ?, ?, 'receiving', ?)",
            id, t.index, "$id/${t.index}.${ext(t.fileName)}", t.sizeBytes, t.sha256?.lowercase(), ext(t.fileName),
        )
        db.update("INSERT OR IGNORE INTO cloud(book_id, conversion) VALUES (?, 'none')", id)
        app.live.changed(app.records.seq())
    }

    /** Called when a file of an app upload is complete and verified. */
    fun fileReady(bookId: String) {
        if (files(bookId).all { it.state == "ready" }) app.converter.enqueue(bookId)
        app.live.book(bookId)
    }

    fun deleteCloud(id: String) {
        files(id).forEach { File(app.books, it.path).delete(); File(app.books, it.path + ".part").delete() }
        db.update("DELETE FROM files WHERE book_id = ?", id)
        db.update("DELETE FROM cloud WHERE book_id = ?", id)
        app.live.book(id)
    }

    fun deleteBook(id: String) {
        deleteCloud(id)
        File(app.books, id).deleteRecursively()
        app.records.putServer(Kind.BOOK, id, j(), id, deleted = true)
        app.live.changed(app.records.seq())
    }

    // ---- web and "Add to cloud" uploads: an audio file or a zip of a book folder ----

    fun upload(id: String) = db.query("SELECT * FROM uploads WHERE id = ?", id) {
        j("id" to it.getString("id"), "name" to it.getString("name"), "size" to it.getLong("size"), "offset" to it.getLong("offset"),
            "state" to it.getString("state"), "bookId" to it.getString("book_id"), "error" to it.getString("error"))
    }.firstOrNull() ?: fail(HttpStatusCode.NotFound, "No such upload")

    fun resumeImports() = db.query("SELECT id FROM uploads WHERE state = 'importing'") { it.getString(1) }.forEach(::startImport)

    fun startImport(uploadId: String) {
        db.update("UPDATE uploads SET state = 'importing' WHERE id = ?", uploadId)
        scope.launch {
            val dir = File(app.uploads, "$uploadId.d")
            try {
                val bookId = import(uploadId, dir)
                db.update("UPDATE uploads SET state = 'done', book_id = ? WHERE id = ?", bookId, uploadId)
                File(app.uploads, "$uploadId.part").delete()
                log.info("Imported upload $uploadId as $bookId")
            } catch (e: Exception) {
                log.warn("Import of $uploadId failed", e)
                db.update("UPDATE uploads SET state = 'failed', error = ? WHERE id = ?", e.message ?: "Import failed", uploadId)
            } finally { dir.deleteRecursively() }
        }
    }

    private fun import(uploadId: String, dir: File): String {
        val name = db.query("SELECT name FROM uploads WHERE id = ?", uploadId) { it.getString(1) }.first()
        val part = File(app.uploads, "$uploadId.part")
        dir.deleteRecursively(); dir.mkdirs()
        if (ext(name) == "zip") unzip(part, dir) else part.copyTo(File(dir, File(name).name.ifEmpty { "audio" }))
        // The book folder is the one holding the audio files (a zip often wraps it in one more folder).
        val audioAll = dir.walk().filter { it.isFile && ext(it.name) in AUDIO_EXTENSIONS && !it.path.contains("__MACOSX") }.toList()
        val folder = audioAll.groupBy { it.parentFile }.maxByOrNull { it.value.size }?.key ?: fail(HttpStatusCode.BadRequest, "No audio files found")
        val audio = audioAll.filter { it.parentFile == folder }.sortedWith(compareBy(NaturalOrder) { it.name })
        val entries = folder.listFiles().orEmpty().filter { it.isFile }
        fun text(f: File?) = f?.let { runCatching { decodeText(it.readBytes()) }.getOrNull() }
        val folderName = if (folder == dir) name.substringBeforeLast('.') else folder.name
        val parentName = folder.parentFile.takeIf { folder != dir && it != dir }?.name

        val probes = audio.map { ffprobe(it) ?: fail(HttpStatusCode.BadRequest, "Can't read ${it.name}") }
        val inputs = audio.mapIndexed { i, f ->
            val p = probes[i]
            val (embedded, extra) = runCatching { FileSource(f).use { parseFile(it, f.name) } }.getOrDefault(null to null)
            val chapters = embedded ?: p.chapters.takeIf { it.size > 1 }?.map { (t, s) -> RawChapter(t, s) }
            FileInput(f.name, p.durationMs, tagsOf(p.tags).fill(extra), chapters)
        }
        val nfos = entries.filter { ext(it.name) == "nfo" }
        val nfo = text(nfos.firstOrNull { it.nameWithoutExtension.equals(folderName, true) } ?: nfos.firstOrNull())?.let { runCatching { parseNfo(it) }.getOrNull() }
        val cues = entries.filter { ext(it.name) == "cue" }.mapNotNull { f -> text(f)?.let { t -> runCatching { f.name to parseCue(t) }.getOrNull() } }
        val infoTxt = text(entries.firstOrNull { it.name.equals("info.txt", true) })
        val libby = text(entries.firstOrNull { it.name.equals("openbook.json", true) })?.let { runCatching { parseOpenbookJson(it) }.getOrNull() }.orEmpty()
        val m = mergeBook(MergeInput(folderName, parentName, inputs, nfo, cues, infoTxt, libby, true))

        // Tracks and chapters exactly as the app's scanner builds them.
        val id = UUID.randomUUID().toString()
        val bookDir = File(app.books, id).apply { mkdirs() }
        val tracks = ArrayList<TrackData>(); val chapters = ArrayList<ChapterData>()
        var offset = 0L
        audio.forEachIndexed { i, f ->
            val dur = inputs[i].durationMs
            val raw = m.chapters.getOrNull(i).orEmpty().ifEmpty { listOf(RawChapter(inputs[i].tags.title ?: f.nameWithoutExtension, 0)) }
            raw.forEachIndexed { k, ch ->
                val end = raw.getOrNull(k + 1)?.startMs ?: dur
                chapters += ChapterData(chapters.size, ch.title, i, ch.startMs, (end - ch.startMs).coerceAtLeast(0), offset + ch.startMs)
            }
            val sha = sha256File(f)
            tracks += TrackData(i, f.name, inputs[i].tags.title, dur, f.length(), sha)
            val dst = File(bookDir, "$i.${ext(f.name)}")
            f.copyTo(dst, overwrite = true)
            db.update("INSERT OR REPLACE INTO files(book_id, idx, path, size, sha256, received, state, format, original, verified) VALUES (?, ?, ?, ?, ?, ?, 'ready', ?, 1, 1)",
                id, i, "$id/${dst.name}", f.length(), sha, f.length(), ext(f.name))
            offset += dur
        }
        val images = entries.filter { ext(it.name) in IMAGE_EXTENSIONS }
        val coverName = rankCoverCandidates(images.map { it.name }, audio.map { it.nameWithoutExtension }, folderName).firstOrNull()
        val coverFile = images.firstOrNull { it.name == coverName }
        if (coverFile != null) coverFile.copyTo(File(bookDir, "cover.${ext(coverFile.name)}"), overwrite = true)
        else if (probes.first().hasPicture) runCatching { ffmpeg(listOf("-i", audio.first().path, "-an", "-map", "0:v:0", "-frames:v", "1", "-q:v", "2", File(bookDir, "cover.jpg").path)) }

        val data = BookData(
            title = m.title, author = m.author, narrator = m.narrator, year = m.year, genre = m.genre, publisher = m.publisher,
            series = m.series, seriesIndex = m.seriesIndex, description = m.description, durationMs = offset,
            addedAt = System.currentTimeMillis(), tracks = tracks, chapters = chapters,
        )
        app.records.putServer(Kind.BOOK, id, data.toData(), id)
        app.live.changed(app.records.seq())
        app.converter.enqueue(id)
        return id
    }

    private fun unzip(zip: File, dir: File) {
        val root = dir.canonicalPath + File.separator
        ZipFile(zip).use { z ->
            for (e in z.entries()) {
                if (e.isDirectory) continue
                val out = File(dir, e.name).canonicalFile
                if (!out.path.startsWith(root)) fail(HttpStatusCode.BadRequest, "Bad path in zip: ${e.name}")
                out.parentFile.mkdirs()
                z.getInputStream(e).use { i -> out.outputStream().use { i.copyTo(it) } }
            }
        }
    }

    // ---- signed links for Cast ----

    fun sign(path: String, exp: Long): String = Mac.getInstance("HmacSHA256").run {
        init(SecretKeySpec(app.secret, "HmacSHA256"))
        doFinal("$path/$exp".toByteArray()).joinToString("") { "%02x".format(it) }
    }

    fun signedOk(call: ApplicationCall, path: String): Boolean {
        val exp = call.request.queryParameters["exp"]?.toLongOrNull() ?: return false
        val sig = call.request.queryParameters["sig"] ?: return false
        return exp > System.currentTimeMillis() && MessageDigest.isEqual(sign(path, exp).toByteArray(), sig.toByteArray())
    }

    suspend fun export(call: ApplicationCall) {
        call.response.header(HttpHeaders.ContentDisposition, ContentDisposition.Attachment.withParameter(ContentDisposition.Parameters.FileName, "mfbooks-export.zip").toString())
        call.respondOutputStream(ContentType.Application.Zip) {
            ZipOutputStream(this).use { z ->
                val snap = File(app.uploads, "export-${UUID.randomUUID()}.db")
                try {
                    app.db.snapshot(snap.path)
                    z.putNextEntry(ZipEntry("mfbooks.db")); snap.inputStream().use { it.copyTo(z) }
                } finally { snap.delete() }
                z.putNextEntry(ZipEntry("records.json"))
                z.write(SyncJson.encodeToString(app.records.since(0)).toByteArray())
                app.books.listFiles().orEmpty().forEach { d -> cover(d.name)?.let { c -> z.putNextEntry(ZipEntry("covers/${d.name}.${ext(c.name)}")); c.inputStream().use { it.copyTo(z) } } }
            }
        }
    }
}

fun ext(name: String) = name.substringAfterLast('.', "").lowercase()

fun sha256File(f: File): String {
    val md = MessageDigest.getInstance("SHA-256")
    f.inputStream().use { i -> val buf = ByteArray(1 shl 16); while (true) { val n = i.read(buf); if (n < 0) break; md.update(buf, 0, n) } }
    return md.digest().joinToString("") { "%02x".format(it) }
}

private class FileSource(f: File) : ByteSource, AutoCloseable {
    private val raf = RandomAccessFile(f, "r")
    override val size = raf.length()
    override fun read(offset: Long, len: Int): ByteArray {
        val n = minOf(len.toLong(), (size - offset).coerceAtLeast(0)).toInt()
        return ByteArray(n).also { raf.seek(offset); raf.readFully(it) }
    }
    override fun close() = raf.close()
}

private fun tagsOf(t: Map<String, String>): TagInfo {
    fun g(vararg k: String) = k.firstNotNullOfOrNull { t[it]?.trim()?.ifEmpty { null } }
    return TagInfo(
        title = g("title"), artist = g("artist"), albumArtist = g("album_artist", "albumartist"), album = g("album"), composer = g("composer"),
        narrator = g("narrator", "narratedby"), year = g("date", "year")?.let { Regex("\\d{4}").find(it)?.value }, genre = g("genre"),
        publisher = g("publisher", "label"), series = g("series", "mvnm"), comment = g("comment"), description = g("description", "synopsis"),
        encoder = g("encoder", "encoded_by"), copyright = g("copyright"),
    )
}

private fun contentType(format: String) = when (format) {
    "opus", "ogg", "oga" -> ContentType("audio", "ogg")
    "mp3", "mpga" -> ContentType.Audio.MPEG
    "m4a", "m4b", "mp4", "aac", "3gp" -> ContentType.Audio.MP4
    "flac" -> ContentType("audio", "flac")
    "wav" -> ContentType("audio", "wav")
    "webm", "weba", "mka", "mkv" -> ContentType("audio", "webm")
    else -> ContentType.Application.OctetStream
}

/**
 * Appends one chunk at [offset] to [part]; any bytes past [offset] from an interrupted chunk are dropped first.
 * Returns the new length.
 */
private suspend fun appendChunk(call: ApplicationCall, part: File, offset: Long, size: Long): Long = withContext(Dispatchers.IO) {
    val limit = minOf(MAX_CHUNK, size - offset)
    RandomAccessFile(part, "rw").use { raf ->
        raf.setLength(offset); raf.seek(offset)
        val input = call.receiveChannel().toInputStream()
        val buf = ByteArray(1 shl 16)
        var total = 0L
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            total += n
            if (total > limit) { raf.setLength(offset); fail(HttpStatusCode.PayloadTooLarge, "Chunk too large") }
            raf.write(buf, 0, n)
        }
        offset + total
    }
}

private fun ApplicationCall.uploadOffset() = request.header("Upload-Offset")?.toLongOrNull() ?: fail(HttpStatusCode.BadRequest, "Upload-Offset header missing")

private fun ApplicationCall.origin(): String {
    val o = request.origin
    val default = (o.scheme == "https" && o.serverPort == 443) || (o.scheme == "http" && o.serverPort == 80)
    return "${o.scheme}://${o.serverHost}" + if (default) "" else ":${o.serverPort}"
}

/** Audio and covers: a device, or a signed link (Cast can't send our token). */
fun Route.mediaRoutes(app: App) {
    val lib = app.library
    get("/books/{id}/files/{index}") {
        val id = call.parameters["id"]!!; val idx = call.parameters["index"]!!.toInt()
        if (!lib.signedOk(call, "$id/$idx") && app.auth.device(call) == null) fail(HttpStatusCode.Unauthorized, "Sign in first")
        val f = lib.files(id).firstOrNull { it.idx == idx && it.state == "ready" } ?: fail(HttpStatusCode.NotFound, "Not in the cloud")
        call.respond(LocalFileContent(File(app.books, f.path), contentType(f.format)))
    }
    get("/books/{id}/cover") {
        val id = call.parameters["id"]!!
        if (!lib.signedOk(call, "$id/cover") && app.auth.device(call) == null) fail(HttpStatusCode.Unauthorized, "Sign in first")
        val c = lib.cover(id) ?: fail(HttpStatusCode.NotFound, "No cover")
        call.response.header(HttpHeaders.CacheControl, "private, max-age=86400")
        call.respond(LocalFileContent(c, ContentType.defaultForFileExtension(ext(c.name))))
    }
}

fun Route.libraryRoutes(app: App) {
    val lib = app.library
    lib.resumeImports()
    get("/books") { call.respond(lib.summaries()) }
    get("/books/{id}") { call.respond(lib.detail(call.parameters["id"]!!)) }
    post("/books") {
        val b = call.receive<JsonObject>()
        val id = b.str("id") ?: UUID.randomUUID().toString()
        val data = (b["data"] as? JsonObject)?.let { runCatching { it.decode<BookData>() }.getOrNull() } ?: fail(HttpStatusCode.BadRequest, "Bad book data")
        lib.register(id, data, call.device.id)
        call.respond(j("id" to id))
    }
    post("/books/{id}/cover") {
        val id = call.parameters["id"]!!
        lib.book(id)
        val type = call.request.contentType()
        val e = when (type.contentSubtype) { "png" -> "png"; "webp" -> "webp"; else -> "jpg" }
        File(app.books, id).mkdirs()
        lib.cover(id)?.delete()
        val f = File(app.books, "$id/cover.$e")
        appendChunk(call, f, 0, 10L shl 20)
        call.respond(j())
    }
    get("/books/{id}/files/{index}/offset") {
        val f = lib.files(call.parameters["id"]!!).firstOrNull { it.idx == call.parameters["index"]!!.toInt() } ?: fail(HttpStatusCode.NotFound, "No such file")
        call.respond(j("offset" to f.received, "size" to f.size))
    }
    post("/books/{id}/files/{index}") {
        val id = call.parameters["id"]!!; val idx = call.parameters["index"]!!.toInt()
        val f = lib.files(id).firstOrNull { it.idx == idx } ?: fail(HttpStatusCode.NotFound, "No such file")
        if (f.state == "ready") return@post call.respond(j("offset" to f.size))
        val off = call.uploadOffset()
        if (off != f.received) return@post call.respond(HttpStatusCode.Conflict, j("error" to "Offset mismatch", "offset" to f.received))
        val part = File(app.books, f.path + ".part")
        val n = appendChunk(call, part, off, f.size)
        if (n < f.size) {
            app.db.update("UPDATE files SET received = ? WHERE book_id = ? AND idx = ?", n, id, idx)
            return@post call.respond(j("offset" to n))
        }
        val sha = withContext(Dispatchers.IO) { sha256File(part) }
        if (f.sha256 != null && sha != f.sha256) {
            part.delete()
            app.db.update("UPDATE files SET received = 0 WHERE book_id = ? AND idx = ?", id, idx)
            fail(HttpStatusCode.UnprocessableEntity, "Checksum mismatch; upload the file again")
        }
        part.renameTo(File(app.books, f.path))
        app.db.update("UPDATE files SET received = ?, sha256 = ?, state = 'ready', verified = ? WHERE book_id = ? AND idx = ?", n, sha, if (f.sha256 != null) 1 else 0, id, idx)
        lib.fileReady(id)
        call.respond(j("offset" to n))
    }
    post("/books/{id}/links") {
        val id = call.parameters["id"]!!
        lib.book(id)
        val exp = System.currentTimeMillis() + LINK_TTL_MS
        val base = "${call.origin()}/api/v1/books/$id"
        call.respond(j("files" to lib.files(id).filter { it.state == "ready" }.map { "$base/files/${it.idx}?exp=$exp&sig=${lib.sign("$id/${it.idx}", exp)}" },
            "cover" to lib.cover(id)?.let { "$base/cover?exp=$exp&sig=${lib.sign("$id/cover", exp)}" }, "expiresAt" to exp))
    }
    delete("/books/{id}/files") { lib.book(call.parameters["id"]!!); lib.deleteCloud(call.parameters["id"]!!); call.respond(j()) }
    delete("/books/{id}") { lib.book(call.parameters["id"]!!); lib.deleteBook(call.parameters["id"]!!); call.respond(j()) }

    post("/uploads") {
        val b = call.receive<JsonObject>()
        val name = b.str("name")?.trim()?.takeIf { it.isNotEmpty() } ?: fail(HttpStatusCode.BadRequest, "Name missing")
        val size = b.long("size")?.takeIf { it > 0 } ?: fail(HttpStatusCode.BadRequest, "Size missing")
        if (ext(name) != "zip" && ext(name) !in AUDIO_EXTENSIONS) fail(HttpStatusCode.BadRequest, "Upload an audio file or a zip of a book folder")
        val id = UUID.randomUUID().toString()
        app.db.update("INSERT INTO uploads(id, name, size, state, created_at) VALUES (?, ?, ?, 'receiving', ?)", id, name, size, System.currentTimeMillis())
        call.respond(lib.upload(id))
    }
    get("/uploads/{id}") { call.respond(lib.upload(call.parameters["id"]!!)) }
    post("/uploads/{id}") {
        val id = call.parameters["id"]!!
        val u = lib.upload(id)
        if (u.str("state") != "receiving") return@post call.respond(u)
        val off = call.uploadOffset()
        val have = u.long("offset")!!; val size = u.long("size")!!
        if (off != have) return@post call.respond(HttpStatusCode.Conflict, JsonObject(u + j("error" to "Offset mismatch")))
        val n = appendChunk(call, File(app.uploads, "$id.part"), off, size)
        app.db.update("UPDATE uploads SET offset = ? WHERE id = ?", n, id)
        if (n == size) lib.startImport(id)
        call.respond(lib.upload(id))
    }
}
