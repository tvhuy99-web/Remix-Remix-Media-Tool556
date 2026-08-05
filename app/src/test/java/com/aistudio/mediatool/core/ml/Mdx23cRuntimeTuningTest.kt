package com.aistudio.mediatool.core.ml

import org.junit.Assert.assertEquals
import org.junit.Test

class Mdx23cRuntimeTuningTest {
    private val contract = Mdx23cVocalPrototypeContract.spectrogram

    @Test
    fun executionDefaultsToXnnpackAndAllowsCpu() {
        assertEquals(
            Mdx23cExecutionMode.XNNPACK,
            Mdx23cExecutionMode.fromSettingsIndex(99),
        )
        assertEquals(OnnxAcceleration.CPU, Mdx23cExecutionMode.CPU.acceleration)
        assertEquals(OnnxAcceleration.XNNPACK, Mdx23cExecutionMode.XNNPACK.acceleration)
    }

    @Test
    fun overlapModesUseExactQuarterChunkStrides() {
        assertEquals(195_840, Mdx23cOverlapMode.FAST.requireCompatible(contract).strideFrames)
        assertEquals(130_560, Mdx23cOverlapMode.BALANCED.requireCompatible(contract).strideFrames)
        assertEquals(65_280, Mdx23cOverlapMode.HIGH_QUALITY.requireCompatible(contract).strideFrames)
        assertEquals(65_280, Mdx23cOverlapMode.FAST.overlapFrames(contract))
        assertEquals(130_560, Mdx23cOverlapMode.BALANCED.overlapFrames(contract))
        assertEquals(195_840, Mdx23cOverlapMode.HIGH_QUALITY.overlapFrames(contract))
    }

    @Test
    fun unknownOverlapSettingFallsBackToBalanced() {
        assertEquals(Mdx23cOverlapMode.BALANCED, Mdx23cOverlapMode.fromSettingsIndex(-1))
        assertEquals(Mdx23cOverlapMode.BALANCED, Mdx23cOverlapMode.fromSettingsIndex(99))
    }
}
