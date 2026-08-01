package com.aistudio.mediatool.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SlideshowTimingTest {
    @Test
    fun preservesExactTotalDuration() {
        val values = SlideshowTiming.distributeDurations(10_001, 3)
        assertEquals(10_001, values.sum())
        assertEquals(3, values.size)
    }

    @Test
    fun customIntervalsAreAbsoluteAndAutomaticImagesFillOnlyFreeRanges() {
        val schedule = SlideshowTiming.buildSchedule(
            10_000L,
            listOf(
                SlideshowInterval(null, null),
                SlideshowInterval(4_000L, 6_000L),
                SlideshowInterval(null, null),
            ),
        )
        assertEquals(
            listOf(
                SlideshowSlot(0L, 4_000L),
                SlideshowSlot(4_000L, 6_000L),
                SlideshowSlot(6_000L, 10_000L),
            ),
            schedule,
        )
    }

    @Test
    fun customOnlySchedulePreservesIntentionalBlackGaps() {
        val schedule = SlideshowTiming.buildSchedule(
            10_000L,
            listOf(
                SlideshowInterval(2_000L, 4_000L),
                SlideshowInterval(6_000L, 8_000L),
            ),
        )
        assertEquals(listOf(SlideshowSlot(2_000L, 4_000L), SlideshowSlot(6_000L, 8_000L)), schedule)
    }

    @Test
    fun rejectsIncompleteOrOverlappingCustomIntervals() {
        assertThrows(IllegalArgumentException::class.java) {
            SlideshowTiming.parseInterval("1000", "")
        }
        assertThrows(IllegalArgumentException::class.java) {
            SlideshowTiming.buildSchedule(
                10_000L,
                listOf(SlideshowInterval(2_000L, 5_000L), SlideshowInterval(4_000L, 7_000L)),
            )
        }
    }
}
