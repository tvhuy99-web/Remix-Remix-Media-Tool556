package com.aistudio.mediatool.feature.studio.audio

data class StudioPitchSegment(
    val startFrame: Long,
    val endFrame: Long,
    val pitchFactor: Float,
    val confidence: Float,
)

data class StudioPitchPlan(
    val segments: List<StudioPitchSegment>,
    val voicedCoverage: Float,
    val averageConfidence: Float,
)
