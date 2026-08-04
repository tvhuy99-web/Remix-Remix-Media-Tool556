package com.aistudio.mediatool.core.ml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceCleanupConfigTest {
    @Test
    fun defaultsKeepComparisonFairAndProtectClipping() {
        val config = VoiceCleanupConfig()

        assertEquals(VoiceCleanupLoudnessMode.MATCH_SOURCE, config.loudnessMode)
        assertEquals(0f, config.outputGainDb)
        assertTrue(config.limiterEnabled)
        assertEquals(-1f, config.limiterCeilingDb)
    }

    @Test
    fun rejectsUnsafeLimiterCeiling() {
        val failed = runCatching {
            VoiceCleanupConfig(limiterCeilingDb = 0f)
        }.isFailure

        assertTrue(failed)
    }
}
