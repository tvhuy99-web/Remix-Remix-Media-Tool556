package com.aistudio.mediatool.core.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioMathTest {
    @Test
    fun panIsClampedAndBalanced() {
        assertEquals(AudioMath.StereoGain(1f, 0f), AudioMath.stereoPan(-20))
        assertEquals(AudioMath.StereoGain(1f, 1f), AudioMath.stereoPan(50))
        assertEquals(AudioMath.StereoGain(0f, 1f), AudioMath.stereoPan(120))
    }

    @Test
    fun fadeNeverConsumesMoreThanHalfSegment() {
        assertEquals(2.5, AudioMath.clampedFadeDuration(10.0, 5.0), 0.0001)
        assertEquals(1.0, AudioMath.clampedFadeDuration(1.0, 5.0), 0.0001)
        assertEquals(0.0, AudioMath.clampedFadeDuration(1.0, 0.0), 0.0001)
    }

    @Test
    fun progressIsBoundedBeforeCompletion() {
        assertEquals(0, AudioMath.progressPercent(10, 0))
        assertEquals(50, AudioMath.progressPercent(500, 1000))
        assertEquals(99, AudioMath.progressPercent(2000, 1000))
    }

    @Test
    fun convertsPeakPercentToDbCeiling() {
        assertEquals(0.0, AudioMath.truePeakDbFromPercent(100f), 0.0001)
        assertEquals(-6.0206, AudioMath.truePeakDbFromPercent(50f), 0.001)
    }

    @Test
    fun fadeRequiresEverySourceDuration() {
        assertTrue(AudioMath.canApplyGlobalFade(3.0, listOf(1_000L, 2_000L)))
        assertFalse(AudioMath.canApplyGlobalFade(3.0, listOf(1_000L, null)))
        assertFalse(AudioMath.canApplyGlobalFade(0.0, listOf(1_000L)))
    }
}
