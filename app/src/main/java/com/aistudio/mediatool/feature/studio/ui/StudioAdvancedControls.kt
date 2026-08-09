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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aistudio.mediatool.core.FileExportManager
import com.aistudio.mediatool.feature.studio.audio.StudioAudioDevice
import com.aistudio.mediatool.feature.studio.audio.StudioInputMode
import com.aistudio.mediatool.feature.studio.audio.StudioSessionRuntime
import com.aistudio.mediatool.feature.studio.audio.StudioSessionState
import com.aistudio.mediatool.feature.studio.domain.StudioProject
import com.aistudio.mediatool.feature.studio.render.StudioExportFormat
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale

@Composable
fun StudioRoutingLatencyCard(
    session: StudioSessionState,
    inputMode: StudioInputMode,
    enabled: Boolean,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Audio Route & Latency", fontWeight = FontWeight.Bold)
            Text(
                "Chọn route ưu tiên. Studio luôn hiển thị device thực tế mà Oboe nhận được và lưu latency riêng cho từng route.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Text("Microphone", style = MaterialTheme.typography.labelLarge)
            DeviceChipRow(
                devices = session.audioDevices.inputs,
                selectedId = session.selectedInputDeviceId,
                enabled = enabled,
                defaultLabel = "Mặc định",
                onSelected = StudioSessionRuntime::selectInputDevice,
            )

            Text("Output", style = MaterialTheme.typography.labelLarge)
            DeviceChipRow(
                devices = session.audioDevices.outputs,
                selectedId = session.selectedOutputDeviceId,
                enabled = enabled,
                defaultLabel = "Mặc định",
                onSelected = StudioSessionRuntime::selectOutputDevice,
            )

            val diagnostics = session.diagnostics
            Text(
                buildString {
                    append("Route thực tế: OUT ")
                    append(diagnostics?.outputDeviceId?.takeIf { it >= 0 } ?: "mặc định")
                    append(" • IN ")
                    append(diagnostics?.inputDeviceId ?: "chưa mở")
                },
                style = MaterialTheme.typography.bodySmall,
            )

            val profile = session.latencyProfile
            if (profile != null) {
                Text(
                    "Compensation ${String.format(Locale.US, "%.1f", profile.milliseconds)} ms • confidence ${(profile.confidence * 100f).toInt()}%",
                    style = MaterialTheme.typography.labelLarge,
                )
            } else {
                Text(
                    "Route này chưa có profile latency. Take vẫn thu được nhưng chưa tự căn offset.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Button(
                onClick = { StudioSessionRuntime.calibrateLatency(inputMode) },
                enabled = enabled,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Hiệu chỉnh tự động") }
            Text(
                "Auto calibration phát một chuỗi click ngắn và nghe lại bằng microphone. Có thể cần đưa nguồn phát gần mic. Fine-tune bên dưới dùng khi route không đo acoustic sạch.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(
                    onClick = { StudioSessionRuntime.adjustLatencyManual(-5.0) },
                    enabled = enabled,
                    modifier = Modifier.weight(1f),
                ) { Text("-5 ms") }
                OutlinedButton(
                    onClick = { StudioSessionRuntime.adjustLatencyManual(-1.0) },
                    enabled = enabled,
                    modifier = Modifier.weight(1f),
                ) { Text("-1") }
                OutlinedButton(
                    onClick = { StudioSessionRuntime.adjustLatencyManual(1.0) },
                    enabled = enabled,
                    modifier = Modifier.weight(1f),
                ) { Text("+1") }
                OutlinedButton(
                    onClick = { StudioSessionRuntime.adjustLatencyManual(5.0) },
                    enabled = enabled,
                    modifier = Modifier.weight(1f),
                ) { Text("+5 ms") }
            }
            OutlinedButton(
                onClick = StudioSessionRuntime::resetLatencyProfile,
                enabled = enabled && profile != null,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Reset latency route này") }
        }
    }
}

@Composable
private fun DeviceChipRow(
    devices: List<StudioAudioDevice>,
    selectedId: Int?,
    enabled: Boolean,
    defaultLabel: String,
    onSelected: (Int?) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilterChip(
            selected = selectedId == null,
            onClick = { onSelected(null) },
            enabled = enabled,
            label = { Text(defaultLabel) },
        )
        devices.forEach { device ->
            FilterChip(
                selected = selectedId == device.id,
                onClick = { onSelected(device.id) },
                enabled = enabled,
                label = { Text("${device.label} #${device.id}") },
            )
        }
    }
}

@Composable
fun StudioMixerCard(
    project: StudioProject,
    enabled: Boolean,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Mixer", fontWeight = FontWeight.Bold)
            Text(
                "Volume, Pan, Mute và Solo cập nhật trực tiếp native monitor. Giá trị này cũng được dùng khi export.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            project.tracks.forEach { track ->
                key(track.id) {
                    var volume by remember(track.id, track.volumeDb) { mutableFloatStateOf(track.volumeDb.coerceIn(-48f, 12f)) }
                    var pan by remember(track.id, track.pan) { mutableFloatStateOf(track.pan.coerceIn(-1f, 1f)) }
                    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(track.name, fontWeight = FontWeight.SemiBold)
                            Text(
                                "${String.format(Locale.US, "%.1f", volume)} dB • ${panLabel(pan)}",
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(
                                selected = track.muted,
                                onClick = { StudioSessionRuntime.toggleTrackMute(track.id) },
                                enabled = enabled,
                                label = { Text("Mute") },
                            )
                            FilterChip(
                                selected = track.solo,
                                onClick = { StudioSessionRuntime.toggleTrackSolo(track.id) },
                                enabled = enabled,
                                label = { Text("Solo") },
                            )
                        }
                        Text("Volume", style = MaterialTheme.typography.labelSmall)
                        Slider(
                            value = volume,
                            onValueChange = { volume = it },
                            onValueChangeFinished = { StudioSessionRuntime.setTrackVolume(track.id, volume) },
                            valueRange = -48f..12f,
                            enabled = enabled,
                        )
                        Text("Pan", style = MaterialTheme.typography.labelSmall)
                        Slider(
                            value = pan,
                            onValueChange = { pan = it },
                            onValueChangeFinished = { StudioSessionRuntime.setTrackPan(track.id, pan) },
                            valueRange = -1f..1f,
                            enabled = enabled,
                        )
                    }
                }
            }

            var masterGain by remember(project.masterMix.gainDb) {
                mutableFloatStateOf(project.masterMix.gainDb.coerceIn(-12f, 6f))
            }
            Text("Master", fontWeight = FontWeight.Bold)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Gain", style = MaterialTheme.typography.labelSmall)
                Text("${String.format(Locale.US, "%.1f", masterGain)} dB", style = MaterialTheme.typography.labelSmall)
            }
            Slider(
                value = masterGain,
                onValueChange = { masterGain = it },
                onValueChangeFinished = { StudioSessionRuntime.setMasterGain(masterGain) },
                valueRange = -12f..6f,
                enabled = enabled,
            )
            FilterChip(
                selected = project.masterMix.limiterEnabled,
                onClick = { StudioSessionRuntime.setMasterLimiter(!project.masterMix.limiterEnabled) },
                enabled = enabled,
                label = { Text("Master Limiter") },
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
    val resultFile = session.exportResultPath?.let(::File)?.takeIf { it.isFile && it.length() > 0L }
    val saveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { destination ->
        val source = resultFile
        if (destination != null && source != null) {
            coroutineScope.launch {
                runCatching { FileExportManager.copyToUri(context, source, destination) }
                    .onSuccess { Toast.makeText(context, "Đã lưu ${source.name}", Toast.LENGTH_SHORT).show() }
                    .onFailure { Toast.makeText(context, it.message ?: "Không thể lưu file", Toast.LENGTH_LONG).show() }
            }
        }
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Export", fontWeight = FontWeight.Bold)
            Text(
                "Final mix dùng chính arrangement và mixer hiện tại. Stems xuất từng track WAV riêng rồi đóng gói ZIP.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { StudioSessionRuntime.exportMix(StudioExportFormat.WAV) },
                    enabled = enabled,
                    modifier = Modifier.weight(1f),
                ) { Text("WAV") }
                Button(
                    onClick = { StudioSessionRuntime.exportMix(StudioExportFormat.M4A) },
                    enabled = enabled,
                    modifier = Modifier.weight(1f),
                ) { Text("M4A") }
                OutlinedButton(
                    onClick = StudioSessionRuntime::exportStems,
                    enabled = enabled,
                    modifier = Modifier.weight(1f),
                ) { Text("Stems ZIP") }
            }

            if (resultFile != null) {
                Text(
                    "${session.exportResultLabel ?: "Kết quả"}: ${resultFile.name} • ${formatBytes(resultFile.length())}",
                    style = MaterialTheme.typography.labelLarge,
                )
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { FileExportManager.shareFile(context, resultFile, "Chia sẻ Studio export") },
                        modifier = Modifier.weight(1f),
                    ) { Text("Chia sẻ") }
                    OutlinedButton(
                        onClick = { saveLauncher.launch(resultFile.name) },
                        modifier = Modifier.weight(1f),
                    ) { Text("Lưu vào...") }
                }
            }
        }
    }
}

private fun panLabel(pan: Float): String = when {
    pan < -0.02f -> "L${(kotlin.math.abs(pan) * 100).toInt()}"
    pan > 0.02f -> "R${(pan * 100).toInt()}"
    else -> "C"
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024L -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
    bytes >= 1024L -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
    else -> "$bytes B"
}
