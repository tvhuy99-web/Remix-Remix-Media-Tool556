package com.aistudio.mediatool.core.ml

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sin
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun shortInputsUseOnePassWithoutDiscardingEdges() {
        for (mode in VoiceCleanupWindowMode.entries) {
            val samples = minOf(mode.onePassLimitSamples, mode.segmentSamples - 1).toLong()
            val plan = VoiceCleanupWindowPlan.resolve(samples, mode)
            val dsp = MossFormer2Dsp(plan)

            assertTrue(plan.fullContext)
            assertEquals(0, plan.edgeDiscardSamples)
            assertEquals(1, dsp.segmentCount(samples))
            assertEquals(0, dsp.retainedRange(0).first)
            assertEquals(plan.segmentSamples - 1, dsp.retainedRange(0).last)
        }
    }

    @Test
    fun maximumModeUsesFullContextUpToTwentySeconds() {
        val eighteenSeconds = 18L * MossFormer2Dsp.SAMPLE_RATE
        val plan = VoiceCleanupWindowPlan.resolve(eighteenSeconds, VoiceCleanupWindowMode.MAXIMUM_15S)

        assertTrue(plan.fullContext)
        assertTrue(plan.segmentSamples >= eighteenSeconds)
        assertTrue(plan.segmentSamples <= 20 * MossFormer2Dsp.SAMPLE_RATE)
        assertEquals(0, plan.edgeDiscardSamples)
    }

    @Test
    fun longInputsStillUseOverlappedFixedWindows() {
        for (mode in VoiceCleanupWindowMode.entries) {
            val samples = mode.onePassLimitSamples.toLong() + 1L
            val plan = VoiceCleanupWindowPlan.resolve(samples, mode)
            val dsp = MossFormer2Dsp(plan)

            assertFalse(plan.fullContext)
            assertEquals(mode.segmentSamples, plan.segmentSamples)
            assertTrue(dsp.segmentCount(samples) >= 2)
        }
    }

    @Test
    fun segmentationAlwaysCoversInputForEveryMode() {
        for (mode in VoiceCleanupWindowMode.entries) {
            val dsp = MossFormer2Dsp(VoiceCleanupWindowPlan.fixed(mode))
            for (length in listOf(0L, 1L, mode.segmentSamples.toLong(), mode.segmentSamples + 1L, 1_000_000L)) {
                val padded = dsp.paddedLength(length)
                assertTrue(padded >= length)
                assertEquals(0L, (padded - dsp.segmentSamples) % dsp.strideSamples)
            }
        }
    }

    @Test
    fun retainedRangesJoinWithoutGapsForEveryMode() {
        for (mode in VoiceCleanupWindowMode.entries) {
            val dsp = MossFormer2Dsp(VoiceCleanupWindowPlan.fixed(mode))
            val first = dsp.retainedRange(0)
            val next = dsp.retainedRange(1)

            assertEquals(dsp.segmentSamples - dsp.edgeDiscardSamples, first.count())
            assertEquals(dsp.strideSamples, next.count())
            assertEquals(dsp.edgeDiscardSamples, next.first)
            assertEquals(dsp.segmentSamples - dsp.edgeDiscardSamples - 1, next.last)
        }
    }

    @Test
    fun validMaskFramesExcludeZeroPaddingAndDiscardedEdges() {
        val dsp = MossFormer2Dsp(VoiceCleanupWindowPlan.fixed(VoiceCleanupWindowMode.COMPATIBILITY_4S))
        val range = dsp.validMaskFrameRange(segmentIndex = 1, availableSamples = 100_000)

        assertFalse(range.isEmpty())
        for (frame in range) {
            val start = frame * MossFormer2Dsp.HOP_SIZE
            val center = start + MossFormer2Dsp.FFT_SIZE / 2
            assertTrue(start + MossFormer2Dsp.FFT_SIZE <= 100_000)
            assertTrue(center in dsp.retainedRange(1))
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
    fun silenceUsesKaldiFloat32EpsilonBeforeLogWithoutDither() {
        val dsp = MossFormer2Dsp(VoiceCleanupWindowMode.COMPATIBILITY_4S)
        val features = dsp.buildFeatures(
            FloatArray(dsp.segmentSamples),
            ditherMode = VoiceCleanupDitherMode.OFF,
        )
        val expectedLogFloor = ln(Math.ulp(1.0f).toDouble()).toFloat()

        for (mel in 0 until MossFormer2Dsp.MEL_BINS) {
            assertEquals(expectedLogFloor, features[mel], 1e-5f)
        }
        for (feature in MossFormer2Dsp.MEL_BINS until MossFormer2Dsp.FEATURES) {
            assertEquals(0f, features[feature], 1e-6f)
        }
    }

    @Test
    fun frontendMatchesTorchaudioKaldiGoldenWithoutDither() {
        val dsp = MossFormer2Dsp(VoiceCleanupWindowMode.COMPATIBILITY_4S)
        val samples = FloatArray(dsp.segmentSamples) { index ->
            val position = index.toDouble()
            (
                0.31 * sin(2.0 * PI * 440.0 * position / MossFormer2Dsp.SAMPLE_RATE) +
                    0.17 * sin(2.0 * PI * 1_234.0 * position / MossFormer2Dsp.SAMPLE_RATE) +
                    0.03 * cos(2.0 * PI * 73.0 * position / MossFormer2Dsp.SAMPLE_RATE)
                ).toFloat() * 32_768f
        }
        val features = dsp.buildFeatures(samples, VoiceCleanupDitherMode.OFF)
        val expectedFrames = mapOf(
            0 to floatArrayOf(19.418184f, 18.368071f, 12.424656f, 13.759562f, 15.406802f, 16.205782f, 23.555054f, 25.691675f, 22.337364f, 15.623430f),
            1 to floatArrayOf(19.357550f, 18.430182f, 13.344843f, 14.439819f, 15.488293f, 16.066973f, 23.550610f, 25.692795f, 22.327763f, 15.199062f),
            127 to floatArrayOf(19.382671f, 18.410883f, 13.927150f, 14.903815f, 15.685616f, 16.064598f, 23.548656f, 25.693327f, 22.322948f, 14.863338f),
            495 to floatArrayOf(19.342030f, 18.458212f, 12.566382f, 13.198477f, 15.329110f, 16.257376f, 23.556709f, 25.691374f, 22.340113f, 15.696876f),
        )

        for ((frame, expected) in expectedFrames) {
            val offset = frame * MossFormer2Dsp.FEATURES
            val actual = features.copyOfRange(offset, offset + expected.size)
            assertArrayEquals("frame=$frame", expected, actual, 2.0e-4f)
        }
    }

    @Test
    fun ditherIsDeterministicAndChangesLowEnergyFeatures() {
        val dsp = MossFormer2Dsp(VoiceCleanupWindowMode.COMPATIBILITY_4S)
        val silence = FloatArray(dsp.segmentSamples)
        val first = dsp.buildFeatures(
            silence,
            ditherMode = VoiceCleanupDitherMode.KALDI_1_LSB,
            ditherSeed = 1234L,
        ).copyOf()
        val second = dsp.buildFeatures(
            silence,
            ditherMode = VoiceCleanupDitherMode.KALDI_1_LSB,
            ditherSeed = 1234L,
        ).copyOf()
        val without = dsp.buildFeatures(silence, VoiceCleanupDitherMode.OFF).copyOf()

        assertArrayEquals(first, second, 0f)
        assertTrue(first.indices.any { abs(first[it] - without[it]) > 1e-5f })
        assertTrue(first.all(Float::isFinite))
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
