package com.aistudio.mediatool.feature.studio.ui

import android.media.AudioManager
import android.media.ToneGenerator
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
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
fun StudioLabScreen(
    projectId: String,
    onNavigateBack: () -> Unit,
) {
    val context = LocalContext.current
    val repository = remember { StudioProjectRepository(context) }
    val processor = remember { StudioMediaIntegrationProcessor(context) }
    val scope = rememberCoroutineScope()
    var project by remember { mutableStateOf<StudioProject?>(null) }
    var selectedSourceId by remember { mutableStateOf<String?>(null) }
    var latestDerivedId by remember { mutableStateOf<String?>(null) }
    var voiceCleanupReady by remember { mutableStateOf(false) }
    var processing by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    var status by remember { mutableStateOf("Sẵn sàng") }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(projectId) {
        StudioSessionRuntime.closeProject()
        val loaded = withContext(Dispatchers.IO) { repository.load(projectId) }
        project = loaded
        selectedSourceId = loaded?.defaultLabSourceId()
        voiceCleanupReady = withContext(Dispatchers.IO) { processor.isVoiceCleanupModelReady(forceRefresh = true) }
    }

    fun reload() {
        scope.launch {
            project = withContext(Dispatchers.IO) { repository.load(projectId) }
            if (project?.asset(selectedSourceId) == null) selectedSourceId = project?.defaultLabSourceId()
            voiceCleanupReady = withContext(Dispatchers.IO) { processor.isVoiceCleanupModelReady(forceRefresh = true) }
        }
    }

    fun runProcessor(kind: StudioMediaProcessorKind) {
        val sourceId = selectedSourceId ?: return
        if (processing) return
        processing = true
        progress = 0f
        error = null
        status = "Đang bắt đầu ${kind.label}"
        scope.launch {
            runCatching {
                processor.process(projectId, sourceId, kind) { value, message ->
                    withContext(Dispatchers.Main) {
                        progress = value.coerceIn(0f, 1f)
                        status = message
                    }
                }
            }.onSuccess { result ->
                project = result.project
                latestDerivedId = result.asset.id
                progress = 1f
                status = "Đã tạo ${result.asset.displayName}"
            }.onFailure { throwable ->
                error = throwable.message ?: "Không thể xử lý Studio asset"
                status = "Xử lý thất bại"
            }
            processing = false
        }
    }

    ToolScaffold(title = "Studio Lab", onNavigateBack = onNavigateBack) { innerPadding ->
        val loaded = project
        Column(
            modifier = Modifier.fillMaxSize().padding(innerPadding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (loaded == null) {
                Text("Không tìm thấy dự án Studio", color = MaterialTheme.colorScheme.error)
                return@Column
            }
            Text(loaded.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(
                "Lab xử lý non-destructive: Take và asset gốc luôn được giữ. Processor chỉ tạo DERIVED asset mới, có lineage để quay về source.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SourceAssetCard(loaded, selectedSourceId, !processing) {
                selectedSourceId = it
                latestDerivedId = null
            }
            MediaToolIntegrationCard(voiceCleanupReady, processing, ::runProcessor)
            StudioProCard(
                project = loaded,
                enabled = !processing,
                onSave = { next ->
                    scope.launch {
                        runCatching { withContext(Dispatchers.IO) { repository.updateProSettings(projectId, next) } }
                            .onSuccess { project = it; status = "Đã lưu thiết lập Pro"; error = null }
                            .onFailure { error = it.message ?: "Không thể lưu thiết lập Pro" }
                    }
                },
                onRenderPro = { next ->
                    scope.launch {
                        runCatching { withContext(Dispatchers.IO) { repository.updateProSettings(projectId, next) } }
                            .onSuccess { project = it; runProcessor(StudioMediaProcessorKind.PRO_VOCAL_CHAIN) }
                            .onFailure { error = it.message ?: "Không thể lưu Pro chain" }
                    }
                },
            )
            latestDerivedId?.let { derivedId ->
                val derived = project?.asset(derivedId) ?: loaded.asset(derivedId)
                if (derived != null) {
                    DerivedResultCard(
                        asset = derived,
                        enabled = !processing,
                        onApply = {
                            val sourceId = selectedSourceId ?: return@DerivedResultCard
                            scope.launch {
                                runCatching { withContext(Dispatchers.IO) { repository.applyDerivedAsset(projectId, sourceId, derived.id) } }
                                    .onSuccess { updated ->
                                        project = updated
                                        selectedSourceId = derived.id
                                        status = "Arrangement đang dùng ${derived.displayName}"
                                        error = null
                                    }
                                    .onFailure { throwable -> error = throwable.message ?: "Không thể áp dụng derived asset" }
                            }
                        },
                        onRestore = {
                            scope.launch {
                                runCatching {
                                    withContext(Dispatchers.IO) {
                                        val current = requireNotNull(repository.load(projectId)) { "Không tìm thấy dự án Studio" }
                                        repository.save(StudioDerivedAssetEditor.restoreSource(current, derived.id))
                                    }
                                }.onSuccess { updated ->
                                    project = updated
                                    selectedSourceId = derived.sourceAssetId
                                    status = "Đã quay arrangement về source"
                                    error = null
                                }.onFailure { throwable -> error = throwable.message ?: "Derived này chưa được dùng trong arrangement" }
                            }
                        },
                    )
                }
            }
            if (processing) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            CircularProgressIndicator()
                            Text(status, fontWeight = FontWeight.SemiBold)
                        }
                        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                    }
                }
            } else Text(status, style = MaterialTheme.typography.bodySmall)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            OutlinedButton(onClick = ::reload, enabled = !processing, modifier = Modifier.fillMaxWidth()) { Text("Đọc lại project") }
        }
    }
}

@Composable
private fun SourceAssetCard(project: StudioProject, selectedSourceId: String?, enabled: Boolean, onSelected: (String) -> Unit) {
    val assets = project.assets.filter { it.kind == StudioAssetKind.BEAT || it.kind == StudioAssetKind.TAKE || it.kind == StudioAssetKind.DERIVED }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Nguồn xử lý", fontWeight = FontWeight.Bold)
            Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                assets.forEach { asset ->
                    FilterChip(selected = asset.id == selectedSourceId, onClick = { onSelected(asset.id) }, enabled = enabled, label = { Text(asset.displayName.take(28)) })
                }
            }
            project.asset(selectedSourceId)?.let { asset ->
                Text(
                    buildString {
                        append(asset.kind.name)
                        asset.processorLabel?.let { append(" • ").append(it) }
                        asset.sampleRate?.let { append(" • ").append(it).append(" Hz") }
                        asset.channelCount?.let { append(" • ").append(it).append(" ch") }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun MediaToolIntegrationCard(voiceCleanupReady: Boolean, processing: Boolean, onProcess: (StudioMediaProcessorKind) -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Text("9. MediaTool Integration", fontWeight = FontWeight.Bold)
            Text("Voice Cleanup, Vocal Polish và Spatial/8D tạo asset mới trong project. Source không bị ghi đè.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(onClick = { onProcess(StudioMediaProcessorKind.VOICE_CLEANUP) }, enabled = !processing && voiceCleanupReady, modifier = Modifier.fillMaxWidth()) {
                Text("AI Voice Cleanup → Derived")
            }
            if (!voiceCleanupReady) Text("Voice Cleanup chưa có model MossFormer2. Tải model một lần trong công cụ Làm sạch giọng rồi quay lại Lab.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { onProcess(StudioMediaProcessorKind.VOCAL_POLISH) }, enabled = !processing, modifier = Modifier.weight(1f)) { Text("Vocal Polish") }
                OutlinedButton(onClick = { onProcess(StudioMediaProcessorKind.SPATIAL_8D) }, enabled = !processing, modifier = Modifier.weight(1f)) { Text("Spatial / 8D") }
            }
        }
    }
}

@Composable
private fun StudioProCard(project: StudioProject, enabled: Boolean, onSave: (StudioProSettings) -> Unit, onRenderPro: (StudioProSettings) -> Unit) {
    var bpm by remember(project.id, project.proSettings.tempo.bpm) { mutableFloatStateOf(project.proSettings.tempo.bpm) }
    var beatsPerBar by remember(project.id, project.proSettings.tempo.beatsPerBar) { mutableIntStateOf(project.proSettings.tempo.beatsPerBar) }
    var metronomeEnabled by remember(project.id, project.proSettings.tempo.metronomeEnabled) { mutableStateOf(project.proSettings.tempo.metronomeEnabled) }
    var metronomeGain by remember(project.id, project.proSettings.tempo.metronomeGainDb) { mutableFloatStateOf(project.proSettings.tempo.metronomeGainDb) }
    var fx by remember(project.id, project.proSettings.vocalFx) { mutableStateOf(project.proSettings.vocalFx) }
    var previewClick by remember { mutableStateOf(false) }
    val toneGenerator = remember { ToneGenerator(AudioManager.STREAM_MUSIC, 70) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(toneGenerator, lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                previewClick = false
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
    LaunchedEffect(previewClick, bpm, beatsPerBar) {
        if (!previewClick) return@LaunchedEffect
        var beat = 0
        while (isActive && previewClick) {
            toneGenerator.startTone(if (beat % beatsPerBar.coerceAtLeast(1) == 0) ToneGenerator.TONE_PROP_ACK else ToneGenerator.TONE_PROP_BEEP, 45)
            beat++
            delay((60_000f / bpm.coerceIn(40f, 260f)).toLong().coerceAtLeast(120L))
        }
    }
    fun settings() = StudioProSettings(
        tempo = StudioTempoSettings(bpm.coerceIn(40f, 260f), beatsPerBar.coerceIn(2, 12), metronomeEnabled, metronomeGain.coerceIn(-36f, 0f)),
        vocalFx = fx,
    )
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("10. Pro Layer", fontWeight = FontWeight.Bold)
            Text("Tempo + metronome metadata và Vocal FX chain được lưu trong project. Render Pro tạo DERIVED asset để A/B và quay lại source bất cứ lúc nào.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Tempo ${bpm.toInt()} BPM • $beatsPerBar/4", fontWeight = FontWeight.SemiBold)
            Slider(value = bpm, onValueChange = { bpm = it }, valueRange = 40f..220f, enabled = enabled)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(3, 4, 6).forEach { value -> FilterChip(selected = beatsPerBar == value, onClick = { beatsPerBar = value }, enabled = enabled, label = { Text("$value/4") }) }
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Metronome project")
                Switch(checked = metronomeEnabled, onCheckedChange = { metronomeEnabled = it }, enabled = enabled)
            }
            Text("Metronome gain ${metronomeGain.toInt()} dB", style = MaterialTheme.typography.labelSmall)
            Slider(value = metronomeGain, onValueChange = { metronomeGain = it }, valueRange = -36f..0f, enabled = enabled)
            OutlinedButton(onClick = { previewClick = !previewClick }, enabled = enabled, modifier = Modifier.fillMaxWidth()) { Text(if (previewClick) "Dừng click preview" else "Nghe click BPM") }
            Text("Vocal FX Preset", fontWeight = FontWeight.SemiBold)
            Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(false, { fx = StudioProFilterBuilder.naturalPreset() }, label = { Text("Natural") }, enabled = enabled)
                FilterChip(false, { fx = StudioProFilterBuilder.rapPreset() }, label = { Text("Rap") }, enabled = enabled)
                FilterChip(false, { fx = StudioProFilterBuilder.brightPreset() }, label = { Text("Bright") }, enabled = enabled)
                FilterChip(false, { fx = StudioProFilterBuilder.warmPreset() }, label = { Text("Warm") }, enabled = enabled)
            }
            FxSlider("High-pass", fx.highPassHz, "${fx.highPassHz.toInt()} Hz", 40f..180f, enabled) { fx = fx.copy(highPassHz = it) }
            FxSlider("Low EQ", fx.lowGainDb, signedDb(fx.lowGainDb), -6f..6f, enabled) { fx = fx.copy(lowGainDb = it) }
            FxSlider("Presence EQ", fx.midGainDb, signedDb(fx.midGainDb), -6f..6f, enabled) { fx = fx.copy(midGainDb = it) }
            FxSlider("Air EQ", fx.highGainDb, signedDb(fx.highGainDb), -6f..6f, enabled) { fx = fx.copy(highGainDb = it) }
            FxSlider("Compressor threshold", fx.compressorThresholdDb, "${fx.compressorThresholdDb.toInt()} dB", -32f..-6f, enabled) { fx = fx.copy(compressorThresholdDb = it) }
            FxSlider("Compressor ratio", fx.compressorRatio, String.format(Locale.US, "%.1f:1", fx.compressorRatio), 1f..8f, enabled) { fx = fx.copy(compressorRatio = it) }
            FxSlider("Reverb", fx.reverbWet, "${(fx.reverbWet * 100f).toInt()}%", 0f..0.35f, enabled) { fx = fx.copy(reverbWet = it) }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { onSave(settings()) }, enabled = enabled, modifier = Modifier.weight(1f)) { Text("Lưu Pro") }
                Button(onClick = { onRenderPro(settings()) }, enabled = enabled, modifier = Modifier.weight(1f)) { Text("Render Pro → Derived") }
            }
        }
    }
}

@Composable
private fun FxSlider(label: String, value: Float, valueLabel: String, range: ClosedFloatingPointRange<Float>, enabled: Boolean, onChange: (Float) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Text(valueLabel, style = MaterialTheme.typography.labelSmall)
    }
    Slider(value = value, onValueChange = onChange, valueRange = range, enabled = enabled)
}

@Composable
private fun DerivedResultCard(asset: StudioAsset, enabled: Boolean, onApply: () -> Unit, onRestore: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Derived mới", fontWeight = FontWeight.Bold)
            Text(asset.displayName)
            Text("${asset.processorLabel ?: asset.processorId ?: "Processor"} • source ${asset.sourceAssetId?.take(8) ?: "?"}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(onClick = onApply, enabled = enabled, modifier = Modifier.fillMaxWidth()) { Text("Dùng bản này trong arrangement") }
            OutlinedButton(onClick = onRestore, enabled = enabled, modifier = Modifier.fillMaxWidth()) { Text("Quay arrangement về source") }
        }
    }
}

private fun StudioProject.defaultLabSourceId(): String? {
    tracks.asSequence().filter { it.type != com.aistudio.mediatool.feature.studio.domain.StudioTrackType.BEAT }.forEach { track ->
        track.clips.firstOrNull()?.sourceAssetId?.let { return it }
        track.activeTakeId?.let { id -> track.takes.firstOrNull { it.id == id }?.assetId }?.let { return it }
        track.takes.lastOrNull()?.assetId?.let { return it }
    }
    return beatAssetId ?: assets.firstOrNull()?.id
}

private fun signedDb(value: Float): String = String.format(Locale.US, "%+.1f dB", value)
