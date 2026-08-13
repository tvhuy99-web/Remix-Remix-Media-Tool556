package com.aistudio.mediatool.feature.studio.ui

import androidx.compose.runtime.Composable
import com.aistudio.mediatool.feature.studio.domain.StudioProject

@Composable
internal fun StudioPitchSourceModeBlock(
    project: StudioProject,
    selectedTrackId: String?,
    mode: StudioPitchToolMode,
    enabled: Boolean,
    onSelectTrack: (String) -> Unit,
    onMode: (StudioPitchToolMode) -> Unit,
) {
    StudioPitchSourceSelector(project, selectedTrackId, enabled, onSelectTrack)
    StudioPitchModeSelector(mode, enabled, onMode)
}
