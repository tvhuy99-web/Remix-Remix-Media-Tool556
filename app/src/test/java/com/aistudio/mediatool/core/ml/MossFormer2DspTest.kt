package com.aistudio.mediatool.core.ml

import kotlin.math.abs
import kotlin.math.ln
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MossFormer2DspTest {
    @Test
    fun supportedWindowModesProduceExpectedFrames() {
        assertEquals(496, VoiceCleanupWindowMode.COMPATIBILITY_4S.frames)
        assertEquals(1_246, VoiceCleanupWindowMode.BALANCED_10S.frames)
        assertEquals(1_871, VoiceCleanupWindowMode.MAXIMUM_15S.frames)
    }

    @Test
    fun segmentationAlwaysCoversRetainedOutputForEveryMode() {
        for (mode in VoiceCleanupWindowMode.entries) {
            val dsp = MossFormer2Dsp(mode)
            val firstRetained = dsp.segmentSamples - dsp.edgeDiscardSamples
            val lengths = listOf(
                0L,
                1L,
                firstRetained.toLong(),
                firstRetained + 1L,
                mode.segmentSamples.toLong(),
                mode.segmentSamples + 1L,
                1_000_000L,
            )

            for (length in lengths) {
                val count = dsp.segmentCount(length)
                val retainedCoverage = firstRetained.toLong() + (count - 1L) * dsp.strideSamples
                val padded = dsp.paddedLength(length)

                assertTrue(retainedCoverage >= length)
                assertTrue(padded >= length)
                assertEquals(0L, (padded - dsp.segmentSamples) % dsp.strideSamples)
                if (count > 1) {
                    assertTrue(retainedCoverage - dsp.strideSamples < length)
                }
            }
        }
    }

    @Test
    fun retainedRangesJoinWithoutGapsForEveryMode() {
        for (mode in VoiceCleanupWindowMode.entries) {
            val dsp = MossFormer2Dsp(mode)
            val first = dsp.retainedRange(0)
            val next = dsp.retainedRange(1)

            assertEquals(dsp.segmentSamples - dsp.edgeDiscardSamples, first.count())
            assertEquals(dsp.strideSamples, next.count())
            assertEquals(dsp.edgeDiscardSamples, next.first)
            assertEquals(dsp.segmentSamples - dsp.edgeDiscardSamples - 1, next.last)
        }
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
        val dsp = MossFormer2Dsp(VoiceCleanupWindowMode.COMPATIBILITY_4S)
        val features = dsp.buildFeatures(FloatArray(dsp.segmentSamples))
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
        val window = MossFormer2Dsp(VoiceCleanupWindowMode.COMPATIBILITY_4S).windowSnapshot()
        assertEquals(MossFormer2Dsp.FFT_SIZE, window.size)
        assertTrue(abs(window.first() - window.last()) < 1e-7f)
        assertTrue(window.first() > 0f)
        assertTrue(window.maxOrNull()!! <= 1.000001f)
    }
}
