package com.aistudio.mediatool.ui.screens

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.aistudio.mediatool.core.DocumentUtils
import com.aistudio.mediatool.core.diagnostics.DiagnosticLogger
import com.aistudio.mediatool.core.FileExportManager
import com.aistudio.mediatool.core.media.MediaEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt
import com.aistudio.mediatool.ui.components.ToolScaffold
import com.aistudio.mediatool.ui.components.ResultFileActions

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubScreen(navController: NavController, subViewModel: SubViewModel = viewModel()) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val mediaEngine = remember { MediaEngine(context) }
    
    val state by subViewModel.state.collectAsStateWithLifecycle()
    
    // Khởi tạo Player & TTS
    LaunchedEffect(Unit) {
        subViewModel.initPlayer(context)
    }

    // Laucher chọn Video
    val vidLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri?.let {
            DocumentUtils.persistReadPermission(context, it)
            val fileName = DocumentUtils.displayName(context, it)
            subViewModel.setVideo(it, fileName)
            Toast.makeText(context, "Đã nạp file Video", Toast.LENGTH_SHORT).show()
        }
    }
    
    // Laucher chọn Phụ đề
    val subLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri?.let {
            DocumentUtils.persistReadPermission(context, it)
            val fileName = DocumentUtils.displayName(context, it)
            coroutineScope.launch(Dispatchers.IO) {
                try {
                    val content = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: ""
                    withContext(Dispatchers.Main) {
                        subViewModel.setSubtitle(uri, fileName, content)
                        Toast.makeText(context, "Đã nạp phụ đề", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "Lỗi đọc file phụ đề", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }


    fun startExtractSubtitles() {
        val videoUri = state.videoUri
        if (videoUri == null) {
            Toast.makeText(context, "Vui lòng chọn video trước", Toast.LENGTH_SHORT).show()
            return
        }
        subViewModel.startExtraction()
        coroutineScope.launch(Dispatchers.IO) {
            var pendingOutput: File? = null
            try {
                val safPath = mediaEngine.getSafParameter(videoUri)
                    ?: error("Không thể đọc video đã chọn")
                val outputFile = FileExportManager.resultFile(context, "subtitles", "srt")
                pendingOutput = outputFile
                val command = "-y -hide_banner -loglevel warning -i \"$safPath\" -map 0:s:0? -c:s srt \"${outputFile.absolutePath}\""

                mediaEngine.executeFFmpegCommand(command, diagnosticPhase = "extract_subtitle").collect { execState ->
                    when (execState) {
                        is MediaEngine.ExecutionState.Connecting -> withContext(Dispatchers.Main) {
                            subViewModel.updateExtractionProgress("Đang kiểm tra luồng phụ đề…")
                        }
                        is MediaEngine.ExecutionState.Progress -> withContext(Dispatchers.Main) {
                            subViewModel.updateExtractionProgress("Đang trích xuất phụ đề…")
                        }
                        is MediaEngine.ExecutionState.Success -> {
                            require(outputFile.isFile && outputFile.length() > 0L) {
                                "Video không có luồng phụ đề đính kèm"
                            }
                            val content = outputFile.readText()
                            require(content.isNotBlank()) { "Luồng phụ đề trích xuất bị rỗng" }
                            withContext(Dispatchers.Main) {
                                subViewModel.finishExtraction(true, outputFile.absolutePath, "Trích xuất thành công")
                                subViewModel.setSubtitle(Uri.fromFile(outputFile), outputFile.name, content)
                                Toast.makeText(context, "Đã trích xuất và nạp phụ đề", Toast.LENGTH_SHORT).show()
                            }
                            pendingOutput = null
                        }
                        is MediaEngine.ExecutionState.Error -> {
                            throw IllegalStateException("Video không có luồng phụ đề phù hợp")
                        }
                    }
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                pendingOutput?.delete()
                throw cancelled
            } catch (error: Exception) {
                pendingOutput?.delete()
                DiagnosticLogger.error(
                    component = "SubScreen",
                    event = "subtitle_extraction_failed",
                    message = error.message,
                    error = error,
                )
                withContext(Dispatchers.Main) {
                    subViewModel.finishExtraction(false, "", error.message ?: "Không thể trích xuất phụ đề")
                }
            }
        }
    }

    ToolScaffold(
        title = "Phụ đề",
        onNavigateBack = { navController.popBackStack() },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // CẢNH BÁO LỖI KHỞI TẠO TTS NẾU CÓ
            if (state.ttsStatusMessage != null) {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Lỗi hệ thống giọng đọc: ${state.ttsStatusMessage}" }
                ) {
                    Text(
                        text = state.ttsStatusMessage!!,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(16.dp),
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center
                    )
                }
            }

            // [1] CHỌN VIDEO
            Text("[1] CHỌN VIDEO", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Button(onClick = { vidLauncher.launch(arrayOf("video/*", "audio/*")) }, modifier = Modifier.fillMaxWidth()) {
                Text("Chọn Video")
            }
            Text("Video: ${state.videoFileName}", color = MaterialTheme.colorScheme.onSurfaceVariant)

            HorizontalDivider()

            // [2] CHỌN PHỤ ĐỀ ĐỂ ĐỌC
            Text("[2] CHỌN PHỤ ĐỀ ĐỂ ĐỌC", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Button(onClick = { subLauncher.launch(arrayOf("application/x-subrip", "text/vtt", "text/plain", "application/octet-stream")) }, modifier = Modifier.fillMaxWidth()) {
                Text("Chọn File Phụ đề (.srt / .vtt)")
            }
            Text("Phụ đề: ${state.subFileName} (${state.subCount} câu)", color = MaterialTheme.colorScheme.onSurfaceVariant)

            // HIỂN THỊ PHỤ ĐỀ TRỰC TIẾP TRÊN MÀN HÌNH
            if (state.currentSubtitleText.isNotEmpty()) {
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.9f),
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
                ) {
                    Text(
                        text = state.currentSubtitleText,
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        textAlign = TextAlign.Center,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }

            HorizontalDivider()

            // ĐIỀU KHIỂN TRÌNH PHÁT
            Text("--- ĐIỀU KHIỂN TRÌNH PHÁT ---", color = MaterialTheme.colorScheme.tertiary, fontWeight = FontWeight.Bold)
            val isPlayEnabled = state.videoUri != null
            Button(
                onClick = { subViewModel.togglePlayPause() },
                enabled = isPlayEnabled,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.tertiary)
            ) {
                Text(if (state.isPlaying) "Tạm dừng Video" else "Phát Video (Chỉ âm thanh)")
            }
            
            Text(
                "Thời gian: ${formatTime(state.currentTimeMs)} / ${formatTime(state.durationMs)}",
                modifier = Modifier.semantics { contentDescription = "Thời gian video hiện tại" }
            )
            Slider(
                value = if (state.durationMs > 0) state.currentTimeMs.toFloat() else 0f,
                onValueChange = { subViewModel.seekTo(it.toLong()) },
                valueRange = 0f..(if (state.durationMs > 0) state.durationMs.toFloat() else 100f),
                modifier = Modifier.fillMaxWidth().then(
                    if (state.isPlaying) {
                        // Xóa semantics của thanh trượt khi đang xem để TalkBack không đọc phần trăm liên tục gây phiền nhiễu
                        Modifier.clearAndSetSemantics { contentDescription = "Thanh trượt tua thời gian video (Đang phát)" }
                    } else {
                        Modifier.semantics { contentDescription = "Thanh trượt tua thời gian video" }
                    }
                )
            )
            
            Text("Âm lượng Video: ${(state.videoVolume * 100).roundToInt()}%")
            Slider(
                value = (state.videoVolume * 100f),
                onValueChange = { subViewModel.setVideoVolume(it / 100f) },
                valueRange = 0f..100f,
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Thanh trượt điều chỉnh âm lượng video" }
            )

            HorizontalDivider()

            // CÀI ĐẶT TTS
            Text("--- CÀI ĐẶT GIỌNG ĐỌC (TTS) ---", color = MaterialTheme.colorScheme.secondary, fontWeight = FontWeight.Bold)
            com.aistudio.mediatool.ui.components.AccessibleSwitchRow(
                checked = state.autoDuck,
                onCheckedChange = { subViewModel.setAutoDuck(it) },
                text = "Auto-Duck (Tự động nhỏ tiếng Video)",
                modifier = Modifier.semantics { contentDescription = "Chuyển đổi giảm âm video thông minh khi đọc giọng nói" }
            )
            
            Text("Tốc độ đọc: ${String.format("%.1f", state.ttsSpeed)}x")
            Slider(
                value = state.ttsSpeed,
                onValueChange = { subViewModel.setTtsSpeed(it) },
                valueRange = 0.5f..3.0f,
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Thanh trượt tốc độ đọc phụ đề" }
            )

            Text("Âm lượng giọng đọc: ${(state.ttsVolume * 100).roundToInt()}%")
            Slider(
                value = state.ttsVolume,
                onValueChange = { subViewModel.setTtsVolume(it) },
                valueRange = 0f..1f,
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Thanh trượt điều chỉnh âm lượng giọng đọc phụ đề" }
            )

            HorizontalDivider()

            // TRÍCH XUẤT PHỤ ĐỀ CÓ SẴN
            Text("--- TRÍCH XUẤT PHỤ ĐỀ CÓ SẴN ---", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
            Button(
                onClick = { startExtractSubtitles() },
                enabled = !state.isExtracting && state.videoUri != null,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
            ) {
                Text("TRÍCH XUẤT PHỤ ĐỀ TỪ VIDEO")
            }
            
            if (state.extractProgress.isNotEmpty()) {
                Text(
                    text = state.extractProgress, 
                    modifier = Modifier.fillMaxWidth(), 
                    textAlign = TextAlign.Center, 
                    fontWeight = FontWeight.Bold, 
                    color = if (state.extractProgress.contains("Lỗi") || state.extractProgress.contains("không có")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                )
                if (state.isExtracting) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
            
            if (state.extractOutputPath.isNotEmpty()) {
                ResultFileActions(file = File(state.extractOutputPath))
            }

            Spacer(modifier = Modifier.height(8.dp))

            OutlinedButton(
                onClick = { subViewModel.clearAll() },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Xóa & Đặt lại")
            }
        }
    }
}

fun formatTime(ms: Long): String {
    if (ms <= 0) return "00:00"
    val totalSeconds = ms / 1000
    val m = totalSeconds / 60
    val s = totalSeconds % 60
    return String.format("%02d:%02d", m, s)
}
