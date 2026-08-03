package com.aistudio.mediatool.core.ml

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DpdfNetDspTest {
    @Test
    fun vorbisWindowIsFinitePositiveAndSymmetric() {
        val window = DpdfNetDsp.buildVorbisWindow()

        assertEquals(DpdfNetDsp.WINDOW_LENGTH, window.size)
        window.forEach { value ->
            assertTrue(value.isFinite())
            assertTrue(value > 0f)
            assertTrue(value <= 1f)
        }
        for (index in window.indices) {
            assertEquals(window[index], window[window.lastIndex - index], 1e-6f)
        }
    }

    @Test
    fun stftAndIstftOverlapAddRestoreCenteredSignal() {
        val hop = DpdfNetDsp.HOP_LENGTH
        val frameCount = 24
        val signal = FloatArray(frameCount * hop + hop) { index ->
            (0.55 * sin(2.0 * PI * 420.0 * index / DpdfNetDsp.SAMPLE_RATE) +
                0.11 * sin(2.0 * PI * 3_100.0 * index / DpdfNetDsp.SAMPLE_RATE)).toFloat()
        }
        val dsp = DpdfNetDsp()
        val frame = FloatArray(DpdfNetDsp.WINDOW_LENGTH)
        val spectrum = FloatArray(DpdfNetDsp.TENSOR_ELEMENTS)
        val hopOutput = FloatArray(hop)
        val restored = FloatArray((frameCount + 1) * hop)

        for (frameIndex in 0 until frameCount) {
            System.arraycopy(signal, frameIndex * hop, frame, 0, frame.size)
            dsp.forward(frame, spectrum)
            dsp.inverseHop(spectrum, hopOutput)
            System.arraycopy(hopOutput, 0, restored, frameIndex * hop, hop)
        }
        dsp.flushHop(hopOutput)
        System.arraycopy(hopOutput, 0, restored, frameCount * hop, hop)

        // Librosa center=True discards the first and last half-window. Those edge
        // samples intentionally have only a near-zero Vorbis weight; the actual
        // DPDFNet pipeline never exports them.
        var maxError = 0.0
        for (index in hop until restored.size - hop) {
            maxError = maxOf(maxError, abs(restored[index] - signal[index]).toDouble())
        }
        assertTrue("centered max error=$maxError", maxError < 3e-4)
        assertTrue(restored.all(Float::isFinite))
    }

    @Test
    fun attenuationLimitBlendsAlignedNoisySpectrum() {
        val dsp = DpdfNetDsp()
        val enhanced = FloatArray(DpdfNetDsp.TENSOR_ELEMENTS) { 0.2f }
        val noisy = FloatArray(DpdfNetDsp.TENSOR_ELEMENTS) { 1f }

        dsp.applyAttenuationLimit(enhanced, noisy, noisyBlend = 0.25f)

        enhanced.forEach { assertEquals(0.4f, it, 1e-6f) }
    }

    @Test
    fun missingAlignedNoisyFrameLeavesOutputUnchanged() {
        val dsp = DpdfNetDsp()
        val enhanced = FloatArray(DpdfNetDsp.TENSOR_ELEMENTS) { index -> index * 0.001f }
        val original = enhanced.copyOf()

        dsp.applyAttenuationLimit(enhanced, alignedNoisy = null, noisyBlend = 0.8f)

        assertTrue(enhanced.contentEquals(original))
    }
}
