package com.aistudio.mediatool.feature.studio.ui

import androidx.compose.runtime.Composable
import com.aistudio.mediatool.feature.studio.domain.StudioProject
import com.aistudio.mediatool.feature.studio.domain.StudioTrack
import com.aistudio.mediatool.feature.studio.domain.StudioTrackType

@Composable
internal fun StudioPitchSourceModeBlock(
    project: StudioProject,
    selectedTrackId: String?,
    mode: StudioPitchToolMode,
    enabled: Boolean,
    onSelectTrack: (String) -> Unit,
    onMode: (StudioPitchToolMode) -> Unit,
) {
    val vocals = project.pitchVocalTracks()
    StudioPitchSourceSelector(project.copy(tracks = vocals), selectedTrackId, enabled, onSelectTrack)
    StudioPitchModeSelector(mode, enabled, onMode)
}

internal fun StudioProject.pitchVocalTracks(): List<StudioTrack> = tracks.filter { track ->
    track.type in setOf(
        StudioTrackType.VOCAL,
        StudioTrackType.BACKING_VOCAL,
        StudioTrackType.ADLIB,
        StudioTrackType.OTHER,
    ) && !track.locked && (track.clips.isNotEmpty() || track.takes.isNotEmpty())
}
