package app.mfbooks.text

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.util.Log
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
import app.mfbooks.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.update
import java.io.IOException

/** Fetches Vosk for one language. A foreground job, so it survives the screen locking. */
class VoskDownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    private val nm = applicationContext.getSystemService(NotificationManager::class.java)

    override suspend fun doWork(): Result {
        val lang = inputData.getString(KEY_LANG) ?: return Result.failure()
        val spec = ModelStore.spec(lang) ?: return Result.failure()
        try {
            setForeground(foreground(0))
            var shown = 0
            ModelStore.ensure(applicationContext, spec) { f ->
                Matching.downloads.update { it + (lang to f) }
                val pct = (f * 100).toInt()
                if (pct != shown) { shown = pct; nm.notify(NOTIFICATION_ID, foreground(pct).notification) }
            }
            Matching.modelsVersion.update { it + 1 }
            inputData.getString(KEY_BOOK)?.let { Matching.enqueue(applicationContext, it, true) }
            return Result.success()
        } catch (e: CancellationException) { throw e } catch (e: IOException) {
            Log.w("VoskDownload", "failed", e)
            Matching.downloadFailed.update { it + lang }
            return Result.failure()
        } catch (e: IllegalStateException) { // the system refused the foreground service
            Log.w("VoskDownload", "couldn't start", e)
            Matching.downloadFailed.update { it + lang }
            return Result.failure()
        } finally {
            Matching.downloads.update { it - lang }
        }
    }

    private fun foreground(pct: Int): ForegroundInfo {
        if (nm.getNotificationChannel(CHANNEL) == null)
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Text matching", NotificationManager.IMPORTANCE_LOW))
        val n = NotificationCompat.Builder(applicationContext, CHANNEL)
            .setSmallIcon(R.drawable.ic_mfbooks_mono)
            .setContentTitle("Downloading speech engine · $pct%")
            .setProgress(100, pct, false)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setOngoing(true).setOnlyAlertOnce(true).setSilent(true)
            .build()
        return ForegroundInfo(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    companion object {
        private const val KEY_LANG = "lang"
        private const val KEY_BOOK = "book"
        private const val CHANNEL = "match"
        private const val NOTIFICATION_ID = 4699

        fun enqueue(context: Context, lang: String, matchBook: String?) {
            WorkManager.getInstance(context).enqueueUniqueWork("vosk-$lang", ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<VoskDownloadWorker>()
                    .setConstraints(Constraints(requiredNetworkType = NetworkType.CONNECTED))
                    .setInputData(workDataOf(KEY_LANG to lang, KEY_BOOK to matchBook))
                    .build())
        }
    }
}
