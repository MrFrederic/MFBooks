package app.mfbooks.convert

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

class ResamplerTest {
    private fun sine(rate: Int, freq: Double, frames: Int, channels: Int = 1) =
        ShortArray(frames * channels) { i -> (sin(2 * PI * freq * (i / channels) / rate) * 10000).toInt().toShort() }

    /** Feeds in uneven chunks to exercise the streaming state. */
    private fun resample(r: Resampler, x: ShortArray, channels: Int): ShortArray {
        val out = ArrayList<Short>()
        var pos = 0; var chunk = 37
        while (pos < x.size / channels) {
            val n = minOf(chunk, x.size / channels - pos)
            r.process(x.copyOfRange(pos * channels, (pos + n) * channels), n).forEach(out::add)
            pos += n; chunk = (chunk * 7 + 13) % 3000 + 1
        }
        r.flush().forEach(out::add)
        return out.toShortArray()
    }

    private fun rms(x: ShortArray, from: Int, to: Int) = sqrt((from until to).sumOf { x[it].toDouble() * x[it] } / (to - from))

    /** Zero crossings per second ≈ 2 × frequency. */
    private fun freq(x: ShortArray, rate: Int, from: Int, to: Int): Double {
        var z = 0
        for (i in from + 1 until to) if ((x[i - 1] < 0) != (x[i] < 0)) z++
        return z / 2.0 / ((to - from).toDouble() / rate)
    }

    @Test fun lengthIsExactForCommonRates() {
        for (inRate in listOf(8000, 11025, 22050, 32000, 44100, 96000)) {
            val frames = inRate * 3 + 17
            val out = resample(Resampler(inRate, 48000, 1), sine(inRate, 440.0, frames), 1)
            val expected = (frames.toLong() * 48000 + inRate - 1) / inRate
            assertEquals("rate $inRate", expected, out.size.toLong())
        }
    }

    @Test fun keepsFrequencyAndLevel44kTo48k() {
        val x = sine(44100, 1000.0, 44100 * 2)
        val y = resample(Resampler(44100, 48000, 1), x, 1)
        assertEquals(1000.0, freq(y, 48000, 4800, y.size - 4800), 2.0)
        val ratio = rms(y, 4800, y.size - 4800) / rms(x, 4410, x.size - 4410)
        assertTrue("gain $ratio", abs(ratio - 1) < 0.02)
    }

    @Test fun stereoChannelsStaySeparate() {
        val frames = 22050
        val x = ShortArray(frames * 2) { i -> if (i % 2 == 0) (sin(2 * PI * 300 * (i / 2) / 22050.0) * 8000).toInt().toShort() else 0 }
        val y = resample(Resampler(22050, 48000, 2), x, 2)
        val left = ShortArray(y.size / 2) { y[it * 2] }; val right = ShortArray(y.size / 2) { y[it * 2 + 1] }
        assertEquals(300.0, freq(left, 48000, 2000, left.size - 2000), 2.0)
        assertTrue(rms(right, 0, right.size) < 1.0)
    }

    @Test fun downsamplingRejectsAliases() {
        // 30 kHz at 96 kHz is above the 24 kHz output Nyquist and must be filtered out, not folded to 18 kHz
        val y = resample(Resampler(96000, 48000, 1), sine(96000, 30000.0, 96000), 1)
        assertTrue(rms(y, 1000, y.size - 1000) < 10000 / sqrt(2.0) * 0.05)
    }

    @Test fun passthroughAt48k() {
        val x = sine(48000, 440.0, 4800)
        assertEquals(x.toList(), resample(Resampler(48000, 48000, 1), x, 1).toList())
    }
}
