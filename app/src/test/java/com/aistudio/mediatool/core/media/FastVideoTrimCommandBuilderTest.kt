package com.aistudio.mediatool.core.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FastVideoTrimCommandBuilderTest {
    @Test
    fun singleSegmentUsesStreamCopyWithoutVideoEncoding() {
        val result = FastVideoTrimCommandBuilder.build(
            inputPath = "input.mp4",
            outputPath = "output.mp4",
            segment = TimelineSegment(5_000L, 20_000L),
            sourceDurationSec = 30.0,
        )

        assertEquals(15.0, result.expectedDurationSec, 0.001)
        assertTrue(result.command.contains("-ss 5.000"))
        assertTrue(result.command.contains("-t 15.000"))
        assertTrue(result.command.contains("-map 0:v:0"))
        assertTrue(result.command.contains("-map 0:a:0?"))
        assertTrue(result.command.contains("-c copy"))
        assertFalse(result.command.contains("-c:v mpeg4"))
        assertFalse(result.command.contains("-filter_complex"))
    }

    @Test
    fun openEndedSegmentStopsAtSourceDuration() {
        val result = FastVideoTrimCommandBuilder.build(
            inputPath = "input.mp4",
            outputPath = "output.mp4",
            segment = TimelineSegment(12_500L, null),
            sourceDurationSec = 40.0,
        )

        assertEquals(27.5, result.expectedDurationSec, 0.001)
        assertTrue(result.command.contains("-ss 12.500"))
        assertTrue(result.command.contains("-t 27.500"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsSegmentOutsideSourceDuration() {
        FastVideoTrimCommandBuilder.build(
            inputPath = "input.mp4",
            outputPath = "output.mp4",
            segment = TimelineSegment(45_000L, null),
            sourceDurationSec = 40.0,
        )
    }
}
