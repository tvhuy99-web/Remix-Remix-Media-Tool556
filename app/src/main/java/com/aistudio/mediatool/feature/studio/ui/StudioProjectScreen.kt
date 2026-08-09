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
import com.aistudio.mediatool.feature.studio.audio.StudioSessionRuntime
import com.aistudio.mediatool.feature.studio.audio.StudioSessionStatus
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

    LaunchedEffect(projectId) {
        StudioSessionRuntime.open(context, projectId)
    }

    fun beginRecordingAfterPermission() {
        StudioSessionRuntime.startRecording(mode = inputMode)
    }

    val micPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            beginRecordingAfterPermission()
        } else {
            Toast.makeText(context, "Cần quyền microphone để thu Studio", Toast.LENGTH_SHORT).show()
        }
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

    fun requestRecording() {
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
                        Button(onClick = { StudioSessionRuntime.open(context, projectId) }) {
                            Text("Thử lại")
                        }
                    }
                }
            }

            project?.let { loaded ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column {
                                Text(
                                    formatFrames(session.transportFrame, loaded.timelineSampleRate),
                                    style = MaterialTheme.typography.headlineMedium,
                                    fontWeight = FontWeight.Light,
                                )
                                Text(
                                    statusLabel(status),
                                    color = if (status == StudioSessionStatus.RECORDING) {
                                        MaterialTheme.colorScheme.error
                                    } else {
                                        MaterialTheme.colorScheme.primary
                                    },
                                    style = MaterialTheme.typography.labelLarge,
                                )
                            }
                            Text(
                                "${formatFrames(session.durationFrames, loaded.timelineSampleRate)} tổng",
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
                                enabled = status != StudioSessionStatus.RECORDING,
                                modifier = Modifier.weight(0.8f),
                            ) { Text("⏮") }
                            Button(
                                onClick = {
                                    if (status == StudioSessionStatus.PLAYING) {
                                        StudioSessionRuntime.pause()
                                    } else {
                                        StudioSessionRuntime.play()
                                    }
                                },
                                enabled = session.isPrepared && status != StudioSessionStatus.RECORDING,
                                modifier = Modifier.weight(1.2f),
                            ) {
                                Text(if (status == StudioSessionStatus.PLAYING) "TẠM DỪNG" else "PHÁT")
                            }
                            Button(
                                onClick = {
                                    if (status == StudioSessionStatus.RECORDING) {
                                        StudioSessionRuntime.stopRecording()
                                    } else {
                                        requestRecording()
                                    }
                                },
                                enabled = session.isPrepared,
                                modifier = Modifier.weight(1.2f),
                            ) {
                                Text(if (status == StudioSessionStatus.RECORDING) "DỪNG THU" else "● REC")
                            }
                        }
                    }
                }

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
                    onSeek = StudioSessionRuntime::seek,
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
                        Text(
                            error,
                            modifier = Modifier.padding(12.dp),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }

                session.diagnostics?.let { diagnostics ->
                    AudioDiagnosticsCard(diagnostics)
                }
            }
        }
    }
}

@Composable
private fun AudioDiagnosticsCard(diagnostics: StudioAudioDiagnostics) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text("Audio diagnostics", fontWeight = FontWeight.SemiBold)
            Text(
                "${diagnostics.audioApiLabel} • ${diagnostics.performanceModeLabel} • ${diagnostics.sharingModeLabel}",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "Output ${diagnostics.sampleRate} Hz • ${diagnostics.bufferSizeFrames} frames • ${String.format(Locale.US, "%.1f", diagnostics.approximateBufferMs)} ms buffer",
                style = MaterialTheme.typography.bodySmall,
            )
            diagnostics.inputSampleRate?.let { rate ->
                Text(
                    "Mic $rate Hz • device ${diagnostics.inputDeviceId ?: "mặc định"}",
                    style = MaterialTheme.typography.bodySmall,
                )
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

private fun statusLabel(status: StudioSessionStatus): String = when (status) {
    StudioSessionStatus.CLOSED -> "Đã đóng"
    StudioSessionStatus.LOADING -> "Đang chuẩn bị"
    StudioSessionStatus.READY -> "Sẵn sàng"
    StudioSessionStatus.PLAYING -> "Đang phát"
    StudioSessionStatus.RECORDING -> "Đang thu vocal"
    StudioSessionStatus.ERROR -> "Lỗi"
}

private fun formatFrames(frame: Long, sampleRate: Int): String {
    val safeRate = sampleRate.coerceAtLeast(1)
    val totalMillis = frame.coerceAtLeast(0L) * 1_000L / safeRate
    val minutes = totalMillis / 60_000L
    val seconds = (totalMillis / 1_000L) % 60L
    val millis = totalMillis % 1_000L
    return String.format(Locale.US, "%02d:%02d.%03d", minutes, seconds, millis)
}
