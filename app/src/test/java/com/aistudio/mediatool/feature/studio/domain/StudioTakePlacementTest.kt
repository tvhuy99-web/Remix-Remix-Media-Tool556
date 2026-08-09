package com.aistudio.mediatool.feature.studio.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class StudioTakePlacementTest {
    @Test
    fun negativeCompensatedStart_trimsUnavailableSourceInsteadOfClampingOnly() {
        val take = StudioTake(
            id = "take",
            assetId = "asset",
            recordedTimelineFrame = 1_000L,
            recordedFrames = 48_000L,
            inputSampleRate = 48_000,
            latencyCompensationFrames = 2_000L,
            status = StudioTakeStatus.COMPLETE,
        )

        val placement = take.latencyCompensatedPlacement(48_000)

        assertEquals(0L, placement.timelineStartFrame)
        assertEquals(1_000L, placement.sourceStartFrame)
        assertEquals(48_000L, placement.sourceEndFrame)
    }

    @Test
    fun compensationBeforeZero_scalesTrimToSourceRate() {
        val take = StudioTake(
            id = "take",
            assetId = "asset",
            recordedTimelineFrame = 0L,
            recordedFrames = 44_100L,
            inputSampleRate = 44_100,
            latencyCompensationFrames = 4_800L,
            status = StudioTakeStatus.COMPLETE,
        )

        val placement = take.latencyCompensatedPlacement(48_000)

        assertEquals(0L, placement.timelineStartFrame)
        assertEquals(4_410L, placement.sourceStartFrame)
        assertEquals(44_100L, placement.sourceEndFrame)
    }
}