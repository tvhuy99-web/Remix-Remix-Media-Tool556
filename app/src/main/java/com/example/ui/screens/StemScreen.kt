package com.example.ui.screens

import android.content.Intent
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.example.core.ml.AudioSeparator
import com.example.core.ml.DownloadState
import com.example.core.ml.ModelDownloader
import com.example.core.ml.SeparationState
import com.example.core.ml.StemService
import kotlinx.coroutines.launch
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StemScreen(
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    
    val downloader = remember { ModelDownloader(context) }
    val modelUrl = "https://huggingface.co/jackjiangxinfa/demucs-onnx/resolve/main/model.onnx"
    val modelName = "demucs_jackjiangxinfa.onnx"
    
    var downloadState by remember { mutableStateOf<DownloadState>(DownloadState.Idle) }
    var selectedAudioUri by remember { mutableStateOf<Uri?>(null) }
    
    val serviceIsProcessing by StemService.isProcessing.collectAsState()
    val serviceSeparationState by StemService.separationState.collectAsState()
    val serviceErrorMsg by StemService.errorMsg.collectAsState()
    
    var separationProgress by remember { mutableFloatStateOf(0f) }
    var resultVocalsUri by remember { mutableStateOf<Uri?>(null) }
    var resultMusicUri by remember { mutableStateOf<Uri?>(null) }
    var resultDrumsUri by remember { mutableStateOf<Uri?>(null) }
    var resultBassUri by remember { mutableStateOf<Uri?>(null) }
    var resultOtherUri by remember { mutableStateOf<Uri?>(null) }
    
    var resultVocalsFile by remember { mutableStateOf<File?>(null) }
    var resultMusicFile by remember { mutableStateOf<File?>(null) }
    var resultDrumsFile by remember { mutableStateOf<File?>(null) }
    var resultBassFile by remember { mutableStateOf<File?>(null) }
    var resultOtherFile by remember { mutableStateOf<File?>(null) }
    
    var fileToSave by remember { mutableStateOf<File?>(null) }

    val audioPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
        onResult = { uri ->
            if (uri != null) {
                selectedAudioUri = uri
                resultVocalsUri = null
                resultMusicUri = null
                resultDrumsUri = null
                resultBassUri = null
                resultOtherUri = null
                
                resultVocalsFile = null
                resultMusicFile = null
                resultDrumsFile = null
                resultBassFile = null
                resultOtherFile = null
                StemService.clearState()
            }
        }
    )

    val saveFileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("audio/mpeg"),
        onResult = { uri ->
            if (uri != null && fileToSave != null) {
                coroutineScope.launch {
                    try {
                        context.contentResolver.openOutputStream(uri)?.use { output ->
                            fileToSave!!.inputStream().use { input ->
                                input.copyTo(output)
                            }
                        }
                        Toast.makeText(context, "Đã lưu thành công", Toast.LENGTH_SHORT).show()
                    } catch (e: Exception) {
                        Toast.makeText(context, "Lỗi khi lưu: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                    fileToSave = null
                }
            }
        }
    )
    
    // Check if model already downloaded
    LaunchedEffect(Unit) {
        if (downloader.isModelDownloaded(modelName)) {
            downloadState = DownloadState.Success(java.io.File(context.filesDir, "models/$modelName"))
        }
    }
    
    // Listen to background processing state
    LaunchedEffect(serviceSeparationState, serviceErrorMsg) {
        when(val state = serviceSeparationState) {
            is SeparationState.Progress -> {
                separationProgress = state.value
            }
            is SeparationState.Success -> {
                resultVocalsFile = state.vocalsFile
                resultMusicFile = state.musicFile
                resultVocalsUri = FileProvider.getUriForFile(context, "${context.packageName}.provider", state.vocalsFile)
                resultMusicUri = FileProvider.getUriForFile(context, "${context.packageName}.provider", state.musicFile)
                
                if (state.drumsFile != null) {
                    resultDrumsFile = state.drumsFile
                    resultBassFile = state.bassFile
                    resultOtherFile = state.otherFile
                    resultDrumsUri = FileProvider.getUriForFile(context, "${context.packageName}.provider", state.drumsFile!!)
                    resultBassUri = FileProvider.getUriForFile(context, "${context.packageName}.provider", state.bassFile!!)
                    resultOtherUri = FileProvider.getUriForFile(context, "${context.packageName}.provider", state.otherFile!!)
                }
            }
            null -> {}
        }
    }

    fun shareAudio(uri: Uri, title: String) {
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "audio/*"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(shareIntent, title)
        context.startActivity(chooser)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Tách Nhạc và Lời (On-Device)") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(imageVector = Icons.Default.ArrowBack, contentDescription = "Trở về")
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            when (val state = downloadState) {
                is DownloadState.Idle -> {
                    Text(
                        "Tính năng này cần tải mô hình AI để xử lý ngoại tuyến (khoảng vài trăm MB).",
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(bottom = 24.dp)
                    )
                    Button(onClick = {
                        coroutineScope.launch {
                            downloader.downloadModel(modelUrl, modelName).collect { state ->
                                downloadState = state
                            }
                        }
                    }) {
                        Text("Tải Mô Hình AI")
                    }
                }
                is DownloadState.Downloading -> {
                    Text("Đang tải mô hình...")
                    Spacer(modifier = Modifier.height(16.dp))
                    if (state.progress >= 0f) {
                        LinearProgressIndicator(
                            progress = { state.progress },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text(
                            text = "${(state.progress * 100).toInt()}%",
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    } else {
                        CircularProgressIndicator()
                        Text("Đang kết nối...", modifier = Modifier.padding(top = 8.dp))
                    }
                }
                is DownloadState.Success -> {
                    if (serviceIsProcessing) {
                        Text("Đang dùng AI để tách nhạc trong nền (Có thể ẩn ứng dụng)")
                        Spacer(modifier = Modifier.height(16.dp))
                        LinearProgressIndicator(
                            progress = { separationProgress },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text("${(separationProgress * 100).toInt()}%", modifier = Modifier.padding(top = 8.dp))
                        
                        Spacer(modifier = Modifier.height(16.dp))
                        OutlinedButton(onClick = { 
                            val intent = Intent(context, StemService::class.java).apply {
                                action = StemService.ACTION_STOP
                            }
                            context.startService(intent)
                        }) {
                            Text("Dừng quá trình")
                        }
                    } else if (resultVocalsUri != null && resultMusicUri != null) {
                        Text(
                            "Tách thành công!",
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.titleLarge,
                            modifier = Modifier.padding(bottom = 16.dp)
                        )
                        AudioPreviewCard(
                            title = "Lời Hát (Vocals)",
                            audioFile = resultVocalsFile,
                            icon = Icons.Default.Person,
                            onShare = { shareAudio(resultVocalsUri!!, "Chia sẻ Lời (Vocals)") },
                            onSaveClick = { 
                                fileToSave = resultVocalsFile
                                saveFileLauncher.launch("Vocals_${System.currentTimeMillis()}.mp3") 
                            }
                        )
                        AudioPreviewCard(
                            title = if (resultDrumsFile != null) "Nhạc Cụ (Other)" else "Nhạc Nền (Beat)",
                            audioFile = resultMusicFile,
                            icon = Icons.Default.MusicNote,
                            onShare = { shareAudio(resultMusicUri!!, "Chia sẻ") },
                            onSaveClick = {
                                fileToSave = resultMusicFile
                                saveFileLauncher.launch(if (resultDrumsFile != null) "Other_${System.currentTimeMillis()}.mp3" else "Beat_${System.currentTimeMillis()}.mp3")
                            }
                        )
                        if (resultDrumsFile != null) {
                            AudioPreviewCard(
                                title = "Trống (Drums)",
                                audioFile = resultDrumsFile,
                                icon = Icons.Default.Audiotrack,
                                onShare = { shareAudio(resultDrumsUri!!, "Chia sẻ Trống (Drums)") },
                                onSaveClick = {
                                    fileToSave = resultDrumsFile
                                    saveFileLauncher.launch("Drums_${System.currentTimeMillis()}.mp3")
                                }
                            )
                            AudioPreviewCard(
                                title = "Bass",
                                audioFile = resultBassFile,
                                icon = Icons.Default.Audiotrack,
                                onShare = { shareAudio(resultBassUri!!, "Chia sẻ Bass") },
                                onSaveClick = {
                                    fileToSave = resultBassFile
                                    saveFileLauncher.launch("Bass_${System.currentTimeMillis()}.mp3")
                                }
                            )
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                        OutlinedButton(onClick = { 
                            resultVocalsUri = null
                            resultMusicUri = null
                            resultDrumsUri = null
                            resultBassUri = null
                            resultOtherUri = null
                            
                            resultVocalsFile = null
                            resultMusicFile = null
                            resultDrumsFile = null
                            resultBassFile = null
                            resultOtherFile = null
                            
                            StemService.clearState()
                            selectedAudioUri = null
                        }) {
                            Text("Tách video/bài hát khác")
                        }
                    } else {
                        Text(
                            "Mô hình AI đã được cài đặt.",
                            color = MaterialTheme.colorScheme.primary,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(bottom = 24.dp)
                        )
                        
                        Button(onClick = { audioPicker.launch("*/*") }) {
                            Text(if (selectedAudioUri == null) "Chọn File Âm Thanh/Video" else "Đã chọn file: ${selectedAudioUri?.lastPathSegment}")
                        }
                        
                        if (selectedAudioUri != null) {
                            Spacer(modifier = Modifier.height(16.dp))
                            Button(onClick = {
                                val intent = Intent(context, StemService::class.java).apply {
                                    action = StemService.ACTION_START
                                    putExtra(StemService.EXTRA_URI, selectedAudioUri.toString())
                                    putExtra(StemService.EXTRA_MODEL_FILE, state.file.absolutePath)
                                }
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                    context.startForegroundService(intent)
                                } else {
                                    context.startService(intent)
                                }
                            }) {
                                Text("Bắt Đầu Tách")
                            }
                        }

                        if (serviceErrorMsg != null) {
                            Text(
                                "Lỗi: $serviceErrorMsg",
                                color = MaterialTheme.colorScheme.error,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(top = 24.dp)
                            )
                        }
                        
                        Text(
                            "Lưu ý: Quá trình phân tích tốn khá nhiều RAM (khoảng 1.5GB - 2GB) và có thể diễn ra chậm tùy mức độ dài của bài hát.",
                            style = MaterialTheme.typography.bodySmall,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(top = 24.dp)
                        )
                    }
                }
                is DownloadState.Error -> {
                    Text(
                        "Lỗi: ${state.message}",
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(bottom = 24.dp)
                    )
                    Button(onClick = {
                        coroutineScope.launch {
                            downloader.downloadModel(modelUrl, modelName).collect { state ->
                                downloadState = state
                            }
                        }
                    }) {
                        Text("Thử Lại")
                    }
                }
            }
        }
    }
}

@Composable
fun AudioPreviewCard(
    title: String,
    audioFile: java.io.File?,
    icon: ImageVector,
    onShare: () -> Unit,
    onSaveClick: () -> Unit
) {
    var isPlaying by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    var mediaPlayer by remember { mutableStateOf<MediaPlayer?>(null) }
    var duration by remember { mutableIntStateOf(1) }

    DisposableEffect(audioFile) {
        var mp: MediaPlayer? = null
        if (audioFile != null && audioFile.exists()) {
            mp = MediaPlayer().apply {
                try {
                    setDataSource(audioFile.absolutePath)
                    prepare()
                    duration = this.duration
                    setOnCompletionListener { 
                        isPlaying = false
                        progress = 0f
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
            mediaPlayer = mp
        }
        onDispose {
            mp?.release()
            mediaPlayer = null
        }
    }

    LaunchedEffect(isPlaying) {
        while (isPlaying) {
            mediaPlayer?.let { mp ->
                if (duration > 0 && mp.isPlaying) {
                    progress = mp.currentPosition.toFloat() / duration.toFloat()
                }
            }
            kotlinx.coroutines.delay(100)
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(32.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.width(16.dp))
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = {
                    if (isPlaying) {
                        mediaPlayer?.pause()
                        isPlaying = false
                    } else {
                        mediaPlayer?.start()
                        isPlaying = true
                    }
                }, modifier = Modifier.semantics {
                    contentDescription = if (isPlaying) "Tạm dừng phát $title" else "Phát thử âm thanh $title"
                }) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = null
                    )
                }
                
                Slider(
                    value = progress,
                    onValueChange = { newValue ->
                        progress = newValue
                        mediaPlayer?.let { mp ->
                            mp.seekTo((newValue * duration).toInt())
                        }
                    },
                    modifier = Modifier.weight(1f).then(
                        if (isPlaying) {
                            Modifier.clearAndSetSemantics { contentDescription = "Tua âm thanh đoạn $title (Đang phát)" }
                        } else {
                            Modifier.semantics { contentDescription = "Tua âm thanh đoạn $title" }
                        }
                    )
                )
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onShare, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
                    Text("Chia sẻ")
                }
                FilledTonalButton(onClick = onSaveClick, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
                    Text("Lưu về máy")
                }
            }
        }
    }
}

