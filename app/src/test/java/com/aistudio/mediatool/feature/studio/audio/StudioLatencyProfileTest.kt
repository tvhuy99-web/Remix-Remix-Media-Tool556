package com.aistudio.mediatool.feature.studio.audio

import org.junit.Assert.assertEquals
import org.junit.Test

class StudioLatencyProfileTest {
    @Test
    fun compensationFrames_scalesToProjectRate() {
        val profile = StudioLatencyProfile(
            key = "route",
            inputFingerprint = "mic",
            outputFingerprint = "headphones",
            inputMode = StudioInputMode.AUTO,
            sampleRate = 44_100,
            automaticFrames = 4_410,
            manualFrames = 441,
            confidence = 0.9f,
        )

        assertEquals(5_280L, profile.compensationFrames(48_000))
    }

    @Test
    fun totalFrames_neverBecomesNegative() {
        val profile = StudioLatencyProfile(
            key = "route",
            inputFingerprint = "mic",
            outputFingerprint = "speaker",
            inputMode = StudioInputMode.STUDIO_RAW,
            sampleRate = 48_000,
            automaticFrames = 480,
            manualFrames = -960,
        )

        assertEquals(0L, profile.totalFrames)
        assertEquals(0L, profile.compensationFrames(48_000))
    }
}
