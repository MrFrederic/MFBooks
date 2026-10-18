package app.mfbooks.playback

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.widget.Toast
import androidx.car.app.connection.CarConnection
import androidx.core.content.IntentCompat
import androidx.lifecycle.Observer
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.CommandButton
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSession.MediaItemsWithStartPosition
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import app.mfbooks.MFBooksApp
import app.mfbooks.R
import app.mfbooks.data.Book
import app.mfbooks.data.BookSettings
import app.mfbooks.data.BookState
import app.mfbooks.data.Bookmark
import app.mfbooks.data.EndOfBook
import app.mfbooks.data.Settings
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Keys of [MediaSession.getSessionExtras], read by [PlayerConnection]. */
object SessionExtras {
    const val SLEEP_MODE = "mfbooks.sleepMode"          // OFF / DURATION / EOC
    const val SLEEP_MINUTES = "mfbooks.sleepMinutes"
    const val SLEEP_REMAINING = "mfbooks.sleepRemainingMs"
    const val SLEEP_FADING = "mfbooks.sleepFading"
    const val SKIP_SILENCE = "mfbooks.skipSilence"
    const val BOOST_MB = "mfbooks.boostMb"
}

/** One MediaLibrarySession for the phone UI, notification, Bluetooth, Wear and Android Auto (§3.3, §5, §6). */
class PlaybackService : MediaLibraryService() {
    private val app by lazy { MFBooksApp.get(this) }
    private val dao by lazy { app.db.library() }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val effects = EffectsProcessor()
    private val boost = Boost()
    private lateinit var exo: ExoPlayer
    private lateinit var player: BookPlayer
    private lateinit var session: MediaLibrarySession
    private lateinit var sleep: SleepTimer
    private lateinit var saver: ProgressSaver
    private lateinit var tree: LibraryTree
    private lateinit var car: CarConnection

    private var settings = Settings()
    private var bookSettings: BookSettings? = null
    private var boostMb = 0
    private var loaded: LoadedBook? = null
    private var lastReloadAt = 0L
    private val needsLoadedBook = setOf(
        Command.REWIND_SMALL, Command.REWIND_BIG, Command.FWD_SMALL, Command.FWD_BIG, Command.SEEK_BY, Command.SEEK_IN_BOOK,
        Command.JUMP_TO, Command.SET_CHAPTER, Command.PREV_CHAPTER, Command.NEXT_CHAPTER, Command.PREV_FILE, Command.NEXT_FILE,
        Command.ADD_BOOKMARK, Command.NEXT_BOOKMARK,
    )
    private var pending: LoadedBook? = null
    private var pendingPausedAt: Long? = null
    private var bookSettingsJob: Job? = null
    private var pausedAt: Long? = null
    private var forceStopJob: Job? = null
    private var buttonsKey = ""

    private val carObserver = Observer<Int> {
        sleep.inCar = it == CarConnection.CONNECTION_TYPE_PROJECTION || it == CarConnection.CONNECTION_TYPE_NATIVE
    }

    override fun onCreate() {
        super.onCreate()
        exo = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_SPEECH).build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .setRenderersFactory(EffectsRenderersFactory(this, effects))
            .build()
        exo.addListener(listener)
        boost.attach(exo.audioSessionId)
        // ponytail: focus policy settings (pauseOnMessage, stopWhenOthersPlaying, resumeAfterCall) and
        // headsetConnectPlay use Media3's default focus handling for now (§5.2).
        player = BookPlayer(exo)
        saver = ProgressSaver(this, exo) { loaded }
        sleep = SleepTimer(this, exo, scope) { saver.history("SLEEP"); saver.save(sidecar = true) }
        tree = LibraryTree(this)
        val activity = packageManager.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        session = MediaLibrarySession.Builder(this, player, callback)
            .apply { activity?.let { setSessionActivity(it) } }
            .setMediaButtonPreferences(buttons())
            .build()
        saver.start(scope)
        scope.launch {
            app.prefs.settings.collect {
                settings = it; sleep.settings = it; saver.statsEnabled = it.statsEnabled
                applyEffects()
            }
        }
        scope.launch {
            sleep.state.map { Triple(it.mode, it.remainingMs / 1000, it.fading) }.distinctUntilChanged().collect {
                publishExtras(); updateButtons()
            }
        }
        car = CarConnection(this)
        car.type.observeForever(carObserver)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession = session

    override fun onDestroy() {
        saver.save(sidecar = true)
        saver.onIsPlayingChanged(false)
        car.type.removeObserver(carObserver)
        sleep.release()
        boost.release()
        session.release()
        exo.release()
        scope.cancel()
        super.onDestroy()
    }

    // ---- player events -------------------------------------------------------------------------------------------

    private val listener = object : Player.Listener {
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (playWhenReady) {
                pausedAt?.let { val n = autoRewindMs(System.currentTimeMillis() - it, settings.autoRewind); if (n > 0) seekBy(-n) }
                pausedAt = null
                saver.history("START")
            } else {
                pausedAt = System.currentTimeMillis()
                saver.history("PAUSE")
                forceStopJob?.cancel(); forceStopJob = null
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            saver.onIsPlayingChanged(isPlaying)
            if (isPlaying) {
                val h = settings.forceStopHours
                if (h > 0 && forceStopJob?.isActive != true)
                    forceStopJob = scope.launch { delay(h * 3_600_000L); exo.pause() }
            } else saver.save(sidecar = !exo.playWhenReady)
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            adoptIfNeeded(mediaItem)
            if (reason != Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED) saver.save()
        }

        override fun onPositionDiscontinuity(old: Player.PositionInfo, new: Player.PositionInfo, reason: Int) {
            if (reason != Player.DISCONTINUITY_REASON_SEEK) return
            saver.save()
            if (!exo.playWhenReady) pausedAt = null // the user chose a position while paused: don't rewind from it
        }

        override fun onPlaybackStateChanged(state: Int) {
            if (state == Player.STATE_ENDED) { saver.save(sidecar = true, finished = true); onBookEnded() }
        }

        /** A file vanished under us (e.g. converted to .opus): reload the book from Room at the saved position, once. */
        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
            val id = loaded?.book?.id ?: return
            if (error.errorCode !in 2000..2999) return // PlaybackException IO errors
            if (System.currentTimeMillis() - lastReloadAt < 10_000) return
            lastReloadAt = System.currentTimeMillis()
            val wasPlaying = exo.playWhenReady
            scope.launch {
                val r = resolve(id) ?: return@launch
                exo.setMediaItems(r.mediaItems, r.startIndex, r.startPositionMs); exo.prepare()
                if (wasPlaying) exo.play()
            }
        }

        override fun onAudioSessionIdChanged(audioSessionId: Int) = boost.attach(audioSessionId)
        override fun onPlaybackParametersChanged(p: PlaybackParameters) = updateButtons()
    }

    // ---- loading books -------------------------------------------------------------------------------------------

    private suspend fun load(bookId: String): Pair<LoadedBook, List<MediaItem>>? {
        val book = dao.book(bookId) ?: return null
        val tracks = dao.tracks(bookId)
        val have = tracks.map { it.index }.toSet()
        val chapters = chaptersOrTracks(bookId, dao.chapters(bookId), tracks).filter { it.trackIndex in have }
        if (chapters.isEmpty()) return null
        val bs = dao.settings(bookId)
        val spans = buildSpans(chapters, bs?.skipStartMs ?: 0, bs?.skipEndMs ?: 0)
        return LoadedBook(book, chapters, spans, bs) to buildMediaItems(book, chapters, tracks, spans)
    }

    /** Book → playlist + start position. Saves the outgoing book first (synchronously, before the swap). */
    private suspend fun resolve(bookId: String, chapter: Int? = null, fromStart: Boolean = false): MediaItemsWithStartPosition? {
        saver.save(sidecar = true)
        val (lb, items) = load(bookId) ?: return null
        val p = dao.progress(bookId)
        val resume = chapter == null && !fromStart && p != null && lb.book.state != BookState.FINISHED
        val (idx, pos) = when {
            chapter != null -> chapter.coerceIn(0, items.lastIndex) to 0L
            resume -> locateInTrack(lb.spans, p!!.trackIndex, p.positionInTrackMs)
            else -> 0 to 0L
        }
        pending = lb
        pendingPausedAt = if (resume) p!!.updatedAt else null
        return MediaItemsWithStartPosition(items, idx, pos)
    }

    private suspend fun resolveQuery(q: String): Book? =
        if (q.isBlank()) dao.lastPlayed() else dao.search(q.trim()).firstOrNull()
            ?: q.split(' ').filter { it.length > 2 }.firstNotNullOfOrNull { dao.search(it).firstOrNull() }

    /** Resolve controller-supplied items (AA taps, voice search, resumption) to a full book playlist. */
    private suspend fun resolveItems(items: List<MediaItem>, startIndex: Int, startPositionMs: Long): MediaItemsWithStartPosition {
        val item = items.getOrNull(startIndex) ?: items.firstOrNull()
        val query = item?.requestMetadata?.searchQuery
        val id = item?.mediaId.orEmpty()
        val r = when {
            query != null || item == null -> resolveQuery(query.orEmpty())?.let { resolve(it.id) }
            '/' in id -> resolve(id.substringBeforeLast('/'), id.substringAfterLast('/').toIntOrNull() ?: 0)
            id.isNotEmpty() -> resolve(id)
            else -> null
        }
        return r ?: if (items.all { it.localConfiguration != null }) MediaItemsWithStartPosition(items, startIndex, startPositionMs)
        else throw IllegalArgumentException("Unknown media id '$id'")
    }

    private fun adoptIfNeeded(item: MediaItem?) {
        val bookId = item?.mediaMetadata?.extras?.getString(ItemExtras.BOOK_ID) ?: return
        pending?.takeIf { it.book.id == bookId }?.let { adopt(it, pendingPausedAt); return }
        if (loaded?.book?.id == bookId) return
        scope.launch {
            val lb = load(bookId)?.first ?: return@launch
            if (exo.currentMediaItem?.mediaMetadata?.extras?.getString(ItemExtras.BOOK_ID) == bookId) adopt(lb, null)
        }
    }

    private fun adopt(lb: LoadedBook, resumePausedAt: Long?) {
        loaded = lb; pending = null; pendingPausedAt = null
        pausedAt = resumePausedAt
        bookSettings = lb.initialSettings
        applyEffects()
        bookSettingsJob?.cancel()
        bookSettingsJob = scope.launch { dao.settingsFlow(lb.book.id).collect { bookSettings = it; applyEffects() } }
    }

    private fun playBook(bookId: String, chapter: Int? = null, fromStart: Boolean = false, play: Boolean = true) {
        if (chapter == null && !fromStart && loaded?.book?.id == bookId && exo.mediaItemCount > 0) {
            if (play) { if (exo.playbackState == Player.STATE_IDLE) exo.prepare(); exo.play() }
            return
        }
        scope.launch {
            val r = resolve(bookId, chapter, fromStart) ?: return@launch
            exo.setMediaItems(r.mediaItems, r.startIndex, r.startPositionMs)
            exo.prepare()
            if (play) exo.play()
        }
    }

    private fun resumeLast() = scope.launch { dao.lastPlayed()?.let { playBook(it.id) } }

    private suspend fun neighbourBook(dir: Int): Book? {
        val books = dao.booksNow()
        val i = books.indexOfFirst { it.id == loaded?.book?.id }
        return if (i < 0) null else books.getOrNull(i + dir)
    }

    private fun onBookEnded() {
        if (settings.endOfBook == EndOfBook.NOTHING || settings.endOfBook == EndOfBook.SHOW_LIBRARY) return // SHOW_LIBRARY: the UI navigates (PlayerConnection.bookEnded)
        val cur = bookSettings
        scope.launch {
            val next = neighbourBook(1) ?: return@launch
            if (settings.endOfBook == EndOfBook.NEXT_BOOK_SAME_SETTINGS && cur != null) {
                val ns = dao.settings(next.id) ?: BookSettings(next.id)
                dao.upsertSettings(ns.copy(speed = cur.speed, pitch = cur.pitch, boostMb = cur.boostMb,
                    eqBandsDb = cur.eqBandsDb, balance = cur.balance, mono = cur.mono, skipSilence = cur.skipSilence))
            }
            playBook(next.id)
        }
    }

    // ---- effects / per-book settings -----------------------------------------------------------------------------

    private fun applyEffects() {
        val bs = bookSettings?.takeIf { it.bookId == loaded?.book?.id }
        val speed = (bs?.speed ?: settings.defaultSpeed).coerceIn(0.5f, 3f)
        val pitch = (bs?.pitch ?: 1f).coerceIn(0.5f, 2f)
        if (exo.playbackParameters.speed != speed || exo.playbackParameters.pitch != pitch)
            exo.playbackParameters = PlaybackParameters(speed, pitch)
        exo.skipSilenceEnabled = bs?.skipSilence ?: settings.defaultSkipSilence
        boostMb = bs?.boostMb ?: 0
        boost.set(boostMb)
        effects.set(parseBands(bs?.eqBandsDb), bs?.balance ?: 0f, bs?.mono ?: false)
        publishExtras()
        updateButtons()
    }

    private fun editBook(f: (BookSettings) -> BookSettings) {
        val id = loaded?.book?.id ?: return
        val next = f(bookSettings?.takeIf { it.bookId == id } ?: BookSettings(id))
        bookSettings = next
        applyEffects()
        scope.launch { dao.upsertSettings(f(dao.settings(id) ?: BookSettings(id))) }
    }

    private fun setSpeed(speed: Float, asDefault: Boolean = false) {
        val v = (Math.round(speed * 20) / 20f).coerceIn(0.5f, 3f)
        if (asDefault) scope.launch { app.prefs.update { it.copy(defaultSpeed = v) } }
        if (loaded == null) exo.playbackParameters = PlaybackParameters(v, exo.playbackParameters.pitch)
        else editBook { it.copy(speed = v) }
    }

    private fun publishExtras() {
        if (!::session.isInitialized) return
        val s = sleep.state.value
        session.setSessionExtras(Bundle().apply {
            putString(SessionExtras.SLEEP_MODE, when (s.mode) { SleepMode.Off -> "OFF"; SleepMode.EndOfChapter -> "EOC"; is SleepMode.Duration -> "DURATION" })
            putInt(SessionExtras.SLEEP_MINUTES, (s.mode as? SleepMode.Duration)?.minutes ?: 0)
            putLong(SessionExtras.SLEEP_REMAINING, s.remainingMs)
            putBoolean(SessionExtras.SLEEP_FADING, s.fading)
            putBoolean(SessionExtras.SKIP_SILENCE, exo.skipSilenceEnabled)
            putInt(SessionExtras.BOOST_MB, boostMb)
        })
    }

    private fun buttons(): List<CommandButton> =
        mediaButtons(this, settings, exo.playbackParameters.speed, if (::sleep.isInitialized) sleep.state.value.mode else SleepMode.Off)

    private fun updateButtons() {
        if (!::session.isInitialized) return
        val key = "${settings.prevAction}|${settings.nextAction}|${settings.rewindSmallSec}|${settings.fwdSmallSec}|" +
            "${exo.playbackParameters.speed}|${sleep.state.value.mode}"
        if (key == buttonsKey) return
        buttonsKey = key
        session.setMediaButtonPreferences(buttons())
    }

    // ---- commands ------------------------------------------------------------------------------------------------

    private fun seekBy(deltaMs: Long) {
        val lb = loaded
        if (lb == null || lb.spans.size != exo.mediaItemCount) {
            exo.seekTo((exo.currentPosition + deltaMs).coerceAtLeast(0)); return
        }
        val (i, off) = seekByMs(lb.spans, exo.currentMediaItemIndex, exo.currentPosition, deltaMs)
        exo.seekTo(i, off)
    }

    /** Runs a §5.6 mapping (next/prev, headset presses). [history] overrides the event name for seconds skips. */
    private fun runMapped(a: MappedAction, history: String? = null) {
        val skip = a.skipMs(settings)
        if (skip != null) { saver.history(history ?: if (skip < 0) "BACK" else "FWD"); seekBy(skip) }
        else exec(a.command, if (a.command == Command.SEEK_BY) Bundle() else Bundle.EMPTY)
    }

    fun exec(cmd: Command, args: Bundle = Bundle.EMPTY) {
        // Cold start: the UI shows the last book before anything is loaded, so seeks/skips must load it (paused) first.
        if (exo.mediaItemCount == 0 && cmd in needsLoadedBook) {
            scope.launch {
                val r = dao.lastPlayed()?.let { resolve(it.id) } ?: return@launch
                exo.setMediaItems(r.mediaItems, r.startIndex, r.startPositionMs); exo.prepare()
                if (exo.mediaItemCount > 0) exec(cmd, args)
            }
            return
        }
        MappedAction(cmd, args.getLong(Command.EXTRA_MS)).skipMs(settings)?.let { skip ->
            saver.history(if (skip < 0) "BACK" else "FWD"); seekBy(skip); return
        }
        val idx = exo.currentMediaItemIndex
        when (cmd) {
            Command.PLAY_PAUSE -> if (exo.playWhenReady && exo.mediaItemCount > 0) exo.pause() else exec(Command.PLAY)
            Command.PLAY -> if (exo.mediaItemCount == 0) resumeLast() else {
                if (exo.playbackState == Player.STATE_IDLE) exo.prepare()
                if (exo.playbackState == Player.STATE_ENDED) exo.seekTo(0, 0)
                exo.play()
            }
            Command.PAUSE -> exo.pause()
            Command.PREV_CHAPTER -> {
                saver.history("PREV")
                exo.seekTo(if (exo.currentPosition > 3_000 || idx == 0) idx else idx - 1, 0)
            }
            Command.NEXT_CHAPTER -> if (idx < exo.mediaItemCount - 1) { saver.history("NEXT"); exo.seekTo(idx + 1, 0) }
            Command.PREV_FILE, Command.NEXT_FILE -> loaded?.spans?.let { spans ->
                val track = spans.getOrNull(idx)?.trackIndex ?: return
                val first = spans.indexOfFirst { it.trackIndex == track }
                val target = if (cmd == Command.PREV_FILE && (idx != first || exo.currentPosition > 3_000)) first
                else spans.indexOfFirst { it.trackIndex == track + if (cmd == Command.PREV_FILE) -1 else 1 }
                if (target >= 0) { saver.history(if (cmd == Command.PREV_FILE) "PREV" else "NEXT"); exo.seekTo(target, 0) }
            }
            Command.PREV_BOOK, Command.NEXT_BOOK -> scope.launch {
                neighbourBook(if (cmd == Command.PREV_BOOK) -1 else 1)?.let { playBook(it.id, play = true) }
            }
            Command.ADD_BOOKMARK -> addBookmark(args)
            Command.NEXT_BOOKMARK -> nextBookmark()
            Command.TOGGLE_SPEED -> {
                val cur = exo.playbackParameters.speed
                if (cur != 1f) editBook { it.copy(speed = 1f, savedToggleSpeed = cur) }
                else editBook { it.copy(speed = it.savedToggleSpeed ?: settings.defaultSpeed.takeIf { s -> s != 1f } ?: 1.5f) }
            }
            Command.SET_SPEED -> setSpeed(args.getFloat(Command.EXTRA_SPEED, 1f), args.getBoolean(Command.EXTRA_AS_DEFAULT))
            Command.CYCLE_SPEED -> setSpeed(nextCycleSpeed(exo.playbackParameters.speed))
            Command.SLEEP_ON -> {
                val explicit = args.containsKey(Command.EXTRA_MINUTES)
                val m = if (explicit) args.getInt(Command.EXTRA_MINUTES) else settings.sleepMinutes
                sleep.set(if (m < 0) SleepMode.EndOfChapter else SleepMode.Duration(m.coerceAtLeast(1)))
                if (explicit && m != settings.sleepMinutes) scope.launch { app.prefs.update { it.copy(sleepMinutes = m) } }
            }
            Command.SLEEP_OFF -> sleep.set(SleepMode.Off)
            Command.SLEEP_EXTEND -> if (sleep.state.value.mode == SleepMode.Off) exec(Command.SLEEP_ON) else sleep.reset()
            Command.SLEEP_CYCLE -> sleep.set(nextSleepCycle(sleep.state.value.mode))
            Command.EXIT -> { saver.save(sidecar = true); exo.pause(); sleep.set(SleepMode.Off); pauseAllPlayersAndStopSelf() }
            Command.PLAY_BOOK -> args.getString(Command.EXTRA_BOOK_ID)?.let {
                playBook(it, args.getInt(Command.EXTRA_CHAPTER, -1).takeIf { c -> c >= 0 }, args.getBoolean(Command.EXTRA_FROM_START))
            }
            Command.PLAY_SEARCH -> scope.launch { resolveQuery(args.getString(Command.EXTRA_QUERY).orEmpty())?.let { playBook(it.id) } }
            Command.SEEK_IN_BOOK -> loaded?.let {
                saver.history("SEEK")
                val (i, o) = locate(it.spans, args.getLong(Command.EXTRA_MS)); exo.seekTo(i, o)
            }
            Command.JUMP_TO -> loaded?.let {
                saver.history("SEEK")
                val (i, o) = locateInTrack(it.spans, args.getInt(Command.EXTRA_TRACK), args.getLong(Command.EXTRA_MS)); exo.seekTo(i, o)
            }
            Command.SET_CHAPTER -> if (exo.mediaItemCount > 0) {
                saver.history("SEEK"); exo.seekTo(args.getInt(Command.EXTRA_CHAPTER).coerceIn(0, exo.mediaItemCount - 1), 0)
            }
            Command.SET_SKIP_SILENCE -> editBook { it.copy(skipSilence = args.getBoolean(Command.EXTRA_ENABLED)) }
            Command.SET_BOOST -> editBook { it.copy(boostMb = args.getInt(Command.EXTRA_MB).coerceIn(0, 1500)) }
            Command.SET_EQ -> editBook {
                it.copy(
                    eqBandsDb = args.getFloatArray(Command.EXTRA_BANDS)?.joinToString(",") { v -> v.coerceIn(-15f, 15f).toString() },
                    balance = args.getFloat(Command.EXTRA_BALANCE, 0f).coerceIn(-1f, 1f),
                    mono = args.getBoolean(Command.EXTRA_MONO),
                    pitch = args.getFloat(Command.EXTRA_PITCH, 1f),
                )
            }
            Command.SEEK_BY, Command.REWIND_SMALL, Command.REWIND_BIG, Command.FWD_SMALL, Command.FWD_BIG -> Unit // handled above
        }
    }

    private fun addBookmark(args: Bundle) {
        val snap = saver.snapshot() ?: return
        val ch = loaded?.chapters?.getOrNull(exo.currentMediaItemIndex)
        val title = args.getString(Command.EXTRA_TITLE) ?: "${ch?.title ?: snap.book.title} · ${formatClock(exo.currentPosition)}"
        val b = Bookmark(bookId = snap.book.id, trackIndex = snap.trackIndex, positionMs = snap.positionInTrackMs,
            title = title, note = args.getString(Command.EXTRA_NOTE), createdAt = System.currentTimeMillis())
        scope.launch {
            app.db.userData().insertBookmark(b)
            // The phone UI shows its own confirmation; toast for the car, notification, headset and Tasker.
            if (!args.getBoolean(Command.EXTRA_QUIET)) Toast.makeText(this@PlaybackService, R.string.pb_bookmark_added, Toast.LENGTH_SHORT).show()
        }
    }

    private fun nextBookmark() {
        val snap = saver.snapshot() ?: return
        scope.launch {
            val next = app.db.userData().bookmarksNow(snap.book.id).firstOrNull {
                it.trackIndex > snap.trackIndex || (it.trackIndex == snap.trackIndex && it.positionMs > snap.positionInTrackMs + 1_000)
            } ?: return@launch
            exec(Command.JUMP_TO, Bundle().apply { putInt(Command.EXTRA_TRACK, next.trackIndex); putLong(Command.EXTRA_MS, next.positionMs) })
        }
    }

    // ---- media buttons (§5.5, §5.6) ------------------------------------------------------------------------------

    private val handler = Handler(Looper.getMainLooper())
    private var presses = 0
    private val pressRunnable = Runnable {
        val n = presses; presses = 0
        (parseAction(settings.headsetPresses.getOrNull(n - 1) ?: "PLAY_PAUSE") ?: MappedAction(Command.PLAY_PAUSE)).let { runMapped(it) }
    }

    private fun onMediaButton(intent: Intent): Boolean {
        val e = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_KEY_EVENT, KeyEvent::class.java) ?: return false
        val action: () -> Unit = when (e.keyCode) {
            KeyEvent.KEYCODE_HEADSETHOOK, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> { ->
                presses++
                handler.removeCallbacks(pressRunnable)
                handler.postDelayed(pressRunnable, 400)
            }
            KeyEvent.KEYCODE_MEDIA_NEXT -> player::seekToNext
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> player::seekToPrevious
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { -> exec(Command.FWD_SMALL) }
            KeyEvent.KEYCODE_MEDIA_REWIND -> { -> exec(Command.REWIND_SMALL) }
            else -> return false
        }
        if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 0) action()
        return true
    }

    /**
     * Every external surface's ⏮/⏭ ends up here (§5.6). Next/previous stay available even on the first/last chapter,
     * because they usually mean "skip 30 s".
     */
    private inner class BookPlayer(p: Player) : ForwardingPlayer(p) {
        private val wrapped = HashMap<Player.Listener, Player.Listener>()
        private val always = intArrayOf(
            COMMAND_SEEK_TO_NEXT, COMMAND_SEEK_TO_PREVIOUS, COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
            COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM, COMMAND_SEEK_FORWARD, COMMAND_SEEK_BACK,
        )

        private fun withSkips(c: Player.Commands) = c.buildUpon().addAll(*always).build()

        override fun seekToNext() = parseAction(settings.nextAction)?.let { runMapped(it, "NEXT") } ?: Unit
        override fun seekToNextMediaItem() = seekToNext()
        override fun seekToPrevious() = parseAction(settings.prevAction)?.let { runMapped(it, "PREV") } ?: Unit
        override fun seekToPreviousMediaItem() = seekToPrevious()
        override fun seekForward() = exec(Command.FWD_SMALL)
        override fun seekBack() = exec(Command.REWIND_SMALL)
        override fun seekTo(positionMs: Long) { saver.history("SEEK"); super.seekTo(positionMs) }
        override fun seekTo(mediaItemIndex: Int, positionMs: Long) { saver.history("SEEK"); super.seekTo(mediaItemIndex, positionMs) }
        override fun play() = if (mediaItemCount == 0) { resumeLast(); Unit } else super.play()
        override fun getAvailableCommands(): Player.Commands = withSkips(super.getAvailableCommands())
        override fun isCommandAvailable(command: Int) = command in always || super.isCommandAvailable(command)

        override fun addListener(listener: Player.Listener) {
            // Not `Player.Listener by listener`: Kotlin delegation skips Java default methods, which is all of them.
            val w = java.lang.reflect.Proxy.newProxyInstance(
                Player.Listener::class.java.classLoader, arrayOf(Player.Listener::class.java),
            ) { proxy, m, args ->
                when (m.name) {
                    "equals" -> proxy === args?.get(0)
                    "hashCode" -> System.identityHashCode(proxy)
                    "toString" -> "BookPlayer.Listener($listener)"
                    "onAvailableCommandsChanged" -> listener.onAvailableCommandsChanged(withSkips(args!![0] as Player.Commands))
                    else -> m.invoke(listener, *(args ?: emptyArray()))
                }
            } as Player.Listener
            wrapped[listener] = w
            super.addListener(w)
        }

        override fun removeListener(listener: Player.Listener) {
            wrapped.remove(listener)?.let { super.removeListener(it) }
        }
    }

    // ---- session callback (§6.2, §6.3) ---------------------------------------------------------------------------

    private val callback = object : MediaLibrarySession.Callback {
        override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
            val commands = MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS.buildUpon()
                .apply { Command.entries.forEach { add(it.session()) } }.build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session).setAvailableSessionCommands(commands).build()
        }

        override fun onCustomCommand(
            session: MediaSession, controller: MediaSession.ControllerInfo, customCommand: SessionCommand, args: Bundle,
        ): ListenableFuture<SessionResult> {
            val cmd = Command.fromAction(customCommand.customAction)
                ?: return Futures.immediateFuture(SessionResult(SessionError.ERROR_NOT_SUPPORTED))
            exec(cmd, args)
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }

        override fun onMediaButtonEvent(session: MediaSession, controllerInfo: MediaSession.ControllerInfo, intent: Intent) =
            onMediaButton(intent)

        override fun onGetLibraryRoot(
            session: MediaLibrarySession, browser: MediaSession.ControllerInfo, params: LibraryParams?,
        ): ListenableFuture<LibraryResult<MediaItem>> {
            val recent = params?.isRecent == true
            val out = LibraryParams.Builder().setRecent(recent)
                .setExtras(LibraryTree.styleExtras(LibraryTree.GRID, LibraryTree.LIST)).build()
            return Futures.immediateFuture(LibraryResult.ofItem(tree.root(recent), out))
        }

        override fun onGetItem(
            session: MediaLibrarySession, browser: MediaSession.ControllerInfo, mediaId: String,
        ): ListenableFuture<LibraryResult<MediaItem>> = scope.future {
            tree.item(mediaId)?.let { LibraryResult.ofItem(it, null) } ?: LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
        }

        override fun onGetChildren(
            session: MediaLibrarySession, browser: MediaSession.ControllerInfo, parentId: String, page: Int, pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = scope.future {
            LibraryResult.ofItemList(tree.children(parentId).page(page, pageSize), params)
        }

        override fun onSearch(
            session: MediaLibrarySession, browser: MediaSession.ControllerInfo, query: String, params: LibraryParams?,
        ): ListenableFuture<LibraryResult<Void>> = scope.future {
            session.notifySearchResultChanged(browser, query, tree.search(query).size, params)
            LibraryResult.ofVoid(params)
        }

        override fun onGetSearchResult(
            session: MediaLibrarySession, browser: MediaSession.ControllerInfo, query: String, page: Int, pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = scope.future {
            LibraryResult.ofItemList(tree.search(query).page(page, pageSize), params)
        }

        override fun onSetMediaItems(
            mediaSession: MediaSession, controller: MediaSession.ControllerInfo, mediaItems: MutableList<MediaItem>,
            startIndex: Int, startPositionMs: Long,
        ): ListenableFuture<MediaItemsWithStartPosition> = scope.future { resolveItems(mediaItems, startIndex, startPositionMs) }

        override fun onAddMediaItems(
            mediaSession: MediaSession, controller: MediaSession.ControllerInfo, mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> = scope.future {
            if (mediaItems.all { it.localConfiguration != null }) mediaItems
            else resolveItems(mediaItems, 0, C.TIME_UNSET).mediaItems.toMutableList()
        }

        override fun onPlaybackResumption(
            mediaSession: MediaSession, controller: MediaSession.ControllerInfo,
        ): ListenableFuture<MediaItemsWithStartPosition> = scope.future {
            dao.lastPlayed()?.let { resolve(it.id) } ?: throw UnsupportedOperationException("Nothing to resume")
        }

        override fun onPlaybackResumption(
            mediaSession: MediaSession, controller: MediaSession.ControllerInfo, isForPlayback: Boolean,
        ): ListenableFuture<MediaItemsWithStartPosition> = onPlaybackResumption(mediaSession, controller)
    }
}
