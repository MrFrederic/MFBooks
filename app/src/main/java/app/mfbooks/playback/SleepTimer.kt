package app.mfbooks.playback

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.SystemClock
import androidx.media3.common.Player
import app.mfbooks.data.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Sleep timer (§5.4). Counts playback (wall) time only, fades volume linearly over the last [Settings.sleepFadeSec],
 * then pauses and restores the volume. Shake restarts the countdown. Owned by [PlaybackService] (main thread).
 */
class SleepTimer(
    private val context: Context,
    private val player: Player,
    private val scope: CoroutineScope,
    private val onSlept: () -> Unit,
) : SensorEventListener {
    private val _state = MutableStateFlow(SleepState())
    val state: StateFlow<SleepState> = _state

    var settings = Settings()
    var inCar = false
        set(v) { field = v; if (v && settings.disableSleepInCar) set(SleepMode.Off) }

    private var job: Job? = null
    private var chapterIndex = -1
    private var chimed = false
    private var lastMag = -1f
    private var lastShakeAt = 0L
    private val sensors get() = context.getSystemService(SensorManager::class.java)
    private val fadeMs get() = settings.sleepFadeSec.coerceAtLeast(1) * 1000L

    fun set(mode: SleepMode) {
        job?.cancel(); job = null
        sensors?.unregisterListener(this)
        player.volume = 1f
        if (mode == SleepMode.Off || (inCar && settings.disableSleepInCar)) { _state.value = SleepState(); return }
        chapterIndex = player.currentMediaItemIndex
        chimed = false
        _state.value = SleepState(mode, remaining(mode, fullMs(mode)))
        if (settings.trackMotion != "NEVER")
            sensors?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let { sensors?.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
        job = scope.launch {
            var last = SystemClock.elapsedRealtime()
            while (isActive) {
                delay(250)
                val now = SystemClock.elapsedRealtime()
                tick(now - last); last = now
            }
        }
    }

    /** Restart the countdown (shake, SLEEP_EXTEND). */
    fun reset() {
        val s = _state.value
        if (s.mode == SleepMode.Off) return
        player.volume = 1f
        chimed = false
        _state.value = s.copy(remainingMs = remaining(s.mode, fullMs(s.mode)), fading = false)
        chime(reset = true)
    }

    fun release() = set(SleepMode.Off)

    private fun fullMs(m: SleepMode) = if (m is SleepMode.Duration) m.minutes * 60_000L else 0L

    private fun remaining(m: SleepMode, durationLeft: Long): Long = when (m) {
        is SleepMode.Duration -> durationLeft
        SleepMode.EndOfChapter -> {
            val d = player.duration
            if (d <= 0) Long.MAX_VALUE else ((d - player.currentPosition) / player.playbackParameters.speed).toLong()
        }
        SleepMode.Off -> 0
    }

    private fun tick(dt: Long) {
        val s = _state.value
        if (s.mode == SleepMode.EndOfChapter && player.currentMediaItemIndex != chapterIndex) {
            // Crossed into the next chapter: stop at its start.
            player.seekTo(player.currentMediaItemIndex, 0); fire(); return
        }
        val left = remaining(s.mode, if (player.isPlaying) s.remainingMs - dt else s.remainingMs)
        if (left <= 0 || (s.mode == SleepMode.EndOfChapter && left < 300 && player.isPlaying)) { fire(); return }
        val fading = left <= fadeMs
        if (!chimed && left <= min(50_000L, fadeMs)) { chimed = true; chime(reset = false) }
        player.volume = if (fading) (left.toFloat() / fadeMs).coerceIn(0f, 1f) else 1f
        _state.value = s.copy(remainingMs = left, fading = fading)
    }

    private fun fire() {
        player.pause()
        onSlept()
        set(SleepMode.Off)
    }

    override fun onSensorChanged(e: SensorEvent) {
        val (x, y, z) = e.values
        val mag = sqrt(x * x + y * y + z * z)
        val delta = if (lastMag < 0) 0f else abs(mag - lastMag)
        lastMag = mag
        val s = _state.value
        val counts = settings.trackMotion == "ALWAYS" || (settings.trackMotion == "FADE" && s.fading)
        val now = SystemClock.elapsedRealtime()
        if (counts && delta > settings.shakeForce && now - lastShakeAt > 1_500) { lastShakeAt = now; reset() }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    /** Original chime: a short synthesized sine (two rising notes for reset). */
    private fun chime(reset: Boolean) {
        val vol = when (settings.chimeVolume) { 0 -> return; 1 -> 0.2f; 2 -> 0.45f; else -> 0.9f }
        scope.launch(Dispatchers.Default) {
            runCatching {
                val rate = 22_050
                val notes = if (reset) listOf(660.0, 990.0) else listOf(880.0)
                val noteLen = rate * 140 / 1000
                val pcm = ShortArray(noteLen * notes.size)
                notes.forEachIndexed { n, f ->
                    for (i in 0 until noteLen) {
                        val env = sin(PI * i / noteLen) // smooth attack/decay, no clicks
                        pcm[n * noteLen + i] = (sin(2 * PI * f * i / rate) * env * vol * Short.MAX_VALUE).toInt().toShort()
                    }
                }
                val track = AudioTrack.Builder()
                    .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                    .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                    .setTransferMode(AudioTrack.MODE_STATIC).setBufferSizeInBytes(pcm.size * 2).build()
                track.write(pcm, 0, pcm.size)
                track.play()
                delay(pcm.size * 1000L / rate + 100)
                track.release()
            }
        }
    }
}
