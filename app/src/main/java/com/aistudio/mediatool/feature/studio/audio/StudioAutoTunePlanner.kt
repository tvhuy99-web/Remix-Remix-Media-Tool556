package com.aistudio.mediatool.feature.studio.audio

import com.aistudio.mediatool.feature.studio.domain.StudioPitchClass
import com.aistudio.mediatool.feature.studio.domain.StudioScaleMode

object StudioAutoTunePlanner {
    fun build(
        mono: FloatArray,
        analysisRate: Int,
        sourceRate: Int,
        sourceFrames: Long,
        root: StudioPitchClass,
        scale: StudioScaleMode,
        strength: Float,
        maxCents: Float,
    ): StudioPitchPlan = StudioPitchPlanSupport.build(
        mono = mono,
        analysisRate = analysisRate,
        sourceRate = sourceRate,
        sourceFrames = sourceFrames,
        strength = strength.coerceIn(0f, 1f),
        maxCents = maxCents.coerceIn(25f, 600f),
        bucketCents = 10f,
        targetMidi = { midi -> StudioPitchTheory.nearestScaleMidi(midi, root, scale) },
    )
}
