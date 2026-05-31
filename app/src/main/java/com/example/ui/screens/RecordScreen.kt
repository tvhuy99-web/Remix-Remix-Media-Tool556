package com.example.ui.screens

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.navigation.NavController
import com.example.ui.components.VideoPlayer
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordScreen(navController: NavController) {
    val context = LocalContext.current
    val isRecording by com.example.core.media.RecordingManager.isRecording.collectAsState()
    val isPaused by com.example.core.media.RecordingManager.isPaused.collectAsState()
    val recordingTimeSec by com.example.core.media.RecordingManager.recordingTimeSec.collectAsState()
    val outputFile by com.example.core.media.RecordingManager.outputFile.collectAsState()
    val hasUnsavedFile by com.example.core.media.RecordingManager.hasUnsavedFile.collectAsState()
    
    val coroutineScope = rememberCoroutineScope()
    var selectedSource by remember { mutableStateOf(0) } // 0 = MIC, 1 = INTERNAL

    val saveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(if (selectedSource == 1) "audio/wav" else "audio/mp4")) { destUri ->
        destUri?.let { uri ->
            outputFile?.let { inFile ->
                coroutineScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                    try {
                        context.contentResolver.openOutputStream(uri)?.use { outStream ->
                            inFile.inputStream().use { inStream ->
                                inStream.copyTo(outStream)
                            }
                        }
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                            Toast.makeText(context, "Đã lưu âm thanh thành công!", Toast.LENGTH_LONG).show()
                        }
                    } catch (e: Exception) {
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                            Toast.makeText(context, "Lỗi khi lưu âm thanh: ${e.message}", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            // Không stop() khi thoat ra cho phep chay nen
            // Chi dung qua phuong thuc stop tu UI.
        }
    }

    val mediaProjectionManager = remember { context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager? }
    val projectionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val mediaProjection = mediaProjectionManager?.getMediaProjection(result.resultCode, result.data!!)
            if (mediaProjection != null) {
                com.example.core.media.RecordingManager.startInternalRecording(context, mediaProjection)
            } else {
                Toast.makeText(context, "Lỗi MediaProjection", Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(context, "Chưa cấp quyền màn hình/âm thanh", Toast.LENGTH_SHORT).show()
        }
    }

    fun startRecordingAction() {
        if (selectedSource == 0) {
            com.example.core.media.RecordingManager.startRecording(context)
        } else {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                mediaProjectionManager?.let {
                    projectionLauncher.launch(it.createScreenCaptureIntent())
                } ?: Toast.makeText(context, "Không hỗ trợ trên thiết bị này", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(context, "Chỉ hỗ trợ Android 10 trở lên", Toast.LENGTH_SHORT).show()
            }
        }
    }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            startRecordingAction()
        } else {
            Toast.makeText(context, "Cần cấp quyền Microphone", Toast.LENGTH_SHORT).show()
        }
    }

    fun stopRecordingAction() {
        com.example.core.media.RecordingManager.stopRecording(context)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Ghi âm đính kèm", fontSize = 18.sp, fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (!isRecording && !hasUnsavedFile) {
                SingleChoiceSegmentedButtonRow(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)
                ) {
                    SegmentedButton(
                        selected = selectedSource == 0,
                        onClick = { selectedSource = 0 },
                        shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2)
                    ) {
                        Text("Mic (Bên ngoài)")
                    }
                    SegmentedButton(
                        selected = selectedSource == 1,
                        onClick = { selectedSource = 1 },
                        shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2)
                    ) {
                        Text("Hệ thống (Bên trong)")
                    }
                }
            }

            Text(
                text = if (selectedSource == 0) {
                    "Chất lượng: ${com.example.core.SettingsManager.getAudioBitrateInt(context) / 1000}kbps | Định dạng lưu: M4A"
                } else {
                    "Audio PCM 44.1kHz | Định dạng lưu: WAV"
                },
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 24.dp)
            )

            Text(
                text = String.format("%02d:%02d", recordingTimeSec / 60, recordingTimeSec % 60),
                fontSize = 56.sp,
                fontWeight = FontWeight.Light,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp)
                    .semantics { contentDescription = "Thời gian đã ghi: ${recordingTimeSec / 60} phút ${recordingTimeSec % 60} giây" }
            )

            Text(
                text = if (isRecording) {
                    if (isPaused) "Tạm dừng ghi âm" else "Đang ghi âm..."
                } else if (hasUnsavedFile) {
                    "Đã ghi xong"
                } else {
                    "Sẵn sàng ghi âm"
                },
                textAlign = TextAlign.Center,
                color = if (isRecording) {
                    if (isPaused) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.primary
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 24.dp)
            )

            if (!isRecording && !hasUnsavedFile) {
                Button(
                    onClick = {
                        if (selectedSource == 0) {
                            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                                startRecordingAction()
                            } else {
                                launcher.launch(Manifest.permission.RECORD_AUDIO)
                            }
                        } else {
                            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                                startRecordingAction() // Will request MediaProjection
                            } else {
                                launcher.launch(Manifest.permission.RECORD_AUDIO)
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer
                    )
                ) {
                    Text("BẮT ĐẦU GHI ÂM", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
            }

            if (isRecording) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N || selectedSource == 1) {
                        Button(
                            onClick = { 
                                if (isPaused) com.example.core.media.RecordingManager.resumeRecording() 
                                else com.example.core.media.RecordingManager.pauseRecording() 
                            },
                            modifier = Modifier.weight(1f).height(50.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isPaused) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.secondaryContainer,
                                contentColor = if (isPaused) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        ) {
                            Text(if (isPaused) "TIẾP TỤC" else "TẠM DỪNG", fontWeight = FontWeight.Bold)
                        }
                    }

                    FilledTonalButton(
                        onClick = { stopRecordingAction() },
                        modifier = Modifier.weight(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N || selectedSource == 1) 1f else 1f).height(50.dp),
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Text("KẾT THÚC GHI", fontWeight = FontWeight.Bold)
                    }
                }
            }

            if (hasUnsavedFile && outputFile != null) {
                Column(modifier = Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text("Đường dẫn file lưu tại:\n${outputFile?.absolutePath}", style = MaterialTheme.typography.bodySmall)

                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text("Nghe lại file:", fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 8.dp))
                            VideoPlayer(uri = Uri.fromFile(outputFile))
                        }
                    }

                    Button(
                        onClick = { saveLauncher.launch("recording_${System.currentTimeMillis()}." + if (outputFile?.name?.endsWith("wav") == true) "wav" else "m4a") },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    ) {
                        Text("Lưu file vào thiết bị", fontWeight = FontWeight.Bold)
                    }

                    OutlinedButton(
                        onClick = {
                            com.example.core.media.RecordingManager.clearOutputFile()
                        },
                        modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Xóa file vừa ghi âm và bắt đầu ghi lại từ đầu" }
                    ) {
                        Text("Xóa file này & Ghi âm lại", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            TextButton(
                onClick = { navController.popBackStack() },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Quay lại Menu chính")
            }
        }
    }
}

