package app.mfbooks.convert

import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Streaming windowed-sinc resampler for interleaved 16-bit PCM (any rate → [outRate], usually 48 kHz for Opus).
 * Exact phase from integer arithmetic (no drift over hours); kernel tables per phase, Blackman window.
 * Output length is exactly ceil(inFrames * outRate / inRate) after [flush].
 */
class Resampler(private val inRate: Int, private val outRate: Int, private val channels: Int, halfTaps: Int = 16) {
    private val passthrough = inRate == outRate
    private val g = gcd(inRate, outRate)
    private val step = inRate / g          // input advance per output frame, in 1/den units
    private val den = outRate / g
    private val ratio = outRate.toDouble() / inRate
    private val cutoff = min(1.0, ratio) * 0.94
    private val half = ceil(halfTaps / min(1.0, ratio)).toInt()  // wider kernel when downsampling
    private val taps = 2 * half
    private val phases = min(den, 1024)
    // kernel[p][k] weights input sample (base - half + 1 + k) for fractional offset p/phases
    private val kernel = Array(phases) { p ->
        val frac = p.toDouble() / phases
        val w = DoubleArray(taps) { k -> h(k - half + 1 - frac) }
        val sum = w.sum()
        FloatArray(taps) { (w[it] / sum).toFloat() } // normalise: unity DC gain
    }

    private fun h(x: Double): Double {
        val win = (x + half) / (2.0 * half)                     // 0..1 across the window
        if (win <= 0.0 || win >= 1.0) return 0.0
        val blackman = 0.42 - 0.5 * cos(2 * PI * win) + 0.08 * cos(4 * PI * win)
        val t = PI * cutoff * x
        return cutoff * (if (x == 0.0) 1.0 else sin(t) / t) * blackman
    }

    // Input history (frames, interleaved). bufStart = absolute input frame index of buf[0].
    private var buf = FloatArray(4096 * channels)
    private var bufFrames = 0
    private var bufStart = -(half - 1).toLong()   // pre-pad with zeros so the first output centres on sample 0
    private var outIndex = 0L
    private var inTotal = 0L

    init { bufFrames = half - 1 }

    /** Feed [frames] interleaved frames from [src]; returns resampled interleaved samples produced so far. */
    fun process(src: ShortArray, frames: Int): ShortArray {
        if (passthrough) return src.copyOf(frames * channels)
        ensure(bufFrames + frames)
        for (i in 0 until frames * channels) buf[bufFrames * channels + i] = src[i].toFloat()
        bufFrames += frames
        inTotal += frames
        return produce(Long.MAX_VALUE)
    }

    /** End of stream: pads with silence and returns the tail so the output length matches the input duration. */
    fun flush(): ShortArray {
        if (passthrough) return ShortArray(0)
        val target = (inTotal * outRate + inRate - 1) / inRate
        ensure(bufFrames + half + 1)
        repeat((half + 1) * channels) { buf[bufFrames * channels + it] = 0f }
        bufFrames += half + 1
        return produce(target)
    }

    private fun produce(limit: Long): ShortArray {
        val out = ShortArrayBuilder()
        while (outIndex < limit) {
            val num = outIndex * step                       // input position = num / den
            val base = num / den
            val first = base - half + 1                     // first input frame the kernel touches
            val last = first + taps - 1
            if (last >= bufStart + bufFrames) break         // need more input
            val p = ((num % den) * phases / den).toInt()
            val k = kernel[p]
            val off = (first - bufStart).toInt()
            for (c in 0 until channels) {
                var acc = 0f
                var idx = off * channels + c
                for (t in 0 until taps) { acc += k[t] * buf[idx]; idx += channels }
                out.add(acc.coerceIn(-32768f, 32767f).toInt().toShort())
            }
            outIndex++
        }
        // drop frames no future output needs
        val keepFrom = (outIndex * step / den) - half + 1
        val drop = (keepFrom - bufStart).toInt().coerceIn(0, bufFrames)
        if (drop > 0) {
            System.arraycopy(buf, drop * channels, buf, 0, (bufFrames - drop) * channels)
            bufFrames -= drop; bufStart += drop
        }
        return out.toArray()
    }

    private fun ensure(frames: Int) {
        if (frames * channels > buf.size) buf = buf.copyOf(maxOf(frames * channels, buf.size * 2))
    }

    private class ShortArrayBuilder {
        private var a = ShortArray(1024); private var n = 0
        fun add(s: Short) { if (n == a.size) a = a.copyOf(n * 2); a[n++] = s }
        fun toArray() = a.copyOf(n)
    }

    companion object {
        tailrec fun gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)
    }
}
