package com.aistudio.mediatool.feature.studio.audio

import com.aistudio.mediatool.feature.studio.domain.StudioPitchClass
import com.aistudio.mediatool.feature.studio.domain.StudioScaleMode
import kotlin.math.PI
import kotlin.math.sin
import org.junit.Assert.assertTrue
import org.junit.Test

class StudioAutoTunePlannerTest {
    @Test
    fun sharpAMovesDownTowardA() {
        val rate = 12_000
        val input = FloatArray(rate) { index ->
            (0.55 * sin(2.0 * PI * 448.0 * index / rate)).toFloat()
        }
        val plan = StudioAutoTunePlanner.build(
            input, rate, 48_000, 48_000,
            StudioPitchClass.C, StudioScaleMode.MAJOR,
            1f, 200f,
        )
        assertTrue(plan.voicedCoverage > 0.35f)
        assertTrue(plan.segments.any { it.pitchFactor < 0.995f })
    }
}
