package com.aistudio.mediatool.ui.screens

import android.media.MediaMetadataRetriever
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.media3.exoplayer.ExoPlayer
import androidx.navigation.NavController
import com.aistudio.mediatool.core.DocumentUtils
import com.aistudio.mediatool.core.FileExportManager
import com.aistudio.mediatool.core.GetContentWithMimeTypes
import com.aistudio.mediatool.core.SettingsManager
import com.aistudio.mediatool.core.diagnostics.DiagnosticLogger
import com.aistudio.mediatool.core.media.AudioMath
import com.aistudio.mediatool.core.media.Media3VideoTrimmer
import com.aistudio.mediatool.core.media.MediaEngine
import com.aistudio.mediatool.core.media.TimelineSegments
import com.aistudio.mediatool.core.media.TrimVideoCommandBuilder
import com.aistudio.mediatool.ui.components.ResultFileActions
import com.aistudio.mediatool.ui.components.ToolScaffold
import com.aistudio.mediatool.ui.components.VideoPlayer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrimScreen(navController: NavController) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val mediaEngine = remember { MediaEngine(context) }
    val media3VideoTrimmer = remember { Media3VideoTrimmer(context) }
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current

    var selectedUriText by rememberSaveable { mutableStateOf<String?>(null) }
    val selectedUri = selectedUriText?.let(Uri::parse)
    var fileName by rememberSaveable { mutableStateOf("Chưa chọn") }
    var exoPlayer by remember { mutableStateOf<ExoPlayer?>(null) }
    var startMs by rememberSaveable { mutableStateOf("") }
    var endMs by rememberSaveable { mutableStateOf("") }
    var startFieldFocused by remember { mutableStateOf(false) }
    var endFieldFocused by remember { mutableStateOf(false) }
    var isProcessing by remember { mutableStateOf(false) }
    var progressMsg by remember { mutableStateOf("") }
    var hasOutput by rememberSaveable { mutableStateOf(false) }
    var outputPath by rememberSaveable { mutableStateOf("") }

    fun dismissKeyboard() {
        focusManager.clearFocus(force = true)
        keyboardController?.hide()
    }

    val launcher = rememberLauncherForActivityResult(GetContentWithMimeTypes()) { uri: Uri? ->
        uri?.let {
            DocumentUtils.persistReadPermission(context, it)
            selectedUriText = it.toString()
            fileName = DocumentUtils.displayName(context, it)
            startMs = ""
            endMs = ""
            hasOutput = false
            outputPath = ""
            progressMsg = ""
        }
    }

    fun startTrim() {
        dismissKeyboard()
        val inputUri = selectedUri ?: run {
            Toast.makeText(context, "Vui lòng chọn file", Toast.LENGTH_SHORT).show()
            return
        }
        val parsedTimeline = TimelineSegments.parse(startMs, endMs)
        val parsedSegments = parsedTimeline.segments
        if (parsedSegments == null || parsedTimeline.error != null) {
            Toast.makeText(context, parsedTimeline.error ?: "Mốc thời gian không hợp lệ", Toast.LENGTH_LONG).show()
            return
        }

        isProcessing = true
        progressMsg = "Đang đọc thông tin tệp..."
        hasOutput = false
        outputPath = ""

        coroutineScope.launch(Dispatchers.IO) {
            var workDir: File? = null
            var pendingOutput: File? = null
            try {
                val mimeType = context.contentResolver.getType(inputUri).orEmpty()
                val audioExtensions = listOf(".mp3", ".m4a", ".wav", ".flac", ".ogg", ".aac", ".opus")
                val isAudio = mimeType.startsWith("audio/") || audioExtensions.any { fileName.endsWith(it, true) }

                var sourceDurationSec = 0.0
                var sourceHasAudio = isAudio
                var sourceHasVideo = false
                val metadataRead = runCatching {
                    val retriever = MediaMetadataRetriever()
                    try {
                        retriever.setDataSource(context, inputUri)
                        sourceDurationSec = (
                            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                                ?.toLongOrNull() ?: 0L
                            ) / 1000.0
                        sourceHasAudio = isAudio || retriever
                            .extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO)
                            .equals("yes", true)
                        sourceHasVideo = retriever
                            .extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO)
                            .equals("yes", true)
                    } finally {
                        retriever.release()
                    }
                }
                if (!isAudio) {
                    require(metadataRead.isSuccess && sourceHasVideo) { "Tệp đã chọn không có luồng video hợp lệ" }
                    require(sourceDurationSec > 0.0) { "Không đọc được thời lượng video nguồn" }
                }

                parsedSegments.forEachIndexed { index, segment ->
                    val startSec = segment.startMs / 1000.0
                    val endSec = segment.endMs?.div(1000.0)
                    require(startSec >= 0.0) { "Mốc bắt đầu đoạn ${index + 1} không hợp lệ" }
                    if (sourceDurationSec > 0.0) {
                        require(startSec < sourceDurationSec) {
                            "Mốc bắt đầu đoạn ${index + 1} nằm ngoài thời lượng tệp"
                        }
                        require(endSec == null || endSec <= sourceDurationSec + 0.05) {
                            "Mốc kết thúc đoạn ${index + 1} nằm ngoài thời lượng tệp"
                        }
                    }
                }

                val totalDurationSec = parsedSegments.sumOf { segment ->
                    val startSec = segment.startMs / 1000.0
                    val endSec = segment.endMs?.div(1000.0)
                    when {
                        endSec != null && endSec > startSec -> endSec - startSec
                        sourceDurationSec > startSec -> sourceDurationSec - startSec
                        else -> 0.0
                    }
                }
                val outputExtension = if (isAudio) SettingsManager.getAudioFormatExt(context) else "mp4"
                val outputFile = FileExportManager.resultFile(context, "trimmed", outputExtension)
                pendingOutput = outputFile

                if (!isAudio) {
                    val requestedFadeSec = SettingsManager.getFadeDurationSec(context).toDouble()
                    val canUseOptimizedTrim = parsedSegments.size == 1 && (!sourceHasAudio || requestedFadeSec <= 0.0)
                    var videoCompleted = false

                    if (canUseOptimizedTrim) {
                        withContext(Dispatchers.Main) {
                            progressMsg = "Đang cắt video nhanh..."
                        }
                        try {
                            val result = media3VideoTrimmer.trim(
                                inputUri = inputUri,
                                outputFile = outputFile,
                                segment = parsedSegments.single(),
                            )
                            validateVideoOutput(outputFile, totalDurationSec)
                            videoCompleted = true
                            DiagnosticLogger.info(
                                component = "TrimScreen",
                                event = "video_trim_media3_success",
                                fields = mapOf(
                                    "output_bytes" to outputFile.length(),
                                    "reported_bytes" to result.fileSizeBytes,
                                    "video_bitrate" to result.videoBitrate,
                                    "audio_bitrate" to result.audioBitrate,
                                    "optimization_result" to result.optimizationResult,
                                    "expected_duration_ms" to (totalDurationSec * 1000.0).toLong(),
                                ),
                            )
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (error: Exception) {
                            outputFile.delete()
                            DiagnosticLogger.warn(
                                component = "TrimScreen",
                                event = "video_trim_media3_fallback",
                                message = "Media3 trim không hoàn tất; chuyển sang FFmpeg tương thích",
                                fields = mapOf(
                                    "segments" to parsedSegments.size,
                                    "expected_duration_ms" to (totalDurationSec * 1000.0).toLong(),
                                ),
                                error = error,
                            )
                            withContext(Dispatchers.Main) {
                                progressMsg = "Đang chuyển sang cắt video tương thích..."
                            }
                        }
                    }

                    if (!videoCompleted) {
                        val safPath = mediaEngine.getSafParameter(inputUri)
                            ?: error("Không thể mở tệp đã chọn")
                        val built = TrimVideoCommandBuilder.build(
                            inputPath = safPath,
                            outputPath = outputFile.absolutePath,
                            segments = parsedSegments,
                            sourceDurationSec = sourceDurationSec,
                            sourceHasAudio = sourceHasAudio,
                            requestedFadeSec = requestedFadeSec,
                        )
                        var succeeded = false
                        mediaEngine.executeFFmpegCommand(
                            built.command,
                            diagnosticPhase = "trim_video_ffmpeg_fallback",
                        ).collect { state ->
                            when (state) {
                                is MediaEngine.ExecutionState.Progress -> withContext(Dispatchers.Main) {
                                    progressMsg = "Đang cắt video tương thích..."
                                }
                                is MediaEngine.ExecutionState.Success -> succeeded = true
                                is MediaEngine.ExecutionState.Error -> withContext(Dispatchers.Main) {
                                    progressMsg = "Không thể cắt video (mã ${state.returnCode})"
                                }
                                else -> Unit
                            }
                        }
                        require(succeeded && outputFile.isFile && outputFile.length() > 0L) {
                            "FFmpeg không tạo được video kết quả"
                        }
                        validateVideoOutput(outputFile, built.expectedDurationSec)
                        DiagnosticLogger.info(
                            component = "TrimScreen",
                            event = "video_trim_ffmpeg_success",
                            fields = mapOf(
                                "output_bytes" to outputFile.length(),
                                "segments" to parsedSegments.size,
                                "expected_duration_ms" to (built.expectedDurationSec * 1000.0).toLong(),
                            ),
                        )
                    }
                } else {
                    val safPath = mediaEngine.getSafParameter(inputUri)
                        ?: error("Không thể mở tệp đã chọn")
                    val audioEncodingArgs = SettingsManager.getAudioEncodingArgs(context)
                    workDir = File(context.cacheDir, "trim_work_${System.currentTimeMillis()}").apply { mkdirs() }
                    val parts = mutableListOf<File>()
                    for ((index, segment) in parsedSegments.withIndex()) {
                        val startSec = segment.startMs / 1000.0
                        val endSec = segment.endMs?.div(1000.0)
                        val durationArgument = endSec
                            ?.minus(startSec)
                            ?.takeIf { it > 0.0 }
                            ?.let { "-t $it" }
                            .orEmpty()
                        val part = File(
                            workDir,
                            "part_${index.toString().padStart(3, '0')}.$outputExtension",
                        )
                        val command = "-y -ss $startSec -i \"$safPath\" $durationArgument -vn $audioEncodingArgs \"${part.absolutePath}\""
                        var succeeded = false
                        mediaEngine.executeFFmpegCommand(
                            command,
                            diagnosticPhase = "trim_audio_segment",
                        ).collect { state ->
                            when (state) {
                                is MediaEngine.ExecutionState.Progress -> withContext(Dispatchers.Main) {
                                    progressMsg = "Đang cắt đoạn ${index + 1}/${parsedSegments.size}..."
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
                        withContext(Dispatchers.Main) {
                            progressMsg = "Đang nối ${parts.size} đoạn..."
                        }
                        val listFile = File(workDir, "concat.txt").apply {
                            writeText(
                                parts.joinToString("\n") {
                                    "file '${it.absolutePath.replace("'", "'\\''")}'"
                                },
                            )
                        }
                        val joinedFile = File(workDir, "joined.$outputExtension")
                        var joined = false
                        mediaEngine.executeFFmpegCommand(
                            "-y -f concat -safe 0 -i \"${listFile.absolutePath}\" -c copy \"${joinedFile.absolutePath}\"",
                            diagnosticPhase = "concat_trim_audio_segments",
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

                    val fadeSec = AudioMath.clampedFadeDuration(
                        SettingsManager.getFadeDurationSec(context).toDouble(),
                        totalDurationSec,
                    )
                    if (fadeSec > 0.0) {
                        withContext(Dispatchers.Main) {
                            progressMsg = "Đang áp dụng fade..."
                        }
                        val fadeOutStart = (totalDurationSec - fadeSec).coerceAtLeast(0.0)
                        val fadeFilter = "afade=t=in:st=0:d=$fadeSec,afade=t=out:st=$fadeOutStart:d=$fadeSec"
                        var faded = false
                        mediaEngine.executeFFmpegCommand(
                            "-y -i \"${combinedFile.absolutePath}\" -af \"$fadeFilter\" -vn $audioEncodingArgs \"${outputFile.absolutePath}\"",
                            diagnosticPhase = "apply_trim_audio_fade",
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
                    require(outputFile.isFile && outputFile.length() > 0L) {
                        "Không tạo được tệp kết quả"
                    }
                }

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
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Button(
                onClick = {
                    dismissKeyboard()
                    launcher.launch(arrayOf("audio/*", "video/*"))
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Chọn file cần cắt")
            }
            Text(
                text = "File: $fileName",
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (selectedUri != null) {
                VideoPlayer(
                    uri = selectedUri,
                    onPlayerReady = { player -> exoPlayer = player },
                )
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedTextField(
                            value = startMs,
                            onValueChange = {
                                startMs = it.filter { char -> char.isDigit() || char == ',' || char == ' ' }
                            },
                            modifier = Modifier
                                .weight(1f)
                                .onFocusChanged { state ->
                                    if (startFieldFocused && !state.isFocused && !endFieldFocused) {
                                        keyboardController?.hide()
                                    }
                                    startFieldFocused = state.isFocused
                                },
                            label = { Text("Bắt đầu (ms, VD: 0, 5000)") },
                            placeholder = { Text("0, 5000") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Text,
                                imeAction = ImeAction.Next,
                            ),
                            keyboardActions = KeyboardActions(
                                onNext = {
                                    focusManager.moveFocus(androidx.compose.ui.focus.FocusDirection.Next)
                                },
                            ),
                        )
                        Button(
                            onClick = {
                                dismissKeyboard()
                                exoPlayer?.let { player ->
                                    val curr = player.currentPosition.toString()
                                    startMs = if (startMs.isBlank()) curr else "$startMs, $curr"
                                }
                            },
                            modifier = Modifier.semantics {
                                contentDescription = "Lấy mốc thời gian bắt đầu đang phát trên trình phát"
                            },
                        ) {
                            Text("Lấy mốc hiện tại")
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedTextField(
                            value = endMs,
                            onValueChange = {
                                endMs = it.filter { char -> char.isDigit() || char == ',' || char == ' ' }
                            },
                            modifier = Modifier
                                .weight(1f)
                                .onFocusChanged { state ->
                                    if (endFieldFocused && !state.isFocused && !startFieldFocused) {
                                        keyboardController?.hide()
                                    }
                                    endFieldFocused = state.isFocused
                                },
                            label = { Text("Kết thúc (VD: 3000, 0)") },
                            placeholder = { Text("3000, 0") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Text,
                                imeAction = ImeAction.Done,
                            ),
                            keyboardActions = KeyboardActions(onDone = { dismissKeyboard() }),
                        )
                        Button(
                            onClick = {
                                dismissKeyboard()
                                exoPlayer?.let { player ->
                                    val curr = player.currentPosition.toString()
                                    endMs = if (endMs.isBlank()) curr else "$endMs, $curr"
                                }
                            },
                            modifier = Modifier.semantics {
                                contentDescription = "Lấy mốc thời gian kết thúc đang phát trên trình phát"
                            },
                        ) {
                            Text("Lấy mốc hiện tại")
                        }
                    }
                }
            }

            Divider(modifier = Modifier.padding(vertical = 8.dp))
            if (isProcessing || progressMsg.isNotEmpty()) {
                Text(
                    text = progressMsg,
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { liveRegion = LiveRegionMode.Polite },
                    textAlign = TextAlign.Center,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
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
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            "Đã cắt xong! File lưu tạm tại:\n$outputPath",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        ResultFileActions(file = File(outputPath))
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            "▶ Xem/Nghe file kết quả:",
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(bottom = 8.dp),
                        )
                        VideoPlayer(uri = Uri.fromFile(File(outputPath)))
                    }
                }
            }
        }
    }
}

private fun validateVideoOutput(file: File, expectedDurationSec: Double) {
    require(file.isFile && file.length() > 0L) {
        "Video kết quả không tồn tại hoặc đang rỗng"
    }
    val retriever = MediaMetadataRetriever()
    try {
        retriever.setDataSource(file.absolutePath)
        val hasVideo = retriever
            .extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO)
            .equals("yes", true)
        val durationMs = retriever
            .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            ?.toLongOrNull() ?: 0L
        val width = retriever
            .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
            ?.toIntOrNull() ?: 0
        val height = retriever
            .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
            ?.toIntOrNull() ?: 0
        require(hasVideo && width > 0 && height > 0) {
            "Video kết quả không có luồng hình hợp lệ"
        }
        require(durationMs > 0L) {
            "Video kết quả không có thời lượng hợp lệ"
        }
        if (expectedDurationSec > 0.0) {
            val actualSec = durationMs / 1000.0
            val toleranceSec = maxOf(1.0, expectedDurationSec * 0.08)
            require(abs(actualSec - expectedDurationSec) <= toleranceSec) {
                "Thời lượng video kết quả sai: mong đợi khoảng %.2f giây, nhận %.2f giây"
                    .format(expectedDurationSec, actualSec)
            }
        }
    } finally {
        retriever.release()
    }
}
