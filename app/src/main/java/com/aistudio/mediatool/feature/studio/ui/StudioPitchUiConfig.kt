package com.aistudio.mediatool.feature.studio.ui

import com.aistudio.mediatool.feature.studio.integration.StudioAutoTuneConfig
import com.aistudio.mediatool.feature.studio.integration.StudioHarmonyConfig

internal fun StudioPitchUiModel.autoTuneConfig(): StudioAutoTuneConfig =
    StudioAutoTuneConfig(strength = strength, maxCorrectionCents = maxCents)

internal fun StudioPitchUiModel.harmonyConfig(): StudioHarmonyConfig =
    StudioHarmonyConfig(preset = harmonyPreset, volumeDb = harmonyVolume, pan = harmonyPan)
