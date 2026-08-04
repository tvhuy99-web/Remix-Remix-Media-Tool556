package com.aistudio.mediatool.ui.screens

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
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
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aistudio.mediatool.core.DocumentUtils
import com.aistudio.mediatool.core.GetContentWithMimeTypes
import com.aistudio.mediatool.core.SettingsManager
import com.aistudio.mediatool.core.diagnostics.DiagnosticLogger
import com.aistudio.mediatool.core.diagnostics.DiagnosticRedactor
import com.aistudio.mediatool.core.ml.DownloadState
import com.aistudio.mediatool.core.ml.VoiceCleanupAudioMetrics
import com.aistudio.mediatool.core.ml.VoiceCleanupConfig
import com.aistudio.mediatool.core.ml.VoiceCleanupLoudnessMode
import com.aistudio.mediatool.core.ml.VoiceCleanupReport
import com.aistudio.mediatool.core.ml.VoiceCleanupService
import com.aistudio.mediatool.core.ml.VoiceCleanupState
import com.aistudio.mediatool.ui.components.AudioPreviewSource
import com.aistudio.mediatool.ui.components.CompactDropdown
import com.aistudio.mediatool.ui.components.DiagnosticReportCard
import com.aistudio.mediatool.ui.components.MediaInputCard
import com.aistudio.mediatool.ui.components.ResultFileActions
import com.aistudio.mediatool.ui.components.StickyProcessBar
import com.aistudio.mediatool.ui.components.ToolScaffold
import com.aistudio.mediatool.ui.components.ToolSectionCard
import com.aistudio.mediatool.ui.components.UnifiedAudioPlayer
import java.io.File
import java.util.Locale
import kotlin.math.roundToInt

private data class PendingVoiceCleanupStart(
    val uriText: String,
    val modelPath: String,
    val config: VoiceCleanupConfig,
)

@Composable
fun VoiceCleanupScreen(onNavigateBack: () -> Unit) {
    val context = LocalContext.current
    val viewModel: VoiceCleanupViewModel = viewModel()
    val downloadState by viewModel.downloadState.collectAsStateWithLifecycle()
    val serviceState by VoiceCleanupService.cleanupState.collectAsStateWithLifecycle()
    val serviceIsProcessing by VoiceCleanupService.isProcessing.collectAsStateWithLifecycle()
    val serviceError by VoiceCleanupService.errorMsg.collectAsStateWithLifecycle()

    var selectedUriText by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedName by rememberSaveable { mutableStateOf<String?>(null) }
    var loudnessModeName by rememberSaveable { mutableStateOf(VoiceCleanupLoudnessMode.MATCH_SOURCE.name) }
    var targetLufs by rememberSaveable { mutableFloatStateOf(-16f) }
    var outputGainDb by rememberSaveable { mutableFloatStateOf(0f) }
    var limiterEnabled by rememberSaveable { mutableStateOf(true) }
    var limiterCeilingDb by rememberSaveable { mutableFloatStateOf(-1f) }
    var formatIndex by rememberSaveable { mutableStateOf(SettingsManager.getAudFormatIndex(context)) }
    var bitrateIndex by rememberSaveable { mutableStateOf(SettingsManager.getAudBitrateIndex(context)) }
    var threadsIndex by rememberSaveable { mutableStateOf(SettingsManager.getNumThreadsIndex(context)) }
    var advancedExpanded by rememberSaveable { mutableStateOf(false) }
    var showAnalysis by rememberSaveable { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    var phase by remember { mutableStateOf("Sẵn sàng") }
    var resultFile by remember { mutableStateOf<File?>(null) }
    var resultReport by remember { mutableStateOf<VoiceCleanupReport?>(null) }

    val selectedUri = selectedUriText?.let(Uri::parse)
    val loudnessMode = VoiceCleanupLoudnessMode.fromName(loudnessModeName)
    val config = VoiceCleanupConfig(
        loudnessMode = loudnessMode,
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

    fun startWithPermission(modelPath: String) {
        val uri = selectedUri ?: return
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

    val downloadedModel = (downloadState as? DownloadState.Success)?.file

    ToolScaffold(
        title = "Làm sạch giọng",
        onNavigateBack = onNavigateBack,
        bottomBar = {
            StickyProcessBar(
                label = if (resultFile == null) "Bắt đầu làm sạch" else "Xử lý lại",
                enabled = selectedUri != null && downloadedModel != null,
                processing = serviceIsProcessing,
                progress = progress,
                phase = phase,
                onClick = { downloadedModel?.let { startWithPermission(it.absolutePath) } },
                onCancel = {
                    context.startService(
                        Intent(context, VoiceCleanupService::class.java)
                            .setAction(VoiceCleanupService.ACTION_STOP),
                    )
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            MediaInputCard(
                fileName = selectedName,
                supportingText = "Audio hoặc video, đầu ra mono 48 kHz",
                onChoose = { picker.launch(arrayOf("audio/*", "video/*")) },
            )

            val previewSources = buildList {
                selectedUri?.let { add(AudioPreviewSource("source", "Bản gốc", it)) }
                resultFile?.let { add(AudioPreviewSource("result", "Kết quả", Uri.fromFile(it))) }
            }
            UnifiedAudioPlayer(sources = previewSources, title = "Nghe so sánh")

            VoiceCleanupModelCard(
                modelName = viewModel.model.displayName,
                modelSizeMiB = viewModel.model.downloadSizeMiB,
                downloadState = downloadState,
                onDownload = viewModel::downloadModel,
                onPause = viewModel::pauseDownload,
                onDiscard = viewModel::discardPartialDownload,
            )

            ToolSectionCard(title = "Âm lượng đầu ra", icon = Icons.Default.GraphicEq) {
                LoudnessModeRow(
                    title = "Khớp âm lượng bản gốc",
                    subtitle = "Phù hợp nhất để nghe A/B công bằng",
                    selected = loudnessMode == VoiceCleanupLoudnessMode.MATCH_SOURCE,
                    onClick = { loudnessModeName = VoiceCleanupLoudnessMode.MATCH_SOURCE.name },
                )
                LoudnessModeRow(
                    title = "Giữ nguyên sau AI",
                    subtitle = "Không tự thay đổi loudness",
                    selected = loudnessMode == VoiceCleanupLoudnessMode.RAW,
                    onClick = { loudnessModeName = VoiceCleanupLoudnessMode.RAW.name },
                )
                LoudnessModeRow(
                    title = "Chuẩn hóa theo LUFS",
                    subtitle = "Đưa file tới mức âm lượng mục tiêu",
                    selected = loudnessMode == VoiceCleanupLoudnessMode.TARGET_LUFS,
                    onClick = { loudnessModeName = VoiceCleanupLoudnessMode.TARGET_LUFS.name },
                )
                if (loudnessMode == VoiceCleanupLoudnessMode.TARGET_LUFS) {
                    ParameterSlider(
                        label = "LUFS mục tiêu",
                        valueLabel = "${targetLufs.roundToInt()} LUFS",
                        value = targetLufs,
                        valueRange = -30f..-8f,
                        steps = 21,
                        onValueChange = { targetLufs = it.roundToInt().toFloat() },
                    )
                }
                HorizontalDivider()
                ParameterSlider(
                    label = "Gain bổ sung",
                    valueLabel = formatSigned(outputGainDb, "dB"),
                    value = outputGainDb,
                    valueRange = -12f..12f,
                    steps = 47,
                    onValueChange = { outputGainDb = (it * 2f).roundToInt() / 2f },
                )
                SettingSwitchRow(
                    title = "Limiter bảo vệ clipping",
                    subtitle = "Chỉ giới hạn đỉnh, không tự nâng loudness",
                    checked = limiterEnabled,
                    onCheckedChange = { limiterEnabled = it },
                )
                if (limiterEnabled) {
                    ParameterSlider(
                        label = "Trần limiter",
                        valueLabel = String.format(Locale.US, "%.1f dBFS", limiterCeilingDb),
                        value = limiterCeilingDb,
                        valueRange = -6f..-0.5f,
                        steps = 10,
                        onValueChange = { limiterCeilingDb = (it * 2f).roundToInt() / 2f },
                    )
                }
            }

            ToolSectionCard(title = "Xuất file", icon = Icons.Default.Settings) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    CompactDropdown(
                        label = "Định dạng",
                        values = listOf("M4A", "MP3", "WAV", "FLAC"),
                        selectedIndex = formatIndex,
                        onSelected = {
                            formatIndex = it
                            SettingsManager.setAudFormatIndex(context, it)
                        },
                        modifier = Modifier.weight(1f),
                    )
                    CompactDropdown(
                        label = "Chất lượng",
                        values = listOf("128 kbps", "192 kbps", "256 kbps", "320 kbps", "Lossless"),
                        selectedIndex = bitrateIndex,
                        onSelected = {
                            bitrateIndex = it
                            SettingsManager.setAudBitrateIndex(context, it)
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            ToolSectionCard(title = "Nâng cao", icon = Icons.Default.Tune) {
                TextButton(onClick = { advancedExpanded = !advancedExpanded }) {
                    Text(if (advancedExpanded) "Thu gọn" else "Mở thông số nâng cao")
                }
                if (advancedExpanded) {
                    CompactDropdown(
                        label = "Luồng CPU",
                        values = listOf("1 luồng", "2 luồng", "4 luồng", "8 luồng"),
                        selectedIndex = threadsIndex,
                        onSelected = {
                            threadsIndex = it
                            SettingsManager.setNumThreadsIndex(context, it)
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    SettingSwitchRow(
                        title = "Hiển thị phân tích chi tiết",
                        subtitle = "LUFS, RMS, peak và thống kê mask",
                        checked = showAnalysis,
                        onCheckedChange = { showAnalysis = it },
                    )
                    Text(
                        "Model cố định: MossFormer2 SE 48K • ONNX Runtime CPU",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            resultFile?.let { file ->
                ToolSectionCard(title = "Kết quả") {
                    resultReport?.let { report ->
                        Text(
                            "Gain đã áp: ${formatSigned(report.appliedGainDb, "dB")} • RTF ${formatNumber(report.inferenceRealTimeFactor)}",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    ResultFileActions(file = file)
                }
            }

            if (showAnalysis) {
                resultReport?.let { VoiceCleanupAnalysisCard(it) }
            }

            serviceError?.let {
                Text(it, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
                DiagnosticReportCard(errorContext = it)
            }
            Spacer(modifier = Modifier.height(12.dp))
        }
    }
}

@Composable
private fun VoiceCleanupModelCard(
    modelName: String,
    modelSizeMiB: Long,
    downloadState: DownloadState,
    onDownload: () -> Unit,
    onPause: () -> Unit,
    onDiscard: () -> Unit,
) {
    ToolSectionCard(title = "Mô hình AI", icon = Icons.Default.Memory) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(modelName, fontWeight = FontWeight.SemiBold)
                Text(
                    "$modelSizeMiB MiB • 48 kHz",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                when (downloadState) {
                    is DownloadState.Success -> "Sẵn sàng"
                    is DownloadState.Downloading -> "Đang tải"
                    is DownloadState.Error -> "Có lỗi"
                    DownloadState.Idle -> "Chưa tải"
                },
                color = if (downloadState is DownloadState.Success) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
        when (downloadState) {
            DownloadState.Idle -> Button(onClick = onDownload, modifier = Modifier.fillMaxWidth()) {
                Text("Tải model")
            }
            is DownloadState.Downloading -> {
                LinearProgressIndicator(
                    progress = { downloadState.progress.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("${(downloadState.progress.coerceIn(0f, 1f) * 100f).toInt()}%")
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(onClick = onPause, modifier = Modifier.weight(1f)) { Text("Tạm dừng") }
                    Button(onClick = onDownload, modifier = Modifier.weight(1f)) { Text("Tiếp tục") }
                }
                TextButton(onClick = onDiscard, modifier = Modifier.fillMaxWidth()) { Text("Xóa phần đã tải") }
            }
            is DownloadState.Error -> {
                Text(downloadState.message, color = MaterialTheme.colorScheme.error)
                Button(onClick = onDownload, modifier = Modifier.fillMaxWidth()) { Text("Thử tải lại") }
            }
            is DownloadState.Success -> Unit
        }
    }
}

@Composable
private fun LoudnessModeRow(
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Medium)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SettingSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Medium)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun ParameterSlider(
    label: String,
    valueLabel: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    onValueChange: (Float) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label)
            Text(valueLabel, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            steps = steps,
        )
    }
}

@Composable
private fun VoiceCleanupAnalysisCard(report: VoiceCleanupReport) {
    ToolSectionCard(title = "Phân tích lần xử lý", icon = Icons.Default.GraphicEq) {
        MetricsLine("Bản gốc", report.source)
        MetricsLine("Sau AI", report.afterAi)
        MetricsLine("File cuối", report.finalOutput)
        HorizontalDivider()
        Text(
            "Mask: mean ${formatNumber(report.mask.mean)}, p10 ${formatNumber(report.mask.p10)}, " +
                "p50 ${formatNumber(report.mask.p50)}, p90 ${formatNumber(report.mask.p90)}",
        )
        Text(
            "Dưới 0,9: ${formatNumber(report.mask.belowPointNinePercent)}% • " +
                "Dưới 0,5: ${formatNumber(report.mask.belowPointFivePercent)}% • " +
                "Gần 1: ${formatNumber(report.mask.nearUnityPercent)}%",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "${report.segmentCount} đoạn • RTF ${formatNumber(report.inferenceRealTimeFactor)}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun MetricsLine(label: String, metrics: VoiceCleanupAudioMetrics) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, fontWeight = FontWeight.SemiBold)
        Text(
            "LUFS ${formatNullable(metrics.integratedLufs)} • RMS ${formatNullable(metrics.rmsDbfs)} dBFS • " +
                "Peak ${formatNullable(metrics.samplePeakDbfs)} dBFS • TP ${formatNullable(metrics.truePeakDbfs)} dBFS",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun formatSigned(value: Float, unit: String): String =
    String.format(Locale.US, "%+.1f %s", value, unit)

private fun formatNumber(value: Double): String = String.format(Locale.US, "%.2f", value)

private fun formatNullable(value: Double?): String = value?.let(::formatNumber) ?: "—"
