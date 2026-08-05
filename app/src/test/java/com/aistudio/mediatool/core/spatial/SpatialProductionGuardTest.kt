package com.aistudio.mediatool.core.spatial

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpatialProductionGuardTest {
    @Test
    fun nativeIdsDoNotDependOnEnumOrderAtCallSites() {
        assertEquals(
            listOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9),
            listOf(
                SpatialTrajectory.HORIZONTAL_CIRCLE,
                SpatialTrajectory.VERTICAL_CIRCLE,
                SpatialTrajectory.FIGURE_EIGHT,
                SpatialTrajectory.LINEAR,
                SpatialTrajectory.STATIC,
                SpatialTrajectory.PENDULUM,
                SpatialTrajectory.FRONT_BACK,
                SpatialTrajectory.SPIRAL,
                SpatialTrajectory.NEAR_FAR,
                SpatialTrajectory.FREE_DRIFT,
            ).map { it.nativeId },
        )
        assertEquals(0, SpatialInterpolation.BILINEAR.nativeId)
        assertEquals(1, SpatialInterpolation.NEAREST.nativeId)
        assertEquals(0, SpatialMotionMode.LOOP.nativeId)
        assertEquals(1, SpatialMotionMode.ONCE.nativeId)
    }

    @Test
    fun diskBudgetIncludesTwoRenderedCopiesTailAndMargin() {
        val oneHourPcm = 48_000L * 2L * 4L * 3_600L
        val value = SpatialDiskBudgetEstimator.estimateForTest(
            decodedBytes = oneHourPcm,
            usableBytes = Long.MAX_VALUE,
            tailSeconds = 2.0,
        )
        val tailBytes = 48_000L * 2L * 4L * 3L
        val expectedRendered = oneHourPcm + tailBytes
        val expectedRequired = expectedRendered * 2L + 256L * 1024L * 1024L
        assertEquals(expectedRendered, value.estimatedRenderedBytes)
        assertEquals(expectedRequired, value.additionalRequiredBytes)
        assertTrue(value.hasCapacity)
    }

    @Test
    fun diskBudgetRejectsInsufficientSpace() {
        val value = SpatialDiskBudgetEstimator.estimateForTest(
            decodedBytes = 400L * 1024L * 1024L,
            usableBytes = 500L * 1024L * 1024L,
            tailSeconds = 2.0,
        )
        assertFalse(value.hasCapacity)
    }

    @Test
    fun diskBudgetSaturatesInsteadOfOverflowing() {
        val value = SpatialDiskBudgetEstimator.estimateForTest(
            decodedBytes = Long.MAX_VALUE,
            usableBytes = Long.MAX_VALUE,
            tailSeconds = 8.0,
        )
        assertEquals(Long.MAX_VALUE, value.estimatedRenderedBytes)
        assertEquals(Long.MAX_VALUE, value.additionalRequiredBytes)
        assertTrue(value.hasCapacity)
    }
}
