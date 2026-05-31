package com.example.ui.screens

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
import androidx.navigation.NavController
import com.example.core.media.MediaEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubScreen(navController: NavController, viewModel: SubViewModel = viewModel()) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val mediaEngine = remember { MediaEngine(context) }
    
    val state by viewModel.state.collectAsState()
    
    // Khởi tạo Player & TTS
    LaunchedEffect(Unit) {
        viewModel.initPlayer(context)
    }

    // Laucher chọn Video
    val vidLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let {
            val fileName = getFileName(context, it) ?: "video_file"
            viewModel.setVideo(it, fileName)
            Toast.makeText(context, "Đã nạp file Video", Toast.LENGTH_SHORT).show()
        }
    }
    
    // Laucher chọn Phụ đề
    val subLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let {
            val fileName = getFileName(context, it) ?: "sub_file"
            coroutineScope.launch(Dispatchers.IO) {
                try {
                    val content = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: ""
                    withContext(Dispatchers.Main) {
                        viewModel.setSubtitle(uri, fileName, content)
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

    // Launcher lưu tệp sau khi trích xuất
    val saveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/x-subrip")) { destUri ->
        destUri?.let { uri ->
            if (state.extractOutputPath.isNotEmpty()) {
                coroutineScope.launch(Dispatchers.IO) {
                    try {
                        val inFile = File(state.extractOutputPath)
                        context.contentResolver.openOutputStream(uri)?.use { outStream ->
                            inFile.inputStream().use { inStream ->
                                inStream.copyTo(outStream)
                            }
                        }
                        withContext(Dispatchers.Main) {
                            Toast.makeText(context, "Đã lưu phụ đề thành công!", Toast.LENGTH_LONG).show()
                        }
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(context, "Lỗi khi lưu phụ đề: ${e.message}", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        }
    }
    
    fun startExtractSubtitles() {
        if (state.videoUri == null) {
            Toast.makeText(context, "Vui lòng chọn video trước!", Toast.LENGTH_SHORT).show()
            return
        }
        viewModel.startExtraction()
        coroutineScope.launch(Dispatchers.IO) {
            try {
                val safPath = mediaEngine.getSafParameter(state.videoUri!!)
                if (safPath == null) {
                    withContext(Dispatchers.Main) {
                        viewModel.finishExtraction(false, "", "Lỗi: Không thể đọc đường dẫn Video")
                        Toast.makeText(context, "Lỗi đọc file Video", Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }
                
                val outputDir = File(context.cacheDir, "subs_output").apply { mkdirs() }
                val outputFile = File(outputDir, "subtitles_${System.currentTimeMillis()}.srt")
                
                // Lệnh lấy stream phụ đề đầu tiên (thêm ? để không lỗi nếu không có)
                val command = "-y -hide_banner -loglevel warning -i \"$safPath\" -map 0:s:0? -c:s srt \"${outputFile.absolutePath}\""
                
                mediaEngine.executeFFmpegCommand(command).collect { execState ->
                    withContext(Dispatchers.Main) {
                        when (execState) {
                            is MediaEngine.ExecutionState.Connecting -> viewModel.updateExtractionProgress("Đang bắt đầu...")
                            is MediaEngine.ExecutionState.Progress -> viewModel.updateExtractionProgress("Đang xử lý...")
                            is MediaEngine.ExecutionState.Success -> {
                                if (outputFile.exists() && outputFile.length() > 0) {
                                    viewModel.finishExtraction(true, outputFile.absolutePath, "Trích xuất thành công!")
                                    
                                    // Tự động nạp file SRT vừa trích xuất với cơ chế retry và delay để đảm bảo file flush hoàn toàn
                                    coroutineScope.launch(Dispatchers.IO) {
                                        var content = ""
                                        for (i in 1..5) {
                                            kotlinx.coroutines.delay(500)
                                            try {
                                                content = outputFile.readText()
                                                if (content.trim().isNotEmpty()) break
                                            } catch (e: Exception) {
                                                // Bỏ qua lỗi và thử lại
                                            }
                                        }
                                        withContext(Dispatchers.Main) {
                                            if (content.trim().isNotEmpty()) {
                                                viewModel.setSubtitle(Uri.fromFile(outputFile), outputFile.name, content)
                                                Toast.makeText(context, "Đã tự động nạp phụ đề thành công!", Toast.LENGTH_LONG).show()
                                            } else {
                                                Toast.makeText(context, "File trích xuất có vẻ rỗng hoặc chưa lưu kịp, vui lòng lưu và chọn lại thủ công.", Toast.LENGTH_LONG).show()
                                            }
                                        }
                                    }
                                } else {
                                    outputFile.delete()
                                    viewModel.finishExtraction(false, "", "Lỗi: Không tìm thấy phụ đề đính kèm trong Video.")
                                }
                            }
                            is MediaEngine.ExecutionState.Error -> {
                                viewModel.finishExtraction(false, "", "Video không có luồng phụ đề đính kèm.")
                            }
                        }
                    }
                }
            } catch(e: Exception) {
                withContext(Dispatchers.Main) {
                    viewModel.finishExtraction(false, "", "Lỗi cục bộ: ${e.message}")
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Đọc & Trích xuất Phụ đề", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Quay lại")
                    }
                },
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
            Button(onClick = { vidLauncher.launch("*/*") }, modifier = Modifier.fillMaxWidth()) {
                Text("Chọn Video")
            }
            Text("Video: ${state.videoFileName}", color = MaterialTheme.colorScheme.onSurfaceVariant)

            HorizontalDivider()

            // [2] CHỌN PHỤ ĐỀ ĐỂ ĐỌC
            Text("[2] CHỌN PHỤ ĐỀ ĐỂ ĐỌC", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Button(onClick = { subLauncher.launch("*/*") }, modifier = Modifier.fillMaxWidth()) {
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
                onClick = { viewModel.togglePlayPause() },
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
                onValueChange = { viewModel.seekTo(it.toLong()) },
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
                onValueChange = { viewModel.setVideoVolume(it / 100f) },
                valueRange = 0f..100f,
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Thanh trượt điều chỉnh âm lượng video" }
            )

            HorizontalDivider()

            // CÀI ĐẶT TTS
            Text("--- CÀI ĐẶT GIỌNG ĐỌC (TTS) ---", color = MaterialTheme.colorScheme.secondary, fontWeight = FontWeight.Bold)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Auto-Duck (Tự động nhỏ tiếng Video)", modifier = Modifier.weight(1f))
                Switch(
                    checked = state.autoDuck,
                    onCheckedChange = { viewModel.setAutoDuck(it) },
                    modifier = Modifier.semantics { contentDescription = "Chuyển đổi giảm âm video thông minh khi đọc giọng nói" }
                )
            }
            
            Text("Tốc độ đọc: ${String.format("%.1f", state.ttsSpeed)}x")
            Slider(
                value = state.ttsSpeed,
                onValueChange = { viewModel.setTtsSpeed(it) },
                valueRange = 0.5f..3.0f,
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Thanh trượt tốc độ đọc phụ đề" }
            )

            Text("Âm lượng giọng đọc: ${(state.ttsVolume * 100).roundToInt()}%")
            Slider(
                value = state.ttsVolume,
                onValueChange = { viewModel.setTtsVolume(it) },
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
                Button(onClick = { 
                    saveLauncher.launch("subtitles_${System.currentTimeMillis()}.srt")
                }, modifier = Modifier.fillMaxWidth()) {
                    Text("Lưu file phụ đề vừa trích xuất")
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            OutlinedButton(
                onClick = { viewModel.clearAll() },
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


