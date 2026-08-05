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
import com.aistudio.mediatool.core.SettingsManager
import com.aistudio.mediatool.core.diagnostics.DiagnosticLogger
import com.aistudio.mediatool.core.diagnostics.DiagnosticRedactor
import com.aistudio.mediatool.core.ml.DownloadState
import com.aistudio.mediatool.core.ml.SeparationState
import com.aistudio.mediatool.core.ml.StemInferenceBackend
import com.aistudio.mediatool.core.ml.StemMode
import com.aistudio.mediatool.core.ml.StemModelDescriptor
import com.aistudio.mediatool.core.ml.StemModelRegistry
import com.aistudio.mediatool.core.ml.StemService
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

private data class PendingStemStart(
    val uriText: String,
    val modelPath: String,
    val modelId: String,
)

@Composable
fun StemScreen(onNavigateBack: () -> Unit) {
    val context = LocalContext.current
    val stemViewModel: StemViewModel = viewModel()
    val downloadState by stemViewModel.downloadState.collectAsStateWithLifecycle()
    val selectedModel by stemViewModel.selectedModel.collectAsStateWithLifecycle()
    val serviceIsProcessing by StemService.isProcessing.collectAsStateWithLifecycle()
    val serviceState by StemService.separationState.collectAsStateWithLifecycle()
    val serviceError by StemService.errorMsg.collectAsStateWithLifecycle()

    var selectedAudioUriText by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedAudioName by rememberSaveable { mutableStateOf<String?>(null) }
    var modeIndex by rememberSaveable { mutableStateOf(SettingsManager.getStemModeIndex(context)) }
    var mdxDenoiseEnabled by rememberSaveable {
        mutableStateOf(SettingsManager.isStemMdxDenoiseEnabled(context))
    }
    var resultSelectionId by rememberSaveable { mutableStateOf("source") }
    var separationProgress by remember { mutableFloatStateOf(0f) }
    var result by remember { mutableStateOf<SeparationState.Success?>(null) }

    val selectedAudioUri = selectedAudioUriText?.let(Uri::parse)
    val selectedMode = StemMode.fromSettingsIndex(modeIndex)
    val modeModels = StemModelRegistry.modelsFor(selectedMode)
    val downloadedModel = (downloadState as? DownloadState.Success)?.file

    LaunchedEffect(Unit) {
        stemViewModel.refreshConfiguredModel()
        StemService.restorePersistedState(context)
    }

    LaunchedEffect(selectedModel.mode.settingsIndex) {
        modeIndex = selectedModel.mode.settingsIndex
    }

    LaunchedEffect(serviceState) {
        when (val state = serviceState) {
            is SeparationState.Progress -> separationProgress = state.value.coerceIn(0f, 1f)
            is SeparationState.Success -> {
                result = state
                resultSelectionId = if (selectedAudioUri != null) "source" else "vocals"
            }
            null -> Unit
        }
    }

    fun resetResult() {
        result = null
        resultSelectionId = "source"
        separationProgress = 0f
        StemService.clearState(context)
    }

    val audioPicker = rememberLauncherForActivityResult(GetContentWithMimeTypes()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        DocumentUtils.persistReadPermission(context, uri)
        selectedAudioUriText = uri.toString()
        selectedAudioName = DocumentUtils.displayName(context, uri)
        resetResult()
    }

    fun launchStemService(start: PendingStemStart) {
        resetResult()
        val intent = Intent(context, StemService::class.java).apply {
            action = StemService.ACTION_START
            putExtra(StemService.EXTRA_URI, start.uriText)
            putExtra(StemService.EXTRA_MODEL_FILE, start.modelPath)
            putExtra(StemService.EXTRA_MODEL_ID, start.modelId)
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ContextCompat.startForegroundService(context, intent)
            } else {
                context.startService(intent)
            }
        } catch (error: Exception) {
            DiagnosticLogger.error(
                component = "StemScreen",
                event = "service_start_failed",
                message = error.message,
                fields = mapOf(
                    "model_id" to start.modelId,
                    "source_id" to DiagnosticRedactor.stableId(start.uriText),
                ),
                error = error,
            )
            Toast.makeText(
                context,
                "Không thể bắt đầu: ${error.message ?: "không xác định"}",
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    var pendingStart by remember { mutableStateOf<PendingStemStart?>(null) }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { _ ->
        pendingStart?.let(::launchStemService)
        pendingStart = null
    }

    fun startWithPermission() {
        val uri = selectedAudioUri ?: return
        val modelFile = downloadedModel ?: return
        val start = PendingStemStart(uri.toString(), modelFile.absolutePath, selectedModel.id)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            pendingStart = start
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            launchStemService(start)
        }
    }

    ToolScaffold(
        title = if (result == null) "Tách nhạc" else "Kết quả",
        onNavigateBack = onNavigateBack,
        bottomBar = {
            if (result == null) {
                StickyProcessBar(
                    label = "Bắt đầu tách",
                    enabled = selectedAudioUri != null && downloadedModel != null,
                    processing = serviceIsProcessing,
                    progress = separationProgress,
                    phase = "Đang tách",
                    onClick = ::startWithPermission,
                    onCancel = {
                        context.startService(
                            Intent(context, StemService::class.java).setAction(StemService.ACTION_STOP),
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
            val success = result
            if (success != null) {
                val choices = buildStemResultChoices(selectedAudioUri, success)
                LaunchedEffect(choices.map { it.id }) {
                    if (choices.none { it.id == resultSelectionId }) {
                        resultSelectionId = choices.firstOrNull()?.id.orEmpty()
                    }
                }
                AudioResultContent(
                    choices = choices,
                    selectedId = resultSelectionId,
                    onSelected = { resultSelectionId = it },
                    processAnotherLabel = "Tách video/bài hát khác",
                    onProcessAnother = {
                        resetResult()
                        audioPicker.launch(arrayOf("audio/*", "video/*"))
                    },
                    onNavigateBack = onNavigateBack,
                )
            } else {
                MediaInputCard(
                    fileName = selectedAudioName,
                    onChoose = { audioPicker.launch(arrayOf("audio/*", "video/*")) },
                )

                UnifiedAudioPlayer(
                    sources = selectedAudioUri?.let {
                        listOf(AudioPreviewSource("source", "Bản gốc", it))
                    }.orEmpty(),
                    title = "Nghe bản gốc",
                )

                ToolSectionCard(title = "Thiết lập") {
                    CompactDropdown(
                        label = "Kết quả",
                        values = listOf(
                            "Giọng hát và nhạc nền",
                            "Giọng, trống, bass và phần khác",
                        ),
                        selectedIndex = modeIndex,
                        onSelected = { index ->
                            if (modeIndex != index) {
                                modeIndex = index
                                SettingsManager.setStemModeIndex(context, index)
                                val mode = StemMode.fromSettingsIndex(index)
                                val model = StemModelRegistry.resolve(
                                    mode,
                                    SettingsManager.getStemModelId(context, index),
                                )
                                stemViewModel.selectModel(model.id)
                                resetResult()
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )

                    CompactDropdown(
                        label = "Mô hình",
                        values = modeModels.map(::shortModelName),
                        selectedIndex = modeModels.indexOfFirst { it.id == selectedModel.id }.coerceAtLeast(0),
                        onSelected = { index ->
                            stemViewModel.selectModel(modeModels[index].id)
                            resetResult()
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )

                    if (selectedModel.backend == StemInferenceBackend.MDX_LITERT) {
                        CompactDropdown(
                            label = "Chất lượng UVR",
                            values = listOf("Tiêu chuẩn", "Làm sạch kỹ"),
                            selectedIndex = if (mdxDenoiseEnabled) 1 else 0,
                            onSelected = { index ->
                                mdxDenoiseEnabled = index == 1
                                SettingsManager.setStemMdxDenoiseEnabled(context, mdxDenoiseEnabled)
                                resetResult()
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            if (mdxDenoiseEnabled) {
                                "Chạy hai lượt đối xứng để giảm nhiễu, thời gian xử lý gần gấp đôi."
                            } else {
                                "Một lượt xử lý, nhanh hơn và dùng ít điện hơn."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    StemDownloadSection(
                        selectedModel = selectedModel,
                        state = downloadState,
                        onDownload = stemViewModel::downloadModel,
                        onPause = stemViewModel::pauseDownload,
                        onDiscard = stemViewModel::discardPartialDownload,
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
private fun StemDownloadSection(
    selectedModel: StemModelDescriptor,
    state: DownloadState,
    onDownload: () -> Unit,
    onPause: () -> Unit,
    onDiscard: () -> Unit,
) {
    when (state) {
        is DownloadState.Success -> Unit
        DownloadState.Idle -> Button(onClick = onDownload, modifier = Modifier.fillMaxWidth()) {
            Text("Tải ${shortModelName(selectedModel)}")
        }
        is DownloadState.Downloading -> {
            val percent = (state.progress.coerceIn(0f, 1f) * 100f).toInt()
            LinearProgressIndicator(
                progress = { state.progress.coerceIn(0f, 1f) },
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics {
                        contentDescription = "Tiến trình tải ${shortModelName(selectedModel)}"
                        stateDescription = "$percent phần trăm"
                    },
            )
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
        is DownloadState.Error -> {
            Text(state.message, color = MaterialTheme.colorScheme.error)
            Button(onClick = onDownload, modifier = Modifier.fillMaxWidth()) {
                Text("Thử tải lại")
            }
        }
    }
}

private fun shortModelName(model: StemModelDescriptor): String = when (model.id) {
    StemModelRegistry.UVR_MDX_VOC_FT_LITERT_ID -> "UVR MDX-Net"
    else -> "Demucs"
}

private fun buildStemResultChoices(
    sourceUri: Uri?,
    success: SeparationState.Success,
): List<AudioResultChoice> = buildList {
    sourceUri?.let { add(AudioResultChoice("source", "Gốc", it)) }
    add(
        AudioResultChoice(
            id = "vocals",
            label = "Giọng hát",
            uri = Uri.fromFile(success.vocalsFile),
            outputFile = success.vocalsFile,
        ),
    )
    val hasFourStems = success.drumsFile != null || success.bassFile != null || success.otherFile != null
    if (hasFourStems) {
        success.drumsFile?.let {
            add(AudioResultChoice("drums", "Trống", Uri.fromFile(it), it))
        }
        success.bassFile?.let {
            add(AudioResultChoice("bass", "Bass", Uri.fromFile(it), it))
        }
        success.otherFile?.let {
            add(AudioResultChoice("other", "Phần khác", Uri.fromFile(it), it))
        }
    } else {
        add(
            AudioResultChoice(
                id = "music",
                label = "Nhạc nền",
                uri = Uri.fromFile(success.musicFile),
                outputFile = success.musicFile,
            ),
        )
    }
}
