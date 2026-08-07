package com.aistudio.mediatool.core.spatial

import org.junit.Assert.assertEquals
import org.junit.Test

class SpatialStereoModeTest {
    @Test
    fun defaultsToMidSideForMusic() {
        assertEquals(SpatialStereoMode.MID_SIDE, SpatialAudioConfig().stereoMode)
        assertEquals(15f, SpatialAudioConfig().stereoObjectHalfAngleDeg, 0f)
    }

    @Test
    fun exposesThreeStereoExperiments() {
        assertEquals(3, SpatialStereoMode.entries.size)
        assertEquals(3, SpatialStereoMode.entries.map { it.label }.distinct().size)
    }

    @Test
    fun cycleSliderRunsDirectlyFromThreeToThirtySeconds() {
        val shortCycle = SpatialAudioConfig().withFriendlyCycle(0f)
        val longCycle = SpatialAudioConfig().withFriendlyCycle(1f)

        assertEquals(3f, shortCycle.cycleSeconds, 0.001f)
        assertEquals(30f, longCycle.cycleSeconds, 0.001f)
        assertEquals(0f, shortCycle.friendlyCyclePosition(), 0.001f)
        assertEquals(1f, longCycle.friendlyCyclePosition(), 0.001f)
    }

    @Test
    fun stereoObjectAngleIsBounded() {
        assertEquals(
            45f,
            SpatialAudioConfig(stereoObjectHalfAngleDeg = 100f).normalized().stereoObjectHalfAngleDeg,
            0f,
        )
        assertEquals(
            0f,
            SpatialAudioConfig(stereoObjectHalfAngleDeg = -10f).normalized().stereoObjectHalfAngleDeg,
            0f,
        )
    }
}
