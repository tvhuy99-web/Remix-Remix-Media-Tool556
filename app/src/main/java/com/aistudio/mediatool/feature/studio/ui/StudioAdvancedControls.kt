package com.aistudio.mediatool.feature.studio.ui

import android.content.Context
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aistudio.mediatool.core.FileExportManager
import com.aistudio.mediatool.feature.studio.audio.StudioAudioDevice
import com.aistudio.mediatool.feature.studio.audio.StudioInputMode
import com.aistudio.mediatool.feature.studio.audio.StudioSessionRuntime
import com.aistudio.mediatool.feature.studio.audio.StudioSessionState
import com.aistudio.mediatool.feature.studio.data.StudioRecordingTargetRequests
import com.aistudio.mediatool.feature.studio.domain.StudioProject
import com.aistudio.mediatool.feature.studio.domain.StudioTrack
import com.aistudio.mediatool.feature.studio.domain.StudioTrackType
import com.aistudio.mediatool.feature.studio.render.StudioExportFormat
import java.io.File
import java.util.Locale
import kotlinx.coroutines.launch

@Composable
fun StudioRoutingLatencyCard(
    session: StudioSessionState,
    inputMode: StudioInputMode,
    enabled: Boolean,
) {
    var showFineTune by rememberSaveable { mutableStateOf(false) }
    var nextRecordingRole by rememberSaveable {
        mutableStateOf(StudioRecordingTargetRequests.nextNewLayerRole())
    }
    val profile = session.latencyProfile

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "Mic, tai nghe & căn tiếng",
                fontWeight = FontWeight.Bold,
                modifier = Modifier.semantics { heading() },
            )

            StudioRecordingRolePanel(
                selectedRole = nextRecordingRole,
                enabled = enabled,
                onSelected = { role ->
                    nextRecordingRole = role
                    StudioRecordingTargetRequests.setNextNewLayerRole(role)
                },
            )

            session.project?.let { project ->
                StudioWorkingTrackPanel(
                    project = project,
                    selectedClipId = session.selectedClipId,
                    enabled = enabled,
                    syncSelectedClip = true,
                )
            }

            Text("Mic dùng để thu", style = MaterialTheme.typography.labelLarge)
            DeviceChipRow(
                devices = session.audioDevices.inputs,
                selectedId = session.selectedInputDeviceId,
                enabled = enabled,
                onSelected = StudioSessionRuntime::selectInputDevice,
            )

            Text("Thiết bị để nghe", style = MaterialTheme.typography.labelLarge)
            DeviceChipRow(
                devices = session.audioDevices.outputs,
                selectedId = session.selectedOutputDeviceId,
                enabled = enabled,
                onSelected = StudioSessionRuntime::selectOutputDevice,
            )

            Text(
                if (profile == null) {
                    "Chưa căn tiếng cho cặp thiết bị này"
                } else {
                    "Đã căn khoảng ${String.format(Locale.US, "%.1f", profile.milliseconds)} mili giây"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Button(
                onClick = { StudioSessionRuntime.calibrateLatency(inputMode) },
                enabled = enabled,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Căn tiếng tự động")
            }

            OutlinedButton(
                onClick = { showFineTune = !showFineTune },
                enabled = enabled,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (showFineTune) "Ẩn chỉnh tay" else "Chỉnh tay")
            }

            if (showFineTune) {
                Text(
                    "Nếu giọng nghe sớm hoặc muộn so với nhạc, chỉnh từng bước nhỏ.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = { StudioSessionRuntime.adjustLatencyManual(-5.0) },
                        enabled = enabled,
                        modifier = Modifier.weight(1f),
                    ) { Text("Muộn 5 ms") }
                    OutlinedButton(
                        onClick = { StudioSessionRuntime.adjustLatencyManual(-1.0) },
                        enabled = enabled,
                        modifier = Modifier.weight(1f),
                    ) { Text("Muộn 1 ms") }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = { StudioSessionRuntime.adjustLatencyManual(1.0) },
                        enabled = enabled,
                        modifier = Modifier.weight(1f),
                    ) { Text("Sớm 1 ms") }
                    OutlinedButton(
                        onClick = { StudioSessionRuntime.adjustLatencyManual(5.0) },
                        enabled = enabled,
                        modifier = Modifier.weight(1f),
                    ) { Text("Sớm 5 ms") }
                }
                OutlinedButton(
                    onClick = StudioSessionRuntime::resetLatencyProfile,
                    enabled = enabled && profile != null,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Bỏ căn chỉnh")
                }
            }
        }
    }
}

@Composable
private fun DeviceChipRow(
    devices: List<StudioAudioDevice>,
    selectedId: Int?,
    enabled: Boolean,
    onSelected: (Int?) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilterChip(
            selected = selectedId == null,
            onClick = { onSelected(null) },
            enabled = enabled,
            label = { Text("Tự động") },
        )
        devices.forEach { device ->
            FilterChip(
                selected = selectedId == device.id,
                onClick = { onSelected(device.id) },
                enabled = enabled,
                label = { Text(friendlyDeviceLabel(device)) },
            )
        }
    }
}

@Composable
fun StudioMixerCard(
    project: StudioProject,
    enabled: Boolean,
    trackEditingEnabled: Boolean = enabled,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                "Cân âm",
                fontWeight = FontWeight.Bold,
                modifier = Modifier.semantics { heading() },
            )

            StudioWorkingTrackPanel(
                project = project,
                selectedClipId = null,
                enabled = trackEditingEnabled,
                syncSelectedClip = false,
            )

            project.tracks.forEach { track ->
                key(track.id) {
                    var volume by remember(track.id, track.volumeDb) {
                        mutableFloatStateOf(track.volumeDb.coerceIn(-48f, 12f))
                    }
                    var pan by remember(track.id, track.pan) {
                        mutableFloatStateOf(track.pan.coerceIn(-1f, 1f))
                    }
                    var showManagement by rememberSaveable(track.id) { mutableStateOf(false) }
                    var editName by rememberSaveable(track.id, track.name) { mutableStateOf(track.name) }
                    var confirmDelete by rememberSaveable(track.id) { mutableStateOf(false) }
                    val name = friendlyTrackName(track)

                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(name, fontWeight = FontWeight.SemiBold)

                        if (track.type != StudioTrackType.BEAT) {
                            OutlinedButton(
                                onClick = { showManagement = !showManagement },
                                enabled = trackEditingEnabled,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(if (showManagement) "Ẩn quản lý lớp" else "Quản lý lớp")
                            }

                            if (showManagement) {
                                Text("Vai trò", style = MaterialTheme.typography.labelLarge)
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    TrackRoleChip("Chính", StudioTrackType.VOCAL, track, trackEditingEnabled)
                                    TrackRoleChip("Bè", StudioTrackType.BACKING_VOCAL, track, trackEditingEnabled)
                                    TrackRoleChip("Phụ", StudioTrackType.ADLIB, track, trackEditingEnabled)
                                    TrackRoleChip("Song ca / khác", StudioTrackType.OTHER, track, trackEditingEnabled)
                                }

                                OutlinedTextField(
                                    value = editName,
                                    onValueChange = { editName = it.take(48) },
                                    label = { Text("Tên lớp") },
                                    singleLine = true,
                                    enabled = trackEditingEnabled,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                Button(
                                    onClick = { StudioSessionRuntime.renameTrack(track.id, editName) },
                                    enabled = trackEditingEnabled && editName.isNotBlank() && editName.trim() != track.name,
                                    modifier = Modifier.fillMaxWidth(),
                                ) { Text("Lưu tên lớp") }

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    OutlinedButton(
                                        onClick = { StudioSessionRuntime.moveTrack(track.id, -1) },
                                        enabled = trackEditingEnabled,
                                        modifier = Modifier.weight(1f),
                                    ) { Text("Đưa lên") }
                                    OutlinedButton(
                                        onClick = { StudioSessionRuntime.moveTrack(track.id, 1) },
                                        enabled = trackEditingEnabled,
                                        modifier = Modifier.weight(1f),
                                    ) { Text("Đưa xuống") }
                                }
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    OutlinedButton(
                                        onClick = { StudioSessionRuntime.duplicateTrack(track.id) },
                                        enabled = trackEditingEnabled,
                                        modifier = Modifier.weight(1f),
                                    ) { Text("Nhân bản") }
                                    OutlinedButton(
                                        onClick = { confirmDelete = true },
                                        enabled = trackEditingEnabled,
                                        modifier = Modifier.weight(1f),
                                    ) { Text("Xóa lớp") }
                                }
                            }

                            if (confirmDelete) {
                                AlertDialog(
                                    onDismissRequest = { confirmDelete = false },
                                    title = { Text("Xóa lớp âm thanh?") },
                                    text = { Text("File thu gốc vẫn được giữ trong dự án và thao tác này có thể Hoàn tác.") },
                                    confirmButton = {
                                        Button(onClick = {
                                            confirmDelete = false
                                            StudioSessionRuntime.deleteTrack(track.id)
                                        }) { Text("Xóa lớp") }
                                    },
                                    dismissButton = {
                                        OutlinedButton(onClick = { confirmDelete = false }) { Text("Giữ lại") }
                                    },
                                )
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            FilterChip(
                                selected = track.muted,
                                onClick = { StudioSessionRuntime.toggleTrackMute(track.id) },
                                enabled = enabled,
                                label = { Text("Tắt tiếng") },
                                modifier = Modifier.weight(1f),
                            )
                            FilterChip(
                                selected = track.solo,
                                onClick = { StudioSessionRuntime.toggleTrackSolo(track.id) },
                                enabled = enabled,
                                label = { Text("Nghe riêng") },
                                modifier = Modifier.weight(1f),
                            )
                        }

                        Text(
                            "Âm lượng: ${volumeLabel(volume)}",
                            style = MaterialTheme.typography.labelMedium,
                        )
                        Slider(
                            value = volume,
                            onValueChange = { volume = it },
                            onValueChangeFinished = { StudioSessionRuntime.setTrackVolume(track.id, volume) },
                            valueRange = -48f..12f,
                            enabled = enabled,
                            modifier = Modifier.semantics {
                                contentDescription = "Âm lượng của $name"
                                stateDescription = volumeLabel(volume)
                            },
                        )

                        Text(
                            "Vị trí: ${panLabel(pan)}",
                            style = MaterialTheme.typography.labelMedium,
                        )
                        Slider(
                            value = pan,
                            onValueChange = { pan = it },
                            onValueChangeFinished = { StudioSessionRuntime.setTrackPan(track.id, pan) },
                            valueRange = -1f..1f,
                            enabled = enabled,
                            modifier = Modifier.semantics {
                                contentDescription = "Vị trí trái phải của $name"
                                stateDescription = panLabel(pan)
                            },
                        )
                    }
                }
            }

            var masterGain by remember(project.masterMix.gainDb) {
                mutableFloatStateOf(project.masterMix.gainDb.coerceIn(-12f, 6f))
            }
            Text("Toàn bài", fontWeight = FontWeight.Bold)
            Text(
                "Âm lượng: ${volumeLabel(masterGain)}",
                style = MaterialTheme.typography.labelMedium,
            )
            Slider(
                value = masterGain,
                onValueChange = { masterGain = it },
                onValueChangeFinished = { StudioSessionRuntime.setMasterGain(masterGain) },
                valueRange = -12f..6f,
                enabled = enabled,
                modifier = Modifier.semantics {
                    contentDescription = "Âm lượng toàn bài"
                    stateDescription = volumeLabel(masterGain)
                },
            )
            FilterChip(
                selected = project.masterMix.limiterEnabled,
                onClick = { StudioSessionRuntime.setMasterLimiter(!project.masterMix.limiterEnabled) },
                enabled = enabled,
                label = { Text("Chống vỡ tiếng") },
            )
        }
    }
}

@Composable
fun StudioExportCard(
    context: Context,
    project: StudioProject,
    session: StudioSessionState,
    enabled: Boolean,
) {
    val coroutineScope = rememberCoroutineScope()
    val resultFile = session.exportResultPath
        ?.let(::File)
        ?.takeIf { it.isFile && it.length() > 0L }
    val saveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { destination ->
        val source = resultFile
        if (destination != null && source != null) {
            coroutineScope.launch {
                runCatching { FileExportManager.copyToUri(context, source, destination) }
                    .onSuccess {
                        Toast.makeText(context, "Đã lưu ${source.name}", Toast.LENGTH_SHORT).show()
                    }
                    .onFailure {
                        Toast.makeText(context, it.message ?: "Không thể lưu tệp", Toast.LENGTH_LONG).show()
                    }
            }
        }
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                "Xuất bài",
                fontWeight = FontWeight.Bold,
                modifier = Modifier.semantics { heading() },
            )

            Button(
                onClick = { StudioSessionRuntime.exportMix(StudioExportFormat.WAV) },
                enabled = enabled,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Chất lượng cao · WAV")
            }
            OutlinedButton(
                onClick = { StudioSessionRuntime.exportMix(StudioExportFormat.M4A) },
                enabled = enabled,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Dung lượng gọn · M4A")
            }
            OutlinedButton(
                onClick = StudioSessionRuntime::exportStems,
                enabled = enabled,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Xuất từng lớp âm thanh · ZIP")
            }

            if (resultFile != null) {
                Text(
                    "Bản xuất đã sẵn sàng · ${formatBytes(resultFile.length())}",
                    style = MaterialTheme.typography.labelLarge,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        onClick = {
                            FileExportManager.shareFile(
                                context,
                                resultFile,
                                "Chia sẻ ${project.name}",
                            )
                        },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Chia sẻ")
                    }
                    OutlinedButton(
                        onClick = { saveLauncher.launch(resultFile.name) },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Lưu vào máy")
                    }
                }
            }
        }
    }
}

private fun friendlyDeviceLabel(device: StudioAudioDevice): String = device.label
    .replace("Bluetooth SCO", "Bluetooth")
    .replace("Bluetooth A2DP", "Bluetooth")
    .replace("Bluetooth LE Headset", "Tai nghe Bluetooth")
    .replace("Bluetooth LE Speaker", "Loa Bluetooth")
    .replace("USB Audio", "USB")
    .replace("USB Headset", "Tai nghe USB")
    .ifBlank { "Thiết bị âm thanh" }

@Composable
private fun TrackRoleChip(
    label: String,
    type: StudioTrackType,
    track: StudioTrack,
    enabled: Boolean,
) {
    FilterChip(
        selected = track.type == type,
        onClick = { StudioSessionRuntime.setTrackRole(track.id, type) },
        enabled = enabled,
        label = { Text(label) },
    )
}

private fun friendlyTrackName(track: StudioTrack): String = when {
    track.type == StudioTrackType.BEAT -> "Nhạc nền"
    track.name.isNotBlank() && !track.name.equals("Vocal", ignoreCase = true) -> track.name
    track.type == StudioTrackType.VOCAL -> "Giọng chính"
    track.type == StudioTrackType.BACKING_VOCAL -> "Giọng bè"
    track.type == StudioTrackType.ADLIB -> "Giọng phụ"
    track.type == StudioTrackType.INSTRUMENT -> "Nhạc cụ"
    else -> "Âm thanh khác"
}

private fun volumeLabel(value: Float): String = when {
    value <= -36f -> "rất nhỏ"
    value <= -14f -> "nhỏ"
    value <= -4f -> "hơi nhỏ"
    value < 4f -> "bình thường"
    value < 9f -> "lớn"
    else -> "rất lớn"
}

private fun panLabel(pan: Float): String = when {
    pan < -0.05f -> "trái ${(kotlin.math.abs(pan) * 100).toInt()}%"
    pan > 0.05f -> "phải ${(pan * 100).toInt()}%"
    else -> "ở giữa"
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024L -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
    bytes >= 1024L -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
    else -> "$bytes B"
}
