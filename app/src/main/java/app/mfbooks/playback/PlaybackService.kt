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
import androidx.media3.cast.CastPlayer
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import app.mfbooks.server.Live
import app.mfbooks.server.LiveDevice
import app.mfbooks.server.Server
import app.mfbooks.server.Sync
import app.mfbooks.server.str
import app.mfbooks.server.num
import kotlinx.serialization.json.JsonObject
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

/** [Live.command] errors that mean the other device can't hear us at all. */
private val UNREACHABLE = setOf("offline", "The device didn't answer", "Not connected to the server")

/** One MediaLibrarySession for the phone UI, notification, Bluetooth, Wear and Android Auto (§3.3, §5, §6). */
class PlaybackService : MediaLibraryService() {
    private val app by lazy { MFBooksApp.get(this) }
    private val dao by lazy { app.db.library() }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val effects = EffectsProcessor()
    private val boost = Boost()
    private lateinit var exo: ExoPlayer
    private lateinit var pl: Player              // what plays: ExoPlayer or the Cast receiver (see Cast.kt)
    private var cast: CastPlayer? = null
    private lateinit var server: MediaServer
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
    private var remote: RemotePlayer? = null
    private val volumeReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context, intent: Intent) = reportLive()
    }
    private var loadedRemoteBook: String? = null // local id of the book [remote] mirrors

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
            .setMediaSourceFactory(DefaultMediaSourceFactory(Streaming.dataSource(this)))
            .setLoadControl(Streaming.loadControl())
            .build()
        boost.attach(exo.audioSessionId)
        // The session's player: ExoPlayer, or the Cast receiver while a Cast session runs (CastPlayer switches).
        server = MediaServer(this)
        cast = CastSupport.player(this, exo, server)
        pl = ClipPlayer(cast ?: exo)
        pl.addListener(listener)
        // ponytail: focus policy settings (pauseOnMessage, stopWhenOthersPlaying, resumeAfterCall) and
        // headsetConnectPlay use Media3's default focus handling for now (§5.2).
        player = BookPlayer(pl)
        saver = ProgressSaver(this, pl) { loaded }
        sleep = SleepTimer(this, pl, scope) { saver.history("SLEEP"); saver.save(sidecar = true) }
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
        Live.onCommand = ::onLiveCommand
        // Hardware buttons change the media volume; the other devices' Connect slider follows.
        // ponytail: VOLUME_CHANGED_ACTION isn't public API but every Android version sends it; Settings observers don't fire on 14.
        androidx.core.content.ContextCompat.registerReceiver(this, volumeReceiver, android.content.IntentFilter("android.media.VOLUME_CHANGED_ACTION"),
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
        Live.onPause = { if (remote == null && pl.playWhenReady) pl.pause() }
        scope.launch { Live.devices.collect(::onDevices) }
        scope.launch {
            // Now playing for the other devices every 5 s, and a sync every minute while playing (S1).
            var n = 0
            while (true) { delay(5_000); if (pl.isPlaying) { reportLive(); if (++n % 12 == 0) Sync.request(this@PlaybackService) } }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession = session

    override fun onDestroy() {
        Live.onCommand = null; Live.onPause = null; Live.setPlaying(false)
        unregisterReceiver(volumeReceiver)
        remote?.let { session.player = player; it.release(); remote = null; Live.setRemote(false) }
        saver.save(sidecar = true)
        saver.onIsPlayingChanged(false)
        car.type.removeObserver(carObserver)
        sleep.release()
        boost.release()
        session.release()
        pl.release() // CastPlayer releases ExoPlayer too
        server.stop()
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
            Live.setPlaying(isPlaying)
            reportLive()
            if (!isPlaying) Sync.request(this@PlaybackService)
            if (isPlaying) {
                val h = settings.forceStopHours
                if (h > 0 && forceStopJob?.isActive != true)
                    forceStopJob = scope.launch { delay(h * 3_600_000L); pl.pause() }
            } else saver.save(sidecar = !pl.playWhenReady)
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            adoptIfNeeded(mediaItem)
            reportLive()
            if (reason != Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED) saver.save()
        }

        override fun onPositionDiscontinuity(old: Player.PositionInfo, new: Player.PositionInfo, reason: Int) {
            if (reason != Player.DISCONTINUITY_REASON_SEEK) return
            saver.save()
            reportLive()
            if (!pl.playWhenReady) pausedAt = null // the user chose a position while paused: don't rewind from it
        }

        override fun onPlaybackStateChanged(state: Int) {
            if (state == Player.STATE_ENDED) { saver.save(sidecar = true, finished = true); onBookEnded() }
        }

        /** A file vanished under us (e.g. converted to .opus): reload the book from Room at the saved position, once. */
        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
            val id = loaded?.book?.id ?: return
            if (error.errorCode !in 2000..2999) return // PlaybackException IO errors
            if (pl.currentMediaItem?.localConfiguration?.uri?.scheme?.startsWith("http") == true)
                Toast.makeText(this@PlaybackService, R.string.pb_server_unreachable, Toast.LENGTH_LONG).show()
            if (System.currentTimeMillis() - lastReloadAt < 10_000) return
            lastReloadAt = System.currentTimeMillis()
            val wasPlaying = pl.playWhenReady
            scope.launch {
                val r = resolve(id) ?: return@launch
                pl.setMediaItems(r.mediaItems, r.startIndex, r.startPositionMs); pl.prepare()
                if (wasPlaying) pl.play()
            }
        }

        override fun onAudioSessionIdChanged(audioSessionId: Int) = boost.attach(audioSessionId)
        override fun onDeviceInfoChanged(deviceInfo: androidx.media3.common.DeviceInfo) {
            if (deviceInfo.playbackType == androidx.media3.common.DeviceInfo.PLAYBACK_TYPE_LOCAL) server.stop() // back from Cast
        }
        override fun onPlaybackParametersChanged(p: PlaybackParameters) { updateButtons(); reportLive() }
    }

    // ---- loading books -------------------------------------------------------------------------------------------

    private suspend fun load(bookId: String): Pair<LoadedBook, List<MediaItem>>? {
        val book = dao.book(bookId) ?: return null
        val tracks = dao.tracks(bookId)
        val have = tracks.map { it.index }.toSet()
        val chapters = chaptersOrTracks(bookId, dao.chapters(bookId), tracks).filter { it.trackIndex in have }
        if (chapters.isEmpty()) return null
        val bs = dao.settings(bookId)
        if (book.serverId != null && tracks.any { it.isRemote }) scope.launch { ServerLinks.prefetch(book.serverId) }
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
            if (pl.currentMediaItem?.mediaMetadata?.extras?.getString(ItemExtras.BOOK_ID) == bookId) adopt(lb, null)
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
        if (chapter == null && !fromStart && loaded?.book?.id == bookId && pl.mediaItemCount > 0) {
            if (play) { if (pl.playbackState == Player.STATE_IDLE) pl.prepare(); pl.play() }
            return
        }
        scope.launch {
            val r = resolve(bookId, chapter, fromStart) ?: return@launch
            pl.setMediaItems(r.mediaItems, r.startIndex, r.startPositionMs)
            pl.prepare()
            if (play) pl.play()
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
        if (pl.playbackParameters.speed != speed || pl.playbackParameters.pitch != pitch)
            pl.playbackParameters = PlaybackParameters(speed, pitch)
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
        if (loaded == null) pl.playbackParameters = PlaybackParameters(v, pl.playbackParameters.pitch)
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
        mediaButtons(this, settings, pl.playbackParameters.speed, if (::sleep.isInitialized) sleep.state.value.mode else SleepMode.Off)

    private fun updateButtons() {
        if (!::session.isInitialized) return
        val key = "${settings.prevAction}|${settings.nextAction}|${settings.rewindSmallSec}|${settings.fwdSmallSec}|" +
            "${pl.playbackParameters.speed}|${sleep.state.value.mode}"
        if (key == buttonsKey) return
        buttonsKey = key
        session.setMediaButtonPreferences(buttons())
    }

    // ---- commands ------------------------------------------------------------------------------------------------

    private fun seekBy(deltaMs: Long) {
        val lb = loaded
        if (lb == null || lb.spans.size != pl.mediaItemCount) {
            pl.seekTo((pl.currentPosition + deltaMs).coerceAtLeast(0)); return
        }
        val (i, off) = seekByMs(lb.spans, pl.currentMediaItemIndex, pl.currentPosition, deltaMs)
        pl.seekTo(i, off)
    }

    /** Runs a §5.6 mapping (next/prev, headset presses). [history] overrides the event name for seconds skips. */
    private fun runMapped(a: MappedAction, history: String? = null) {
        val skip = a.skipMs(settings)
        if (skip != null) { saver.history(history ?: if (skip < 0) "BACK" else "FWD"); seekBy(skip) }
        else exec(a.command, if (a.command == Command.SEEK_BY) Bundle() else Bundle.EMPTY)
    }

    fun exec(cmd: Command, args: Bundle = Bundle.EMPTY) {
        remote?.let { if (remoteExec(it, cmd, args)) return }
        // Cold start: the UI shows the last book before anything is loaded, so seeks/skips must load it (paused) first.
        if (pl.mediaItemCount == 0 && cmd in needsLoadedBook) {
            scope.launch {
                val r = dao.lastPlayed()?.let { resolve(it.id) } ?: return@launch
                pl.setMediaItems(r.mediaItems, r.startIndex, r.startPositionMs); pl.prepare()
                if (pl.mediaItemCount > 0) exec(cmd, args)
            }
            return
        }
        MappedAction(cmd, args.getLong(Command.EXTRA_MS)).skipMs(settings)?.let { skip ->
            saver.history(if (skip < 0) "BACK" else "FWD"); seekBy(skip); return
        }
        val idx = pl.currentMediaItemIndex
        when (cmd) {
            Command.PLAY_PAUSE -> if (pl.playWhenReady && pl.mediaItemCount > 0) pl.pause() else exec(Command.PLAY)
            Command.PLAY -> if (pl.mediaItemCount == 0) resumeLast() else {
                if (pl.playbackState == Player.STATE_IDLE) pl.prepare()
                if (pl.playbackState == Player.STATE_ENDED) pl.seekTo(0, 0)
                pl.play()
            }
            Command.PAUSE -> pl.pause()
            Command.PREV_CHAPTER -> {
                saver.history("PREV")
                pl.seekTo(if (pl.currentPosition > 3_000 || idx == 0) idx else idx - 1, 0)
            }
            Command.NEXT_CHAPTER -> if (idx < pl.mediaItemCount - 1) { saver.history("NEXT"); pl.seekTo(idx + 1, 0) }
            Command.PREV_FILE, Command.NEXT_FILE -> loaded?.spans?.let { spans ->
                val track = spans.getOrNull(idx)?.trackIndex ?: return
                val first = spans.indexOfFirst { it.trackIndex == track }
                val target = if (cmd == Command.PREV_FILE && (idx != first || pl.currentPosition > 3_000)) first
                else spans.indexOfFirst { it.trackIndex == track + if (cmd == Command.PREV_FILE) -1 else 1 }
                if (target >= 0) { saver.history(if (cmd == Command.PREV_FILE) "PREV" else "NEXT"); pl.seekTo(target, 0) }
            }
            Command.PREV_BOOK, Command.NEXT_BOOK -> scope.launch {
                neighbourBook(if (cmd == Command.PREV_BOOK) -1 else 1)?.let { playBook(it.id, play = true) }
            }
            Command.ADD_BOOKMARK -> addBookmark(args)
            Command.NEXT_BOOKMARK -> nextBookmark()
            Command.TOGGLE_SPEED -> {
                val cur = pl.playbackParameters.speed
                if (cur != 1f) editBook { it.copy(speed = 1f, savedToggleSpeed = cur) }
                else editBook { it.copy(speed = it.savedToggleSpeed ?: settings.defaultSpeed.takeIf { s -> s != 1f } ?: 1.5f) }
            }
            Command.SET_SPEED -> setSpeed(args.getFloat(Command.EXTRA_SPEED, 1f), args.getBoolean(Command.EXTRA_AS_DEFAULT))
            Command.CYCLE_SPEED -> setSpeed(nextCycleSpeed(pl.playbackParameters.speed))
            Command.SLEEP_ON -> {
                val explicit = args.containsKey(Command.EXTRA_MINUTES)
                val m = if (explicit) args.getInt(Command.EXTRA_MINUTES) else settings.sleepMinutes
                sleep.set(if (m < 0) SleepMode.EndOfChapter else SleepMode.Duration(m.coerceAtLeast(1)))
                if (explicit && m != settings.sleepMinutes) scope.launch { app.prefs.update { it.copy(sleepMinutes = m) } }
            }
            Command.SLEEP_OFF -> sleep.set(SleepMode.Off)
            Command.SLEEP_EXTEND -> if (sleep.state.value.mode == SleepMode.Off) exec(Command.SLEEP_ON) else sleep.reset()
            Command.SLEEP_CYCLE -> sleep.set(nextSleepCycle(sleep.state.value.mode))
            Command.EXIT -> { saver.save(sidecar = true); pl.pause(); sleep.set(SleepMode.Off); pauseAllPlayersAndStopSelf() }
            Command.PLAY_BOOK -> args.getString(Command.EXTRA_BOOK_ID)?.let {
                if (args.containsKey(Command.EXTRA_TRACK)) playAt(it, args.getInt(Command.EXTRA_TRACK), args.getLong(Command.EXTRA_MS))
                else playBook(it, args.getInt(Command.EXTRA_CHAPTER, -1).takeIf { c -> c >= 0 }, args.getBoolean(Command.EXTRA_FROM_START))
            }
            Command.PLAY_SEARCH -> scope.launch { resolveQuery(args.getString(Command.EXTRA_QUERY).orEmpty())?.let { playBook(it.id) } }
            Command.SEEK_IN_BOOK -> loaded?.let {
                saver.history("SEEK")
                val (i, o) = locate(it.spans, args.getLong(Command.EXTRA_MS)); pl.seekTo(i, o)
            }
            Command.JUMP_TO -> loaded?.let {
                saver.history("SEEK")
                val (i, o) = locateInTrack(it.spans, args.getInt(Command.EXTRA_TRACK), args.getLong(Command.EXTRA_MS)); pl.seekTo(i, o)
            }
            Command.SET_CHAPTER -> if (pl.mediaItemCount > 0) {
                saver.history("SEEK"); pl.seekTo(args.getInt(Command.EXTRA_CHAPTER).coerceIn(0, pl.mediaItemCount - 1), 0)
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
        val ch = loaded?.chapters?.getOrNull(pl.currentMediaItemIndex)
        val title = args.getString(Command.EXTRA_TITLE) ?: "${ch?.title ?: snap.book.title} · ${formatClock(pl.currentPosition)}"
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

    // ---- other devices (Spotify Connect–style, see server/Live.kt and RemotePlayer) ----------------------------------

    /** Plays [bookId] at a position in a track, without auto-rewind: a handoff continues exactly where the other device was. */
    private fun playAt(bookId: String, track: Int, positionMs: Long, speed: Float? = null, play: Boolean = true) = scope.launch {
        exitRemote()
        val r = resolve(bookId) ?: return@launch
        val spans = pending?.spans ?: return@launch
        pendingPausedAt = null
        val (i, o) = locateInTrack(spans, track, positionMs)
        pl.setMediaItems(r.mediaItems, i, o); pl.prepare()
        if (play) pl.play()
        speed?.let { setSpeed(it) }
    }

    /**
     * The device we control is gone (switched off, out of Wi-Fi): continue the book here from where it last was, playing
     * if the owner just pressed play, else paused and ready, so the phone never stays a remote for nothing.
     */
    private fun takeBack(r: RemotePlayer, play: Boolean) {
        val st = r.device.state
        Live.giveUp(r.device.id)
        android.util.Log.i("Live", "${r.device.name} is gone; continuing here at track ${st?.trackIndex} ${st?.positionMs} ms (play=$play)")
        exitRemote()
        if (st == null) return
        scope.launch { app.db.sync().bookByServerId(r.bookId)?.let { playAt(it.id, st.trackIndex, st.positionMs, st.speed, play) } }
    }

    private fun reportLive() {
        if (remote != null) return
        val snap = saver.snapshot() ?: return
        val sid = snap.book.serverId ?: return
        Live.state(sid, snap.book.title, snap.trackIndex, snap.positionInTrackMs, pl.playbackParameters.speed, pl.playWhenReady, mediaVolume(this))
    }

    /** Becomes the remote control of a device that started playing while this phone is idle; stays until it leaves or we play. */
    private fun onDevices(list: List<LiveDevice>) {
        val me = Live.me
        val active = list.firstOrNull { it.active && it.online && it.id != me && it.state?.bookId != null }
        val r = remote
        if (r != null) {
            if (pl.playWhenReady) exitRemote()
            else if (active == null || active.id != r.device.id) takeBack(r, play = false)
            else if (active.state?.bookId != r.bookId) { exitRemote(); onDevices(list) }
            else r.update(active)
            return
        }
        if (active != null && active.state!!.playing && !pl.playWhenReady && !Live.isSilent(active)) scope.launch {
            val book = app.db.sync().bookByServerId(active.state.bookId!!) ?: return@launch // ponytail: books this phone doesn't know aren't mirrored
            val (lb, items) = load(book.id) ?: return@launch
            if (remote != null || pl.playWhenReady) return@launch
            val rp = RemotePlayer(mainLooper, active.state.bookId, items, lb.spans, active, remoteOps(active.id),
                settings.rewindSmallSec * 1000L, settings.fwdSmallSec * 1000L)
            remote = rp
            loadedRemoteBook = book.id
            session.player = rp
            Live.setRemote(true)
        }
    }

    private fun exitRemote() {
        val r = remote ?: return
        remote = null
        session.player = player
        r.release()
        Live.setRemote(false)
    }

    private fun remoteOps(target: String) = object : RemoteOps {
        override fun command(action: String, args: Map<String, Any?>) { scope.launch {
            val err = Live.command(target, action, args) ?: return@launch
            val r = remote?.takeIf { it.device.id == target }
            if (r != null && err in UNREACHABLE) {
                Toast.makeText(this@PlaybackService, "${r.device.name} isn't reachable. " + if (action == "play") "Playing on this phone." else "Back on this phone.", Toast.LENGTH_SHORT).show()
                takeBack(r, play = action == "play")
            } else Toast.makeText(this@PlaybackService, err, Toast.LENGTH_SHORT).show()
        } }
        override fun next() { parseAction(settings.nextAction)?.let { remoteMapped(it) } }
        override fun previous() { parseAction(settings.prevAction)?.let { remoteMapped(it) } }
    }

    private fun remoteMapped(a: MappedAction) { remote?.let { remoteExec(it, a.command, Bundle().apply { putLong(Command.EXTRA_MS, a.ms) }) } }

    /** The phone's controls while another device plays. Returns false for commands that stay local (they leave remote mode). */
    private fun remoteExec(r: RemotePlayer, cmd: Command, args: Bundle): Boolean {
        val ops = remoteOps(r.device.id)
        MappedAction(cmd, args.getLong(Command.EXTRA_MS)).skipMs(settings)?.let { ops.command("skip", mapOf("ms" to it)); return true }
        val (idx, pos) = r.position()
        val s = r.device.state
        when (cmd) {
            Command.PLAY_PAUSE -> r.playWhenReady = !r.playWhenReady
            Command.PLAY -> r.play()
            Command.PAUSE -> r.pause()
            Command.PREV_CHAPTER -> r.seekChapter(if (pos > 3_000 || idx == 0) idx else idx - 1, 0)
            Command.NEXT_CHAPTER -> r.seekChapter(idx + 1, 0)
            Command.SET_CHAPTER -> r.seekChapter(args.getInt(Command.EXTRA_CHAPTER), 0)
            Command.SEEK_IN_BOOK -> {
                val ch = (0 until r.mediaItemCount).lastOrNull { i -> (r.getMediaItemAt(i).mediaMetadata.extras?.getLong(ItemExtras.START_IN_BOOK) ?: 0) <= args.getLong(Command.EXTRA_MS) } ?: 0
                r.seekChapter(ch, args.getLong(Command.EXTRA_MS) - (r.getMediaItemAt(ch).mediaMetadata.extras?.getLong(ItemExtras.START_IN_BOOK) ?: 0))
            }
            Command.JUMP_TO -> ops.command("seek", mapOf("trackIndex" to args.getInt(Command.EXTRA_TRACK), "positionMs" to args.getLong(Command.EXTRA_MS)))
            Command.SET_SPEED -> ops.command("speed", mapOf("speed" to args.getFloat(Command.EXTRA_SPEED, 1f)))
            Command.TOGGLE_SPEED -> ops.command("speed", mapOf("speed" to if ((s?.speed ?: 1f) != 1f) 1f else settings.defaultSpeed.takeIf { it != 1f } ?: 1.5f))
            Command.CYCLE_SPEED -> ops.command("speed", mapOf("speed" to nextCycleSpeed(s?.speed ?: 1f)))
            Command.SLEEP_ON -> ops.command("sleep", mapOf("minutes" to if (args.containsKey(Command.EXTRA_MINUTES)) args.getInt(Command.EXTRA_MINUTES) else settings.sleepMinutes))
            Command.SLEEP_OFF -> ops.command("sleep", mapOf("minutes" to null))
            // A chapter of the book the other device plays stays there, like every other control in remote mode.
            Command.PLAY_BOOK -> {
                val sameBook = args.getString(Command.EXTRA_BOOK_ID)?.let { loadedRemoteBook == it } == true
                if (sameBook && args.getInt(Command.EXTRA_CHAPTER, -1) >= 0) r.seekChapter(args.getInt(Command.EXTRA_CHAPTER), 0)
                else if (sameBook && !args.containsKey(Command.EXTRA_TRACK)) r.play()
                else { ops.command("pause"); exitRemote(); return false }
            }
            Command.PLAY_SEARCH, Command.PREV_BOOK, Command.NEXT_BOOK, Command.EXIT -> { ops.command("pause"); exitRemote(); return false }
            else -> Unit // EQ, boost, bookmarks… belong to this phone's own playback
        }
        return true
    }

    /** A command from another device (the web player): runs on this phone's own playback. */
    private suspend fun onLiveCommand(m: JsonObject): String? {
        val b = Bundle()
        fun local(c: Command) { exitRemote(); exec(c, b) }
        when (m.str("action")) {
            "play" -> {
                val sid = m.str("bookId") ?: return null.also { local(Command.PLAY) }
                val book = app.db.sync().bookByServerId(sid) ?: return "That book isn't on this phone"
                playAt(book.id, m.num("trackIndex")?.toInt() ?: 0, m.num("positionMs")?.toLong() ?: 0, m.num("speed")?.toFloat())
            }
            "pause" -> local(Command.PAUSE)
            "seek" -> { b.putInt(Command.EXTRA_TRACK, m.num("trackIndex")?.toInt() ?: 0); b.putLong(Command.EXTRA_MS, m.num("positionMs")?.toLong() ?: 0); local(Command.JUMP_TO) }
            "skip" -> { b.putLong(Command.EXTRA_MS, m.num("ms")?.toLong() ?: 0); local(Command.SEEK_BY) }
            "speed" -> { b.putFloat(Command.EXTRA_SPEED, m.num("speed")?.toFloat() ?: 1f); local(Command.SET_SPEED) }
            "sleep" -> m.num("minutes")?.toInt()?.let { b.putInt(Command.EXTRA_MINUTES, it); local(Command.SLEEP_ON) } ?: local(Command.SLEEP_OFF)
            "volume" -> { setMediaVolume(this, m.num("volume")?.toFloat() ?: return "No volume"); reportLive() }
            else -> return "Unknown command"
        }
        return null
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
