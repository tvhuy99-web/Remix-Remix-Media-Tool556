package com.aistudio.mediatool.ui.screens

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
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
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.navigation.NavController
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aistudio.mediatool.core.SettingsManager
import com.aistudio.mediatool.core.media.RecordingManager
import com.aistudio.mediatool.ui.components.ResultFileActions
import com.aistudio.mediatool.ui.components.ToolScaffold
import com.aistudio.mediatool.ui.components.VideoPlayer

private const val SOURCE_MICROPHONE = 0
private const val SOURCE_INTERNAL = 1

@Composable
fun RecordScreen(navController: NavController) {
    val context = LocalContext.current
    val isRecording by RecordingManager.isRecording.collectAsStateWithLifecycle()
    val isStarting by RecordingManager.isStarting.collectAsStateWithLifecycle()
    val isFinalizing by RecordingManager.isFinalizing.collectAsStateWithLifecycle()
    val isPaused by RecordingManager.isPaused.collectAsStateWithLifecycle()
    val recordingTimeSec by RecordingManager.recordingTimeSec.collectAsStateWithLifecycle()
    val outputFile by RecordingManager.outputFile.collectAsStateWithLifecycle()
    val hasUnsavedFile by RecordingManager.hasUnsavedFile.collectAsStateWithLifecycle()
    val lastError by RecordingManager.lastError.collectAsStateWithLifecycle()
    var selectedSource by rememberSaveable { mutableIntStateOf(SOURCE_MICROPHONE) }

    val projectionManager = remember {
        context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager
    }
    val projectionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val permissionData = result.data
        if (result.resultCode == Activity.RESULT_OK && permissionData != null) {
            RecordingManager.startInternalRecording(context, result.resultCode, permissionData)
        } else {
            Toast.makeText(context, "Bạn chưa cấp quyền ghi âm hệ thống", Toast.LENGTH_SHORT).show()
        }
    }

    fun startForSelectedSource() {
        when (selectedSource) {
            SOURCE_MICROPHONE -> RecordingManager.startRecording(context)
            SOURCE_INTERNAL -> {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                    Toast.makeText(context, "Ghi âm hệ thống cần Android 10 trở lên", Toast.LENGTH_SHORT).show()
                } else {
                    projectionManager?.let { projectionLauncher.launch(it.createScreenCaptureIntent()) }
                        ?: Toast.makeText(context, "Thiết bị không hỗ trợ MediaProjection", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    val microphonePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) startForSelectedSource()
        else Toast.makeText(context, "Cần quyền microphone để ghi âm", Toast.LENGTH_SHORT).show()
    }

    fun requestMicrophoneAndStart() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startForSelectedSource()
        } else {
            microphonePermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { _ -> requestMicrophoneAndStart() }

    fun requestStart() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            requestMicrophoneAndStart()
        }
    }

    ToolScaffold(
        title = "Ghi âm",
        onNavigateBack = { navController.popBackStack() },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (!isRecording && !isStarting && !isFinalizing && !hasUnsavedFile) {
                SingleChoiceSegmentedButtonRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 16.dp),
                ) {
                    SegmentedButton(
                        selected = selectedSource == SOURCE_MICROPHONE,
                        onClick = { selectedSource = SOURCE_MICROPHONE },
                        shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                    ) { Text("Microphone") }
                    SegmentedButton(
                        selected = selectedSource == SOURCE_INTERNAL,
                        onClick = { selectedSource = SOURCE_INTERNAL },
                        shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                    ) { Text("Âm thanh hệ thống") }
                }
            }

            Text(
                text = if (selectedSource == SOURCE_MICROPHONE) {
                    "AAC ${SettingsManager.getAudioBitrateInt(context) / 1_000} kbps • M4A"
                } else {
                    "PCM stereo 44,1 kHz • WAV"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 24.dp),
            )

            Text(
                text = String.format("%02d:%02d", recordingTimeSec / 60, recordingTimeSec % 60),
                fontSize = 56.sp,
                fontWeight = FontWeight.Light,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp)
                    .semantics {
                        contentDescription = "Thời gian đã ghi: ${recordingTimeSec / 60} phút ${recordingTimeSec % 60} giây"
                    },
            )

            Text(
                text = when {
                    isStarting -> "Đang khởi tạo..."
                    isFinalizing -> "Đang hoàn tất và ghi header..."
                    isRecording && isPaused -> "Đã tạm dừng"
                    isRecording -> "Đang ghi âm"
                    hasUnsavedFile -> "Bản ghi đã sẵn sàng"
                    else -> "Sẵn sàng ghi âm"
                },
                textAlign = TextAlign.Center,
                color = when {
                    isRecording && !isPaused -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.primary
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
            )

            lastError?.let { error ->
                Text(
                    text = error,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 16.dp),
                )
            }

            if (!isRecording && !isFinalizing && !hasUnsavedFile) {
                Button(
                    onClick = ::requestStart,
                    enabled = !isStarting,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    ),
                ) {
                    Text(if (isStarting) "ĐANG KHỞI TẠO" else "BẮT ĐẦU GHI", fontWeight = FontWeight.Bold)
                }
            }

            if (isRecording) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N || selectedSource == SOURCE_INTERNAL) {
                        Button(
                            onClick = {
                                if (isPaused) RecordingManager.resumeRecording() else RecordingManager.pauseRecording()
                            },
                            modifier = Modifier
                                .weight(1f)
                                .height(50.dp),
                        ) {
                            Text(if (isPaused) "TIẾP TỤC" else "TẠM DỪNG", fontWeight = FontWeight.Bold)
                        }
                    }
                    FilledTonalButton(
                        onClick = { RecordingManager.stopRecording(context) },
                        modifier = Modifier
                            .weight(1f)
                            .height(50.dp),
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer,
                        ),
                    ) { Text("KẾT THÚC", fontWeight = FontWeight.Bold) }
                }
            }

            val recordedFile = outputFile
            if (hasUnsavedFile && recordedFile != null) {
                Column(
                    modifier = Modifier.padding(top = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text("Nghe lại", fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 8.dp))
                            VideoPlayer(uri = Uri.fromFile(recordedFile))
                        }
                    }
                    ResultFileActions(file = recordedFile)
                    OutlinedButton(
                        onClick = { RecordingManager.clearOutputFile(context) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .semantics { contentDescription = "Xóa bản ghi hiện tại và ghi lại" },
                    ) {
                        Text("Xóa và ghi lại", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}
