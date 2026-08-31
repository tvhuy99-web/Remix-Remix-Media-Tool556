package com.aistudio.mediatool.core.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrimAudioCommandBuilderTest {
    @Test
    fun singleSegmentWritesExplicitMuxerWithoutFadeWhenDisabled() {
        val result = TrimAudioCommandBuilder.build(
            inputPath = "saf:input",
            outputPath = "saf:output",
            segments = listOf(TimelineSegment(5_000L, 15_000L)),
            sourceDurationSec = 60.0,
            audioEncodingArgs = "-c:a aac -b:a 192k",
            requestedFadeSec = 0.0,
            outputFormat = "mp4",
        )

        assertEquals(10.0, result.expectedDurationSec, 0.001)
        assertTrue(result.command.contains("atrim=start=5.000:end=15.000"))
        assertTrue(result.command.contains("-f mp4 \"saf:output\""))
        assertFalse(result.command.contains("afade="))
    }

    @Test
    fun multipleSegmentsConcatAndFadeInOnePass() {
        val result = TrimAudioCommandBuilder.build(
            inputPath = "saf:input",
            outputPath = "saf:output",
            segments = listOf(
                TimelineSegment(0L, 3_000L),
                TimelineSegment(10_000L, 14_000L),
            ),
            sourceDurationSec = 30.0,
            audioEncodingArgs = "-c:a libmp3lame -b:a 256k",
            requestedFadeSec = 1.0,
            outputFormat = "mp3",
        )

        assertEquals(7.0, result.expectedDurationSec, 0.001)
        assertTrue(result.command.contains("asplit=2[asrc0][asrc1]"))
        assertTrue(result.command.contains("concat=n=2:v=0:a=1[acat]"))
        assertTrue(result.command.contains("afade=t=in"))
        assertTrue(result.command.contains("afade=t=out"))
        assertTrue(result.command.contains("-f mp3 \"saf:output\""))
    }
}
