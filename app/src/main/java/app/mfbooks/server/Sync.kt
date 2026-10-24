package app.mfbooks.server

import android.content.Context
import androidx.room.withTransaction
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.mfbooks.MFBooksApp
import app.mfbooks.data.Book
import app.mfbooks.data.BookProgress
import app.mfbooks.data.BookSettings
import app.mfbooks.data.BookState
import app.mfbooks.data.Bookmark
import app.mfbooks.data.HistoryEvent
import app.mfbooks.data.ListeningSession
import app.mfbooks.data.PendingChange
import app.mfbooks.data.Settings
import app.mfbooks.data.SyncSeen
import app.mfbooks.sync.BookData
import app.mfbooks.sync.BookmarkData
import app.mfbooks.sync.Change
import app.mfbooks.sync.ChapterData
import app.mfbooks.sync.HistoryData
import app.mfbooks.sync.Kind
import app.mfbooks.sync.ProgressData
import app.mfbooks.sync.SessionData
import app.mfbooks.sync.SettingsData
import app.mfbooks.sync.SyncJson
import app.mfbooks.sync.SyncRequest
import app.mfbooks.sync.SyncResponse
import app.mfbooks.sync.TrackData
import app.mfbooks.sync.decode
import app.mfbooks.sync.sameBook
import app.mfbooks.sync.toData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit

enum class SyncState { OFF, SYNCING, SYNCED, OFFLINE, FAILED }
data class SyncStatus(val state: SyncState = SyncState.OFF, val lastSync: Long = 0, val error: String? = null)

/** Settings that describe this phone's folders, not the owner's preferences: never synced. */
private val DEVICE_ONLY = setOf("saveInBookFolders", "useTagTitles", "keepScreenOnCharging")

/**
 * The sync engine (S1–S5, B1, B2). Local writes queue [PendingChange]s through SQL triggers; a run pushes them in
 * batches, then applies whatever the server sends back. The server's merge rules decide; the app applies the result.
 */
object Sync {
    private val _status = MutableStateFlow(SyncStatus())
    val status: StateFlow<SyncStatus> = _status
    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var appCtx: Context

    fun init(context: Context) {
        appCtx = context.applicationContext
        if (Server.connected) { _status.value = SyncStatus(SyncState.SYNCED, Server.lastSyncAt); schedule(context); request(context) }
    }

    /** Sync soon (app start, pause, book switch, a remote change); WorkManager waits for the network. */
    fun request(context: Context = appCtx) {
        if (!Server.connected) return
        WorkManager.getInstance(context).enqueueUniqueWork("sync", ExistingWorkPolicy.APPEND_OR_REPLACE,
            OneTimeWorkRequestBuilder<SyncWorker>().setConstraints(Constraints(requiredNetworkType = NetworkType.CONNECTED)).build())
    }

    private fun schedule(context: Context) = WorkManager.getInstance(context).enqueueUniquePeriodicWork("sync-periodic", ExistingPeriodicWorkPolicy.KEEP,
        PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
            .setConstraints(Constraints(requiredNetworkType = NetworkType.CONNECTED, requiresCharging = true)).build())

    fun connected(context: Context) {
        _status.value = SyncStatus(SyncState.SYNCING)
        schedule(context); request(context)
        Live.update()
    }

    fun disconnected(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork("sync-periodic")
        WorkManager.getInstance(context).cancelUniqueWork("sync")
        _status.value = SyncStatus()
        Cloud.clear(context)
        // Books that only stream from the server can't play without it.
        scope.launch { MFBooksApp.get(context).db.let { db -> db.sync().syncedBooks().filter { it.rootUri == CLOUD_ROOT }.forEach { db.library().removeBook(it.id) } } }
        Live.update()
    }

    fun revoked() {
        _status.value = SyncStatus(SyncState.OFF, error = "This phone was signed out by the server")
        scope.launch { MFBooksApp.get(appCtx).db.sync().let { it.clearPending(); it.clearSeen() }; disconnected(appCtx) }
    }

    /** App settings live in DataStore, outside Room's triggers. */
    fun settingsChanged(context: Context) {
        if (!Server.connected) return
        scope.launch { MFBooksApp.get(context).db.sync().addPending(PendingChange(tbl = "app", key = "app", at = System.currentTimeMillis())); request(context) }
    }

    suspend fun run(app: MFBooksApp) = mutex.withLock {
        if (!Server.connected) return@withLock
        _status.value = _status.value.copy(state = SyncState.SYNCING, error = null)
        try {
            Server.checkVersion(app.packageManager.getPackageInfo(app.packageName, 0).versionName ?: "0")
            val dao = app.db.sync()
            if (Server.lastSeq == 0L) restore(app)
            // Every local book gets a server id (its record backs up progress and stats even without a cloud copy).
            for (b in dao.booksWithoutServerId()) {
                dao.setServerId(b.id, UUID.randomUUID().toString())
                dao.addPending(PendingChange(tbl = "book", key = b.id, at = b.lastPlayedAt ?: b.addedAt))
            }
            while (true) {
                val max = dao.maxPending()
                val latest = dao.pending().filter { it.id <= max }.groupBy { it.tbl to it.key }.mapValues { (_, v) -> v.maxOf { it.at } }
                val changes = latest.mapNotNull { (k, at) -> build(app, k.first, k.second, at) }
                if (changes.isEmpty()) exchange(app, emptyList(), max)
                else changes.chunked(300).forEach { exchange(app, it, max) }
                dao.deletePendingUpTo(max)
                if (dao.maxPending() == max) break // nothing new while we talked to the server
            }
            Cloud.refresh(app)
            Server.lastSyncAt = System.currentTimeMillis()
            _status.value = SyncStatus(SyncState.SYNCED, Server.lastSyncAt)
        } catch (e: ServerError) {
            _status.value = SyncStatus(if (Server.connected) SyncState.FAILED else SyncState.OFF, Server.lastSyncAt, e.message)
            throw e
        } catch (e: IOException) {
            _status.value = SyncStatus(SyncState.OFFLINE, Server.lastSyncAt, e.message)
            throw e
        }
    }

    private suspend fun exchange(app: MFBooksApp, changes: List<Change>, sentUpTo: Long) {
        val body = SyncJson.encodeToJsonElement(SyncRequest(Server.lastSeq, System.currentTimeMillis(), changes))
        val r = SyncJson.decodeFromJsonElement<SyncResponse>(Server.post("/sync", body))
        apply(app, r.changes, sentUpTo)
        Server.lastSeq = r.seq
    }

    /** First sync on this phone: pull everything and match local books to server records by fingerprint (B1). */
    private suspend fun restore(app: MFBooksApp) {
        val dao = app.db.sync()
        val all = SyncJson.decodeFromJsonElement<SyncResponse>(Server.get("/sync?since=0"))
        val unmatched = dao.booksWithoutServerId().toMutableList()
        for (c in all.changes) if (c.kind == Kind.BOOK && !c.deleted && dao.bookByServerId(c.id) == null) {
            val server = runCatching { c.data.decode<BookData>() }.getOrNull() ?: continue
            val local = unmatched.firstOrNull { sameBook(bookData(app, it), server) } ?: continue
            dao.setServerId(local.id, c.id); unmatched -= local
        }
        // Back up everything already on the phone; time 0 = "older than whatever the server has".
        app.db.openHelper.writableDatabase.apply {
            execSQL("INSERT INTO PendingChange(tbl, `key`, at) SELECT 'book', id, COALESCE(lastPlayedAt, addedAt) FROM Book")
            execSQL("INSERT INTO PendingChange(tbl, `key`, at) SELECT 'progress', bookId, updatedAt FROM BookProgress")
            execSQL("INSERT INTO PendingChange(tbl, `key`, at) SELECT 'book_settings', bookId, 0 FROM BookSettings")
            execSQL("INSERT INTO PendingChange(tbl, `key`, at) SELECT 'bookmark', uuid, updatedAt FROM Bookmark")
            execSQL("INSERT INTO PendingChange(tbl, `key`, at) SELECT 'history', uuid, at FROM HistoryEvent")
            execSQL("INSERT INTO PendingChange(tbl, `key`, at) SELECT 'session', uuid, startedAt + listenedMs FROM ListeningSession")
            execSQL("INSERT INTO PendingChange(tbl, `key`, at) VALUES ('app', 'app', 0)")
        }
        apply(app, all.changes, app.db.sync().maxPending())
        Server.lastSeq = all.seq
    }

    suspend fun bookData(app: MFBooksApp, b: Book): BookData {
        val lib = app.db.library()
        return BookData(
            title = b.title, author = b.author, narrator = b.narrator, year = b.year, genre = b.genre, publisher = b.publisher,
            series = b.series, seriesIndex = b.seriesIndex, description = b.description, durationMs = b.durationMs, seedColor = b.seedColor,
            state = b.state.name, addedAt = b.addedAt, lastPlayedAt = b.lastPlayedAt, queuePosition = b.queuePosition, hidden = b.hidden,
            tracks = lib.tracks(b.id).map { TrackData(it.index, it.fileName, it.title, it.durationMs, it.sizeBytes, it.sha256) },
            chapters = lib.chapters(b.id).map { ChapterData(it.index, it.title, it.trackIndex, it.startInTrackMs, it.durationMs, it.startInBookMs) },
        )
    }

    private suspend fun build(app: MFBooksApp, tbl: String, key: String, at: Long): Change? {
        val lib = app.db.library(); val dao = app.db.sync()
        suspend fun sid(bookId: String) = lib.book(bookId)?.serverId
        return when (tbl) {
            Kind.BOOK -> lib.book(key)?.let { b ->
                val sid = b.serverId ?: return null
                // A cloud-only book's manifest belongs to the server; only the listening state is ours.
                val data = if (b.rootUri == CLOUD_ROOT) JsonObject(mapOf("state" to JsonPrimitive(b.state.name), "lastPlayedAt" to JsonPrimitive(b.lastPlayedAt),
                    "hidden" to JsonPrimitive(b.hidden), "queuePosition" to JsonPrimitive(b.queuePosition))) else bookData(app, b).toData()
                Change(Kind.BOOK, sid, sid, at, data = data)
            }
            Kind.PROGRESS -> { val sid = sid(key) ?: return null; val p = lib.progress(key) ?: return null
                Change(Kind.PROGRESS, sid, sid, at, base = dao.seen("progress:$sid") ?: 0, data = ProgressData(p.trackIndex, p.positionInTrackMs).toData()) }
            Kind.SETTINGS -> { val sid = sid(key) ?: return null; val s = lib.settings(key) ?: return null
                Change(Kind.SETTINGS, sid, sid, at, data = SettingsData(s.speed, s.savedToggleSpeed, s.pitch, s.boostMb, s.eqBandsDb, s.balance, s.mono,
                    s.skipSilence, s.skipStartMs, s.skipEndMs, s.repeatMode).toData()) }
            Kind.BOOKMARK -> dao.bookmark(key)?.let { m -> val sid = sid(m.bookId) ?: return null
                Change(Kind.BOOKMARK, key, sid, at, data = BookmarkData(m.trackIndex, m.positionMs, m.title, m.note, m.createdAt).toData()) }
                ?: Change(Kind.BOOKMARK, key, null, at, deleted = true)
            Kind.HISTORY -> dao.history(key)?.let { h -> val sid = sid(h.bookId) ?: return null
                Change(Kind.HISTORY, key, sid, at, data = HistoryData(h.action, h.trackIndex, h.positionMs, h.at).toData()) }
            Kind.SESSION -> dao.session(key)?.let { s ->
                Change(Kind.SESSION, key, sid(s.bookId), at, data = SessionData(s.bookTitle, s.bookPath, s.startedAt, s.listenedMs).toData()) }
            "app" -> Change(Kind.APP, "app", null, at, data = JsonObject(SyncJson.encodeToJsonElement(app.prefs.now()).jsonObject - DEVICE_ONLY))
            else -> null
        }
    }

    /**
     * Applies the server's records. Records written here must not echo back, so the pending rows this creates are dropped;
     * records the phone changed again after [sentUpTo] are left alone (the next round pushes them).
     */
    private suspend fun apply(app: MFBooksApp, changes: List<Change>, sentUpTo: Long) {
        if (changes.isEmpty()) return
        val lib = app.db.library(); val dao = app.db.sync(); val user = app.db.userData()
        val me = Server.link.value?.deviceId
        var appSettings: JsonObject? = null
        app.db.withTransaction {
            val before = dao.maxPending()
            val dirty = dao.pendingKeysAbove(sentUpTo).toSet()
            for (c in changes) {
                if (c.kind == Kind.PROGRESS) dao.setSeen(SyncSeen("progress:${c.id}", c.seq))
                if (c.deviceId == me) continue // our own write, already here
                val local = c.bookId?.let { dao.bookByServerId(it) } ?: if (c.kind == Kind.BOOK) dao.bookByServerId(c.id) else null
                if (local != null && "${keyKind(c.kind)}:${local.id}" in dirty) continue
                if ("${keyKind(c.kind)}:${c.id}" in dirty) continue
                runCatching { applyOne(app, c, local) }.onFailure { android.util.Log.w("Sync", "can't apply ${c.kind} ${c.id}", it) }
                if (c.kind == Kind.APP) appSettings = c.data
            }
            dao.deletePendingAbove(before)
        }
        appSettings?.let { d ->
            val merged = JsonObject(SyncJson.encodeToJsonElement(app.prefs.now()).jsonObject + (d - DEVICE_ONLY))
            runCatching { SyncJson.decodeFromJsonElement<Settings>(merged) }.getOrNull()?.let { s -> app.prefs.update(false) { s } }
        }
    }

    private fun keyKind(kind: String) = if (kind == Kind.APP) "app" else kind

    private suspend fun applyOne(app: MFBooksApp, c: Change, local: Book?) {
        val lib = app.db.library(); val dao = app.db.sync(); val user = app.db.userData()
        val d = c.data
        when (c.kind) {
            Kind.BOOK -> {
                local ?: return // unknown here: a cloud copy shows up through Cloud.refresh, anything else stays on the server
                if (c.deleted) { if (local.rootUri == CLOUD_ROOT) lib.removeBook(local.id) else dao.setServerId(local.id, null); return }
                dao.applyBookState(local.id, d.str("state")?.let { s -> BookState.entries.firstOrNull { it.name == s } } ?: local.state,
                    if (d.containsKey("lastPlayedAt")) d.num("lastPlayedAt")?.toLong() else local.lastPlayedAt,
                    (d["hidden"] as? JsonPrimitive)?.content?.toBooleanStrictOrNull() ?: local.hidden,
                    if (d.containsKey("queuePosition")) d.num("queuePosition")?.toInt() else local.queuePosition)
            }
            Kind.PROGRESS -> {
                local ?: return
                val p = d.decode<ProgressData>()
                val mine = lib.progress(local.id)
                if (mine == null || c.updatedAt > mine.updatedAt) lib.upsertProgress(BookProgress(local.id, p.trackIndex, p.positionInTrackMs, c.updatedAt))
            }
            Kind.SETTINGS -> {
                local ?: return
                val s = d.decode<SettingsData>()
                lib.upsertSettings(BookSettings(local.id, s.speed, s.savedToggleSpeed, s.pitch, s.boostMb, s.eqBandsDb, s.balance, s.mono,
                    s.skipSilence, s.skipStartMs, s.skipEndMs, s.repeatMode, c.updatedAt))
            }
            Kind.BOOKMARK -> {
                if (c.deleted) return dao.deleteBookmark(c.id)
                local ?: return
                val m = d.decode<BookmarkData>()
                dao.upsertBookmark(Bookmark(dao.bookmark(c.id)?.id ?: 0, local.id, m.trackIndex, m.positionMs, m.title, m.note, m.createdAt, c.id, c.updatedAt))
            }
            Kind.HISTORY -> {
                local ?: return
                val h = d.decode<HistoryData>()
                dao.insertHistory(HistoryEvent(0, local.id, h.action, h.trackIndex, h.positionMs, h.at, c.id))
            }
            Kind.SESSION -> {
                val s = d.decode<SessionData>()
                dao.upsertSession(ListeningSession(dao.session(c.id)?.id ?: 0, local?.id ?: c.bookId.orEmpty(), s.bookTitle, s.bookPath, s.startedAt, s.listenedMs, c.id))
            }
        }
    }
}

class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try {
        Sync.run(MFBooksApp.get(applicationContext)); Result.success()
    } catch (e: ServerError) {
        if (e.code >= 500) Result.retry() else Result.failure()
    } catch (e: IOException) { Result.retry() }
}
