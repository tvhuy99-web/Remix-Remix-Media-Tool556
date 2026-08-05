package com.aistudio.mediatool.core.ml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Test

class StemModelRegistryTest {
    @Test
    fun twoStemDefaultsToUvrMdx() {
        assertSame(
            StemModelRegistry.uvrMdxVocFtLiteRt,
            StemModelRegistry.resolve(StemMode.TWO_STEM, null),
        )
    }

    @Test
    fun twoStemKeepsUvrAndDemucsChoices() {
        assertEquals(
            listOf(
                StemModelRegistry.UVR_MDX_VOC_FT_LITERT_ID,
                StemModelRegistry.DEMUCS_FT_VOCALS_SPECIALIST_ID,
                StemModelRegistry.DEMUCS_2_STEM_LITE_ID,
            ),
            StemModelRegistry.modelsFor(StemMode.TWO_STEM).map { it.id },
        )
    }

    @Test
    fun uvrMdxContractMatchesPinnedLiteRtGraph() {
        val model = StemModelRegistry.uvrMdxVocFtLiteRt
        val mdx = requireNotNull(model.mdx)
        assertEquals(StemInferenceBackend.MDX_LITERT, model.backend)
        assertEquals(44_100, model.sampleRate)
        assertEquals(6_144, mdx.nFft)
        assertEquals(1_024, mdx.hopLength)
        assertEquals(3_072, mdx.frequencyBins)
        assertEquals(256, mdx.timeFrames)
        assertEquals(261_120, mdx.chunkFrames)
        assertEquals(3_072, mdx.trimFrames)
        assertEquals(254_976, mdx.generatedFrames)
        assertEquals(229_478, mdx.strideFrames)
        assertEquals(25_498, mdx.overlapFrames)
        assertEquals(3_145_728, mdx.tensorElements)
        assertEquals(66_848_828L, model.modelSpec.expectedBytes)
        assertEquals(
            "5ef47e3b3bafa14357532c0a3f6c5f18444d94b6efe3fd62b3d13f80051f1e58",
            model.modelSpec.sha256,
        )
    }

    @Test
    fun htdemucsFtVocalsContractMatchesPinnedOnnxGraph() {
        val model = StemModelRegistry.demucsFtVocalsSpecialist

        assertEquals(StemMode.TWO_STEM, model.mode)
        assertEquals(AudioNormalization.NONE, model.normalization)
        assertEquals(343_980, model.chunking.frames)
        assertEquals(85_995, model.chunking.overlapFrames)
        assertEquals(OverlapProfile.REFERENCE_LINEAR_WINDOW, model.chunking.overlapProfile)
        assertEquals("mix", model.tensor.inputName)
        assertEquals("stems", model.tensor.outputName)
        assertEquals(4, model.tensor.sourceCount)
        assertEquals(listOf(3), model.sources.vocals.sourceIndices)
        assertEquals(listOf(0, 1, 2), model.sources.music.sourceIndices)
        assertEquals(316_446_953L, model.modelSpec.expectedBytes)
        assertEquals(
            "8c5d5e2da1f27050240bb80236673307ee3b40d4b064066d9350f4d64bfd544d",
            model.modelSpec.sha256,
        )
        assertNotEquals(StemModelRegistry.demucsTwoStemLite.modelSpec, model.modelSpec)
    }

    @Test
    fun demucsUsesCpuProviderOnly() {
        val specialist = StemModelRegistry.demucsFtVocalsSpecialist
        val twoStem = StemModelRegistry.demucsTwoStemLite
        val fourStem = StemModelRegistry.demucsFourStem

        assertEquals(StemMode.TWO_STEM, twoStem.mode)
        assertEquals(fourStem.modelSpec, twoStem.modelSpec)
        listOf(specialist, twoStem, fourStem).forEach { model ->
            assertEquals(setOf(OnnxAcceleration.CPU), model.allowedAccelerators)
            assertFalse(OnnxAcceleration.XNNPACK in model.allowedAccelerators)
        }
        assertEquals(listOf(3), twoStem.sources.vocals.sourceIndices)
        assertEquals(listOf(0, 1, 2), twoStem.sources.music.sourceIndices)
    }

    @Test
    fun removedStoredChoiceFallsBackToUvr() {
        assertSame(
            StemModelRegistry.uvrMdxVocFtLiteRt,
            StemModelRegistry.resolve(StemMode.TWO_STEM, "melband-roformer-kj-vocals-v1"),
        )
    }
}
