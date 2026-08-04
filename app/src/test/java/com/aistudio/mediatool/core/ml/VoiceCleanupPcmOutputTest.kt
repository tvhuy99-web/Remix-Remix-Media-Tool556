package com.aistudio.mediatool.core.ml

import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceCleanupPcmOutputTest {
    @Test
    fun finiteSamplesArePreservedWithoutHardClipping() {
        assertEquals(2.25f, VoiceCleanupPcmOutput.validatedSample(2.25f), 0f)
        assertEquals(-2.25f, VoiceCleanupPcmOutput.validatedSample(-2.25f), 0f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun nonFiniteSamplesAreRejected() {
        VoiceCleanupPcmOutput.validatedSample(Float.NaN)
    }
}
