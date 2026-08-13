package com.aistudio.mediatool.feature.studio.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aistudio.mediatool.feature.studio.domain.StudioTrackType

internal val STUDIO_RECORDING_ROLE_TYPES = listOf(
    StudioTrackType.VOCAL,
    StudioTrackType.BACKING_VOCAL,
    StudioTrackType.ADLIB,
    StudioTrackType.OTHER,
)

@Composable
internal fun StudioRecordingRolePanel(
    selectedRole: StudioTrackType,
    enabled: Boolean,
    onSelected: (StudioTrackType) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                "Lớp sắp thu",
                fontWeight = FontWeight.Bold,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                "Chọn vai trò trước khi bấm Thu giọng. Mỗi bản thu mới vẫn là một lớp độc lập.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                STUDIO_RECORDING_ROLE_TYPES.forEach { type ->
                    val label = studioRecordingRoleLabel(type)
                    FilterChip(
                        selected = selectedRole == type,
                        onClick = { onSelected(type) },
                        enabled = enabled,
                        label = { Text(label) },
                        modifier = Modifier.semantics {
                            contentDescription = "Vai trò lớp sắp thu: $label"
                            stateDescription = if (selectedRole == type) "đang chọn" else "chưa chọn"
                        },
                    )
                }
            }
            Text(
                "Sẽ thu vào: ${studioRecordingRoleLabel(selectedRole)}",
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.semantics {
                    liveRegion = LiveRegionMode.Polite
                    contentDescription = "Lớp sắp thu là ${studioRecordingRoleLabel(selectedRole)}"
                },
            )
        }
    }
}

internal fun studioRecordingRoleLabel(type: StudioTrackType): String = when (type) {
    StudioTrackType.VOCAL -> "Giọng chính"
    StudioTrackType.BACKING_VOCAL -> "Giọng bè"
    StudioTrackType.ADLIB -> "Giọng phụ"
    StudioTrackType.OTHER -> "Song ca / khác"
    StudioTrackType.BEAT -> "Nhạc nền"
    StudioTrackType.INSTRUMENT -> "Nhạc cụ"
}
