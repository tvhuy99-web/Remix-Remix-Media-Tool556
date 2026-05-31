package com.example.ui.screens

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.example.core.media.MediaEngine
import com.example.ui.components.VideoPlayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileWriter

data class ImageItem(
    val uri: Uri,
    val startMs: String = "",
    val endMs: String = ""
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Img2VidScreen(navController: NavController) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val mediaEngine = remember { MediaEngine(context) }
    
    var audioUri by remember { mutableStateOf<Uri?>(null) }
    var audioName by remember { mutableStateOf("Chưa chọn") }
    
    var selectedImageItems by remember { mutableStateOf<List<ImageItem>>(emptyList()) }
    var expanded by remember { mutableStateOf(false) }
    var ratioIndex by remember { mutableStateOf(0) }
    val ratios = listOf("Ngang 16:9", "Dọc 9:16", "Vuông 1:1")
    
    var isProcessing by remember { mutableStateOf(false) }
    var progressMsg by remember { mutableStateOf("") }
    var outputUri by remember { mutableStateOf<Uri?>(null) }

    val saveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("video/mp4")) { destUri ->
        destUri?.let { uri ->
            outputUri?.let { outUri ->
                val outputPathStr = outUri.path ?: ""
                if (outputPathStr.isNotEmpty()) {
                    coroutineScope.launch(Dispatchers.IO) {
                        try {
                            val inFile = File(outputPathStr)
                            context.contentResolver.openOutputStream(uri)?.use { outStream ->
                                inFile.inputStream().use { inStream ->
                                    inStream.copyTo(outStream)
                                }
                            }
                            withContext(Dispatchers.Main) {
                                Toast.makeText(context, "Đã lưu video thành công!", Toast.LENGTH_LONG).show()
                            }
                        } catch (e: Exception) {
                            withContext(Dispatchers.Main) {
                                Toast.makeText(context, "Lỗi khi lưu video: ${e.message}", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }
            }
        }
    }

    val audioLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            audioUri = uri
            audioName = getFileName(context, uri) ?: "audio_file"
            outputUri = null
        }
    }
    
    val imagesLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        selectedImageItems = selectedImageItems + uris.map { ImageItem(it) }
        outputUri = null
    }

    fun startCreateVideo() {
        if (audioUri == null) {
            Toast.makeText(context, "Vui lòng chọn âm thanh", Toast.LENGTH_SHORT).show()
            return
        }
        if (selectedImageItems.isEmpty()) {
            Toast.makeText(context, "Vui lòng chọn ít nhất 1 ảnh", Toast.LENGTH_SHORT).show()
            return
        }
        
        isProcessing = true
        progressMsg = "Đang chuẩn bị..."
        outputUri = null
        
        coroutineScope.launch(Dispatchers.IO) {
            try {
                val audioMime = context.contentResolver.getType(audioUri!!) ?: ""
                val audioExt = if (audioMime.contains("mp3")) ".mp3" 
                               else if (audioMime.contains("wav")) ".wav" 
                               else if (audioMime.contains("ogg")) ".ogg" 
                               else if (audioMime.contains("flac")) ".flac"
                               else ".m4a"
                val tempAudioFile = File(context.cacheDir, "temp_img2vid_audio_${System.currentTimeMillis()}$audioExt")
                var audioPath = ""
                try {
                    context.contentResolver.openInputStream(audioUri!!)?.use { input ->
                        tempAudioFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                    audioPath = tempAudioFile.absolutePath
                } catch (e: Exception) {
                    val audioSafOption = mediaEngine.getSafParameter(audioUri!!)
                    if (audioSafOption == null) {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(context, "Lỗi đọc file âm thanh", Toast.LENGTH_SHORT).show()
                            isProcessing = false
                        }
                        return@launch
                    }
                    audioPath = audioSafOption
                }
                
                val outputDir = File(context.cacheDir, "img2vid").apply { mkdirs() }
                val outputFile = File(outputDir, "video_${System.currentTimeMillis()}.mp4")
                
                val copiedImages = mutableListOf<File>()
                for ((index, item) in selectedImageItems.withIndex()) {
                    val uri = item.uri
                    val mimeType = context.contentResolver.getType(uri) ?: ""
                    val ext = if (mimeType.contains("png")) ".png" else if (mimeType.contains("webp")) ".webp" else ".jpg"
                    val tempFile = File(context.cacheDir, "img2vid_temp_${System.currentTimeMillis()}_$index$ext")
                    try {
                        context.contentResolver.openInputStream(uri)?.use { input ->
                            tempFile.outputStream().use { output ->
                                input.copyTo(output)
                            }
                        }
                        if (tempFile.exists() && tempFile.length() > 0) {
                            copiedImages.add(tempFile)
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
                
                if (copiedImages.isEmpty()) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "Lỗi đọc file ảnh", Toast.LENGTH_SHORT).show()
                        isProcessing = false
                    }
                    return@launch
                }
                
                val scaleFilter = when (ratioIndex) {
                    0 -> "scale=1280:720:force_original_aspect_ratio=decrease,pad=1280:720:-1:-1:color=black,fps=30"
                    1 -> "scale=720:1280:force_original_aspect_ratio=decrease,pad=720:1280:-1:-1:color=black,fps=30"
                    else -> "scale=720:720:force_original_aspect_ratio=decrease,pad=720:720:-1:-1:color=black,fps=30"
                }
                
                val vBitrate = com.example.core.SettingsManager.getVideoBitrateArg(context)
                val aCodec = com.example.core.SettingsManager.getAudioCodecArg(context)
                val aBitrate = com.example.core.SettingsManager.getAudioBitrateArg(context)
    
                val command = if (copiedImages.size == 1 && selectedImageItems.first().startMs.isEmpty() && selectedImageItems.first().endMs.isEmpty()) {
                    val imgPath = copiedImages.first().absolutePath
                    "-y -hide_banner -loglevel warning -f image2 -loop 1 -framerate 30 -i \"$imgPath\" -i \"$audioPath\" -map 0:v:0 -map 1:a:0 -vf \"$scaleFilter\" -c:v mpeg4 $vBitrate $aCodec $aBitrate -pix_fmt yuv420p -shortest \"${outputFile.absolutePath}\""
                } else {
                    val concatFile = File(context.cacheDir, "img_concat.txt")
                    val writer = FileWriter(concatFile)
                    writer.write("ffconcat version 1.0\n")
                    
                    for (i in selectedImageItems.indices) {
                        val item = selectedImageItems[i]
                        val s = item.startMs.toLongOrNull() ?: 0L
                        val e = item.endMs.toLongOrNull() ?: -1L
                        val durMs = if (e > s) (e - s) else 3000L
                        val durSec = durMs / 1000f
                        
                        val fileOpt = copiedImages.getOrNull(i)
                        if (fileOpt != null) {
                            writer.write("file '${fileOpt.absolutePath.replace("'", "'\\''")}'\n")
                            writer.write("duration $durSec\n")
                        }
                    }
                    val lastImg = copiedImages.lastOrNull()
                    if (lastImg != null) {
                        writer.write("file '${lastImg.absolutePath.replace("'", "'\\''")}'\n")
                        writer.write("duration 99999\n")
                    }
                    writer.close()
                    
                    "-y -hide_banner -loglevel warning -f concat -safe 0 -i \"${concatFile.absolutePath}\" -i \"$audioPath\" -map 0:v:0 -map 1:a:0 -vf \"$scaleFilter\" -c:v mpeg4 $vBitrate $aCodec $aBitrate -pix_fmt yuv420p -shortest \"${outputFile.absolutePath}\""
                }
                
                mediaEngine.executeFFmpegCommand(command).collect { state ->
                    withContext(Dispatchers.Main) {
                        when (state) {
                            is MediaEngine.ExecutionState.Connecting -> progressMsg = "Đang bắt đầu..."
                            is MediaEngine.ExecutionState.Progress -> progressMsg = "Đang xử lý tạo video..."
                            is MediaEngine.ExecutionState.Success -> {
                                progressMsg = "Hoàn thành!"
                                isProcessing = false
                                outputUri = Uri.fromFile(outputFile)
                                Toast.makeText(context, "Tạo video thành công!", Toast.LENGTH_SHORT).show()
                            }
                            is MediaEngine.ExecutionState.Error -> {
                                val logTail = state.logs?.takeLast(2000) ?: state.failStackTrace?.takeLast(2000) ?: "Lỗi không rõ"
                                progressMsg = "Lỗi Code: ${state.returnCode}\n$logTail"
                                isProcessing = false
                                Toast.makeText(context, "Lỗi: ${state.returnCode}", Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                }
            } catch(e: Throwable) {
                withContext(Dispatchers.Main) {
                    progressMsg = "Ngoại lệ: ${e.message}"
                    isProcessing = false
                    Toast.makeText(context, "Lỗi: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Ghép ảnh vào âm thanh", fontSize = 18.sp, fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Button(onClick = { audioLauncher.launch("audio/*") }, modifier = Modifier.fillMaxWidth()) {
                    Text("Chọn Âm thanh")
                }
                Text("File đã chọn: $audioName", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Button(onClick = { imagesLauncher.launch("image/*") }, modifier = Modifier.fillMaxWidth()) {
                    Text("Thêm Ảnh (Nhiều file)")
                }
                Text("Đã chọn ${selectedImageItems.size} ảnh.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
            }

            if (selectedImageItems.isNotEmpty()) {
                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        itemsIndexed(selectedImageItems) { index, item ->
                            val uri = item.uri
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                            ) {
                                Column(modifier = Modifier.padding(8.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text("Ảnh ${index + 1}: ${getFileName(context, uri)}", modifier = Modifier.weight(1f))
                                        IconButton(
                                            onClick = { 
                                                selectedImageItems = selectedImageItems.toMutableList().apply { removeAt(index) } 
                                            },
                                            modifier = Modifier.semantics { contentDescription = "Xóa ảnh thứ ${index + 1} khỏi danh sách" }
                                        ) {
                                            Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                                        }
                                    }
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                                        OutlinedTextField(
                                            value = item.startMs,
                                            onValueChange = { 
                                                val newList = selectedImageItems.toMutableList()
                                                newList[index] = item.copy(startMs = it)
                                                selectedImageItems = newList
                                            },
                                            label = { Text("B.Đầu (ms)") },
                                            modifier = Modifier.weight(1f)
                                        )
                                        OutlinedTextField(
                                            value = item.endMs,
                                            onValueChange = { 
                                                val newList = selectedImageItems.toMutableList()
                                                newList[index] = item.copy(endMs = it)
                                                selectedImageItems = newList
                                            },
                                            label = { Text("K.Thúc (ms)") },
                                            modifier = Modifier.weight(1f)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            } else {
                Spacer(modifier = Modifier.weight(1f))
            }

            ExposedDropdownMenuBox(
                expanded = expanded,
                onExpandedChange = { expanded = it },
                modifier = Modifier.fillMaxWidth()
            ) {
                OutlinedTextField(
                    value = ratios[ratioIndex],
                    onValueChange = {},
                    readOnly = true,
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                    modifier = Modifier.menuAnchor().fillMaxWidth(),
                    label = { Text("Tỉ lệ khung hình") }
                )
                ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    ratios.forEachIndexed { index, ratio ->
                        DropdownMenuItem(
                            text = { Text(ratio) },
                            onClick = {
                                ratioIndex = index
                                expanded = false
                            }
                        )
                    }
                }
            }

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
                onClick = { startCreateVideo() },
                enabled = !isProcessing && audioUri != null && selectedImageItems.isNotEmpty(),
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer
                )
            ) {
                Text("BẮT ĐẦU TẠO VIDEO", fontWeight = FontWeight.Bold)
            }

            if (outputUri != null) {
                Button(onClick = { 
                     saveLauncher.launch("video_${System.currentTimeMillis()}.mp4")
                }, modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Lưu video vừa tạo được bằng ảnh và âm thanh vào thiết bị" }) {
                    Text("Lưu video vào thiết bị")
                }
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Trình phát:", fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 8.dp))
                        VideoPlayer(uri = outputUri!!)
                    }
                }
            }

            TextButton(onClick = { navController.popBackStack() }, modifier = Modifier.fillMaxWidth()) {
                Text("Quay lại")
            }
        }
    }
}

