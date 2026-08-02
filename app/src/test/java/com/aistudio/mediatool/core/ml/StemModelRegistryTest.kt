package com.aistudio.mediatool.core.ml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class StemModelRegistryTest {
    @Test
    fun nativeFtVocalsIsDefaultTwoStemModel() {
        val model = StemModelRegistry.resolve(StemMode.TWO_STEM, null)
        assertSame(StemModelRegistry.demucsFtVocalsTwoStem, model)
        assertEquals(83_994_361L, model.modelSpec.expectedBytes)
        assertEquals(
            "19186500a45a551a034d96e9500415ebe73c8bd570bf55337ddc8cc8f53a9120",
            model.modelSpec.sha256,
        )
        assertTrue(model.modelSpec.fileName.endsWith(".bin"))
    }

    @Test
    fun oldStoredOnnxChoiceFallsBackToNativeModel() {
        assertSame(
            StemModelRegistry.demucsFtVocalsTwoStem,
            StemModelRegistry.resolve(StemMode.TWO_STEM, StemModelRegistry.DEMUCS_2_STEM_LITE_ID),
        )
    }

    @Test
    fun nativeFourStemUsesSameVerifiedWeights() {
        val model = StemModelRegistry.demucsFtVocalsFourStem
        assertEquals(StemMode.FOUR_STEM, model.mode)
        assertEquals(StemModelRegistry.demucsFtVocalsTwoStem.modelSpec, model.modelSpec)
        assertEquals(listOf(3), model.sources.vocals.sourceIndices)
        assertEquals(listOf(0), model.sources.drums?.sourceIndices)
    }
}
