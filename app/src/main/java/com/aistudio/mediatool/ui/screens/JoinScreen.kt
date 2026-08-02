package com.aistudio.mediatool.ui.screens

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.*
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
import com.aistudio.mediatool.core.GetMultipleContentsWithMimeTypes
import com.aistudio.mediatool.core.DocumentUtils
import com.aistudio.mediatool.core.diagnostics.DiagnosticLogger
import com.aistudio.mediatool.core.FileExportManager
import com.aistudio.mediatool.core.SettingsManager
import com.aistudio.mediatool.core.media.MediaEngine
import com.aistudio.mediatool.core.media.AudioMath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import com.aistudio.mediatool.ui.components.ToolScaffold
import com.aistudio.mediatool.ui.components.ResultFileActions


private val UriStateListSaver = Saver<androidx.compose.runtime.snapshots.SnapshotStateList<Uri>, ArrayList<String>>(
    save = { values -> ArrayList(values.map(Uri::toString)) },
    restore = { values -> mutableStateListOf<Uri>().apply { addAll(values.map(Uri::parse)) } },
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JoinScreen(navController: NavController) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val mediaEngine = remember { MediaEngine(context) }
    
    val selectedUris = rememberSaveable(saver = UriStateListSaver) { mutableStateListOf<Uri>() }
    
    var isProcessing by remember { mutableStateOf(false) }
    var progressMsg by remember { mutableStateOf("") }
    var hasOutput by rememberSaveable { mutableStateOf(false) }
    var outputPath by rememberSaveable { mutableStateOf("") }

    val launcher = rememberLauncherForActivityResult(GetMultipleContentsWithMimeTypes()) { uris ->
        uris.forEach { uri ->
            DocumentUtils.persistReadPermission(context, uri)
            if (uri !in selectedUris) selectedUris.add(uri)
        }
        hasOutput = false
        outputPath = ""
    }


    fun startJoinAudio() {
        try {
            if (selectedUris.size < 2) {
                Toast.makeText(context, "Vui lòng chọn ít nhất 2 file để nối", Toast.LENGTH_SHORT).show()
                return
            }

            isProcessing = true
            progressMsg = "Đang chuẩn bị nối..."
            hasOutput = false
            val inputUris = selectedUris.toList()

            coroutineScope.launch(Dispatchers.IO) {
                var pendingOutput: File? = null
                try {
                    val safPaths = inputUris.mapNotNull { mediaEngine.getSafParameter(it) }
                    if (safPaths.size != inputUris.size) {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(context, "Có lỗi khi đọc đường dẫn file gốc", Toast.LENGTH_SHORT).show()
                            isProcessing = false
                        }
                        return@launch
                    }

                    val ext = SettingsManager.getAudioFormatExt(context)
                    val outputFile = FileExportManager.resultFile(context, "joined_audio", ext)
                    pendingOutput = outputFile

                    val durationsMs = inputUris.map { uri ->
                        runCatching {
                            val retriever = android.media.MediaMetadataRetriever()
                            try {
                                retriever.setDataSource(context, uri)
                                retriever
                                    .extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
                                    ?.toLongOrNull()
                                    ?.takeIf { it > 0L }
                            } finally {
                                retriever.release()
                            }
                        }.getOrNull()
                    }
                    val allDurationsKnown = durationsMs.all { it != null }
                    val totalDurationMs = if (allDurationsKnown) durationsMs.filterNotNull().sum() else 0L
                    val totalDurationSec = totalDurationMs / 1000.0

                    val globalFadeSec = SettingsManager.getFadeDurationSec(context).toDouble()
                    val inputs = safPaths.joinToString(" ") { "-i \"$it\"" }
                    val filter = StringBuilder()
                    for (i in safPaths.indices) {
                        filter.append("[$i:a]aresample=48000,aformat=sample_fmts=fltp:channel_layouts=stereo[a$i];")
                    }
                    for (i in safPaths.indices) {
                        filter.append("[a$i]")
                    }
                    filter.append("concat=n=${safPaths.size}:v=0:a=1[out_concat];")
                    
                    val applyGlobalFade = AudioMath.canApplyGlobalFade(globalFadeSec, durationsMs)
                    if (applyGlobalFade) {
                        val fade = AudioMath.clampedFadeDuration(globalFadeSec, totalDurationSec)
                        val fadeOutStart = (totalDurationSec - fade).coerceAtLeast(0.0)
                        filter.append("[out_concat]afade=t=in:st=0:d=$fade,afade=t=out:st=$fadeOutStart:d=$fade[outa]")
                    } else {
                        filter.append("[out_concat]anull[outa]")
                    }
        
                    val encodingArgs = SettingsManager.getAudioEncodingArgs(context)
                    val command = "-y $inputs -filter_complex \"$filter\" -map \"[outa]\" $encodingArgs \"${outputFile.absolutePath}\""

                    mediaEngine.executeFFmpegCommand(command, diagnosticPhase = "join_audio").collect { state ->
                        withContext(Dispatchers.Main) {
                            when (state) {
                                is MediaEngine.ExecutionState.Connecting -> {
                                    progressMsg = "Đang khởi tạo FFmpeg..."
                                }
                                is MediaEngine.ExecutionState.Progress -> {
                                    val percent = AudioMath.progressPercent(state.timeInMilliseconds, totalDurationMs)
                                    progressMsg = if (totalDurationMs > 0L) "Đang xử lý: $percent%" else "Đang xử lý…"
                                }
                                is MediaEngine.ExecutionState.Success -> {
                                    require(outputFile.isFile && outputFile.length() > 0L) { "Không tạo được tệp kết quả" }
                                    progressMsg = if (globalFadeSec > 0.0 && !allDurationsKnown) {
                                        "Nối thành công; đã bỏ qua fade vì không đọc đủ thời lượng nguồn"
                                    } else {
                                        "Nối thành công!"
                                    }
                                    isProcessing = false
                                    hasOutput = true
                                    outputPath = outputFile.absolutePath
                                    pendingOutput = null
                                    Toast.makeText(context, "Nối file thành công!", Toast.LENGTH_SHORT).show()
                                }
                                is MediaEngine.ExecutionState.Error -> {
                                    pendingOutput?.delete()
                                    progressMsg = "Không thể nối các tệp đã chọn"
                                    isProcessing = false
                                    Toast.makeText(context, "FFmpeg không thể nối một hoặc nhiều tệp", Toast.LENGTH_LONG).show()
                                }
                            }
                        }
                    }
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    pendingOutput?.delete()
                    throw cancelled
                } catch(e: Exception) {
                    pendingOutput?.delete()
                    DiagnosticLogger.error(
                        component = "JoinScreen",
                        event = "join_pipeline_failed",
                        message = e.message,
                        error = e,
                    )
                    withContext(Dispatchers.Main) {
                        progressMsg = "Không thể nối tệp: ${e.message ?: "lỗi không xác định"}"
                        isProcessing = false
                        Toast.makeText(context, progressMsg, Toast.LENGTH_LONG).show()
                    }
                }
            }
        } catch(e: Exception) {
            DiagnosticLogger.error(
                component = "JoinScreen",
                event = "join_start_failed",
                message = e.message,
                error = e,
            )
            Toast.makeText(context, "Lỗi khi bắt đầu: ${e.message}", Toast.LENGTH_LONG).show()
            isProcessing = false
        }
    }

    ToolScaffold(
        title = "Nối nhiều tệp audio",
        onNavigateBack = { navController.popBackStack() },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            val currentFormatStr = if (SettingsManager.isAudioLossless(context)) {
                "Gốc/High"
            } else {
                "${SettingsManager.getAudioFormatExt(context).uppercase()} ${SettingsManager.getAudioBitrateArg(context).replace("-b:a ", "")}"
            }

            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.secondaryContainer,
                shape = MaterialTheme.shapes.medium,
            ) {
                Text(
                    "Đầu ra: $currentFormatStr",
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(12.dp),
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }

            Button(onClick = { launcher.launch(arrayOf("audio/*")) }, modifier = Modifier.fillMaxWidth()) {
                Text("Chọn Thêm File Audio (Chọn nhiều được)")
            }

            Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(text = "Danh sách file (${selectedUris.size}):", fontWeight = FontWeight.Bold)
                    if (selectedUris.isEmpty()) {
                        Text("Chưa chọn file nào.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        selectedUris.forEachIndexed { index, uri ->
                            Row(
                                modifier = Modifier.fillMaxWidth(), 
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                val name = DocumentUtils.displayName(context, uri)
                                Text(
                                    text = "${index + 1}. $name",
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.weight(1f)
                                )
                                IconButton(
                                    onClick = { selectedUris.removeAt(index) },
                                    modifier = Modifier.semantics { contentDescription = "Xóa file $name khỏi danh sách" }
                                ) {
                                    Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
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
                onClick = { startJoinAudio() },
                enabled = !isProcessing && selectedUris.size >= 2,
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer
                )
            ) {
                Text("BẮT ĐẦU NỐI FILE", color = Color(0xFFFF0000), fontWeight = FontWeight.Bold)
            }

            if (hasOutput) {
                Card(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Nối xong! File tạm:\n$outputPath", style = MaterialTheme.typography.bodySmall)
                        Spacer(modifier = Modifier.height(8.dp))
                        ResultFileActions(file = File(outputPath))
                        Spacer(modifier = Modifier.height(16.dp))
                        Text("▶ Nghe file kết quả:", fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 8.dp))
                        com.aistudio.mediatool.ui.components.VideoPlayer(uri = Uri.fromFile(File(outputPath)))
                    }
                }
            }

            OutlinedButton(onClick = { selectedUris.clear(); hasOutput = false; outputPath = "" }, modifier = Modifier.fillMaxWidth()) {
                Text("Xóa danh sách hiện tại")
            }

        }
    }
}
