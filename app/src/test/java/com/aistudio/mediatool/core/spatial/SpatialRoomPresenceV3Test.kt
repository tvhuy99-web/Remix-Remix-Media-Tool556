package com.aistudio.mediatool.core.spatial

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpatialRoomPresenceV3Test {
    @Test
    fun maximumRoomPresenceIsAudibleButBounded() {
        val minimums = mapOf(
            SpatialRoomPreset.DRY to 0.12f,
            SpatialRoomPreset.STUDIO to 0.24f,
            SpatialRoomPreset.LISTENING_ROOM to 0.34f,
            SpatialRoomPreset.THEATER to 0.43f,
            SpatialRoomPreset.WAREHOUSE to 0.42f,
            SpatialRoomPreset.OUTDOOR to 0.03f,
        )
        minimums.forEach { (preset, expected) ->
            val value = SpatialAudioConfig()
                .withRoomPreset(preset)
                .withFriendlyReflection(1f)
            assertEquals(expected, value.reverbWet, 1e-6f)
            assertTrue(value.reverbWet < 0.5f)
        }
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
