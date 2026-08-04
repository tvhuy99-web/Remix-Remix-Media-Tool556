package com.aistudio.mediatool.core.ml

import kotlin.math.abs
import kotlin.math.ln
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MossFormer2DspTest {
    @Test
    fun officialSegmentProduces496Frames() {
        assertEquals(
            MossFormer2Dsp.FRAMES,
            1 + (MossFormer2Dsp.SEGMENT_SAMPLES - MossFormer2Dsp.FFT_SIZE) / MossFormer2Dsp.HOP_SIZE,
        )
    }

    @Test
    fun segmentationAlwaysCoversInput() {
        for (length in listOf(0L, 1L, 192_000L, 192_001L, 1_000_000L)) {
            val padded = MossFormer2Dsp.paddedLength(length)
            assertTrue(padded >= length)
            assertEquals(
                0L,
                (padded - MossFormer2Dsp.SEGMENT_SAMPLES) % MossFormer2Dsp.STRIDE_SAMPLES,
            )
        }
    }

    @Test
    fun retainedRangesJoinWithoutGaps() {
        val first = MossFormer2Dsp.retainedRange(0)
        val next = MossFormer2Dsp.retainedRange(1)
        assertEquals(168_000, first.count())
        assertEquals(144_000, next.count())
        assertEquals(MossFormer2Dsp.STRIDE_SAMPLES, next.count())
    }

    @Test
    fun deltasReplicateBoundaryFrames() {
        val input = floatArrayOf(0f, 1f, 2f, 3f)
        val actual = MossFormer2Dsp.computeDeltas(input, frames = 4, bins = 1)
        val expected = floatArrayOf(0.5f, 0.8f, 0.8f, 0.5f)
        actual.indices.forEach { index ->
            assertTrue(abs(actual[index] - expected[index]) < 1e-6f)
        }
    }

    @Test
    fun silenceUsesKaldiFloat32EpsilonBeforeLog() {
        val features = MossFormer2Dsp().buildFeatures(FloatArray(MossFormer2Dsp.SEGMENT_SAMPLES))
        val expectedLogFloor = ln(Math.ulp(1.0f).toDouble()).toFloat()

        for (mel in 0 until MossFormer2Dsp.MEL_BINS) {
            assertEquals(expectedLogFloor, features[mel], 1e-5f)
        }
        for (feature in MossFormer2Dsp.MEL_BINS until MossFormer2Dsp.FEATURES) {
            assertEquals(0f, features[feature], 1e-6f)
        }
    }

    @Test
    fun symmetricHammingWindowIsStableAndNonZeroAtEdges() {
        val window = MossFormer2Dsp().windowSnapshot()
        assertEquals(MossFormer2Dsp.FFT_SIZE, window.size)
        assertTrue(abs(window.first() - window.last()) < 1e-7f)
        assertTrue(window.first() > 0f)
        assertTrue(window.maxOrNull()!! <= 1.000001f)
    }
}
