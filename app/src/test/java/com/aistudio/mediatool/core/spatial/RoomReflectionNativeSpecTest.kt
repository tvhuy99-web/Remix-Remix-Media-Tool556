package com.aistudio.mediatool.core.spatial

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RoomReflectionNativeSpecTest {
    @Test
    fun payloadShapeAndVersionStayStable() {
        SpatialRoomPreset.entries.forEach { preset ->
            val value = RoomReflectionNativeSpec.balanced(preset)
            assertEquals(RoomReflectionNativeSpec.INTEGER_PAYLOAD_SIZE, value.integerPayload().size)
            assertEquals(RoomReflectionNativeSpec.FLOAT_PAYLOAD_SIZE, value.floatPayload().size)
            assertEquals(RoomReflectionNativeSpec.PAYLOAD_VERSION, value.integerPayload()[0])
            assertEquals(RoomReflectionNativeSpec.PAYLOAD_VERSION.toFloat(), value.floatPayload()[0], 0f)
        }
    }

    @Test
    fun balancedQualityIsDeterministic() {
        val value = RoomReflectionNativeSpec.balanced(SpatialRoomPreset.LISTENING_ROOM)
        assertArrayEquals(
            intArrayOf(1, 1, 2, 4_096, 16, 1, 2),
            value.integerPayload(),
        )
        assertEquals(2.0f, value.durationSeconds, 0f)
        assertEquals(0.12f, value.hybridTransitionSeconds, 0f)
        assertEquals(0.25f, value.hybridOverlapPercent, 0f)
        assertEquals(0.25f, value.updateSeconds, 0f)
    }

    @Test
    fun enclosedRoomsCarryGeometryAndMaterials() {
        SpatialRoomPreset.entries
            .filterNot { it == SpatialRoomPreset.OUTDOOR }
            .forEach { preset ->
                val value = RoomReflectionNativeSpec.balanced(preset)
                val floats = value.floatPayload()
                assertTrue("$preset enabled", value.enabled)
                assertTrue("$preset width", floats[1] > 0f)
                assertTrue("$preset depth", floats[2] > 0f)
                assertTrue("$preset height", floats[3] > 0f)
                listOf(4, 5, 6, 8, 9, 10, 12, 13, 14).forEach { index ->
                    assertTrue("$preset absorption[$index]", floats[index] in 0f..1f)
                }
                listOf(7, 11, 15).forEach { index ->
                    assertTrue("$preset scattering[$index]", floats[index] in 0f..1f)
                }
            }
    }

    @Test
    fun outdoorDisablesGeometrySimulationButKeepsStablePayload() {
        val value = RoomReflectionNativeSpec.balanced(SpatialRoomPreset.OUTDOOR)
        val ints = value.integerPayload()
        val floats = value.floatPayload()
        assertFalse(value.enabled)
        assertEquals(0, ints[1])
        assertEquals(SpatialRoomPreset.OUTDOOR.nativeId, ints[2])
        assertEquals(0f, floats[1], 0f)
        assertEquals(0f, floats[2], 0f)
        assertEquals(0f, floats[3], 0f)
        assertTrue(floats.drop(4).take(12).all { it == 0f })
    }

    @Test
    fun nativeQualityValuesStayInsideMobileGuardrails() {
        SpatialRoomPreset.entries.forEach { preset ->
            val value = RoomReflectionNativeSpec.balanced(preset)
            assertTrue(value.rays in 256..32_768)
            assertTrue(value.bounces in 1..64)
            assertTrue(value.order in 0..2)
            assertTrue(value.threads in 1..4)
            assertTrue(value.durationSeconds in 0.25f..4f)
            assertTrue(value.updateSeconds in 0.05f..2f)
        }
    }
}
