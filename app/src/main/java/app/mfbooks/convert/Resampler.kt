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
        val b = buf; val o = bufFrames * channels
        for (i in 0 until frames * channels) b[o + i] = src[i].toFloat()
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
        // Every output frame whose kernel fits in the buffered input, up to [limit]: count first, then fill one array.
        val end = bufStart + bufFrames                      // first input frame we don't have
        // output m is computable iff floor(m*step/den) + half <= end - 1  ⇔  m*step < (end - half) * den
        val avail = if (end - half < 0) 0L else ((end - half) * den + step - 1) / step
        val n = (minOf(avail, limit) - outIndex).coerceAtLeast(0).toInt()
        val out = ShortArray(n * channels)
        var o = 0
        val buf = buf; val taps = taps
        repeat(n) {
            val num = outIndex * step                       // input position = num / den
            val first = num / den - half + 1                // first input frame the kernel touches
            val k = kernel[((num % den) * phases / den).toInt()]
            var idx = (first - bufStart).toInt() * channels
            when (channels) {
                1 -> {
                    var acc = 0f
                    for (t in 0 until taps) acc += k[t] * buf[idx + t]
                    out[o++] = clip(acc)
                }
                2 -> {
                    var l = 0f; var r = 0f
                    for (t in 0 until taps) { val w = k[t]; l += w * buf[idx]; r += w * buf[idx + 1]; idx += 2 }
                    out[o++] = clip(l); out[o++] = clip(r)
                }
                else -> for (c in 0 until channels) {
                    var acc = 0f
                    var i = idx + c
                    for (t in 0 until taps) { acc += k[t] * buf[i]; i += channels }
                    out[o++] = clip(acc)
                }
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
        return out
    }

    private fun clip(v: Float): Short = (if (v > 32767f) 32767f else if (v < -32768f) -32768f else v).toInt().toShort()

    private fun ensure(frames: Int) {
        if (frames * channels > buf.size) buf = buf.copyOf(maxOf(frames * channels, buf.size * 2))
    }

    companion object {
        tailrec fun gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)
    }
}
