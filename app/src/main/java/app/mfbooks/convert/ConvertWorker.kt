package app.mfbooks.convert

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.BatteryManager
import android.os.Build
import android.util.Log
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

        suspend fun report(current: String?, fileProgress: Float) {
            setProgress(workDataOf(P_DONE to done, P_TOTAL to total, P_CURRENT to current, P_FILE to fileProgress, P_FAILED to failed, P_SAVED to saved))
            runCatching { setForeground(foreground(done, total, saved, current, fileProgress)) }
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
                    if (now - last > 1000) { last = now; runCatching { nm.notify(NOTIFICATION_ID, foreground(done, total, saved, t.fileName, p).notification) }; setProgressAsync(workDataOf(P_DONE to done, P_TOTAL to total, P_CURRENT to t.fileName, P_FILE to p, P_FAILED to failed, P_SAVED to saved)) }
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

    override suspend fun getForegroundInfo() = foreground(0, 0, store.savedBytes.first(), null, 0f)

    private fun foreground(done: Int, total: Int, saved: Long, current: String?, p: Float): ForegroundInfo {
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null)
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Opus conversion", NotificationManager.IMPORTANCE_LOW))
        val n = NotificationCompat.Builder(applicationContext, CHANNEL)
            .setSmallIcon(R.drawable.ic_mfbooks_mono)
            .setContentTitle(if (total > 0) "Converting ${minOf(done + 1, total)}/$total · saved ${formatBytes(saved)}" else "Converting to Opus")
            .setContentText(current)
            .setProgress(1000, if (total > 0) ((done + p) / total * 1000).toInt() else 0, total == 0)
            .setOngoing(true).setOnlyAlertOnce(true).setSilent(true)
            .addAction(0, "Cancel", WorkManager.getInstance(applicationContext).createCancelPendingIntent(id))
            .build()
        return if (Build.VERSION.SDK_INT >= 29) ForegroundInfo(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else ForegroundInfo(NOTIFICATION_ID, n)
    }

    companion object {
        const val NAME = "convert-opus"
        private const val CHANNEL = "convert"
        private const val NOTIFICATION_ID = 4601
        const val P_DONE = "done"; const val P_TOTAL = "total"; const val P_CURRENT = "current"
        const val P_FILE = "file"; const val P_FAILED = "failed"; const val P_SAVED = "saved"
        const val OUT_MESSAGE = "message"

        fun enqueue(context: Context, charging: Boolean = false, policy: ExistingWorkPolicy = ExistingWorkPolicy.REPLACE) {
            val c = Constraints.Builder().setRequiresStorageNotLow(true).setRequiresCharging(charging).build()
            WorkManager.getInstance(context).enqueueUniqueWork(NAME, policy, OneTimeWorkRequestBuilder<ConvertWorker>().setConstraints(c).build())
        }

        fun cancel(context: Context) = WorkManager.getInstance(context).cancelUniqueWork(NAME)
    }
}
