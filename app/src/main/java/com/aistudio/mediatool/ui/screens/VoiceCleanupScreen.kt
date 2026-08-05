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
import com.aistudio.mediatool.core.ml.VoiceCleanupConfig
import com.aistudio.mediatool.core.ml.VoiceCleanupLoudnessMode
import com.aistudio.mediatool.core.ml.VoiceCleanupService
import com.aistudio.mediatool.core.ml.VoiceCleanupState
import com.aistudio.mediatool.core.ml.VoiceCleanupWindowMode
import com.aistudio.mediatool.ui.components.AccessibleValueSlider
import com.aistudio.mediatool.ui.components.AudioPreviewSource
import com.aistudio.mediatool.ui.components.AudioResultChoice
import com.aistudio.mediatool.ui.components.AudioResultContent
import com.aistudio.mediatool.ui.components.CompactDropdown
import com.aistudio.mediatool.ui.components.DiagnosticReportCard
import com.aistudio.mediatool.ui.components.MediaInputCard
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
    var windowModeName by rememberSaveable { mutableStateOf(VoiceCleanupWindowMode.BALANCED_10S.name) }
    var cleanupStrength by rememberSaveable { mutableFloatStateOf(65f) }
    var loudnessModeName by rememberSaveable { mutableStateOf(VoiceCleanupLoudnessMode.MATCH_SOURCE.name) }
    var targetLufs by rememberSaveable { mutableFloatStateOf(-16f) }
    var outputGainDb by rememberSaveable { mutableFloatStateOf(0f) }
    var limiterEnabled by rememberSaveable { mutableStateOf(true) }
    var resultSelectionId by rememberSaveable { mutableStateOf("source") }
    var progress by remember { mutableFloatStateOf(0f) }
    var phase by remember { mutableStateOf("Sẵn sàng") }
    var resultFile by remember { mutableStateOf<File?>(null) }

    val selectedUri = selectedUriText?.let(Uri::parse)
    val windowMode = VoiceCleanupWindowMode.fromName(windowModeName)
    val loudnessMode = VoiceCleanupLoudnessMode.fromName(loudnessModeName)
    val cleanupStrengthPercent = cleanupStrength.roundToInt().coerceIn(1, 100)
    val config = VoiceCleanupConfig(
        windowMode = windowMode,
        cleanupStrengthPercent = cleanupStrengthPercent,
        loudnessMode = loudnessMode,
        targetLufs = targetLufs,
        outputGainDb = outputGainDb,
        limiterEnabled = limiterEnabled,
        limiterCeilingDb = -1f,
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
                resultSelectionId = if (selectedUri != null) "source" else "cleaned"
            }
            null -> Unit
        }
    }

    fun resetResult() {
        resultFile = null
        resultSelectionId = "source"
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
            putExtra(VoiceCleanupService.EXTRA_WINDOW_MODE, start.config.windowMode.name)
            putExtra(VoiceCleanupService.EXTRA_CLEANUP_STRENGTH, start.config.cleanupStrengthPercent)
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
    val windowOptions = listOf(
        "Cân bằng · 10 giây",
        "Chất lượng cao · 20 giây",
        "Tối đa · 30 giây",
    )
    val windowIndex = when (windowMode) {
        VoiceCleanupWindowMode.BALANCED_10S -> 0
        VoiceCleanupWindowMode.QUALITY_20S -> 1
        VoiceCleanupWindowMode.MAXIMUM_30S -> 2
    }
    val strengthHint = when (cleanupStrengthPercent) {
        in 1..35 -> "Tự nhiên"
        in 36..70 -> "Cân bằng"
        in 71..90 -> "Làm sạch mạnh"
        else -> "Mạnh nhất"
    }
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
        title = if (resultFile == null) "Làm sạch giọng" else "Kết quả",
        onNavigateBack = onNavigateBack,
        bottomBar = {
            if (resultFile == null) {
                StickyProcessBar(
                    label = "Bắt đầu làm sạch",
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
            }
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
            val output = resultFile
            if (output != null) {
                val choices = buildVoiceCleanupResultChoices(selectedUri, output)
                LaunchedEffect(choices.map { it.id }) {
                    if (choices.none { it.id == resultSelectionId }) {
                        resultSelectionId = choices.firstOrNull()?.id.orEmpty()
                    }
                }
                AudioResultContent(
                    choices = choices,
                    selectedId = resultSelectionId,
                    onSelected = { resultSelectionId = it },
                    processAnotherLabel = "Làm sạch video/bài hát khác",
                    onProcessAnother = {
                        resetResult()
                        picker.launch(arrayOf("audio/*", "video/*"))
                    },
                    onNavigateBack = onNavigateBack,
                )
            } else {
                MediaInputCard(
                    fileName = selectedName,
                    onChoose = { picker.launch(arrayOf("audio/*", "video/*")) },
                )

                UnifiedAudioPlayer(
                    sources = selectedUri?.let {
                        listOf(AudioPreviewSource("source", "Bản gốc", it))
                    }.orEmpty(),
                    title = "Nghe bản gốc",
                )

                VoiceCleanupDownloadSection(
                    state = downloadState,
                    onDownload = viewModel::downloadModel,
                    onPause = viewModel::pauseDownload,
                    onDiscard = viewModel::discardPartialDownload,
                )

                ToolSectionCard(title = "Điều chỉnh âm thanh") {
                    CompactDropdown(
                        label = "Độ dài xử lý AI",
                        values = windowOptions,
                        selectedIndex = windowIndex,
                        onSelected = { index ->
                            windowModeName = when (index) {
                                1 -> VoiceCleanupWindowMode.QUALITY_20S.name
                                2 -> VoiceCleanupWindowMode.MAXIMUM_30S.name
                                else -> VoiceCleanupWindowMode.BALANCED_10S.name
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    AccessibleValueSlider(
                        label = "Mức làm sạch",
                        valueDescription = "$cleanupStrengthPercent% · $strengthHint",
                        value = cleanupStrength,
                        valueRange = 1f..100f,
                        steps = 98,
                        onValueChange = { cleanupStrength = it.roundToInt().toFloat() },
                    )
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
                    CompactDropdown(
                        label = "Chống vỡ tiếng",
                        values = listOf("Tắt", "Bật"),
                        selectedIndex = if (limiterEnabled) 1 else 0,
                        onSelected = { limiterEnabled = it == 1 },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                serviceError?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
                    DiagnosticReportCard(errorContext = it)
                }
                Spacer(modifier = Modifier.height(12.dp))
            }
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

private fun buildVoiceCleanupResultChoices(
    sourceUri: Uri?,
    outputFile: File,
): List<AudioResultChoice> = buildList {
    sourceUri?.let { add(AudioResultChoice("source", "Gốc", it)) }
    add(
        AudioResultChoice(
            id = "cleaned",
            label = "Đã làm sạch",
            uri = Uri.fromFile(outputFile),
            outputFile = outputFile,
        ),
    )
}

private fun formatSigned(value: Float, unit: String): String =
    String.format(Locale.US, "%+.1f %s", value, unit)
