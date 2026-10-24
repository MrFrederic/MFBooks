package app.mfbooks.playback

import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import android.os.Looper
import androidx.core.content.ContextCompat
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import app.mfbooks.MFBooksApp
import app.mfbooks.data.Book
import app.mfbooks.data.Chapter
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

sealed interface SleepMode {
    data object Off : SleepMode
    data class Duration(val minutes: Int) : SleepMode
    data object EndOfChapter : SleepMode
}

data class SleepState(val mode: SleepMode = SleepMode.Off, val remainingMs: Long = 0, val fading: Boolean = false)

/** Snapshot of the player for the UI. Updated ~4×/s while playing. */
data class NowPlaying(
    val book: Book? = null,
    val chapters: List<Chapter> = emptyList(),
    val chapterIndex: Int = 0,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val positionInChapterMs: Long = 0,
    val chapterDurationMs: Long = 0,
    val positionInBookMs: Long = 0,
    val bookDurationMs: Long = 0,
    val speed: Float = 1f,
    val skipSilence: Boolean = false,
    val boostMb: Int = 0,
    val sleep: SleepState = SleepState(),
) {
    val chapter: Chapter? get() = chapters.getOrNull(chapterIndex)
}

/**
 * The UI's only door to playback: wraps a MediaController to [PlaybackService]. The UI never touches ExoPlayer.
 * Calls made before the controller connects are queued and run once it does.
 */
class PlayerConnection(private val context: Context) {
    private val _state = MutableStateFlow(NowPlaying())
    val state: StateFlow<NowPlaying> = _state
    private val _bookEnded = kotlinx.coroutines.flow.MutableSharedFlow<String>(extraBufferCapacity = 1)
    /** Emits the book id when playback reaches the end of the last chapter (UI: "end of book → show library"). */
    val bookEnded: kotlinx.coroutines.flow.SharedFlow<String> = _bookEnded

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val dao get() = MFBooksApp.get(context).db.library()
    private var future: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private val queue = ArrayDeque<(MediaController) -> Unit>()
    private var poll: Job? = null
    private var loadingBookId: String? = null

    init {
        scope.launch { connect(); showLastPlayed() }
    }

    /** Resume [bookId] at its saved position, or start at [chapterIndex] / from the beginning. */
    fun playBook(bookId: String, chapterIndex: Int? = null, fromStart: Boolean = false) = send(Command.PLAY_BOOK, Bundle().apply {
        putString(Command.EXTRA_BOOK_ID, bookId)
        putInt(Command.EXTRA_CHAPTER, chapterIndex ?: -1)
        putBoolean(Command.EXTRA_FROM_START, fromStart)
    })
    /** Plays at an exact position without auto-rewind (continuing another device's book here). */
    fun playAt(bookId: String, trackIndex: Int, positionInTrackMs: Long) = send(Command.PLAY_BOOK, Bundle().apply {
        putString(Command.EXTRA_BOOK_ID, bookId); putInt(Command.EXTRA_TRACK, trackIndex); putLong(Command.EXTRA_MS, positionInTrackMs)
    })
    fun playFromSearch(query: String) = send(Command.PLAY_SEARCH, Bundle().apply { putString(Command.EXTRA_QUERY, query) })
    fun playPause() = send(Command.PLAY_PAUSE)
    fun seekInChapter(ms: Long) = seekInBook((_state.value.chapter?.startInBookMs ?: 0) + ms.coerceAtLeast(0))
    fun seekInBook(ms: Long) = send(Command.SEEK_IN_BOOK, Bundle().apply { putLong(Command.EXTRA_MS, ms) })
    fun jumpTo(trackIndex: Int, positionInTrackMs: Long) = send(Command.JUMP_TO, Bundle().apply {
        putInt(Command.EXTRA_TRACK, trackIndex); putLong(Command.EXTRA_MS, positionInTrackMs)
    })
    fun skipBy(deltaMs: Long) = send(Command.SEEK_BY, Bundle().apply { putLong(Command.EXTRA_MS, deltaMs) })
    fun nextChapter() = send(Command.NEXT_CHAPTER)
    fun prevChapter() = send(Command.PREV_CHAPTER)
    fun setChapter(index: Int) = send(Command.SET_CHAPTER, Bundle().apply { putInt(Command.EXTRA_CHAPTER, index) })
    fun setSpeed(speed: Float, asDefault: Boolean = false) = send(Command.SET_SPEED, Bundle().apply {
        putFloat(Command.EXTRA_SPEED, speed); putBoolean(Command.EXTRA_AS_DEFAULT, asDefault)
    })
    fun toggleSpeed() = send(Command.TOGGLE_SPEED)
    fun setSkipSilence(enabled: Boolean) = send(Command.SET_SKIP_SILENCE, Bundle().apply { putBoolean(Command.EXTRA_ENABLED, enabled) })
    fun setBoost(mb: Int) = send(Command.SET_BOOST, Bundle().apply { putInt(Command.EXTRA_MB, mb) })
    fun setEq(bandsDb: List<Float>, balance: Float, mono: Boolean, pitch: Float) = send(Command.SET_EQ, Bundle().apply {
        putFloatArray(Command.EXTRA_BANDS, bandsDb.toFloatArray())
        putFloat(Command.EXTRA_BALANCE, balance); putBoolean(Command.EXTRA_MONO, mono); putFloat(Command.EXTRA_PITCH, pitch)
    })
    fun setSleep(mode: SleepMode) = when (mode) {
        SleepMode.Off -> send(Command.SLEEP_OFF)
        SleepMode.EndOfChapter -> send(Command.SLEEP_ON, Bundle().apply { putInt(Command.EXTRA_MINUTES, -1) })
        is SleepMode.Duration -> send(Command.SLEEP_ON, Bundle().apply { putInt(Command.EXTRA_MINUTES, mode.minutes) })
    }
    fun addBookmark(title: String? = null, note: String? = null) = send(Command.ADD_BOOKMARK, Bundle().apply {
        putBoolean(Command.EXTRA_QUIET, true)
        title?.let { putString(Command.EXTRA_TITLE, it) }; note?.let { putString(Command.EXTRA_NOTE, it) }
    })
    fun send(command: Command, args: Bundle = Bundle.EMPTY) = withController { it.sendCustomCommand(command.session(), args); Unit }

    // ---- connection ----------------------------------------------------------------------------------------------

    private fun withController(block: (MediaController) -> Unit) {
        if (Looper.myLooper() != Looper.getMainLooper()) { scope.launch { withController(block) }; return }
        val c = controller
        if (c != null && c.isConnected) block(c) else { queue += block; connect() }
    }

    private fun connect() {
        if (future != null) return
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val f = MediaController.Builder(context, token)
            .setListener(object : MediaController.Listener {
                override fun onDisconnected(controller: MediaController) = onLost()
                override fun onExtrasChanged(controller: MediaController, extras: Bundle) = refresh()
            })
            .setApplicationLooper(Looper.getMainLooper())
            .buildAsync()
        future = f
        f.addListener({
            val c = runCatching { f.get() }.getOrNull() ?: run { future = null; return@addListener }
            controller = c
            c.addListener(playerListener)
            while (queue.isNotEmpty()) queue.removeFirst()(c)
            refresh()
            if (c.isPlaying) startPolling()
        }, ContextCompat.getMainExecutor(context))
    }

    private fun onLost() {
        controller?.removeListener(playerListener)
        controller = null; future = null
        poll?.cancel()
        _state.update { it.copy(isPlaying = false, isBuffering = false, sleep = SleepState()) }
        // Reconnect lazily on the next call: reconnecting now would resurrect the service right after EXIT.
    }

    private val playerListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            if (events.contains(Player.EVENT_PLAYBACK_STATE_CHANGED) && player.playbackState == Player.STATE_ENDED)
                _state.value.book?.id?.let { _bookEnded.tryEmit(it) }
            refresh()
            if (player.isPlaying) startPolling() else poll?.cancel()
        }
    }

    private fun startPolling() {
        if (poll?.isActive == true) return
        poll = scope.launch { while (isActive) { refresh(); delay(250) } }
    }

    private fun refresh() {
        val c = controller ?: return
        val x = c.currentMediaItem?.mediaMetadata?.extras
        val bookId = x?.getString(ItemExtras.BOOK_ID)
        val se = c.sessionExtras
        val sleep = sleepFrom(se)
        if (bookId == null) {
            _state.update { it.copy(isPlaying = false, isBuffering = false, sleep = sleep) }
            return
        }
        if (bookId != _state.value.book?.id && loadingBookId != bookId) {
            loadingBookId = bookId
            scope.launch {
                val book = dao.book(bookId)
                val chapters = chaptersOrTracks(bookId, dao.chapters(bookId), dao.tracks(bookId))
                if (book != null) _state.update { it.copy(book = book, chapters = chapters) }
                loadingBookId = null
                refresh()
            }
        }
        val pos = c.currentPosition.coerceAtLeast(0)
        val dur = c.duration.takeIf { it > 0 } ?: c.currentMediaItem?.mediaMetadata?.durationMs ?: 0L
        _state.update {
            it.copy(
                chapterIndex = x.getInt(ItemExtras.CHAPTER),
                isPlaying = c.isPlaying,
                isBuffering = c.playbackState == Player.STATE_BUFFERING,
                positionInChapterMs = pos,
                chapterDurationMs = dur,
                positionInBookMs = x.getLong(ItemExtras.START_IN_BOOK) + pos,
                bookDurationMs = x.getLong(ItemExtras.BOOK_DURATION),
                speed = c.playbackParameters.speed,
                skipSilence = se.getBoolean(SessionExtras.SKIP_SILENCE),
                boostMb = se.getInt(SessionExtras.BOOST_MB),
                sleep = sleep,
            )
        }
    }

    private fun sleepFrom(b: Bundle) = SleepState(
        mode = when (b.getString(SessionExtras.SLEEP_MODE)) {
            "DURATION" -> SleepMode.Duration(b.getInt(SessionExtras.SLEEP_MINUTES))
            "EOC" -> SleepMode.EndOfChapter
            else -> SleepMode.Off
        },
        remainingMs = b.getLong(SessionExtras.SLEEP_REMAINING),
        fading = b.getBoolean(SessionExtras.SLEEP_FADING),
    )

    /** Nothing loaded yet: show the last played book at its saved position (mini player). */
    private suspend fun showLastPlayed() {
        if (_state.value.book != null) return
        val book = dao.lastPlayed() ?: return
        val chapters = chaptersOrTracks(book.id, dao.chapters(book.id), dao.tracks(book.id))
        val p = dao.progress(book.id)
        val i = p?.let { pr -> chapters.indexOfLast { it.trackIndex == pr.trackIndex && it.startInTrackMs <= pr.positionInTrackMs } }
            ?.takeIf { it >= 0 } ?: 0
        val ch = chapters.getOrNull(i)
        val inChapter = if (p != null && ch != null) (p.positionInTrackMs - ch.startInTrackMs).coerceIn(0, ch.durationMs) else 0
        if (_state.value.book != null) return
        _state.update {
            it.copy(book = book, chapters = chapters, chapterIndex = i, positionInChapterMs = inChapter,
                chapterDurationMs = ch?.durationMs ?: 0, positionInBookMs = (ch?.startInBookMs ?: 0) + inChapter,
                bookDurationMs = book.durationMs)
        }
    }
}
