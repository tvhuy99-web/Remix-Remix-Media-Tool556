package com.aistudio.mediatool.feature.studio.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aistudio.mediatool.feature.studio.audio.StudioAudioDiagnostics
import com.aistudio.mediatool.feature.studio.audio.StudioInputMode
import com.aistudio.mediatool.feature.studio.audio.StudioRecordingKind
import com.aistudio.mediatool.feature.studio.audio.StudioSessionRuntime
import com.aistudio.mediatool.feature.studio.audio.StudioSessionStatus
import com.aistudio.mediatool.feature.studio.domain.StudioClip
import com.aistudio.mediatool.feature.studio.domain.StudioProject
import com.aistudio.mediatool.feature.studio.domain.StudioTrackType
import com.aistudio.mediatool.ui.components.ToolScaffold
import java.util.Locale

@Composable
fun StudioProjectScreen(
    projectId: String,
    onNavigateBack: () -> Unit,
) {
    val context = LocalContext.current
    val session by StudioSessionRuntime.state.collectAsStateWithLifecycle()
    var inputMode by rememberSaveable { mutableStateOf(StudioInputMode.AUTO) }
    var pixelsPerSecond by rememberSaveable { mutableFloatStateOf(58f) }
    var requestedRecordingKind by remember { mutableStateOf(StudioRecordingKind.FULL_TAKE) }

    LaunchedEffect(projectId) {
        StudioSessionRuntime.open(context, projectId)
    }

    fun beginRecordingAfterPermission() {
        when (requestedRecordingKind) {
            StudioRecordingKind.FULL_TAKE -> StudioSessionRuntime.startRecording(mode = inputMode)
            StudioRecordingKind.PUNCH -> StudioSessionRuntime.startPunchRecording(mode = inputMode)
        }
    }

    val micPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) beginRecordingAfterPermission()
        else Toast.makeText(context, "Cần quyền microphone để thu Studio", Toast.LENGTH_SHORT).show()
    }

    fun requestMicrophone() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            beginRecordingAfterPermission()
        } else {
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { _ -> requestMicrophone() }

    fun requestRecording(kind: StudioRecordingKind) {
        requestedRecordingKind = kind
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            requestMicrophone()
        }
    }

    val showingProject = session.projectId == projectId
    val project = session.project.takeIf { showingProject }
    val status = if (showingProject) session.status else StudioSessionStatus.LOADING

    ToolScaffold(
        title = project?.name ?: "Studio",
        onNavigateBack = onNavigateBack,
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (status == StudioSessionStatus.LOADING) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator()
                    Column {
                        Text("Đang chuẩn bị Studio...", fontWeight = FontWeight.SemiBold)
                        session.message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }

            if (status == StudioSessionStatus.ERROR) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text("Không thể mở Studio", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error)
                        Text(session.errorMessage ?: "Lỗi không xác định")
                        Button(onClick = { StudioSessionRuntime.open(context, projectId) }) { Text("Thử lại") }
                    }
                }
            }

            project?.let { loaded ->
                TransportCard(
                    project = loaded,
                    status = status,
                    recordingKind = session.recordingKind,
                    transportFrame = session.transportFrame,
                    durationFrames = session.durationFrames,
                    prepared = session.isPrepared,
                    onRecord = { requestRecording(StudioRecordingKind.FULL_TAKE) },
                )

                Text("Nguồn thu", fontWeight = FontWeight.SemiBold)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    StudioInputMode.entries.forEach { mode ->
                        FilterChip(
                            selected = inputMode == mode,
                            onClick = { inputMode = mode },
                            enabled = status != StudioSessionStatus.RECORDING,
                            label = {
                                Text(
                                    when (mode) {
                                        StudioInputMode.AUTO -> "Tự động"
                                        StudioInputMode.STUDIO_RAW -> "Studio Raw"
                                        StudioInputMode.LIVE_LOW_LATENCY -> "Live"
                                    },
                                )
                            },
                        )
                    }
                }
                Text(
                    when (inputMode) {
                        StudioInputMode.AUTO -> "Ưu tiên đường thu thô, tự fallback sang preset low-latency tương thích nếu thiết bị không hỗ trợ."
                        StudioInputMode.STUDIO_RAW -> "Ưu tiên UNPROCESSED để giữ tín hiệu microphone nguyên bản nhất có thể."
                        StudioInputMode.LIVE_LOW_LATENCY -> "Ưu tiên VOICE_PERFORMANCE/VOICE_RECOGNITION cho phản hồi realtime thấp hơn."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Timeline", fontWeight = FontWeight.Bold)
                    Text("Zoom ${pixelsPerSecond.toInt()} px/s", style = MaterialTheme.typography.labelSmall)
                }
                Slider(
                    value = pixelsPerSecond,
                    onValueChange = { pixelsPerSecond = it },
                    valueRange = 28f..150f,
                    enabled = status != StudioSessionStatus.RECORDING,
                )

                StudioTimeline(
                    project = loaded,
                    waveforms = session.waveforms,
                    transportFrame = session.transportFrame,
                    durationFrames = session.durationFrames,
                    pixelsPerSecond = pixelsPerSecond,
                    selectedClipId = session.selectedClipId,
                    punchStartFrame = session.punchStartFrame,
                    punchEndFrame = session.punchEndFrame,
                    onSeek = StudioSessionRuntime::seek,
                    onClipSelected = StudioSessionRuntime::selectClip,
                )

                EditingCard(
                    project = loaded,
                    selectedClipId = session.selectedClipId,
                    canUndo = session.canUndo,
                    canRedo = session.canRedo,
                    enabled = status != StudioSessionStatus.RECORDING,
                )

                PunchCard(
                    project = loaded,
                    punchStartFrame = session.punchStartFrame,
                    punchEndFrame = session.punchEndFrame,
                    recordingKind = session.recordingKind,
                    status = status,
                    onPunchRecord = { requestRecording(StudioRecordingKind.PUNCH) },
                )

                StudioTakeSelector(
                    project = loaded,
                    onSelectTake = StudioSessionRuntime::selectTake,
                    modifier = Modifier.fillMaxWidth(),
                )

                session.message?.takeIf { it.isNotBlank() }?.let { message ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Text(message, modifier = Modifier.padding(12.dp))
                    }
                }
                session.errorMessage?.let { error ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Text(error, modifier = Modifier.padding(12.dp), color = MaterialTheme.colorScheme.error)
                    }
                }

                session.diagnostics?.let { diagnostics -> AudioDiagnosticsCard(diagnostics) }
            }
        }
    }
}

@Composable
private fun TransportCard(
    project: StudioProject,
    status: StudioSessionStatus,
    recordingKind: StudioRecordingKind?,
    transportFrame: Long,
    durationFrames: Long,
    prepared: Boolean,
    onRecord: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text(
                        formatFrames(transportFrame, project.timelineSampleRate),
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Light,
                    )
                    Text(
                        statusLabel(status, recordingKind),
                        color = if (status == StudioSessionStatus.RECORDING) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
                Text(
                    "${formatFrames(durationFrames, project.timelineSampleRate)} tổng",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { StudioSessionRuntime.seek(0L) },
                    enabled = status != StudioSessionStatus.RECORDING,
                    modifier = Modifier.weight(0.8f),
                ) { Text("⏮") }
                Button(
                    onClick = {
                        if (status == StudioSessionStatus.PLAYING) StudioSessionRuntime.pause() else StudioSessionRuntime.play()
                    },
                    enabled = prepared && status != StudioSessionStatus.RECORDING,
                    modifier = Modifier.weight(1.2f),
                ) { Text(if (status == StudioSessionStatus.PLAYING) "TẠM DỪNG" else "PHÁT") }
                Button(
                    onClick = {
                        if (status == StudioSessionStatus.RECORDING) StudioSessionRuntime.stopRecording() else onRecord()
                    },
                    enabled = prepared,
                    modifier = Modifier.weight(1.2f),
                ) {
                    Text(if (status == StudioSessionStatus.RECORDING) "DỪNG THU" else "● REC")
                }
            }
        }
    }
}

@Composable
private fun EditingCard(
    project: StudioProject,
    selectedClipId: String?,
    canUndo: Boolean,
    canRedo: Boolean,
    enabled: Boolean,
) {
    val vocalTrack = project.tracks.firstOrNull { it.type == StudioTrackType.VOCAL }
    val selectedClip = project.findClip(selectedClipId)
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Text("Editing • Non-destructive", fontWeight = FontWeight.Bold)
            Text(
                "Split, Trim, Move, Gain và Fade chỉ thay metadata. WAV Take gốc không bị sửa.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = StudioSessionRuntime::undo,
                    enabled = enabled && canUndo,
                    modifier = Modifier.weight(1f),
                ) { Text("↶ Undo") }
                OutlinedButton(
                    onClick = StudioSessionRuntime::redo,
                    enabled = enabled && canRedo,
                    modifier = Modifier.weight(1f),
                ) { Text("↷ Redo") }
            }

            if (vocalTrack != null && vocalTrack.takes.isNotEmpty() && vocalTrack.clips.isEmpty()) {
                Button(
                    onClick = { StudioSessionRuntime.beginEditing(vocalTrack.id) },
                    enabled = enabled,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Tạo arrangement từ Take hiện tại") }
            }

            if (selectedClip != null) {
                Text(
                    "Clip đã chọn • Gain ${String.format(Locale.US, "%.1f", selectedClip.gainDb)} dB",
                    style = MaterialTheme.typography.labelLarge,
                )
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Button(onClick = StudioSessionRuntime::splitSelectedAtPlayhead, enabled = enabled, modifier = Modifier.weight(1f)) {
                        Text("Split")
                    }
                    OutlinedButton(onClick = StudioSessionRuntime::trimSelectedStartToPlayhead, enabled = enabled, modifier = Modifier.weight(1f)) {
                        Text("Trim đầu")
                    }
                    OutlinedButton(onClick = StudioSessionRuntime::trimSelectedEndToPlayhead, enabled = enabled, modifier = Modifier.weight(1f)) {
                        Text("Trim cuối")
                    }
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(onClick = { StudioSessionRuntime.moveSelectedByMillis(-100L) }, enabled = enabled, modifier = Modifier.weight(1f)) {
                        Text("← 100ms")
                    }
                    OutlinedButton(onClick = { StudioSessionRuntime.moveSelectedByMillis(100L) }, enabled = enabled, modifier = Modifier.weight(1f)) {
                        Text("100ms →")
                    }
                    OutlinedButton(onClick = StudioSessionRuntime::deleteSelectedClip, enabled = enabled, modifier = Modifier.weight(1f)) {
                        Text("Xóa")
                    }
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(onClick = { StudioSessionRuntime.adjustSelectedGain(-1f) }, enabled = enabled, modifier = Modifier.weight(1f)) {
                        Text("Gain -1")
                    }
                    OutlinedButton(onClick = { StudioSessionRuntime.adjustSelectedGain(1f) }, enabled = enabled, modifier = Modifier.weight(1f)) {
                        Text("Gain +1")
                    }
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(onClick = { StudioSessionRuntime.adjustSelectedFadeIn(-50L) }, enabled = enabled, modifier = Modifier.weight(1f)) {
                        Text("Fade In -")
                    }
                    OutlinedButton(onClick = { StudioSessionRuntime.adjustSelectedFadeIn(50L) }, enabled = enabled, modifier = Modifier.weight(1f)) {
                        Text("Fade In +")
                    }
                    OutlinedButton(onClick = { StudioSessionRuntime.adjustSelectedFadeOut(-50L) }, enabled = enabled, modifier = Modifier.weight(1f)) {
                        Text("Fade Out -")
                    }
                    OutlinedButton(onClick = { StudioSessionRuntime.adjustSelectedFadeOut(50L) }, enabled = enabled, modifier = Modifier.weight(1f)) {
                        Text("Fade Out +")
                    }
                }
            } else if (vocalTrack?.clips?.isNotEmpty() == true) {
                Text("Chạm một clip vocal trên Timeline để chỉnh sửa.", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun PunchCard(
    project: StudioProject,
    punchStartFrame: Long?,
    punchEndFrame: Long?,
    recordingKind: StudioRecordingKind?,
    status: StudioSessionStatus,
    onPunchRecord: () -> Unit,
) {
    val hasVocal = project.tracks.any { it.type == StudioTrackType.VOCAL && it.takes.isNotEmpty() }
    val validRange = punchStartFrame != null && punchEndFrame != null && punchEndFrame > punchStartFrame
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Text("Overdub & Punch", fontWeight = FontWeight.Bold)
            Text(
                "Overdub nghe beat + arrangement hiện tại khi thu. Punch có pre-roll 3 giây, mute vocal cũ trong vùng chọn và tự dừng ở Punch Out.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "IN ${punchStartFrame?.let { formatFrames(it, project.timelineSampleRate) } ?: "--:--.---"}   •   OUT ${punchEndFrame?.let { formatFrames(it, project.timelineSampleRate) } ?: "--:--.---"}",
                style = MaterialTheme.typography.labelLarge,
            )
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(
                    onClick = StudioSessionRuntime::setPunchStartAtPlayhead,
                    enabled = status != StudioSessionStatus.RECORDING,
                    modifier = Modifier.weight(1f),
                ) { Text("Đặt Punch In") }
                OutlinedButton(
                    onClick = StudioSessionRuntime::setPunchEndAtPlayhead,
                    enabled = status != StudioSessionStatus.RECORDING,
                    modifier = Modifier.weight(1f),
                ) { Text("Đặt Punch Out") }
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(
                    onClick = StudioSessionRuntime::clearPunchRange,
                    enabled = status != StudioSessionStatus.RECORDING,
                    modifier = Modifier.weight(1f),
                ) { Text("Xóa vùng") }
                Button(
                    onClick = {
                        if (status == StudioSessionStatus.RECORDING && recordingKind == StudioRecordingKind.PUNCH) {
                            StudioSessionRuntime.stopRecording()
                        } else {
                            onPunchRecord()
                        }
                    },
                    enabled = hasVocal && (validRange || recordingKind == StudioRecordingKind.PUNCH),
                    modifier = Modifier.weight(1f),
                ) {
                    Text(if (recordingKind == StudioRecordingKind.PUNCH) "DỪNG PUNCH" else "● PUNCH REC")
                }
            }
        }
    }
}

@Composable
private fun AudioDiagnosticsCard(diagnostics: StudioAudioDiagnostics) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text("Audio diagnostics", fontWeight = FontWeight.SemiBold)
            Text(
                "${diagnostics.audioApiLabel} • ${diagnostics.performanceModeLabel} • ${diagnostics.sharingModeLabel}",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "Output ${diagnostics.sampleRate} Hz • ${diagnostics.bufferSizeFrames} frames • ${String.format(Locale.US, "%.1f", diagnostics.approximateBufferMs)} ms buffer",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "Monitor arrangement: ${diagnostics.arrangementClipCount} clip • ${formatFrames(diagnostics.arrangementDurationFrames, 48_000)}",
                style = MaterialTheme.typography.bodySmall,
            )
            diagnostics.inputSampleRate?.let { rate ->
                Text("Mic $rate Hz • device ${diagnostics.inputDeviceId ?: "mặc định"}", style = MaterialTheme.typography.bodySmall)
            }
            if (diagnostics.ringOverrunFrames > 0L) {
                Text(
                    "Dropped input frames: ${diagnostics.ringOverrunFrames}",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

private fun statusLabel(status: StudioSessionStatus, kind: StudioRecordingKind?): String = when (status) {
    StudioSessionStatus.CLOSED -> "Đã đóng"
    StudioSessionStatus.LOADING -> "Đang chuẩn bị"
    StudioSessionStatus.READY -> "Sẵn sàng"
    StudioSessionStatus.PLAYING -> "Đang phát arrangement"
    StudioSessionStatus.RECORDING -> if (kind == StudioRecordingKind.PUNCH) "Đang Punch" else "Đang Overdub / thu vocal"
    StudioSessionStatus.ERROR -> "Lỗi"
}

private fun StudioProject.findClip(id: String?): StudioClip? {
    if (id == null) return null
    return tracks.asSequence().flatMap { it.clips.asSequence() }.firstOrNull { it.id == id }
}

private fun formatFrames(frame: Long, sampleRate: Int): String {
    val safeRate = sampleRate.coerceAtLeast(1)
    val totalMillis = frame.coerceAtLeast(0L) * 1_000L / safeRate
    val minutes = totalMillis / 60_000L
    val seconds = (totalMillis / 1_000L) % 60L
    val millis = totalMillis % 1_000L
    return String.format(Locale.US, "%02d:%02d.%03d", minutes, seconds, millis)
}
