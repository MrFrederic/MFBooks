package app.mfbooks.text

import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.speech.ModelDownloadListener
import android.speech.RecognitionListener
import android.speech.RecognitionPart
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.annotation.RequiresApi
import app.mfbooks.data.Track
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import org.vosk.Recognizer
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.concurrent.thread

private const val TAG = "Speech"
private const val SAMPLE_RATE = 16_000
private val ALLOWED = arrayListOf("ru-RU", "en-US")

/** What went wrong, so the worker can choose between falling back and telling the user. */
class SpeechFailure(val kind: Kind, message: String, cause: Throwable? = null) : Exception(message, cause) {
    enum class Kind { UNAVAILABLE, PERMISSION, LANGUAGE, NO_PARTS, BAD_TIMESTAMPS, TIMEOUT, ENGINE, DROPPED_SPEECH }
}

/** Turns one chunk of 16 kHz mono 16-bit PCM into words. One chunk at a time per engine. */
interface SpeechEngine : Closeable {
    val name: String
    /** Throws [SpeechFailure], never crashes. Word times are relative to the chunk. */
    suspend fun transcribe(pcm: ShortArray): List<SpokenWord>
}

/** At most two recognisers in the process, and one Vosk model (about 300 MB). */
object RecognizerSlots {
    val permits = Semaphore(2)
    val vosk = Mutex()
}

fun localeOf(lang: String) = when (lang) { "ru" -> "ru-RU"; "en" -> "en-US"; else -> null }

fun androidRecognizerAvailable(context: Context) =
    Build.VERSION.SDK_INT >= 34 && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)

/** Creates a recogniser on the main thread, hands it to [start] there, waits for [start]'s deferred, and always destroys it there. */
@RequiresApi(34)
private suspend fun <T> withRecognizer(context: Context, timeoutMs: Long, what: String, start: (SpeechRecognizer, CompletableDeferred<T>) -> Unit): T {
    val result = CompletableDeferred<T>()
    var rec: SpeechRecognizer? = null
    try {
        withContext(Dispatchers.Main) {
            rec = SpeechRecognizer.createOnDeviceSpeechRecognizer(context).also { start(it, result) }
        }
        return withTimeoutOrNull(timeoutMs) { result.await() } ?: throw SpeechFailure(SpeechFailure.Kind.TIMEOUT, "$what timed out")
    } catch (e: CancellationException) { throw e } catch (e: SpeechFailure) { throw e } catch (e: Exception) {
        throw SpeechFailure(SpeechFailure.Kind.ENGINE, "$what failed: ${e.message}", e)
    } finally {
        withContext(NonCancellable + Dispatchers.Main) { runCatching { rec?.destroy() } }
    }
}

private fun errorFailure(code: Int) = when (code) {
    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> SpeechFailure(SpeechFailure.Kind.PERMISSION, "The phone's speech recogniser needs a permission this app doesn't have")
    SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> SpeechFailure(SpeechFailure.Kind.LANGUAGE, "The phone's speech recogniser can't use this language")
    else -> SpeechFailure(SpeechFailure.Kind.ENGINE, "The phone's speech recogniser failed (error $code)")
}

/**
 * One recognition session over [pcm], fed through a pipe. [onLanguage] returns true to end the session early.
 * ponytail: the PCM is held in memory for the session (10 MB for 5 minutes), which keeps the writer trivial.
 */
@RequiresApi(34)
private suspend fun recognise(
    context: Context, pcm: ShortArray, locale: String, timeoutMs: Long,
    extras: Intent.() -> Unit = {}, onLanguage: ((Bundle) -> Boolean)? = null,
): List<SpokenWord> {
    val pipe = ParcelFileDescriptor.createPipe()
    var writerStarted = false
    try {
        return withRecognizer(context, timeoutMs, "Speech recognition") { rec, result ->
            val words = ArrayList<SpokenWord>()
            var last = -1L
            fun take(b: Bundle) {
                val parts = b.getParcelableArrayList(SpeechRecognizer.RECOGNITION_PARTS, RecognitionPart::class.java)
                if (parts.isNullOrEmpty()) {
                    if (!b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().isNullOrBlank())
                        result.completeExceptionally(SpeechFailure(SpeechFailure.Kind.NO_PARTS, "The phone's speech recogniser gave no word timings"))
                    return
                }
                for (p in parts) {
                    if (p.timestampMillis < last) { // two short words can share a timestamp
                        result.completeExceptionally(SpeechFailure(SpeechFailure.Kind.BAD_TIMESTAMPS, "The phone's speech recogniser gave unusable word timings"))
                        return
                    }
                    last = p.timestampMillis
                    p.rawText.split(Regex("\\s+")).filter { it.isNotEmpty() }.forEach { words += SpokenWord(it, p.timestampMillis.toInt()) }
                }
            }
            rec.setRecognitionListener(object : RecognitionListener {
                override fun onSegmentResults(segmentResults: Bundle) = take(segmentResults)
                override fun onEndOfSegmentedSession() { result.complete(words) }
                override fun onResults(results: Bundle) { take(results); result.complete(words) }
                override fun onLanguageDetection(results: Bundle) { if (onLanguage?.invoke(results) == true) result.complete(words) }
                override fun onError(error: Int) {
                    if (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) result.complete(words) // silence or music
                    else result.completeExceptionally(errorFailure(error))
                }
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
            rec.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, locale)
                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, pipe[0])
                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, SAMPLE_RATE)
                putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, RecognizerIntent.EXTRA_AUDIO_SOURCE)
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                putExtra(RecognizerIntent.EXTRA_REQUEST_WORD_TIMING, true)
                extras()
            })
            writerStarted = true
            thread(name = "speech-pcm", isDaemon = true) { writePcm(pipe[1], pcm) }
        }
    } finally {
        runCatching { pipe[0].close() } // our copy; also breaks a writer blocked on a dead reader
        if (!writerStarted) runCatching { pipe[1].close() }
    }
}

/** On a Pixel 9 Pro an unpaced pipe made the recogniser lose about 90% of the speech. */
private const val FEED_SPEED = 5

private fun writePcm(out: ParcelFileDescriptor, pcm: ShortArray) {
    try {
        ParcelFileDescriptor.AutoCloseOutputStream(out).use { stream ->
            val buf = ByteBuffer.allocate(SAMPLE_RATE * 2).order(ByteOrder.LITTLE_ENDIAN)
            val started = SystemClock.elapsedRealtime()
            var i = 0
            while (i < pcm.size) {
                buf.clear()
                while (i < pcm.size && buf.remaining() >= 2) buf.putShort(pcm[i++])
                stream.write(buf.array(), 0, buf.position())
                val dueMs = i * 1000L / SAMPLE_RATE / FEED_SPEED
                val waitMs = dueMs - (SystemClock.elapsedRealtime() - started)
                if (waitMs > 0) Thread.sleep(waitMs)
            }
        }
    } catch (e: IOException) { Log.d(TAG, "PCM writer stopped: ${e.message}") } // the session ended first
}

/** The on-device recogniser. Trusted only after the worker's probe: see docs/MFBooks-ReadAlong.md §1.6. */
@RequiresApi(34)
class AndroidEngine(private val context: Context, private val locale: String) : SpeechEngine {
    override val name = "android-$locale"

    /** Makes sure the language pack is on the phone, asking the system to fetch it if not. */
    suspend fun ensureLanguage() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(RecognizerIntent.EXTRA_LANGUAGE, locale)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        val support = withRecognizer<RecognitionSupport>(context, CHECK_TIMEOUT_MS, "Language check") { rec, done ->
            rec.checkRecognitionSupport(intent, context.mainExecutor, object : RecognitionSupportCallback {
                override fun onSupportResult(recognitionSupport: RecognitionSupport) { done.complete(recognitionSupport) }
                override fun onError(error: Int) { done.completeExceptionally(SpeechFailure(SpeechFailure.Kind.LANGUAGE, "Couldn't check the phone's speech languages (error $error)")) }
            })
        }
        fun List<String>.has() = any { it.equals(locale, ignoreCase = true) }
        when {
            support.installedOnDeviceLanguages.has() -> return
            support.pendingOnDeviceLanguages.has() -> throw SpeechFailure(SpeechFailure.Kind.LANGUAGE, "The language pack for $locale is still downloading")
            !support.supportedOnDeviceLanguages.has() -> throw SpeechFailure(SpeechFailure.Kind.LANGUAGE, "This phone has no on-device recogniser for $locale")
        }
        withRecognizer<Unit>(context, DOWNLOAD_TIMEOUT_MS, "Language pack download") { rec, done ->
            rec.triggerModelDownload(intent, context.mainExecutor, object : ModelDownloadListener {
                override fun onProgress(completedPercent: Int) {}
                override fun onSuccess() { done.complete(Unit) }
                override fun onScheduled() { done.completeExceptionally(SpeechFailure(SpeechFailure.Kind.LANGUAGE, "The language pack for $locale will download later")) }
                override fun onError(error: Int) { done.completeExceptionally(SpeechFailure(SpeechFailure.Kind.LANGUAGE, "The language pack for $locale couldn't be downloaded (error $error)")) }
            })
        }
    }

    override suspend fun transcribe(pcm: ShortArray): List<SpokenWord> {
        val words = recognise(context, pcm, locale, (pcm.size / (SAMPLE_RATE / 1000) / ProbeRules.MIN_SPEED).toLong() + SESSION_MARGIN_MS)
        val minutes = pcm.size / SAMPLE_RATE / 60.0
        if (minutes >= 2 && words.size < minutes * ProbeRules.MIN_WORDS_PER_MIN / 2) // half the probe's bar: a chunk can hold a music break
            throw SpeechFailure(SpeechFailure.Kind.DROPPED_SPEECH, "The phone's speech recogniser dropped too much speech")
        return words
    }

    override fun close() {} // a recogniser lives for one session only

    private companion object {
        const val CHECK_TIMEOUT_MS = 10_000L
        const val DOWNLOAD_TIMEOUT_MS = 5 * 60_000L
        const val SESSION_MARGIN_MS = 30_000L
    }
}

/** Vosk small. Holds the model until [close]; take [RecognizerSlots.vosk] first. */
class VoskEngine(spec: ModelStore.Spec, dir: File) : SpeechEngine {
    override val name = spec.engine
    private val model: Model = try {
        LibVosk.setLogLevel(LogLevel.WARNINGS)
        Model(dir.path)
    } catch (e: Throwable) { throw SpeechFailure(SpeechFailure.Kind.ENGINE, "The speech model couldn't be loaded: ${e.message}", e) }

    override suspend fun transcribe(pcm: ShortArray): List<SpokenWord> = withContext(Dispatchers.Default) {
        val out = ArrayList<SpokenWord>()
        try {
            Recognizer(model, SAMPLE_RATE.toFloat()).use { rec ->
                rec.setWords(true)
                val block = ShortArray(FEED_SAMPLES)
                var i = 0
                while (i < pcm.size) {
                    ensureActive()
                    val n = minOf(FEED_SAMPLES, pcm.size - i)
                    pcm.copyInto(block, 0, i, i + n)
                    if (rec.acceptWaveForm(block, n)) out += parseVoskResult(rec.result)
                    i += n
                }
                out += parseVoskResult(rec.finalResult)
            }
        } catch (e: CancellationException) { throw e } catch (e: Throwable) {
            throw SpeechFailure(SpeechFailure.Kind.ENGINE, "Vosk failed: ${e.message}", e)
        }
        out
    }

    override fun close() { runCatching { model.close() } }

    private companion object { const val FEED_SAMPLES = 8000 }
}

private const val DETECT_BUDGET_MS = 20_000L
private const val DETECT_SAMPLE_MS = 30_000L
private const val DETECT_SESSION_MS = 8_000L

/** "ru"/"en" by majority over three samples of the book, or null: below API 34, on any failure, or past the budget. */
suspend fun detectSpoken(context: Context, tracks: List<Track>): String? {
    if (Build.VERSION.SDK_INT < 34 || !androidRecognizerAvailable(context) || tracks.isEmpty()) return null
    if (!RecognizerSlots.permits.tryAcquire()) return null
    val votes = ArrayList<String?>()
    try {
        withTimeoutOrNull(DETECT_BUDGET_MS) {
            withContext(Dispatchers.IO) {
                val audioMs = tracks.sumOf { it.durationMs }
                PcmReader(context, tracks) { !isActive }.use { reader ->
                    for (at in listOf(0.1, 0.5, 0.8)) {
                        val from = (audioMs * at).toLong()
                        votes += detectOne(context, reader.read(from, minOf(from + DETECT_SAMPLE_MS, audioMs)))
                    }
                }
            }
        }
    } catch (e: CancellationException) { currentCoroutineContext().ensureActive() } catch (e: Exception) {
        Log.d(TAG, "language detection failed", e)
    } finally { RecognizerSlots.permits.release() }
    return majorityLanguage(votes)
}

@RequiresApi(34)
private suspend fun detectOne(context: Context, pcm: ShortArray): String? {
    var found: String? = null
    try {
        recognise(context, pcm, ALLOWED[0], DETECT_SESSION_MS, {
            putExtra(RecognizerIntent.EXTRA_ENABLE_LANGUAGE_DETECTION, true)
            putStringArrayListExtra(RecognizerIntent.EXTRA_LANGUAGE_DETECTION_ALLOWED_LANGUAGES, ALLOWED)
        }) { b ->
            val confident = b.getInt(SpeechRecognizer.LANGUAGE_DETECTION_CONFIDENCE_LEVEL) >= SpeechRecognizer.LANGUAGE_DETECTION_CONFIDENCE_LEVEL_CONFIDENT
            if (confident) found = b.getString(SpeechRecognizer.DETECTED_LANGUAGE)
            confident
        }
    } catch (e: SpeechFailure) { Log.d(TAG, "detection sample failed: ${e.message}") }
    return found
}
