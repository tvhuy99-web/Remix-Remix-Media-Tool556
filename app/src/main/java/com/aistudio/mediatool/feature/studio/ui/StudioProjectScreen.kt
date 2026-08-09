package com.aistudio.mediatool.feature.studio.ui

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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aistudio.mediatool.feature.studio.audio.StudioAudioDiagnostics
import com.aistudio.mediatool.feature.studio.audio.StudioAudioOperationResult
import com.aistudio.mediatool.feature.studio.audio.StudioNativeAudio
import com.aistudio.mediatool.feature.studio.data.StudioProjectRepository
import com.aistudio.mediatool.feature.studio.domain.StudioProject
import com.aistudio.mediatool.ui.components.ToolScaffold
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class AudioCoreState { CLOSED, OPEN, RUNNING, ERROR }

@Composable
fun StudioProjectScreen(
    projectId: String,
    onNavigateBack: () -> Unit,
) {
    val context = LocalContext.current
    val repository = remember { StudioProjectRepository(context) }
    val scope = rememberCoroutineScope()
    var project by remember(projectId) { mutableStateOf<StudioProject?>(null) }
    var projectError by remember(projectId) { mutableStateOf<String?>(null) }

    val engineResult = remember { runCatching { StudioNativeAudio() } }
    val audioEngine = engineResult.getOrNull()
    var audioState by remember { mutableStateOf(AudioCoreState.CLOSED) }
    var audioError by remember { mutableStateOf(engineResult.exceptionOrNull()?.message) }
    var diagnostics by remember { mutableStateOf<StudioAudioDiagnostics?>(null) }

    LaunchedEffect(projectId) {
        project = withContext(Dispatchers.IO) { repository.load(projectId) }
        if (project == null) projectError = "Không tìm thấy dự án Studio"
    }

    DisposableEffect(audioEngine) {
        onDispose { audioEngine?.close() }
    }

    LaunchedEffect(audioState) {
        if (audioState == AudioCoreState.RUNNING) {
            while (true) {
                diagnostics = audioEngine?.diagnostics()
                delay(500)
            }
        }
    }

    fun openAudioCore() {
        val engine = audioEngine ?: return
        scope.launch {
            when (val result = withContext(Dispatchers.Default) { engine.openOutput() }) {
                StudioAudioOperationResult.Success -> {
                    diagnostics = engine.diagnostics()
                    audioState = AudioCoreState.OPEN
                    audioError = null
                }
                StudioAudioOperationResult.Released -> {
                    audioState = AudioCoreState.ERROR
                    audioError = "Audio Core đã được giải phóng"
                }
                is StudioAudioOperationResult.Error -> {
                    audioState = AudioCoreState.ERROR
                    audioError = "Không thể mở Oboe output (mã ${result.nativeCode})"
                }
            }
        }
    }

    fun startAudioClock() {
        val engine = audioEngine ?: return
        scope.launch {
            when (val result = withContext(Dispatchers.Default) { engine.start() }) {
                StudioAudioOperationResult.Success -> {
                    audioState = AudioCoreState.RUNNING
                    audioError = null
                }
                StudioAudioOperationResult.Released -> {
                    audioState = AudioCoreState.ERROR
                    audioError = "Audio Core đã được giải phóng"
                }
                is StudioAudioOperationResult.Error -> {
                    audioState = AudioCoreState.ERROR
                    audioError = "Không thể chạy audio clock (mã ${result.nativeCode})"
                }
            }
        }
    }

    fun stopAudioCore() {
        val engine = audioEngine ?: return
        scope.launch {
            withContext(Dispatchers.Default) {
                engine.stop()
                engine.closeStream()
            }
            diagnostics = null
            audioState = AudioCoreState.CLOSED
            audioError = null
        }
    }

    ToolScaffold(
        title = project?.name ?: "Studio Project",
        onNavigateBack = onNavigateBack,
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            projectError?.let { Text(it, color = MaterialTheme.colorScheme.error) }

            project?.let { loaded ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text("Project Foundation", fontWeight = FontWeight.Bold)
                        Text("Timeline: ${loaded.timelineSampleRate} Hz")
                        Text("Beat: ${loaded.beatAsset()?.displayName ?: "Chưa có"}")
                        Text("Assets: ${loaded.assets.size} • Tracks: ${loaded.tracks.size}")
                    }
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text("Native Audio Core", fontWeight = FontWeight.Bold)
                    Text(
                        "Oboe output clock low-latency. Giai đoạn này cố ý phát silence; beat và microphone sẽ dùng chung clock này ở bước Studio Recording.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Button(
                            onClick = ::openAudioCore,
                            enabled = audioEngine != null && audioState == AudioCoreState.CLOSED,
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("Mở core")
                        }
                        Button(
                            onClick = ::startAudioClock,
                            enabled = audioState == AudioCoreState.OPEN,
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("Chạy clock")
                        }
                    }
                    OutlinedButton(
                        onClick = ::stopAudioCore,
                        enabled = audioState == AudioCoreState.OPEN || audioState == AudioCoreState.RUNNING || audioState == AudioCoreState.ERROR,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Dừng và đóng Audio Core")
                    }

                    Text(
                        when (audioState) {
                            AudioCoreState.CLOSED -> "Trạng thái: Chưa mở"
                            AudioCoreState.OPEN -> "Trạng thái: Đã mở stream"
                            AudioCoreState.RUNNING -> "Trạng thái: Audio clock đang chạy"
                            AudioCoreState.ERROR -> "Trạng thái: Lỗi"
                        },
                        fontWeight = FontWeight.SemiBold,
                    )
                    audioError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    diagnostics?.let { AudioDiagnosticsView(it) }
                }
            }
        }
    }
}

@Composable
private fun AudioDiagnosticsView(diagnostics: StudioAudioDiagnostics) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("API: ${diagnostics.audioApiLabel}")
        Text("Sample rate thực tế: ${diagnostics.sampleRate} Hz")
        Text("Kênh output: ${diagnostics.channelCount}")
        Text("Performance: ${diagnostics.performanceModeLabel}")
        Text("Sharing: ${diagnostics.sharingModeLabel}")
        Text("Frames/burst: ${diagnostics.framesPerBurst}")
        Text("Buffer: ${diagnostics.bufferSizeFrames} frames (${String.format("%.1f", diagnostics.approximateBufferMs)} ms)")
        Text("Device id: ${diagnostics.deviceId}")
        Text("Clock frames: ${diagnostics.callbackFrames}")
        if (diagnostics.disconnectCount > 0) {
            Text("Disconnects: ${diagnostics.disconnectCount}", color = MaterialTheme.colorScheme.error)
        }
    }
}
