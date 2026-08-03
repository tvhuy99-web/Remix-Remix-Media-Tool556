package com.aistudio.mediatool.core.ml

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BluesteinFftTest {
    @Test
    fun forwardMatchesNaiveDftForNonPowerOfTwoSize() {
        val size = 15
        val real = FloatArray(size) { index ->
            (0.7 * sin(2.0 * PI * index / size) + 0.2 * cos(6.0 * PI * index / size)).toFloat()
        }
        val imag = FloatArray(size) { index -> (0.1 * cos(4.0 * PI * index / size)).toFloat() }
        val expected = naiveDft(real, imag)

        BluesteinFft(size).forward(real, imag)

        for (index in 0 until size) {
            assertEquals(expected.first[index], real[index].toDouble(), 2e-4)
            assertEquals(expected.second[index], imag[index].toDouble(), 2e-4)
        }
    }

    @Test
    fun roundTripRestoresDpdfNetTransformLength() {
        val size = DpdfNetDsp.WINDOW_LENGTH
        val originalReal = FloatArray(size) { index ->
            (0.5 * sin(2.0 * PI * 440.0 * index / DpdfNetDsp.SAMPLE_RATE) +
                0.15 * cos(2.0 * PI * 3_200.0 * index / DpdfNetDsp.SAMPLE_RATE)).toFloat()
        }
        val originalImag = FloatArray(size) { index -> (index % 17 - 8) * 0.0003f }
        val real = originalReal.copyOf()
        val imag = originalImag.copyOf()
        val fft = BluesteinFft(size)

        fft.forward(real, imag)
        fft.inverse(real, imag)

        var maxError = 0.0
        for (index in 0 until size) {
            maxError = maxOf(maxError, abs(real[index] - originalReal[index]).toDouble())
            maxError = maxOf(maxError, abs(imag[index] - originalImag[index]).toDouble())
        }
        assertTrue("max error=$maxError", maxError < 2e-4)
    }

    private fun naiveDft(real: FloatArray, imag: FloatArray): Pair<DoubleArray, DoubleArray> {
        val size = real.size
        val outputReal = DoubleArray(size)
        val outputImag = DoubleArray(size)
        for (frequency in 0 until size) {
            for (sample in 0 until size) {
                val angle = -2.0 * PI * frequency.toDouble() * sample.toDouble() / size.toDouble()
                val cosine = cos(angle)
                val sine = sin(angle)
                outputReal[frequency] += real[sample] * cosine - imag[sample] * sine
                outputImag[frequency] += real[sample] * sine + imag[sample] * cosine
            }
        }
        return outputReal to outputImag
    }
}
