package com.aistudio.mediatool.feature.studio.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aistudio.mediatool.feature.studio.audio.StudioHarmonyPreset
import com.aistudio.mediatool.feature.studio.domain.StudioProject
import com.aistudio.mediatool.feature.studio.domain.StudioTrack
import com.aistudio.mediatool.feature.studio.domain.StudioTrackType
import kotlin.math.roundToInt

enum class StudioPitchToolMode(val label: String) {
    AUTO_TUNE("Auto-Tune"),
    HARMONY("Tạo bè"),
}

@Composable
internal fun StudioPitchSourceSelector(
    project: StudioProject,
    selectedTrackId: String?,
    enabled: Boolean,
    onSelect: (String) -> Unit,
) {
    val tracks = project.pitchEditableTracks()
    Text("Lớp giọng nguồn", fontWeight = FontWeight.SemiBold)
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        tracks.forEach { track ->
            FilterChip(
                selected = track.id == selectedTrackId,
                onClick = { onSelect(track.id) },
                enabled = enabled,
                label = { Text(track.name.take(28)) },
                modifier = Modifier.semantics {
                    stateDescription = if (track.id == selectedTrackId) "Đang chọn" else "Chưa chọn"
                },
            )
        }
    }
    if (tracks.isEmpty()) {
        Text("Bài chưa có lớp giọng để xử lý.", color = MaterialTheme.colorScheme.error)
    }
}

@Composable
internal fun StudioPitchModeSelector(
    mode: StudioPitchToolMode,
    enabled: Boolean,
    onMode: (StudioPitchToolMode) -> Unit,
) {
    Text("Công cụ", fontWeight = FontWeight.SemiBold)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        StudioPitchToolMode.entries.forEach { option ->
            FilterChip(
                selected = mode == option,
                onClick = { onMode(option) },
                enabled = enabled,
                label = { Text(option.label) },
            )
        }
    }
}

@Composable
internal fun StudioAutoTuneControls(
    strength: Float,
    maxCents: Float,
    enabled: Boolean,
    onStrength: (Float) -> Unit,
    onMaxCents: (Float) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Mức chỉnh: ${(strength * 100).roundToInt()}%", fontWeight = FontWeight.SemiBold)
        Slider(
            value = strength,
            onValueChange = onStrength,
            valueRange = 0.15f..1f,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth().semantics {
                stateDescription = "Mức chỉnh ${(strength * 100).roundToInt()} phần trăm"
            },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(0.35f to "Nhẹ", 0.70f to "Tự nhiên", 1f to "Rõ").forEach { (value, label) ->
                FilterChip(
                    selected = kotlin.math.abs(strength - value) < 0.03f,
                    onClick = { onStrength(value) },
                    enabled = enabled,
                    label = { Text(label) },
                )
            }
        }
        Text("Giới hạn mỗi lần sửa: ${maxCents.roundToInt()} cent", fontWeight = FontWeight.SemiBold)
        Slider(
            value = maxCents,
            onValueChange = onMaxCents,
            valueRange = 50f..300f,
            steps = 24,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth().semantics {
                stateDescription = "Tối đa ${maxCents.roundToInt()} cent"
            },
        )
        Text(
            "Auto-Tune v1 xử lý offline và giữ bản gốc. Mức thấp giữ luyến tự nhiên hơn; mức cao bám tông mạnh hơn.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
internal fun StudioHarmonyControls(
    preset: StudioHarmonyPreset,
    volumeDb: Float,
    pan: Float,
    enabled: Boolean,
    onPreset: (StudioHarmonyPreset) -> Unit,
    onVolume: (Float) -> Unit,
    onPan: (Float) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Kiểu bè", fontWeight = FontWeight.SemiBold)
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            StudioHarmonyPreset.entries.forEach { option ->
                FilterChip(
                    selected = preset == option,
                    onClick = { onPreset(option) },
                    enabled = enabled,
                    label = { Text(option.label) },
                )
            }
        }
        Text("Âm lượng bè: ${volumeDb.roundToInt()} dB", fontWeight = FontWeight.SemiBold)
        Slider(
            value = volumeDb,
            onValueChange = onVolume,
            valueRange = -18f..0f,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth().semantics {
                stateDescription = "Âm lượng bè ${volumeDb.roundToInt()} đề xi ben"
            },
        )
        val panPercent = (pan * 100).roundToInt()
        val panLabel = when {
            panPercent < 0 -> "trái ${-panPercent}%"
            panPercent > 0 -> "phải $panPercent%"
            else -> "giữa"
        }
        Text("Vị trí bè: $panLabel", fontWeight = FontWeight.SemiBold)
        Slider(
            value = pan,
            onValueChange = onPan,
            valueRange = -1f..1f,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth().semantics { stateDescription = "Pan $panLabel" },
        )
        Text(
            "Bè được tính theo bậc của tông bài và tạo thành một lớp Giọng bè độc lập. Giọng chính không bị thay thế.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
internal fun StudioPitchStatus(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.semantics {
            liveRegion = LiveRegionMode.Polite
            stateDescription = text
        },
    )
}

internal fun StudioProject.pitchEditableTracks(): List<StudioTrack> = tracks.filter { track ->
    track.type != StudioTrackType.BEAT && !track.locked && (track.clips.isNotEmpty() || track.takes.isNotEmpty())
}
