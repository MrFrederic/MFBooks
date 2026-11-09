package app.mfbooks.text

import android.content.Context
import app.mfbooks.data.SpeechEnginePref
import app.mfbooks.MFBooksApp
import app.mfbooks.data.TextState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class SpeechModelInfo(val lang: String, val name: String, val sizeBytes: Long, val installed: Boolean)

/** The public face of the matching job, used by the UI. */
object Matching {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** [app.mfbooks.data.BookText.error] while a book is waiting for the user to download Vosk. */
    const val NEEDS_VOSK = "needs-vosk"
    /** Language to fraction done, for each Vosk download in progress. */
    val downloads = MutableStateFlow<Map<String, Float>>(emptyMap())
    /** Goes up whenever a Vosk language appears or disappears. */
    val modelsVersion = MutableStateFlow(0)
    /** Languages whose last download failed. */
    val downloadFailed = MutableStateFlow<Set<String>>(emptySet())

    /** Fetch Vosk for [lang], the only way it reaches the phone. [matchBook] continues that book's matching afterwards. */
    fun downloadVosk(context: Context, lang: String, matchBook: String? = null) {
        if (ModelStore.spec(lang) == null || lang in downloads.value) return
        downloads.update { it + (lang to 0f) }
        downloadFailed.update { it - lang }
        VoskDownloadWorker.enqueue(context, lang, matchBook)
    }

    fun voskDownloadBytes(context: Context, lang: String): Long = ModelStore.spec(lang)?.let { ModelStore.downloadBytes(context, it) } ?: 0

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

    fun models(context: Context): List<SpeechModelInfo> =
        ModelStore.specs.map { val on = ModelStore.installed(context, it); SpeechModelInfo(it.lang, it.name, if (on) it.sizeBytes else ModelStore.downloadBytes(context, it), on) }

    fun deleteModel(context: Context, lang: String) {
        ModelStore.delete(context, lang)
        modelsVersion.update { it + 1 }
    }

    /** The saved choice, or Automatic if that engine isn't on this phone (Vosk deleted, or a setting synced from another device). */
    fun effectiveEngine(context: Context, pref: SpeechEnginePref): SpeechEnginePref = when {
        pref == SpeechEnginePref.VOSK && ModelStore.specs.none { ModelStore.installed(context, it) } -> SpeechEnginePref.AUTO
        pref == SpeechEnginePref.ANDROID && !androidEngineAvailable(context) -> SpeechEnginePref.AUTO
        else -> pref
    }
}
