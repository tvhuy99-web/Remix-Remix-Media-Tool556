package com.aistudio.mediatool.feature.studio.ui

import android.media.AudioManager
import android.media.ToneGenerator
import android.net.Uri
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.aistudio.mediatool.feature.studio.audio.StudioSessionRuntime
import com.aistudio.mediatool.feature.studio.data.StudioDerivedAssetEditor
import com.aistudio.mediatool.feature.studio.data.StudioProjectRepository
import com.aistudio.mediatool.feature.studio.domain.StudioAsset
import com.aistudio.mediatool.feature.studio.domain.StudioAssetKind
import com.aistudio.mediatool.feature.studio.domain.StudioProSettings
import com.aistudio.mediatool.feature.studio.domain.StudioProject
import com.aistudio.mediatool.feature.studio.domain.StudioTempoSettings
import com.aistudio.mediatool.feature.studio.integration.StudioMediaIntegrationProcessor
import com.aistudio.mediatool.feature.studio.integration.StudioMediaProcessorKind
import com.aistudio.mediatool.feature.studio.render.StudioProFilterBuilder
import com.aistudio.mediatool.ui.components.ToolScaffold
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun StudioToolsScreen(
    projectId: String,
    onNavigateBack: () -> Unit,
) {
    val context = LocalContext.current
    val repository = remember { StudioProjectRepository(context) }
    val processor = remember { StudioMediaIntegrationProcessor(context) }
    val scope = rememberCoroutineScope()
    var project by remember { mutableStateOf<StudioProject?>(null) }
    var selectedSourceId by remember { mutableStateOf<String?>(null) }
    var latestProcessedId by remember { mutableStateOf<String?>(null) }
    var voiceCleanupReady by remember { mutableStateOf(false) }
    var processing by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    var status by remember { mutableStateOf("Sẵn sàng") }
    var error by remember { mutableStateOf<String?>(null) }
    var previewingAssetId by remember { mutableStateOf<String?>(null) }

    val previewPlayer = remember { ExoPlayer.Builder(context).build() }
    DisposableEffect(previewPlayer) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) previewingAssetId = null
            }
        }
        previewPlayer.addListener(listener)
        onDispose {
            previewPlayer.removeListener(listener)
            previewPlayer.release()
        }
    }

    fun stopPreview() {
        previewPlayer.stop()
        previewPlayer.clearMediaItems()
        previewingAssetId = null
    }

    fun togglePreview(assetId: String) {
        if (previewingAssetId == assetId && previewPlayer.isPlaying) {
            stopPreview()
            return
        }
        scope.launch {
            val file = withContext(Dispatchers.IO) { repository.assetFile(projectId, assetId) }
            if (file == null) {
                error = "Không tìm thấy tệp âm thanh để nghe thử"
                return@launch
            }
            stopPreview()
            previewPlayer.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
            previewPlayer.prepare()
            previewPlayer.play()
            previewingAssetId = assetId
            error = null
        }
    }

    suspend fun loadProject(forceModelRefresh: Boolean) {
        val loaded = withContext(Dispatchers.IO) { repository.load(projectId) }
        project = loaded
        if (loaded?.asset(selectedSourceId) == null) selectedSourceId = loaded?.defaultToolSourceId()
        voiceCleanupReady = withContext(Dispatchers.IO) {
            processor.isVoiceCleanupModelReady(forceRefresh = forceModelRefresh)
        }
    }

    LaunchedEffect(projectId) {
        StudioSessionRuntime.closeProject()
        loadProject(forceModelRefresh = true)
    }

    fun runProcessor(kind: StudioMediaProcessorKind) {
        val sourceId = selectedSourceId ?: return
        if (processing) return
        stopPreview()
        processing = true
        progress = 0f
        error = null
        status = "Đang ${friendlyProcessorAction(kind)}..."
        scope.launch {
            runCatching {
                processor.process(projectId, sourceId, kind) { value, _ ->
                    withContext(Dispatchers.Main) {
                        progress = value.coerceIn(0f, 1f)
                        status = "Đang ${friendlyProcessorAction(kind)}..."
                    }
                }
            }.onSuccess { result ->
                project = result.project
                latestProcessedId = result.asset.id
                progress = 1f
                status = "Đã tạo bản mới"
            }.onFailure { throwable ->
                error = friendlyToolError(throwable.message ?: "Không thể xử lý âm thanh")
                status = "Chưa thể hoàn tất"
            }
            processing = false
        }
    }

    ToolScaffold(title = "Chỉnh âm thanh", onNavigateBack = onNavigateBack) { innerPadding ->
        val loaded = project
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (loaded == null) {
                Text("Không tìm thấy bài", color = MaterialTheme.colorScheme.error)
                return@Column
            }

            Text(
                loaded.name,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.semantics { heading() },
            )

            SourcePickerCard(
                project = loaded,
                selectedSourceId = selectedSourceId,
                enabled = !processing,
                previewingAssetId = previewingAssetId,
                onSelected = { assetId ->
                    stopPreview()
                    selectedSourceId = assetId
                    latestProcessedId = null
                },
                onPreview = ::togglePreview,
            )

            SoundToolsCard(
                voiceCleanupReady = voiceCleanupReady,
                processing = processing,
                onProcess = ::runProcessor,
            )

            ProToolsCard(
                project = loaded,
                enabled = !processing,
                onSave = { next ->
                    scope.launch {
                        runCatching {
                            withContext(Dispatchers.IO) {
                                repository.updateProSettings(projectId, next)
                            }
                        }.onSuccess {
                            project = it
                            status = "Đã lưu thiết lập"
                            error = null
                        }.onFailure {
                            error = friendlyToolError(it.message ?: "Không thể lưu thiết lập")
                        }
                    }
                },
                onCreateProcessed = { next ->
                    scope.launch {
                        runCatching {
                            withContext(Dispatchers.IO) {
                                repository.updateProSettings(projectId, next)
                            }
                        }.onSuccess {
                            project = it
                            runProcessor(StudioMediaProcessorKind.PRO_VOCAL_CHAIN)
                        }.onFailure {
                            error = friendlyToolError(it.message ?: "Không thể lưu thiết lập")
                        }
                    }
                },
            )

            latestProcessedId?.let { processedId ->
                val processed = project?.asset(processedId) ?: loaded.asset(processedId)
                if (processed != null) {
                    ProcessedResultCard(
                        asset = processed,
                        enabled = !processing,
                        isPlaying = previewingAssetId == processed.id && previewPlayer.isPlaying,
                        onPreview = { togglePreview(processed.id) },
                        onApply = {
                            val sourceId = selectedSourceId ?: return@ProcessedResultCard
                            stopPreview()
                            scope.launch {
                                runCatching {
                                    withContext(Dispatchers.IO) {
                                        repository.applyDerivedAsset(projectId, sourceId, processed.id)
                                    }
                                }.onSuccess { updated ->
                                    project = updated
                                    selectedSourceId = processed.id
                                    status = "Bài đang dùng bản mới"
                                    error = null
                                }.onFailure { throwable ->
                                    error = friendlyToolError(
                                        throwable.message ?: "Không thể dùng bản mới trong bài",
                                    )
                                }
                            }
                        },
                        onRestore = {
                            stopPreview()
                            scope.launch {
                                runCatching {
                                    withContext(Dispatchers.IO) {
                                        val current = requireNotNull(repository.load(projectId)) {
                                            "Không tìm thấy bài"
                                        }
                                        repository.save(
                                            StudioDerivedAssetEditor.restoreSource(current, processed.id),
                                        )
                                    }
                                }.onSuccess { updated ->
                                    project = updated
                                    selectedSourceId = processed.sourceAssetId
                                    status = "Đã quay về bản gốc"
                                    error = null
                                }.onFailure { throwable ->
                                    error = friendlyToolError(
                                        throwable.message ?: "Bản này chưa được dùng trong bài",
                                    )
                                }
                            }
                        },
                    )
                }
            }

            if (processing) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            CircularProgressIndicator()
                            Text(status, fontWeight = FontWeight.SemiBold)
                        }
                        LinearProgressIndicator(
                            progress = { progress },
                            modifier = Modifier
                                .fillMaxWidth()
                                .semantics {
                                    contentDescription = "Tiến độ xử lý"
                                    stateDescription = "${(progress * 100f).toInt()} phần trăm"
                                },
                        )
                    }
                }
            } else {
                Text(status, style = MaterialTheme.typography.bodySmall)
            }

            error?.let {
                Text(it, color = MaterialTheme.colorScheme.error)
            }

            OutlinedButton(
                onClick = {
                    stopPreview()
                    scope.launch { loadProject(forceModelRefresh = true) }
                },
                enabled = !processing,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Làm mới")
            }
        }
    }
}

@Composable
private fun SourcePickerCard(
    project: StudioProject,
    selectedSourceId: String?,
    enabled: Boolean,
    previewingAssetId: String?,
    onSelected: (String) -> Unit,
    onPreview: (String) -> Unit,
) {
    val assets = project.assets.filter {
        it.kind == StudioAssetKind.BEAT ||
            it.kind == StudioAssetKind.TAKE ||
            it.kind == StudioAssetKind.DERIVED
    }
    val selected = project.asset(selectedSourceId)

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                "Chọn bản để xử lý",
                fontWeight = FontWeight.Bold,
                modifier = Modifier.semantics { heading() },
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                assets.forEach { asset ->
                    FilterChip(
                        selected = asset.id == selectedSourceId,
                        onClick = { onSelected(asset.id) },
                        enabled = enabled,
                        label = { Text(asset.displayName.take(30)) },
                    )
                }
            }
            selected?.let { asset ->
                Text(
                    friendlyAssetType(asset),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(
                    onClick = { onPreview(asset.id) },
                    enabled = enabled,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        if (previewingAssetId == asset.id) {
                            "Dừng nghe"
                        } else {
                            "Nghe thử bản này"
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun SoundToolsCard(
    voiceCleanupReady: Boolean,
    processing: Boolean,
    onProcess: (StudioMediaProcessorKind) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                "Làm sạch & hiệu ứng",
                fontWeight = FontWeight.Bold,
                modifier = Modifier.semantics { heading() },
            )
            Button(
                onClick = { onProcess(StudioMediaProcessorKind.VOICE_CLEANUP) },
                enabled = !processing && voiceCleanupReady,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Làm sạch giọng")
            }
            if (!voiceCleanupReady) {
                Text(
                    "Cần tải gói Làm sạch giọng trước. Mở công cụ Làm sạch giọng và tải một lần.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = { onProcess(StudioMediaProcessorKind.VOCAL_POLISH) },
                    enabled = !processing,
                    modifier = Modifier.weight(1f),
                ) { Text("Giọng rõ hơn") }
                OutlinedButton(
                    onClick = { onProcess(StudioMediaProcessorKind.SPATIAL_8D) },
                    enabled = !processing,
                    modifier = Modifier.weight(1f),
                ) { Text("Không gian 8D") }
            }
        }
    }
}

@Composable
private fun ProToolsCard(
    project: StudioProject,
    enabled: Boolean,
    onSave: (StudioProSettings) -> Unit,
    onCreateProcessed: (StudioProSettings) -> Unit,
) {
    var tempo by remember(project.id, project.proSettings.tempo.bpm) {
        mutableFloatStateOf(project.proSettings.tempo.bpm)
    }
    var beatsPerBar by remember(project.id, project.proSettings.tempo.beatsPerBar) {
        mutableIntStateOf(project.proSettings.tempo.beatsPerBar)
    }
    var countEnabled by remember(project.id, project.proSettings.tempo.metronomeEnabled) {
        mutableStateOf(project.proSettings.tempo.metronomeEnabled)
    }
    var countVolume by remember(project.id, project.proSettings.tempo.metronomeGainDb) {
        mutableFloatStateOf(project.proSettings.tempo.metronomeGainDb)
    }
    var voice by remember(project.id, project.proSettings.vocalFx) {
        mutableStateOf(project.proSettings.vocalFx)
    }
    var previewCount by remember { mutableStateOf(false) }
    var showFineTune by rememberSaveable { mutableStateOf(false) }
    val toneGenerator = remember { ToneGenerator(AudioManager.STREAM_MUSIC, 70) }
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(toneGenerator, lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                previewCount = false
                toneGenerator.stopTone()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            toneGenerator.stopTone()
            toneGenerator.release()
        }
    }

    LaunchedEffect(previewCount, tempo, beatsPerBar) {
        if (!previewCount) return@LaunchedEffect
        var beat = 0
        while (isActive && previewCount) {
            toneGenerator.startTone(
                if (beat % beatsPerBar.coerceAtLeast(1) == 0) {
                    ToneGenerator.TONE_PROP_ACK
                } else {
                    ToneGenerator.TONE_PROP_BEEP
                },
                45,
            )
            beat++
            delay((60_000f / tempo.coerceIn(40f, 220f)).toLong().coerceAtLeast(120L))
        }
    }

    fun settings() = StudioProSettings(
        tempo = StudioTempoSettings(
            bpm = tempo.coerceIn(40f, 220f),
            beatsPerBar = beatsPerBar.coerceIn(2, 12),
            metronomeEnabled = countEnabled,
            metronomeGainDb = countVolume.coerceIn(-36f, 0f),
        ),
        vocalFx = voice,
    )

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "Nhịp & màu giọng",
                fontWeight = FontWeight.Bold,
                modifier = Modifier.semantics { heading() },
            )

            Text("Tốc độ: ${tempo.toInt()} nhịp/phút", fontWeight = FontWeight.SemiBold)
            Slider(
                value = tempo,
                onValueChange = { tempo = it },
                valueRange = 40f..220f,
                enabled = enabled,
                modifier = Modifier.semantics {
                    contentDescription = "Tốc độ bài hát"
                    stateDescription = "${tempo.toInt()} nhịp mỗi phút"
                },
            )

            Text("Mỗi ô nhịp", style = MaterialTheme.typography.labelLarge)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                listOf(3, 4, 6).forEach { value ->
                    FilterChip(
                        selected = beatsPerBar == value,
                        onClick = { beatsPerBar = value },
                        enabled = enabled,
                        label = { Text("$value nhịp") },
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Bật tiếng đếm")
                Switch(
                    checked = countEnabled,
                    onCheckedChange = { countEnabled = it },
                    enabled = enabled,
                    modifier = Modifier.semantics {
                        contentDescription = "Bật tiếng đếm"
                        stateDescription = if (countEnabled) "Đang bật" else "Đang tắt"
                    },
                )
            }

            Text("Âm lượng tiếng đếm", style = MaterialTheme.typography.labelMedium)
            Slider(
                value = countVolume,
                onValueChange = { countVolume = it },
                valueRange = -36f..0f,
                enabled = enabled,
                modifier = Modifier.semantics {
                    contentDescription = "Âm lượng tiếng đếm"
                    stateDescription = levelPercent(countVolume, -36f, 0f)
                },
            )

            OutlinedButton(
                onClick = { previewCount = !previewCount },
                enabled = enabled,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (previewCount) "Dừng tiếng đếm" else "Nghe thử tiếng đếm")
            }

            Text("Màu giọng", fontWeight = FontWeight.SemiBold)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = false,
                    onClick = { voice = StudioProFilterBuilder.naturalPreset() },
                    enabled = enabled,
                    label = { Text("Tự nhiên") },
                )
                FilterChip(
                    selected = false,
                    onClick = { voice = StudioProFilterBuilder.rapPreset() },
                    enabled = enabled,
                    label = { Text("Rap") },
                )
                FilterChip(
                    selected = false,
                    onClick = { voice = StudioProFilterBuilder.brightPreset() },
                    enabled = enabled,
                    label = { Text("Sáng") },
                )
                FilterChip(
                    selected = false,
                    onClick = { voice = StudioProFilterBuilder.warmPreset() },
                    enabled = enabled,
                    label = { Text("Ấm") },
                )
            }

            OutlinedButton(
                onClick = { showFineTune = !showFineTune },
                enabled = enabled,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (showFineTune) "Ẩn tùy chỉnh giọng" else "Tùy chỉnh giọng")
            }

            if (showFineTune) {
                FriendlySlider(
                    label = "Giảm tiếng ù",
                    value = voice.highPassHz,
                    range = 40f..180f,
                    enabled = enabled,
                ) { voice = voice.copy(highPassHz = it) }
                FriendlySlider(
                    label = "Độ ấm",
                    value = voice.lowGainDb,
                    range = -6f..6f,
                    enabled = enabled,
                ) { voice = voice.copy(lowGainDb = it) }
                FriendlySlider(
                    label = "Độ rõ",
                    value = voice.midGainDb,
                    range = -6f..6f,
                    enabled = enabled,
                ) { voice = voice.copy(midGainDb = it) }
                FriendlySlider(
                    label = "Độ sáng",
                    value = voice.highGainDb,
                    range = -6f..6f,
                    enabled = enabled,
                ) { voice = voice.copy(highGainDb = it) }
                FriendlySlider(
                    label = "Độ nén",
                    value = voice.compressorThresholdDb,
                    range = -32f..-6f,
                    enabled = enabled,
                ) { voice = voice.copy(compressorThresholdDb = it) }
                FriendlySlider(
                    label = "Độ mạnh khi nén",
                    value = voice.compressorRatio,
                    range = 1f..8f,
                    enabled = enabled,
                ) { voice = voice.copy(compressorRatio = it) }
                FriendlySlider(
                    label = "Độ vang",
                    value = voice.reverbWet,
                    range = 0f..0.35f,
                    enabled = enabled,
                ) { voice = voice.copy(reverbWet = it) }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = { onSave(settings()) },
                    enabled = enabled,
                    modifier = Modifier.weight(1f),
                ) { Text("Lưu thiết lập") }
                Button(
                    onClick = { onCreateProcessed(settings()) },
                    enabled = enabled,
                    modifier = Modifier.weight(1f),
                ) { Text("Tạo bản mới") }
            }
        }
    }
}

@Composable
private fun FriendlySlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    enabled: Boolean,
    onChange: (Float) -> Unit,
) {
    val state = levelPercent(value, range.start, range.endInclusive)
    Text("$label: $state", style = MaterialTheme.typography.labelMedium)
    Slider(
        value = value,
        onValueChange = onChange,
        valueRange = range,
        enabled = enabled,
        modifier = Modifier.semantics {
            contentDescription = label
            stateDescription = state
        },
    )
}

@Composable
private fun ProcessedResultCard(
    asset: StudioAsset,
    enabled: Boolean,
    isPlaying: Boolean,
    onPreview: () -> Unit,
    onApply: () -> Unit,
    onRestore: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                "Bản mới đã sẵn sàng",
                fontWeight = FontWeight.Bold,
                modifier = Modifier.semantics { heading() },
            )
            Text(asset.displayName)
            OutlinedButton(
                onClick = onPreview,
                enabled = enabled,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (isPlaying) "Dừng nghe" else "Nghe thử kết quả")
            }
            Button(
                onClick = onApply,
                enabled = enabled,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Dùng bản này trong bài")
            }
            OutlinedButton(
                onClick = onRestore,
                enabled = enabled,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Quay về bản gốc")
            }
        }
    }
}

private fun StudioProject.defaultToolSourceId(): String? {
    tracks
        .asSequence()
        .filter { it.type != StudioTrackType.BEAT }
        .forEach { track ->
            track.clips.firstOrNull()?.sourceAssetId?.let { return it }
            track.activeTakeId
                ?.let { id -> track.takes.firstOrNull { it.id == id }?.assetId }
                ?.let { return it }
            track.takes.lastOrNull()?.assetId?.let { return it }
        }
    return beatAssetId ?: assets.firstOrNull()?.id
}

private fun friendlyAssetType(asset: StudioAsset): String = when (asset.kind) {
    StudioAssetKind.BEAT -> "Nhạc nền"
    StudioAssetKind.TAKE -> "Bản thu giọng"
    StudioAssetKind.DERIVED -> "Bản đã xử lý"
}

private fun friendlyProcessorAction(kind: StudioMediaProcessorKind): String = when (kind) {
    StudioMediaProcessorKind.VOICE_CLEANUP -> "làm sạch giọng"
    StudioMediaProcessorKind.VOCAL_POLISH -> "làm giọng rõ hơn"
    StudioMediaProcessorKind.SPATIAL_8D -> "tạo hiệu ứng không gian"
    StudioMediaProcessorKind.PRO_VOCAL_CHAIN -> "tạo màu giọng"
}

private fun friendlyToolError(value: String): String = value
    .replace("derived", "bản đã xử lý", ignoreCase = true)
    .replace("source asset", "bản gốc", ignoreCase = true)
    .replace("source", "bản gốc", ignoreCase = true)
    .replace("processor", "công cụ xử lý", ignoreCase = true)
    .replace("render", "xử lý", ignoreCase = true)
    .replace("canonical", "hợp lệ", ignoreCase = true)
    .replace("project", "bài", ignoreCase = true)
    .replace("arrangement", "phần đã ghép", ignoreCase = true)
    .replace("model", "gói xử lý", ignoreCase = true)

private fun levelPercent(value: Float, min: Float, max: Float): String {
    if (max <= min) return "0%"
    val percent = (((value - min) / (max - min)) * 100f).coerceIn(0f, 100f).toInt()
    return "$percent%"
}
