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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
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
import com.aistudio.mediatool.ui.components.AccessibleSwitchRow
import com.aistudio.mediatool.ui.components.AccessibleValueSlider
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
                phase = "Đã hoàn tất"
                resultFile = state.outputFile
                resultReport = state.report
            }
            null -> Unit
        }
    }

    fun resetResult() {
        resultFile = null
        resultReport = null
        showAnalysis = false
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
                "Không thể bắt đầu: ${error.message ?: "không xác định"}",
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
    val loudnessOptions = listOf(
        "Giống bản gốc",
        "Giữ nguyên kết quả lọc",
        "Đặt âm lượng mong muốn",
    )
    val loudnessIndex = when (loudnessMode) {
        VoiceCleanupLoudnessMode.MATCH_SOURCE -> 0
        VoiceCleanupLoudnessMode.RAW -> 1
        VoiceCleanupLoudnessMode.TARGET_LUFS -> 2
    }

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
                onChoose = { picker.launch(arrayOf("audio/*", "video/*")) },
            )

            val previewSources = buildList {
                selectedUri?.let { add(AudioPreviewSource("source", "Bản gốc", it)) }
                resultFile?.let { add(AudioPreviewSource("result", "Kết quả", Uri.fromFile(it))) }
            }
            UnifiedAudioPlayer(sources = previewSources, title = "Nghe thử")

            VoiceCleanupDownloadSection(
                state = downloadState,
                onDownload = viewModel::downloadModel,
                onPause = viewModel::pauseDownload,
                onDiscard = viewModel::discardPartialDownload,
            )

            ToolSectionCard(title = "Điều chỉnh âm thanh") {
                CompactDropdown(
                    label = "Giữ âm lượng",
                    values = loudnessOptions,
                    selectedIndex = loudnessIndex,
                    onSelected = { index ->
                        loudnessModeName = when (index) {
                            1 -> VoiceCleanupLoudnessMode.RAW.name
                            2 -> VoiceCleanupLoudnessMode.TARGET_LUFS.name
                            else -> VoiceCleanupLoudnessMode.MATCH_SOURCE.name
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (loudnessMode == VoiceCleanupLoudnessMode.TARGET_LUFS) {
                    AccessibleValueSlider(
                        label = "Âm lượng mong muốn",
                        valueDescription = "${targetLufs.roundToInt()} LUFS",
                        value = targetLufs,
                        valueRange = -30f..-8f,
                        steps = 21,
                        onValueChange = { targetLufs = it.roundToInt().toFloat() },
                    )
                }
                AccessibleValueSlider(
                    label = "Tăng hoặc giảm âm lượng",
                    valueDescription = formatSigned(outputGainDb, "dB"),
                    value = outputGainDb,
                    valueRange = -12f..12f,
                    steps = 47,
                    onValueChange = { outputGainDb = (it * 2f).roundToInt() / 2f },
                )
                AccessibleSwitchRow(
                    label = "Chống vỡ tiếng",
                    checked = limiterEnabled,
                    onCheckedChange = { limiterEnabled = it },
                )
                if (limiterEnabled) {
                    AccessibleValueSlider(
                        label = "Mức âm lượng cao nhất",
                        valueDescription = String.format(Locale.US, "%.1f dB", limiterCeilingDb),
                        value = limiterCeilingDb,
                        valueRange = -6f..-0.5f,
                        steps = 10,
                        onValueChange = { limiterCeilingDb = (it * 2f).roundToInt() / 2f },
                    )
                }
            }

            resultFile?.let { file ->
                ToolSectionCard(title = "Kết quả") {
                    ResultFileActions(file = file)
                    resultReport?.let {
                        TextButton(
                            onClick = { showAnalysis = !showAnalysis },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(if (showAnalysis) "Ẩn thông tin lần xử lý" else "Xem thông tin lần xử lý")
                        }
                    }
                }
            }

            if (showAnalysis) resultReport?.let { VoiceCleanupAnalysisCard(it) }

            serviceError?.let {
                Text(it, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
                DiagnosticReportCard(errorContext = it)
            }
            Spacer(modifier = Modifier.height(12.dp))
        }
    }
}

@Composable
private fun VoiceCleanupDownloadSection(
    state: DownloadState,
    onDownload: () -> Unit,
    onPause: () -> Unit,
    onDiscard: () -> Unit,
) {
    when (state) {
        is DownloadState.Success -> Unit
        DownloadState.Idle -> Button(onClick = onDownload, modifier = Modifier.fillMaxWidth()) {
            Text("Tải bộ xử lý giọng nói")
        }
        is DownloadState.Downloading -> ToolSectionCard(title = "Đang tải bộ xử lý") {
            val percent = (state.progress.coerceIn(0f, 1f) * 100f).toInt()
            LinearProgressIndicator(
                progress = { state.progress.coerceIn(0f, 1f) },
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics {
                        contentDescription = "Tiến trình tải"
                        stateDescription = "$percent phần trăm"
                    },
            )
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(onClick = onPause, modifier = Modifier.fillMaxWidth()) {
                    Text("Tạm dừng tải")
                }
                Button(onClick = onDownload, modifier = Modifier.fillMaxWidth()) {
                    Text("Tiếp tục tải")
                }
                TextButton(onClick = onDiscard, modifier = Modifier.fillMaxWidth()) {
                    Text("Xóa phần đã tải")
                }
            }
        }
        is DownloadState.Error -> ToolSectionCard(title = "Không tải được") {
            Text(state.message, color = MaterialTheme.colorScheme.error)
            Button(onClick = onDownload, modifier = Modifier.fillMaxWidth()) {
                Text("Thử tải lại")
            }
        }
    }
}

@Composable
private fun VoiceCleanupAnalysisCard(report: VoiceCleanupReport) {
    ToolSectionCard(title = "Thông tin lần xử lý") {
        MetricsLine("Bản gốc", report.source)
        MetricsLine("Sau khi lọc", report.afterAi)
        MetricsLine("Tệp cuối", report.finalOutput)
        Column(
            modifier = Modifier.semantics(mergeDescendants = true) {
                contentDescription = buildString {
                    append("Mức tác động của bộ lọc. ")
                    append("Trung bình ${formatNumber(report.mask.mean)}. ")
                    append("Mức thấp ${formatNumber(report.mask.p10)}. ")
                    append("Mức giữa ${formatNumber(report.mask.p50)}. ")
                    append("Mức cao ${formatNumber(report.mask.p90)}. ")
                    append("Lọc mạnh ${formatNumber(report.mask.belowPointFivePercent)} phần trăm. ")
                    append("Gần như giữ nguyên ${formatNumber(report.mask.nearUnityPercent)} phần trăm.")
                }
            },
        ) {
            Text("Mức tác động của bộ lọc", modifier = Modifier.clearAndSetSemantics { })
            Text(
                "Trung bình ${formatNumber(report.mask.mean)} • thấp ${formatNumber(report.mask.p10)} • " +
                    "giữa ${formatNumber(report.mask.p50)} • cao ${formatNumber(report.mask.p90)}",
                modifier = Modifier.clearAndSetSemantics { },
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "Lọc mạnh ${formatNumber(report.mask.belowPointFivePercent)}% • " +
                    "gần như giữ nguyên ${formatNumber(report.mask.nearUnityPercent)}%",
                modifier = Modifier.clearAndSetSemantics { },
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun MetricsLine(label: String, metrics: VoiceCleanupAudioMetrics) {
    val description = "$label. Âm lượng ${formatNullable(metrics.integratedLufs)} LUFS. " +
        "Mức trung bình ${formatNullable(metrics.rmsDbfs)} dB. " +
        "Đỉnh ${formatNullable(metrics.samplePeakDbfs)} dB. " +
        "Đỉnh thực ${formatNullable(metrics.truePeakDbfs)} dB."
    Column(
        modifier = Modifier.semantics(mergeDescendants = true) { contentDescription = description },
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(label, modifier = Modifier.clearAndSetSemantics { })
        Text(
            "Âm lượng ${formatNullable(metrics.integratedLufs)} LUFS • " +
                "trung bình ${formatNullable(metrics.rmsDbfs)} dB • " +
                "đỉnh ${formatNullable(metrics.samplePeakDbfs)} dB • " +
                "đỉnh thực ${formatNullable(metrics.truePeakDbfs)} dB",
            modifier = Modifier.clearAndSetSemantics { },
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

private fun formatSigned(value: Float, unit: String): String =
    String.format(Locale.US, "%+.1f %s", value, unit)

private fun formatNumber(value: Double): String = String.format(Locale.US, "%.2f", value)

private fun formatNullable(value: Double?): String = value?.let(::formatNumber) ?: "không có"
