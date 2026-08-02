package com.aistudio.mediatool.ui.screens

import android.media.MediaMetadataRetriever
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import androidx.navigation.NavController
import androidx.media3.exoplayer.ExoPlayer
import com.aistudio.mediatool.core.GetContentWithMimeTypes
import com.aistudio.mediatool.core.DocumentUtils
import com.aistudio.mediatool.core.diagnostics.DiagnosticLogger
import com.aistudio.mediatool.core.FileExportManager
import com.aistudio.mediatool.core.SettingsManager
import com.aistudio.mediatool.core.media.MediaEngine
import com.aistudio.mediatool.core.media.AudioMath
import com.aistudio.mediatool.core.media.TimelineSegments
import com.aistudio.mediatool.ui.components.VideoPlayer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import com.aistudio.mediatool.ui.components.ToolScaffold
import com.aistudio.mediatool.ui.components.ResultFileActions

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrimScreen(navController: NavController) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val mediaEngine = remember { MediaEngine(context) }
    
    var selectedUriText by rememberSaveable { mutableStateOf<String?>(null) }
    val selectedUri = selectedUriText?.let(Uri::parse)
    var fileName by rememberSaveable { mutableStateOf("Chưa chọn") }
    var exoPlayer by remember { mutableStateOf<ExoPlayer?>(null) }
    
    var startMs by rememberSaveable { mutableStateOf("0") }
    var endMs by rememberSaveable { mutableStateOf("0") }
    
    var isProcessing by remember { mutableStateOf(false) }
    var progressMsg by remember { mutableStateOf("") }
    var hasOutput by rememberSaveable { mutableStateOf(false) }
    var outputPath by rememberSaveable { mutableStateOf("") }
    

    val launcher = rememberLauncherForActivityResult(GetContentWithMimeTypes()) { uri: Uri? ->
        uri?.let {
            DocumentUtils.persistReadPermission(context, it)
            selectedUriText = it.toString()
            fileName = DocumentUtils.displayName(context, it)
            hasOutput = false
            outputPath = ""
            progressMsg = ""
        }
    }
    
    fun startTrim() {
        val inputUri = selectedUri
        if (inputUri == null) {
            Toast.makeText(context, "Vui lòng chọn file", Toast.LENGTH_SHORT).show()
            return
        }

        val parsedTimeline = TimelineSegments.parse(startMs, endMs)
        val parsedSegments = parsedTimeline.segments
        if (parsedSegments == null || parsedTimeline.error != null) {
            Toast.makeText(context, parsedTimeline.error ?: "Mốc thời gian không hợp lệ", Toast.LENGTH_LONG).show()
            return
        }
        val segments = parsedSegments.map { segment ->
            segment.startMs / 1000.0 to (segment.endMs?.div(1000.0) ?: 0.0)
        }

        isProcessing = true
        progressMsg = "Đang đọc thông tin tệp..."
        hasOutput = false
        outputPath = ""

        coroutineScope.launch(Dispatchers.IO) {
            var workDir: File? = null
            var pendingOutput: File? = null
            try {
                val safPath = mediaEngine.getSafParameter(inputUri)
                    ?: error("Không thể mở tệp đã chọn")

                val mimeType = context.contentResolver.getType(inputUri).orEmpty()
                val audioExtensions = listOf(".mp3", ".m4a", ".wav", ".flac", ".ogg", ".aac", ".opus")
                val isAudio = mimeType.startsWith("audio/") || audioExtensions.any { fileName.endsWith(it, true) }

                var sourceDurationSec = 0.0
                var sourceHasAudio = isAudio
                runCatching {
                    val retriever = MediaMetadataRetriever()
                    try {
                        retriever.setDataSource(context, inputUri)
                        sourceDurationSec = (
                            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                                ?.toLongOrNull() ?: 0L
                            ) / 1000.0
                        sourceHasAudio = isAudio || retriever
                            .extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO)
                            .equals("yes", ignoreCase = true)
                    } finally {
                        retriever.release()
                    }
                }

                segments.forEachIndexed { index, (startSec, endSec) ->
                    require(startSec >= 0.0) { "Mốc bắt đầu đoạn ${index + 1} không hợp lệ" }
                    if (sourceDurationSec > 0.0) {
                        require(startSec < sourceDurationSec) {
                            "Mốc bắt đầu đoạn ${index + 1} nằm ngoài thời lượng tệp"
                        }
                        require(endSec <= 0.0 || endSec <= sourceDurationSec + 0.05) {
                            "Mốc kết thúc đoạn ${index + 1} nằm ngoài thời lượng tệp"
                        }
                    }
                }

                val totalDurationSec = segments.sumOf { (startSec, endSec) ->
                    when {
                        endSec > startSec -> endSec - startSec
                        sourceDurationSec > startSec -> sourceDurationSec - startSec
                        else -> 0.0
                    }
                }

                val outputExtension = if (isAudio) SettingsManager.getAudioFormatExt(context) else "mp4"
                val outputFile = FileExportManager.resultFile(context, "trimmed", outputExtension)
                pendingOutput = outputFile
                val audioEncodingArgs = SettingsManager.getAudioEncodingArgs(context)
                val videoEncodingArgs = buildString {
                    append("-map 0:v:0 -map 0:a:0? -c:v mpeg4 -q:v 3 -pix_fmt yuv420p")
                    if (sourceHasAudio) append(" -c:a aac -b:a 256k")
                    append(" -movflags +faststart")
                }

                workDir = File(context.cacheDir, "trim_work_${System.currentTimeMillis()}").apply { mkdirs() }
                val parts = mutableListOf<File>()

                for ((index, segment) in segments.withIndex()) {
                    val (startSec, endSec) = segment
                    val durationSec = if (endSec > startSec) endSec - startSec else 0.0
                    val durationArgument = if (durationSec > 0.0) "-t $durationSec" else ""
                    val part = File(workDir, "part_${index.toString().padStart(3, '0')}.$outputExtension")
                    val encodingArguments = if (isAudio) "-vn $audioEncodingArgs" else videoEncodingArgs
                    val command = "-y -ss $startSec -i \"$safPath\" $durationArgument $encodingArguments \"${part.absolutePath}\""

                    var succeeded = false
                    mediaEngine.executeFFmpegCommand(command, diagnosticPhase = "trim_segment").collect { state ->
                        when (state) {
                            is MediaEngine.ExecutionState.Progress -> withContext(Dispatchers.Main) {
                                progressMsg = "Đang cắt đoạn ${index + 1}/${segments.size}..."
                            }
                            is MediaEngine.ExecutionState.Success -> succeeded = true
                            is MediaEngine.ExecutionState.Error -> withContext(Dispatchers.Main) {
                                progressMsg = "Không thể xử lý đoạn ${index + 1} (mã ${state.returnCode})"
                            }
                            else -> Unit
                        }
                    }
                    require(succeeded && part.isFile && part.length() > 0L) {
                        "Không tạo được đoạn ${index + 1}"
                    }
                    parts += part
                }

                val combinedFile = if (parts.size == 1) {
                    parts.first()
                } else {
                    withContext(Dispatchers.Main) { progressMsg = "Đang nối ${parts.size} đoạn..." }
                    val listFile = File(workDir, "concat.txt").apply {
                        writeText(parts.joinToString("\n") { "file '${it.absolutePath.replace("'", "'\\''")}'" })
                    }
                    val joinedFile = File(workDir, "joined.$outputExtension")
                    var joined = false
                    mediaEngine.executeFFmpegCommand(
                        "-y -f concat -safe 0 -i \"${listFile.absolutePath}\" -c copy \"${joinedFile.absolutePath}\"",
                        diagnosticPhase = "concat_trim_segments",
                    ).collect { state ->
                        when (state) {
                            is MediaEngine.ExecutionState.Progress -> withContext(Dispatchers.Main) {
                                progressMsg = "Đang nối các đoạn..."
                            }
                            is MediaEngine.ExecutionState.Success -> joined = true
                            is MediaEngine.ExecutionState.Error -> withContext(Dispatchers.Main) {
                                progressMsg = "Không thể nối các đoạn (mã ${state.returnCode})"
                            }
                            else -> Unit
                        }
                    }
                    require(joined && joinedFile.isFile && joinedFile.length() > 0L) {
                        "Không tạo được tệp sau khi nối"
                    }
                    joinedFile
                }

                val requestedFadeSec = SettingsManager.getFadeDurationSec(context).toDouble()
                val fadeSec = AudioMath.clampedFadeDuration(requestedFadeSec, totalDurationSec)

                if (fadeSec > 0.0 && sourceHasAudio) {
                    withContext(Dispatchers.Main) { progressMsg = "Đang áp dụng fade..." }
                    val fadeOutStart = (totalDurationSec - fadeSec).coerceAtLeast(0.0)
                    val fadeFilter = "afade=t=in:st=0:d=$fadeSec,afade=t=out:st=$fadeOutStart:d=$fadeSec"
                    val finalEncoding = if (isAudio) {
                        "-vn $audioEncodingArgs"
                    } else {
                        "-map 0:v:0 -map 0:a:0? -c:v copy -c:a aac -b:a 256k -movflags +faststart"
                    }
                    var faded = false
                    mediaEngine.executeFFmpegCommand(
                        "-y -i \"${combinedFile.absolutePath}\" -af \"$fadeFilter\" $finalEncoding \"${outputFile.absolutePath}\"",
                        diagnosticPhase = "apply_trim_fade",
                    ).collect { state ->
                        when (state) {
                            is MediaEngine.ExecutionState.Progress -> withContext(Dispatchers.Main) {
                                progressMsg = "Đang hoàn thiện tệp..."
                            }
                            is MediaEngine.ExecutionState.Success -> faded = true
                            is MediaEngine.ExecutionState.Error -> withContext(Dispatchers.Main) {
                                progressMsg = "Không thể áp dụng fade (mã ${state.returnCode})"
                            }
                            else -> Unit
                        }
                    }
                    require(faded) { "Không thể áp dụng hiệu ứng fade" }
                } else if (!combinedFile.renameTo(outputFile)) {
                    combinedFile.copyTo(outputFile, overwrite = true)
                }

                require(outputFile.isFile && outputFile.length() > 0L) { "Không tạo được tệp kết quả" }
                withContext(Dispatchers.Main) {
                    progressMsg = "Xử lý thành công"
                    isProcessing = false
                    hasOutput = true
                    outputPath = outputFile.absolutePath
                    pendingOutput = null
                }
            } catch (cancelled: CancellationException) {
                pendingOutput?.delete()
                throw cancelled
            } catch (error: Exception) {
                pendingOutput?.delete()
                val message = error.message ?: "Không thể cắt tệp"
                DiagnosticLogger.error(
                    component = "TrimScreen",
                    event = "trim_pipeline_failed",
                    message = message,
                    error = error,
                )
                withContext(Dispatchers.Main) {
                    progressMsg = "Lỗi: $message"
                    isProcessing = false
                    Toast.makeText(context, progressMsg, Toast.LENGTH_LONG).show()
                }
            } finally {
                workDir?.deleteRecursively()
            }
        }
    }

    ToolScaffold(
        title = "Cắt audio / video",
        onNavigateBack = { navController.popBackStack() },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Button(onClick = { launcher.launch(arrayOf("audio/*", "video/*")) }, modifier = Modifier.fillMaxWidth()) {
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
            ) {
                Text("Bắt đầu cắt", fontWeight = FontWeight.Bold)
            }

            if (hasOutput) {
                Card(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Đã cắt xong! File lưu tạm tại:\n$outputPath", style = MaterialTheme.typography.bodySmall)
                        Spacer(modifier = Modifier.height(8.dp))
                        ResultFileActions(file = File(outputPath))
                        Spacer(modifier = Modifier.height(16.dp))
                        Text("▶ Xem/Nghe file kết quả:", fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 8.dp))
                        com.aistudio.mediatool.ui.components.VideoPlayer(uri = Uri.fromFile(File(outputPath)))
                    }
                }
            }

        }
    }
}
