package app.mfbooks.playback

import android.content.Context
import android.os.SystemClock
import androidx.media3.common.Player
import app.mfbooks.MFBooksApp
import app.mfbooks.data.Book
import app.mfbooks.data.BookProgress
import app.mfbooks.data.BookState
import app.mfbooks.data.HistoryEvent
import app.mfbooks.data.ListeningSession
import app.mfbooks.library.Sidecar
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** The book currently in the player, with its playlist spans. [book] is refreshed as its state changes. */
class LoadedBook(
    var book: Book, val chapters: List<app.mfbooks.data.Chapter>, val spans: List<ItemSpan>,
    val initialSettings: app.mfbooks.data.BookSettings? = null,
)

/**
 * Progress, state transitions, listening sessions and history (§5.3). Snapshots are taken on the main thread;
 * writes go to a scope that outlives the service so the last save on destroy still lands.
 */
class ProgressSaver(private val context: Context, private val player: Player, private val current: () -> LoadedBook?) {
    private val app = MFBooksApp.get(context)
    private val lib = app.db.library()
    private val user = app.db.userData()
    // ponytail: process-lifetime serial scope (FIFO keeps insert-before-update); writes must survive service teardown.
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))

    private var sessionId: Long? = null
    private var sessionBook: String? = null
    private var listenedMs = 0L
    private var playingSince: Long? = null
    private var lastSidecarAt = 0L
    var statsEnabled = true

    data class Snapshot(val book: Book, val trackIndex: Int, val positionInTrackMs: Long, val positionInBookMs: Long)

    fun snapshot(): Snapshot? {
        val lb = current() ?: return null
        val item = player.currentMediaItem ?: return null
        if (item.mediaMetadata.extras?.getString(ItemExtras.BOOK_ID) != lb.book.id) return null
        val i = player.currentMediaItemIndex
        val s = lb.spans.getOrNull(i) ?: return null
        val pos = player.currentPosition.coerceIn(0, s.durationMs)
        return Snapshot(lb.book, s.trackIndex, s.startInTrackMs + pos, s.startInBookMs + pos)
    }

    /** Ticks every 5 s while playing. */
    fun start(scope: CoroutineScope) = scope.launch {
        while (isActive) {
            delay(5_000)
            if (player.isPlaying) save(sidecar = System.currentTimeMillis() - lastSidecarAt > 5 * 60_000)
        }
    }

    /** Upsert progress now. [sidecar] also writes the book-folder sidecar (pause / stop / book switch / ≤ 5 min). */
    fun save(sidecar: Boolean = false, finished: Boolean = false) {
        val snap = snapshot() ?: return
        val lb = current() ?: return
        val now = System.currentTimeMillis()
        val playing = player.isPlaying
        val newState = when {
            finished -> BookState.FINISHED
            snap.book.state == BookState.NEW && snap.positionInBookMs > 10_000 && playing -> BookState.STARTED
            snap.book.state == BookState.FINISHED && playing && !finished && snap.positionInBookMs < snap.book.durationMs - 60_000 -> BookState.STARTED
            else -> snap.book.state
        }
        if (newState != snap.book.state) lb.book = lb.book.copy(state = newState)
        if (playing) lb.book = lb.book.copy(lastPlayedAt = now)
        val book = lb.book
        val progress = BookProgress(book.id, snap.trackIndex, snap.positionInTrackMs, now)
        tickSession(book)
        if (sidecar) lastSidecarAt = now
        io.launch {
            lib.upsertProgress(progress)
            if (newState != snap.book.state) lib.setState(book.id, newState)
            if (playing || book.lastPlayedAt == null) lib.setLastPlayed(book.id, now)
            if (sidecar) Sidecar.writeProgress(context, book, progress, lib.settings(book.id))
        }
    }

    fun onIsPlayingChanged(playing: Boolean) {
        val now = SystemClock.elapsedRealtime()
        if (playing) {
            if (playingSince == null) playingSince = now
            current()?.let { tickSession(it.book) }
        } else {
            playingSince?.let { listenedMs += now - it }
            playingSince = null
            closeSession()
        }
    }

    /** Opens/updates/rotates the listening session for [book]. */
    private fun tickSession(book: Book) {
        if (!statsEnabled) return
        val since = playingSince ?: return
        if (sessionBook != book.id) closeSession()
        val now = SystemClock.elapsedRealtime()
        listenedMs += now - since
        playingSince = now
        val ms = listenedMs
        if (sessionBook == null) {
            sessionBook = book.id
            val s = ListeningSession(bookId = book.id, bookTitle = book.title, bookPath = book.relativePath,
                startedAt = System.currentTimeMillis() - ms, listenedMs = ms)
            io.launch { sessionId = user.insertSession(s) }
        } else io.launch { sessionId?.let { user.updateSession(it, ms) } }
    }

    private fun closeSession() {
        if (sessionBook == null) { listenedMs = 0; return }
        val ms = listenedMs
        io.launch { sessionId?.let { user.updateSession(it, ms) }; sessionId = null }
        sessionBook = null
        listenedMs = 0
    }

    /** History event at the current position (call *before* the seek it describes). */
    fun history(action: String) {
        val snap = snapshot() ?: return
        val e = HistoryEvent(bookId = snap.book.id, action = action, trackIndex = snap.trackIndex,
            positionMs = snap.positionInTrackMs, at = System.currentTimeMillis())
        io.launch { user.insertHistory(e) }
    }
}
