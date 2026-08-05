package com.aistudio.mediatool.ui.screens

import android.net.Uri
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
import com.aistudio.mediatool.core.GetContentWithMimeTypes
import com.aistudio.mediatool.core.DocumentUtils
import com.aistudio.mediatool.core.diagnostics.DiagnosticLogger
import com.aistudio.mediatool.core.media.MediaEngine
import com.aistudio.mediatool.core.media.AudioMath
import com.aistudio.mediatool.core.media.TimelineSegments
import com.aistudio.mediatool.core.media.MediaEffectPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import com.aistudio.mediatool.ui.components.AccessibleCheckboxRow
import com.aistudio.mediatool.ui.components.AccessibleSliderColumn
import com.aistudio.mediatool.ui.components.ToolScaffold
import com.aistudio.mediatool.ui.components.ResultFileActions
import com.aistudio.mediatool.core.FileExportManager
import com.aistudio.mediatool.core.SettingsManager
import com.aistudio.mediatool.core.spatial.SpatialAudioConfig
import com.aistudio.mediatool.core.spatial.SpatialAudioEngine
import com.aistudio.mediatool.core.spatial.SpatialAudioPreferences
import com.aistudio.mediatool.ui.components.SpatialAudioControls
import kotlin.math.pow

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OtherScreen(navController: NavController) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val mediaEngine = remember { MediaEngine(context) }
    val spatialAudioEngine = remember { SpatialAudioEngine(context, mediaEngine) }

    var fileUriText by rememberSaveable { mutableStateOf<String?>(null) }
    val fileUri = fileUriText?.let(Uri::parse)
    var fileName by rememberSaveable { mutableStateOf("Chưa chọn") }
    var resultPath by rememberSaveable { mutableStateOf<String?>(null) }
    val resultFile = resultPath?.let(::File)?.takeIf { it.isFile && it.length() > 0L }
    val resultUri = resultFile?.let(Uri::fromFile)
    
    var isProcessing by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    var statusText by remember { mutableStateOf("Sẵn sàng") }
    var enableSpatialAudio by rememberSaveable { mutableStateOf(false) }
    var spatialAudioConfig by remember { mutableStateOf(SpatialAudioPreferences.load(context)) }

    fun updateSpatialAudioConfig(next: SpatialAudioConfig) {
        val normalized = next.normalized()
        spatialAudioConfig = normalized
        SpatialAudioPreferences.save(context, normalized)
    }

    val sofaLauncher = rememberLauncherForActivityResult(GetContentWithMimeTypes()) { uri ->
        if (uri != null) {
            coroutineScope.launch(Dispatchers.IO) {
                runCatching {
                    val directory = File(context.filesDir, "hrtf").apply { mkdirs() }
                    val target = File(directory, "custom_${System.currentTimeMillis()}.sofa")
                    context.contentResolver.openInputStream(uri).use { input ->
                        requireNotNull(input) { "Không thể mở tệp SOFA" }
                        target.outputStream().use(input::copyTo)
                    }
                    require(target.length() > 0L) { "Tệp SOFA rỗng" }
                    withContext(Dispatchers.Main) {
                        spatialAudioConfig.customSofaPath?.let(::File)?.delete()
                        updateSpatialAudioConfig(spatialAudioConfig.copy(customSofaPath = target.absolutePath))
                        statusText = "Đã chọn HRTF SOFA: ${target.name}"
                    }
                }.onFailure { error ->
                    withContext(Dispatchers.Main) {
                        statusText = "Không thể nhập SOFA: ${error.message ?: "Tệp không hợp lệ"}"
                    }
                }
            }
        }
    }

    var exoPlayer by remember { mutableStateOf<androidx.media3.exoplayer.ExoPlayer?>(null) }
    var isPlayingBase by remember { mutableStateOf(false) }
    var isPlayingResult by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        onDispose {
            exoPlayer?.release()
        }
    }

    val launcher = rememberLauncherForActivityResult(GetContentWithMimeTypes()) { uri ->
        if (uri != null) {
            DocumentUtils.persistReadPermission(context, uri)
            fileUriText = uri.toString()
            fileName = DocumentUtils.displayName(context, uri)
            resultPath = null
            exoPlayer?.release()
            exoPlayer = null
            isPlayingBase = false
            isPlayingResult = false
        }
    }

    fun playAudio(uri: Uri?, isResult: Boolean) {
        if (uri == null) return
        if (exoPlayer == null) {
            exoPlayer = androidx.media3.exoplayer.ExoPlayer.Builder(context).build().apply {
                addListener(object : androidx.media3.common.Player.Listener {
                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        if (!isPlaying) {
                            isPlayingBase = false
                            isPlayingResult = false
                        }
                    }
                    override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                        isPlayingBase = false
                        isPlayingResult = false
                        statusText = "Không thể phát tệp: ${error.errorCodeName}"
                    }
                })
            }
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

    var isVideoMode by rememberSaveable { mutableStateOf(false) }
    var modeIndex by rememberSaveable { mutableStateOf(0) }
    var expandedMode by remember { mutableStateOf(false) }

    val audioModes = listOf("Xử lý Hiệu ứng", "Chuyển đổi định dạng")
    val videoModes = listOf("Hiệu ứng (Giữ Video)", "Trích xuất Âm thanh", "Tắt tiếng Video", "Trích xuất Ảnh (Thumbnail)", "Nén dung lượng Video")
    val currentModes = if (isVideoMode) videoModes else audioModes
    val showAudioEffects = MediaEffectPolicy.supportsAudioFilters(isVideoMode, modeIndex)

    var extIndex by rememberSaveable { mutableIntStateOf(0) }
    var resIndex by rememberSaveable { mutableIntStateOf(0) }

    // States for effects
    var enableTimeMocks by rememberSaveable { mutableStateOf(false) }
    var enableNorm by rememberSaveable { mutableStateOf(false) }
    var enableNg by rememberSaveable { mutableStateOf(false) }
    var enableSpeedPitch by rememberSaveable { mutableStateOf(false) }
    var enablePan by rememberSaveable { mutableStateOf(false) }
    var enableAutoPan by rememberSaveable { mutableStateOf(false) }
    var enableEcho by rememberSaveable { mutableStateOf(false) }
    var enableReverb by rememberSaveable { mutableStateOf(false) }
    var enableComp by rememberSaveable { mutableStateOf(false) }
    var enableEq by rememberSaveable { mutableStateOf(false) }

    var enableDenoise by rememberSaveable { mutableStateOf(false) }
    var enableSilenceRemove by rememberSaveable { mutableStateOf(false) }

    var denoiseLevel by rememberSaveable { mutableFloatStateOf(25f) }
    var silenceThreshold by rememberSaveable { mutableFloatStateOf(30f) }
    
    var denoiseStartMs by rememberSaveable { mutableStateOf("") }
    var denoiseEndMs by rememberSaveable { mutableStateOf("") }

    var ngStartMs by rememberSaveable { mutableStateOf("") }
    var ngEndMs by rememberSaveable { mutableStateOf("") }
    var panStartMs by rememberSaveable { mutableStateOf("") }
    var panEndMs by rememberSaveable { mutableStateOf("") }
    var compStartMs by rememberSaveable { mutableStateOf("") }
    var compEndMs by rememberSaveable { mutableStateOf("") }
    var eqStartMs by rememberSaveable { mutableStateOf("") }
    var eqEndMs by rememberSaveable { mutableStateOf("") }

    // Value states
    var targetPeakPercent by rememberSaveable { mutableFloatStateOf(95f) }
    
    var ngPresetIndex by rememberSaveable { mutableIntStateOf(1) }
    val ngPresets = listOf("Lọc nhẹ (Môi trường tĩnh)", "Lọc trung bình (Quạt máy, ồn nền)", "Lọc mạnh (Môi trường ồn ào)", "Tùy chỉnh thủ công")
    var expandedNgPreset by rememberSaveable { mutableStateOf(false) }
    
    var ngOpenThresh by rememberSaveable { mutableFloatStateOf(-30f) }
    var ngAttackMs by rememberSaveable { mutableFloatStateOf(5f) }
    var ngReleaseMs by rememberSaveable { mutableFloatStateOf(200f) }
    
    var speedFactor by rememberSaveable { mutableFloatStateOf(1f) }
    var pitchFactor by rememberSaveable { mutableFloatStateOf(1f) }
    var panVal by rememberSaveable { mutableFloatStateOf(50f) }
    
    var autoPanCycle by rememberSaveable { mutableFloatStateOf(4000f) }
    
    var echoDelayMs by rememberSaveable { mutableFloatStateOf(300f) }
    var echoDecay by rememberSaveable { mutableFloatStateOf(0.5f) }
    var reverbRoomSize by rememberSaveable { mutableFloatStateOf(0.5f) }
    var reverbDamping by rememberSaveable { mutableFloatStateOf(0.5f) }
    var reverbWet by rememberSaveable { mutableFloatStateOf(0.3f) }
    
    var compIsLimiter by rememberSaveable { mutableStateOf(false) }
    var compThresholdDb by rememberSaveable { mutableFloatStateOf(-10f) }
    var compRatio by rememberSaveable { mutableFloatStateOf(4f) }
    var compAttackMs by rememberSaveable { mutableFloatStateOf(10f) }
    var compReleaseMs by rememberSaveable { mutableFloatStateOf(100f) }
    var compMakeupDb by rememberSaveable { mutableFloatStateOf(0f) }
    
    var eqPresetIndex by rememberSaveable { mutableIntStateOf(1) }
    val eqPresets = listOf("Tùy chỉnh", "Phẳng (Nguyên bản)", "Siêu trầm (Bass Boost)", "Sáng giọng (Vocal Boost)", "Sắc nét (Treble Boost)", "Nhạc sôi động (V-Shape)")
    var expandedEqPreset by rememberSaveable { mutableStateOf(false) }
    var eqBands by rememberSaveable { mutableStateOf(listOf(0f, 0f, 0f, 0f, 0f)) }
    
    var channelModeIndex by rememberSaveable { mutableIntStateOf(0) }
    val channelModes = listOf("Chế độ kênh: Giữ nguyên", "Chế độ kênh: Xuất mono", "Chế độ kênh: Xuất stereo")
    var expandedChannel by rememberSaveable { mutableStateOf(false) }

    var imgExtractTimes by rememberSaveable { mutableStateOf("") }
    var compressQuality by rememberSaveable { mutableFloatStateOf(70f) }

    fun processFeature(isPreview: Boolean = false) {
        val sourceUri = fileUri
        if (sourceUri == null) {
            statusText = "Chưa chọn file"
            return
        }

        exoPlayer?.pause()
        isPlayingBase = false
        isPlayingResult = false
        resultPath = null
        isProcessing = true
        statusText = if (isPreview) "Đang tạo đoạn nghe thử 10 giây..." else "Đang xử lý..."
        progress = 5f

        coroutineScope.launch(Dispatchers.IO) {
            var pendingOutput: File? = null
            var pendingDirectory: File? = null
            try {
                val inputSaf = mediaEngine.getSafParameter(sourceUri)
                    ?: error("Không thể đọc tệp đã chọn")
                val sourceDurationMs = runCatching {
                    android.media.MediaMetadataRetriever().let { retriever ->
                        try {
                            retriever.setDataSource(context, sourceUri)
                            retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                        } finally {
                            retriever.release()
                        }
                    }
                }.getOrDefault(0L)
                val isImageExtraction = isVideoMode && modeIndex == 3

                if (isImageExtraction) {
                    val times = imgExtractTimes
                        .split(',')
                        .mapNotNull { it.trim().toDoubleOrNull() }
                        .filter { it >= 0.0 }
                        .distinct()
                    require(times.isNotEmpty()) { "Hãy nhập ít nhất một mốc thời gian hợp lệ" }

                    val imageDir = File(context.cacheDir, "image_extract_${System.currentTimeMillis()}")
                        .apply { mkdirs() }
                    pendingDirectory = imageDir
                    val command = buildString {
                        append("-y ")
                        times.forEach { time -> append("-ss $time -i \"$inputSaf\" ") }
                        times.indices.forEach { index ->
                            append("-map $index:v:0 -frames:v 1 -q:v 2 ")
                            append("\"${File(imageDir, "frame_${index + 1}.jpg").absolutePath}\" ")
                        }
                    }

                    mediaEngine.executeFFmpegCommand(command, diagnosticPhase = "extract_frames").collect { state ->
                        when (state) {
                            is MediaEngine.ExecutionState.Connecting -> withContext(Dispatchers.Main) {
                                statusText = "Khởi tạo trích xuất ảnh..."
                                progress = 10f
                            }
                            is MediaEngine.ExecutionState.Progress -> withContext(Dispatchers.Main) {
                                statusText = "Đang trích xuất ảnh..."
                                progress = if (sourceDurationMs > 0L) {
                                    (10f + 80f * state.timeInMilliseconds.toFloat() / sourceDurationMs).coerceIn(10f, 90f)
                                } else 50f
                            }
                            is MediaEngine.ExecutionState.Success -> {
                                val images = imageDir.listFiles()
                                    ?.filter { it.isFile && it.extension.equals("jpg", true) && it.length() > 0L }
                                    ?.sortedBy { it.name }
                                    .orEmpty()
                                require(images.isNotEmpty()) {
                                    "Mốc thời gian không hợp lệ"
                                }
                                val zip = FileExportManager.zipFiles(context, images, "anh_trich_xuat")
                                imageDir.deleteRecursively()
                                pendingDirectory = null
                                withContext(Dispatchers.Main) {
                                    resultPath = zip.absolutePath
                                    statusText = "Đã tạo ZIP ${images.size} ảnh"
                                    progress = 100f
                                    isProcessing = false
                                }
                            }
                            is MediaEngine.ExecutionState.Error -> withContext(Dispatchers.Main) {
                                imageDir.deleteRecursively()
                                statusText = "Lỗi trích xuất ảnh: ${state.failStackTrace}"
                                progress = 0f
                                isProcessing = false
                            }
                        }
                    }
                    return@launch
                }

                val extension = when {
                    isVideoMode && modeIndex != 1 -> "mp4"
                    modeIndex == 1 -> when (extIndex) {
                        0 -> "m4a"
                        1 -> "wav"
                        else -> "mp3"
                    }
                    else -> SettingsManager.getAudioFormatExt(context)
                }
                val output = FileExportManager.resultFile(
                    context,
                    if (isPreview) "mau_10_giay" else "ket_qua_xu_ly",
                    extension,
                )
                pendingOutput = output

                fun enableExpression(start: String, end: String): String {
                    if (!enableTimeMocks) return ""
                    val parsed = TimelineSegments.parse(start, end)
                    val segments = parsed.segments
                        ?: throw IllegalArgumentException(parsed.error ?: "Mốc thời gian hiệu ứng không hợp lệ")
                    if (segments.size == 1 && segments[0].startMs == 0L && segments[0].endMs == null) return ""
                    val conditions = segments.map { segment ->
                        val startSeconds = segment.startMs / 1000.0
                        val endSeconds = segment.endMs?.div(1000.0)
                        if (endSeconds != null) {
                            "between(t,$startSeconds,$endSeconds)"
                        } else {
                            "gte(t,$startSeconds)"
                        }
                    }
                    return if (conditions.isEmpty()) "" else ":enable='${conditions.joinToString("+")}'"
                }

                fun amplitudeFromDb(db: Float): Double =
                    10.0.pow(db.toDouble() / 20.0).coerceIn(0.000001, 1.0)

                fun atempoFilters(value: Float): List<String> {
                    var remaining = value.toDouble().coerceIn(0.25, 4.0)
                    val filters = mutableListOf<String>()
                    while (remaining > 2.0) {
                        filters += "atempo=2.0"
                        remaining /= 2.0
                    }
                    while (remaining < 0.5) {
                        filters += "atempo=0.5"
                        remaining /= 0.5
                    }
                    if (kotlin.math.abs(remaining - 1.0) > 0.0001) {
                        filters += "atempo=${"%.5f".format(java.util.Locale.US, remaining)}"
                    }
                    return filters
                }

                val supportsAudioFilters = showAudioEffects
                val audioFilters = mutableListOf<String>()
                if (supportsAudioFilters) {
                if (enableNorm) {
                    val truePeakDb = AudioMath.truePeakDbFromPercent(targetPeakPercent)
                    val formattedPeak = "%.3f".format(java.util.Locale.US, truePeakDb)
                    // loudnorm là chuẩn hóa loudness EBU R128 thật; chế độ động
                    // một lượt phù hợp khi còn ghép cùng chuỗi hiệu ứng khác.
                    audioFilters += "loudnorm=I=-16:LRA=11:TP=$formattedPeak"
                    // loudnorm động nội suy ở 192 kHz; trả về 48 kHz để encoder
                    // AAC/MP3 trên thiết bị Android luôn nhận sample rate phổ biến.
                    audioFilters += "aresample=48000"
                }
                if (enableDenoise) {
                    audioFilters += "afftdn=nf=-${denoiseLevel.toInt()}${enableExpression(denoiseStartMs, denoiseEndMs)}"
                }
                if (enableSilenceRemove) {
                    audioFilters += "silenceremove=start_periods=1:start_duration=0.1:start_threshold=-${silenceThreshold.toInt()}dB:stop_periods=-1:stop_duration=0.5:stop_threshold=-${silenceThreshold.toInt()}dB"
                }
                if (enableNg) {
                    val preset = when (ngPresetIndex) {
                        0 -> Triple(-40f, 10f, 300f)
                        1 -> Triple(-30f, 5f, 200f)
                        2 -> Triple(-20f, 1f, 100f)
                        else -> Triple(ngOpenThresh, ngAttackMs, ngReleaseMs)
                    }
                    val noiseFloor = when (ngPresetIndex) {
                        0 -> -20
                        1 -> -40
                        2 -> -60
                        else -> -30
                    }
                    val gateThreshold = amplitudeFromDb(preset.first)
                    val gateRatio = when (ngPresetIndex) {
                        0 -> 2
                        1 -> 4
                        2 -> 8
                        else -> 4
                    }
                    audioFilters += "afftdn=nf=$noiseFloor"
                    audioFilters += "agate=threshold=$gateThreshold:ratio=$gateRatio:range=0.01:attack=${preset.second}:release=${preset.third}${enableExpression(ngStartMs, ngEndMs)}"
                }
                if (enablePan) {
                    val gain = AudioMath.stereoPan(panVal.roundToInt())
                    audioFilters += "pan=stereo|c0=${gain.left}*c0|c1=${gain.right}*c1${enableExpression(panStartMs, panEndMs)}"
                }
                if (enableAutoPan) {
                    audioFilters += "apulsator=mode=sine:hz=${1000f / autoPanCycle}:width=1"
                }
                if (enableEcho) {
                    audioFilters += "aecho=0.8:0.9:${echoDelayMs}:${echoDecay}"
                }
                if (enableReverb) {
                    val delays = "${reverbRoomSize * 100f}|${reverbRoomSize * 150f}"
                    val absorption = (1f - reverbDamping).coerceIn(0.05f, 1f)
                    val firstDecay = (reverbWet * absorption).coerceIn(0f, 0.9f)
                    val secondDecay = (firstDecay * 0.55f).coerceIn(0f, 0.9f)
                    audioFilters += "aecho=0.8:0.8:$delays:${firstDecay}|${secondDecay}"
                }
                if (enableComp) {
                    val threshold = amplitudeFromDb(compThresholdDb)
                    val ratio = if (compIsLimiter) 20f else compRatio
                    val makeup = 10.0.pow(compMakeupDb.toDouble() / 20.0).coerceIn(1.0, 64.0)
                    audioFilters += "acompressor=threshold=$threshold:ratio=$ratio:attack=$compAttackMs:release=$compReleaseMs:makeup=$makeup${enableExpression(compStartMs, compEndMs)}"
                }
                if (enableEq) {
                    listOf(60, 230, 910, 3600, 14000).forEachIndexed { index, frequency ->
                        audioFilters += "equalizer=f=$frequency:width_type=q:width=1:g=${eqBands[index]}${enableExpression(eqStartMs, eqEndMs)}"
                    }
                }
                if (enableSpeedPitch) {
                    val sampleRate = (44_100f * pitchFactor).roundToInt().coerceAtLeast(8_000)
                    audioFilters += "asetrate=$sampleRate"
                    audioFilters += "aresample=44100"
                    audioFilters += atempoFilters(speedFactor / pitchFactor)
                }
                if (channelModeIndex == 1) {
                    audioFilters += "pan=mono|c0=0.5*c0+0.5*c1"
                } else if (channelModeIndex == 2) {
                    audioFilters += "aformat=sample_fmts=fltp:channel_layouts=stereo"
                }
                }

                if (audioFilters.isNotEmpty()) {
                    audioFilters += "alimiter=limit=0.9886:level=0:latency=1"
                }

                if (enableSpatialAudio) {
                    SpatialAudioPreferences.save(context, spatialAudioConfig)
                    spatialAudioEngine.process(
                        inputSaf = inputSaf,
                        output = output,
                        sourceDurationMs = sourceDurationMs,
                        config = spatialAudioConfig,
                        preFilters = audioFilters,
                        isVideoMode = isVideoMode,
                        modeIndex = modeIndex,
                        extension = extension,
                        preview = isPreview,
                    ).collect { state ->
                        when (state) {
                            is SpatialAudioEngine.State.Progress -> withContext(Dispatchers.Main) {
                                statusText = state.message
                                progress = state.percent
                            }
                            is SpatialAudioEngine.State.Success -> withContext(Dispatchers.Main) {
                                resultPath = output.absolutePath
                                pendingOutput = null
                                statusText = if (isPreview) {
                                    "Đã tạo mẫu Spatial Audio 10 giây"
                                } else {
                                    "Spatial Audio hoàn tất • RMS ${String.format(java.util.Locale.US, "%.1f", state.metrics.rmsDbfs)} dBFS"
                                }
                                progress = 100f
                                isProcessing = false
                                if (isPreview) playAudio(Uri.fromFile(output), true)
                            }
                            is SpatialAudioEngine.State.Error -> withContext(Dispatchers.Main) {
                                output.delete()
                                statusText = "Lỗi Spatial Audio: ${state.message}"
                                progress = 0f
                                isProcessing = false
                            }
                        }
                    }
                    return@launch
                }

                val command = buildString {
                    append("-y -i \"$inputSaf\" ")
                    if (isPreview) append("-t 10 ")
                    if (audioFilters.isNotEmpty()) {
                        append("-af \"${audioFilters.joinToString(",")}\" ")
                    }

                    when {
                        isVideoMode && modeIndex == 0 -> {
                            append("-c:v copy ")
                            if (audioFilters.isEmpty() && channelModeIndex == 0) {
                                append("-c:a copy ")
                            } else {
                                append("-c:a aac -b:a ${SettingsManager.getAudioBitrateInt(context) / 1000}k ")
                            }
                            append("-movflags +faststart ")
                        }
                        isVideoMode && modeIndex == 1 -> {
                            append("-vn ")
                            append(
                                when (extension) {
                                    "m4a" -> "-c:a aac -b:a ${SettingsManager.getAudioBitrateInt(context) / 1000}k "
                                    "mp3" -> "-c:a libmp3lame -b:a ${SettingsManager.getAudioBitrateInt(context) / 1000}k "
                                    "wav" -> "-c:a pcm_s16le "
                                    else -> "-c:a flac "
                                },
                            )
                        }
                        isVideoMode && modeIndex == 2 -> append("-an -c:v copy -movflags +faststart ")
                        isVideoMode && modeIndex == 4 -> {
                            val resize = when (resIndex) {
                                1 -> "scale=-2:720"
                                2 -> "scale=-2:480"
                                else -> "scale=trunc(iw/2)*2:trunc(ih/2)*2"
                            }
                            val quality = (31f - compressQuality * 30f / 100f).roundToInt().coerceIn(1, 31)
                            append("-vf \"$resize\" -c:v mpeg4 -q:v $quality ")
                            append("-c:a aac -b:a 192k -movflags +faststart ")
                        }
                        else -> {
                            append("-vn ")
                            append(
                                when (extension) {
                                    "m4a" -> "-c:a aac -b:a ${SettingsManager.getAudioBitrateInt(context) / 1000}k "
                                    "mp3" -> "-c:a libmp3lame -b:a ${SettingsManager.getAudioBitrateInt(context) / 1000}k "
                                    "wav" -> "-c:a pcm_s16le "
                                    "flac" -> "-c:a flac "
                                    else -> SettingsManager.getAudioEncodingArgs(context) + " "
                                },
                            )
                        }
                    }
                    append("\"${output.absolutePath}\"")
                }

                mediaEngine.executeFFmpegCommand(
                    command,
                    diagnosticPhase = if (isVideoMode) "other_video_mode_$modeIndex" else "other_audio_mode_$modeIndex",
                ).collect { state ->
                    when (state) {
                        is MediaEngine.ExecutionState.Connecting -> withContext(Dispatchers.Main) {
                            statusText = "Khởi tạo bộ xử lý..."
                            progress = 10f
                        }
                        is MediaEngine.ExecutionState.Progress -> withContext(Dispatchers.Main) {
                            statusText = "Đang xử lý: ${state.timeInMilliseconds} ms"
                            progress = if (sourceDurationMs > 0L) {
                                (10f + 85f * state.timeInMilliseconds.toFloat() / sourceDurationMs).coerceIn(10f, 95f)
                            } else 50f
                        }
                        is MediaEngine.ExecutionState.Success -> {
                            require(output.isFile && output.length() > 0L) { "FFmpeg không tạo được tệp kết quả" }
                            withContext(Dispatchers.Main) {
                                resultPath = output.absolutePath
                                pendingOutput = null
                                statusText = if (isPreview) "Đã tạo mẫu 10 giây" else "Xử lý thành công"
                                progress = 100f
                                isProcessing = false
                                if (isPreview) playAudio(Uri.fromFile(output), true)
                            }
                        }
                        is MediaEngine.ExecutionState.Error -> withContext(Dispatchers.Main) {
                            output.delete()
                            statusText = "Lỗi xử lý: ${state.failStackTrace}"
                            progress = 0f
                            isProcessing = false
                        }
                    }
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                pendingOutput?.delete()
                pendingDirectory?.deleteRecursively()
                throw cancelled
            } catch (error: Exception) {
                pendingOutput?.delete()
                pendingDirectory?.deleteRecursively()
                DiagnosticLogger.error(
                    component = "OtherScreen",
                    event = "other_tool_pipeline_failed",
                    message = error.message,
                    fields = mapOf("video_mode" to isVideoMode, "mode_index" to modeIndex),
                    error = error,
                )
                withContext(Dispatchers.Main) {
                    statusText = "Lỗi: ${error.message ?: "Không xác định"}"
                    progress = 0f
                    isProcessing = false
                }
            }
        }
    }

    ToolScaffold(
        title = "Hiệu ứng và trích xuất",
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
            Button(
                onClick = {
                    if (!isProcessing) {
                        isVideoMode = !isVideoMode
                        modeIndex = 0
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondaryContainer, contentColor = MaterialTheme.colorScheme.onSecondaryContainer)
            ) {
                Text(if (isVideoMode) "Đang ở chế độ: VIDEO (Bấm để đổi sang Audio)" else "Đang ở chế độ: AUDIO (Bấm để đổi sang Video)")
            }

            Button(onClick = { if (!isProcessing) launcher.launch(arrayOf(if (isVideoMode) "video/*" else "audio/*")) }, modifier = Modifier.fillMaxWidth()) {
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
                OutlinedTextField(value = imgExtractTimes, onValueChange = { imgExtractTimes = it }, placeholder = { Text("Ví dụ: 1.5, 5, 12") }, label = { Text("Các mốc thời gian (giây)") }, modifier = Modifier.fillMaxWidth())
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
            if (showAudioEffects) {
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
                            Text("Giới hạn thời gian hiệu ứng", color = MaterialTheme.colorScheme.primary, fontSize = 14.sp)
                        }
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
                
                SpatialAudioControls(
                    enabled = enableSpatialAudio,
                    onEnabledChange = { enableSpatialAudio = it },
                    config = spatialAudioConfig,
                    onConfigChange = ::updateSpatialAudioConfig,
                    onPickSofa = { sofaLauncher.launch(arrayOf("application/octet-stream", "*/*")) },
                    onClearSofa = {
                        spatialAudioConfig.customSofaPath?.let(::File)?.delete()
                        updateSpatialAudioConfig(spatialAudioConfig.copy(customSofaPath = null))
                    },
                )

                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant), modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        AccessibleCheckboxRow(checked = enableNorm, onCheckedChange = { enableNorm = it }, text = "Chuẩn hóa âm lượng")
                        if (enableNorm) {
                            Text(
                                "Chuẩn hóa áp dụng cho toàn bộ tệp để đo loudness ổn định.",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            AccessibleSliderColumn(
                                label = "Trần true peak: ${targetPeakPercent.roundToInt()}%",
                                value = targetPeakPercent,
                                onValueChange = { targetPeakPercent = it },
                                valueRange = 50f..99f
                            )
                        }
                    }
                }

                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant), modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        AccessibleCheckboxRow(checked = enableDenoise, onCheckedChange = { enableDenoise = it }, text = "Lọc nhiễu")
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
                        AccessibleCheckboxRow(checked = enableSilenceRemove, onCheckedChange = { enableSilenceRemove = it }, text = "Cắt khoảng lặng")
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
                        AccessibleCheckboxRow(checked = enableNg, onCheckedChange = { enableNg = it }, text = "Noise Gate")
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
                        AccessibleCheckboxRow(checked = enableSpeedPitch, onCheckedChange = { enableSpeedPitch = it }, text = "Tốc độ và cao độ")
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
                        AccessibleCheckboxRow(checked = enablePan, onCheckedChange = { enablePan = it }, text = "Pan")
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
                        AccessibleCheckboxRow(checked = enableAutoPan, onCheckedChange = { enableAutoPan = it }, text = "Auto Pan")
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
                        AccessibleCheckboxRow(checked = enableEcho, onCheckedChange = { enableEcho = it }, text = "Echo")
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
                        AccessibleCheckboxRow(checked = enableReverb, onCheckedChange = { enableReverb = it }, text = "Reverb")
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
                        AccessibleCheckboxRow(checked = enableComp, onCheckedChange = { enableComp = it }, text = "Compressor")
                        if (enableComp) {
                            TimeBlock(compStartMs, { compStartMs = it }, compEndMs, { compEndMs = it }, "Nén âm lượng Compressor")
                            AccessibleCheckboxRow(checked = compIsLimiter, onCheckedChange = { compIsLimiter = it }, text = "Limiter")
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
                        AccessibleCheckboxRow(checked = enableEq, onCheckedChange = { enableEq = it }, text = "EQ")
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
                                                listOf(0f, 0f, 0f, 0f, 0f),       // Tùy chỉnh
                                                listOf(0f, 0f, 0f, 0f, 0f),       // Phẳng
                                                listOf(6f, 4f, 0f, -2f, -2f),     // Siêu trầm
                                                listOf(-2f, -1f, 4f, 3f, 1f),     // Sáng giọng
                                                listOf(-3f, -2f, 0f, 4f, 6f),     // Sắc nét
                                                listOf(5f, 3f, -2f, 4f, 5f)       // Nhạc sôi động
                                            )
                                            eqBands = presetBands[index]
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
                                        eqBands = eqBands.toMutableList().also { values -> values[i] = it }
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

            if (resultUri != null && resultFile?.extension?.lowercase() != "zip") {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { playAudio(resultUri, true) }, modifier = Modifier.weight(1f)) {
                        Text(if (isPlayingResult) "⏸ Tạm dừng file kết quả" else "▶ Nghe file kết quả", textAlign = TextAlign.Center)
                    }
                }
            }

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { if (!isProcessing) processFeature(true) }, modifier = Modifier.weight(1f)) {
                    Text("Nghe thử 10 giây", textAlign = TextAlign.Center)
                }
            }

            Button(
                onClick = { if (!isProcessing) processFeature(false) },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer)
            ) {
                Text(if (isProcessing) "ĐANG XỬ LÝ..." else "BẮT ĐẦU XỬ LÝ TOÀN BỘ", color = Color(0xFFFF0000), fontWeight = FontWeight.Bold)
            }

            resultFile?.let { file ->
                ResultFileActions(file = file)
            }
            OutlinedButton(onClick = {
                fileUriText = null
                fileName = "Chưa chọn"
                resultPath = null
                exoPlayer?.release()
                exoPlayer = null
                isPlayingBase = false
                isPlayingResult = false
                progress = 0f
                statusText = "Đã xóa file"
            }, modifier = Modifier.fillMaxWidth(), enabled = !isProcessing) {
                Text("Xóa file hiện tại")
            }
        }
    }
}
