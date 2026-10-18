package app.mfbooks.playback

import android.content.Context
import android.media.audiofx.LoudnessEnhancer
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import java.nio.ByteBuffer
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

val EQ_BANDS_HZ = floatArrayOf(60f, 230f, 910f, 4000f, 14000f)

/** "0,0,0,0,0" ↔ FloatArray, tolerant of junk. */
fun parseBands(s: String?): FloatArray =
    FloatArray(EQ_BANDS_HZ.size) { i -> s?.split(',')?.getOrNull(i)?.trim()?.toFloatOrNull()?.coerceIn(-15f, 15f) ?: 0f }

/** RBJ-cookbook peaking biquads (Q≈1), one cascade per channel. Pure math, so it is unit-testable on the JVM. */
class Equalizer(private val sampleRate: Int, private val channels: Int) {
    // per band: b0 b1 b2 a1 a2 (normalised by a0)
    private val coef = Array(EQ_BANDS_HZ.size) { doubleArrayOf(1.0, 0.0, 0.0, 0.0, 0.0) }
    private val active = BooleanArray(EQ_BANDS_HZ.size)
    // per channel, per band: x1 x2 y1 y2
    private val state = Array(channels) { Array(EQ_BANDS_HZ.size) { DoubleArray(4) } }
    var isFlat = true; private set

    /** [bypassZero] = false runs 0 dB bands through the filter anyway (tests). */
    fun setGains(db: FloatArray, q: Double = 1.0, bypassZero: Boolean = true) {
        for (b in EQ_BANDS_HZ.indices) {
            val g = db.getOrElse(b) { 0f }.toDouble()
            val f0 = EQ_BANDS_HZ[b].toDouble()
            active[b] = (g != 0.0 || !bypassZero) && f0 < sampleRate * 0.45
            if (!active[b]) continue
            val a = 10.0.pow(g / 40)
            val w0 = 2 * PI * f0 / sampleRate
            val alpha = sin(w0) / (2 * q)
            val a0 = 1 + alpha / a
            coef[b][0] = (1 + alpha * a) / a0
            coef[b][1] = -2 * cos(w0) / a0
            coef[b][2] = (1 - alpha * a) / a0
            coef[b][3] = -2 * cos(w0) / a0
            coef[b][4] = (1 - alpha / a) / a0
        }
        isFlat = active.none { it }
    }

    fun reset() = state.forEach { ch -> ch.forEach { it.fill(0.0) } }

    fun process(x: Double, channel: Int): Double {
        var v = x
        for (b in EQ_BANDS_HZ.indices) {
            if (!active[b]) continue
            val c = coef[b]; val s = state[channel][b]
            val y = c[0] * v + c[1] * s[0] + c[2] * s[1] - c[3] * s[2] - c[4] * s[3]
            s[1] = s[0]; s[0] = v; s[3] = s[2]; s[2] = y
            v = y
        }
        return v
    }
}

/**
 * EQ + balance + mono in one always-active processor, adjustable while playing.
 * ponytail: balance/mono live here instead of Media3's ChannelMixingAudioProcessor, because that one goes inactive
 * for an identity matrix at configure time and can't be switched on again without reconfiguring the sink.
 */
class EffectsProcessor : BaseAudioProcessor() {
    @Volatile private var bands = FloatArray(EQ_BANDS_HZ.size)
    @Volatile private var balance = 0f
    @Volatile private var mono = false
    @Volatile private var dirty = true
    private var eq: Equalizer? = null

    fun set(bandsDb: FloatArray, balance: Float, mono: Boolean) {
        if (bandsDb.contentEquals(bands) && balance == this.balance && mono == this.mono) return
        bands = bandsDb.copyOf(); this.balance = balance.coerceIn(-1f, 1f); this.mono = mono; dirty = true
    }

    override fun onConfigure(f: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (f.encoding != C.ENCODING_PCM_16BIT && f.encoding != C.ENCODING_PCM_FLOAT) return AudioProcessor.AudioFormat.NOT_SET
        eq = Equalizer(f.sampleRate, f.channelCount); dirty = true
        return f
    }

    override fun queueInput(input: ByteBuffer) {
        val n = input.remaining()
        if (n == 0) return
        val out = replaceOutputBuffer(n)
        // Must always consume input, or the sink stalls in BUFFERING forever.
        val eq = eq ?: Equalizer(inputAudioFormat.sampleRate, inputAudioFormat.channelCount).also { eq = it; dirty = true }
        if (dirty) { eq.setGains(bands); dirty = false }
        val ch = inputAudioFormat.channelCount
        val bal = balance; val mono = mono
        if (eq.isFlat && bal == 0f && !mono) { out.put(input); out.flip(); return }
        val float = inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT
        val gl = if (bal > 0) 1f - bal else 1f
        val gr = if (bal < 0) 1f + bal else 1f
        val frame = DoubleArray(ch)
        while (input.remaining() >= inputAudioFormat.bytesPerFrame) {
            for (c in 0 until ch) {
                val x = if (float) input.float.toDouble() else input.short / 32768.0
                frame[c] = eq.process(x, c)
            }
            if (ch == 2) {
                if (mono) { val m = (frame[0] + frame[1]) / 2; frame[0] = m; frame[1] = m }
                frame[0] *= gl; frame[1] *= gr
            }
            for (c in 0 until ch) {
                val v = frame[c].coerceIn(-1.0, 1.0)
                if (float) out.putFloat(v.toFloat()) else out.putShort((v * 32767).toInt().toShort())
            }
        }
        out.flip()
    }

    override fun onFlush() { eq?.reset() }
    override fun onReset() { eq = null }
}

class EffectsRenderersFactory(context: Context, private val effects: EffectsProcessor) : DefaultRenderersFactory(context) {
    override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioOutputPlaybackParams: Boolean): AudioSink =
        DefaultAudioSink.Builder(context)
            .setAudioProcessors(arrayOf(effects))
            .setEnableFloatOutput(enableFloatOutput)
            .setEnableAudioOutputPlaybackParameters(enableAudioOutputPlaybackParams)
            .build()
}

/** Volume boost via LoudnessEnhancer, re-attached when the audio session changes. */
class Boost {
    private var fx: LoudnessEnhancer? = null
    private var mb = 0

    fun attach(audioSessionId: Int) {
        release()
        if (audioSessionId == C.AUDIO_SESSION_ID_UNSET) return
        fx = runCatching { LoudnessEnhancer(audioSessionId) }.getOrNull()
        apply()
    }

    fun set(mb: Int) { this.mb = mb.coerceIn(0, 1500); apply() }

    private fun apply() = runCatching { fx?.run { setTargetGain(mb); enabled = mb > 0 } }

    fun release() { fx?.release(); fx = null }
}
