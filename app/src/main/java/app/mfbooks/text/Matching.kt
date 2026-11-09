package app.mfbooks.text

import android.content.Context
import app.mfbooks.MFBooksApp
import app.mfbooks.data.SpeechEnginePref
import app.mfbooks.data.TextState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class SpeechModelInfo(val lang: String, val name: String, val sizeBytes: Long, val installed: Boolean)

/** The public face of the matching job, used by the UI. */
object Matching {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Queue matching for a book. [now] ignores the "run matching" constraint. */
    fun enqueue(context: Context, bookId: String, now: Boolean) {
        val app = MFBooksApp.get(context)
        scope.launch {
            // Before the work is replaced: the old worker must not see ALIGNING and write PAUSED over the new run.
            app.db.text().setState(bookId, TextState.QUEUED, null, System.currentTimeMillis())
            // With the whole transcript saved this only takes seconds, so it doesn't wait for a charger.
            AlignWorker.enqueue(app, bookId, app.prefs.now().matchWhen, now || transcriptComplete(app, bookId))
        }
    }

    private suspend fun transcriptComplete(app: MFBooksApp, bookId: String): Boolean {
        val tracks = app.db.library().tracks(bookId)
        val audioMs = tracks.sumOf { it.durationMs }.toInt()
        val file = app.textStore.transcriptFile(bookId)
        if (!file.exists() || audioMs == 0) return false
        val covered = Coverage.merge(Transcript.load(file, audioMs, tracks.size).map { Coverage.range(it.fromMs, it.untilMs) })
        return Coverage.totalMs(covered) >= audioMs
    }

    /** Stop now, keep the transcript and the map. Resume through [enqueue]. */
    fun pause(context: Context, bookId: String) {
        val app = MFBooksApp.get(context)
        scope.launch {
            app.db.text().setState(bookId, TextState.PAUSED, null, System.currentTimeMillis())
            AlignWorker.cancel(app, bookId)
        }
    }

    /** Stop the job and forget that it was running. Deletes nothing. */
    fun cancel(context: Context, bookId: String) {
        val app = MFBooksApp.get(context)
        scope.launch {
            val row = app.db.text().text(bookId)
            val state = if (row != null && row.syncTier == SyncTier.ALIGNED && row.coverage > 0f) TextState.READY else TextState.PARSED
            app.db.text().setState(bookId, state, null, System.currentTimeMillis())
            AlignWorker.cancel(app, bookId)
        }
    }

    /** Spoken language ("ru", "en") from three audio samples, or null if the phone can't detect it. */
    suspend fun detectSpokenLanguage(context: Context, bookId: String): String? =
        detectSpoken(context, MFBooksApp.get(context).db.library().tracks(bookId))

    /** The phone has an on-device recogniser on Android 14+. Controls whether the Android choice is offered. */
    fun androidEngineAvailable(context: Context): Boolean = androidRecognizerAvailable(context)

    /** Download size in bytes needed before matching can run in [lang], 0 if nothing is needed or the system handles it. */
    suspend fun downloadNeeded(context: Context, lang: String): Long {
        val spec = ModelStore.spec(lang) ?: return 0
        if (ModelStore.installed(context, spec)) return 0
        return when (MFBooksApp.get(context).prefs.now().speechEngine) {
            SpeechEnginePref.VOSK -> spec.sizeBytes
            SpeechEnginePref.ANDROID -> 0
            SpeechEnginePref.AUTO -> spec.sizeBytes // the Android engine may still fail its probe and need Vosk: never download unannounced
        }
    }

    fun models(context: Context): List<SpeechModelInfo> =
        ModelStore.specs.map { SpeechModelInfo(it.lang, it.name, it.sizeBytes, ModelStore.installed(context, it)) }

    fun deleteModel(context: Context, lang: String): Unit = ModelStore.delete(context, lang)
}
