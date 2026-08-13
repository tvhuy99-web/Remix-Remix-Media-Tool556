package com.aistudio.mediatool.feature.studio.audio

import com.aistudio.mediatool.feature.studio.domain.StudioPitchClass
import com.aistudio.mediatool.feature.studio.domain.StudioScaleMode

object StudioHarmonyPlanner {
    fun build(
        mono: FloatArray,
        analysisRate: Int,
        sourceRate: Int,
        sourceFrames: Long,
        root: StudioPitchClass,
        scale: StudioScaleMode,
        preset: StudioHarmonyPreset,
    ): StudioPitchPlan = StudioPitchPlanSupport.build(
        mono = mono,
        analysisRate = analysisRate,
        sourceRate = sourceRate,
        sourceFrames = sourceFrames,
        strength = 1f,
        maxCents = 1_200f,
        bucketCents = 25f,
        targetMidi = { midi -> StudioPitchTheory.harmonyTargetMidi(midi, root, scale, preset) },
    )
}
