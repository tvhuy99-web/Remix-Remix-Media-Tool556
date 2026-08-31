package com.aistudio.mediatool.core.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrimVideoCommandBuilderTest {
    @Test
    fun singleSegmentBuildsTrimAndMapsVideoAndAudio() {
        val result = TrimVideoCommandBuilder.build(
            inputPath = "input.mp4",
            outputPath = "output.mp4",
            segments = listOf(TimelineSegment(5_000L, 10_000L)),
            sourceDurationSec = 30.0,
            sourceHasAudio = true,
            requestedFadeSec = 0.0,
        )

        assertEquals(5.0, result.expectedDurationSec, 0.001)
        assertTrue(result.command.contains("[0:v]trim=start=5.000:end=10.000"))
        assertTrue(result.command.contains("[0:a]atrim=start=5.000:end=10.000"))
        assertTrue(result.command.contains("-map \"[v0]\""))
        assertTrue(result.command.contains("-map \"[a0]\""))
        assertTrue(result.command.contains("-c:v mpeg4 -q:v 5"))
        assertTrue(result.command.contains("-c:a aac -b:a 160k"))
    }

    @Test
    fun multipleSegmentsSplitStreamsBeforeFilterConcat() {
        val result = TrimVideoCommandBuilder.build(
            inputPath = "input.mp4",
            outputPath = "output.mp4",
            segments = listOf(
                TimelineSegment(0L, 2_000L),
                TimelineSegment(8_000L, 11_000L),
            ),
            sourceDurationSec = 20.0,
            sourceHasAudio = true,
            requestedFadeSec = 1.0,
        )

        assertEquals(5.0, result.expectedDurationSec, 0.001)
        assertTrue(result.command.contains("[0:v]split=2[vsrc0][vsrc1]"))
        assertTrue(result.command.contains("[0:a]asplit=2[asrc0][asrc1]"))
        assertTrue(result.command.contains("concat=n=2:v=1:a=1[vcat][acat]"))
        assertTrue(result.command.contains("afade=t=in"))
        assertTrue(result.command.contains("afade=t=out"))
        assertFalse(result.command.contains("-f concat"))
    }

    @Test
    fun silentVideoDoesNotReferenceAudioStream() {
        val result = TrimVideoCommandBuilder.build(
            inputPath = "input.mp4",
            outputPath = "output.mp4",
            segments = listOf(
                TimelineSegment(1_000L, 2_000L),
                TimelineSegment(3_000L, null),
            ),
            sourceDurationSec = 5.0,
            sourceHasAudio = false,
            requestedFadeSec = 1.0,
        )

        assertEquals(3.0, result.expectedDurationSec, 0.001)
        assertTrue(result.command.contains("concat=n=2:v=1:a=0[vcat]"))
        assertFalse(result.command.contains("[0:a]"))
        assertFalse(result.command.contains("-c:a"))
    }
}
