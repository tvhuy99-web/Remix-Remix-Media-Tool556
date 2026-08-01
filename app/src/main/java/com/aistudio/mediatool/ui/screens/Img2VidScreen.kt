package com.aistudio.mediatool.ui.screens

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
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
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
import com.aistudio.mediatool.core.DocumentUtils
import com.aistudio.mediatool.core.diagnostics.DiagnosticLogger
import com.aistudio.mediatool.core.SlideshowTiming
import com.aistudio.mediatool.core.media.MediaEngine
import com.aistudio.mediatool.core.media.AudioMath
import com.aistudio.mediatool.ui.components.VideoPlayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import com.aistudio.mediatool.ui.components.ToolScaffold
import com.aistudio.mediatool.ui.components.ResultFileActions

data class ImageItem(
    val uri: Uri,
    val startMs: String = "",
    val endMs: String = ""
)


private val ImageItemListSaver = Saver<List<ImageItem>, ArrayList<String>>(
    save = { items ->
        ArrayList(items.flatMap { listOf(it.uri.toString(), it.startMs, it.endMs) })
    },
    restore = { values ->
        values.chunked(3).mapNotNull { row ->
            row.getOrNull(0)?.let { ImageItem(Uri.parse(it), row.getOrNull(1).orEmpty(), row.getOrNull(2).orEmpty()) }
        }
    },
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Img2VidScreen(navController: NavController) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val mediaEngine = remember { MediaEngine(context) }
    
    var audioUriText by rememberSaveable { mutableStateOf<String?>(null) }
    val audioUri = audioUriText?.let(Uri::parse)
    var audioName by rememberSaveable { mutableStateOf("Chưa chọn") }
    
    var selectedImageItems by rememberSaveable(stateSaver = ImageItemListSaver) { mutableStateOf<List<ImageItem>>(emptyList()) }
    var expanded by remember { mutableStateOf(false) }
    var ratioIndex by rememberSaveable { mutableStateOf(0) }
    val ratios = listOf("Ngang 16:9", "Dọc 9:16", "Vuông 1:1")
    
    var isProcessing by remember { mutableStateOf(false) }
    var progressMsg by remember { mutableStateOf("") }
    var outputUriText by rememberSaveable { mutableStateOf<String?>(null) }
    val outputUri = outputUriText?.let(Uri::parse)

    val audioLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            DocumentUtils.persistReadPermission(context, uri)
            audioUriText = uri.toString()
            audioName = DocumentUtils.displayName(context, uri)
            outputUriText = null
        }
    }
    
    val imagesLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        uris.forEach { DocumentUtils.persistReadPermission(context, it) }
        val existing = selectedImageItems.map { it.uri }.toSet()
        selectedImageItems = selectedImageItems + uris.filterNot { it in existing }.map { ImageItem(it) }
        outputUriText = null
    }

    fun startCreateVideo() {
        val sourceAudio = audioUri
        val imageItems = selectedImageItems.toList()
        val selectedRatioIndex = ratioIndex
        if (sourceAudio == null) {
            Toast.makeText(context, "Vui lòng chọn âm thanh", Toast.LENGTH_SHORT).show()
            return
        }
        if (imageItems.isEmpty()) {
            Toast.makeText(context, "Vui lòng chọn ít nhất một ảnh", Toast.LENGTH_SHORT).show()
            return
        }

        isProcessing = true
        progressMsg = "Đang chuẩn bị dữ liệu..."
        outputUriText = null

        coroutineScope.launch(Dispatchers.IO) {
            val temporaryFiles = mutableListOf<File>()
            var pendingOutput: File? = null
            try {
                val audioFile = mediaEngine.copyUriToCache(sourceAudio, "img2vid-audio")
                temporaryFiles += audioFile

                val preparedImages = imageItems.mapIndexed { index, item ->
                    val imageFile = mediaEngine.copyUriToCache(item.uri, "img2vid-image-$index")
                    temporaryFiles += imageFile
                    item to imageFile
                }

                val outputFile = com.aistudio.mediatool.core.FileExportManager.resultFile(context, "video-tu-anh", "mp4")
                pendingOutput = outputFile
                val audioDurationMs = runCatching {
                    android.media.MediaMetadataRetriever().let { retriever ->
                        try {
                            retriever.setDataSource(context, sourceAudio)
                            retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                        } finally {
                            retriever.release()
                        }
                    }
                }.getOrDefault(0L)
                require(audioDurationMs > 0L) {
                    "Không đọc được thời lượng âm thanh; hãy chọn tệp âm thanh hợp lệ"
                }
                val intervals = preparedImages.mapIndexed { index, (item, _) ->
                    runCatching { SlideshowTiming.parseInterval(item.startMs, item.endMs) }
                        .getOrElse { error("Ảnh ${index + 1}: ${it.message}") }
                }
                val schedule = SlideshowTiming.buildSchedule(audioDurationMs, intervals)

                val dimensions = when (selectedRatioIndex) {
                    0 -> 1280 to 720
                    1 -> 720 to 1280
                    else -> 720 to 720
                }
                val scaleFilter = when (selectedRatioIndex) {
                    0 -> "scale=1280:720:force_original_aspect_ratio=decrease,pad=1280:720:-1:-1:color=black,fps=30"
                    1 -> "scale=720:1280:force_original_aspect_ratio=decrease,pad=720:1280:-1:-1:color=black,fps=30"
                    else -> "scale=720:720:force_original_aspect_ratio=decrease,pad=720:720:-1:-1:color=black,fps=30"
                }
                val videoBitrate = com.aistudio.mediatool.core.SettingsManager.getVideoBitrateArg(context)
                val audioBitrate = com.aistudio.mediatool.core.SettingsManager
                    .getAudioBitrateInt(context)
                    .coerceAtMost(320_000) / 1_000
                fun seconds(milliseconds: Long): String =
                    "%.6f".format(java.util.Locale.US, milliseconds / 1_000.0)

                val inputArgs = buildString {
                    preparedImages.forEach { (_, imageFile) ->
                        append("-loop 1 -framerate 30 -i \"${imageFile.absolutePath}\" ")
                    }
                    append("-i \"${audioFile.absolutePath}\"")
                }
                val filter = buildString {
                    append(
                        "color=c=black:s=${dimensions.first}x${dimensions.second}:r=30:" +
                            "d=${seconds(audioDurationMs)}[canvas_0];",
                    )
                    schedule.forEachIndexed { index, slot ->
                        val start = seconds(slot.startMs)
                        val end = seconds(slot.endMs)
                        val duration = seconds(slot.durationMs)
                        append("[$index:v]$scaleFilter,trim=duration=$duration,")
                        append("setpts=PTS-STARTPTS+$start/TB[image_$index];")
                        append("[canvas_$index][image_$index]")
                        append("overlay=eof_action=pass:shortest=0:enable='between(t,$start,$end)'[canvas_${index + 1}];")
                    }
                    append("[canvas_${schedule.size}]format=yuv420p[outv]")
                }
                val audioInputIndex = preparedImages.size
                val command = buildString {
                    append("-y -hide_banner -loglevel warning $inputArgs ")
                    append("-filter_complex \"$filter\" ")
                    append("-map \"[outv]\" -map $audioInputIndex:a:0 ")
                    append("-t ${seconds(audioDurationMs)} -c:v mpeg4 $videoBitrate ")
                    append("-c:a aac -b:a ${audioBitrate}k -pix_fmt yuv420p -movflags +faststart -shortest ")
                    append("\"${outputFile.absolutePath}\"")
                }

                mediaEngine.executeFFmpegCommand(command, diagnosticPhase = "slideshow_render").collect { state ->
                    withContext(Dispatchers.Main) {
                        when (state) {
                            is MediaEngine.ExecutionState.Connecting -> progressMsg = "Đang khởi tạo FFmpeg..."
                            is MediaEngine.ExecutionState.Progress -> {
                                val percent = AudioMath.progressPercent(state.timeInMilliseconds, audioDurationMs)
                                progressMsg = if (audioDurationMs > 0L) "Đang dựng video: $percent%" else "Đang dựng video…"
                            }
                            is MediaEngine.ExecutionState.Success -> {
                                require(outputFile.isFile && outputFile.length() > 0L) { "FFmpeg không tạo tệp đầu ra" }
                                progressMsg = "Hoàn thành"
                                isProcessing = false
                                outputUriText = Uri.fromFile(outputFile).toString()
                                pendingOutput = null
                                Toast.makeText(context, "Tạo video thành công", Toast.LENGTH_SHORT).show()
                            }
                            is MediaEngine.ExecutionState.Error -> {
                                val details = state.logs?.takeLast(1_500)
                                    ?: state.failStackTrace?.takeLast(1_500)
                                    ?: "Lỗi không xác định"
                                progressMsg = "Không thể tạo video: $details"
                                isProcessing = false
                            }
                        }
                    }
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                DiagnosticLogger.error(
                    component = "Img2VidScreen",
                    event = "slideshow_pipeline_failed",
                    message = error.message,
                    error = error,
                )
                withContext(Dispatchers.Main) {
                    progressMsg = "Lỗi: ${error.message ?: error.javaClass.simpleName}"
                    isProcessing = false
                    Toast.makeText(context, progressMsg, Toast.LENGTH_LONG).show()
                }
            } finally {
                pendingOutput?.delete()
                temporaryFiles.forEach { runCatching { it.delete() } }
            }
        }
    }

    ToolScaffold(
        title = "Tạo video từ ảnh",
        onNavigateBack = { navController.popBackStack() },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Button(onClick = { audioLauncher.launch(arrayOf("audio/*")) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Chọn Âm thanh")
                }
                Text("File đã chọn: $audioName", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Button(onClick = { imagesLauncher.launch(arrayOf("image/*")) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Thêm Ảnh (Nhiều file)")
                }
                Text("Đã chọn ${selectedImageItems.size} ảnh.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                Text(
                    "Để trống mốc để tự chia thời lượng; nếu nhập, cả B.Đầu và K.Thúc là vị trí tuyệt đối trên timeline.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                )
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
                                        Text("Ảnh ${index + 1}: ${DocumentUtils.displayName(context, uri)}", modifier = Modifier.weight(1f))
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
                outputUri?.path?.let { ResultFileActions(file = File(it)) }
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Trình phát:", fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 8.dp))
                        VideoPlayer(uri = outputUri!!)
                    }
                }
            }

        }
    }
}
