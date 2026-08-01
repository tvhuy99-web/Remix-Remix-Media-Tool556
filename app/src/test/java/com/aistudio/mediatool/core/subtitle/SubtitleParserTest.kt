package com.aistudio.mediatool.core.subtitle

import org.junit.Assert.assertEquals
import org.junit.Test

class SubtitleParserTest {
    @Test
    fun parsesSrtAndCleansMarkup() {
        val cues = SubtitleParser.parse(
            """
            1
            00:00:01,250 --> 00:00:03,000
            <i>Xin &amp; chào</i>

            2
            00:00:03.500 --> 00:00:04.750
            Thế giới
            """.trimIndent(),
        )
        assertEquals(2, cues.size)
        assertEquals(1_250L, cues[0].startMs)
        assertEquals("Xin & chào", cues[0].text)
        assertEquals(4_750L, cues[1].endMs)
    }

    @Test
    fun parsesWebVttMinutesOnly() {
        val cues = SubtitleParser.parse("WEBVTT\n\n00:01.000 --> 00:02.000\nHello")
        assertEquals(1_000L, cues.single().startMs)
    }
}
