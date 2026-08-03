package com.aistudio.mediatool.ui.screens

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.core.content.ContextCompat
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.aistudio.mediatool.core.GetContentWithMimeTypes
import com.aistudio.mediatool.core.DocumentUtils
import com.aistudio.mediatool.core.diagnostics.DiagnosticLogger
import com.aistudio.mediatool.core.diagnostics.DiagnosticRedactor
import com.aistudio.mediatool.core.ml.DownloadState
import com.aistudio.mediatool.core.ml.SeparationState
import com.aistudio.mediatool.core.ml.StemService
import com.aistudio.mediatool.ui.components.DiagnosticReportCard
import com.aistudio.mediatool.ui.components.ResultFileActions
import com.aistudio.mediatool.ui.components.ToolScaffold
import kotlinx.coroutines.delay
import java.io.File

@Composable
fun StemScreen(onNavigateBack: () -> Unit) {
    val context = LocalContext.current
    val stemViewModel: StemViewModel = viewModel()
    val downloadState by stemViewModel.downloadState.collectAsStateWithLifecycle()
    val selectedModel by stemViewModel.selectedModel.collectAsStateWithLifecycle()

    var selectedAudioUriText by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedAudioName by rememberSaveable { mutableStateOf("Chọn âm thanh hoặc video") }
    val selectedAudioUri = selectedAudioUriText?.let(Uri::parse)
    var separationProgress by remember { mutableFloatStateOf(0f) }
    var result by remember { mutableStateOf<SeparationState.Success?>(null) }

    val serviceIsProcessing by StemService.isProcessing.collectAsStateWithLifecycle()
    val serviceState by StemService.separationState.collectAsStateWithLifecycle()
    val serviceError by StemService.errorMsg.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        stemViewModel.refreshConfiguredModel()
        StemService.restorePersistedState(context)
    }

    var pendingStemStart by remember { mutableStateOf<Triple<String, String, String>?>(null) }

    fun launchStemService(uriText: String, modelPath: String, modelId: String) {
        val serviceIntent = Intent(context, StemService::class.java).apply {
            action = StemService.ACTION_START
            putExtra(StemService.EXTRA_URI, uriText)
            putExtra(StemService.EXTRA_MODEL_FILE, modelPath)
            putExtra(StemService.EXTRA_MODEL_ID, modelId)
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ContextCompat.startForegroundService(context, serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        } catch (error: Exception) {
            DiagnosticLogger.error(
                component = "StemScreen",
                event = "service_start_failed",
                message = error.message,
                fields = mapOf(
                    "model_id" to modelId,
                    "source_id" to DiagnosticRedactor.stableId(uriText),
                ),
                error = error,
            )
            Toast.makeText(
                context,
                "Không thể bắt đầu xử lý nền: ${error.message ?: "không xác định"}",
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { _ ->
        pendingStemStart?.let { (uriText, modelPath, modelId) ->
            launchStemService(uriText, modelPath, modelId)
        }
        pendingStemStart = null
    }

    fun startStemWithPermission(uri: Uri, modelPath: String, modelId: String) {
        val args = Triple(uri.toString(), modelPath, modelId)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            pendingStemStart = args
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            launchStemService(args.first, args.second, args.third)
        }
    }

    fun resetResult() {
        result = null
        separationProgress = 0f
        StemService.clearState(context)
    }

    val audioPicker = rememberLauncherForActivityResult(
        GetContentWithMimeTypes(),
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        DocumentUtils.persistReadPermission(context, uri)
        selectedAudioUriText = uri.toString()
        selectedAudioName = DocumentUtils.displayName(context, uri)
        resetResult()
    }

    LaunchedEffect(serviceState) {
        when (val state = serviceState) {
            is SeparationState.Progress -> separationProgress = state.value.coerceIn(0f, 1f)
            is SeparationState.Success -> result = state
            null -> Unit
        }
    }

    ToolScaffold(
        title = "Tách giọng và nhạc",
        onNavigateBack = onNavigateBack,
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = selectedModel.displayName,
                modifier = Modifier.fillMaxWidth(),
                fontWeight = FontWeight.Bold,
            )
            when (val state = downloadState) {
                DownloadState.Idle -> {
                    Button(onClick = stemViewModel::downloadModel) {
                        Text("Tải model ${selectedModel.downloadSizeMiB} MiB")
                    }
                }

                is DownloadState.Downloading -> {
                    Text("Đang tải và kiểm tra mô hình...")
                    LinearProgressIndicator(
                        progress = { state.progress.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text("${(state.progress.coerceIn(0f, 1f) * 100).toInt()}%")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = stemViewModel::pauseDownload) { Text("Tạm dừng") }
                        Button(onClick = stemViewModel::downloadModel) { Text("Tiếp tục") }
                    }
                    OutlinedButton(onClick = stemViewModel::discardPartialDownload) {
                        Text("Xóa phần đã tải")
                    }
                }

                is DownloadState.Error -> {
                    Text(
                        text = state.message,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center,
                    )
                    Button(onClick = stemViewModel::downloadModel) { Text("Thử tải lại") }
                }

                is DownloadState.Success -> {
                    when {
                        serviceIsProcessing -> ProcessingSection(
                            progress = separationProgress,
                            onCancel = {
                                context.startService(
                                    Intent(context, StemService::class.java).setAction(StemService.ACTION_STOP),
                                )
                            },
                        )

                        result != null -> {
                            val files = result!!
                            Text(
                                "Tách thành công",
                                style = MaterialTheme.typography.titleLarge,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            StemPreviewCard("Lời hát", files.vocalsFile, Icons.Default.Person)
                            StemPreviewCard(
                                if (files.drumsFile == null) "Nhạc nền" else "Nhạc nền tổng hợp",
                                files.musicFile,
                                Icons.Default.MusicNote,
                            )
                            files.drumsFile?.let { StemPreviewCard("Trống", it, Icons.Default.Audiotrack) }
                            files.bassFile?.let { StemPreviewCard("Bass", it, Icons.Default.Audiotrack) }
                            files.otherFile?.let { other ->
                                if (other.absolutePath != files.musicFile.absolutePath) {
                                    StemPreviewCard("Khác", other, Icons.Default.MusicNote)
                                }
                            }
                            OutlinedButton(
                                onClick = {
                                    selectedAudioUriText = null
                                    selectedAudioName = "Chọn âm thanh hoặc video"
                                    resetResult()
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text("Chọn bài khác") }
                        }

                        else -> {
                            Text(
                                "Mô hình đã sẵn sàng",
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Button(
                                onClick = { audioPicker.launch(arrayOf("audio/*", "video/*")) },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(selectedAudioName)
                            }
                            selectedAudioUri?.let { sourceUri ->
                                Button(
                                    onClick = {
                                        resetResult()
                                        startStemWithPermission(
                                            sourceUri,
                                            state.file.absolutePath,
                                            selectedModel.id,
                                        )
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                ) { Text("Bắt đầu tách") }
                            }
                            serviceError?.let { error ->
                                Text(
                                    text = error,
                                    color = MaterialTheme.colorScheme.error,
                                    textAlign = TextAlign.Center,
                                )
                            }
                        }
                    }
                }
            }
            val diagnosticReason = when {
                downloadState is DownloadState.Error -> (downloadState as DownloadState.Error).message
                serviceError != null -> serviceError
                else -> null
            }
            diagnosticReason?.let { reason ->
                DiagnosticReportCard(errorContext = reason)
            }
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

@Composable
private fun ProcessingSection(progress: Float, onCancel: () -> Unit) {
    CircularProgressIndicator()
    Text("Đang tách nhạc trong nền")
    LinearProgressIndicator(
        progress = { progress.coerceIn(0f, 1f) },
        modifier = Modifier.fillMaxWidth(),
    )
    Text("${(progress.coerceIn(0f, 1f) * 100).toInt()}%")
    OutlinedButton(onClick = onCancel) { Text("Hủy xử lý") }
}

@Composable
private fun StemPreviewCard(title: String, audioFile: File, icon: ImageVector) {
    var isPlaying by remember(audioFile) { mutableStateOf(false) }
    var progress by remember(audioFile) { mutableFloatStateOf(0f) }
    var durationMs by remember(audioFile) { mutableIntStateOf(1) }
    var mediaPlayer by remember(audioFile) { mutableStateOf<MediaPlayer?>(null) }

    DisposableEffect(audioFile) {
        val player = runCatching {
            MediaPlayer().apply {
                setDataSource(audioFile.absolutePath)
                prepare()
                durationMs = duration.coerceAtLeast(1)
                setOnCompletionListener {
                    isPlaying = false
                    progress = 0f
                }
            }
        }.getOrNull()
        mediaPlayer = player
        onDispose {
            runCatching { player?.release() }
            mediaPlayer = null
        }
    }

    LaunchedEffect(isPlaying, mediaPlayer) {
        while (isPlaying) {
            val player = mediaPlayer
            if (player != null && runCatching { player.isPlaying }.getOrDefault(false)) {
                progress = (player.currentPosition.toFloat() / durationMs).coerceIn(0f, 1f)
            }
            delay(100)
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(32.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.width(12.dp))
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = {
                        val player = mediaPlayer ?: return@IconButton
                        if (isPlaying) {
                            player.pause()
                            isPlaying = false
                        } else {
                            player.start()
                            isPlaying = true
                        }
                    },
                    enabled = mediaPlayer != null,
                    modifier = Modifier.semantics {
                        contentDescription = if (isPlaying) "Tạm dừng $title" else "Phát $title"
                    },
                ) {
                    Icon(if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, contentDescription = null)
                }
                Slider(
                    value = progress,
                    onValueChange = { value ->
                        progress = value
                        mediaPlayer?.seekTo((value * durationMs).toInt())
                    },
                    modifier = Modifier
                        .weight(1f)
                        .then(
                            if (isPlaying) {
                                Modifier.clearAndSetSemantics { contentDescription = "Tua $title, đang phát" }
                            } else {
                                Modifier.semantics { contentDescription = "Tua $title" }
                            },
                        ),
                )
            }
            ResultFileActions(file = audioFile)
        }
    }
}
