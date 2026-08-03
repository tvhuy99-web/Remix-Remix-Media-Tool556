package com.aistudio.mediatool.core.ml

import org.junit.Assert.assertEquals
import org.junit.Test

class OverlapWindowTest {
    @Test
    fun weightsAreComplementaryAcrossTheWholeOverlap() {
        val size = 257
        repeat(size) { index ->
            val weights = OverlapWindow.weights(index, size)
            assertEquals(1f, weights.previous + weights.current, 0.000001f)
        }
        assertEquals(OverlapWeights(1f, 0f), OverlapWindow.weights(0, size))
        assertEquals(OverlapWeights(0f, 1f), OverlapWindow.weights(size - 1, size))
    }

    @Test
    fun identicalPredictionsKeepUnityAmplitude() {
        val sample = 0.8f
        repeat(64) { index ->
            val weights = OverlapWindow.weights(index, 64)
            val mixed = sample * weights.previous + sample * weights.current
            assertEquals(sample, mixed, 0.000001f)
        }
    }

    @Test
    fun uvrReferenceWindowIsNormalizedAcrossOverlap() {
        val chunking = StemModelRegistry.uvrMdxVocFtLiteRt.chunking
        repeat(chunking.overlapFrames) { index ->
            val weights = OverlapWindow.weights(index, chunking)
            assertEquals(1f, weights.previous + weights.current, 0.000001f)
        }
        assertEquals(OverlapWeights(1f, 0f), OverlapWindow.weights(0, chunking))
        assertEquals(
            OverlapWeights(0f, 1f),
            OverlapWindow.weights(chunking.overlapFrames - 1, chunking),
        )
    }
}
