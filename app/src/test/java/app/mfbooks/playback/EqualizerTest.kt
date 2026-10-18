package app.mfbooks.playback

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.PI
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

class EqualizerTest {
    private val rate = 44_100

    private fun sine(freq: Double, n: Int = rate) = DoubleArray(n) { sin(2 * PI * freq * it / rate) * 0.5 }

    private fun run(eq: Equalizer, x: DoubleArray) = DoubleArray(x.size) { eq.process(x[it], 0) }

    private fun rms(x: DoubleArray, from: Int) = sqrt(x.drop(from).sumOf { it * it } / (x.size - from))

    @Test fun unityGainAtZeroDb() {
        // All five biquads computed and applied at 0 dB: output must equal input.
        val eq = Equalizer(rate, 1).apply { setGains(FloatArray(5), bypassZero = false) }
        for (f in doubleArrayOf(60.0, 440.0, 1_000.0, 4_000.0, 12_000.0)) {
            val x = sine(f)
            assertArrayEquals("at $f Hz", x, run(eq, x), 1e-9)
        }
    }

    @Test fun boostAtBandCentre() {
        val eq = Equalizer(rate, 1).apply { setGains(floatArrayOf(0f, 0f, 6f, 0f, 0f)) }
        val x = sine(910.0)
        val gainDb = 20 * log10(rms(run(eq, x), rate / 4) / rms(x, rate / 4))
        assertEquals(6.0, gainDb, 0.3)
    }

    @Test fun bandsParsing() {
        assertArrayEquals(floatArrayOf(1f, -15f, 15f, 0f, 0f), parseBands("1,-20,99,x"), 0f)
        assertArrayEquals(FloatArray(5), parseBands(null), 0f)
    }
}
