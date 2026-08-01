package com.aistudio.mediatool.core.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TimelineSegmentsTest {
    @Test
    fun blankInputMeansWholeTrackFromZero() {
        val result = TimelineSegments.parse("", "")
        assertTrue(result.isValid)
        assertEquals(TimelineSegment(0L, null), result.segments?.single())
    }

    @Test
    fun allowsMultipleOpenEndedPlacements() {
        val result = TimelineSegments.parse("0, 5000", "")
        assertEquals(listOf(TimelineSegment(0L, null), TimelineSegment(5000L, null)), result.segments)
    }

    @Test
    fun rejectsInvalidOrMismatchedTokens() {
        assertFalse(TimelineSegments.parse("abc", "1000").isValid)
        assertFalse(TimelineSegments.parse("0,,2000", "").isValid)
        assertFalse(TimelineSegments.parse("0,1000", "2000").isValid)
    }

    @Test
    fun rejectsEndBeforeStart() {
        val result = TimelineSegments.parse("2000", "1000")
        assertNull(result.segments)
        assertTrue(result.error.orEmpty().contains("phải lớn hơn"))
    }
}
