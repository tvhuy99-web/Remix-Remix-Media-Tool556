package com.aistudio.mediatool.core.ml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceCleanupMetricsTest {
    @Test
    fun parsesLoudnessAndVolumeSummaries() {
        val logs = """
            [Parsed_volumedetect_0] mean_volume: -22.4 dB
            [Parsed_volumedetect_0] max_volume: -3.1 dB
            {
                "input_i" : "-20.5",
                "input_tp" : "-1.4"
            }
        """.trimIndent()

        val metrics = VoiceCleanupMetricsParser.parse(logs)

        assertEquals(-20.5, metrics.integratedLufs!!, 1e-6)
        assertEquals(-22.4, metrics.rmsDbfs!!, 1e-6)
        assertEquals(-3.1, metrics.samplePeakDbfs!!, 1e-6)
        assertEquals(-1.4, metrics.truePeakDbfs!!, 1e-6)
    }

    @Test
    fun maskAccumulatorExposesSuppressionAndIdentityRates() {
        val accumulator = VoiceCleanupMaskAccumulator()
        accumulator.add(floatArrayOf(0.2f, 0.4f, 0.8f, 0.99f, 1.0f, 1.2f))

        val metrics = accumulator.snapshot()

        assertEquals(6L, metrics.valueCount)
        assertEquals(0.2, metrics.minimum, 1e-5)
        assertEquals(1.2, metrics.maximum, 1e-5)
        assertEquals(2.0 / 6.0 * 100.0, metrics.belowPointFivePercent, 1e-6)
        assertEquals(3.0 / 6.0 * 100.0, metrics.belowPointNinePercent, 1e-6)
        assertEquals(2.0 / 6.0 * 100.0, metrics.nearUnityPercent, 1e-6)
        assertEquals(1.0 / 6.0 * 100.0, metrics.outsideZeroOnePercent, 1e-6)
        assertTrue(metrics.p10 <= metrics.p50)
        assertTrue(metrics.p50 <= metrics.p90)
    }

    @Test
    fun frameSelectionExcludesPaddingValues() {
        val bins = 3
        val mask = floatArrayOf(
            0.1f, 0.2f, 0.3f,
            0.4f, 0.5f, 0.6f,
            9f, 9f, 9f,
            9f, 9f, 9f,
        )
        val accumulator = VoiceCleanupMaskAccumulator()
        accumulator.addFrames(mask, 0..1, bins)

        val metrics = accumulator.snapshot()

        assertEquals(2L, metrics.frameCount)
        assertEquals(6L, metrics.valueCount)
        assertEquals(0.6, metrics.maximum, 1e-5)
    }
}
