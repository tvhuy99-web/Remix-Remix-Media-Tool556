package com.aistudio.mediatool.feature.studio.ui

import android.net.Uri
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.aistudio.mediatool.feature.studio.audio.StudioRhythmAnalyzer
import com.aistudio.mediatool.feature.studio.audio.StudioRhythmSuggestion
import com.aistudio.mediatool.feature.studio.data.StudioProjectRepository
import com.aistudio.mediatool.feature.studio.domain.StudioMusicalKeySettings
import com.aistudio.mediatool.feature.studio.domain.StudioPitchClass
import com.aistudio.mediatool.feature.studio.domain.StudioProject
import com.aistudio.mediatool.feature.studio.domain.StudioScaleMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToLong

/**
 * Adds Phase 3 musical controls without replacing the existing Studio tools surface.
 * Suggestions stay local until the user explicitly applies/saves them.
 */
@Composable
fun StudioPhase3ToolsScreen(projectId: String, onNavigateBack: () -> Unit) {
    val context = LocalContext.current
    val repository = remember(context) { StudioProjectRepository(context) }
    val analyzer = remember(context, repository) { StudioRhythmAnalyzer(context, repository) }
    val scope = rememberCoroutineScope()
    val player = remember(context) { ExoPlayer.Builder(context).build() }

    var panelOpen by remember { mutableStateOf(false) }
    var project by remember { mutableStateOf<StudioProject?>(null) }
    var draftBpm by remember { mutableFloatStateOf(120f) }
    var draftRoot by remember { mutableStateOf<StudioPitchClass?>(null) }
    var draftScale by remember { mutableStateOf<StudioScaleMode?>(null) }
    var draftGridOrigin by remember { mutableLongStateOf(0L) }
    var suggestion by remember { mutableStateOf<StudioRhythmSuggestion?>(null) }
    var analyzing by remember { mutableStateOf(false) }
    var beatPlaying by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Sẵn sàng") }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                beatPlaying = isPlaying
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) beatPlaying = false
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }

    fun loadDraft(loaded: StudioProject) {
        project = loaded
        draftBpm = loaded.proSettings.tempo.bpm
        draftRoot = loaded.proSettings.musicalKey.root
        draftScale = loaded.proSettings.musicalKey.scale
        draftGridOrigin = loaded.proSettings.tempo.gridOriginFrame
    }

    LaunchedEffect(projectId) {
        withContext(Dispatchers.IO) { repository.load(projectId) }?.let(::loadDraft)
    }

    Box(modifier = Modifier.fillMaxSize()) {
        StudioToolsScreen(projectId = projectId, onNavigateBack = onNavigateBack)
        ExtendedFloatingActionButton(
            onClick = {
                scope.launch {
                    withContext(Dispatchers.IO) { repository.load(projectId) }?.let(::loadDraft)
                    suggestion = null
                    status = "Sẵn sàng chỉnh nhịp và tông"
                    panelOpen = true
                }
            },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(18.dp),
            text = { Text("Nhịp & tông") },
        )
    }

    if (panelOpen) {
        AlertDialog(
            onDismissRequest = {
                if (!analyzing) panelOpen = false
            },
            title = {
                Text(
                    "Nhịp & tông bài",
                    modifier = Modifier.semantics { heading() },
                )
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 560.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        "Bạn có thể đặt thủ công hoặc để Phòng thu phân tích rồi duyệt gợi ý trước khi lưu.",
                        style = MaterialTheme.typography.bodyMedium,
                    )

                    Text("Tốc độ: ${draftBpm.roundToLong()} BPM", fontWeight = FontWeight.SemiBold)
                    Slider(
                        value = draftBpm,
                        onValueChange = { draftBpm = it },
                        valueRange = 40f..220f,
                        enabled = !analyzing,
                        modifier = Modifier.semantics {
                            stateDescription = "${draftBpm.roundToLong()} nhịp mỗi phút"
                        },
                    )

                    Text("Tông gốc", fontWeight = FontWeight.SemiBold)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        StudioPitchClass.entries.forEach { root ->
                            FilterChip(
                                selected = draftRoot == root,
                                onClick = { draftRoot = root },
                                enabled = !analyzing,
                                label = { Text(rootLabel(root)) },
                                modifier = Modifier.semantics {
                                    stateDescription = if (draftRoot == root) "Đã chọn" else "Chưa chọn"
                                },
                            )
                        }
                    }

                    Text("Thang âm", fontWeight = FontWeight.SemiBold)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = draftScale == StudioScaleMode.MAJOR,
                            onClick = { draftScale = StudioScaleMode.MAJOR },
                            enabled = !analyzing,
                            label = { Text("Trưởng") },
                        )
                        FilterChip(
                            selected = draftScale == StudioScaleMode.MINOR,
                            onClick = { draftScale = StudioScaleMode.MINOR },
                            enabled = !analyzing,
                            label = { Text("Thứ") },
                        )
                        OutlinedButton(
                            onClick = {
                                draftRoot = null
                                draftScale = null
                            },
                            enabled = !analyzing,
                        ) { Text("Chưa biết") }
                    }

                    Text(
                        "Phách 1: ${formatFrameAsTime(draftGridOrigin, project?.timelineSampleRate ?: 48_000)}",
                        fontWeight = FontWeight.SemiBold,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedButton(
                            onClick = {
                                val loaded = project ?: return@OutlinedButton
                                scope.launch {
                                    val beatId = loaded.beatAssetId ?: return@launch
                                    val file = withContext(Dispatchers.IO) { repository.assetFile(projectId, beatId) }
                                        ?: return@launch
                                    if (player.currentMediaItem == null) {
                                        player.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
                                        player.prepare()
                                    }
                                    if (player.isPlaying) player.pause() else player.play()
                                }
                            },
                            enabled = !analyzing,
                            modifier = Modifier.weight(1f),
                        ) { Text(if (beatPlaying) "Tạm dừng beat" else "Nghe beat") }
                        OutlinedButton(
                            onClick = {
                                val loaded = project ?: return@OutlinedButton
                                draftGridOrigin = (player.currentPosition.coerceAtLeast(0L) *
                                    loaded.timelineSampleRate.toLong() / 1_000L)
                                status = "Đã đặt phách 1 tại ${formatFrameAsTime(draftGridOrigin, loaded.timelineSampleRate)}; chưa lưu"
                            },
                            enabled = !analyzing && player.currentMediaItem != null,
                            modifier = Modifier.weight(1f),
                        ) { Text("Đặt phách 1 tại vị trí đang nghe") }
                    }

                    Button(
                        onClick = {
                            if (analyzing) return@Button
                            analyzing = true
                            status = "Đang phân tích nhịp và tông..."
                            scope.launch {
                                runCatching { analyzer.analyze(projectId) }
                                    .onSuccess {
                                        suggestion = it
                                        status = suggestionAnnouncement(it)
                                    }
                                    .onFailure {
                                        status = it.message ?: "Chưa thể phân tích nhịp và tông"
                                    }
                                analyzing = false
                            }
                        },
                        enabled = !analyzing,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(if (analyzing) "Đang phân tích..." else "Phân tích BPM + tông") }

                    suggestion?.let { result ->
                        Text(
                            suggestionAnnouncement(result),
                            modifier = Modifier.semantics {
                                liveRegion = LiveRegionMode.Polite
                                stateDescription = suggestionAnnouncement(result)
                            },
                        )
                        Button(
                            onClick = {
                                result.bpm?.let { draftBpm = it.coerceIn(40f, 220f) }
                                if (result.root != null && result.scale != null) {
                                    draftRoot = result.root
                                    draftScale = result.scale
                                }
                                status = "Đã đưa gợi ý vào phần chỉnh; hãy bấm Lưu để áp dụng cho bài"
                            },
                            enabled = !analyzing,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Dùng gợi ý này") }
                    }

                    Text(
                        status,
                        modifier = Modifier.semantics {
                            liveRegion = LiveRegionMode.Polite
                            stateDescription = status
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val loaded = project ?: return@Button
                        scope.launch {
                            runCatching {
                                withContext(Dispatchers.IO) {
                                    repository.save(
                                        loaded.copy(
                                            proSettings = loaded.proSettings.copy(
                                                tempo = loaded.proSettings.tempo.copy(
                                                    bpm = draftBpm.coerceIn(40f, 220f),
                                                    gridOriginFrame = draftGridOrigin.coerceAtLeast(0L),
                                                ),
                                                musicalKey = StudioMusicalKeySettings(
                                                    root = draftRoot,
                                                    scale = draftScale,
                                                ),
                                            ),
                                        ),
                                    )
                                }
                            }.onSuccess {
                                loadDraft(it)
                                status = "Đã lưu nhịp và tông"
                                panelOpen = false
                            }.onFailure {
                                status = it.message ?: "Không thể lưu nhịp và tông"
                            }
                        }
                    },
                    enabled = !analyzing && project != null,
                ) { Text("Lưu") }
            },
            dismissButton = {
                OutlinedButton(onClick = { panelOpen = false }, enabled = !analyzing) {
                    Text("Đóng")
                }
            },
        )
    }
}

private fun rootLabel(root: StudioPitchClass): String = when (root) {
    StudioPitchClass.C -> "Đô C"
    StudioPitchClass.C_SHARP -> "Đô♯ C♯"
    StudioPitchClass.D -> "Rê D"
    StudioPitchClass.D_SHARP -> "Rê♯ D♯"
    StudioPitchClass.E -> "Mi E"
    StudioPitchClass.F -> "Fa F"
    StudioPitchClass.F_SHARP -> "Fa♯ F♯"
    StudioPitchClass.G -> "Sol G"
    StudioPitchClass.G_SHARP -> "Sol♯ G♯"
    StudioPitchClass.A -> "La A"
    StudioPitchClass.A_SHARP -> "La♯ A♯"
    StudioPitchClass.B -> "Si B"
}

private fun scaleLabel(scale: StudioScaleMode?): String = when (scale) {
    StudioScaleMode.MAJOR -> "Trưởng"
    StudioScaleMode.MINOR -> "Thứ"
    null -> "chưa xác định"
}

private fun suggestionAnnouncement(value: StudioRhythmSuggestion): String {
    val tempo = value.bpm?.let { "${it.roundToLong()} BPM, độ tin cậy ${(value.bpmConfidence * 100).roundToLong()}%" }
        ?: "chưa xác định được BPM"
    val key = if (value.root != null && value.scale != null) {
        "${rootLabel(value.root)} ${scaleLabel(value.scale)}, độ tin cậy ${(value.keyConfidence * 100).roundToLong()}%"
    } else {
        "chưa xác định được tông"
    }
    return "Gợi ý: $tempo; $key. Kết quả chưa được áp dụng."
}

private fun formatFrameAsTime(frame: Long, sampleRate: Int): String {
    val millis = if (sampleRate > 0) frame.coerceAtLeast(0L) * 1_000L / sampleRate else 0L
    val minutes = millis / 60_000L
    val seconds = (millis % 60_000L) / 1_000L
    val ms = millis % 1_000L
    return "%d:%02d.%03d".format(minutes, seconds, ms)
}
