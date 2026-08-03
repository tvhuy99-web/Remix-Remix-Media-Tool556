package com.aistudio.mediatool.core.ml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceCleanupConfigTest {
    @Test
    fun strongerPresetAllowsLessNoisySignalBackIntoOutput() {
        val natural = VoiceCleanupStrength.NATURAL.noisyBlend
        val balanced = VoiceCleanupStrength.BALANCED.noisyBlend
        val strong = VoiceCleanupStrength.STRONG.noisyBlend

        assertTrue(natural > balanced)
        assertTrue(balanced > strong)
        assertTrue(strong > 0f)
    }

    @Test
    fun unknownStrengthFallsBackToBalanced() {
        assertEquals(VoiceCleanupStrength.BALANCED, VoiceCleanupStrength.fromName("unknown"))
        assertEquals(VoiceCleanupStrength.STRONG, VoiceCleanupStrength.fromName("STRONG"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun unsupportedLoudnessTargetIsRejected() {
        VoiceCleanupConfig(targetLufs = -12)
    }
}
