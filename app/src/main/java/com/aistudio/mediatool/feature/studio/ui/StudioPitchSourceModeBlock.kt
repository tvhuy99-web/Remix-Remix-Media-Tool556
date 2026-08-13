package com.aistudio.mediatool.feature.studio.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontWeight
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
    val keyKnown = project.proSettings.musicalKey.isKnown
    Text(
        text = if (keyKnown) {
            "Tông bài đã sẵn sàng cho Auto-Tune và tạo bè."
        } else {
            "Chưa có tông bài. Quay lại Nhịp & tông để chọn hoặc phân tích tông trước."
        },
        fontWeight = FontWeight.SemiBold,
        color = if (keyKnown) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error,
    )
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
