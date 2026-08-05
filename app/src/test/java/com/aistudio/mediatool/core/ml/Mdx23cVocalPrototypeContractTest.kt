package com.aistudio.mediatool.core.ml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class Mdx23cVocalPrototypeContractTest {
    @Test
    fun matchesOfficialMdx23cVocalReferenceFraming() {
        val contract = Mdx23cVocalPrototypeContract.spectrogram

        assertEquals(8_192, contract.nFft)
        assertEquals(1_024, contract.hopLength)
        assertEquals(4_096, contract.frequencyBins)
        assertEquals(256, contract.timeFrames)
        assertEquals(261_120, contract.chunkFrames)
        assertEquals(4_096, contract.trimFrames)
        assertEquals(0, contract.contributionTrimFrames)
        assertEquals(261_120, contract.generatedFrames)
        assertEquals(65_280, contract.strideFrames)
        assertEquals(195_840, contract.overlapFrames)
        assertEquals(26_112, contract.windowFadeFrames)
        assertEquals(195_840, contract.reflectBoundaryFrames)
        assertEquals(4_194_304, contract.tensorElements)
        assertFalse(contract.supportsPolarityDenoise)
    }
}
