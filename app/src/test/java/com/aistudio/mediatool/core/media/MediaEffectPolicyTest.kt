package com.aistudio.mediatool.core.media

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaEffectPolicyTest {
    @Test
    fun audioConversionNeverAppliesHiddenEffects() {
        assertTrue(MediaEffectPolicy.supportsAudioFilters(isVideoMode = false, modeIndex = 0))
        assertFalse(MediaEffectPolicy.supportsAudioFilters(isVideoMode = false, modeIndex = 1))
    }

    @Test
    fun videoEffectsAndAudioExtractionMayUseVisibleFilters() {
        assertTrue(MediaEffectPolicy.supportsAudioFilters(isVideoMode = true, modeIndex = 0))
        assertTrue(MediaEffectPolicy.supportsAudioFilters(isVideoMode = true, modeIndex = 1))
        assertFalse(MediaEffectPolicy.supportsAudioFilters(isVideoMode = true, modeIndex = 2))
        assertFalse(MediaEffectPolicy.supportsAudioFilters(isVideoMode = true, modeIndex = 4))
    }
}
