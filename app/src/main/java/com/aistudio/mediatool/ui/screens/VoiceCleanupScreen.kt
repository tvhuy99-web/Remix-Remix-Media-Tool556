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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import com.aistudio.mediatool.core.ml.VoiceCleanupService
import com.aistudio.mediatool.core.ml.VoiceCleanupState
import com.aistudio.mediatool.ui.components.DiagnosticReportCard
import com.aistudio.mediatool.ui.components.ResultFileActions
import com.aistudio.mediatool.ui.components.ToolScaffold
import java.io.File

@Composable
fun VoiceCleanupScreen(onNavigateBack: () -> Unit) {
    val context = LocalContext.current
    val viewModel: VoiceCleanupViewModel = viewModel()
    val downloadState by viewModel.downloadState.collectAsStateWithLifecycle()
    val serviceState by VoiceCleanupService.cleanupState.collectAsStateWithLifecycle()
    val serviceIsProcessing by VoiceCleanupService.isProcessing.collectAsStateWithLifecycle()
    val serviceError by VoiceCleanupService.errorMsg.collectAsStateWithLifecycle()

    var selectedUriText by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedName by rememberSaveable { mutableStateOf("Chọn file ghi âm hoặc video") }
    var progress by remember { mutableFloatStateOf(0f) }
    var phase by remember { mutableStateOf("Sẵn sàng") }
    var resultFile by remember { mutableStateOf<File?>(null) }
    val selectedUri = selectedUriText?.let(Uri::parse)

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
            }
            null -> Unit
        }
    }

    fun resetResult() {
        resultFile = null
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

    fun launchService(uriText: String, modelPath: String) {
        val intent = Intent(context, VoiceCleanupService::class.java).apply {
            action = VoiceCleanupService.ACTION_START
            putExtra(VoiceCleanupService.EXTRA_URI, uriText)
            putExtra(VoiceCleanupService.EXTRA_MODEL_FILE, modelPath)
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
                fields = mapOf("source_id" to DiagnosticRedactor.stableId(uriText)),
                error = error,
            )
            Toast.makeText(
                context,
                "Không thể bắt đầu xử lý nền: ${error.message ?: "không xác định"}",
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    var pendingStart by remember { mutableStateOf<Pair<String, String>?>(null) }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { _ ->
        pendingStart?.let { (uriText, modelPath) -> launchService(uriText, modelPath) }
        pendingStart = null
    }

    fun startWithPermission(uri: Uri, modelPath: String) {
        val args = uri.toString() to modelPath
        resetResult()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            pendingStart = args
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            launchService(args.first, args.second)
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
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(viewModel.model.displayName, fontWeight = FontWeight.Bold)
                    Text(
                        "Khử nhiễu giọng nói ngoại tuyến ở 48 kHz. Chất lượng được ưu tiên hơn tốc độ.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        "Model ${viewModel.model.downloadSizeMiB} MiB • ${viewModel.model.licenseName}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "MossFormer2 cần nhiều RAM và có thể xử lý chậm trên điện thoại.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            when (val state = downloadState) {
                DownloadState.Idle -> Button(
                    onClick = viewModel::downloadModel,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Tải model MossFormer2") }

                is DownloadState.Downloading -> {
                    Text("Đang tải và kiểm tra model...")
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
                    when {
                        serviceIsProcessing -> {
                            CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
                            Text(phase, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
                            LinearProgressIndicator(
                                progress = { progress.coerceIn(0f, 1f) },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Text(
                                "${(progress.coerceIn(0f, 1f) * 100f).toInt()}%",
                                modifier = Modifier.fillMaxWidth(),
                                textAlign = TextAlign.Center,
                            )
                            OutlinedButton(
                                onClick = {
                                    context.startService(
                                        Intent(context, VoiceCleanupService::class.java)
                                            .setAction(VoiceCleanupService.ACTION_STOP),
                                    )
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text("Hủy xử lý") }
                        }

                        resultFile != null -> {
                            Text(
                                "Giọng nói đã được làm sạch",
                                style = MaterialTheme.typography.titleLarge,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            ResultFileActions(file = checkNotNull(resultFile))
                            OutlinedButton(
                                onClick = {
                                    selectedUriText = null
                                    selectedName = "Chọn file ghi âm hoặc video"
                                    resetResult()
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text("Xử lý file khác") }
                        }

                        else -> {
                            Button(
                                onClick = { picker.launch(arrayOf("audio/*", "video/*")) },
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text(selectedName) }
                            selectedUri?.let { uri ->
                                Button(
                                    onClick = { startWithPermission(uri, state.file.absolutePath) },
                                    modifier = Modifier.fillMaxWidth(),
                                ) { Text("Bắt đầu làm sạch") }
                            }
                            serviceError?.let { error ->
                                Text(error, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
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
            diagnosticReason?.let { DiagnosticReportCard(errorContext = it) }
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}
