package com.aistudio.mediatool.core.spatial

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpatialProductionTuningSourceTest {
    private val source by lazy {
        File("src/main/cpp/room_aware_spatial_jni.cpp").readText()
    }

    @Test
    fun kotlinAndNativeRoomPayloadVersionsStayAligned() {
        assertEquals(2, RoomReflectionNativeSpec.PAYLOAD_VERSION)
        assertTrue(
            source.contains(
                "constexpr int kPayloadVersion = ${RoomReflectionNativeSpec.PAYLOAD_VERSION};",
            ),
        )
    }

    @Test
    fun fallbackUsesDiffusePairInsteadOfFollowingTheMovingSource() {
        assertTrue(source.contains("applyFallbackDiffuse"))
        assertTrue(source.contains("fallbackWetBinauralRight"))
        assertTrue(source.contains("kFallbackDiffuseAzimuthDeg = 58.0f"))
        assertTrue(!source.contains("wetParams.direction = pose.direction"))
    }

    @Test
    fun wetBusIsDarkenedAndGateIsSmoothed() {
        assertTrue(source.contains("kReflectionLowpassHz = 7200.0f"))
        assertTrue(source.contains("kFallbackHighEqCeiling = 0.72f"))
        assertTrue(source.contains("reflectionGateState"))
        assertTrue(source.contains("blockSeconds / 0.180f"))
    }

    @Test
    fun rearCueAndRoomPresenceHaveProductionHeadroom() {
        assertTrue(source.contains("kRearDirectLowpassHz = 6800.0f"))
        assertTrue(source.contains("kRearDirectAttenuation = 0.32f"))
        assertTrue(source.contains("kRearWetBoost = 0.65f"))
        assertTrue(source.contains("kReflectionHeadroomDb = -3.0f"))
    }
}
