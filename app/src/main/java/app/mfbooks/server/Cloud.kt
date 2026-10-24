package app.mfbooks.server

import android.content.Context
import androidx.room.withTransaction
import app.mfbooks.MFBooksApp
import app.mfbooks.data.Book
import app.mfbooks.data.BookProgress
import app.mfbooks.data.BookState
import app.mfbooks.data.Chapter
import app.mfbooks.data.Track
import app.mfbooks.library.Covers
import app.mfbooks.sync.SyncJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import java.io.File

/** Root uri of books that exist only on the server (uploaded from the browser or another phone). Their id is the server id. */
const val CLOUD_ROOT = "cloud"

/** A book's cloud copy as the server reports it (`BookSummary.cloud`). */
@Serializable
data class CloudInfo(
    val files: Int, val ready: Int, val bytes: Long, val verified: Boolean,
    val conversion: String = "none", val progress: Float = 0f, val error: String? = null,
) {
    val complete get() = files > 0 && ready == files
    val converting get() = conversion == "queued" || conversion == "converting"
}

/** The cloud side of the library (U4–U6): what the server holds, kept in a small cache file so badges show offline too. */
object Cloud {
    private val _books = MutableStateFlow<Map<String, CloudInfo>>(emptyMap())
    /** server id → cloud copy, only for books that have one. */
    val books: StateFlow<Map<String, CloudInfo>> = _books
    private lateinit var cache: File
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    /** Local book ids with at least one streamed track (offloaded or cloud-only). */
    lateinit var remote: StateFlow<Set<String>> private set

    fun init(context: Context) {
        cache = File(context.filesDir, "cloud.json")
        runCatching { _books.value = SyncJson.decodeFromString(cache.readText()) }
        remote = MFBooksApp.get(context).db.sync().remoteBookIds().map { it.toSet() }.stateIn(scope, SharingStarted.Eagerly, emptySet())
    }

    fun clear(context: Context) { _books.value = emptyMap(); cache.delete() }

    /** Live `book` events while converting. */
    fun onConversion(serverId: String, conversion: String?, progress: Double?) {
        if (conversion != "converting") return
        _books.update { m -> m[serverId]?.let { m + (serverId to it.copy(conversion = conversion, progress = progress?.toFloat() ?: it.progress)) } ?: m }
    }

    suspend fun refresh(app: MFBooksApp) {
        val list = Server.get("/books").jsonArray.map { it.jsonObject }
        val map = list.mapNotNull { b -> (b["cloud"] as? JsonObject)?.let { b.str("id")!! to SyncJson.decodeFromJsonElement(CloudInfo.serializer(), it) } }.toMap()
        _books.value = map
        runCatching { cache.writeText(SyncJson.encodeToString(map)) }
        val dao = app.db.sync(); val lib = app.db.library()
        for ((sid, c) in map) if (c.complete && dao.bookByServerId(sid) == null) runCatching { addCloudBook(app, sid) }
            .onFailure { android.util.Log.w("Cloud", "can't add $sid", it) }
        // A cloud-only book whose cloud copy was deleted is gone.
        for (b in dao.syncedBooks()) if (b.rootUri == CLOUD_ROOT && b.serverId !in map) lib.removeBook(b.id)
        // Covers for every synced book, so the web player shows phone-only books with their art too.
        val noCover = list.filter { it.str("cover") != "true" }.mapNotNull { it.str("id") }.toSet()
        for (b in dao.syncedBooks()) {
            val sid = b.serverId ?: continue
            val path = b.coverUri?.let { android.net.Uri.parse(it).path } ?: continue
            if (sid !in noCover || !coverTried.add(sid)) continue
            runCatching { File(path).readBytes().let { Server.chunk("/books/$sid/cover", 0, it, it.size, "image/jpeg") } }
        }
    }

    /** ponytail: one cover attempt per book per process; a failed one is retried after the next app start. */
    private val coverTried = java.util.Collections.synchronizedSet(HashSet<String>())

    /** A book that is only on the server becomes a library book that streams (O2). */
    private suspend fun addCloudBook(app: MFBooksApp, sid: String) {
        val d = Server.get("/books/$sid").jsonObject
        val cover = if (d.str("cover") == "true") runCatching {
            Server.download("/books/$sid/cover").use { r -> Covers.resolve(app, sid, emptyList(), r.body.bytes()) }
        }.getOrNull() else null
        val tracks = (d["tracks"] as? JsonArray).orEmpty().map { it.jsonObject }.map { t ->
            val i = t.num("index")!!.toInt()
            Track(sid, i, Server.fileUrl(sid, i), t.str("fileName").orEmpty(), t.str("title"), t.num("durationMs")?.toLong() ?: 0,
                t.num("sizeBytes")?.toLong() ?: 0, 0, t.str("sha256"))
        }
        val chapters = (d["chapters"] as? JsonArray).orEmpty().map { it.jsonObject }.map { c ->
            Chapter(sid, c.num("index")!!.toInt(), c.str("title").orEmpty(), c.num("trackIndex")!!.toInt(), c.num("startInTrackMs")?.toLong() ?: 0,
                c.num("durationMs")?.toLong() ?: 0, c.num("startInBookMs")?.toLong() ?: 0)
        }
        val book = Book(
            id = sid, rootUri = CLOUD_ROOT, folderUri = "", relativePath = d.str("title").orEmpty(), title = d.str("title") ?: "Untitled",
            author = d.str("author"), narrator = d.str("narrator"), year = d.str("year"), genre = d.str("genre"), publisher = d.str("publisher"),
            series = d.str("series"), seriesIndex = d.str("seriesIndex"), coverUri = cover?.first, seedColor = d.num("seedColor")?.toInt() ?: cover?.second,
            durationMs = d.num("durationMs")?.toLong() ?: tracks.sumOf { it.durationMs }, fileCount = tracks.size,
            state = d.str("state")?.let { s -> BookState.entries.firstOrNull { it.name == s } } ?: BookState.NEW,
            addedAt = d.num("addedAt")?.toLong() ?: System.currentTimeMillis(), lastPlayedAt = d.num("lastPlayedAt")?.toLong(),
            queuePosition = d.num("queuePosition")?.toInt(), hidden = d.str("hidden") == "true", description = d.str("description"),
            fieldSources = null, scanSignature = "", serverId = sid,
        )
        val p = d["progress"] as? JsonObject
        val dao = app.db.sync()
        app.db.withTransaction {
            val before = dao.maxPending()
            app.db.library().replaceBook(book, tracks, chapters)
            if (p != null) app.db.library().upsertProgress(BookProgress(sid, p.num("trackIndex")?.toInt() ?: 0, p.num("positionInTrackMs")?.toLong() ?: 0, p.num("updatedAt")?.toLong() ?: 0))
            dao.deletePendingAbove(before)
        }
    }

    /** Drops cached info for one book right away (after deleting its cloud copy). */
    fun forget(serverId: String) = _books.update { it - serverId }
    fun refreshSoon(context: Context) = scope.launch { runCatching { refresh(MFBooksApp.get(context)) } }
}
