package com.aistudio.mediatool.core.ml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class StemModelRegistryTest {
    @Test
    fun twoStemDefaultsToHtDemucsFtVocals() {
        assertSame(
            StemModelRegistry.htDemucsFtVocalsQnn,
            StemModelRegistry.resolve(StemMode.TWO_STEM, null),
        )
    }

    @Test
    fun htDemucsFtVocalsContractIsPinnedAndUsesMixComplement() {
        val model = StemModelRegistry.htDemucsFtVocalsQnn
        assertEquals(165_612_636L, model.modelSpec.expectedBytes)
        assertEquals("0cbe651f535415c9d26a7bb614f7d322dd5a080fa0298f2e50f478030a994dce", model.modelSpec.sha256)
        assertEquals("mix", model.tensor.inputName)
        assertEquals("stems", model.tensor.outputName)
        assertEquals(343_980, model.chunking.frames)
        assertEquals(85_995, model.chunking.overlapFrames)
        assertEquals(OverlapProfile.REFERENCE_LINEAR_WINDOW, model.chunking.overlapProfile)
        assertEquals(AudioNormalization.NONE, model.normalization)
        assertEquals(listOf(3), model.sources.vocals.sourceIndices)
        assertEquals(true, model.musicFromMixMinusVocals)
        assertEquals(setOf(OnnxAcceleration.CPU, OnnxAcceleration.XNNPACK, OnnxAcceleration.QNN_GPU), model.allowedAccelerators)
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
    fun demucsLiteReusesFourSourceGraphForTwoStemOutput() {
        val model = StemModelRegistry.demucsTwoStemLite
        assertEquals(StemMode.TWO_STEM, model.mode)
        assertEquals(StemModelRegistry.demucsFourStem.modelSpec, model.modelSpec)
        assertEquals(4, model.tensor.sourceCount)
        assertEquals(listOf(3), model.sources.vocals.sourceIndices)
        assertEquals(listOf(0, 1, 2), model.sources.music.sourceIndices)
        assertEquals(null, model.sources.drums)
    }

    @Test
    fun incompatibleStoredChoiceFallsBackWithinMode() {
        assertSame(
            StemModelRegistry.htDemucsFtVocalsQnn,
            StemModelRegistry.resolve(StemMode.TWO_STEM, StemModelRegistry.DEMUCS_4_STEM_ID),
        )
    }
}
