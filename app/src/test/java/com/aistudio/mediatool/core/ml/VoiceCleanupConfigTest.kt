package com.aistudio.mediatool.core.ml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceCleanupConfigTest {
    @Test
    fun defaultsUseTenSecondsBalancedStrengthUpstreamDitherAndProtectClipping() {
        val config = VoiceCleanupConfig()

        assertEquals(VoiceCleanupWindowMode.BALANCED_10S, config.windowMode)
        assertEquals(65, config.cleanupStrengthPercent)
        assertEquals(0.65f, config.cleanupStrength, 1e-6f)
        assertEquals(VoiceCleanupDitherMode.KALDI_1_LSB, config.ditherMode)
        assertEquals(VoiceCleanupLoudnessMode.MATCH_SOURCE, config.loudnessMode)
        assertEquals(0f, config.outputGainDb)
        assertTrue(config.limiterEnabled)
        assertEquals(-1f, config.limiterCeilingDb)
    }

    @Test
    fun unknownAndRemovedWindowModesFallBackSafely() {
        assertEquals(
            VoiceCleanupWindowMode.BALANCED_10S,
            VoiceCleanupWindowMode.fromName("UNKNOWN"),
        )
        assertEquals(
            VoiceCleanupWindowMode.BALANCED_10S,
            VoiceCleanupWindowMode.fromName(null),
        )
        assertEquals(
            VoiceCleanupWindowMode.BALANCED_10S,
            VoiceCleanupWindowMode.fromName("COMPATIBILITY_4S"),
        )
        assertEquals(
            VoiceCleanupWindowMode.QUALITY_20S,
            VoiceCleanupWindowMode.fromName("MAXIMUM_15S"),
        )
    }

    @Test
    fun modesAreTenTwentyAndThirtySecondsWithIncreasingRam() {
        val modes = VoiceCleanupWindowMode.entries
        val mib = 1024L * 1024L

        assertEquals(listOf(10, 20, 30), modes.map(VoiceCleanupWindowMode::seconds))
        assertEquals(listOf(1_246, 2_496, 3_746), modes.map(VoiceCleanupWindowMode::frames))
        assertEquals(listOf(1_024L, 1_536L, 2_048L), modes.map { it.minimumAvailableRamBytes / mib })
        assertTrue(modes.zipWithNext().all { (left, right) -> left.segmentSamples < right.segmentSamples })
        assertTrue(modes.zipWithNext().all { (left, right) -> left.frames < right.frames })
        assertTrue(
            modes.zipWithNext().all { (left, right) ->
                left.minimumAvailableRamBytes < right.minimumAvailableRamBytes
            },
        )
        assertEquals(30 * MossFormer2Dsp.SAMPLE_RATE, VoiceCleanupWindowMode.MAXIMUM_30S.onePassLimitSamples)
    }

    @Test
    fun rejectsInvalidStrengthAndUnsafeLimiterCeiling() {
        assertTrue(runCatching { VoiceCleanupConfig(cleanupStrengthPercent = 0) }.isFailure)
        assertTrue(runCatching { VoiceCleanupConfig(cleanupStrengthPercent = 101) }.isFailure)
        assertTrue(runCatching { VoiceCleanupConfig(limiterCeilingDb = 0f) }.isFailure)
    }
}
