package com.aistudio.mediatool.feature.studio.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.aistudio.mediatool.feature.studio.audio.StudioHarmonyPreset

@Composable
internal fun StudioPitchToolsSurface(
    model: StudioPitchUiModel,
    playing: Boolean,
    playingOriginal: Boolean = false,
    onSelectTrack: (String) -> Unit,
    onMode: (StudioPitchToolMode) -> Unit,
    onStrength: (Float) -> Unit,
    onMaxCents: (Float) -> Unit,
    onHarmonyPreset: (StudioHarmonyPreset) -> Unit,
    onHarmonyVolume: (Float) -> Unit,
    onHarmonyPan: (Float) -> Unit,
    onCreatePreview: () -> Unit,
    onToggleOriginal: () -> Unit = {},
    onTogglePreview: () -> Unit,
    onApply: () -> Unit,
    onRestore: () -> Unit,
) {
    val project = model.project ?: return
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        StudioPitchSourceModeBlock(
            project,
            model.selectedTrackId,
            model.mode,
            !model.processing,
            onSelectTrack,
            onMode,
        )
        StudioPitchParameterBlock(
            model.mode,
            model.strength,
            model.maxCents,
            model.harmonyPreset,
            model.harmonyVolume,
            model.harmonyPan,
            !model.processing,
            onStrength,
            onMaxCents,
            onHarmonyPreset,
            onHarmonyVolume,
            onHarmonyPan,
        )
        Button(
            onClick = onCreatePreview,
            enabled = model.canProcess,
            modifier = Modifier.fillMaxWidth(),
        ) { androidx.compose.material3.Text(if (model.processing) "Đang xử lý..." else "Tạo bản nghe thử") }
        if (model.preview != null) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = onToggleOriginal,
                    enabled = !model.processing,
                    modifier = Modifier.weight(1f),
                ) {
                    androidx.compose.material3.Text(if (playing && playingOriginal) "Dừng A" else "A · Giọng gốc")
                }
                OutlinedButton(
                    onClick = onTogglePreview,
                    enabled = !model.processing,
                    modifier = Modifier.weight(1f),
                ) {
                    androidx.compose.material3.Text(if (playing && !playingOriginal) "Dừng B" else "B · Bản xử lý")
                }
            }
            Button(onClick = onApply, enabled = !model.processing, modifier = Modifier.fillMaxWidth()) {
                androidx.compose.material3.Text("Áp dụng")
            }
        }
        if (model.appliedAutoTune != null) {
            OutlinedButton(onClick = onRestore, enabled = !model.processing, modifier = Modifier.fillMaxWidth()) {
                androidx.compose.material3.Text("Khôi phục giọng gốc")
            }
        }
        StudioPitchStatus(model.status)
    }
}
