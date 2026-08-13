package com.aistudio.mediatool.feature.studio.ui

import androidx.compose.runtime.Composable
import com.aistudio.mediatool.feature.studio.audio.StudioHarmonyPreset

@Composable
internal fun StudioPitchParameterBlock(
    mode: StudioPitchToolMode,
    strength: Float,
    maxCents: Float,
    harmonyPreset: StudioHarmonyPreset,
    harmonyVolume: Float,
    harmonyPan: Float,
    enabled: Boolean,
    onStrength: (Float) -> Unit,
    onMaxCents: (Float) -> Unit,
    onHarmonyPreset: (StudioHarmonyPreset) -> Unit,
    onHarmonyVolume: (Float) -> Unit,
    onHarmonyPan: (Float) -> Unit,
) {
    if (mode == StudioPitchToolMode.AUTO_TUNE) {
        StudioAutoTuneControls(strength, maxCents, enabled, onStrength, onMaxCents)
    } else {
        StudioHarmonyControls(
            harmonyPreset,
            harmonyVolume,
            harmonyPan,
            enabled,
            onHarmonyPreset,
            onHarmonyVolume,
            onHarmonyPan,
        )
    }
}
