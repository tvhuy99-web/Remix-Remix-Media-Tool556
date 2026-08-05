package com.aistudio.mediatool.core.spatial

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpatialRoomPresetTest {
    @Test
    fun exposesStableRoomIdsForFutureNativeSceneBridge() {
        assertEquals(listOf(0, 1, 2, 3, 4, 5), SpatialRoomPreset.entries.map { it.nativeId })
        assertEquals(SpatialRoomPreset.entries.size, SpatialRoomPreset.entries.map { it.label }.distinct().size)
    }

    @Test
    fun enclosedRoomsProduceBoundedPhysicalAcoustics() {
        SpatialRoomPreset.entries
            .filterNot { it == SpatialRoomPreset.OUTDOOR }
            .forEach { preset ->
                val room = preset.acoustics
                assertTrue("$preset dimensions", room.dimensions != null)
                assertTrue("$preset volume", room.dimensions!!.volumeM3 > 0f)
                assertTrue("$preset low RT60", room.rt60Seconds.low in 0.1f..6f)
                assertTrue("$preset mid RT60", room.rt60Seconds.mid in 0.1f..6f)
                assertTrue("$preset high RT60", room.rt60Seconds.high in 0.1f..6f)
                assertTrue("$preset EQ low", room.reverbEq.low in 0.05f..1f)
                assertTrue("$preset EQ mid", room.reverbEq.mid in 0.05f..1f)
                assertTrue("$preset EQ high", room.reverbEq.high in 0.05f..1f)
                assertTrue("$preset scattering", room.averageScattering in 0f..1f)
            }
    }

    @Test
    fun largerHarderRoomsHaveLongerDecayThanDryRoom() {
        val dry = SpatialRoomPreset.DRY.acoustics.rt60Seconds
        val theater = SpatialRoomPreset.THEATER.acoustics.rt60Seconds
        val warehouse = SpatialRoomPreset.WAREHOUSE.acoustics.rt60Seconds

        assertTrue(theater.low > dry.low)
        assertTrue(theater.mid > dry.mid)
        assertTrue(warehouse.low >= theater.low)
        assertTrue(warehouse.mid >= theater.mid)
    }

    @Test
    fun outdoorPresetDoesNotPretendToHaveAnEnclosingRoom() {
        val outdoor = SpatialRoomPreset.OUTDOOR.acoustics
        assertNull(outdoor.dimensions)
        assertTrue(outdoor.outdoor)
        assertTrue(outdoor.maxReflectionWet <= 0.05f)
        assertEquals(0f, outdoor.firstReflectionMs, 0f)
    }

    @Test
    fun switchingRoomPreservesFriendlyReflectionIntent() {
        val original = SpatialAudioConfig().withFriendlyReflection(0.72f)
        val theater = original.withRoomPreset(SpatialRoomPreset.THEATER)
        val studio = theater.withRoomPreset(SpatialRoomPreset.STUDIO)

        assertEquals(0.72f, theater.friendlyReflectionPosition(), 1e-4f)
        assertEquals(0.72f, studio.friendlyReflectionPosition(), 1e-4f)
        assertTrue(theater.reverbWet <= SpatialRoomPreset.THEATER.acoustics.maxReflectionWet)
        assertTrue(studio.reverbWet <= SpatialRoomPreset.STUDIO.acoustics.maxReflectionWet)
    }

    @Test
    fun friendlyReflectionNeverBecomesWetOnly() {
        SpatialRoomPreset.entries.forEach { preset ->
            val value = SpatialAudioConfig()
                .withRoomPreset(preset)
                .withFriendlyReflection(1f)
            assertEquals(preset.acoustics.maxReflectionWet, value.reverbWet, 1e-5f)
            assertTrue("$preset wet=${value.reverbWet}", value.reverbWet < 0.5f)
        }
    }

    @Test
    fun applyingPresetUpdatesTechnicalRendererParameters() {
        val value = SpatialAudioConfig().withRoomPreset(SpatialRoomPreset.THEATER)
        val room = SpatialRoomPreset.THEATER.acoustics

        assertEquals(room.distanceRolloff, value.distanceRolloff, 0f)
        assertEquals(room.airAbsorption, value.airAbsorption, 0f)
        assertEquals(room.rt60Seconds.low, value.reverbRt60Low, 0f)
        assertEquals(room.rt60Seconds.mid, value.reverbRt60Mid, 0f)
        assertEquals(room.rt60Seconds.high, value.reverbRt60High, 0f)
        assertEquals(room.reverbEq.low, value.reverbEqLow, 0f)
        assertEquals(room.reverbEq.mid, value.reverbEqMid, 0f)
        assertEquals(room.reverbEq.high, value.reverbEqHigh, 0f)
    }
}
