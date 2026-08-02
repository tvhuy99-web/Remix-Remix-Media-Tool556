package com.aistudio.mediatool.core.ml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class StemModelRegistryTest {
    @Test
    fun twoStemDefaultsToExperimentalUvrMdx() {
        assertSame(
            StemModelRegistry.uvrMdxVocFtLiteRt,
            StemModelRegistry.resolve(StemMode.TWO_STEM, null),
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
            StemModelRegistry.uvrMdxVocFtLiteRt,
            StemModelRegistry.resolve(StemMode.TWO_STEM, StemModelRegistry.DEMUCS_4_STEM_ID),
        )
    }
}
