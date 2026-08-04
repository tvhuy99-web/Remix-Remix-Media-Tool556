package com.aistudio.mediatool.core.ml

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.assertTrue
import org.junit.Test

class BluesteinFftTest {
    @Test
    fun fft1920RoundTripsComplexSignal() {
        val size = 1_920
        val originalReal = FloatArray(size) { index ->
            (0.7 * sin(2.0 * PI * 41.0 * index / size) +
                0.2 * cos(2.0 * PI * 307.0 * index / size)).toFloat()
        }
        val originalImag = FloatArray(size) { index ->
            (0.1 * sin(2.0 * PI * 127.0 * index / size)).toFloat()
        }
        val real = originalReal.copyOf()
        val imag = originalImag.copyOf()
        val fft = BluesteinFft(size)

        fft.forward(real, imag)
        fft.inverse(real, imag)

        val realError = real.indices.maxOf { abs(real[it] - originalReal[it]) }
        val imagError = imag.indices.maxOf { abs(imag[it] - originalImag[it]) }
        assertTrue("real max error $realError", realError < 2e-3f)
        assertTrue("imag max error $imagError", imagError < 2e-3f)
    }
}
