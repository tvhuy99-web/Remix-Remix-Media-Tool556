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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aistudio.mediatool.core.DocumentUtils
import com.aistudio.mediatool.core.GetContentWithMimeTypes
import com.aistudio.mediatool.core.diagnostics.DiagnosticLogger
import com.aistudio.mediatool.core.diagnostics.DiagnosticRedactor
import com.aistudio.mediatool.core.ml.DownloadState
import com.aistudio.mediatool.core.ml.VoiceCleanupAudioMetrics
import com.aistudio.mediatool.core.ml.VoiceCleanupConfig
import com.aistudio.mediatool.core.ml.VoiceCleanupLoudnessMode
import com.aistudio.mediatool.core.ml.VoiceCleanupReport
import com.aistudio.mediatool.core.ml.VoiceCleanupService
import com.aistudio.mediatool.core.ml.VoiceCleanupState
import com.aistudio.mediatool.ui.components.DiagnosticReportCard
import com.aistudio.mediatool.ui.components.ResultFileActions
import com.aistudio.mediatool.ui.components.ToolScaffold
import java.io.File
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

private data class PendingVoiceCleanupStart(
    val uriText: String,
    val modelPath: String,
    val config: VoiceCleanupConfig,
)

private enum class PreviewTrack {
    ORIGINAL,
    RESULT,
}

@Composable
fun VoiceCleanupScreen(onNavigateBack: () -> Unit) {
    val context = LocalContext.current
    val viewModel: VoiceCleanupViewModel = viewModel()
    val downloadState by viewModel.downloadState.collectAsStateWithLifecycle()
    val serviceState by VoiceCleanupService.cleanupState.collectAsStateWithLifecycle()
    val serviceIsProcessing by VoiceCleanupService.isProcessing.collectAsStateWithLifecycle()
    val serviceError by VoiceCleanupService.errorMsg.collectAsStateWithLifecycle()

    var selectedUriText by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedName by rememberSaveable { mutableStateOf("Chọn file") }
    var matchSourceLoudness by rememberSaveable { mutableStateOf(true) }
    var targetLoudnessEnabled by rememberSaveable { mutableStateOf(false) }
    var targetLufs by rememberSaveable { mutableFloatStateOf(-16f) }
    var outputGainDb by rememberSaveable { mutableFloatStateOf(0f) }
    var limiterEnabled by rememberSaveable { mutableStateOf(true) }
    var limiterCeilingDb by rememberSaveable { mutableFloatStateOf(-1f) }
    var progress by remember { mutableFloatStateOf(0f) }
    var phase by remember { mutableStateOf("Sẵn sàng") }
    var resultFile by remember { mutableStateOf<File?>(null) }
    var resultReport by remember { mutableStateOf<VoiceCleanupReport?>(null) }
    val selectedUri = selectedUriText?.let(Uri::parse)

    val config = VoiceCleanupConfig(
        loudnessMode = when {
            targetLoudnessEnabled -> VoiceCleanupLoudnessMode.TARGET_LUFS
            matchSourceLoudness -> VoiceCleanupLoudnessMode.MATCH_SOURCE
            else -> VoiceCleanupLoudnessMode.RAW
        },
        targetLufs = targetLufs,
        outputGainDb = outputGainDb,
        limiterEnabled = limiterEnabled,
        limiterCeilingDb = limiterCeilingDb,
    )

    LaunchedEffect(Unit) {
        viewModel.refreshModelState()
        VoiceCleanupService.restorePersistedState(context)
    }

    LaunchedEffect(serviceState) {
        when (val state = serviceState) {
            is VoiceCleanupState.Progress -> {
                progress = state.value.coerceIn(0f, 1f)
                phase = state.phase
            }
            is VoiceCleanupState.Success -> {
                progress = 1f
                phase = "Làm sạch hoàn tất"
                resultFile = state.outputFile
                resultReport = state.report
            }
            null -> Unit
        }
    }

    fun resetResult() {
        resultFile = null
        resultReport = null
        progress = 0f
        phase = "Sẵn sàng"
        VoiceCleanupService.clearState(context)
    }

    val picker = rememberLauncherForActivityResult(GetContentWithMimeTypes()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        DocumentUtils.persistReadPermission(context, uri)
        selectedUriText = uri.toString()
        selectedName = DocumentUtils.displayName(context, uri)
        resetResult()
    }

    fun launchService(start: PendingVoiceCleanupStart) {
        resetResult()
        val intent = Intent(context, VoiceCleanupService::class.java).apply {
            action = VoiceCleanupService.ACTION_START
            putExtra(VoiceCleanupService.EXTRA_URI, start.uriText)
            putExtra(VoiceCleanupService.EXTRA_MODEL_FILE, start.modelPath)
            putExtra(VoiceCleanupService.EXTRA_LOUDNESS_MODE, start.config.loudnessMode.name)
            putExtra(VoiceCleanupService.EXTRA_TARGET_LUFS, start.config.targetLufs)
            putExtra(VoiceCleanupService.EXTRA_OUTPUT_GAIN_DB, start.config.outputGainDb)
            putExtra(VoiceCleanupService.EXTRA_LIMITER_ENABLED, start.config.limiterEnabled)
            putExtra(VoiceCleanupService.EXTRA_LIMITER_CEILING_DB, start.config.limiterCeilingDb)
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ContextCompat.startForegroundService(context, intent)
            } else {
                context.startService(intent)
            }
        } catch (error: Exception) {
            DiagnosticLogger.error(
                component = "VoiceCleanupScreen",
                event = "service_start_failed",
                message = error.message,
                fields = mapOf("source_id" to DiagnosticRedactor.stableId(start.uriText)),
                error = error,
            )
            Toast.makeText(
                context,
                "Không thể bắt đầu xử lý: ${error.message ?: "không xác định"}",
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    var pendingStart by remember { mutableStateOf<PendingVoiceCleanupStart?>(null) }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { _ ->
        pendingStart?.let(::launchService)
        pendingStart = null
    }

    fun startWithPermission(uri: Uri, modelPath: String) {
        val start = PendingVoiceCleanupStart(uri.toString(), modelPath, config)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            pendingStart = start
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            launchService(start)
        }
    }

    ToolScaffold(title = "Làm sạch giọng", onNavigateBack = onNavigateBack) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(viewModel.model.displayName, fontWeight = FontWeight.Bold)
                        Text(
                            "${viewModel.model.downloadSizeMiB} MiB • ${viewModel.model.licenseName}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Text("48 kHz", style = MaterialTheme.typography.labelLarge)
                }
            }

            when (val state = downloadState) {
                DownloadState.Idle -> Button(
                    onClick = viewModel::downloadModel,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Tải model MossFormer2") }

                is DownloadState.Downloading -> {
                    Text("Đang tải model")
                    LinearProgressIndicator(
                        progress = { state.progress.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text("${(state.progress.coerceIn(0f, 1f) * 100f).toInt()}%")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = viewModel::pauseDownload, modifier = Modifier.weight(1f)) {
                            Text("Tạm dừng")
                        }
                        Button(onClick = viewModel::downloadModel, modifier = Modifier.weight(1f)) {
                            Text("Tiếp tục")
                        }
                    }
                    OutlinedButton(
                        onClick = viewModel::discardPartialDownload,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Xóa phần đã tải") }
                }

                is DownloadState.Error -> {
                    Text(state.message, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
                    Button(onClick = viewModel::downloadModel, modifier = Modifier.fillMaxWidth()) {
                        Text("Thử tải lại")
                    }
                }

                is DownloadState.Success -> {
                    VoiceCleanupSourceCard(
                        selectedName = selectedName,
                        originalUri = selectedUri,
                        resultFile = resultFile,
                        onChoose = { picker.launch(arrayOf("audio/*", "video/*")) },
                    )

                    VoiceCleanupControlsCard(
                        matchSourceLoudness = matchSourceLoudness,
                        onMatchSourceLoudnessChange = { checked ->
                            matchSourceLoudness = checked
                            if (checked) targetLoudnessEnabled = false
                        },
                        targetLoudnessEnabled = targetLoudnessEnabled,
                        onTargetLoudnessEnabledChange = { checked ->
                            targetLoudnessEnabled = checked
                            if (checked) matchSourceLoudness = false
                        },
                        targetLufs = targetLufs,
                        onTargetLufsChange = { targetLufs = it.roundToInt().toFloat() },
                        outputGainDb = outputGainDb,
                        onOutputGainDbChange = { outputGainDb = (it * 2f).roundToInt() / 2f },
                        limiterEnabled = limiterEnabled,
                        onLimiterEnabledChange = { limiterEnabled = it },
                        limiterCeilingDb = limiterCeilingDb,
                        onLimiterCeilingDbChange = {
                            limiterCeilingDb = (it * 2f).roundToInt() / 2f
                        },
                    )

                    Button(
                        onClick = {
                            selectedUri?.let { uri -> startWithPermission(uri, state.file.absolutePath) }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = selectedUri != null && !serviceIsProcessing,
                    ) {
                        Text(if (resultFile == null) "Bắt đầu làm sạch" else "Xử lý lại")
                    }

                    if (serviceIsProcessing) {
                        VoiceCleanupProgressCard(
                            progress = progress,
                            phase = phase,
                            onCancel = {
                                context.startService(
                                    Intent(context, VoiceCleanupService::class.java)
                                        .setAction(VoiceCleanupService.ACTION_STOP),
                                )
                            },
                        )
                    }

                    resultFile?.let { file ->
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Text("Kết quả", style = MaterialTheme.typography.titleMedium)
                                ResultFileActions(file = file)
                            }
                        }
                    }

                    resultReport?.let { VoiceCleanupMetricsCard(it) }
                    serviceError?.let { error ->
                        Text(error, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
                    }
                }
            }

            val diagnosticReason = when {
                downloadState is DownloadState.Error -> (downloadState as DownloadState.Error).message
                serviceError != null -> serviceError
                else -> null
            }
            diagnosticReason?.let { DiagnosticReportCard(errorContext = it) }
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

@Composable
private fun VoiceCleanupSourceCard(
    selectedName: String,
    originalUri: Uri?,
    resultFile: File?,
    onChoose: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Tệp và nghe thử", style = MaterialTheme.typography.titleMedium)
            Button(onClick = onChoose, modifier = Modifier.fillMaxWidth()) {
                Text(if (originalUri == null) "Chọn audio hoặc video" else "Đổi tệp")
            }
            if (originalUri != null) {
                Text(selectedName, style = MaterialTheme.typography.bodyMedium)
                VoiceCleanupPreview(originalUri, resultFile)
            }
        }
    }
}

@Composable
private fun VoiceCleanupControlsCard(
    matchSourceLoudness: Boolean,
    onMatchSourceLoudnessChange: (Boolean) -> Unit,
    targetLoudnessEnabled: Boolean,
    onTargetLoudnessEnabledChange: (Boolean) -> Unit,
    targetLufs: Float,
    onTargetLufsChange: (Float) -> Unit,
    outputGainDb: Float,
    onOutputGainDbChange: (Float) -> Unit,
    limiterEnabled: Boolean,
    onLimiterEnabledChange: (Boolean) -> Unit,
    limiterCeilingDb: Float,
    onLimiterCeilingDbChange: (Float) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Tinh chỉnh đầu ra", style = MaterialTheme.typography.titleMedium)
            SettingSwitchRow(
                title = "Khớp âm lượng bản gốc",
                checked = matchSourceLoudness,
                onCheckedChange = onMatchSourceLoudnessChange,
            )
            SettingSwitchRow(
                title = "Chuẩn hóa tới LUFS mục tiêu",
                checked = targetLoudnessEnabled,
                onCheckedChange = onTargetLoudnessEnabledChange,
            )
            if (targetLoudnessEnabled) {
                ValueSlider(
                    label = "Mục tiêu: ${targetLufs.roundToInt()} LUFS",
                    value = targetLufs,
                    onValueChange = onTargetLufsChange,
                    valueRange = -24f..-10f,
                    steps = 13,
                )
            }
            HorizontalDivider()
            ValueSlider(
                label = "Gain bổ sung: ${formatSigned(outputGainDb)} dB",
                value = outputGainDb,
                onValueChange = onOutputGainDbChange,
                valueRange = -12f..12f,
                steps = 47,
            )
            SettingSwitchRow(
                title = "Limiter chống clipping",
                checked = limiterEnabled,
                onCheckedChange = onLimiterEnabledChange,
            )
            if (limiterEnabled) {
                ValueSlider(
                    label = "Trần limiter: ${formatOneDecimal(limiterCeilingDb)} dBFS",
                    value = limiterCeilingDb,
                    onValueChange = onLimiterCeilingDbChange,
                    valueRange = -6f..-0.5f,
                    steps = 10,
                )
            }
            Text(
                when {
                    targetLoudnessEnabled -> "Chế độ: LUFS mục tiêu"
                    matchSourceLoudness -> "Chế độ: khớp bản gốc"
                    else -> "Chế độ: giữ mức sau AI"
                },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SettingSwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun ValueSlider(
    label: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            steps = steps,
        )
    }
}

@Composable
private fun VoiceCleanupProgressCard(
    progress: Float,
    phase: String,
    onCancel: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            CircularProgressIndicator()
            Text(phase, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
            LinearProgressIndicator(
                progress = { progress.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
            )
            Text("${(progress.coerceIn(0f, 1f) * 100f).toInt()}%")
            OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
                Text("Hủy xử lý")
            }
        }
    }
}

@Composable
private fun VoiceCleanupPreview(originalUri: Uri, resultFile: File?) {
    val context = LocalContext.current
    var selectedTrack by remember(resultFile?.absolutePath) {
        mutableStateOf(if (resultFile == null) PreviewTrack.ORIGINAL else PreviewTrack.RESULT)
    }
    if (resultFile == null && selectedTrack == PreviewTrack.RESULT) {
        selectedTrack = PreviewTrack.ORIGINAL
    }
    val activeUri = when (selectedTrack) {
        PreviewTrack.ORIGINAL -> originalUri
        PreviewTrack.RESULT -> resultFile?.let(Uri::fromFile) ?: originalUri
    }
    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    var prepared by remember { mutableStateOf(false) }
    var playing by remember { mutableStateOf(false) }
    var positionMs by remember { mutableIntStateOf(0) }
    var durationMs by remember { mutableIntStateOf(1) }

    DisposableEffect(activeUri) {
        prepared = false
        playing = false
        positionMs = 0
        durationMs = 1
        val mediaPlayer = MediaPlayer()
        player = mediaPlayer
        mediaPlayer.setOnPreparedListener {
            prepared = true
            durationMs = it.duration.coerceAtLeast(1)
        }
        mediaPlayer.setOnCompletionListener {
            playing = false
            positionMs = 0
            it.seekTo(0)
        }
        mediaPlayer.setOnErrorListener { _, _, _ ->
            prepared = false
            playing = false
            true
        }
        runCatching {
            mediaPlayer.setDataSource(context, activeUri)
            mediaPlayer.prepareAsync()
        }.onFailure {
            prepared = false
            playing = false
        }
        onDispose {
            runCatching { mediaPlayer.release() }
            if (player === mediaPlayer) player = null
        }
    }

    LaunchedEffect(playing, player) {
        while (playing) {
            val active = player
            if (active != null && runCatching { active.isPlaying }.getOrDefault(false)) {
                positionMs = active.currentPosition.coerceAtLeast(0)
            }
            delay(150L)
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (selectedTrack == PreviewTrack.ORIGINAL) {
                    Button(onClick = { selectedTrack = PreviewTrack.ORIGINAL }, modifier = Modifier.weight(1f)) {
                        Text("Bản gốc")
                    }
                } else {
                    OutlinedButton(
                        onClick = { selectedTrack = PreviewTrack.ORIGINAL },
                        modifier = Modifier.weight(1f),
                    ) { Text("Bản gốc") }
                }
                if (resultFile != null) {
                    if (selectedTrack == PreviewTrack.RESULT) {
                        Button(onClick = { selectedTrack = PreviewTrack.RESULT }, modifier = Modifier.weight(1f)) {
                            Text("Kết quả")
                        }
                    } else {
                        OutlinedButton(
                            onClick = { selectedTrack = PreviewTrack.RESULT },
                            modifier = Modifier.weight(1f),
                        ) { Text("Kết quả") }
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = {
                        val active = player ?: return@IconButton
                        if (playing) active.pause() else active.start()
                        playing = !playing
                    },
                    enabled = prepared,
                ) {
                    Icon(
                        imageVector = if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (playing) "Tạm dừng" else "Nghe thử",
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Slider(
                        value = positionMs.toFloat().coerceIn(0f, durationMs.toFloat()),
                        onValueChange = { value ->
                            positionMs = value.roundToInt().coerceIn(0, durationMs)
                            player?.seekTo(positionMs)
                        },
                        valueRange = 0f..durationMs.toFloat().coerceAtLeast(1f),
                        enabled = prepared,
                    )
                    Text(
                        "${formatTime(positionMs)} / ${formatTime(durationMs)}",
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }
    }
}

@Composable
private fun VoiceCleanupMetricsCard(report: VoiceCleanupReport) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Thống kê xử lý", style = MaterialTheme.typography.titleMedium)
            AudioMetricRow("Bản gốc", report.source)
            AudioMetricRow("Sau AI", report.afterAi)
            AudioMetricRow("File cuối", report.finalOutput)
            HorizontalDivider()
            Text(
                "Mask: mean ${formatMetric(report.mask.mean)} • p50 ${formatMetric(report.mask.p50)} • " +
                    "<0,9 ${formatMetric(report.mask.belowPointNinePercent)}% • " +
                    "<0,5 ${formatMetric(report.mask.belowPointFivePercent)}%",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "Gần 1: ${formatMetric(report.mask.nearUnityPercent)}% • " +
                    "Gain áp dụng: ${formatSigned(report.appliedGainDb)} dB • " +
                    "RTF: ${formatMetric(report.inferenceRealTimeFactor)}",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun AudioMetricRow(label: String, metrics: VoiceCleanupAudioMetrics) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, fontWeight = FontWeight.SemiBold)
        Text(
            "LUFS ${formatNullable(metrics.integratedLufs)} • " +
                "RMS ${formatNullable(metrics.rmsDbfs)} dBFS • " +
                "Peak ${formatNullable(metrics.samplePeakDbfs)} dBFS • " +
                "True peak ${formatNullable(metrics.truePeakDbfs)} dBFS",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

private fun formatTime(milliseconds: Int): String {
    val totalSeconds = milliseconds.coerceAtLeast(0) / 1_000
    return "%d:%02d".format(Locale.US, totalSeconds / 60, totalSeconds % 60)
}

private fun formatSigned(value: Float): String = String.format(Locale.US, "%+.1f", value)
private fun formatOneDecimal(value: Float): String = String.format(Locale.US, "%.1f", value)
private fun formatMetric(value: Double): String = String.format(Locale.US, "%.2f", value)
private fun formatNullable(value: Double?): String = value?.let(::formatMetric) ?: "N/A"
