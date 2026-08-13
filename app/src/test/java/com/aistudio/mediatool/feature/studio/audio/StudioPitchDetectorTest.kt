package com.aistudio.mediatool.feature.studio.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import org.junit.Assert.assertTrue
import org.junit.Test

class StudioPitchDetectorTest {
    @Test
    fun detectorFindsA4() {
        val rate = 12_000
        val input = FloatArray((rate * 1.2f).toInt()) { index ->
            (0.55 * sin(2.0 * PI * 440.0 * index / rate.toDouble())).toFloat()
        }
        val voiced = StudioPitchDetector.estimate(input, rate).mapNotNull { it.frequencyHz }
        assertTrue(voiced.isNotEmpty())
        val sorted = voiced.sorted()
        assertTrue(abs(sorted[sorted.size / 2] - 440f) < 6f)
    }
}
