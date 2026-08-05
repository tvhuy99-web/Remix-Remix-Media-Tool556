package com.aistudio.mediatool.core.spatial

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpatialAudioConfigTest {
    @Test
    fun defaultsAreTunedForNormalStereoMusic() {
        val value = SpatialAudioConfig()
        assertEquals(1.2f, value.startDistanceM, 0f)
        assertEquals(1.2f, value.endDistanceM, 0f)
        assertEquals(8f, value.cycleSeconds, 0f)
        assertEquals(0.85f, value.spatialBlend, 0f)
        assertEquals(0.12f, value.reverbWet, 0f)
        assertEquals(0.65f, value.distanceRolloff, 0f)
        assertEquals(0.35f, value.airAbsorption, 0f)
    }

    @Test
    fun exposesExactlyTenDistinctTrajectories() {
        assertEquals(10, SpatialTrajectory.entries.size)
        assertEquals(10, SpatialTrajectory.entries.map { it.label }.distinct().size)
    }

    @Test
    fun normalizesEveryUserControlledParameter() {
        val value = SpatialAudioConfig(
            startAzimuthDeg = Float.NaN,
            endAzimuthDeg = 5_000f,
            startElevationDeg = -500f,
            endElevationDeg = 500f,
            startDistanceM = 0f,
            endDistanceM = 1_000f,
            cycleSeconds = 0f,
            spatialBlend = 3f,
            distanceMinM = -1f,
            distanceRolloff = 99f,
            airAbsorption = Float.POSITIVE_INFINITY,
            directivityWeight = -1f,
            directivityPower = 20f,
            sourceYawDeg = 500f,
            reverbWet = -2f,
            reverbRt60Low = 0f,
            reverbRt60Mid = 20f,
            reverbRt60High = Float.NaN,
            outputGainDb = 30f,
            effectStartSeconds = -3f,
            effectEndSeconds = -2f,
            frameSize = 3_000,
        ).normalized()

        assertEquals(-90f, value.startAzimuthDeg, 0f)
        assertEquals(720f, value.endAzimuthDeg, 0f)
        assertEquals(-90f, value.startElevationDeg, 0f)
        assertEquals(90f, value.endElevationDeg, 0f)
        assertEquals(0.2f, value.startDistanceM, 0f)
        assertEquals(100f, value.endDistanceM, 0f)
        assertEquals(0.5f, value.cycleSeconds, 0f)
        assertEquals(1f, value.spatialBlend, 0f)
        assertEquals(0.1f, value.distanceMinM, 0f)
        assertEquals(4f, value.distanceRolloff, 0f)
        assertEquals(0.35f, value.airAbsorption, 0f)
        assertEquals(0f, value.directivityWeight, 0f)
        assertEquals(8f, value.directivityPower, 0f)
        assertEquals(180f, value.sourceYawDeg, 0f)
        assertEquals(0f, value.reverbWet, 0f)
        assertEquals(0.1f, value.reverbRt60Low, 0f)
        assertEquals(10f, value.reverbRt60Mid, 0f)
        assertEquals(0.45f, value.reverbRt60High, 0f)
        assertEquals(6f, value.outputGainDb, 0f)
        assertEquals(0f, value.effectStartSeconds, 0f)
        assertEquals(-1f, value.effectEndSeconds, 0f)
        assertEquals(2_048, value.frameSize)
    }

    @Test
    fun friendlyControlsUseFinalSpeedAndDistanceRanges() {
        val slowNear = SpatialAudioConfig().withFriendlySpeed(0f).withFriendlyDistance(0f)
        val midpoint = SpatialAudioConfig().withFriendlyDistance(0.5f)
        val fastFar = SpatialAudioConfig().withFriendlySpeed(1f).withFriendlyDistance(1f)

        assertEquals(30f, slowNear.cycleSeconds, 0.001f)
        assertEquals(0.8f, slowNear.startDistanceM, 0.001f)
        assertEquals(4f, midpoint.startDistanceM, 0.001f)
        assertEquals(3f, fastFar.cycleSeconds, 0.001f)
        assertEquals(20f, fastFar.startDistanceM, 0.001f)
        assertEquals(fastFar.startDistanceM, fastFar.endDistanceM, 0f)
    }

    @Test
    fun depthTrajectoriesKeepNearAndFarMotionWhenDistanceChanges() {
        val nearFar = SpatialAudioConfig()
            .withFriendlyTrajectory(SpatialTrajectory.NEAR_FAR)
            .withFriendlyDistance(1f)
        assertEquals(9f, nearFar.startDistanceM, 0.001f)
        assertEquals(20f, nearFar.endDistanceM, 0.001f)

        val freeDrift = SpatialAudioConfig()
            .withFriendlyTrajectory(SpatialTrajectory.FREE_DRIFT)
            .withFriendlyDistance(1f)
        assertEquals(12f, freeDrift.startDistanceM, 0.001f)
        assertEquals(20f, freeDrift.endDistanceM, 0.001f)
    }

    @Test
    fun switchingAwayFromDepthTrajectoryRemovesHiddenDistanceAnimation() {
        val nearFar = SpatialAudioConfig()
            .withFriendlyTrajectory(SpatialTrajectory.NEAR_FAR)
        val horizontal = nearFar.withFriendlyTrajectory(SpatialTrajectory.HORIZONTAL_CIRCLE)
        assertEquals(horizontal.startDistanceM, horizontal.endDistanceM, 0f)
        assertEquals(nearFar.endDistanceM, horizontal.startDistanceM, 0f)
    }

    @Test
    fun friendlyTrajectoryAppliesSafePresetForEveryChoice() {
        SpatialTrajectory.entries.forEach { trajectory ->
            val value = SpatialAudioConfig().withFriendlyTrajectory(trajectory)
            assertEquals(trajectory, value.trajectory)
        }

        val figureEight = SpatialAudioConfig().withFriendlyTrajectory(SpatialTrajectory.FIGURE_EIGHT)
        assertEquals(-110f, figureEight.startAzimuthDeg, 0f)
        assertEquals(110f, figureEight.endAzimuthDeg, 0f)
        assertEquals(-30f, figureEight.startElevationDeg, 0f)
        assertEquals(30f, figureEight.endElevationDeg, 0f)

        val linear = SpatialAudioConfig().withFriendlyTrajectory(SpatialTrajectory.LINEAR)
        assertEquals(SpatialMotionMode.ONCE, linear.motionMode)

        val static = SpatialAudioConfig().withFriendlyTrajectory(SpatialTrajectory.STATIC)
        assertEquals(SpatialMotionMode.ONCE, static.motionMode)

        val looping = SpatialTrajectory.entries - setOf(SpatialTrajectory.LINEAR, SpatialTrajectory.STATIC)
        looping.forEach { trajectory ->
            assertEquals(
                SpatialMotionMode.LOOP,
                SpatialAudioConfig().withFriendlyTrajectory(trajectory).motionMode,
            )
        }
    }

    @Test
    fun horizontalCircleVisitsFourCardinalDirections() {
        val config = SpatialAudioConfig(
            trajectory = SpatialTrajectory.HORIZONTAL_CIRCLE,
            startAzimuthDeg = 0f,
            endAzimuthDeg = 360f,
            cycleSeconds = 8f,
        )
        assertPose(SpatialTrajectoryMath.pose(config, 0f), 0f, 0f, -1f)
        assertPose(SpatialTrajectoryMath.pose(config, 2f), 1f, 0f, 0f)
        assertPose(SpatialTrajectoryMath.pose(config, 4f), 0f, 0f, 1f)
        assertPose(SpatialTrajectoryMath.pose(config, 6f), -1f, 0f, 0f)
    }

    @Test
    fun verticalCirclePassesAboveAndBelowListener() {
        val config = SpatialAudioConfig(
            trajectory = SpatialTrajectory.VERTICAL_CIRCLE,
            startAzimuthDeg = 0f,
            cycleSeconds = 8f,
        )
        assertPose(SpatialTrajectoryMath.pose(config, 2f), 0f, 1f, 0f)
        assertPose(SpatialTrajectoryMath.pose(config, 6f), 0f, -1f, 0f)
    }

    @Test
    fun pendulumReversesSmoothlyBetweenLeftAndRight() {
        val config = SpatialAudioConfig().withFriendlyTrajectory(SpatialTrajectory.PENDULUM)
        val left = SpatialTrajectoryMath.pose(config, 0f)
        val right = SpatialTrajectoryMath.pose(config, config.cycleSeconds / 2f)
        val looped = SpatialTrajectoryMath.pose(config, config.cycleSeconds)
        assertTrue(left.x < -0.9f)
        assertTrue(right.x > 0.9f)
        assertPose(looped, left.x, left.y, left.z)
    }

    @Test
    fun frontBackArcReachesBothCardinalDirections() {
        val config = SpatialAudioConfig().withFriendlyTrajectory(SpatialTrajectory.FRONT_BACK)
        val front = SpatialTrajectoryMath.pose(config, 0f)
        val back = SpatialTrajectoryMath.pose(config, config.cycleSeconds / 2f)
        assertPose(front, 0f, 0f, -1f)
        assertPose(back, 0f, 0f, 1f)
    }

    @Test
    fun nearFarReturnsToNearDistanceWithoutASeam() {
        val config = SpatialAudioConfig(
            trajectory = SpatialTrajectory.NEAR_FAR,
            startAzimuthDeg = -90f,
            endAzimuthDeg = 270f,
            startDistanceM = 1f,
            endDistanceM = 8f,
            cycleSeconds = 10f,
        )
        assertEquals(1f, SpatialTrajectoryMath.pose(config, 0f).distanceM, 1e-4f)
        assertEquals(8f, SpatialTrajectoryMath.pose(config, 5f).distanceM, 1e-4f)
        assertEquals(1f, SpatialTrajectoryMath.pose(config, 10f).distanceM, 1e-4f)
    }

    @Test
    fun everyLoopingTrajectoryIsContinuousAtCycleBoundary() {
        val looping = SpatialTrajectory.entries - setOf(SpatialTrajectory.LINEAR, SpatialTrajectory.STATIC)
        looping.forEach { trajectory ->
            val config = SpatialAudioConfig().withFriendlyTrajectory(trajectory)
            val start = SpatialTrajectoryMath.pose(config, 0f)
            val end = SpatialTrajectoryMath.pose(config, config.cycleSeconds)
            assertEquals("$trajectory x", start.x, end.x, 1e-4f)
            assertEquals("$trajectory y", start.y, end.y, 1e-4f)
            assertEquals("$trajectory z", start.z, end.z, 1e-4f)
            assertEquals("$trajectory distance", start.distanceM, end.distanceM, 1e-4f)
        }
    }

    @Test
    fun oneShotLinearTrajectoryStopsAtEndPose() {
        val config = SpatialAudioConfig(
            trajectory = SpatialTrajectory.LINEAR,
            motionMode = SpatialMotionMode.ONCE,
            startAzimuthDeg = -90f,
            endAzimuthDeg = 90f,
            startElevationDeg = -30f,
            endElevationDeg = 45f,
            startDistanceM = 12f,
            endDistanceM = 0.5f,
            cycleSeconds = 5f,
        )
        val atEnd = SpatialTrajectoryMath.pose(config, 5f)
        val longAfterEnd = SpatialTrajectoryMath.pose(config, 50f)
        assertEquals(atEnd.x, longAfterEnd.x, 1e-5f)
        assertEquals(atEnd.y, longAfterEnd.y, 1e-5f)
        assertEquals(atEnd.z, longAfterEnd.z, 1e-5f)
        assertEquals(0.5f, longAfterEnd.distanceM, 1e-5f)
    }

    @Test
    fun everyPoseIsUnitLengthAndDistanceStaysBounded() {
        SpatialTrajectory.entries.forEach { trajectory ->
            val config = SpatialAudioConfig(
                trajectory = trajectory,
                startDistanceM = 1f,
                endDistanceM = 15f,
                cycleSeconds = 9f,
            )
            repeat(101) { index ->
                val pose = SpatialTrajectoryMath.pose(config, index * 0.09f)
                val length = kotlin.math.sqrt(pose.x * pose.x + pose.y * pose.y + pose.z * pose.z)
                assertEquals(1f, length, 1e-4f)
                assertTrue("$trajectory distance ${pose.distanceM}", pose.distanceM in 1f..15f)
            }
        }
    }

    private fun assertPose(actual: SpatialPose, x: Float, y: Float, z: Float) {
        assertEquals(x, actual.x, 1e-4f)
        assertEquals(y, actual.y, 1e-4f)
        assertEquals(z, actual.z, 1e-4f)
    }
}
