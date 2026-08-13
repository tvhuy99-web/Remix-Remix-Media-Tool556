package com.aistudio.mediatool.feature.studio.audio

import com.aistudio.mediatool.feature.studio.domain.StudioPitchClass
import com.aistudio.mediatool.feature.studio.domain.StudioScaleMode
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StudioRhythmAnalyzerTest {
    @Test
    fun pulseTrainAt120BpmIsDetected() {
        val sampleRate = 8_000
        val samples = FloatArray(sampleRate * 12)
        val beatFrames = sampleRate / 2
        val pulseFrames = sampleRate / 50
        var start = 0
        while (start < samples.size) {
            for (i in start until minOf(samples.size, start + pulseFrames)) samples[i] = 0.8f
            start += beatFrames
        }

        val estimate = StudioRhythmAnalysisMath.estimateBpm(samples, sampleRate)

        assertNotNull(estimate)
        assertTrue(abs(requireNotNull(estimate).bpm - 120f) <= 1.5f)
        assertTrue(estimate.confidence in 0f..1f)
        assertTrue(estimate.confidence > 0.45f)
    }

    @Test
    fun cMajorChordProducesCMajorSuggestion() {
        val sampleRate = 8_000
        val samples = FloatArray(sampleRate * 6) { index ->
            val time = index.toDouble() / sampleRate.toDouble()
            val c = sin(2.0 * PI * 261.6256 * time)
            val e = sin(2.0 * PI * 329.6276 * time)
            val g = sin(2.0 * PI * 391.9954 * time)
            ((c + e + g) / 3.0 * 0.5).toFloat()
        }

        val estimate = StudioRhythmAnalysisMath.estimateKey(samples, sampleRate)

        assertNotNull(estimate)
        assertEquals(StudioPitchClass.C, requireNotNull(estimate).root)
        assertEquals(StudioScaleMode.MAJOR, estimate.scale)
        assertTrue(estimate.confidence in 0f..1f)
    }

    @Test
    fun silenceReturnsNoSuggestion() {
        val silence = FloatArray(8_000 * 2)
        assertEquals(null, StudioRhythmAnalysisMath.estimateBpm(silence, 8_000))
        assertEquals(null, StudioRhythmAnalysisMath.estimateKey(silence, 8_000))
    }
}
