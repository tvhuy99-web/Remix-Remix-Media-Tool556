package com.example.ui.screens

import android.content.Context
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.common.MediaItem
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.example.core.media.MediaEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import com.example.ui.components.AccessibleCheckboxRow
import com.example.ui.components.AccessibleSliderColumn

data class PreviewTrack(
    val player: ExoPlayer,
    val startMs: Long,
    val endMs: Long?,
    val maxVolume: Float
)

data class BgAudioItem(
    val id: String = java.util.UUID.randomUUID().toString(),
    val uri: Uri,
    val name: String,
    val starts: String = "",
    val ends: String = "",
    val volume: Float = 0.3f,
    val pan: Int = 50, // 0=Trái, 50=Giữa, 100=Phải
    val pansStr: String = "" // Cho Pan Nâng cao (mỗi đoạn 1 giá trị)
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MixScreen(navController: NavController) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val mediaEngine = remember { MediaEngine(context) }
    
    var isMixModeVideo by remember { mutableStateOf(false) }
    
    // Base File
    var baseUri by remember { mutableStateOf<Uri?>(null) }
    var baseName by remember { mutableStateOf("Chưa chọn") }
    var baseStarts by remember { mutableStateOf("") }
    var baseEnds by remember { mutableStateOf("") }
    var muteBaseVideo by remember { mutableStateOf(false) }
    var baseVolume by remember { mutableStateOf(1.0f) }
    var basePan by remember { mutableStateOf(50) }
    
    // Background Audios
    val bgAudios = remember { mutableStateListOf<BgAudioItem>() }
    var loopBg by remember { mutableStateOf(false) }
    var autoDuck by remember { mutableStateOf(false) }
    
    var isProcessing by remember { mutableStateOf(false) }
    var isPreviewing by remember { mutableStateOf(false) }
    val previewTracks = remember { mutableStateListOf<PreviewTrack>() }
    var playingAudioIndex by remember { mutableStateOf(-1) }
    var singleAudioPlayer by remember { mutableStateOf<ExoPlayer?>(null) }
    var progressMsg by remember { mutableStateOf("Sẵn sàng") }
    var hasOutput by remember { mutableStateOf(false) }
    var outputPath by remember { mutableStateOf("") }
    
    var showLiveConsole by remember { mutableStateOf(false) }
    
    var advPanEnabled by remember { mutableStateOf(false) }
    var showAdvPanDialog by remember { mutableStateOf(false) }
    var basePansStr by remember { mutableStateOf("") }
    
    // Players for Preview
    var basePlayer by remember { mutableStateOf<ExoPlayer?>(null) }
    var isBasePlaying by remember { mutableStateOf(false) }
    
    if (showLiveConsole) {
        LiveConsoleDialog(
            context = context,
            baseUri = baseUri,
            baseName = baseName,
            bgAudios = bgAudios,
            muteBaseVideo = muteBaseVideo,
            baseVolume = baseVolume,
            basePan = basePan,
            onDismissRequest = { showLiveConsole = false },
            onApply = { bStart, bEnd, bgStarts, bgEnds ->
                baseStarts = bStart
                baseEnds = bEnd
                
                val updatedAudios = bgAudios.mapIndexed { index, audio ->
                    audio.copy(
                        starts = bgStarts.getOrNull(index) ?: "",
                        ends = bgEnds.getOrNull(index) ?: ""
                    )
                }
                bgAudios.clear()
                bgAudios.addAll(updatedAudios)
                
                showLiveConsole = false
                Toast.makeText(context, "Đã áp dụng mốc thời gian từ Trạm trộn DJ", Toast.LENGTH_SHORT).show()
            }
        )
    }

    // Clean up players on dispose
    DisposableEffect(Unit) {
        onDispose {
            basePlayer?.release()
            previewTracks.forEach { it.player.release() }
            singleAudioPlayer?.release()
        }
    }

    LaunchedEffect(isPreviewing) {
        if (isPreviewing) {
            while(isActive) {
                val currentMs = basePlayer?.currentPosition ?: 0L
                for (track in previewTracks) {
                    val inRange = currentMs >= track.startMs && (track.endMs == null || currentMs <= track.endMs)
                    if (inRange) {
                        val expectedPos = currentMs - track.startMs
                        val p = track.player
                        if (!p.isPlaying && p.playbackState != androidx.media3.common.Player.STATE_ENDED) {
                            if (kotlin.math.abs(p.currentPosition - expectedPos) > 300) {
                                p.seekTo(expectedPos)
                            }
                            p.volume = track.maxVolume
                            p.play()
                        } else if (p.isPlaying) {
                            if (kotlin.math.abs(p.currentPosition - expectedPos) > 1000) {
                                p.seekTo(expectedPos)
                            }
                        }
                    } else {
                        if (track.player.isPlaying) {
                            track.player.pause()
                            track.player.seekTo(0)
                        }
                    }
                }
                kotlinx.coroutines.delay(100)
            }
        }
    }

    val launcherBase = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            baseUri = uri
            baseName = getFileName(context, uri) ?: "base_file"
            
            // Release existing player so it reloads the new file
            basePlayer?.release()
            basePlayer = null
            isBasePlaying = false
        }
    }

    val launcherBg = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        uris.forEach { uri ->
            if (bgAudios.size < 5) {
                val name = getFileName(context, uri) ?: "audio_${bgAudios.size + 1}"
                bgAudios.add(BgAudioItem(uri = uri, name = name))
            } else {
                Toast.makeText(context, "Chỉ được chọn tối đa 5 nhạc nền", Toast.LENGTH_SHORT).show()
            }
        }
    }

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
    
    fun toggleBasePlayer() {
        if (baseUri == null) {
            Toast.makeText(context, "Chưa chọn file gốc", Toast.LENGTH_SHORT).show()
            return
        }
        if (isBasePlaying && basePlayer != null) {
            basePlayer?.pause()
            isBasePlaying = false
        } else {
            if (basePlayer == null) {
                basePlayer = ExoPlayer.Builder(context).build().apply {
                    setMediaItem(MediaItem.fromUri(baseUri!!))
                    prepare()
                    addListener(object : androidx.media3.common.Player.Listener {
                        override fun onPlaybackStateChanged(state: Int) {
                            if (state == androidx.media3.common.Player.STATE_ENDED) {
                                isBasePlaying = false
                            }
                        }
                    })
                }
            }
            val vol = if (muteBaseVideo) 0f else baseVolume
            basePlayer?.volume = vol
            
            // Try to parse starts as Long
            try {
                val startsSplit = baseStarts.split(",").filter { it.isNotBlank() }
                if (startsSplit.isNotEmpty()) {
                    val s = startsSplit[0].trim().toLongOrNull() ?: 0L
                    if (s >= 0) basePlayer?.seekTo(s)
                }
            } catch (e: Exception) {}
            
            basePlayer?.play()
            isBasePlaying = true
        }
    }

    fun togglePreviewMix() {
        if (baseUri == null || bgAudios.isEmpty()) {
            Toast.makeText(context, "Cần 1 File gốc và ít nhất 1 Nhạc nền", Toast.LENGTH_SHORT).show()
            return
        }

        if (isPreviewing) {
            basePlayer?.pause()
            previewTracks.forEach { it.player.release() }
            previewTracks.clear()
            isPreviewing = false
        } else {
            singleAudioPlayer?.release()
            singleAudioPlayer = null
            playingAudioIndex = -1

            val (baseSegs, baseErr) = parseSegmentsStrict(baseStarts, baseEnds)
            if (baseErr != null) { Toast.makeText(context, "Lỗi Video gốc: $baseErr", Toast.LENGTH_LONG).show(); return }

            for ((index, bg) in bgAudios.withIndex()) {
                val (_, bgErr) = parseSegmentsStrict(bg.starts, bg.ends)
                if (bgErr != null) { Toast.makeText(context, "Lỗi Nhạc ${index + 1}: $bgErr", Toast.LENGTH_LONG).show(); return }
            }

            if (basePlayer == null) {
                basePlayer = ExoPlayer.Builder(context).build().apply { 
                    setMediaItem(MediaItem.fromUri(baseUri!!))
                    prepare()
                }
            }
            if (isMixModeVideo && muteBaseVideo) {
                basePlayer?.volume = 0f
            } else {
                basePlayer?.volume = baseVolume
            }
            
            val bStartMs = baseSegs?.firstOrNull()?.first?.toLong() ?: 0L
            basePlayer?.seekTo(bStartMs)
            basePlayer?.play()
            isBasePlaying = true

            previewTracks.clear()
            val parseMsList = { s: String ->
                s.split(",").mapNotNull { it.trim().toLongOrNull() }.filter { it >= 0 }
            }
            bgAudios.forEach { bg ->
                val starts = parseMsList(bg.starts).ifEmpty { listOf(0L) }
                val ends = parseMsList(bg.ends)
                
                starts.forEachIndexed { i, startMs ->
                    val endMs = if (i < ends.size && ends[i] > startMs) ends[i] else null
                    val p = ExoPlayer.Builder(context).build().apply {
                        setMediaItem(MediaItem.fromUri(bg.uri))
                        volume = 0f
                        if (loopBg) repeatMode = androidx.media3.common.Player.REPEAT_MODE_ALL
                        prepare()
                    }
                    previewTracks.add(PreviewTrack(p, startMs, endMs, bg.volume))
                }
            }
            isPreviewing = true
        }
    }

    fun getCurrentPlayerMs(): Int {
        return try {
            basePlayer?.currentPosition?.toInt() ?: 0
        } catch (e: Exception) { 0 }
    }

    fun startProcessing() {
        try {
            if (baseUri == null || bgAudios.isEmpty()) {
                Toast.makeText(context, "Cần 1 File gốc và ít nhất 1 Nhạc nền", Toast.LENGTH_SHORT).show()
                return
            }

            val (_, baseErr) = parseSegmentsStrict(baseStarts, baseEnds)
            if (baseErr != null) { Toast.makeText(context, "Lỗi mốc Video gốc: $baseErr", Toast.LENGTH_LONG).show(); return }
            for ((index, bg) in bgAudios.withIndex()) {
                val (_, bgErr) = parseSegmentsStrict(bg.starts, bg.ends)
                if (bgErr != null) { Toast.makeText(context, "Lỗi mốc Nhạc ${index + 1}: $bgErr", Toast.LENGTH_LONG).show(); return }
            }
            
            isProcessing = true
            progressMsg = "Đang chuẩn bị ghép..."
            hasOutput = false
            
            coroutineScope.launch(Dispatchers.IO) {
                try {
                    val baseSaf = mediaEngine.getSafParameter(baseUri!!)
                    if (baseSaf == null) {
                        withContext(Dispatchers.Main) { Toast.makeText(context, "Lỗi đọc file gốc", Toast.LENGTH_SHORT).show(); isProcessing=false }
                        return@launch
                    }
                    
                    val ext = if (isMixModeVideo) "mp4" else com.example.core.SettingsManager.getAudioFormatExt(context)
                    val outputDir = File(context.cacheDir, "mix_outputs").apply { mkdirs() }
                    val outputFile = File(outputDir, "mixed_${System.currentTimeMillis()}.$ext")
                    
                    val amixDuration = if (isMixModeVideo || loopBg) "first" else "longest"
                    
                    // Build filter_complex string
                    val filter = java.lang.StringBuilder()
                    
                    // Build inputs without pre-trimming so we don't cut the original audio.
                    // The user specifically requested: "tuyệt đối không được cắt đoạn âm thanh nào đây là tính năng ghép âm thanh"
                    // So we use adelay to pad the stream with silence instead.
                    val inputArgs = java.lang.StringBuilder()
                    inputArgs.append("-i \"$baseSaf\" ")

                    var hasBaseAudio = true
                    var baseDurationMs = 0L
                    try {
                        val retriever = android.media.MediaMetadataRetriever()
                        retriever.setDataSource(context, baseUri!!)
                        val hasAudioStr = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO)
                        if (hasAudioStr == null || hasAudioStr == "no") {
                            hasBaseAudio = false
                        }
                        val durationStr = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
                        if (durationStr != null) {
                            baseDurationMs = durationStr.toLongOrNull() ?: 0L
                        }
                        retriever.release()
                    } catch (e: Exception) {
                    }
                    val baseDurationSec = if (baseDurationMs > 0) baseDurationMs / 1000.0 else 10.0

                    val bgDurationsMs = bgAudios.map { bg ->
                        try {
                            val r = android.media.MediaMetadataRetriever()
                            r.setDataSource(context, bg.uri)
                            val d = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                            r.release()
                            d
                        } catch (_: Exception) { 0L }
                    }

                    bgAudios.forEach { bg ->
                        val bgSaf = mediaEngine.getSafParameter(bg.uri)
                        if (bgSaf == null) {
                            withContext(Dispatchers.Main) { Toast.makeText(context, "Lỗi đọc nhạc nền: ${bg.name}", Toast.LENGTH_SHORT).show() }
                            isProcessing = false
                            return@launch
                        }
                        inputArgs.append("-i \"$bgSaf\" ")
                    }

                    val parseMsString = { s: String ->
                        s.split(",").mapNotNull { it.trim().toDoubleOrNull()?.div(1000.0) }.filter { it >= 0 }
                    }

                    var baseStartsList = parseMsString(baseStarts)
                    var baseEndsList = parseMsString(baseEnds)

                    if (baseStartsList.isEmpty() && baseEndsList.isEmpty()) {
                        baseStartsList = listOf(0.0)
                    } else if (baseStartsList.isEmpty() && baseEndsList.isNotEmpty()) {
                        baseStartsList = listOf(0.0)
                    }
                    val baseStartSec = baseStartsList.firstOrNull() ?: 0.0

                    val buildTrackPauseFilter = { inputIdx: Int, starts: List<Double>, ends: List<Double>, trackVol: Float, defaultPan: Int, pansStr: String, outName: String, trackDurationSec: Double ->
                        val localFilter = java.lang.StringBuilder()
                        val pansList = pansStr.split(",").mapNotNull { it.trim().toIntOrNull() }
                        val globalFadeSec = com.example.core.SettingsManager.getFadeDurationSec(context).toDouble()
                        val fi = globalFadeSec
                        val fo = globalFadeSec

                        if (starts.isEmpty()) {
                            localFilter.append("[$inputIdx:a]volume=0,aresample=48000,aformat=sample_fmts=fltp:channel_layouts=stereo[$outName]; ")
                        } else {
                            val n = starts.size
                            localFilter.append("[$inputIdx:a]aresample=48000,aformat=sample_fmts=fltp:channel_layouts=stereo[fmt_$inputIdx]; ")

                            if (loopBg) {
                                localFilter.append("[fmt_$inputIdx]aloop=loop=-1:size=2e9[loop_$inputIdx]; ")
                            }
                            val sourceLabel = if (loopBg) "[loop_$inputIdx]" else "[fmt_$inputIdx]"

                            if (n > 1) {
                                localFilter.append("${sourceLabel}asplit=$n")
                                for (j in 0 until n) localFilter.append("[split_${inputIdx}_$j]")
                                localFilter.append("; ")
                            } else {
                                localFilter.append("${sourceLabel}anull[split_${inputIdx}_0]; ")
                            }

                            val mixInputs = java.lang.StringBuilder()

                            for (j in 0 until n) {
                                val s = starts[j]
                                val e = if (j < ends.size && ends[j] > s) ends[j] else -1.0

                                val trimFilter = if (e > 0 && e > s) {
                                    val trimEnd = e - s
                                    "atrim=0:$trimEnd,"
                                } else ""

                                val panVal = if (advPanEnabled && j < pansList.size) pansList[j] else defaultPan
                                val Lvol = if (panVal <= 50) 1f else (100 - panVal) / 50f
                                val Rvol = if (panVal >= 50) 1f else panVal / 50f
                                val panFilter = "pan=stereo|c0=${Lvol}*c0|c1=${Rvol}*c1"

                                val durationToFade = if (e > 0) e - s else trackDurationSec
                                val fadeIn = if (fi > 0) "afade=t=in:st=0:d=$fi," else ""
                                val fadeOut = if (fo > 0 && durationToFade > fo) "afade=t=out:st=${durationToFade - fo}:d=$fo," else ""

                                val delayMs = (s * 1000).toLong()
                                val delayStr = if (delayMs > 0) "adelay=${delayMs}|${delayMs}" else "anull"

                                val segFilter = "${trimFilter}asetpts=PTS-STARTPTS,volume=$trackVol,${panFilter},${fadeIn}${fadeOut}${delayStr}"
                                localFilter.append("[split_${inputIdx}_$j]${segFilter}[seg_${inputIdx}_$j]; ")
                                mixInputs.append("[seg_${inputIdx}_$j]")
                            }

                            if (n > 1) {
                                localFilter.append("${mixInputs}amix=inputs=$n:duration=longest:dropout_transition=0:normalize=0[$outName]; ")
                            } else {
                                localFilter.append("[seg_${inputIdx}_0]anull[$outName]; ")
                            }
                        }
                        localFilter.toString()
                    }

                    val numInputs = 1 + bgAudios.size
                    if (!hasBaseAudio) {
                        filter.append("anullsrc=r=48000:cl=stereo:d=$baseDurationSec,aformat=sample_fmts=fltp:channel_layouts=stereo[a0]; ")
                    } else if (muteBaseVideo) {
                        filter.append("[0:a]volume=0,aresample=48000,aformat=sample_fmts=fltp:channel_layouts=stereo[a0]; ")
                    } else {
                        filter.append(buildTrackPauseFilter(0, baseStartsList, baseEndsList, baseVolume, basePan, basePansStr, "a0", baseDurationSec))
                    }
                    
                    bgAudios.forEachIndexed { i, bg ->
                        var bgStartsListAll = parseMsString(bg.starts)
                        var bgEndsListAll = parseMsString(bg.ends)
                        
                        if (bgStartsListAll.isEmpty() && bgEndsListAll.isEmpty()) {
                            bgStartsListAll = listOf(0.0)
                        } else if (bgStartsListAll.isEmpty() && bgEndsListAll.isNotEmpty()) {
                            bgStartsListAll = listOf(0.0)
                        }
                        
                        val actualStarts = bgStartsListAll
                        
                        filter.append(buildTrackPauseFilter(i + 1, actualStarts, bgEndsListAll, bg.volume, bg.pan, bg.pansStr, "a${i+1}", bgDurationsMs[i] / 1000.0))
                    }
                    
                    if (autoDuck && !muteBaseVideo && hasBaseAudio && bgAudios.isNotEmpty()) {
                        // Ducking (Sidechain compression)
                        for (i in 1..bgAudios.size) {
                            filter.append("[a$i]")
                        }
                        if (bgAudios.size > 1) {
                            // Let the background mix be 'longest' so it doesn't get cut off prematurely before ducking
                            filter.append("amix=inputs=${bgAudios.size}:duration=longest:dropout_transition=2:normalize=0[bg_mixed]; ")
                        } else {
                            filter.append("volume=1[bg_mixed]; ")
                        }
                        
                        // Tách âm thanh gốc thành 2 đường: 1 để ghép đầu ra, 1 để làm sidechain điều khiển
                        filter.append("[a0]asplit=2[base_out][base_sc]; ")
                        
                        // Thêm apad cho sidechain để không làm cắt cụt nhạc nền (tránh lỗi bug FFmpeg truncates amix based on sidechaincompress)
                        filter.append("[base_sc]apad[base_sc_padded]; ")
                        
                        // Áp dụng hiệu ứng sidechaincompress (Bóp âm lượng bg_mixed dựa trên base_sc)
                        filter.append("[bg_mixed][base_sc_padded]sidechaincompress=threshold=0.08:ratio=5.0:attack=100:release=1000[bg_ducked]; ")
                        
                        // Ghép đường âm thanh gốc (base_out) với nhạc nền đã được tự động ducking (bg_ducked)
                        // final duration=first ensures the output follows the length of base_out (video)
                        filter.append("[base_out][bg_ducked]amix=inputs=2:duration=${amixDuration}:dropout_transition=2:normalize=0[mixed_uncapped]; ")
                    } else {
                        if (numInputs > 1) {
                            for (i in 0 until numInputs) {
                                filter.append("[a$i]")
                            }
                            // normalize=0 ngăn chặn việc FFmpeg tự động giảm âm lượng tổng thể xuống (vd 3 input thì chia 3) gây nhỏ tiếng nghiêm trọng
                            filter.append("amix=inputs=$numInputs:duration=${amixDuration}:dropout_transition=2:normalize=0[mixed_uncapped]; ")
                        } else {
                            filter.append("[a0]volume=1.0[mixed_uncapped]; ")
                        }
                    }
                    // Thêm Limiter ở bước cuối cùng để chống xé tiếng (clipping) nếu tổng âm lượng vượt quá 0dB
                    filter.append("[mixed_uncapped]alimiter=limit=-0.1dB:level_in=1:level_out=1[outa]")
                    
                    val acodec = if (isMixModeVideo) "-c:a aac" else com.example.core.SettingsManager.getAudioCodecArg(context)
                    val abitrateArg = com.example.core.SettingsManager.getAudioBitrateArg(context)
                    val abitrate = if (isMixModeVideo || !(acodec.contains("flac") || acodec.contains("pcm"))) abitrateArg else ""
                    
                    val vcodec = if (isMixModeVideo) "-c:v copy" else ""
                    val maps = if (isMixModeVideo) "-map 0:v? -map \"[outa]\"" else "-map \"[outa]\""
                    
                    val command = "-y $inputArgs -filter_complex \"$filter\" $maps $vcodec $acodec $abitrate \"${outputFile.absolutePath}\""
                    
                    android.util.Log.e("MixScreen", "Executing Mix Command: $command")
                    
                    mediaEngine.executeFFmpegCommand(command).collect { state ->
                        withContext(Dispatchers.Main) {
                            when (state) {
                                is MediaEngine.ExecutionState.Connecting -> progressMsg = "Khởi tạo..."
                                is MediaEngine.ExecutionState.Progress -> progressMsg = "Đang xử lý: ${state.timeInMilliseconds}ms"
                                is MediaEngine.ExecutionState.Success -> {
                                    progressMsg = "Ghép thành công!"
                                    isProcessing = false
                                    hasOutput = true
                                    outputPath = outputFile.absolutePath
                                }
                                is MediaEngine.ExecutionState.Error -> {
                                    progressMsg = "Lỗi FFmpeg."
                                    isProcessing = false
                                    Toast.makeText(context, "Ghép thất bại!", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    }
                } catch(e: Throwable) {
                    withContext(Dispatchers.Main) {
                        progressMsg = "Ngoại lệ: ${e.message}"
                        isProcessing = false
                        Toast.makeText(context, "Lỗi Coroutine: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
        } catch(e: Throwable) {
            Toast.makeText(context, "Lỗi khi bắt đầu: ${e.message}", Toast.LENGTH_LONG).show()
            isProcessing = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("GHÉP NHẠC ĐA LUỒNG", fontSize = 18.sp, fontWeight = FontWeight.Bold) },
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
            Button(
                onClick = { isMixModeVideo = !isMixModeVideo },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                )
            ) {
                Text(
                    text = if (isMixModeVideo) "Chế độ Video (Chạm để đổi sang Audio)" else "Chế độ Audio (Chạm để đổi sang Video)",
                    textAlign = TextAlign.Center
                )
            }

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { showLiveConsole = true },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.primary)
                ) {
                    Text(text = "LIVE MIXER", fontWeight = FontWeight.Bold)
                }
                OutlinedButton(
                    onClick = { showAdvPanDialog = true },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.secondary)
                ) {
                    Text(text = "PAN NÂNG CAO", fontWeight = FontWeight.Bold)
                }
            }

            OutlinedButton(
                onClick = { navController.navigate(com.example.navigation.Route.Trim.path) },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                    contentColor = MaterialTheme.colorScheme.onTertiaryContainer
                )
            ) {
                Text("✂️ Công cụ Cắt (Trim) nhạc", textAlign = TextAlign.Center)
            }

            // [1] BASE FILE
            Text(if (isMixModeVideo) "1. VIDEO GỐC" else "1. ÂM THANH GỐC", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
            
            Button(onClick = { 
                if (isMixModeVideo) launcherBase.launch("video/*") else launcherBase.launch("audio/*") 
            }, modifier = Modifier.fillMaxWidth()) {
                Text(if (isMixModeVideo) "Chọn Video gốc" else "Chọn Âm thanh gốc")
            }
            
            Text("Tệp: $baseName", fontWeight = FontWeight.Bold)
            
            OutlinedTextField(
                value = baseStarts,
                onValueChange = { baseStarts = it },
                label = { Text("Mốc bắt đầu (ms)") },
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text)
            )
            Button(onClick = { 
                baseStarts = getCurrentPlayerMs().toString()
            }, modifier = Modifier.fillMaxWidth()) {
                Text("Lấy mốc bắt đầu hiện tại")
            }
            
            OutlinedTextField(
                value = baseEnds,
                onValueChange = { baseEnds = it },
                label = { Text("Mốc kết thúc (ms)") },
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text)
            )
            Button(onClick = { 
                baseEnds = getCurrentPlayerMs().toString()
            }, modifier = Modifier.fillMaxWidth()) {
                Text("Lấy mốc kết thúc hiện tại")
            }
            
            if (isMixModeVideo) {
                AccessibleCheckboxRow(
                    checked = muteBaseVideo,
                    onCheckedChange = { muteBaseVideo = it },
                    text = "Tắt âm thanh gốc của Video"
                )
            }

            AccessibleSliderColumn(
                label = "Âm lượng: ${(baseVolume * 100).toInt()}%",
                contentDesc = "Âm lượng video/nhạc gốc",
                value = baseVolume,
                onValueChange = { baseVolume = it; if (basePlayer != null) basePlayer?.volume = it },
                valueRange = 0f..1.5f
            )
            
            AccessibleSliderColumn(
                label = "Cân bằng kênh (Pan L/R): $basePan",
                contentDesc = "Cân bằng kênh trái phải gốc",
                value = basePan.toFloat(),
                onValueChange = { basePan = it.toInt() },
                valueRange = 0f..100f
            )

            Button(onClick = { toggleBasePlayer() }, modifier = Modifier.fillMaxWidth()) {
                Text(if (isBasePlaying) "Tạm dừng File Gốc" else "Phát File Gốc")
            }
            
            Divider(modifier = Modifier.padding(vertical = 8.dp))

            // [2] BG AUDIOS
            Text("2. DANH SÁCH NHẠC NỀN", color = Color(0xFF00AA00), fontWeight = FontWeight.Bold)
            
            Button(onClick = { launcherBg.launch("audio/*") }, modifier = Modifier.fillMaxWidth()) {
                Text("Thêm Nhạc nền (Được chọn nhiều)")
            }
            
            if (bgAudios.isEmpty()) {
                Text("Chưa có bản nhạc nền nào.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            
            bgAudios.forEachIndexed { index, audio ->
                Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Nhạc ${index + 1}: ${audio.name}", color = Color(0xFF00AA00), modifier = Modifier.weight(1f), fontWeight = FontWeight.Bold)
                            IconButton(onClick = {
                                if (playingAudioIndex == index) {
                                    singleAudioPlayer?.release()
                                    singleAudioPlayer = null
                                    playingAudioIndex = -1
                                } else if (playingAudioIndex > index) {
                                    playingAudioIndex -= 1
                                }
                                bgAudios.removeAt(index)
                            }) {
                                Icon(Icons.Default.Delete, contentDescription = "Xóa", tint = MaterialTheme.colorScheme.error)
                            }
                        }
                        
                        OutlinedTextField(
                            value = audio.starts,
                            onValueChange = { newValue -> bgAudios[index] = audio.copy(starts = newValue) },
                            label = { Text("Mốc bắt đầu (ms)") },
                            modifier = Modifier.fillMaxWidth(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text)
                        )
                        OutlinedTextField(
                            value = audio.ends,
                            onValueChange = { newValue -> bgAudios[index] = audio.copy(ends = newValue) },
                            label = { Text("Mốc kết thúc (ms)") },
                            modifier = Modifier.fillMaxWidth(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text)
                        )
                        
                        AccessibleSliderColumn(
                            label = "Âm lượng: ${(audio.volume * 100).toInt()}%",
                            contentDesc = "Âm lượng nhạc nền ${index + 1}",
                            value = audio.volume,
                            onValueChange = { bgAudios[index] = audio.copy(volume = it) },
                            valueRange = 0f..1.5f
                        )
                        
                        AccessibleSliderColumn(
                            label = "Cân bằng kênh: ${audio.pan}",
                            contentDesc = "Cân bằng kênh nhạc nền ${index + 1}",
                            value = audio.pan.toFloat(),
                            onValueChange = { bgAudios[index] = audio.copy(pan = it.toInt()) },
                            valueRange = 0f..100f
                        )
                        Button(
                            onClick = {
                                if (playingAudioIndex == index) {
                                    singleAudioPlayer?.pause()
                                    singleAudioPlayer?.release()
                                    singleAudioPlayer = null
                                    playingAudioIndex = -1
                                } else {
                                    singleAudioPlayer?.release()
                                    val player = ExoPlayer.Builder(context).build().apply {
                                        setMediaItem(MediaItem.fromUri(audio.uri))
                                        volume = audio.volume
                                        prepare()
                                        playWhenReady = true
                                        addListener(object : androidx.media3.common.Player.Listener {
                                            override fun onPlaybackStateChanged(state: Int) {
                                                if (state == androidx.media3.common.Player.STATE_ENDED) {
                                                    playingAudioIndex = -1
                                                }
                                            }
                                        })
                                    }
                                    singleAudioPlayer = player
                                    playingAudioIndex = index
                                }
                            },
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                        ) {
                            Text(if (playingAudioIndex == index) "Dừng bài này" else "Nghe thử bài này")
                        }
                    }
                }
            }

            Divider(modifier = Modifier.padding(vertical = 8.dp))

            AccessibleCheckboxRow(
                checked = loopBg,
                onCheckedChange = { loopBg = it },
                text = "Lặp lại nhạc nền"
            )
            AccessibleCheckboxRow(
                checked = autoDuck,
                onCheckedChange = { autoDuck = it },
                text = "Auto-Ducking (Tự động nhỏ nhạc nền khi có tiếng)"
            )

            Text(text = progressMsg, modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite }, textAlign = TextAlign.Center, fontWeight = FontWeight.Bold)
            if (isProcessing) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            Button(
                onClick = { togglePreviewMix() },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.tertiary)
            ) {
                Text(if (isPreviewing) "⏹ DỪNG NGHE THỬ SƠ BỘ" else "▶ NGHE THỬ SƠ BỘ (Tất cả)")
            }

            Button(
                onClick = { startProcessing() },
                enabled = !isProcessing,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer)
            ) {
                Text("BẮT ĐẦU GHÉP NHẠC", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            }
            
            if (hasOutput) {
                Card(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Button(onClick = { 
                            val ext = if (isMixModeVideo) "mp4" else outputPath.substringAfterLast(".", "m4a")
                            saveLauncher.launch("mixed_result_${System.currentTimeMillis()}.$ext")
                        }, modifier = Modifier.fillMaxWidth()) {
                            Text("Lưu File đã ghép vào thiết bị")
                        }
                        
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(if (isMixModeVideo) "▶ Xem video hoặc Nghe file kết quả:" else "▶ Nghe file kết quả:", fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 8.dp))
                        com.example.ui.components.VideoPlayer(uri = Uri.fromFile(File(outputPath)))
                    }
                }
            }
            
            OutlinedButton(onClick = { 
                baseUri = null; baseName = "Chưa chọn"; bgAudios.clear(); hasOutput = false
                basePlayer?.release()
                basePlayer = null
                isBasePlaying = false
            }, modifier = Modifier.fillMaxWidth()) {
                Text("Xóa cấu hình")
            }
            
            TextButton(onClick = { navController.popBackStack() }, modifier = Modifier.fillMaxWidth()) {
                Text("Quay lại")
            }
            
            if (showAdvPanDialog) {
                AdvancedPanDialog(
                    bgAudios = bgAudios,
                    basePansStr = basePansStr,
                    onDismissRequest = { showAdvPanDialog = false },
                    onApply = { enabled, basePans, updatedBgs ->
                        advPanEnabled = enabled
                        basePansStr = basePans
                        bgAudios.clear()
                        bgAudios.addAll(updatedBgs)
                        showAdvPanDialog = false
                    },
                    advPanEnabled = advPanEnabled
                )
            }
        }
    }
}

@Composable
fun AdvancedPanDialog(
    bgAudios: List<BgAudioItem>,
    basePansStr: String,
    onDismissRequest: () -> Unit,
    onApply: (advPanEnabled: Boolean, basePans: String, updateBgs: List<BgAudioItem>) -> Unit,
    advPanEnabled: Boolean
) {
    var enabled by remember { mutableStateOf(advPanEnabled) }
    var localBasePans by remember { mutableStateOf(basePansStr) }
    val localBgPans = remember { mutableStateListOf<String>().apply { addAll(bgAudios.map { it.pansStr }) } }

    AlertDialog(
        onDismissRequest = onDismissRequest,
        confirmButton = {
            Button(onClick = {
                val updatedBgs = bgAudios.mapIndexed { i, bg -> bg.copy(pansStr = localBgPans[i]) }
                onApply(enabled, localBasePans, updatedBgs)
            }) {
                Text("LƯU & THOÁT")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) { Text("Hủy") }
        },
        title = { Text("Pan (Trái/Phải) Nâng Cao") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                AccessibleCheckboxRow(
                    checked = enabled,
                    onCheckedChange = { enabled = it },
                    text = "Bật cân bằng kênh tĩnh cho từng đoạn"
                )
                
                Text("ĐOẠN GỐC", color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
                OutlinedTextField(
                    value = localBasePans,
                    onValueChange = { localBasePans = it },
                    label = { Text("Cân bằng kênh đoạn gốc (các giá trị cách nhau bằng dấu phẩy)") },
                    modifier = Modifier.fillMaxWidth()
                )
                
                bgAudios.forEachIndexed { index, audio ->
                    Text("NHẠC NỀN ${index + 1}", color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
                    OutlinedTextField(
                        value = localBgPans[index],
                        onValueChange = { localBgPans[index] = it },
                        label = { Text("Cân bằng kênh nhạc nền ${index+1}") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LiveConsoleDialog(
    context: Context,
    baseUri: Uri?,
    baseName: String,
    bgAudios: List<BgAudioItem>,
    muteBaseVideo: Boolean,
    baseVolume: Float,
    basePan: Int,
    onDismissRequest: () -> Unit,
    onApply: (baseStart: String, baseEnd: String, bgStarts: List<String>, bgEnds: List<String>) -> Unit
) {
    var isRecording by remember { mutableStateOf(false) }
    var basePlaying by remember { mutableStateOf(false) }
    val bgPlaying = remember { mutableStateListOf<Boolean>().apply { 
        repeat(bgAudios.size) { add(false) } 
    } }
    
    var startTime by remember { mutableStateOf(0L) }
    var currentTime by remember { mutableStateOf(0L) }
    
    val baseStartsList = remember { mutableStateListOf<Long>() }
    val baseEndsList = remember { mutableStateListOf<Long>() }
    val bgStartsList = remember { mutableStateListOf<MutableList<Long>>().apply { 
        repeat(bgAudios.size) { add(mutableListOf()) } 
    } }
    val bgEndsList = remember { mutableStateListOf<MutableList<Long>>().apply { 
        repeat(bgAudios.size) { add(mutableListOf()) } 
    } }
    
    val basePlayerRef = remember { mutableStateOf<ExoPlayer?>(null) }
    val bgPlayersRef = remember { mutableStateListOf<ExoPlayer>() }
    
    LaunchedEffect(isRecording) {
        while(isRecording) {
            currentTime = System.currentTimeMillis() - startTime
            delay(50)
        }
    }
    
    DisposableEffect(Unit) {
        onDispose {
            basePlayerRef.value?.release()
            bgPlayersRef.forEach { it.release() }
        }
    }
    
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismissRequest,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFF1A1A1D)) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                    Text("TRẠM TRỘN DJ (DJ MIXER)", fontSize = 20.sp, color = Color(0xFFFF8800), fontWeight = FontWeight.Bold)
                    
                    val minutes = (currentTime / 1000) / 60
                    val seconds = (currentTime / 1000) % 60
                    val millis = currentTime % 1000
                    Text(
                        text = String.format("%02d:%02d.%03d", minutes, seconds, millis),
                        fontSize = 36.sp,
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                    )
                }
                
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    MixerChannelStrip(
                        name = "Tệp gốc: $baseName",
                        isPlaying = basePlaying,
                        onTogglePlay = {
                            if (isRecording) {
                                if (basePlaying) {
                                    basePlayerRef.value?.pause()
                                    baseEndsList.add(currentTime)
                                    basePlaying = false
                                } else {
                                    basePlayerRef.value?.play()
                                    baseStartsList.add(currentTime)
                                    basePlaying = true
                                }
                            } else {
                                Toast.makeText(context, "Hãy bấm BẮT ĐẦU GHI ở dưới trước!", Toast.LENGTH_SHORT).show()
                            }
                        },
                        isRecording = isRecording,
                        isMuted = muteBaseVideo
                    )
                    
                    bgAudios.forEachIndexed { i, bg ->
                        MixerChannelStrip(
                            name = "Nhạc nền ${i+1}: ${bg.name}",
                            isPlaying = bgPlaying[i],
                            onTogglePlay = {
                                if (isRecording) {
                                    if (bgPlaying[i]) {
                                        bgPlayersRef[i].pause()
                                        bgEndsList[i].add(currentTime)
                                        bgPlaying[i] = false
                                    } else {
                                        bgPlayersRef[i].play()
                                        bgStartsList[i].add(currentTime)
                                        bgPlaying[i] = true
                                    }
                                } else {
                                    Toast.makeText(context, "Hãy bấm BẮT ĐẦU GHI ở dưới trước!", Toast.LENGTH_SHORT).show()
                                }
                            },
                            isRecording = isRecording,
                            isMuted = false
                        )
                    }
                }
                
                Column(modifier = Modifier.fillMaxWidth().padding(top = 16.dp)) {
                    Button(
                        onClick = {
                            if (!isRecording) {
                                if (baseUri == null) {
                                    Toast.makeText(context, "Chưa chọn file gốc", Toast.LENGTH_SHORT).show()
                                    return@Button
                                }
                                
                                basePlayerRef.value?.release()
                                bgPlayersRef.forEach { it.release() }
                                bgPlayersRef.clear()
                                
                                val bPlayer = ExoPlayer.Builder(context).build().apply {
                                    setMediaItem(MediaItem.fromUri(baseUri))
                                    volume = if (muteBaseVideo) 0f else baseVolume
                                    prepare()
                                    playWhenReady = true
                                }
                                basePlayerRef.value = bPlayer
                                
                                bgAudios.forEachIndexed { index, aud ->
                                    val bgP = ExoPlayer.Builder(context).build().apply {
                                        setMediaItem(MediaItem.fromUri(aud.uri))
                                        volume = aud.volume
                                        prepare()
                                        playWhenReady = false
                                    }
                                    bgPlayersRef.add(bgP)
                                }
                                
                                baseStartsList.clear()
                                baseEndsList.clear()
                                bgStartsList.clear()
                                bgEndsList.clear()
                                repeat(bgAudios.size) { 
                                    bgStartsList.add(mutableListOf())
                                    bgEndsList.add(mutableListOf())
                                }
                                
                                basePlaying = true
                                baseStartsList.add(0L)
                                for (i in bgPlaying.indices) bgPlaying[i] = false
                                
                                startTime = System.currentTimeMillis()
                                isRecording = true
                            }
                        },
                        modifier = Modifier.fillMaxWidth().height(56.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    ) {
                        Text(if (isRecording) "ĐANG GHI..." else "BẮT ĐẦU GHI (REC)", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    }
                    
                    Button(
                        onClick = {
                            val bStart = baseStartsList.joinToString(", ")
                            val bEnd = baseEndsList.joinToString(", ")
                            val bgS = bgStartsList.map { it.joinToString(", ") }
                            val bgE = bgEndsList.map { it.joinToString(", ") }
                            
                            if (isRecording) {
                                if (basePlaying) {
                                    baseEndsList.add(currentTime)
                                }
                                bgPlaying.forEachIndexed { i, playing ->
                                    if (playing) bgEndsList[i].add(currentTime)
                                }
                                isRecording = false
                                basePlayerRef.value?.release()
                                basePlayerRef.value = null
                                bgPlayersRef.forEach { it.release() }
                                bgPlayersRef.clear()
                                
                                val finalBEnd = baseEndsList.joinToString(", ")
                                val finalBgE = bgEndsList.map { it.joinToString(", ") }
                                
                                onApply(bStart, finalBEnd, bgS, finalBgE)
                            } else {
                                onApply(bStart, bEnd, bgS, bgE)
                            }
                        },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                    ) {
                        Text("KẾT THÚC & ÁP DỤNG", fontWeight = FontWeight.Bold)
                    }
                    
                    OutlinedButton(
                        onClick = onDismissRequest,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
                    ) {
                        Text("HỦY & ĐÓNG")
                    }
                }
            }
        }
    }
}

@Composable
fun MixerChannelStrip(
    name: String,
    isPlaying: Boolean,
    onTogglePlay: () -> Unit,
    isRecording: Boolean,
    isMuted: Boolean
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF2A2A2E))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    name, 
                    fontWeight = FontWeight.Bold, 
                    color = Color.White,
                    modifier = Modifier.weight(1f)
                )
                Button(
                    onClick = onTogglePlay,
                    enabled = isRecording,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isPlaying) Color(0xFF00C853) else Color.DarkGray
                    ),
                    modifier = Modifier.semantics {
                        contentDescription = "Nút phát kênh $name"
                        stateDescription = if (isPlaying) "Đang phát" else "Đang dừng"
                    }
                ) {
                    Text(if (isPlaying) "TẠM DỪNG" else "PHÁT CUE", color = Color.White, fontWeight = FontWeight.Bold)
                }
            }
            
            if (isMuted) {
                Text("Kênh này đang bị khóa âm lượng do tắt tiếng ở màn hình ngoài.", color = MaterialTheme.colorScheme.error, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
            }
        }
    }
}

// Hàm kiểm tra tính hợp lệ của đa mốc thời gian
fun parseSegmentsStrict(startsStr: String, endsStr: String): Pair<List<Pair<Double, Double>>?, String?> {
    val sStr = startsStr.replace("\\s+".toRegex(), "")
    val eStr = endsStr.replace("\\s+".toRegex(), "")

    if (sStr.isEmpty() && eStr.isEmpty()) return Pair(listOf(Pair(0.0, -1.0)), null)

    val starts = if (sStr.isNotEmpty()) sStr.split(",").mapNotNull { it.toDoubleOrNull()?.let { v -> if (v < 0) 0.0 else v } } else listOf(0.0)
    val ends = if (eStr.isNotEmpty()) eStr.split(",").mapNotNull { it.toDoubleOrNull()?.let { v -> if (v <= 0) -1.0 else v } } else listOf(-1.0)

    if (starts.size != ends.size) return Pair(null, "Số mốc bắt đầu (${starts.size}) và kết thúc (${ends.size}) không bằng nhau!")

    val segments = mutableListOf<Pair<Double, Double>>()
    for (i in starts.indices) {
        val s = starts[i]
        val e = ends[i]
        if (e != -1.0 && s >= e) return Pair(null, "Đoạn ${i + 1}: Bắt đầu ($s) phải nhỏ hơn Kết thúc ($e)!")
        segments.add(Pair(s, e))
    }
    return Pair(segments, null)
}

// Utility function to get file name
fun getFileName(context: Context, uri: Uri): String? {
    var result: String? = null
    if (uri.scheme == "content") {
        val cursor = context.contentResolver.query(uri, null, null, null, null)
        try {
            if (cursor != null && cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (index >= 0) {
                    result = cursor.getString(index)
                }
            }
        } finally {
            cursor?.close()
        }
    }
    if (result == null) {
        result = uri.path
        val cut = result?.lastIndexOf('/')
        if (cut != null && cut != -1) {
            result = result?.substring(cut + 1)
        }
    }
    return result
}
