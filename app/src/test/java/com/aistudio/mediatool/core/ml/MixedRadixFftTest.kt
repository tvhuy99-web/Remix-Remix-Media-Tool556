package com.aistudio.mediatool.core.ml

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.assertTrue
import org.junit.Test

class MixedRadixFftTest {
    @Test
    fun radixThreeFactorizationMatchesDirectDft() {
        val size = 12
        val real = FloatArray(size) { index ->
            (0.4 * sin(2.0 * PI * index / size) + 0.2 * cos(6.0 * PI * index / size)).toFloat()
        }
        val imag = FloatArray(size) { index -> (0.05 * index).toFloat() }
        val expectedReal = FloatArray(size)
        val expectedImag = FloatArray(size)
        for (k in 0 until size) {
            for (n in 0 until size) {
                val angle = -2.0 * PI * k * n / size.toDouble()
                expectedReal[k] += (real[n] * cos(angle) - imag[n] * sin(angle)).toFloat()
                expectedImag[k] += (real[n] * sin(angle) + imag[n] * cos(angle)).toFloat()
            }
        }

        MixedRadixFft(size).forward(real, imag)

        assertMaxErrorBelow(real, expectedReal, 1e-4f)
        assertMaxErrorBelow(imag, expectedImag, 1e-4f)
    }

    @Test
    fun fft6144RoundTripsComplexSignal() {
        val size = 6_144
        val originalReal = FloatArray(size) { index ->
            (0.6 * sin(2.0 * PI * 37.0 * index / size) +
                0.2 * cos(2.0 * PI * 913.0 * index / size)).toFloat()
        }
        val originalImag = FloatArray(size) { index ->
            (0.1 * sin(2.0 * PI * 127.0 * index / size)).toFloat()
        }
        val real = originalReal.copyOf()
        val imag = originalImag.copyOf()
        val fft = MixedRadixFft(size)

        fft.forward(real, imag)
        fft.inverse(real, imag)

        assertMaxErrorBelow(real, originalReal, 2e-3f)
        assertMaxErrorBelow(imag, originalImag, 2e-3f)
    }

    @Test
    fun fft4096RoundTripsRealSignal() {
        val size = 4_096
        val original = FloatArray(size) { index ->
            (0.8 * sin(2.0 * PI * 41.0 * index / size)).toFloat()
        }
        val real = original.copyOf()
        val imag = FloatArray(size)
        val fft = MixedRadixFft(size)

        fft.forward(real, imag)
        fft.inverse(real, imag)

        assertMaxErrorBelow(real, original, 1e-3f)
        assertMaxErrorBelow(imag, FloatArray(size), 1e-3f)
    }

    private fun assertMaxErrorBelow(actual: FloatArray, expected: FloatArray, threshold: Float) {
        val maxError = actual.indices.maxOf { index -> abs(actual[index] - expected[index]) }
        assertTrue("max error $maxError exceeds $threshold", maxError <= threshold)
    }
}
