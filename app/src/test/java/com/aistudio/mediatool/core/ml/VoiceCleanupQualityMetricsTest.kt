package com.aistudio.mediatool.core.ml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceCleanupQualityMetricsTest {
    @Test
    fun frontendComparisonReportsDifferenceMagnitude() {
        val metrics = VoiceCleanupFrontendComparisonMetrics.compare(
            floatArrayOf(1f, 2f, 3f, 4f),
            floatArrayOf(1f, 2.5f, 2f, 4f),
        )

        assertEquals(4, metrics.valueCount)
        assertEquals(0.375, metrics.meanAbsoluteDifference, 1e-9)
        assertEquals(1.0, metrics.maximumAbsoluteDifference, 1e-9)
        assertEquals(50.0, metrics.changedPercent, 1e-9)
    }

    @Test
    fun seamMetricsReportJumpAndRmsChange() {
        val accumulator = VoiceCleanupSeamAccumulator(windowSamples = 4)
        accumulator.addSegment(floatArrayOf(0.1f, 0.1f, 0.1f, 0.1f), 0, 4)
        accumulator.addSegment(floatArrayOf(0.8f, 0.8f, 0.8f, 0.8f), 0, 4)

        val metrics = accumulator.snapshot()

        assertEquals(1, metrics.seamCount)
        assertEquals(0.7, metrics.maximumAbsoluteJump, 1e-6)
        assertTrue(metrics.maximumAbsoluteRmsDeltaDb > 17.0)
        assertTrue(metrics.maximumRelativeJumpDb != null)
    }

    @Test
    fun continuousSegmentsHaveZeroJumpAndRmsDelta() {
        val accumulator = VoiceCleanupSeamAccumulator(windowSamples = 4)
        accumulator.addSegment(floatArrayOf(0.2f, 0.2f, 0.2f, 0.2f), 0, 4)
        accumulator.addSegment(floatArrayOf(0.2f, 0.2f, 0.2f, 0.2f), 0, 4)

        val metrics = accumulator.snapshot()

        assertEquals(1, metrics.seamCount)
        assertEquals(0.0, metrics.maximumAbsoluteJump, 1e-9)
        assertEquals(0.0, metrics.maximumAbsoluteRmsDeltaDb, 1e-9)
    }
}
