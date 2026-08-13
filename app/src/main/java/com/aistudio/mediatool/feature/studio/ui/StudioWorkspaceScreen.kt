package com.aistudio.mediatool.feature.studio.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aistudio.mediatool.feature.studio.audio.StudioInputMode
import com.aistudio.mediatool.feature.studio.audio.StudioRecordingKind
import com.aistudio.mediatool.feature.studio.audio.StudioSessionRuntime
import com.aistudio.mediatool.feature.studio.audio.StudioSessionStatus
import com.aistudio.mediatool.feature.studio.data.StudioRecordingTargetRequests
import com.aistudio.mediatool.feature.studio.domain.StudioClip
import com.aistudio.mediatool.feature.studio.domain.StudioProject
import com.aistudio.mediatool.feature.studio.domain.StudioTakeStatus
import com.aistudio.mediatool.feature.studio.domain.StudioTrackType
import com.aistudio.mediatool.ui.components.ToolScaffold
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun StudioWorkspaceScreen(
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
    LaunchedEffect(session.inputMode) {
        inputMode = session.inputMode
    }

    fun beginRecordingAfterPermission() {
        when (requestedRecordingKind) {
            StudioRecordingKind.FULL_TAKE -> {
                StudioRecordingTargetRequests.requestNewLayer()
                StudioSessionRuntime.startRecording(mode = inputMode)
            }
            StudioRecordingKind.PUNCH -> {
                val selectedTrackId = session.project?.tracks
                    ?.firstOrNull { track ->
                        session.selectedClipId != null &&
                            track.clips.any { it.id == session.selectedClipId }
                    }
                    ?.id
                    ?: session.project?.tracks?.firstOrNull { it.type == StudioTrackType.VOCAL }?.id
                StudioRecordingTargetRequests.requestExistingTrack(selectedTrackId)
                StudioSessionRuntime.startPunchRecording(mode = inputMode)
            }
        }
    }

    val micPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            beginRecordingAfterPermission()
        } else {
            Toast.makeText(context, "Cần quyền dùng mic để thu giọng", Toast.LENGTH_SHORT).show()
        }
    }

    fun requestMicrophone() {
        if (ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO,
            ) == PackageManager.PERMISSION_GRANTED
        ) {
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
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            requestMicrophone()
        }
    }

    val showingProject = session.projectId == projectId
    val project = session.project.takeIf { showingProject }
    val status = if (showingProject) session.status else StudioSessionStatus.LOADING
    val editEnabled = status != StudioSessionStatus.RECORDING && !session.isBusy
    val routingEnabled = editEnabled && status != StudioSessionStatus.LOADING && status != StudioSessionStatus.ERROR
    val mixerEnabled = !session.isBusy && status != StudioSessionStatus.LOADING && status != StudioSessionStatus.ERROR
    val exportEnabled = status == StudioSessionStatus.READY || status == StudioSessionStatus.PLAYING

    ToolScaffold(
        title = project?.name ?: "Phòng thu",
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
            if (status == StudioSessionStatus.LOADING || session.isBusy) {
                BusyCard(status, session.message)
            }

            if (status == StudioSessionStatus.ERROR) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(
                            "Không thể mở bài",
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.error,
                        )
                        Text(friendlyMessage(session.errorMessage ?: "Có lỗi xảy ra"))
                        Button(onClick = { StudioSessionRuntime.open(context, projectId) }) {
                            Text("Thử lại")
                        }
                    }
                }
            }

            project?.let { loaded ->
                TransportPanel(
                    project = loaded,
                    status = status,
                    recordingKind = session.recordingKind,
                    transportFrame = session.transportFrame,
                    durationFrames = session.durationFrames,
                    prepared = session.isPrepared,
                    onRecord = { requestRecording(StudioRecordingKind.FULL_TAKE) },
                )

                RecordingModePanel(
                    selectedMode = inputMode,
                    enabled = routingEnabled,
                    onSelected = { mode ->
                        inputMode = mode
                        StudioSessionRuntime.setInputMode(mode)
                    },
                )

                StudioRoutingLatencyCard(
                    session = session,
                    inputMode = inputMode,
                    enabled = routingEnabled,
                )

                TimelinePanel(
                    project = loaded,
                    session = session,
                    pixelsPerSecond = pixelsPerSecond,
                    onZoomChanged = { pixelsPerSecond = it },
                    enabled = editEnabled,
                )

                EditPanel(
                    project = loaded,
                    selectedClipId = session.selectedClipId,
                    canUndo = session.canUndo,
                    canRedo = session.canRedo,
                    enabled = editEnabled,
                )

                RetakePanel(
                    project = loaded,
                    punchStartFrame = session.punchStartFrame,
                    punchEndFrame = session.punchEndFrame,
                    recordingKind = session.recordingKind,
                    status = status,
                    enabled = !session.isBusy,
                    onRecord = { requestRecording(StudioRecordingKind.PUNCH) },
                )

                TakesPanel(project = loaded, enabled = editEnabled)

                StudioMixerCard(project = loaded, enabled = mixerEnabled)

                StudioExportCard(
                    context = context,
                    project = loaded,
                    session = session,
                    enabled = exportEnabled,
                )

                session.message
                    ?.takeIf { it.isNotBlank() && !session.isBusy }
                    ?.let { message ->
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                friendlyMessage(message),
                                modifier = Modifier.padding(14.dp),
                            )
                        }
                    }
                session.errorMessage?.let { error ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            friendlyMessage(error),
                            modifier = Modifier.padding(14.dp),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }

                if ((session.diagnostics?.ringOverrunFrames ?: 0L) > 0L) {
                    Text(
                        "Thiết bị đang quá tải. Bản thu có thể bị hụt tiếng.",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

@Composable
private fun BusyCard(status: StudioSessionStatus, message: String?) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator()
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    when (status) {
                        StudioSessionStatus.CALIBRATING -> "Đang căn tiếng..."
                        StudioSessionStatus.RENDERING -> "Đang xuất bài..."
                        else -> "Đang mở bài..."
                    },
                    fontWeight = FontWeight.SemiBold,
                )
                message?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        friendlyMessage(it),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun TransportPanel(
    project: StudioProject,
    status: StudioSessionStatus,
    recordingKind: StudioRecordingKind?,
    transportFrame: Long,
    durationFrames: Long,
    prepared: Boolean,
    onRecord: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        formatFrames(transportFrame, project.timelineSampleRate),
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Light,
                    )
                    Text(
                        friendlyStatus(status, recordingKind),
                        color = if (status == StudioSessionStatus.RECORDING) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.primary
                        },
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
                Text(
                    "Tổng ${formatFrames(durationFrames, project.timelineSampleRate)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = { StudioSessionRuntime.seek(0L) },
                    enabled = prepared && status != StudioSessionStatus.RECORDING,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Về đầu")
                }
                Button(
                    onClick = {
                        if (status == StudioSessionStatus.PLAYING) {
                            StudioSessionRuntime.pause()
                        } else {
                            StudioSessionRuntime.play()
                        }
                    },
                    enabled = prepared && status != StudioSessionStatus.RECORDING,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(if (status == StudioSessionStatus.PLAYING) "Tạm dừng" else "Nghe thử")
                }
            }

            Button(
                onClick = {
                    if (status == StudioSessionStatus.RECORDING) {
                        StudioSessionRuntime.stopRecording()
                    } else {
                        onRecord()
                    }
                },
                enabled = prepared,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (status == StudioSessionStatus.RECORDING) "Dừng thu" else "Thu giọng")
            }
        }
    }
}

@Composable
private fun RecordingModePanel(
    selectedMode: StudioInputMode,
    enabled: Boolean,
    onSelected: (StudioInputMode) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                "Cách thu",
                fontWeight = FontWeight.Bold,
                modifier = Modifier.semantics { heading() },
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                StudioInputMode.entries.forEach { mode ->
                    FilterChip(
                        selected = selectedMode == mode,
                        onClick = { onSelected(mode) },
                        enabled = enabled,
                        label = { Text(inputModeLabel(mode)) },
                    )
                }
            }
            Text(
                when (selectedMode) {
                    StudioInputMode.AUTO -> "Phù hợp cho hầu hết điện thoại và tai nghe."
                    StudioInputMode.STUDIO_RAW -> "Giữ giọng ít xử lý nhất có thể."
                    StudioInputMode.LIVE_LOW_LATENCY -> "Ưu tiên phản hồi nhanh khi đang thu."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun TimelinePanel(
    project: StudioProject,
    session: com.aistudio.mediatool.feature.studio.audio.StudioSessionState,
    pixelsPerSecond: Float,
    onZoomChanged: (Float) -> Unit,
    enabled: Boolean,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "Dòng thời gian",
            fontWeight = FontWeight.Bold,
            modifier = Modifier.semantics { heading() },
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(
                onClick = {
                    StudioSessionRuntime.seek(
                        (session.transportFrame - project.timelineSampleRate * 5L).coerceAtLeast(0L),
                    )
                },
                enabled = enabled,
                modifier = Modifier.weight(1f),
            ) { Text("Lùi 5 giây") }
            Text(
                formatFrames(session.transportFrame, project.timelineSampleRate),
                style = MaterialTheme.typography.labelLarge,
            )
            OutlinedButton(
                onClick = {
                    StudioSessionRuntime.seek(
                        (session.transportFrame + project.timelineSampleRate * 5L)
                            .coerceAtMost(session.durationFrames),
                    )
                },
                enabled = enabled,
                modifier = Modifier.weight(1f),
            ) { Text("Tiến 5 giây") }
        }

        Text("Thu phóng", style = MaterialTheme.typography.labelMedium)
        Slider(
            value = pixelsPerSecond,
            onValueChange = onZoomChanged,
            valueRange = 28f..150f,
            enabled = enabled,
            modifier = Modifier.semantics {
                contentDescription = "Thu phóng dòng thời gian"
                stateDescription = "${zoomPercent(pixelsPerSecond)} phần trăm"
            },
        )

        Box(
            modifier = Modifier.semantics {
                contentDescription =
                    "Sóng âm của bài hát. Có thể chạm để chọn vị trí. Khi dùng trình đọc màn hình, dùng các nút lùi, tiến và danh sách đoạn bên dưới."
            },
        ) {
            StudioTimeline(
                project = project,
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
        }
    }
}

@Composable
private fun EditPanel(
    project: StudioProject,
    selectedClipId: String?,
    canUndo: Boolean,
    canRedo: Boolean,
    enabled: Boolean,
) {
    val vocalTrack = project.tracks.firstOrNull { it.type == StudioTrackType.VOCAL }
    val clips = project.tracks
        .filter { it.type != StudioTrackType.BEAT }
        .flatMap { it.clips }
    val selectedClip = project.findClip(selectedClipId)

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                "Chỉnh đoạn thu",
                fontWeight = FontWeight.Bold,
                modifier = Modifier.semantics { heading() },
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = StudioSessionRuntime::undo,
                    enabled = enabled && canUndo,
                    modifier = Modifier.weight(1f),
                ) { Text("Hoàn tác") }
                OutlinedButton(
                    onClick = StudioSessionRuntime::redo,
                    enabled = enabled && canRedo,
                    modifier = Modifier.weight(1f),
                ) { Text("Làm lại") }
            }

            if (vocalTrack != null && vocalTrack.takes.isNotEmpty() && vocalTrack.clips.isEmpty()) {
                Button(
                    onClick = { StudioSessionRuntime.beginEditing(vocalTrack.id) },
                    enabled = enabled,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Bắt đầu chỉnh bản thu")
                }
            }

            if (clips.isNotEmpty()) {
                Text("Chọn đoạn", style = MaterialTheme.typography.labelLarge)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    clips.forEachIndexed { index, clip ->
                        FilterChip(
                            selected = clip.id == selectedClipId,
                            onClick = { StudioSessionRuntime.selectClip(clip.id) },
                            enabled = enabled,
                            label = { Text("Đoạn ${index + 1}") },
                        )
                    }
                }
            }

            if (selectedClip != null) {
                Text("Đoạn đã chọn", fontWeight = FontWeight.SemiBold)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        onClick = StudioSessionRuntime::splitSelectedAtPlayhead,
                        enabled = enabled,
                        modifier = Modifier.weight(1f),
                    ) { Text("Tách tại vị trí") }
                    OutlinedButton(
                        onClick = StudioSessionRuntime::deleteSelectedClip,
                        enabled = enabled,
                        modifier = Modifier.weight(1f),
                    ) { Text("Xóa đoạn") }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = StudioSessionRuntime::trimSelectedStartToPlayhead,
                        enabled = enabled,
                        modifier = Modifier.weight(1f),
                    ) { Text("Bỏ phần trước") }
                    OutlinedButton(
                        onClick = StudioSessionRuntime::trimSelectedEndToPlayhead,
                        enabled = enabled,
                        modifier = Modifier.weight(1f),
                    ) { Text("Bỏ phần sau") }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = { StudioSessionRuntime.moveSelectedByMillis(-100L) },
                        enabled = enabled,
                        modifier = Modifier.weight(1f),
                    ) { Text("Lùi đoạn") }
                    OutlinedButton(
                        onClick = { StudioSessionRuntime.moveSelectedByMillis(100L) },
                        enabled = enabled,
                        modifier = Modifier.weight(1f),
                    ) { Text("Tiến đoạn") }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = { StudioSessionRuntime.adjustSelectedGain(-1f) },
                        enabled = enabled,
                        modifier = Modifier.weight(1f),
                    ) { Text("Nhỏ hơn") }
                    OutlinedButton(
                        onClick = { StudioSessionRuntime.adjustSelectedGain(1f) },
                        enabled = enabled,
                        modifier = Modifier.weight(1f),
                    ) { Text("Lớn hơn") }
                }
                Text("Làm mượt mép đoạn", style = MaterialTheme.typography.labelLarge)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = { StudioSessionRuntime.adjustSelectedFadeIn(-50L) },
                        enabled = enabled,
                        modifier = Modifier.weight(1f),
                    ) { Text("Đầu bớt mượt") }
                    OutlinedButton(
                        onClick = { StudioSessionRuntime.adjustSelectedFadeIn(50L) },
                        enabled = enabled,
                        modifier = Modifier.weight(1f),
                    ) { Text("Đầu mượt hơn") }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = { StudioSessionRuntime.adjustSelectedFadeOut(-50L) },
                        enabled = enabled,
                        modifier = Modifier.weight(1f),
                    ) { Text("Cuối bớt mượt") }
                    OutlinedButton(
                        onClick = { StudioSessionRuntime.adjustSelectedFadeOut(50L) },
                        enabled = enabled,
                        modifier = Modifier.weight(1f),
                    ) { Text("Cuối mượt hơn") }
                }
            } else if (clips.isNotEmpty()) {
                Text(
                    "Chọn một đoạn ở trên để chỉnh.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun RetakePanel(
    project: StudioProject,
    punchStartFrame: Long?,
    punchEndFrame: Long?,
    recordingKind: StudioRecordingKind?,
    status: StudioSessionStatus,
    enabled: Boolean,
    onRecord: () -> Unit,
) {
    val hasVocal = project.tracks.any {
        it.type == StudioTrackType.VOCAL && it.takes.isNotEmpty()
    }
    val validRange = punchStartFrame != null && punchEndFrame != null && punchEndFrame > punchStartFrame

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                "Thu sửa một đoạn",
                fontWeight = FontWeight.Bold,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                "Đánh dấu đoạn cần sửa rồi thu lại đúng đoạn đó.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "Bắt đầu: ${punchStartFrame?.let { formatFrames(it, project.timelineSampleRate) } ?: "chưa đặt"}",
                style = MaterialTheme.typography.labelMedium,
            )
            Text(
                "Kết thúc: ${punchEndFrame?.let { formatFrames(it, project.timelineSampleRate) } ?: "chưa đặt"}",
                style = MaterialTheme.typography.labelMedium,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = StudioSessionRuntime::setPunchStartAtPlayhead,
                    enabled = enabled && status != StudioSessionStatus.RECORDING,
                    modifier = Modifier.weight(1f),
                ) { Text("Đặt bắt đầu") }
                OutlinedButton(
                    onClick = StudioSessionRuntime::setPunchEndAtPlayhead,
                    enabled = enabled && status != StudioSessionStatus.RECORDING,
                    modifier = Modifier.weight(1f),
                ) { Text("Đặt kết thúc") }
            }
            OutlinedButton(
                onClick = StudioSessionRuntime::clearPunchRange,
                enabled = enabled && status != StudioSessionStatus.RECORDING,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Xóa vùng đã chọn") }
            Button(
                onClick = {
                    if (
                        status == StudioSessionStatus.RECORDING &&
                        recordingKind == StudioRecordingKind.PUNCH
                    ) {
                        StudioSessionRuntime.stopRecording()
                    } else {
                        onRecord()
                    }
                },
                enabled = enabled && hasVocal && (validRange || recordingKind == StudioRecordingKind.PUNCH),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    if (recordingKind == StudioRecordingKind.PUNCH) {
                        "Dừng thu đoạn"
                    } else {
                        "Thu lại đoạn"
                    },
                )
            }
        }
    }
}

@Composable
private fun TakesPanel(project: StudioProject, enabled: Boolean) {
    val vocalTracks = project.tracks.filter {
        it.type == StudioTrackType.VOCAL && it.takes.isNotEmpty()
    }
    if (vocalTracks.isEmpty()) return

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                "Các bản thu",
                fontWeight = FontWeight.Bold,
                modifier = Modifier.semantics { heading() },
            )
            vocalTracks.forEach { track ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    track.takes.forEachIndexed { index, take ->
                        val recovered = take.status == StudioTakeStatus.RECOVERED
                        FilterChip(
                            selected = track.activeTakeId == take.id,
                            onClick = { StudioSessionRuntime.selectTake(track.id, take.id) },
                            enabled = enabled,
                            label = {
                                Text(
                                    if (recovered) {
                                        "Bản ${index + 1} · đã khôi phục"
                                    } else {
                                        "Bản ${index + 1}"
                                    },
                                )
                            },
                        )
                    }
                }
                if (track.clips.isNotEmpty()) {
                    Text(
                        "Phần đã ghép vẫn được giữ khi bạn đổi bản đang chọn.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

private fun StudioProject.findClip(id: String?): StudioClip? {
    if (id == null) return null
    return tracks
        .asSequence()
        .flatMap { it.clips.asSequence() }
        .firstOrNull { it.id == id }
}

private fun inputModeLabel(mode: StudioInputMode): String = when (mode) {
    StudioInputMode.AUTO -> "Tự động"
    StudioInputMode.STUDIO_RAW -> "Âm thanh gốc"
    StudioInputMode.LIVE_LOW_LATENCY -> "Phản hồi nhanh"
}

private fun friendlyStatus(
    status: StudioSessionStatus,
    kind: StudioRecordingKind?,
): String = when (status) {
    StudioSessionStatus.CLOSED -> "Đã đóng"
    StudioSessionStatus.LOADING -> "Đang mở bài"
    StudioSessionStatus.READY -> "Sẵn sàng"
    StudioSessionStatus.PLAYING -> "Đang nghe thử"
    StudioSessionStatus.RECORDING -> if (kind == StudioRecordingKind.PUNCH) {
        "Đang thu lại đoạn"
    } else {
        "Đang thu giọng"
    }
    StudioSessionStatus.CALIBRATING -> "Đang căn tiếng"
    StudioSessionStatus.RENDERING -> "Đang xuất bài"
    StudioSessionStatus.ERROR -> "Có lỗi"
}

private fun friendlyMessage(value: String): String = value
    .replace("latency", "độ trễ", ignoreCase = true)
    .replace("route", "thiết bị âm thanh", ignoreCase = true)
    .replace("output", "thiết bị nghe", ignoreCase = true)
    .replace("input", "mic", ignoreCase = true)
    .replace("audio focus", "quyền phát âm thanh", ignoreCase = true)
    .replace("playback plan", "bản phối", ignoreCase = true)
    .replace("arrangement", "phần đã ghép", ignoreCase = true)
    .replace("render", "xuất", ignoreCase = true)
    .replace("take", "bản thu", ignoreCase = true)
    .replace("device", "thiết bị", ignoreCase = true)
    .replace("engine", "bộ âm thanh", ignoreCase = true)

private fun zoomPercent(value: Float): Int =
    (((value - 28f) / (150f - 28f)) * 100f).coerceIn(0f, 100f).roundToInt()

private fun formatFrames(frame: Long, sampleRate: Int): String {
    val safeRate = sampleRate.coerceAtLeast(1)
    val totalMillis = frame.coerceAtLeast(0L) * 1_000L / safeRate
    val minutes = totalMillis / 60_000L
    val seconds = (totalMillis / 1_000L) % 60L
    val millis = totalMillis % 1_000L
    return String.format(Locale.US, "%02d:%02d.%03d", minutes, seconds, millis)
}
