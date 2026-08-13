package com.aistudio.mediatool.feature.studio.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aistudio.mediatool.feature.studio.audio.StudioSessionRuntime
import com.aistudio.mediatool.feature.studio.data.StudioWorkingTrackSelection
import com.aistudio.mediatool.feature.studio.domain.StudioProject
import com.aistudio.mediatool.feature.studio.domain.StudioTrackType

@Composable
internal fun StudioWorkingTrackPanel(
    project: StudioProject,
    selectedClipId: String?,
    enabled: Boolean,
    syncSelectedClip: Boolean = true,
) {
    val state by StudioWorkingTrackSelection.state.collectAsStateWithLifecycle()
    val editableTracks = project.tracks.filter { it.type != StudioTrackType.BEAT }

    LaunchedEffect(project.id, project.tracks, selectedClipId, syncSelectedClip) {
        if (syncSelectedClip || StudioWorkingTrackSelection.state.value.projectId != project.id) {
            StudioWorkingTrackSelection.sync(
                project = project,
                selectedClipId = selectedClipId.takeIf { syncSelectedClip },
            )
        }
    }

    if (editableTracks.isEmpty()) return
    val selectedTrackId = state.trackId.takeIf { state.projectId == project.id }
    val selectedName = editableTracks.firstOrNull { it.id == selectedTrackId }?.let(::workingTrackName)

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Lớp đang thao tác", fontWeight = FontWeight.SemiBold)
        Text(
            "Timeline, chỉnh đoạn, cân âm và Thu sửa một đoạn cùng dùng lựa chọn này.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            editableTracks.forEach { track ->
                val label = workingTrackName(track)
                FilterChip(
                    selected = selectedTrackId == track.id,
                    onClick = {
                        StudioSessionRuntime.selectClip(null)
                        StudioWorkingTrackSelection.select(project, track.id)
                    },
                    enabled = enabled,
                    label = { Text(label) },
                    modifier = Modifier.semantics {
                        contentDescription = "Chọn lớp đang thao tác: $label"
                        stateDescription = if (selectedTrackId == track.id) "đang chọn" else "chưa chọn"
                    },
                )
            }
        }
        selectedName?.let { name ->
            Text(
                "Đang thao tác: $name",
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.semantics {
                    liveRegion = LiveRegionMode.Polite
                    contentDescription = "Lớp đang thao tác là $name"
                },
            )
        }
    }
}

private fun workingTrackName(track: com.aistudio.mediatool.feature.studio.domain.StudioTrack): String = when {
    track.name.isNotBlank() && !track.name.equals("Vocal", ignoreCase = true) -> track.name
    track.type == StudioTrackType.VOCAL -> "Giọng chính"
    track.type == StudioTrackType.BACKING_VOCAL -> "Giọng bè"
    track.type == StudioTrackType.ADLIB -> "Giọng phụ"
    track.type == StudioTrackType.INSTRUMENT -> "Nhạc cụ"
    else -> "Giọng khác"
}
