package com.aistudio.mediatool.core.spatial

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpatialRoomPresenceV3Test {
    @Test
    fun maximumRoomPresenceIsAudibleButBounded() {
        val minimums = mapOf(
            SpatialRoomPreset.DRY to 0.12f,
            SpatialRoomPreset.STUDIO to 0.27f,
            SpatialRoomPreset.LISTENING_ROOM to 0.38f,
            SpatialRoomPreset.THEATER to 0.48f,
            SpatialRoomPreset.WAREHOUSE to 0.50f,
            SpatialRoomPreset.OUTDOOR to 0.03f,
        )
        minimums.forEach { (preset, expected) ->
            val value = SpatialAudioConfig()
                .withRoomPreset(preset)
                .withFriendlyReflection(1f)
            assertEquals(expected, value.reverbWet, 1e-6f)
            assertTrue(value.reverbWet <= 0.5f)
        }
    }

    @Test
    fun frontBackPresetStaysOnTheHorizontalPlane() {
        val config = SpatialAudioConfig()
            .withFriendlyTrajectory(SpatialTrajectory.FRONT_BACK)
        assertEquals(0f, config.startElevationDeg, 1e-6f)
        assertEquals(0f, config.endElevationDeg, 1e-6f)

        val rear = SpatialTrajectoryMath.pose(config, config.cycleSeconds * 0.5f)
        assertTrue(kotlin.math.abs(rear.y) < 1e-4f)
        assertTrue(rear.z > 0.99f)
    }

    @Test
    fun midpointNoLongerFeelsAlmostDry() {
        val value = SpatialAudioConfig()
            .withRoomPreset(SpatialRoomPreset.LISTENING_ROOM)
            .withFriendlyReflection(0.5f)
        val ratio = value.reverbWet / SpatialRoomPreset.LISTENING_ROOM.acoustics.maxReflectionWet
        assertTrue(ratio > 0.40f)
        assertTrue(ratio < 0.50f)
        assertEquals(0.5f, value.friendlyReflectionPosition(), 1e-4f)
    }
}
