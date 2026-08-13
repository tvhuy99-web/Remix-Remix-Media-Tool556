package com.aistudio.mediatool.feature.studio.integration

import com.aistudio.mediatool.feature.studio.audio.StudioHarmonyPreset
import com.aistudio.mediatool.feature.studio.domain.StudioAsset
import com.aistudio.mediatool.feature.studio.domain.StudioProject

data class StudioAutoTuneConfig(
    val strength: Float = 0.75f,
    val maxCorrectionCents: Float = 180f,
)

data class StudioHarmonyConfig(
    val preset: StudioHarmonyPreset = StudioHarmonyPreset.THIRD_ABOVE,
    val volumeDb: Float = -7f,
    val pan: Float = 0.28f,
)

data class StudioPitchPreviewResult(
    val project: StudioProject,
    val asset: StudioAsset,
    val sourceTrackId: String,
    val sourceWasMuted: Boolean,
    val voicedCoverage: Float,
    val confidence: Float,
)
