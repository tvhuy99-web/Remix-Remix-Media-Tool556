package com.aistudio.mediatool.core.ml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceCleanupConfigTest {
    @Test
    fun defaultsUseBalancedWindowUpstreamDitherAndProtectClipping() {
        val config = VoiceCleanupConfig()

        assertEquals(VoiceCleanupWindowMode.BALANCED_10S, config.windowMode)
        assertEquals(VoiceCleanupDitherMode.KALDI_1_LSB, config.ditherMode)
        assertEquals(VoiceCleanupLoudnessMode.MATCH_SOURCE, config.loudnessMode)
        assertEquals(0f, config.outputGainDb)
        assertTrue(config.limiterEnabled)
        assertEquals(-1f, config.limiterCeilingDb)
    }

    @Test
    fun unknownWindowModeFallsBackToTenSeconds() {
        assertEquals(
            VoiceCleanupWindowMode.BALANCED_10S,
            VoiceCleanupWindowMode.fromName("UNKNOWN"),
        )
        assertEquals(
            VoiceCleanupWindowMode.BALANCED_10S,
            VoiceCleanupWindowMode.fromName(null),
        )
    }

    @Test
    fun longerWindowsIncreaseContextAndRamRequirement() {
        val modes = VoiceCleanupWindowMode.entries

        assertTrue(modes.zipWithNext().all { (left, right) -> left.segmentSamples < right.segmentSamples })
        assertTrue(modes.zipWithNext().all { (left, right) -> left.frames < right.frames })
        assertTrue(
            modes.zipWithNext().all { (left, right) ->
                left.minimumAvailableRamBytes < right.minimumAvailableRamBytes
            },
        )
        assertEquals(20 * MossFormer2Dsp.SAMPLE_RATE, VoiceCleanupWindowMode.MAXIMUM_15S.onePassLimitSamples)
    }

    @Test
    fun rejectsUnsafeLimiterCeiling() {
        val failed = runCatching {
            VoiceCleanupConfig(limiterCeilingDb = 0f)
        }.isFailure

        assertTrue(failed)
    }
}
