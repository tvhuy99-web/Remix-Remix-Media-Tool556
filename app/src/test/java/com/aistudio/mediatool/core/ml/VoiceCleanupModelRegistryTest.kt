package com.aistudio.mediatool.core.ml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceCleanupModelRegistryTest {
    @Test
    fun modelArtifactIsPinnedToVerifiedRevision() {
        val model = VoiceCleanupModelRegistry.dpdfnet8_48khz_hr

        assertEquals(19_639_068L, model.modelSpec.expectedBytes)
        assertEquals(
            "3a28291a00b359592eaf6e853f49344eb6aac23dc992739de28da0f9face44c3",
            model.modelSpec.sha256,
        )
        assertTrue(model.modelSpec.url.contains(VoiceCleanupModelRegistry.REVISION))
        assertEquals(48_000, model.sampleRate)
        assertEquals(960, model.windowLength)
        assertEquals(480, model.hopLength)
        assertEquals("Apache-2.0", model.licenseName)
    }
}
