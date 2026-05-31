package com.example.ui.screens

import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import androidx.media3.exoplayer.ExoPlayer
import com.example.core.media.MediaEngine
import com.example.ui.components.VideoPlayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrimScreen(navController: NavController) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val mediaEngine = remember { MediaEngine(context) }
    
    var selectedUri by remember { mutableStateOf<Uri?>(null) }
    var fileName by remember { mutableStateOf("Chưa chọn") }
    var exoPlayer by remember { mutableStateOf<ExoPlayer?>(null) }
    
    var startMs by remember { mutableStateOf("0") }
    var endMs by remember { mutableStateOf("0") }
    
    var isProcessing by remember { mutableStateOf(false) }
    var progressMsg by remember { mutableStateOf("") }
    var hasOutput by remember { mutableStateOf(false) }
    var outputPath by remember { mutableStateOf("") }
    
    val saveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { destUri ->
        destUri?.let { uri ->
            if (outputPath.isNotEmpty()) {
                coroutineScope.launch(Dispatchers.IO) {
                    try {
                        val inFile = File(outputPath)
                        context.contentResolver.openOutputStream(uri)?.use { outStream ->
                            inFile.inputStream().use { inStream ->
                                inStream.copyTo(outStream)
                            }
                        }
                        withContext(Dispatchers.Main) {
                            Toast.makeText(context, "Đã lưu file thành công!", Toast.LENGTH_LONG).show()
                        }
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(context, "Lỗi khi lưu file: ${e.message}", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        }
    }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let {
            selectedUri = it
            fileName = getFileName(context, it) ?: "Unknown"
        }
    }
    
    fun startTrim() {
        if (selectedUri == null) {
            Toast.makeText(context, "Vui lòng chọn file", Toast.LENGTH_SHORT).show()
            return
        }
        
        val stTokens = if (startMs.isBlank()) listOf("0") else startMs.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        val enTokens = if (endMs.isBlank()) listOf("0") else endMs.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        
        val segments = mutableListOf<Pair<Double, Double>>()
        for (i in stTokens.indices) {
            val s = stTokens[i].toLongOrNull()?.let { it / 1000.0 } ?: 0.0
            val e = enTokens.getOrNull(i)?.toLongOrNull()?.let { it / 1000.0 } ?: 0.0
            if (e in 0.001..s) {
                Toast.makeText(context, "Mốc kết thúc đoạn ${i+1} phải lớn hơn bắt đầu", Toast.LENGTH_SHORT).show()
                return
            }
            segments.add(Pair(s, e))
        }
        if (segments.isEmpty()) {
            segments.add(Pair(0.0, 0.0))
        }

        isProcessing = true
        progressMsg = "Đang chuẩn bị..."
        hasOutput = false

        coroutineScope.launch(Dispatchers.IO) {
            try {
                val uri = selectedUri!!
                val safPath = mediaEngine.getSafParameter(uri)
                if (safPath == null) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "Không thể đọc file", Toast.LENGTH_SHORT).show()
                        isProcessing = false
                    }
                    return@launch
                }
                
                val outputDir = File(context.cacheDir, "trim_outputs").apply { mkdirs() }
                val isAudio = fileName.endsWith(".mp3", true) || fileName.endsWith(".m4a", true) || 
                              fileName.endsWith(".wav", true) || fileName.endsWith(".flac", true) || 
                              fileName.endsWith(".ogg", true) || fileName.endsWith(".aac", true)
                
                val ext = if (isAudio) {
                    if (com.example.core.SettingsManager.isAudioLossless(context)) {
                        if (fileName.contains(".")) fileName.substringAfterLast(".") else "m4a"
                    } else {
                        com.example.core.SettingsManager.getAudioFormatExt(context)
                    }
                } else {
                    if (fileName.contains(".")) fileName.substringAfterLast(".") else "mp4"
                }

                val globalFadeSec = com.example.core.SettingsManager.getFadeDurationSec(context).toDouble()
                var fileDurSec = 0.0
                if (globalFadeSec > 0.0) {
                    try {
                        val mmr = android.media.MediaMetadataRetriever()
                        mmr.setDataSource(safPath)
                        val dStr = mmr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
                        fileDurSec = (dStr?.toLongOrNull() ?: 0L) / 1000.0
                        mmr.release()
                    } catch (e: Exception) {}
                }

                var totalTrimDurationSec = 0.0
                for (seg in segments) {
                    val s = seg.first
                    val e = seg.second
                    if (e > s) totalTrimDurationSec += (e - s)
                    else if (fileDurSec > s) totalTrimDurationSec += (fileDurSec - s)
                }

                val outputFile = File(outputDir, "trimmed_${System.currentTimeMillis()}.$ext")
                
                var codecArg = if (isAudio) {
                    val baseCodec = if (com.example.core.SettingsManager.isAudioLossless(context)) {
                        "-c copy" 
                    } else {
                        val acodec = com.example.core.SettingsManager.getAudioCodecArg(context)
                        val abitrate = com.example.core.SettingsManager.getAudioBitrateArg(context)
                        if (acodec.contains("flac") || acodec.contains("pcm")) acodec else "$acodec $abitrate"
                    }
                    "-vn $baseCodec"
                } else {
                    "-c copy"
                }
                
                val tempFiles = mutableListOf<File>()
                
                for ((index, segment) in segments.withIndex()) {
                    val (startSec, endSec) = segment
                    val duration = if (endSec > 0.0) endSec - startSec else 0.0
                    val durationArg = if (duration > 0.0) "-t $duration" else ""
                    
                    val tempExt = if (isAudio && codecArg == "-c copy") {
                       ext
                    } else if (!isAudio) {
                       "mp4" // standard video intermediate
                    } else {
                       ext
                    }
                    
                    val tempFile = File(outputDir, "temp_part_${index}_${System.currentTimeMillis()}.$tempExt")
                    
                    var currentCodecArg = codecArg
                    var filterArg = ""
                    if (segments.size == 1 && globalFadeSec > 0.0) {
                        val fi = globalFadeSec
                        val fo = globalFadeSec
                        val fadeOutStart = if (totalTrimDurationSec > fo) totalTrimDurationSec - fo else 0.0
                        filterArg = "-af \"afade=t=in:st=0:d=$fi,afade=t=out:st=$fadeOutStart:d=$fo\" "
                        
                        if (isAudio) {
                            val acodec = com.example.core.SettingsManager.getAudioCodecArg(context)
                            val abitrate = com.example.core.SettingsManager.getAudioBitrateArg(context)
                            currentCodecArg = "-vn ${if (acodec.contains("flac") || acodec.contains("pcm")) acodec else "$acodec $abitrate"}"
                        } else {
                            val acodec = com.example.core.SettingsManager.getAudioCodecArg(context) // re-encode audio for afade
                            currentCodecArg = "-c:v copy ${if (acodec.contains("flac") || acodec.contains("pcm")) acodec else "$acodec"}"
                        }
                    }
                    
                    val command = "-y -ss $startSec -i \"$safPath\" $durationArg $filterArg $currentCodecArg \"${tempFile.absolutePath}\""
                    
                    var success = false
                    mediaEngine.executeFFmpegCommand(command).collect { state ->
                        withContext(Dispatchers.Main) {
                            when (state) {
                                is MediaEngine.ExecutionState.Progress -> {
                                    progressMsg = "Đang cắt đoạn ${index+1}/${segments.size}..."
                                }
                                is MediaEngine.ExecutionState.Success -> {
                                    success = true
                                }
                                is MediaEngine.ExecutionState.Error -> {
                                    progressMsg = "Lỗi đoạn ${index+1}: ${state.returnCode}"
                                }
                                else -> {}
                            }
                        }
                    }
                    if (success) {
                        tempFiles.add(tempFile)
                    } else {
                        throw Exception("Lỗi khi cắt đoạn ${index+1}")
                    }
                }
                
                if (tempFiles.size == 1) {
                    tempFiles[0].renameTo(outputFile)
                    withContext(Dispatchers.Main) {
                        progressMsg = "Xử lý thành công!"
                        isProcessing = false
                        hasOutput = true
                        outputPath = outputFile.absolutePath
                    }
                } else if (tempFiles.size > 1) {
                    withContext(Dispatchers.Main) { progressMsg = "Đang nối ${tempFiles.size} đoạn..." }
                    val listFile = File(outputDir, "concat_list_${System.currentTimeMillis()}.txt")
                    listFile.writeText(tempFiles.joinToString("\n") { "file '${it.absolutePath}'" })
                    
                    var concatCmd = "-y -f concat -safe 0 -i \"${listFile.absolutePath}\" -c copy \"${outputFile.absolutePath}\""
                    if (globalFadeSec > 0.0) {
                        val fi = globalFadeSec
                        val fo = globalFadeSec
                        val fadeOutStart = if (totalTrimDurationSec > fo) totalTrimDurationSec - fo else 0.0
                        val filterArg = "-af \"afade=t=in:st=0:d=$fi,afade=t=out:st=$fadeOutStart:d=$fo\" "
                        
                        val ccArg = if (isAudio) {
                            val acodec = com.example.core.SettingsManager.getAudioCodecArg(context)
                            val abitrate = com.example.core.SettingsManager.getAudioBitrateArg(context)
                            "-vn ${if (acodec.contains("flac") || acodec.contains("pcm")) acodec else "$acodec $abitrate"}"
                        } else {
                            val acodec = com.example.core.SettingsManager.getAudioCodecArg(context)
                            "-c:v copy ${if (acodec.contains("flac") || acodec.contains("pcm")) acodec else acodec}"
                        }
                        concatCmd = "-y -f concat -safe 0 -i \"${listFile.absolutePath}\" $filterArg $ccArg \"${outputFile.absolutePath}\""
                    }
                    
                    var concatSuccess = false
                    mediaEngine.executeFFmpegCommand(concatCmd).collect { state ->
                        withContext(Dispatchers.Main) {
                            when (state) {
                                is MediaEngine.ExecutionState.Progress -> { progressMsg = "Đang nối các đoạn..." }
                                is MediaEngine.ExecutionState.Success -> { concatSuccess = true }
                                is MediaEngine.ExecutionState.Error -> { progressMsg = "Lỗi khi nối: ${state.returnCode}" }
                                else -> {}
                            }
                        }
                    }
                    if (concatSuccess) {
                        withContext(Dispatchers.Main) {
                            progressMsg = "Xử lý thành công!"
                            isProcessing = false
                            hasOutput = true
                            outputPath = outputFile.absolutePath
                        }
                    } else {
                        throw Exception("Lỗi khi ghép các đoạn")
                    }
                }

            } catch (e: Throwable) {
                val causeStr = generateSequence(e) { it.cause }.joinToString(" -> ") { it.toString() }
                withContext(Dispatchers.Main) {
                    progressMsg = "Ngoại lệ: $causeStr"
                    isProcessing = false
                    Toast.makeText(context, "Lỗi: $causeStr", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("CẮT ĐA ĐOẠN AUDIO / VIDEO", fontSize = 18.sp, fontWeight = FontWeight.Bold) },
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
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Button(onClick = { launcher.launch("*/*") }, modifier = Modifier.fillMaxWidth()) {
                Text("Chọn file cần cắt")
            }

            Text(text = "File: $fileName", modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.onSurfaceVariant)

            if (selectedUri != null) {
                VideoPlayer(
                    uri = selectedUri!!,
                    onPlayerReady = { player -> exoPlayer = player }
                )
            }

            Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = startMs,
                            onValueChange = { startMs = it.filter { char -> char.isDigit() || char == ',' || char == ' ' } },
                            modifier = Modifier.weight(1f),
                            label = { Text("Bắt đầu (ms, VD: 0, 5000)") },
                            placeholder = { Text("0, 5000") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text, imeAction = ImeAction.Next)
                        )
                        Button(onClick = { 
                            exoPlayer?.let { player -> 
                                val curr = player.currentPosition.toString()
                                startMs = if (startMs.isBlank()) curr else "$startMs, $curr"
                            }
                        }, modifier = Modifier.semantics { contentDescription = "Lấy mốc thời gian bắt đầu đang phát trên trình phát video" }) {
                            Text("Lấy mốc hiện tại")
                        }
                    }

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = endMs,
                            onValueChange = { endMs = it.filter { char -> char.isDigit() || char == ',' || char == ' ' } },
                            modifier = Modifier.weight(1f),
                            label = { Text("Kết thúc (VD: 3000, 0)") },
                            placeholder = { Text("3000, 0") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text, imeAction = ImeAction.Done)
                        )
                        Button(onClick = {
                            exoPlayer?.let { player -> 
                                val curr = player.currentPosition.toString()
                                endMs = if (endMs.isBlank()) curr else "$endMs, $curr"
                            }
                        }, modifier = Modifier.semantics { contentDescription = "Lấy mốc thời gian kết thúc đang phát trên trình phát video" }) {
                            Text("Lấy mốc hiện tại")
                        }
                    }
                }
            }

            Divider(modifier = Modifier.padding(vertical = 8.dp))

            if (isProcessing || progressMsg.isNotEmpty()) {
                Text(
                    text = progressMsg, 
                    modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite }, 
                    textAlign = TextAlign.Center, 
                    fontWeight = FontWeight.Bold, 
                    color = MaterialTheme.colorScheme.primary
                )
                if (isProcessing) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }

            Button(
                onClick = { startTrim() },
                enabled = !isProcessing && selectedUri != null,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer)
            ) {
                Text("BẮT ĐẦU CẮT FILE", color = Color(0xFFFF0000), fontWeight = FontWeight.Bold)
            }

            if (hasOutput) {
                Card(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Đã cắt xong! File lưu tạm tại:\n$outputPath", style = MaterialTheme.typography.bodySmall)
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(onClick = { 
                            val ext = if (outputPath.contains(".")) outputPath.substringAfterLast(".") else "mp4"
                            saveLauncher.launch("trimmed_${System.currentTimeMillis()}.$ext")
                        }, modifier = Modifier.fillMaxWidth()) {
                            Text("Lưu file vào thiết bị")
                        }
                        
                        Spacer(modifier = Modifier.height(16.dp))
                        Text("▶ Xem/Nghe file kết quả:", fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 8.dp))
                        com.example.ui.components.VideoPlayer(uri = Uri.fromFile(File(outputPath)))
                    }
                }
            }

            TextButton(onClick = { navController.popBackStack() }, modifier = Modifier.fillMaxWidth()) {
                Text("Quay lại")
            }
        }
    }
}


