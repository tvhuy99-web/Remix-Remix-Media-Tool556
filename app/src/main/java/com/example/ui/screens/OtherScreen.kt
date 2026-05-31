package com.example.ui.screens

import android.net.Uri
import android.os.Environment
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
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.example.core.media.MediaEngine
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import com.example.ui.components.AccessibleCheckboxRow
import com.example.ui.components.AccessibleSliderColumn

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OtherScreen(navController: NavController) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val mediaEngine = remember { MediaEngine(context) }

    var fileUri by remember { mutableStateOf<Uri?>(null) }
    var fileName by remember { mutableStateOf("Chưa chọn") }
    var resultUri by remember { mutableStateOf<Uri?>(null) }
    
    var isProcessing by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    var statusText by remember { mutableStateOf("Sẵn sàng") }

    var exoPlayer by remember { mutableStateOf<androidx.media3.exoplayer.ExoPlayer?>(null) }
    var isPlayingBase by remember { mutableStateOf(false) }
    var isPlayingResult by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        onDispose {
            exoPlayer?.release()
        }
    }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            fileUri = uri
            fileName = getFileName(context, uri) ?: "unknown"
            resultUri = null
            exoPlayer?.release()
            exoPlayer = null
            isPlayingBase = false
            isPlayingResult = false
        }
    }

    fun playAudio(uri: Uri?, isResult: Boolean) {
        if (uri == null) return
        if (exoPlayer == null) {
            exoPlayer = androidx.media3.exoplayer.ExoPlayer.Builder(context).build()
        }
        val p = exoPlayer!!
        if ((isResult && isPlayingResult) || (!isResult && isPlayingBase)) {
            p.pause()
            if (isResult) isPlayingResult = false else isPlayingBase = false
        } else {
            p.setMediaItem(androidx.media3.common.MediaItem.fromUri(uri))
            p.prepare()
            p.play()
            if (isResult) {
                isPlayingResult = true
                isPlayingBase = false
            } else {
                isPlayingBase = true
                isPlayingResult = false
            }
        }
    }

    var isVideoMode by remember { mutableStateOf(false) }
    var modeIndex by remember { mutableStateOf(0) }
    var expandedMode by remember { mutableStateOf(false) }

    val audioModes = listOf("Xử lý Hiệu ứng", "Chuyển đổi định dạng")
    val videoModes = listOf("Hiệu ứng (Giữ Video)", "Trích xuất Âm thanh", "Tắt tiếng Video", "Trích xuất Ảnh (Thumbnail)", "Nén dung lượng Video")
    val currentModes = if (isVideoMode) videoModes else audioModes

    var extIndex by remember { mutableIntStateOf(0) }
    var resIndex by remember { mutableIntStateOf(0) }

    // States for effects
    var enableTimeMocks by remember { mutableStateOf(false) }
    var enable8d by remember { mutableStateOf(false) }
    var enableNorm by remember { mutableStateOf(false) }
    var enableNg by remember { mutableStateOf(false) }
    var enableSpeedPitch by remember { mutableStateOf(false) }
    var enablePan by remember { mutableStateOf(false) }
    var enableAutoPan by remember { mutableStateOf(false) }
    var enableEcho by remember { mutableStateOf(false) }
    var enableReverb by remember { mutableStateOf(false) }
    var enableComp by remember { mutableStateOf(false) }
    var enableEq by remember { mutableStateOf(false) }

    var enableDenoise by remember { mutableStateOf(false) }
    var enableSilenceRemove by remember { mutableStateOf(false) }

    var denoiseLevel by remember { mutableFloatStateOf(25f) }
    var silenceThreshold by remember { mutableFloatStateOf(30f) }
    
    var denoiseStartMs by remember { mutableStateOf("") }
    var denoiseEndMs by remember { mutableStateOf("") }
    var silenceRemoveStartMs by remember { mutableStateOf("") }
    var silenceRemoveEndMs by remember { mutableStateOf("") }

    var normStartMs by remember { mutableStateOf("") }
    var normEndMs by remember { mutableStateOf("") }
    var ngStartMs by remember { mutableStateOf("") }
    var ngEndMs by remember { mutableStateOf("") }
    var pitchStartMs by remember { mutableStateOf("") }
    var pitchEndMs by remember { mutableStateOf("") }
    var panStartMs by remember { mutableStateOf("") }
    var panEndMs by remember { mutableStateOf("") }
    var autoPanStartMs by remember { mutableStateOf("") }
    var autoPanEndMs by remember { mutableStateOf("") }
    var eightDStartMs by remember { mutableStateOf("") }
    var eightDEndMs by remember { mutableStateOf("") }
    var eightDCycle by remember { mutableFloatStateOf(8f) }
    var eightDRoomSize by remember { mutableFloatStateOf(50f) }
    var echoStartMs by remember { mutableStateOf("") }
    var echoEndMs by remember { mutableStateOf("") }
    var reverbStartMs by remember { mutableStateOf("") }
    var reverbEndMs by remember { mutableStateOf("") }
    var compStartMs by remember { mutableStateOf("") }
    var compEndMs by remember { mutableStateOf("") }
    var eqStartMs by remember { mutableStateOf("") }
    var eqEndMs by remember { mutableStateOf("") }

    // Value states
    var targetPeakPercent by remember { mutableFloatStateOf(100f) }
    
    var ngPresetIndex by remember { mutableIntStateOf(1) }
    val ngPresets = listOf("Lọc nhẹ (Môi trường tĩnh)", "Lọc trung bình (Quạt máy, ồn nền)", "Lọc mạnh (Môi trường ồn ào)", "Tùy chỉnh thủ công")
    var expandedNgPreset by remember { mutableStateOf(false) }
    
    var ngOpenThresh by remember { mutableFloatStateOf(-30f) }
    var ngCloseThresh by remember { mutableFloatStateOf(-35f) }
    var ngAttackMs by remember { mutableFloatStateOf(5f) }
    var ngHoldMs by remember { mutableFloatStateOf(50f) }
    var ngReleaseMs by remember { mutableFloatStateOf(200f) }
    
    var speedFactor by remember { mutableFloatStateOf(1f) }
    var pitchFactor by remember { mutableFloatStateOf(1f) }
    var panVal by remember { mutableFloatStateOf(50f) }
    
    var autoPanCycle by remember { mutableFloatStateOf(4000f) }
    var autoPanDirIndex by remember { mutableIntStateOf(0) }
    val autoPanDirs = listOf("Hướng Auto Pan: Từ Trái", "Hướng Auto Pan: Từ Phải")
    var expandedAutoPanDir by remember { mutableStateOf(false) }
    
    var echoDelayMs by remember { mutableFloatStateOf(300f) }
    var echoDecay by remember { mutableFloatStateOf(0.5f) }
    var reverbRoomSize by remember { mutableFloatStateOf(0.5f) }
    var reverbDamping by remember { mutableFloatStateOf(0.5f) }
    var reverbWet by remember { mutableFloatStateOf(0.3f) }
    
    var compIsLimiter by remember { mutableStateOf(false) }
    var compThresholdDb by remember { mutableFloatStateOf(-10f) }
    var compRatio by remember { mutableFloatStateOf(4f) }
    var compAttackMs by remember { mutableFloatStateOf(10f) }
    var compReleaseMs by remember { mutableFloatStateOf(100f) }
    var compMakeupDb by remember { mutableFloatStateOf(0f) }
    
    var eqPresetIndex by remember { mutableIntStateOf(1) }
    val eqPresets = listOf("Tùy chỉnh", "Phẳng (Nguyên bản)", "Siêu trầm (Bass Boost)", "Sáng giọng (Vocal Boost)", "Sắc nét (Treble Boost)", "Nhạc sôi động (V-Shape)")
    var expandedEqPreset by remember { mutableStateOf(false) }
    var eqBands by remember { mutableStateOf(floatArrayOf(0f, 0f, 0f, 0f, 0f)) }
    
    var channelModeIndex by remember { mutableIntStateOf(0) }
    val channelModes = listOf("Chế độ kênh: Giữ nguyên", "Chế độ kênh: Xuất mono", "Chế độ kênh: Xuất stereo")
    var expandedChannel by remember { mutableStateOf(false) }

    var imgExtractTimes by remember { mutableStateOf("") }
    var compressQuality by remember { mutableFloatStateOf(70f) }

    fun processFeature(isPreview: Boolean = false) {
        if (fileUri == null) {
            statusText = "Chưa chọn file"
            return
        }
        exoPlayer?.pause()
        isPlayingBase = false
        isPlayingResult = false
        isProcessing = true
        statusText = if (isPreview) "Đang tạo đoạn nghe thử 10s..." else "Đang xử lý..."
        progress = 10f
        
        coroutineScope.launch(Dispatchers.IO) {
            try {
                val inputSaf = mediaEngine.getSafParameter(fileUri!!) ?: throw Exception("Cannot get input path")
                val outputDir = File(context.cacheDir, "other_outputs").apply { mkdirs() }
                
                var ext = "mp4"
                if (!isVideoMode) {
                    ext = if (modeIndex == 1) { 
                        when (extIndex) { 0 -> "m4a"; 1 -> "wav"; else -> "mp3" }
                    } else com.example.core.SettingsManager.getAudioFormatExt(context)
                } else {
                    if (modeIndex == 1) ext = when (extIndex) { 0 -> "m4a"; 1 -> "wav"; else -> "mp3" }
                }
                
                val isImageExtraction = isVideoMode && modeIndex == 3
                if (isImageExtraction) ext = "jpg"

                val outputFile = File(outputDir, "out_${System.currentTimeMillis()}.$ext")
                
                // Construct FFmpeg command
                val cmd = java.lang.StringBuilder("-y -i \"$inputSaf\" ")
                
                if (isPreview) cmd.append("-t 10 ")
                
                if (isImageExtraction) {
                    val times = imgExtractTimes.split(",").mapNotNull { it.trim().toDoubleOrNull() }
                    if (times.isEmpty()) throw Exception("Chưa nhập thời gian hợp lệ")
                    val imgOutDir = File(context.cacheDir, "TrichXuat_Img_${System.currentTimeMillis()}").apply { mkdirs() }
                    
                    val newCmd = java.lang.StringBuilder("-y ")
                    times.forEach { t ->
                        newCmd.append("-ss $t -i \"$inputSaf\" ")
                    }
                    times.forEachIndexed { i, _ ->
                        newCmd.append("-map ${i}:v -frames:v 1 -q:v 2 \"${imgOutDir.absolutePath}/img_$i.jpg\" ")
                    }
                    cmd.clear()
                    cmd.append(newCmd.toString())
                    
                    mediaEngine.executeFFmpegCommand(cmd.toString()).collect { state ->
                        withContext(Dispatchers.Main) {
                            when (state) {
                                is MediaEngine.ExecutionState.Connecting -> statusText = "Khởi tạo trích xuất..."
                                is MediaEngine.ExecutionState.Progress -> statusText = "Đang trích xuất ảnh..."
                                is MediaEngine.ExecutionState.Success -> {
                                    coroutineScope.launch(Dispatchers.IO) {
                                        try {
                                            val files = imgOutDir.listFiles()?.filter { it.extension == "jpg" }
                                            if (!files.isNullOrEmpty()) {
                                                var savedCount = 0
                                                val resolver = context.contentResolver
                                                files.forEachIndexed { i, file ->
                                                    val values = android.content.ContentValues().apply {
                                                        put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, "TrichXuat_${System.currentTimeMillis()}_$i.jpg")
                                                        put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                                                        put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, android.os.Environment.DIRECTORY_PICTURES + "/TrichXuatVideo")
                                                    }
                                                    val uri = resolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                                                    uri?.let {
                                                        resolver.openOutputStream(it)?.use { out ->
                                                            file.inputStream().use { inStream -> inStream.copyTo(out) }
                                                        }
                                                        savedCount++
                                                    }
                                                }
                                                withContext(Dispatchers.Main) {
                                                    statusText = "Thành công! Đã lưu $savedCount ảnh vào Bộ sưu tập (Ảnh/TrichXuatVideo)."
                                                    progress = 100f
                                                    isProcessing = false
                                                }
                                            } else {
                                                withContext(Dispatchers.Main) {
                                                    statusText = "Lỗi: Không tìm thấy ảnh. Thời gian có thể bị lỗi."
                                                    progress = 100f
                                                    isProcessing = false
                                                }
                                            }
                                        } catch (e: Exception) {
                                            withContext(Dispatchers.Main) {
                                                statusText = "Lỗi lưu ảnh: ${e.message}"
                                                progress = 100f
                                                isProcessing = false
                                            }
                                        }
                                    }
                                }
                                is MediaEngine.ExecutionState.Error -> {
                                    statusText = "Lỗi trích xuất: ${state.failStackTrace}"
                                    isProcessing = false
                                }
                            }
                        }
                    }
                    return@launch
                }
                
                val aFilters = mutableListOf<String>()
                
                if (enableTimeMocks) {
                    // Do nothing for time mock init
                }
                
                fun getEnable(start: String, end: String): String {
                    if (!enableTimeMocks) return ""
                    
                    val startTokens = start.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                    val endTokens = end.split(",").map { it.trim() }.filter { it.isNotEmpty() }

                    if (startTokens.isEmpty()) return ""

                    val conditions = mutableListOf<String>()
                    for (i in startTokens.indices) {
                        val s = startTokens[i].toDoubleOrNull()?.div(1000.0) ?: continue
                        val e = endTokens.getOrNull(i)?.toDoubleOrNull()?.div(1000.0)

                        if (e != null && e > s) {
                            conditions.add("between(t,$s,$e)")
                        } else if (e == null || e == 0.0) {
                            conditions.add("gte(t,$s)")
                        }
                    }

                    if (conditions.isEmpty()) return ""
                    return ":enable='${conditions.joinToString("+")}'"
                }
                
                if (enableNorm) {
                    aFilters.add("volume=${targetPeakPercent/100f}${getEnable(normStartMs, normEndMs)}")
                }
                if (enableDenoise) {
                    aFilters.add("afftdn=nf=-${denoiseLevel.toInt()}${getEnable(denoiseStartMs, denoiseEndMs)}")
                }
                if (enableSilenceRemove) {
                    aFilters.add("silenceremove=start_periods=1:start_duration=0.1:start_threshold=-${silenceThreshold.toInt()}dB:stop_periods=-1:stop_duration=0.5:stop_threshold=-${silenceThreshold.toInt()}dB")
                }
                if (enableNg) {
                    // Cập nhật preset và mức độ lọc nhiễu FFT (afftdn)
                    val nf = when (ngPresetIndex) { 0 -> -20; 1 -> -40; 2 -> -60; else -> -30 }
                    if (ngPresetIndex == 0) { ngOpenThresh = -40f; ngCloseThresh = -45f; ngAttackMs = 10f; ngHoldMs = 100f; ngReleaseMs = 300f }
                    else if (ngPresetIndex == 1) { ngOpenThresh = -30f; ngCloseThresh = -35f; ngAttackMs = 5f; ngHoldMs = 50f; ngReleaseMs = 200f }
                    else if (ngPresetIndex == 2) { ngOpenThresh = -20f; ngCloseThresh = -25f; ngAttackMs = 1f; ngHoldMs = 20f; ngReleaseMs = 100f }
                    aFilters.add("afftdn=nf=$nf,agate=range=0.01:open=${ngOpenThresh}:close=${ngCloseThresh}:attack=${ngAttackMs}:hold=${ngHoldMs}:release=${ngReleaseMs}${getEnable(ngStartMs, ngEndMs)}")
                }
                if (enablePan) {
                    val L = if (panVal <= 50f) 1f else (100f - panVal) / 50f
                    val R = if (panVal >= 50f) 1f else panVal / 50f
                    aFilters.add("pan=stereo|c0=${L}*c0|c1=${R}*c1${getEnable(panStartMs, panEndMs)}")
                }
                if (enableAutoPan) {
                    val hz = 1000f / autoPanCycle
                    aFilters.add("apulsator=mode=sine:hz=${hz}:width=1")
                }
                if (enable8d) {
                    val hz8d = 1f / eightDCycle
                    val d1 = (eightDRoomSize * 0.8f).toInt().coerceAtLeast(1)
                    val d2 = (eightDRoomSize * 1.0f).toInt().coerceAtLeast(2)
                    val dec1 = (eightDRoomSize / 100f * 0.4f).coerceIn(0.01f, 0.8f)
                    val dec2 = (eightDRoomSize / 100f * 0.46f).coerceIn(0.01f, 0.8f)
                    aFilters.add("apulsator=mode=sine:hz=${hz8d}:width=1${getEnable(eightDStartMs, eightDEndMs)},aecho=0.8:0.9:${d1}|${d2}:${dec1}|${dec2}")
                }
                if (enableEcho) {
                    aFilters.add("aecho=0.8:0.9:${echoDelayMs}:${echoDecay}")
                }
                if (enableReverb) {
                    val delays = "${reverbRoomSize * 100f}|${reverbRoomSize * 150f}"
                    val decays = "${reverbWet}|${reverbWet * 0.5f}"
                    aFilters.add("aecho=0.8:0.8:$delays:$decays")
                }
                if (enableComp) {
                    val realRatio = if (compIsLimiter) 20f else compRatio
                    aFilters.add("acompressor=threshold=${compThresholdDb}:ratio=${realRatio}:attack=${compAttackMs}:release=${compReleaseMs}:makeup=${compMakeupDb}${getEnable(compStartMs, compEndMs)}")
                }
                if (enableEq) {
                    val f = listOf(60, 230, 910, 3600, 14000)
                    for (i in 0..4) {
                        aFilters.add("equalizer=f=${f[i]}:width_type=q:width=1:g=${eqBands[i]}${getEnable(eqStartMs, eqEndMs)}")
                    }
                }
                if (enableSpeedPitch) {
                    val sr = 44100 * pitchFactor
                    val t = speedFactor / pitchFactor
                    aFilters.add("asetrate=$sr,atempo=$t")
                }
                
                if (channelModeIndex == 1) { // mono
                    aFilters.add("pan=mono|c0=0.5*c0+0.5*c1")
                } else if (channelModeIndex == 2) { // stereo
                    aFilters.add("aformat=sample_fmts=fltp:channel_layouts=stereo")
                }

                if (aFilters.isNotEmpty() && (!isVideoMode || (isVideoMode && modeIndex == 0) || (isVideoMode && modeIndex == 1))) {
                    aFilters.add("alimiter=limit=-0.1dB:level_in=1:level_out=1") // Tự động khóa peak ở -0.1dB chống xé tiếng (clipping)
                    cmd.append("-af \"${aFilters.joinToString(",")}\" ")
                }
                
                var globalAudioBitrate = com.example.core.SettingsManager.getAudioBitrateArg(context)
                val codecArg = when (ext) {
                    "m4a" -> "-c:a aac"
                    "mp3" -> "-c:a libmp3lame"
                    "wav" -> "-c:a pcm_s16le"
                    "flac" -> "-c:a flac"
                    else -> "-c:a aac"
                }
                
                // Tránh lỗi -c:a copy khi dùng filter hoặc khi format conversion
                if ((aFilters.isNotEmpty() || channelModeIndex > 0 || (isVideoMode && modeIndex == 1) || (!isVideoMode && modeIndex == 1) || codecArg != "-c:a aac") && globalAudioBitrate.contains("-c:a copy")) {
                    globalAudioBitrate = "-b:a 320k" // fallback an toàn để cho phép encode lại
                }

                if (isVideoMode) {
                    when (modeIndex) {
                        0 -> {
                            cmd.append("-c:v copy ")
                            if (aFilters.isNotEmpty() || channelModeIndex > 0 || !globalAudioBitrate.contains("-c:a copy")) {
                                cmd.append("-c:a aac $globalAudioBitrate ")
                            } else {
                                cmd.append("-c:a copy ")
                            }
                        }
                        1 -> {
                            cmd.append("-vn $codecArg ")
                            if (!codecArg.contains("pcm_s16le") && !codecArg.contains("flac")) cmd.append("$globalAudioBitrate ")
                        }
                        2 -> cmd.append("-an -c:v copy ")
                        4 -> { // Nén Video
                            val res = when(resIndex) { 
                                1 -> "-vf scale=-2:720 "
                                2 -> "-vf scale=-2:480 "
                                else -> "-vf \"scale=trunc(iw/2)*2:trunc(ih/2)*2\" " 
                            }
                            val qVal = (31f - (compressQuality * 30f / 100f)).toInt().coerceIn(1, 31)
                            cmd.append("$res -c:v mpeg4 -q:v $qVal -c:a aac $globalAudioBitrate ")
                        }
                    }
                } else {
                    cmd.append("-vn $codecArg ")
                    if (!codecArg.contains("pcm_s16le") && !codecArg.contains("flac")) cmd.append("$globalAudioBitrate ")
                }
                
                cmd.append("\"${outputFile.absolutePath}\"")
                
                mediaEngine.executeFFmpegCommand(cmd.toString()).collect { state ->
                    withContext(Dispatchers.Main) {
                        when (state) {
                            is MediaEngine.ExecutionState.Connecting -> {
                                statusText = "Khởi tạo..."
                                progress = 10f
                            }
                            is MediaEngine.ExecutionState.Progress -> {
                                statusText = "Đang xử lý: ${state.timeInMilliseconds}ms"
                                progress = 50f
                            }
                            is MediaEngine.ExecutionState.Success -> {
                                val outUri = Uri.fromFile(outputFile)
                                resultUri = outUri
                                statusText = if (isPreview) "Tạo mẫu thành công! Đang phát thử..." else "Xử lý thành công!"
                                progress = 100f
                                isProcessing = false
                                if (isPreview) {
                                    playAudio(outUri, true)
                                }
                            }
                            is MediaEngine.ExecutionState.Error -> {
                                statusText = "Lỗi xử lý: ${state.failStackTrace}"
                                isProcessing = false
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    statusText = "Lỗi: ${e.message}"
                    isProcessing = false
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Tính năng khác (Hiệu ứng/Trích xuất)", fontSize = 18.sp, fontWeight = FontWeight.Bold) },
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
            Button(
                onClick = {
                    if (!isProcessing) {
                        isVideoMode = !isVideoMode
                        modeIndex = 0
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondaryContainer, contentColor = Color(0xFF00A0FF))
            ) {
                Text(if (isVideoMode) "Đang ở chế độ: VIDEO (Bấm để đổi sang Audio)" else "Đang ở chế độ: AUDIO (Bấm để đổi sang Video)")
            }

            Button(onClick = { if (!isProcessing) launcher.launch(if (isVideoMode) "video/*" else "audio/*") }, modifier = Modifier.fillMaxWidth()) {
                Text(if (isVideoMode) "Chọn file Video" else "Chọn file Âm thanh")
            }

            Text("File: $fileName", color = MaterialTheme.colorScheme.onSurfaceVariant)

            Button(onClick = { playAudio(fileUri, false) }, modifier = Modifier.fillMaxWidth(), enabled = fileUri != null) {
                Text(if (isPlayingBase) "⏸ Tạm dừng file trước xử lý" else "▶ Nghe thử file trước xử lý")
            }

            ExposedDropdownMenuBox(
                expanded = expandedMode,
                onExpandedChange = { expandedMode = it },
                modifier = Modifier.fillMaxWidth()
            ) {
                OutlinedTextField(
                    value = currentModes.getOrNull(modeIndex) ?: "",
                    onValueChange = {},
                    readOnly = true,
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expandedMode) },
                    modifier = Modifier.menuAnchor().fillMaxWidth(),
                    label = { Text("Chế độ xử lý") }
                )
                ExposedDropdownMenu(expanded = expandedMode, onDismissRequest = { expandedMode = false }) {
                    currentModes.forEachIndexed { index, mode ->
                        DropdownMenuItem(
                            text = { Text(mode) },
                            onClick = {
                                modeIndex = index
                                expandedMode = false
                            }
                        )
                    }
                }
            }

            if ((!isVideoMode && modeIndex == 1) || (isVideoMode && modeIndex == 1)) {
                val exts = listOf("M4A (Nhẹ)", "WAV (Chất lượng cao)", "MP3 (Giả lập đuôi)")
                var expandedExt by remember { mutableStateOf(false) }
                ExposedDropdownMenuBox(
                    expanded = expandedExt,
                    onExpandedChange = { expandedExt = it },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    OutlinedTextField(
                        value = exts[extIndex],
                        onValueChange = {},
                        readOnly = true,
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expandedExt) },
                        modifier = Modifier.menuAnchor().fillMaxWidth(),
                        label = { Text("Định dạng xuất") }
                    )
                    ExposedDropdownMenu(expanded = expandedExt, onDismissRequest = { expandedExt = false }) {
                        exts.forEachIndexed { index, mode ->
                            DropdownMenuItem(text = { Text(mode) }, onClick = { extIndex = index; expandedExt = false })
                        }
                    }
                }
            }

            if (isVideoMode && modeIndex == 3) {
                Text("Nhập các mốc thời gian (giây) để cắt ảnh. Cách nhau bằng dấu phẩy.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(value = imgExtractTimes, onValueChange = { imgExtractTimes = it }, placeholder = { Text("Ví dụ: 1.5, 5, 12") }, label = { Text("Các mốc thời gian (giây)") }, modifier = Modifier.fillMaxWidth())
                Text("* Ảnh sẽ tự động lưu vào Bộ sưu tập.", fontSize = 12.sp, color = Color(0xFF00AA00))
            }

            if (isVideoMode && modeIndex == 4) {
                val resList = listOf("Giữ nguyên độ phân giải", "Giảm xuống 720p", "Giảm xuống 480p")
                var expandedRes by remember { mutableStateOf(false) }
                ExposedDropdownMenuBox(
                    expanded = expandedRes,
                    onExpandedChange = { expandedRes = it },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    OutlinedTextField(
                        value = resList[resIndex],
                        onValueChange = {},
                        readOnly = true,
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expandedRes) },
                        label = { Text("Độ phân giải đầu ra") },
                        modifier = Modifier.menuAnchor().fillMaxWidth()
                    )
                    ExposedDropdownMenu(expanded = expandedRes, onDismissRequest = { expandedRes = false }) {
                        resList.forEachIndexed { index, mode ->
                            DropdownMenuItem(text = { Text(mode) }, onClick = { resIndex = index; expandedRes = false })
                        }
                    }
                }
                AccessibleSliderColumn(
                    label = "Chất lượng nén: ${compressQuality.roundToInt()}%",
                    value = compressQuality,
                    onValueChange = { compressQuality = it },
                    valueRange = 10f..100f
                )
            }

            // Effects section
            if ((!isVideoMode && modeIndex == 0) || (isVideoMode && (modeIndex == 0 || modeIndex == 1))) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .weight(1f)
                                .toggleable(value = enableTimeMocks, onValueChange = { enableTimeMocks = it }, role = Role.Checkbox)
                        ) {
                            Checkbox(checked = enableTimeMocks, onCheckedChange = null)
                            Text("⏱️ Cài thời gian", color = Color(0xFF00A0FF), fontSize = 14.sp)
                        }
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .toggleable(value = enable8d, onValueChange = { enable8d = it }, role = Role.Checkbox)
                            .padding(vertical = 4.dp)
                    ) {
                        Checkbox(checked = enable8d, onCheckedChange = null)
                        Text("🎧 Âm thanh không gian (Nhạc 8D)", color = Color(0xFF8A2BE2), fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    }
                }

                @Composable
                fun TimeBlock(startMs: String, onStartChange: (String) -> Unit, endMs: String, onEndChange: (String) -> Unit, effectName: String) {
                    if (enableTimeMocks) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            OutlinedTextField(value = startMs, onValueChange = onStartChange, modifier = Modifier.weight(1f), label = { Text("Từ $effectName (ms, vd: 0, 50000)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text))
                            OutlinedTextField(value = endMs, onValueChange = onEndChange, modifier = Modifier.weight(1f), label = { Text("Đến $effectName (ms, vd: 10000, 60000)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text))
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                    }
                }
                
                if (enable8d) {
                    TimeBlock(eightDStartMs, { eightDStartMs = it }, eightDEndMs, { eightDEndMs = it }, "Nhạc 8D")
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant), modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("Tùy chỉnh Nhạc 8D", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color(0xFF8A2BE2))
                            AccessibleSliderColumn(
                                label = "Thời gian xoay 1 vòng: ${eightDCycle.roundToInt()} giây",
                                value = eightDCycle,
                                onValueChange = { eightDCycle = it },
                                valueRange = 2f..20f
                            )
                            AccessibleSliderColumn(
                                label = "Độ vang không gian: ${eightDRoomSize.roundToInt()}",
                                value = eightDRoomSize,
                                onValueChange = { eightDRoomSize = it },
                                valueRange = 10f..100f
                            )
                        }
                    }
                }

                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant), modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        AccessibleCheckboxRow(checked = enableNorm, onCheckedChange = { enableNorm = it }, text = "Bật chuẩn hóa âm lượng")
                        if (enableNorm) {
                            TimeBlock(normStartMs, { normStartMs = it }, normEndMs, { normEndMs = it }, "chuẩn hóa âm lượng")
                            AccessibleSliderColumn(
                                label = "Peak mục tiêu: ${targetPeakPercent.roundToInt()}%",
                                value = targetPeakPercent,
                                onValueChange = { targetPeakPercent = it },
                                valueRange = 50f..99f
                            )
                        }
                    }
                }

                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant), modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        AccessibleCheckboxRow(checked = enableDenoise, onCheckedChange = { enableDenoise = it }, text = "Bật Lọc nhiễu (Denoise)")
                        if (enableDenoise) {
                            TimeBlock(denoiseStartMs, { denoiseStartMs = it }, denoiseEndMs, { denoiseEndMs = it }, "Lọc nhiễu")
                            AccessibleSliderColumn(
                                label = "Mức độ giảm nhiễu: ${denoiseLevel.roundToInt()} dB",
                                value = denoiseLevel,
                                onValueChange = { denoiseLevel = it },
                                valueRange = 10f..80f
                            )
                        }
                    }
                }

                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant), modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        AccessibleCheckboxRow(checked = enableSilenceRemove, onCheckedChange = { enableSilenceRemove = it }, text = "Tự động cắt khoảng lặng (Silence Remove)")
                        if (enableSilenceRemove) {
                            AccessibleSliderColumn(
                                label = "Ngưỡng phát hiện: -${silenceThreshold.roundToInt()} dB",
                                value = silenceThreshold,
                                onValueChange = { silenceThreshold = it },
                                valueRange = 20f..60f
                            )
                        }
                    }
                }

                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant), modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        AccessibleCheckboxRow(checked = enableNg, onCheckedChange = { enableNg = it }, text = "Bật Noise Gate (Cổng triệt ồn)")
                        if (enableNg) {
                            TimeBlock(ngStartMs, { ngStartMs = it }, ngEndMs, { ngEndMs = it }, "Noise Gate")
                            ExposedDropdownMenuBox(
                                expanded = expandedNgPreset,
                                onExpandedChange = { expandedNgPreset = it },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                OutlinedTextField(
                                    value = ngPresets[ngPresetIndex],
                                    onValueChange = {},
                                    readOnly = true,
                                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expandedNgPreset) },
                                    modifier = Modifier.menuAnchor().fillMaxWidth(),
                                    label = { Text("Chế độ triệt ồn") }
                                )
                                ExposedDropdownMenu(expanded = expandedNgPreset, onDismissRequest = { expandedNgPreset = false }) {
                                    ngPresets.forEachIndexed { index, mode ->
                                        DropdownMenuItem(text = { Text(mode) }, onClick = { ngPresetIndex = index; expandedNgPreset = false })
                                    }
                                }
                            }
                        }
                    }
                }

                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant), modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        AccessibleCheckboxRow(checked = enableSpeedPitch, onCheckedChange = { enableSpeedPitch = it }, text = "Bật Thay đổi Tốc độ & Độ cao")
                        if (enableSpeedPitch) {
                            if (!isVideoMode) {
                                AccessibleSliderColumn(
                                    label = "Tốc độ (Speed): ${String.format("%.2f", speedFactor)}x",
                                    value = speedFactor,
                                    onValueChange = { speedFactor = it },
                                    valueRange = 0.5f..2.0f
                                )
                            }
                            AccessibleSliderColumn(
                                label = "Độ cao (Pitch): ${String.format("%.2f", pitchFactor)}x",
                                value = pitchFactor,
                                onValueChange = { pitchFactor = it },
                                valueRange = 0.5f..2.0f
                            )
                        }
                    }
                }

                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant), modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        AccessibleCheckboxRow(checked = enablePan, onCheckedChange = { enablePan = it }, text = "Bật Pan tĩnh")
                        if (enablePan) {
                            TimeBlock(panStartMs, { panStartMs = it }, panEndMs, { panEndMs = it }, "Pan trái phải")
                            AccessibleSliderColumn(
                                label = "Pan: ${if (panVal < 50f) "Trái ${panVal.roundToInt()}" else if (panVal > 50f) "Phải ${panVal.roundToInt()}" else "Giữa"}",
                                value = panVal,
                                onValueChange = { panVal = it },
                                valueRange = 1f..100f
                            )
                        }
                    }
                }

                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant), modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        AccessibleCheckboxRow(checked = enableAutoPan, onCheckedChange = { enableAutoPan = it }, text = "Bật Auto Pan (Hiệu ứng đảo tai)")
                        if (enableAutoPan) {
                            AccessibleSliderColumn(
                                label = "Chu kỳ Auto Pan: ${autoPanCycle.roundToInt()} ms",
                                value = autoPanCycle,
                                onValueChange = { autoPanCycle = it },
                                valueRange = 500f..10000f
                            )
                        }
                    }
                }

                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant), modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        AccessibleCheckboxRow(checked = enableEcho, onCheckedChange = { enableEcho = it }, text = "Bật tiếng vang (Echo)")
                        if (enableEcho) {
                            AccessibleSliderColumn(
                                label = "Độ trễ vang: ${echoDelayMs.roundToInt()} ms",
                                value = echoDelayMs,
                                onValueChange = { echoDelayMs = it },
                                valueRange = 50f..2000f
                            )
                            AccessibleSliderColumn(
                                label = "Độ ngân dài: ${String.format("%.1f", echoDecay)}",
                                value = echoDecay,
                                onValueChange = { echoDecay = it },
                                valueRange = 0.1f..0.9f
                            )
                        }
                    }
                }

                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant), modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        AccessibleCheckboxRow(checked = enableReverb, onCheckedChange = { enableReverb = it }, text = "Bật Reverb (Vang phòng thu)")
                        if (enableReverb) {
                            AccessibleSliderColumn(
                                label = "Kích thước phòng: ${(reverbRoomSize * 100).roundToInt()}%",
                                value = reverbRoomSize,
                                onValueChange = { reverbRoomSize = it },
                                valueRange = 0.1f..1.0f
                            )
                            AccessibleSliderColumn(
                                label = "Hấp thụ (Damping): ${(reverbDamping * 100).roundToInt()}%",
                                value = reverbDamping,
                                onValueChange = { reverbDamping = it },
                                valueRange = 0.0f..1.0f
                            )
                            AccessibleSliderColumn(
                                label = "Mức Reverb (Wet): ${(reverbWet * 100).roundToInt()}%",
                                value = reverbWet,
                                onValueChange = { reverbWet = it },
                                valueRange = 0.0f..0.8f
                            )
                        }
                    }
                }

                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant), modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        AccessibleCheckboxRow(checked = enableComp, onCheckedChange = { enableComp = it }, text = "Bật Compressor (Nén âm lượng)")
                        if (enableComp) {
                            TimeBlock(compStartMs, { compStartMs = it }, compEndMs, { compEndMs = it }, "Nén âm lượng Compressor")
                            AccessibleCheckboxRow(checked = compIsLimiter, onCheckedChange = { compIsLimiter = it }, text = "Chế độ Limiter (chặn cứng)")
                            AccessibleSliderColumn(
                                label = "Ngưỡng (Threshold): ${compThresholdDb.roundToInt()} dB",
                                value = compThresholdDb,
                                onValueChange = { compThresholdDb = it },
                                valueRange = -40f..0f
                            )
                            AccessibleSliderColumn(
                                label = "Tỉ lệ nén (Ratio): ${compRatio.roundToInt()}:1",
                                value = compRatio,
                                onValueChange = { compRatio = it },
                                valueRange = 1f..20f
                            )
                            AccessibleSliderColumn(
                                label = "Attack: ${compAttackMs.roundToInt()} ms",
                                value = compAttackMs,
                                onValueChange = { compAttackMs = it },
                                valueRange = 1f..100f
                            )
                            AccessibleSliderColumn(
                                label = "Release: ${compReleaseMs.roundToInt()} ms",
                                value = compReleaseMs,
                                onValueChange = { compReleaseMs = it },
                                valueRange = 10f..1000f
                            )
                            AccessibleSliderColumn(
                                label = "Bù Gain (Makeup): ${compMakeupDb.roundToInt()} dB",
                                value = compMakeupDb,
                                onValueChange = { compMakeupDb = it },
                                valueRange = 0f..20f
                            )
                        }
                    }
                }

                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant), modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        AccessibleCheckboxRow(checked = enableEq, onCheckedChange = { enableEq = it }, text = "Bật EQ (Equalizer 5 dải tần)")
                        if (enableEq) {
                            TimeBlock(eqStartMs, { eqStartMs = it }, eqEndMs, { eqEndMs = it }, "Equalizer")
                            ExposedDropdownMenuBox(
                                expanded = expandedEqPreset,
                                onExpandedChange = { expandedEqPreset = it },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                OutlinedTextField(
                                    value = eqPresets[eqPresetIndex],
                                    onValueChange = {},
                                    readOnly = true,
                                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expandedEqPreset) },
                                    modifier = Modifier.menuAnchor().fillMaxWidth(),
                                    label = { Text("Chọn Preset") }
                                )
                                ExposedDropdownMenu(expanded = expandedEqPreset, onDismissRequest = { expandedEqPreset = false }) {
                                    eqPresets.forEachIndexed { index, mode ->
                                        DropdownMenuItem(text = { Text(mode) }, onClick = { 
                                            eqPresetIndex = index
                                            expandedEqPreset = false
                                            
                                            val presetBands = listOf(
                                                floatArrayOf(0f, 0f, 0f, 0f, 0f),       // Tùy chỉnh
                                                floatArrayOf(0f, 0f, 0f, 0f, 0f),       // Phẳng
                                                floatArrayOf(6f, 4f, 0f, -2f, -2f),     // Siêu trầm
                                                floatArrayOf(-2f, -1f, 4f, 3f, 1f),     // Sáng giọng
                                                floatArrayOf(-3f, -2f, 0f, 4f, 6f),     // Sắc nét
                                                floatArrayOf(5f, 3f, -2f, 4f, 5f)       // Nhạc sôi động
                                            )
                                            eqBands = presetBands[index].copyOf()
                                        })
                                    }
                                }
                            }
                            val freqs = listOf("60Hz (Siêu trầm)", "230Hz (Bass)", "910Hz (Mid)", "3.6kHz (Presence)", "14kHz (Treble)")
                            eqBands.forEachIndexed { i, _ ->
                                AccessibleSliderColumn(
                                    label = "${freqs[i]}: ${if (eqBands[i] > 0) "+" else ""}${eqBands[i].roundToInt()} dB",
                                    value = eqBands[i],
                                    onValueChange = { 
                                        val newBands = eqBands.copyOf()
                                        newBands[i] = it
                                        eqBands = newBands
                                        if (eqPresetIndex != 0) eqPresetIndex = 0
                                    },
                                    valueRange = -15f..15f
                                )
                            }
                        }
                    }
                }

                ExposedDropdownMenuBox(
                    expanded = expandedChannel,
                    onExpandedChange = { expandedChannel = it },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    OutlinedTextField(
                        value = channelModes[channelModeIndex],
                        onValueChange = {},
                        readOnly = true,
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expandedChannel) },
                        label = { Text("Chế độ kênh âm thanh") },
                        modifier = Modifier.menuAnchor().fillMaxWidth()
                    )
                    ExposedDropdownMenu(expanded = expandedChannel, onDismissRequest = { expandedChannel = false }) {
                        channelModes.forEachIndexed { index, mode ->
                            DropdownMenuItem(text = { Text(mode) }, onClick = { channelModeIndex = index; expandedChannel = false })
                        }
                    }
                }
            }

            Text(statusText, modifier = Modifier.fillMaxWidth().padding(top = 16.dp).semantics { liveRegion = LiveRegionMode.Polite }, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), progress = progress / 100f)

            if (resultUri != null) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { playAudio(resultUri, true) }, modifier = Modifier.weight(1f)) {
                        Text(if (isPlayingResult) "⏸ Tạm dừng file kết quả" else "▶ Nghe file kết quả", textAlign = TextAlign.Center)
                    }
                }
            }

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { if (!isProcessing) processFeature(true) }, modifier = Modifier.weight(1f)) {
                    Text("✂️ Tạo mẫu 10s & Nghe thử", textAlign = TextAlign.Center)
                }
            }

            Button(
                onClick = { if (!isProcessing) processFeature(false) },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer)
            ) {
                Text(if (isProcessing) "ĐANG XỬ LÝ..." else "BẮT ĐẦU XỬ LÝ TOÀN BỘ", color = Color(0xFFFF0000), fontWeight = FontWeight.Bold)
            }

            Button(onClick = {
                if (resultUri != null) {
                    val shareIntent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                        type = if (isVideoMode && modeIndex != 1 && modeIndex != 3) "video/*" else "audio/*"
                        putExtra(android.content.Intent.EXTRA_STREAM, resultUri)
                        addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(android.content.Intent.createChooser(shareIntent, "Chia sẻ file"))
                }
            }, modifier = Modifier.fillMaxWidth(), enabled = resultUri != null) {
                Text("Chia sẻ file kết quả")
            }
            OutlinedButton(onClick = {
                fileUri = null
                fileName = "Chưa chọn"
                resultUri = null
                exoPlayer?.release()
                exoPlayer = null
                isPlayingBase = false
                isPlayingResult = false
                progress = 0f
                statusText = "Đã xóa file"
            }, modifier = Modifier.fillMaxWidth(), enabled = !isProcessing) {
                Text("Xóa file hiện tại")
            }
            TextButton(onClick = { navController.popBackStack() }, modifier = Modifier.fillMaxWidth()) {
                Text("Quay lại")
            }
        }
    }
}
