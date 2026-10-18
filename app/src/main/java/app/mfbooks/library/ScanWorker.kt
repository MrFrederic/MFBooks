package app.mfbooks.library

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import app.mfbooks.MFBooksApp

/** Unique work "library-scan"; progress is exposed through [LibraryScanner.progress] ("Analyzing files n/m"). */
class ScanWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        MFBooksApp.get(applicationContext).scanner.scan(inputData.getBoolean("full", false))
        return Result.success()
    }
}
