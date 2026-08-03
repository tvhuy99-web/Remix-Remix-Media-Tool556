package com.aistudio.mediatool.core.ml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VoiceCleanupLoudnessParserTest {
    @Test
    fun extractLoudnessJsonReturnsLatestCompleteMeasurement() {
        val logs = """
            ffmpeg preface
            {
                "input_i" : "-23.10",
                "input_tp" : "-4.20",
                "input_lra" : "2.00",
                "input_thresh" : "-33.40",
                "target_offset" : "0.10"
            }
            unrelated line
            {
                "input_i" : "-18.25",
                "input_tp" : "-1.75",
                "input_lra" : "1.20",
                "input_thresh" : "-28.80",
                "target_offset" : "-0.05"
            }
        """.trimIndent()

        val json = VoiceCleanupProcessor.extractLoudnessJson(logs)

        assertEquals(true, json?.contains("\"input_i\" : \"-18.25\"") == true)
        assertEquals(true, json?.contains("\"target_offset\" : \"-0.05\"") == true)
    }

    @Test
    fun extractLoudnessJsonRejectsIncompleteObject() {
        val logs = """{ "input_i": "-20.0", "target_offset": "0.0""" // missing closing brace

        assertNull(VoiceCleanupProcessor.extractLoudnessJson(logs))
    }
}
