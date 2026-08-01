package com.aistudio.mediatool.core.ml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class StemModelRegistryTest {
    @Test
    fun twoStemDefaultsToMelBandRoFormer() {
        assertSame(
            StemModelRegistry.melBandRoFormerTwoStem,
            StemModelRegistry.resolve(StemMode.TWO_STEM, null),
        )
    }

    @Test
    fun melBandTensorAndChunkContractMatchesExport() {
        val model = StemModelRegistry.melBandRoFormerTwoStem
        assertEquals(352_800, model.chunking.frames)
        assertEquals(176_400, model.chunking.stepFrames)
        assertEquals(2, model.tensor.sourceCount)
        assertEquals(listOf(0), model.sources.vocals.sourceIndices)
        assertEquals(listOf(1), model.sources.music.sourceIndices)
        assertEquals(AudioNormalization.NONE, model.normalization)
        assertEquals(
            setOf(OnnxAcceleration.CPU, OnnxAcceleration.XNNPACK),
            model.allowedAccelerators,
        )
    }

    @Test
    fun incompatibleStoredChoiceFallsBackWithinMode() {
        assertSame(
            StemModelRegistry.melBandRoFormerTwoStem,
            StemModelRegistry.resolve(StemMode.TWO_STEM, StemModelRegistry.DEMUCS_4_STEM_ID),
        )
    }
}
