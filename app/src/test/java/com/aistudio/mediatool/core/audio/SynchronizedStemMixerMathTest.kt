package com.aistudio.mediatool.core.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class SynchronizedStemMixerMathTest {
    @Test
    fun mixesTwoStereoTracksFromDedicatedChannelPairs() {
        val source = floatArrayOf(
            0.50f, -0.25f,
            0.20f, 0.40f,
            9f, 9f,
            9f, 9f,
            -0.10f, 0.30f,
            0.70f, -0.20f,
            9f, 9f,
            9f, 9f,
        )
        val output = FloatArray(4)

        val count = SynchronizedStemMixerMath.mixInterleaved(
            source = source,
            frames = 2,
            trackCount = 2,
            gains = floatArrayOf(1f, 0.5f),
            destination = output,
        )

        assertEquals(4, count)
        assertArrayEquals(
            floatArrayOf(0.60f, -0.05f, 0.25f, 0.20f),
            output,
            0.000001f,
        )
    }

    @Test
    fun supportsFourTracksAndMuteGain() {
        val source = floatArrayOf(
            1f, 2f,
            3f, 4f,
            5f, 6f,
            7f, 8f,
        )
        val output = FloatArray(2)

        SynchronizedStemMixerMath.mixInterleaved(
            source = source,
            frames = 1,
            trackCount = 4,
            gains = floatArrayOf(1f, 0f, 0.5f, 0.25f),
            destination = output,
        )

        assertArrayEquals(floatArrayOf(5.25f, 7f), output, 0.000001f)
    }

    @Test
    fun sanitizesNonFiniteSamplesAndGains() {
        val source = floatArrayOf(
            Float.NaN, 0.5f,
            0.75f, Float.POSITIVE_INFINITY,
            0f, 0f,
            0f, 0f,
        )
        val output = FloatArray(2)

        SynchronizedStemMixerMath.mixInterleaved(
            source = source,
            frames = 1,
            trackCount = 2,
            gains = floatArrayOf(Float.NaN, 2f),
            destination = output,
        )

        assertArrayEquals(floatArrayOf(0.75f, 0f), output, 0f)
    }
}
