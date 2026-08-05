package com.aistudio.mediatool.core.spatial

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpatialHardeningTest {
    @Test
    fun listeningRoomTwentyMetersIsUniformlyFittedInsideRoom() {
        val requested = SpatialAudioConfig(
            roomPreset = SpatialRoomPreset.LISTENING_ROOM,
            trajectory = SpatialTrajectory.HORIZONTAL_CIRCLE,
            startDistanceM = 20f,
            endDistanceM = 20f,
            reverbWet = 0.3f,
        )
        val result = SpatialRoomTrajectoryPolicy.fit(requested)

        assertTrue(result.adjusted)
        assertTrue(result.scale < 0.2f)
        assertEquals(result.config.startDistanceM, result.config.endDistanceM, 1e-5f)
        assertTrajectoryInside(result.config)
    }

    @Test
    fun theaterFrontBackKeepsShapeInsideRoom() {
        val requested = SpatialAudioConfig(
            roomPreset = SpatialRoomPreset.THEATER,
            trajectory = SpatialTrajectory.FRONT_BACK,
            startAzimuthDeg = 0f,
            endAzimuthDeg = 180f,
            startElevationDeg = 0f,
            endElevationDeg = 45f,
            startDistanceM = 20f,
            endDistanceM = 20f,
            reverbWet = 0.4f,
        )
        val result = SpatialRoomTrajectoryPolicy.fit(requested)

        assertTrue(result.adjusted)
        assertTrajectoryInside(result.config)
    }

    @Test
    fun outdoorDoesNotChangeRequestedDistance() {
        val requested = SpatialAudioConfig(
            roomPreset = SpatialRoomPreset.OUTDOOR,
            startDistanceM = 20f,
            endDistanceM = 20f,
            reverbWet = 0.04f,
        )
        val result = SpatialRoomTrajectoryPolicy.fit(requested)

        assertFalse(result.adjusted)
        assertEquals(20f, result.config.endDistanceM, 1e-5f)
    }

    @Test
    fun loudnessParserReadsLastCompletePrettyPrintedBlock() {
        val logs = """
            {"input_i":"-20","input_tp":"-5","input_lra":"2","input_thresh":"-30"}
            unrelated {"message":"brace } inside string"}
            {
              "input_i" : "-13.98",
              "input_tp" : "-1.09",
              "input_lra" : "5.1",
              "input_thresh" : "-24"
            }
        """.trimIndent()

        val reading = SpatialLoudnessParser.parse(logs)!!
        assertEquals(-13.98, reading.integratedLufs!!, 1e-6)
        assertEquals(-1.09, reading.truePeakDbtp!!, 1e-6)
        assertNull(SpatialLoudnessParser.parse("ffmpeg completed successfully"))
    }

    @Test
    fun tailPoliciesAreExplicit() {
        assertEquals(
            SpatialTailPolicy.PRESERVE_AUDIO_TAIL,
            SpatialTailPolicy.resolve(isVideoMode = false, preview = false),
        )
        assertEquals(
            SpatialTailPolicy.TRUNCATE_PREVIEW_10_SECONDS,
            SpatialTailPolicy.resolve(isVideoMode = true, preview = true),
        )
        assertEquals(
            SpatialTailPolicy.TRUNCATE_TO_VIDEO,
            SpatialTailPolicy.resolve(isVideoMode = true, preview = false),
        )
    }

    @Test
    fun genuineStereoKeepsAtLeastTwentyTwoPercentSideAtFullBlend() {
        val preservation = SpatialStereoPostProcessor.sidePreservation(
            distanceM = 20f,
            spatialBlend = 1f,
            inputDualMono = false,
        )
        assertTrue(preservation >= 0.22f)
        assertEquals(
            0f,
            SpatialStereoPostProcessor.sidePreservation(20f, 1f, inputDualMono = true),
            1e-6f,
        )
    }

    private fun assertTrajectoryInside(config: SpatialAudioConfig) {
        repeat(512) { index ->
            val seconds = config.cycleSeconds * index.toFloat() / 512f
            assertTrue(
                "pose $index escaped ${config.roomPreset}",
                SpatialRoomTrajectoryPolicy.isPoseInside(config, SpatialTrajectoryMath.pose(config, seconds)),
            )
        }
    }
}
