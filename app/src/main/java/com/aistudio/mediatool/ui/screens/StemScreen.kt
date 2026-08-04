package com.aistudio.mediatool.ui.screens

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.aistudio.mediatool.core.DocumentUtils
import com.aistudio.mediatool.core.GetContentWithMimeTypes
import com.aistudio.mediatool.core.SettingsManager
import com.aistudio.mediatool.core.diagnostics.DiagnosticLogger
import com.aistudio.mediatool.core.diagnostics.DiagnosticRedactor
import com.aistudio.mediatool.core.ml.DownloadState
import com.aistudio.mediatool.core.ml.SeparationState
import com.aistudio.mediatool.core.ml.StemMode
import com.aistudio.mediatool.core.ml.StemModelDescriptor
import com.aistudio.mediatool.core.ml.StemModelRegistry
import com.aistudio.mediatool.core.ml.StemService
import com.aistudio.mediatool.ui.components.AudioPreviewSource
import com.aistudio.mediatool.ui.components.CompactDropdown
import com.aistudio.mediatool.ui.components.DiagnosticReportCard
import com.aistudio.mediatool.ui.components.MediaInputCard
import com.aistudio.mediatool.ui.components.ResultFileActions
import com.aistudio.mediatool.ui.components.StickyProcessBar
import com.aistudio.mediatool.ui.components.ToolScaffold
import com.aistudio.mediatool.ui.components.ToolSectionCard
import com.aistudio.mediatool.ui.components.UnifiedAudioPlayer
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

private data class PendingStemStart(
    val uriText: String,
    val modelPath: String,
    val modelId: String,
)

private data class StemMixerItem(
    val id: String,
    val label: String,
    val file: File,
)

@Composable
fun StemScreen(onNavigateBack: () -> Unit) {
    val context = LocalContext.current
    val stemViewModel: StemViewModel = viewModel()
    val downloadState by stemViewModel.downloadState.collectAsStateWithLifecycle()
    val selectedModel by stemViewModel.selectedModel.collectAsStateWithLifecycle()
    val serviceIsProcessing by StemService.isProcessing.collectAsStateWithLifecycle()
    val serviceState by StemService.separationState.collectAsStateWithLifecycle()
    val serviceError by StemService.errorMsg.collectAsStateWithLifecycle()

    var selectedAudioUriText by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedAudioName by rememberSaveable { mutableStateOf<String?>(null) }
    var modeIndex by rememberSaveable { mutableStateOf(SettingsManager.getStemModeIndex(context)) }
    var formatIndex by rememberSaveable { mutableStateOf(SettingsManager.getAudFormatIndex(context)) }
    var bitrateIndex by rememberSaveable { mutableStateOf(SettingsManager.getAudBitrateIndex(context)) }
    var threadsIndex by rememberSaveable { mutableStateOf(SettingsManager.getNumThreadsIndex(context)) }
    var advancedExpanded by rememberSaveable { mutableStateOf(false) }
    var separationProgress by remember { mutableFloatStateOf(0f) }
    var result by remember { mutableStateOf<SeparationState.Success?>(null) }

    val selectedAudioUri = selectedAudioUriText?.let(Uri::parse)
    val selectedMode = StemMode.fromSettingsIndex(modeIndex)
    val modeModels = StemModelRegistry.modelsFor(selectedMode)
    val downloadedModel = (downloadState as? DownloadState.Success)?.file

    LaunchedEffect(Unit) {
        stemViewModel.refreshConfiguredModel()
        StemService.restorePersistedState(context)
    }

    LaunchedEffect(selectedModel.mode.settingsIndex) {
        modeIndex = selectedModel.mode.settingsIndex
    }

    LaunchedEffect(serviceState) {
        when (val state = serviceState) {
            is SeparationState.Progress -> separationProgress = state.value.coerceIn(0f, 1f)
            is SeparationState.Success -> result = state
            null -> Unit
        }
    }

    fun resetResult() {
        result = null
        separationProgress = 0f
        StemService.clearState(context)
    }

    val audioPicker = rememberLauncherForActivityResult(GetContentWithMimeTypes()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        DocumentUtils.persistReadPermission(context, uri)
        selectedAudioUriText = uri.toString()
        selectedAudioName = DocumentUtils.displayName(context, uri)
        resetResult()
    }

    fun launchStemService(start: PendingStemStart) {
        resetResult()
        val intent = Intent(context, StemService::class.java).apply {
            action = StemService.ACTION_START
            putExtra(StemService.EXTRA_URI, start.uriText)
            putExtra(StemService.EXTRA_MODEL_FILE, start.modelPath)
            putExtra(StemService.EXTRA_MODEL_ID, start.modelId)
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ContextCompat.startForegroundService(context, intent)
            } else {
                context.startService(intent)
            }
        } catch (error: Exception) {
            DiagnosticLogger.error(
                component = "StemScreen",
                event = "service_start_failed",
                message = error.message,
                fields = mapOf(
                    "model_id" to start.modelId,
                    "source_id" to DiagnosticRedactor.stableId(start.uriText),
                ),
                error = error,
            )
            Toast.makeText(
                context,
                "Không thể bắt đầu xử lý: ${error.message ?: "không xác định"}",
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    var pendingStart by remember { mutableStateOf<PendingStemStart?>(null) }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { _ ->
        pendingStart?.let(::launchStemService)
        pendingStart = null
    }

    fun startWithPermission() {
        val uri = selectedAudioUri ?: return
        val modelFile = downloadedModel ?: return
        val start = PendingStemStart(uri.toString(), modelFile.absolutePath, selectedModel.id)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            pendingStart = start
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            launchStemService(start)
        }
    }

    ToolScaffold(
        title = "Tách nhạc",
        onNavigateBack = onNavigateBack,
        bottomBar = {
            StickyProcessBar(
                label = if (result == null) "Bắt đầu tách" else "Tách lại",
                enabled = selectedAudioUri != null && downloadedModel != null,
                processing = serviceIsProcessing,
                progress = separationProgress,
                phase = "Đang tách bằng ${selectedModel.displayName}",
                onClick = ::startWithPermission,
                onCancel = {
                    context.startService(
                        Intent(context, StemService::class.java).setAction(StemService.ACTION_STOP),
                    )
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            MediaInputCard(
                fileName = selectedAudioName,
                supportingText = "Audio hoặc video",
                onChoose = { audioPicker.launch(arrayOf("audio/*", "video/*")) },
            )

            UnifiedAudioPlayer(
                sources = selectedAudioUri?.let {
                    listOf(AudioPreviewSource("source", "Bản gốc", it))
                }.orEmpty(),
                title = "Nghe bản gốc",
            )

            ToolSectionCard(title = "Cấu hình tách", icon = Icons.Default.GraphicEq) {
                Text("Số stem", fontWeight = FontWeight.SemiBold)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    listOf("2 stem", "4 stem").forEachIndexed { index, label ->
                        FilterChip(
                            selected = modeIndex == index,
                            onClick = {
                                if (modeIndex != index) {
                                    modeIndex = index
                                    SettingsManager.setStemModeIndex(context, index)
                                    val mode = StemMode.fromSettingsIndex(index)
                                    val model = StemModelRegistry.resolve(
                                        mode,
                                        SettingsManager.getStemModelId(context, index),
                                    )
                                    stemViewModel.selectModel(model.id)
                                    resetResult()
                                }
                            },
                            label = { Text(label) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }

                Text("Mô hình", fontWeight = FontWeight.SemiBold)
                modeModels.forEach { model ->
                    StemModelChoice(
                        model = model,
                        selected = selectedModel.id == model.id,
                        onSelect = {
                            stemViewModel.selectModel(model.id)
                            resetResult()
                        },
                    )
                }

                StemDownloadSection(
                    selectedModel = selectedModel,
                    state = downloadState,
                    onDownload = stemViewModel::downloadModel,
                    onPause = stemViewModel::pauseDownload,
                    onDiscard = stemViewModel::discardPartialDownload,
                )
            }

            ToolSectionCard(title = "Xuất file", icon = Icons.Default.Settings) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    CompactDropdown(
                        label = "Định dạng",
                        values = listOf("M4A", "MP3", "WAV", "FLAC"),
                        selectedIndex = formatIndex,
                        onSelected = {
                            formatIndex = it
                            SettingsManager.setAudFormatIndex(context, it)
                        },
                        modifier = Modifier.weight(1f),
                    )
                    CompactDropdown(
                        label = "Chất lượng",
                        values = listOf("128 kbps", "192 kbps", "256 kbps", "320 kbps", "Lossless"),
                        selectedIndex = bitrateIndex,
                        onSelected = {
                            bitrateIndex = it
                            SettingsManager.setAudBitrateIndex(context, it)
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            ToolSectionCard(title = "Nâng cao", icon = Icons.Default.Tune) {
                TextButton(onClick = { advancedExpanded = !advancedExpanded }) {
                    Text(if (advancedExpanded) "Thu gọn" else "Mở thông số nâng cao")
                }
                if (advancedExpanded) {
                    CompactDropdown(
                        label = "Luồng CPU",
                        values = listOf("1 luồng", "2 luồng", "4 luồng", "8 luồng"),
                        selectedIndex = threadsIndex,
                        onSelected = {
                            threadsIndex = it
                            SettingsManager.setNumThreadsIndex(context, it)
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "${selectedModel.sampleRate / 1_000} kHz • ${selectedModel.channels} kênh • ${selectedModel.backend}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        selectedModel.deviceRequirements.userFacingSummary,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            result?.let { success ->
                val items = buildStemItems(success)
                StemMixerCard(items)
                ToolSectionCard(title = "Tệp kết quả", icon = Icons.Default.MusicNote) {
                    items.forEach { item ->
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(item.label, fontWeight = FontWeight.SemiBold)
                            ResultFileActions(file = item.file)
                        }
                    }
                }
            }

            serviceError?.let {
                Text(it, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
                DiagnosticReportCard(errorContext = it)
            }
            Spacer(modifier = Modifier.height(12.dp))
        }
    }
}

@Composable
private fun StemModelChoice(
    model: StemModelDescriptor,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Column(modifier = Modifier.weight(1f)) {
            Text(model.displayName, fontWeight = FontWeight.Medium)
            Text(
                "${model.description} • ${model.downloadSizeMiB} MiB • ${model.licenseName}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun StemDownloadSection(
    selectedModel: StemModelDescriptor,
    state: DownloadState,
    onDownload: () -> Unit,
    onPause: () -> Unit,
    onDiscard: () -> Unit,
) {
    when (state) {
        DownloadState.Idle -> Button(onClick = onDownload, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.Memory, contentDescription = null)
            Text("Tải ${selectedModel.displayName}")
        }
        is DownloadState.Downloading -> {
            LinearProgressIndicator(
                progress = { state.progress.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
            )
            Text("Đang tải ${selectedModel.displayName}: ${(state.progress * 100f).toInt()}%")
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(onClick = onPause, modifier = Modifier.weight(1f)) { Text("Tạm dừng") }
                Button(onClick = onDownload, modifier = Modifier.weight(1f)) { Text("Tiếp tục") }
            }
            TextButton(onClick = onDiscard, modifier = Modifier.fillMaxWidth()) { Text("Xóa phần đã tải") }
        }
        is DownloadState.Error -> {
            Text(state.message, color = MaterialTheme.colorScheme.error)
            Button(onClick = onDownload, modifier = Modifier.fillMaxWidth()) { Text("Thử tải lại") }
        }
        is DownloadState.Success -> Text(
            "Model đã sẵn sàng",
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun StemMixerCard(items: List<StemMixerItem>) {
    if (items.isEmpty()) return
    val context = LocalContext.current
    val players = remember(items.map { it.file.absolutePath }) {
        items.associate { item ->
            item.id to ExoPlayer.Builder(context).build().apply {
                setMediaItem(MediaItem.fromUri(Uri.fromFile(item.file)))
                prepare()
            }
        }
    }
    val volumes = remember(items.map { it.id }) {
        mutableStateMapOf<String, Float>().apply { items.forEach { put(it.id, 1f) } }
    }
    val muted = remember(items.map { it.id }) {
        mutableStateMapOf<String, Boolean>().apply { items.forEach { put(it.id, false) } }
    }
    var soloId by rememberSaveable(items.map { it.id }) { mutableStateOf<String?>(null) }
    var isPlaying by remember { mutableStateOf(false) }
    var positionMs by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf(1L) }
    val referencePlayer = players[items.first().id]

    fun applyVolumes() {
        players.forEach { (id, player) ->
            val audible = soloId?.let { it == id } ?: (muted[id] != true)
            player.volume = if (audible) volumes[id]?.coerceIn(0f, 1f) ?: 1f else 0f
        }
    }

    DisposableEffect(players) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) {
                    durationMs = referencePlayer?.duration?.takeIf { it > 0L } ?: durationMs
                } else if (state == Player.STATE_ENDED) {
                    isPlaying = false
                    positionMs = 0L
                    players.values.forEach { it.pause(); it.seekTo(0L) }
                }
            }
        }
        referencePlayer?.addListener(listener)
        applyVolumes()
        onDispose {
            referencePlayer?.removeListener(listener)
            players.values.forEach(ExoPlayer::release)
        }
    }

    LaunchedEffect(soloId, muted.toMap(), volumes.toMap()) {
        applyVolumes()
    }

    LaunchedEffect(isPlaying, players) {
        while (isActive) {
            if (isPlaying) {
                val referencePosition = referencePlayer?.currentPosition ?: positionMs
                positionMs = referencePosition.coerceAtLeast(0L)
                val currentDuration = referencePlayer?.duration ?: 0L
                if (currentDuration > 0L) durationMs = currentDuration
                players.values.forEach { player ->
                    if (kotlin.math.abs(player.currentPosition - referencePosition) > 180L) {
                        player.seekTo(referencePosition)
                    }
                }
            }
            delay(120L)
        }
    }

    ToolSectionCard(
        title = "Mixer nghe thử",
        subtitle = "Solo, mute và âm lượng chỉ tác động khi nghe thử",
        icon = Icons.Default.GraphicEq,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            IconButton(
                onClick = {
                    if (isPlaying) {
                        players.values.forEach(ExoPlayer::pause)
                        isPlaying = false
                    } else {
                        applyVolumes()
                        players.values.forEach { player ->
                            player.seekTo(positionMs)
                            player.play()
                        }
                        isPlaying = true
                    }
                },
            ) {
                Icon(
                    if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (isPlaying) "Tạm dừng mixer" else "Phát mixer",
                )
            }
            Slider(
                value = positionMs.coerceAtMost(durationMs).toFloat(),
                onValueChange = {
                    positionMs = it.toLong()
                    players.values.forEach { player -> player.seekTo(positionMs) }
                },
                valueRange = 0f..durationMs.coerceAtLeast(1L).toFloat(),
                modifier = Modifier.weight(1f),
            )
        }
        Text(
            "${formatStemDuration(positionMs)} / ${formatStemDuration(durationMs)}",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        items.forEach { item ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(item.label, modifier = Modifier.weight(1f), fontWeight = FontWeight.Medium)
                    FilterChip(
                        selected = soloId == item.id,
                        onClick = { soloId = if (soloId == item.id) null else item.id },
                        label = { Text("S") },
                    )
                    FilterChip(
                        selected = muted[item.id] == true,
                        onClick = { muted[item.id] = !(muted[item.id] ?: false) },
                        label = { Text("M") },
                    )
                }
                Slider(
                    value = volumes[item.id] ?: 1f,
                    onValueChange = { volumes[item.id] = it },
                    valueRange = 0f..1f,
                )
            }
        }
    }
}

private fun buildStemItems(success: SeparationState.Success): List<StemMixerItem> = listOfNotNull(
    StemMixerItem("vocals", "Giọng hát", success.vocalsFile),
    StemMixerItem("music", if (success.drumsFile == null) "Nhạc nền" else "Nhạc nền tổng hợp", success.musicFile),
    success.drumsFile?.let { StemMixerItem("drums", "Trống", it) },
    success.bassFile?.let { StemMixerItem("bass", "Bass", it) },
    success.otherFile?.let { StemMixerItem("other", "Khác", it) },
).distinctBy { it.file.absolutePath }

private fun formatStemDuration(milliseconds: Long): String {
    val totalSeconds = milliseconds.coerceAtLeast(0L) / 1_000L
    return "%d:%02d".format(totalSeconds / 60L, totalSeconds % 60L)
}
