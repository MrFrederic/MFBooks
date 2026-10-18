package app.mfbooks.convert

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.BatteryManager
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.mfbooks.MFBooksApp
import app.mfbooks.R
import app.mfbooks.data.Book
import app.mfbooks.data.Track
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * Unique work "convert-opus" (§4.6). Converts the eligible files of the books in [ConvertStore.queue].
 * Resumable by construction: eligibility is re-derived from Room each run, and converted files are `.opus`.
 * "Charging OR battery ≥ threshold" can't be one WorkManager constraint set (they AND), so the worker checks the
 * battery itself and, when it's too low, re-enqueues itself with requiresCharging.
 */
class ConvertWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    private val app = MFBooksApp.get(applicationContext)
    private val store = ConvertStore(applicationContext)
    private val nm = applicationContext.getSystemService(NotificationManager::class.java)

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        if (!app.prefs.now().saveInBookFolders) return@withContext Result.failure(workDataOf(OUT_MESSAGE to "\"Save progress in book folders\" is off"))
        val threshold = store.thresholdNow()
        if (!powerOk(threshold)) { waitForCharger(); return@withContext Result.success(workDataOf(OUT_MESSAGE to "Waiting for a charger")) }

        val dao = app.db.library()
        val plan = ArrayList<Pair<Book, Track>>()
        for (id in store.queueNow()) {
            val book = dao.book(id) ?: continue
            dao.tracks(id).filter(Eligibility::isEligible).forEach { plan += book to it }
        }
        val total = plan.size
        var done = 0; var failed = 0; var skipped = 0
        var saved = store.savedBytes.first()
        val converter = FileConverter(applicationContext)
        val repaired = HashSet<String>()
        val later = ArrayList<Pair<Book, Track>>()

        val durations = plan.map { it.second.durationMs.coerceAtLeast(1) }
        fun status(current: String?, p: Float) = Status(done, total, saved, current, p, durations)

        suspend fun report(current: String?, fileProgress: Float) {
            setProgress(workDataOf(P_DONE to done, P_TOTAL to total, P_CURRENT to current, P_FILE to fileProgress, P_FAILED to failed, P_SAVED to saved))
            // Without the FGS (started from the background on API 31+) this is a plain job that the system stops after
            // ~10 min; the work is then rescheduled and resumes from the finished chunks (see OpusTranscoder).
            try { setForeground(foreground(status(current, fileProgress))) } catch (e: CancellationException) { throw e } catch (e: Exception) {
                Log.w("Convert", "setForeground failed, running as a plain job", e)
            }
        }

        suspend fun one(book: Book, track: Track): Boolean {
            if (!powerOk(threshold)) { waitForCharger(); return false }
            if (book.id !in repaired) { runCatching { converter.repair(book) }; repaired += book.id }
            // re-read: a scan may have changed the row since the plan was made
            val t = dao.tracks(book.id).firstOrNull { it.index == track.index && it.fileName == track.fileName }
            if (t == null) { skipped++; return true }
            report(t.fileName, 0f)
            var last = 0L
            val res = try {
                converter.convert(book, t, { p ->
                    val now = System.currentTimeMillis()
                    if (now - last >= 1000) { last = now; runCatching { nm.notify(NOTIFICATION_ID, foreground(status(t.fileName, p)).notification) }; setProgressAsync(workDataOf(P_DONE to done, P_TOTAL to total, P_CURRENT to t.fileName, P_FILE to p, P_FAILED to failed, P_SAVED to saved)) }
                }, { isStopped })
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                Log.w("Convert", "failed: ${t.fileName}", e); FileResult.Failed(e.message ?: "error")
            }
            when (res) {
                is FileResult.Done -> { store.addSaved(res.savedBytes); saved += res.savedBytes }
                is FileResult.Failed -> { failed++; Log.w("Convert", "${book.title}/${t.fileName}: ${res.reason}") }
                is FileResult.Skipped -> skipped++
            }
            done++
            return true
        }

        try {
            report(null, 0f)
            for ((book, track) in plan) {
                if (isStopped) break
                if (isInPlayer(book.id)) { later += book to track; continue } // never convert what's playing; retry at the end
                if (!one(book, track)) return@withContext Result.success(workDataOf(OUT_MESSAGE to "Waiting for a charger"))
            }
            for ((book, track) in later) {
                if (isStopped) break
                if (isInPlayer(book.id)) { skipped++; done++; continue }
                if (!one(book, track)) return@withContext Result.success(workDataOf(OUT_MESSAGE to "Waiting for a charger"))
            }
        } catch (e: CancellationException) { throw e }
        if (!isStopped) store.setQueue(emptySet())
        val msg = buildString {
            val ok = done - failed - skipped
            append("Converted $ok file${if (ok == 1) "" else "s"}")
            if (failed > 0) append(", $failed failed")
            if (skipped > 0) append(", $skipped skipped")
        }
        Result.success(workDataOf(OUT_MESSAGE to msg, P_DONE to done, P_TOTAL to total, P_FAILED to failed))
    }

    /** True while [bookId] is the book playing (or buffering): its playlist holds the original file URIs. */
    private fun isInPlayer(bookId: String): Boolean {
        val s = app.player.state.value
        return s.book?.id == bookId && (s.isPlaying || s.isBuffering)
    }

    private fun powerOk(threshold: Int): Boolean {
        val bm = applicationContext.getSystemService(BatteryManager::class.java)
        return bm.isCharging || bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) >= threshold
    }

    /** Run again once plugged in (APPEND_OR_REPLACE: starts after this run ends). */
    private fun waitForCharger() = enqueue(applicationContext, charging = true, policy = ExistingWorkPolicy.APPEND_OR_REPLACE)

    override suspend fun getForegroundInfo() = foreground(Status(0, 0, store.savedBytes.first(), null, 0f, emptyList()))

    /** [durations] (ms per planned file) size the progress segments; [p] is the current file's progress. */
    private class Status(val done: Int, val total: Int, val saved: Long, val current: String?, val p: Float, val durations: List<Long>) {
        val title get() = if (total > 0) "Converting ${minOf(done + 1, total)} of $total" else "Converting to Opus"
        val text get() = listOfNotNull(current, "saved ${formatBytes(saved)}".takeIf { saved > 0 }).joinToString(" · ")
        /** Overall 0..1, weighted by duration so a 10 h file counts more than a 5 min one. */
        val fraction get() = if (durations.isEmpty()) 0f
            else ((durations.take(done).sum() + p * (durations.getOrNull(done) ?: 0)) / durations.sum().toFloat()).coerceIn(0f, 1f)
    }

    private fun foreground(s: Status): ForegroundInfo {
        if (nm.getNotificationChannel(CHANNEL) == null)
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Opus conversion", NotificationManager.IMPORTANCE_LOW))
        val pause = WorkManager.getInstance(applicationContext).createCancelPendingIntent(id) // keeps the queue: resume from the app
        val open = applicationContext.packageManager.getLaunchIntentForPackage(applicationContext.packageName)
            ?.let { PendingIntent.getActivity(applicationContext, 0, it, PendingIntent.FLAG_IMMUTABLE) }
        val n = if (Build.VERSION.SDK_INT >= 36) progressStyle(s, pause, open) else NotificationCompat.Builder(applicationContext, CHANNEL)
            .setSmallIcon(R.drawable.ic_mfbooks_mono)
            .setContentTitle(s.title).setContentText(s.text).setContentIntent(open)
            .setProgress(1000, (s.fraction * 1000).toInt(), s.total == 0)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setOngoing(true).setOnlyAlertOnce(true).setSilent(true)
            .addAction(0, "Pause", pause)
            .build()
        return when {
            Build.VERSION.SDK_INT >= 35 -> ForegroundInfo(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING)
            else -> ForegroundInfo(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        }
    }

    /**
     * Android 16 progress-centric notification: one segment per file (length ∝ duration, alternating tints so file
     * boundaries show), the bar filled to the overall position. Asks to be promoted to a Live Update (status chip).
     */
    @RequiresApi(36)
    private fun progressStyle(s: Status, pause: PendingIntent, open: PendingIntent?): Notification {
        val max = 10_000
        val style = Notification.ProgressStyle().setStyledByProgress(true).setProgress((s.fraction * max).toInt())
        val total = s.durations.sum().toFloat()
        if (s.durations.size in 2..MAX_SEGMENTS) {
            val tint = applicationContext.getColor(android.R.color.system_accent1_400)
            val alt = applicationContext.getColor(android.R.color.system_accent3_400)
            var used = 0
            s.durations.forEachIndexed { i, d ->
                val len = if (i == s.durations.lastIndex) max - used else (d / total * max).toInt().coerceAtLeast(1)
                used += len
                style.addProgressSegment(Notification.ProgressStyle.Segment(len).setColor(if (i % 2 == 0) tint else alt))
            }
        } else style.addProgressSegment(Notification.ProgressStyle.Segment(max))
        if (s.total == 0) style.setProgressIndeterminate(true)
        return Notification.Builder(applicationContext, CHANNEL)
            .setSmallIcon(R.drawable.ic_mfbooks_mono)
            .setContentTitle(s.title).setContentText(s.text).setContentIntent(open)
            .setStyle(style)
            .setShortCriticalText("${(s.fraction * 100).toInt()}%")
            .setCategory(Notification.CATEGORY_PROGRESS)
            .setOngoing(true).setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(null, "Pause", pause).build())
            .also { it.extras.putBoolean(Notification.EXTRA_REQUEST_PROMOTED_ONGOING, true) }
            .build()
    }

    companion object {
        const val NAME = "convert-opus"
        private const val CHANNEL = "convert"
        private const val NOTIFICATION_ID = 4601
        private const val MAX_SEGMENTS = 40
        const val P_DONE = "done"; const val P_TOTAL = "total"; const val P_CURRENT = "current"
        const val P_FILE = "file"; const val P_FAILED = "failed"; const val P_SAVED = "saved"
        const val OUT_MESSAGE = "message"

        fun enqueue(context: Context, charging: Boolean = false, policy: ExistingWorkPolicy = ExistingWorkPolicy.REPLACE) {
            val c = Constraints.Builder().setRequiresStorageNotLow(true).setRequiresCharging(charging).build()
            WorkManager.getInstance(context).enqueueUniqueWork(NAME, policy, OneTimeWorkRequestBuilder<ConvertWorker>().setConstraints(c).build())
        }

        /** Pause: stop now, keep the queue and the finished chunks of the current file. */
        fun cancel(context: Context) = WorkManager.getInstance(context).cancelUniqueWork(NAME)

        /** Cancel: stop and forget the queue and any partial work. */
        suspend fun discard(context: Context) {
            cancel(context)
            ConvertStore(context).setQueue(emptySet())
            withContext(Dispatchers.IO) { java.io.File(context.cacheDir, "opus").deleteRecursively() }
        }
    }
}
