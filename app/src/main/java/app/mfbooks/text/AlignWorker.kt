package app.mfbooks.text

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.mfbooks.MFBooksApp
import app.mfbooks.R
import app.mfbooks.convert.ConvertStore
import app.mfbooks.data.MatchWhen
import app.mfbooks.data.SpeechEnginePref
import app.mfbooks.data.TextState
import app.mfbooks.data.Track
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Unique work per book (§1.6). Listens to the audio from the listener's position, appends words to the transcript and aligns
 * them to the text every half hour of audio. Each chunk is one atomic append, so any stop (pause, unplugged charger, the Android 15
 * foreground limit, a kill) loses at most one chunk: WorkManager reruns the work and it picks up from the file.
 */
class AlignWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    private val app = MFBooksApp.get(applicationContext)
    private val nm = applicationContext.getSystemService(NotificationManager::class.java)
    private val texts get() = app.db.text()
    private val bookId = inputData.getString(KEY_BOOK).orEmpty()
    private var bookTitle = "book"
    private var voskHeld = false

    private class RetryLater(message: String) : Exception(message)

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try { match() }
        catch (e: CancellationException) { withContext(NonCancellable) { noteStop() }; throw e }
        catch (e: RetryLater) { setState(TextState.QUEUED, e.message); Result.retry() }
        catch (e: SpeechFailure) { Log.w(TAG, "$bookId: ${e.kind}", e); stop(e.message ?: "Speech recognition failed") }
        catch (e: Exception) {
            Log.w(TAG, "$bookId failed", e)
            val why = e.message ?: e.javaClass.simpleName
            if (runAttemptCount >= MAX_ATTEMPTS) stop("Matching stopped: $why")
            else { setState(TextState.QUEUED, "Will try again: $why"); Result.retry() }
        }
    }

    private suspend fun match(): Result {
        val row = texts.text(bookId) ?: return Result.failure()
        val lib = app.db.library()
        val book = lib.book(bookId) ?: return Result.failure()
        bookTitle = book.title
        val tracks = lib.tracks(bookId)
        if (tracks.isEmpty()) return stop("This book has no audio files")
        val doc = app.textStore.readText(bookId) ?: return stop("The book's text is missing")
        val audioMs = tracks.sumOf { it.durationMs }.toInt()
        val settings = app.prefs.now()
        val checkBattery = !inputData.getBoolean(KEY_NOW, false) && settings.matchWhen == MatchWhen.CHARGING_OR_BATTERY

        conditions(checkBattery)?.let { return it }
        setState(TextState.ALIGNING, null)

        val file = app.textStore.transcriptFile(bookId)
        val members = ArrayList(Transcript.load(file, audioMs, tracks.size))
        var covered = Coverage.merge(members.map { Coverage.range(it.fromMs, it.untilMs) })
        val plan = ChunkPlan.plan(audioMs, startPosition(tracks, audioMs), CHUNK_MS, covered)
        var engineName = members.lastOrNull()?.engine
        var alignedAt = Coverage.totalMs(covered)

        val syncTracks = tracks.map { SyncTrack(it.fileName, it.durationMs) }
        val chapters = lib.chapters(bookId).map { ChapterRef(it.title, it.startInBookMs.toInt()) }
        suspend fun align(): SyncDoc? = try {
            val words = members.flatMap { it.words }.sortedBy { it.ms }
            val user = app.textStore.readSync(bookId)?.user.orEmpty()
            val sync = withContext(Dispatchers.Default) { Aligner.align(doc, row.textSha, words, covered, syncTracks, chapters, engineName ?: "unknown", user) }
            app.textStore.writeSync(bookId, sync, Coverage.totalMs(covered).toLong())
            sync
        } catch (e: CancellationException) { throw e } catch (e: Throwable) { Log.w(TAG, "$bookId: alignment failed", e); null }

        if (plan.isNotEmpty()) {
            report(Coverage.totalMs(covered), audioMs)
            if (!RecognizerSlots.permits.tryAcquire()) throw RetryLater("Waiting for another book to finish matching")
            var engine: SpeechEngine? = null
            try {
                var current = openEngine(settings.speechEngine, row.lang, doc, tracks, audioMs, engineName).also { engine = it }
                engineName = current.name
                PcmReader(applicationContext, tracks) { isStopped }.use { reader ->
                    for (c in plan) {
                        conditions(checkBattery)?.let { return it }
                        setState(TextState.ALIGNING, null) // also heals a state lost in a stop/replace race
                        val pcm = reader.read(c.fromMs.toLong(), c.untilMs.toLong())
                        val words = try { current.transcribe(pcm) } catch (f: SpeechFailure) {
                            if (settings.speechEngine != SpeechEnginePref.AUTO || current is VoskEngine) throw f
                            Log.w(TAG, "$bookId: Android engine failed mid-job (${f.kind}), switching to Vosk")
                            current.close()
                            current = openVosk(row.lang, confirmed = false).also { engine = it }
                            engineName = current.name
                            current.transcribe(pcm)
                        }
                        val member = Transcript.Member(c.fromMs, c.untilMs, audioMs, current.name, words.map { TimedWord(it.word, c.fromMs + it.startMs) })
                        Transcript.append(file, member)
                        members += member
                        covered = Coverage.merge(covered + listOf(Coverage.range(c.fromMs, c.untilMs)))
                        val total = Coverage.totalMs(covered)
                        mirror()
                        report(total, audioMs)
                        if (total - alignedAt >= ALIGN_EVERY_MS) {
                            val sync = align()
                            if (sync != null && Outcome.stopEarly(alignedAt, total, Outcome.matchedRatio(sync.ranges))) {
                                setState(TextState.NO_MATCH, "${(Outcome.matchedRatio(sync.ranges) * 100).toInt()}% of the first hour matched")
                                return Result.success()
                            }
                            alignedAt = total
                        }
                    }
                }
            } finally {
                runCatching { engine?.close() }
                if (voskHeld) { voskHeld = false; RecognizerSlots.vosk.unlock() }
                RecognizerSlots.permits.release()
            }
        }

        val sync = align() ?: return stop("Couldn't align the text with the audio")
        val state = if (sync.coverage() >= Outcome.READY_COVERAGE) TextState.READY else TextState.NO_MATCH
        setState(state, null) // the card shows coverage
        return Result.success(workDataOf(OUT_MESSAGE to state))
    }

    private suspend fun openEngine(pref: SpeechEnginePref, lang: String, doc: BookTextDoc, tracks: List<Track>, audioMs: Int, lastEngine: String?): SpeechEngine {
        val locale = localeOf(lang)
        val android = if (Build.VERSION.SDK_INT >= 34 && locale != null && androidRecognizerAvailable(applicationContext)) AndroidEngine(applicationContext, locale) else null
        return when (pref) {
            SpeechEnginePref.VOSK -> openVosk(lang, confirmed = true)
            SpeechEnginePref.ANDROID -> {
                if (Build.VERSION.SDK_INT < 34 || android == null) throw SpeechFailure(SpeechFailure.Kind.UNAVAILABLE, "This phone has no on-device recogniser for this book")
                android.ensureLanguage()
                android
            }
            SpeechEnginePref.AUTO -> {
                // A resumed job keeps the engine it was using; only a fresh job probes.
                val usable = Build.VERSION.SDK_INT >= 34 && android != null && lastEngine?.startsWith("vosk") != true && try {
                    android.ensureLanguage()
                    lastEngine != null || probe(android, doc, tracks, audioMs)
                } catch (e: SpeechFailure) { Log.i(TAG, "$bookId: Android engine unusable (${e.kind}): ${e.message}"); false }
                if (usable) android!! else openVosk(lang, confirmed = false)
            }
        }
    }

    /** Vosk for [lang]. [confirmed]: the user agreed to the download (Vosk chosen, or no Android engine at all). */
    private suspend fun openVosk(lang: String, confirmed: Boolean): SpeechEngine {
        val spec = ModelStore.spec(lang) ?: throw SpeechFailure(SpeechFailure.Kind.LANGUAGE, "No speech model for this language")
        if (!RecognizerSlots.vosk.tryLock()) throw RetryLater("Waiting for another book to finish matching")
        voskHeld = true
        if (!confirmed && !ModelStore.installed(applicationContext, spec) &&
            applicationContext.getSystemService(ConnectivityManager::class.java).isActiveNetworkMetered)
            throw RetryLater("Waiting for Wi-Fi to download the speech model")
        val dir = try { ModelStore.ensure(applicationContext, spec) } catch (e: IOException) {
            throw RetryLater("The speech model couldn't be downloaded: ${e.message}")
        }
        return VoskEngine(spec, dir)
    }

    /** 60 seconds from about 10% in. Failing or timing out only means "not this engine". */
    private suspend fun probe(engine: SpeechEngine, doc: BookTextDoc, tracks: List<Track>, audioMs: Int): Boolean {
        val from = audioMs / 10L
        val until = minOf(from + PROBE_MS, audioMs.toLong())
        return try {
            val pcm = PcmReader(applicationContext, tracks) { isStopped }.use { it.read(from, until) }
            val t0 = SystemClock.elapsedRealtime()
            val words = withTimeoutOrNull(PROBE_TIMEOUT_MS) { engine.transcribe(pcm) } ?: return false
            ProbeRules.accepts(words, bookWords(doc), until - from, SystemClock.elapsedRealtime() - t0).also { Log.i(TAG, "$bookId: probe ${words.size} words -> $it") }
        } catch (e: CancellationException) { throw e } catch (e: Exception) { Log.i(TAG, "$bookId: probe failed: ${e.message}"); false }
    }

    private suspend fun startPosition(tracks: List<Track>, audioMs: Int): Int {
        val p = app.db.library().progress(bookId) ?: return 0
        return (tracks.takeWhile { it.index < p.trackIndex }.sumOf { it.durationMs } + p.positionInTrackMs).toInt().coerceIn(0, audioMs - 1)
    }

    /** A Result when the job shouldn't go on now: battery low in charging-or-battery mode. Too hot: throws, to retry later. */
    private suspend fun conditions(checkBattery: Boolean): Result? {
        if (applicationContext.getSystemService(PowerManager::class.java).currentThermalStatus >= PowerManager.THERMAL_STATUS_SEVERE)
            throw RetryLater("Waiting for the phone to cool down")
        if (checkBattery) {
            val bm = applicationContext.getSystemService(BatteryManager::class.java)
            if (!bm.isCharging && bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) < ConvertStore(applicationContext).thresholdNow()) {
                setState(TextState.QUEUED, "Waiting for a charger")
                enqueue(applicationContext, bookId, requireCharging = true, now = false, policy = ExistingWorkPolicy.APPEND_OR_REPLACE)
                return Result.success()
            }
        }
        return null
    }

    private suspend fun setState(state: String, error: String?) = texts.setState(bookId, state, error, System.currentTimeMillis())

    private suspend fun stop(message: String): Result {
        setState(TextState.PAUSED, message)
        return Result.failure(workDataOf(OUT_MESSAGE to message))
    }

    /** The worker is being stopped. Matching.pause and cancel set their own state first; the notification's Pause and system stops land here. */
    private suspend fun noteStop() {
        if (texts.text(bookId)?.state != TextState.ALIGNING) return
        when (stopReason) {
            WorkInfo.STOP_REASON_CANCELLED_BY_APP -> setState(TextState.PAUSED, null)
            WorkInfo.STOP_REASON_FOREGROUND_SERVICE_TIMEOUT -> setState(TextState.QUEUED, "Android's background time limit was reached. Matching continues later.")
            else -> setState(TextState.QUEUED, null)
        }
    }

    private suspend fun mirror() {
        try { app.textStore.mirrorTranscript(bookId) } catch (e: CancellationException) { throw e } catch (e: Throwable) { Log.w(TAG, "$bookId: mirror failed", e) }
    }

    /**
     * Foreground with the percent. The system can refuse a foreground start (from the background on API 31+, or the Android 15 limit
     * used up): the job then runs on as a plain job until the system stops it, and each chunk tries again. Same as ConvertWorker.
     */
    private suspend fun report(coveredMs: Int, audioMs: Int) {
        val pct = if (audioMs > 0) (coveredMs * 100L / audioMs).toInt().coerceIn(0, 100) else 0
        setProgress(workDataOf(P_PERCENT to pct))
        try { setForeground(foreground(pct)) } catch (e: CancellationException) { throw e } catch (e: Exception) {
            Log.w(TAG, "setForeground failed, running as a plain job", e)
        }
    }

    override suspend fun getForegroundInfo() = foreground(0)

    private fun foreground(pct: Int): ForegroundInfo {
        if (nm.getNotificationChannel(CHANNEL) == null)
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Text matching", NotificationManager.IMPORTANCE_LOW))
        val pause = WorkManager.getInstance(applicationContext).createCancelPendingIntent(id)
        val open = applicationContext.packageManager.getLaunchIntentForPackage(applicationContext.packageName)
            ?.let { PendingIntent.getActivity(applicationContext, 0, it, PendingIntent.FLAG_IMMUTABLE) }
        val n = NotificationCompat.Builder(applicationContext, CHANNEL)
            .setSmallIcon(R.drawable.ic_mfbooks_mono)
            .setContentTitle("Matching $bookTitle · $pct%").setContentIntent(open)
            .setProgress(100, pct, false)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setOngoing(true).setOnlyAlertOnce(true).setSilent(true)
            .addAction(0, "Pause", pause)
            .build()
        val notificationId = NOTIFICATION_ID + (bookId.hashCode() and 0xFFF)
        return if (Build.VERSION.SDK_INT >= 35) ForegroundInfo(notificationId, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING)
        else ForegroundInfo(notificationId, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    companion object {
        private const val TAG = "AlignWorker"
        private const val CHANNEL = "match"
        private const val NOTIFICATION_ID = 4700
        private const val KEY_BOOK = "bookId"
        private const val KEY_NOW = "now"
        const val P_PERCENT = "percent"
        const val OUT_MESSAGE = "message"
        private const val MAX_ATTEMPTS = 5
        private const val BACKOFF_MINUTES = 10L
        const val CHUNK_MS = 5 * 60_000
        const val ALIGN_EVERY_MS = 30 * 60_000
        private const val PROBE_MS = 60_000L
        private const val PROBE_TIMEOUT_MS = 90_000L

        private fun name(bookId: String) = "align-$bookId"

        fun enqueue(context: Context, bookId: String, matchWhen: MatchWhen, now: Boolean) =
            enqueue(context, bookId, requireCharging = !now && matchWhen == MatchWhen.CHARGING, now = now, policy = ExistingWorkPolicy.REPLACE)

        internal fun enqueue(context: Context, bookId: String, requireCharging: Boolean, now: Boolean, policy: ExistingWorkPolicy) {
            val c = Constraints.Builder().setRequiresStorageNotLow(true).setRequiresCharging(requireCharging).build()
            WorkManager.getInstance(context).enqueueUniqueWork(name(bookId), policy, OneTimeWorkRequestBuilder<AlignWorker>()
                .setInputData(workDataOf(KEY_BOOK to bookId, KEY_NOW to now))
                .setConstraints(c).setBackoffCriteria(BackoffPolicy.LINEAR, BACKOFF_MINUTES, TimeUnit.MINUTES).build())
        }

        fun cancel(context: Context, bookId: String) { WorkManager.getInstance(context).cancelUniqueWork(name(bookId)) }
    }
}
