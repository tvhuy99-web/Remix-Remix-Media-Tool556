package com.aistudio.mediatool.core.ml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceCleanupModelRegistryTest {
    @Test
    fun mossFormer2ArtifactIsPinned() {
        val descriptor = VoiceCleanupModelRegistry.mossFormer2
        assertEquals(VoiceCleanupModelRegistry.MOSSFORMER2_ID, descriptor.id)
        assertEquals(229_126_935L, descriptor.modelSpec.expectedBytes)
        assertEquals(
            "0904ff3b74bdc089854612096edbe5a2fcfada489241972ba69e0c3ccb24304a",
            descriptor.modelSpec.sha256,
        )
        assertTrue(descriptor.modelSpec.url.contains(VoiceCleanupModelRegistry.REVISION))
        assertTrue(descriptor.modelSpec.url.startsWith("https://"))
        assertEquals(48_000, descriptor.sampleRate)
        assertEquals("Apache-2.0", descriptor.licenseName)
    }
}
