package app.mfbooks.text

import android.content.Context

class SpeechModelInfo(val lang: String, val name: String, val sizeBytes: Long, val installed: Boolean)

/** The public face of the matching job, used by the UI. */
object Matching {
    /** Queue matching for a book. [now] ignores the "run matching" constraint. */
    fun enqueue(context: Context, bookId: String, now: Boolean): Unit = TODO("matching agent")
    fun pause(context: Context, bookId: String): Unit = TODO("matching agent")
    fun cancel(context: Context, bookId: String): Unit = TODO("matching agent")

    /** Spoken language ("ru", "en") from three audio samples, or null if the phone can't detect it. */
    suspend fun detectSpokenLanguage(context: Context, bookId: String): String? = TODO("matching agent")

    /** The phone has an on-device recogniser on Android 14+. Controls whether the Android choice is offered. */
    fun androidEngineAvailable(context: Context): Boolean = TODO("matching agent")

    /** Download size in bytes needed before matching can run in [lang], 0 if nothing is needed or the system handles it. */
    suspend fun downloadNeeded(context: Context, lang: String): Long = TODO("matching agent")

    fun models(context: Context): List<SpeechModelInfo> = TODO("matching agent")
    fun deleteModel(context: Context, lang: String): Unit = TODO("matching agent")
}
