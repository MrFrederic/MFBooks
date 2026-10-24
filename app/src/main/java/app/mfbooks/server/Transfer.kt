package app.mfbooks.server

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.ServiceInfo
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.mfbooks.MFBooksApp
import app.mfbooks.R
import app.mfbooks.data.Book
import app.mfbooks.library.Covers
import app.mfbooks.library.Sidecar
import app.mfbooks.library.displayName
import app.mfbooks.library.sha1
import app.mfbooks.playback.MediaServer
import app.mfbooks.sync.SyncJson
import app.mfbooks.sync.toData
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest

private const val CHUNK = 4 shl 20

/** What the transfer worker is doing right now, for the book page and the library badges. */
data class TransferProgress(val bookId: String?, val label: String, val fraction: Float)

/**
 * Uploads (U1–U3) and downloads (O3) run in a WorkManager foreground worker with a progress notification and a
 * Pause action; the queues survive restarts. Queue entries: `book:<local id>`, `file:<content uri>`, `down:<local id>`.
 */
object Transfer {
    private const val UP = "transfer-up"
    private const val DOWN = "transfer-down"
    private val _queue = MutableStateFlow<List<String>>(emptyList())
    val queue: StateFlow<List<String>> = _queue
    val current = MutableStateFlow<TransferProgress?>(null)
    private lateinit var appCtx: Context

    fun init(context: Context) {
        appCtx = context.applicationContext
        _queue.value = prefs().getString("queue", null)?.let { s -> JSONArray(s).let { a -> (0 until a.length()).map(a::getString) } }.orEmpty()
    }

    private fun prefs() = appCtx.getSharedPreferences("transfer", Context.MODE_PRIVATE)
    private fun save() = prefs().edit().putString("queue", JSONArray(_queue.value).toString()).apply()
    private fun add(items: List<String>) { _queue.update { (it + items).distinct() }; save() }
    fun remove(item: String) { _queue.update { it - item }; save() }

    fun uploadBooks(context: Context, ids: List<String>) { add(ids.map { "book:$it" }); start(context, upload = true) }
    fun addFile(context: Context, uri: Uri) { add(listOf("file:$uri")); start(context, upload = true) }
    fun download(context: Context, id: String) { add(listOf("down:$id")); start(context, upload = false) }
    fun resume(context: Context) { start(context, true); start(context, false) }

    /** Uploads wait for Wi-Fi; a download the owner asked for runs on any network. */
    private fun start(context: Context, upload: Boolean) = WorkManager.getInstance(context).enqueueUniqueWork(
        if (upload) UP else DOWN, ExistingWorkPolicy.KEEP,
        OneTimeWorkRequestBuilder<TransferWorker>().setInputData(workDataOf("upload" to upload))
            .setConstraints(Constraints(requiredNetworkType = if (upload) NetworkType.UNMETERED else NetworkType.CONNECTED)).build(),
    )

    /** True when the app may delete this book's files (risk 16); otherwise the owner removes them by hand. */
    suspend fun canDelete(context: Context, book: Book): Boolean = withContext(Dispatchers.IO) {
        val t = MFBooksApp.get(context).db.library().tracks(book.id).firstOrNull { !it.isRemote } ?: return@withContext false
        runCatching {
            context.contentResolver.query(Uri.parse(t.uri), arrayOf(Document.COLUMN_FLAGS), null, null, null)?.use { c ->
                c.moveToFirst() && (c.getInt(0) and Document.FLAG_SUPPORTS_DELETE) != 0
            } == true
        }.getOrDefault(false)
    }

    /**
     * "Free up space" (O1): deletes the audio files once the server verified every original; the book stays in the library
     * and streams from then on. Returns an error message, or null on success.
     */
    suspend fun offload(context: Context, book: Book): String? = withContext(Dispatchers.IO) {
        val app = MFBooksApp.get(context)
        val sid = book.serverId ?: return@withContext "Not synced yet"
        val cloud = Cloud.books.value[sid]
        if (cloud?.verified != true) return@withContext "The server hasn't verified this book yet"
        for (t in app.db.library().tracks(book.id)) {
            if (t.isRemote) continue
            val ok = runCatching { DocumentsContract.deleteDocument(context.contentResolver, Uri.parse(t.uri)) }.getOrDefault(false)
            if (!ok) return@withContext "Can't delete files in ${book.relativePath}. Remove them yourself; the book will stream."
            app.db.sync().setTrack(book.id, t.index, Server.fileUrl(sid, t.index), t.fileName, t.sizeBytes)
        }
        null
    }

    internal suspend fun run(w: TransferWorker, upload: Boolean) {
        val app = MFBooksApp.get(appCtx)
        while (true) {
            val item = _queue.value.firstOrNull { it.startsWith("down:") != upload } ?: break
            try {
                when {
                    item.startsWith("book:") -> uploadBook(app, w, item.removePrefix("book:"))
                    item.startsWith("file:") -> uploadFile(app, w, Uri.parse(item.removePrefix("file:")))
                    else -> downloadBook(app, w, item.removePrefix("down:"))
                }
                remove(item)
            } catch (e: CancellationException) { throw e } catch (e: ServerError) {
                if (e.code in 400..499) { remove(item); w.fail("${e.message}") } else throw e
            }
        }
        current.value = null
        Cloud.refreshSoon(appCtx)
    }

    private suspend fun uploadBook(app: MFBooksApp, w: TransferWorker, id: String) {
        val lib = app.db.library()
        if (lib.book(id)?.serverId == null) Sync.run(app)
        val book = lib.book(id) ?: return
        val sid = book.serverId ?: throw IOException("Not synced yet")
        val tracks = lib.tracks(id).filter { !it.isRemote }
        if (tracks.isEmpty()) return
        // The server checks every file against these on arrival (offload relies on it).
        tracks.forEachIndexed { i, t ->
            if (t.sha256 == null) {
                w.report(TransferProgress(id, "Checking ${book.title}", i.toFloat() / tracks.size))
                app.db.sync().setSha(id, t.index, open(Uri.parse(t.uri)).use(::sha256))
            }
        }
        Server.post("/books", JsonObject(mapOf("id" to JsonPrimitive(sid), "data" to Sync.bookData(app, lib.book(id)!!).toData())))
        book.coverUri?.let { c -> runCatching { java.io.File(Uri.parse(c).path!!).readBytes() }.getOrNull()?.let { Server.chunk("/books/$sid/cover", 0, it, it.size, "image/jpeg") } }
        val total = tracks.sumOf { it.sizeBytes }.coerceAtLeast(1)
        var done = 0L
        for (t in tracks) {
            val path = "/books/$sid/files/${t.index}"
            sendFile(w, path, Uri.parse(t.uri), Server.get("$path/offset").jsonObject.num("offset")?.toLong() ?: 0, t.sizeBytes) { sent ->
                TransferProgress(id, "Uploading ${book.title}", (done + sent).toFloat() / total)
            }
            done += t.sizeBytes
        }
    }

    private suspend fun uploadFile(app: MFBooksApp, w: TransferWorker, uri: Uri) {
        val name = appCtx.displayName(uri) ?: "book.zip"
        val size = appCtx.contentResolver.query(uri, arrayOf(Document.COLUMN_SIZE), null, null, null)?.use { if (it.moveToFirst()) it.getLong(0) else 0 } ?: 0
        val key = "upload:$uri"
        val id = prefs().getString(key, null) ?: Server.post("/uploads", JsonObject(mapOf("name" to JsonPrimitive(name), "size" to JsonPrimitive(size))))
            .jsonObject.str("id")!!.also { prefs().edit().putString(key, it).apply() }
        val offset = Server.get("/uploads/$id").jsonObject.num("offset")?.toLong() ?: 0
        sendFile(w, "/uploads/$id", uri, offset, size) { sent -> TransferProgress(null, "Uploading $name", sent.toFloat() / size.coerceAtLeast(1)) }
        prefs().edit().remove(key).apply()
    }

    /** Resumable chunks with `Upload-Offset`; a 409 tells where the server actually is. */
    private suspend fun sendFile(w: TransferWorker, path: String, uri: Uri, from: Long, size: Long, progress: (Long) -> TransferProgress) {
        var offset = from
        val buf = ByteArray(CHUNK)
        while (offset < size) {
            open(uri).use { input ->
                input.skipFully(offset)
                while (offset < size) {
                    w.report(progress(offset))
                    val n = input.readFully(buf, minOf(CHUNK.toLong(), size - offset).toInt())
                    if (n <= 0) throw IOException("File ended early")
                    val (code, r) = Server.chunk(path, offset, buf, n)
                    when (code) {
                        200 -> offset = r.num("offset")?.toLong() ?: (offset + n)
                        409 -> { offset = r.num("offset")?.toLong() ?: 0; return@use }
                        else -> throw ServerError(code, r.str("error") ?: "HTTP $code")
                    }
                }
            }
        }
    }

    /** O3: the cloud copy (Opus, or the original if it wasn't converted) into the book's folder, like a book the app converted itself. */
    private suspend fun downloadBook(app: MFBooksApp, w: TransferWorker, id: String) = withContext(Dispatchers.IO) {
        val lib = app.db.library(); val r = appCtx.contentResolver
        val book = lib.book(id) ?: return@withContext
        val sid = book.serverId ?: return@withContext
        val d = Server.get("/books/$sid").jsonObject
        val formats = (d["tracks"] as JsonArray).map { it.jsonObject }.associate { it.num("index")!!.toInt() to ((it["cloud"] as? JsonObject)?.str("format")) }
        val cloudOnly = book.rootUri == CLOUD_ROOT
        val root = if (cloudOnly) lib.rootsNow().firstOrNull() ?: throw ServerError(400, "Add a library folder first") else null
        val folder: Uri = if (root == null) Uri.parse(book.folderUri) else {
            val tree = Uri.parse(root.uri)
            val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
            val name = listOfNotNull(book.author, book.title).joinToString(" - ").replace(Regex("[\\\\/:*?\"<>|]"), "_").take(120)
            DocumentsContract.createDocument(r, parent, Document.MIME_TYPE_DIR, name) ?: throw IOException("Can't create a folder")
        }
        val tracks = lib.tracks(id).filter { it.isRemote }
        val chapters = lib.chapters(id)
        val files = JSONArray()
        tracks.forEachIndexed { i, t ->
            val opus = formats[t.index] == "opus"
            val name = if (opus && !t.fileName.endsWith(".opus", true)) t.fileName.substringBeforeLast('.') + ".opus" else t.fileName
            w.report(TransferProgress(id, "Downloading ${book.title}", i.toFloat() / tracks.size))
            val doc = DocumentsContract.createDocument(r, folder, MediaServer.mimeOf(name), name) ?: throw IOException("Can't create $name")
            var size = 0L
            Server.download("/books/$sid/files/${t.index}").use { resp -> r.openOutputStream(doc, "w")!!.use { out -> size = resp.body.byteStream().copyTo(out) } }
            app.db.sync().setTrack(id, t.index, doc.toString(), name, size)
            files.put(JSONObject().put("name", name).put("durationMs", t.durationMs).apply {
                if (opus) put("converted", JSONObject().put("from", t.fileName).put("codec", "opus").put("at", System.currentTimeMillis()))
                put("chapters", JSONArray(chapters.filter { it.trackIndex == t.index }.map { JSONObject().put("title", it.title).put("startMs", it.startInTrackMs) }))
            })
        }
        if (cloudOnly) {
            // Tags don't survive the trip into Ogg reliably, so the sidecar carries the metadata and chapters (§4.5).
            val fields = mapOf("title" to book.title, "author" to book.author, "narrator" to book.narrator, "year" to book.year, "genre" to book.genre,
                "publisher" to book.publisher, "series" to book.series, "seriesIndex" to book.seriesIndex, "description" to book.description)
            val sc = JSONObject().put("v", 1).put("serverId", sid).put("updatedAt", System.currentTimeMillis())
                .put("book", JSONObject(fields as Map<*, *>).put("userEdited", JSONArray(fields.filterValues { it != null }.keys)))
                .put("files", files)
            DocumentsContract.createDocument(r, folder, "application/octet-stream", Sidecar.NAME)?.let { u -> r.openOutputStream(u, "w")!!.use { it.write(sc.toString(1).toByteArray()) } }
            // The scanner takes the cover from the folder, like any other book.
            Covers.file(appCtx, id).takeIf { it.exists() }?.let { c ->
                DocumentsContract.createDocument(r, folder, "image/jpeg", "cover.jpg")?.let { u -> r.openOutputStream(u, "w")!!.use { o -> c.inputStream().use { it.copyTo(o) } } }
            }
            val newId = sha1(folder.toString()).take(16)
            val rel = r.query(folder, arrayOf(Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null } ?: book.title
            Covers.file(appCtx, id).takeIf { it.exists() }?.renameTo(Covers.file(appCtx, newId))
            app.db.sync().rekey(id, newId, root!!.uri, folder.toString(), rel)
            lib.book(newId)?.let { b -> if (b.coverUri != null) lib.upsertBook(b.copy(coverUri = Uri.fromFile(Covers.file(appCtx, newId)).toString())) }
        }
        app.scanner.enqueue(full = false)
    }

    private fun open(uri: Uri): InputStream = appCtx.contentResolver.openInputStream(uri) ?: throw IOException("Can't read $uri")
}

private fun sha256(input: InputStream): String {
    val md = MessageDigest.getInstance("SHA-256"); val buf = ByteArray(1 shl 16)
    while (true) { val n = input.read(buf); if (n < 0) break; md.update(buf, 0, n) }
    return md.digest().joinToString("") { "%02x".format(it) }
}

private fun InputStream.skipFully(n: Long) { var left = n; while (left > 0) { val s = skip(left); if (s <= 0) { if (read() < 0) return; left-- } else left -= s } }
private fun InputStream.readFully(buf: ByteArray, len: Int): Int { var off = 0; while (off < len) { val n = read(buf, off, len - off); if (n < 0) break; off += n }; return off }

class TransferWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    private val nm = context.getSystemService(NotificationManager::class.java)
    private var last = 0L

    override suspend fun doWork(): Result = try {
        setForeground(info(TransferProgress(null, "Preparing", 0f)))
        Transfer.run(this, inputData.getBoolean("upload", true)); Result.success()
    } catch (e: CancellationException) { throw e } catch (e: IOException) { Result.retry() } finally { Transfer.current.value = null }

    override suspend fun getForegroundInfo() = info(TransferProgress(null, "Preparing", 0f))

    suspend fun report(p: TransferProgress) {
        Transfer.current.value = p
        val now = System.currentTimeMillis()
        if (now - last < 1000) return
        last = now
        runCatching { setForeground(info(p)) }
    }

    fun fail(message: String) = nm.notify(NOTIFICATION_ID + 1, NotificationCompat.Builder(applicationContext, CHANNEL)
        .setSmallIcon(R.drawable.ic_mfbooks_mono).setContentTitle("Transfer failed").setContentText(message).build())

    private fun info(p: TransferProgress): ForegroundInfo {
        if (nm.getNotificationChannel(CHANNEL) == null) nm.createNotificationChannel(NotificationChannel(CHANNEL, "Cloud transfers", NotificationManager.IMPORTANCE_LOW))
        val pause = WorkManager.getInstance(applicationContext).createCancelPendingIntent(id) // the queue stays; resume from Settings → Server
        val open = applicationContext.packageManager.getLaunchIntentForPackage(applicationContext.packageName)
            ?.let { PendingIntent.getActivity(applicationContext, 0, it, PendingIntent.FLAG_IMMUTABLE) }
        val n = NotificationCompat.Builder(applicationContext, CHANNEL)
            .setSmallIcon(R.drawable.ic_mfbooks_mono).setContentTitle(p.label).setContentText("${(p.fraction * 100).toInt()} %")
            .setContentIntent(open).setProgress(1000, (p.fraction * 1000).toInt(), false)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS).setOngoing(true).setOnlyAlertOnce(true).setSilent(true)
            .addAction(0, "Pause", pause).build()
        return ForegroundInfo(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    companion object {
        private const val CHANNEL = "transfer"
        private const val NOTIFICATION_ID = 4210
    }
}
