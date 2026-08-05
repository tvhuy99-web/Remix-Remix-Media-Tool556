package com.aistudio.mediatool.core.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaEffectRulesTest {
    @Test
    fun unsupportedFiltersNeverReceiveTimelineExpressions() {
        assertTrue(MediaEffectRules.supportsTimeline(MediaAudioEffect.DENOISE))
        assertTrue(MediaEffectRules.supportsTimeline(MediaAudioEffect.NOISE_GATE))
        assertTrue(MediaEffectRules.supportsTimeline(MediaAudioEffect.EQUALIZER))
        assertFalse(MediaEffectRules.supportsTimeline(MediaAudioEffect.PAN))
        assertFalse(MediaEffectRules.supportsTimeline(MediaAudioEffect.COMPRESSOR))
    }

    @Test
    fun speedPitchNormalizesInputSampleRateBeforePitchShift() {
        val filters = MediaEffectRules.speedPitchFilters(speed = 1f, pitch = 1f, isVideoMode = false)
        assertEquals(listOf("aresample=44100", "asetrate=44100", "aresample=44100"), filters)
    }

    @Test
    fun videoModeIgnoresHiddenSpeedSettingButPreservesPitchCompensation() {
        val filters = MediaEffectRules.speedPitchFilters(speed = 2f, pitch = 2f, isVideoMode = true)
        assertEquals("aresample=44100", filters[0])
        assertEquals("asetrate=88200", filters[1])
        assertEquals("aresample=44100", filters[2])
        assertEquals("atempo=0.50000", filters[3])
    }

    @Test
    fun denoiseUsesNoiseReductionParameterAndClampsToFfmpegRange() {
        assertEquals("afftdn=nr=0.01", MediaEffectRules.denoiseFilter(-5f))
        assertEquals(
            "afftdn=nr=97.00:enable='between(t,0,1)'",
            MediaEffectRules.denoiseFilter(120f, ":enable='between(t,0,1)'"),
        )
    }

    @Test
    fun zeroWetReverbIsABypass() {
        assertNull(MediaEffectRules.reverbFilter(roomSize = 0.5f, damping = 0.5f, wet = 0f))
        assertTrue(
            MediaEffectRules.reverbFilter(roomSize = 0.5f, damping = 1f, wet = 0.3f)!!
                .contains("0.0150"),
        )
    }

    @Test
    fun silenceRemovalIsDisabledOnlyForVideoKeepPictureMode() {
        assertFalse(MediaEffectRules.supportsSilenceRemoval(isVideoMode = true, modeIndex = 0))
        assertTrue(MediaEffectRules.supportsSilenceRemoval(isVideoMode = true, modeIndex = 1))
        assertTrue(MediaEffectRules.supportsSilenceRemoval(isVideoMode = false, modeIndex = 0))
    }

    @Test
    fun loudnessNormalizationIsAppendedAfterExistingEffects() {
        val filters = mutableListOf("equalizer=f=910:g=4", "acompressor=ratio=4")
        MediaEffectRules.appendFinalLoudnessFilters(filters, enabled = true, targetPeakPercent = 95f)
        assertEquals("equalizer=f=910:g=4", filters[0])
        assertEquals("acompressor=ratio=4", filters[1])
        assertTrue(filters[2].startsWith("loudnorm=I=-16:LRA=11:TP="))
        assertEquals("aresample=48000", filters[3])
    }
}
