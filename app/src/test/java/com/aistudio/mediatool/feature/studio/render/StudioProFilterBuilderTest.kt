package com.aistudio.mediatool.feature.studio.render

import com.aistudio.mediatool.feature.studio.domain.StudioVocalFxSettings
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StudioProFilterBuilderTest {
    @Test
    fun enabledChain_containsEqCompressionReverbAndLatencyCompensatedLimiter() {
        val filter = StudioProFilterBuilder.build(
            StudioVocalFxSettings(
                enabled = true,
                reverbWet = 0.2f,
                reverbDecay = 0.3f,
            ),
        )

        assertTrue(filter.contains("highpass="))
        assertTrue(filter.contains("equalizer="))
        assertTrue(filter.contains("acompressor="))
        assertTrue(filter.contains("aecho="))
        assertTrue(filter.contains("alimiter="))
        assertTrue(filter.contains("latency=1"))
        assertFalse(filter.contains("NaN"))
        assertFalse(filter.contains("Infinity"))
    }

    @Test
    fun disabledChain_isHardBypass() {
        val filter = StudioProFilterBuilder.build(StudioVocalFxSettings(enabled = false))
        assertTrue(filter == "anull")
    }

    @Test
    fun extremeValues_areClampedBeforeFormatting() {
        val filter = StudioProFilterBuilder.build(
            StudioVocalFxSettings(
                highPassHz = -100f,
                lowGainDb = 99f,
                midGainDb = -99f,
                highGainDb = 99f,
                compressorThresholdDb = -999f,
                compressorRatio = 100f,
                compressorAttackMs = -1f,
                compressorReleaseMs = 99_999f,
                compressorMakeupDb = 99f,
                reverbWet = 10f,
                reverbDelayMs = 9_999f,
                reverbDecay = 10f,
            ),
        )

        assertTrue(filter.contains("highpass=f=20.000"))
        assertTrue(filter.contains("ratio=20.000"))
        assertFalse(filter.contains("99.000dB"))
        assertFalse(filter.contains("NaN"))
    }
}
