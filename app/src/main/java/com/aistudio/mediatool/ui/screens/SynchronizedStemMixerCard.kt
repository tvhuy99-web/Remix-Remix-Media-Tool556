package com.aistudio.mediatool.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aistudio.mediatool.core.audio.SynchronizedStemMixerController
import com.aistudio.mediatool.core.audio.SynchronizedStemTrack
import com.aistudio.mediatool.core.ml.SeparationState
import com.aistudio.mediatool.ui.components.ToolSectionCard
import com.aistudio.mediatool.ui.components.formatDuration
import kotlin.math.roundToInt

/** Mixer UI backed by one FFmpeg timeline and one AudioTrack hardware clock. */
@Composable
internal fun SynchronizedStemMixerCard(success: SeparationState.Success) {
    val context = LocalContext.current
    val tracks = remember(success) { synchronizedTracks(success) }
    if (tracks.isEmpty()) return

    val controller = remember(tracks.map { it.file.absolutePath }) {
        SynchronizedStemMixerController(context, tracks)
    }
    val isPlaying by controller.isPlaying.collectAsState()
    val enginePositionMs by controller.positionMs.collectAsState()
    val durationMs by controller.durationMs.collectAsState()
    val playbackError by controller.error.collectAsState()

    val volumes = remember(tracks.map(SynchronizedStemTrack::id)) {
        mutableStateMapOf<String, Float>().apply { tracks.forEach { put(it.id, 1f) } }
    }
    val muted = remember(tracks.map(SynchronizedStemTrack::id)) {
        mutableStateMapOf<String, Boolean>().apply { tracks.forEach { put(it.id, false) } }
    }
    var soloId by rememberSaveable(tracks.map(SynchronizedStemTrack::id)) { mutableStateOf<String?>(null) }
    var displayedPositionMs by remember { mutableLongStateOf(0L) }
    var draggingTimeline by remember { mutableStateOf(false) }

    DisposableEffect(controller) {
        onDispose { controller.close() }
    }

    LaunchedEffect(enginePositionMs, draggingTimeline) {
        if (!draggingTimeline) displayedPositionMs = enginePositionMs
    }

    LaunchedEffect(soloId, muted.toMap(), volumes.toMap(), controller) {
        controller.updateGains(
            FloatArray(tracks.size) { index ->
                val track = tracks[index]
                val audible = soloId?.let { selected -> selected == track.id } ?: (muted[track.id] != true)
                if (audible) volumes[track.id]?.coerceIn(0f, 1f) ?: 1f else 0f
            },
        )
    }

    ToolSectionCard(title = "Nghe kết quả") {
        Text(
            "Các phần âm thanh được phát trên cùng một đồng hồ để không bị lệch nhịp.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            IconButton(
                onClick = {
                    if (isPlaying) {
                        controller.pause()
                    } else {
                        val startPosition = if (displayedPositionMs >= durationMs - 100L) {
                            0L
                        } else {
                            displayedPositionMs
                        }
                        displayedPositionMs = startPosition
                        controller.play(startPosition)
                    }
                },
            ) {
                Icon(
                    imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (isPlaying) {
                        "Tạm dừng các phần âm thanh"
                    } else {
                        "Phát các phần âm thanh đồng bộ"
                    },
                )
            }
            Slider(
                value = displayedPositionMs.coerceIn(0L, durationMs.coerceAtLeast(1L)).toFloat(),
                onValueChange = { value ->
                    draggingTimeline = true
                    displayedPositionMs = value.toLong()
                },
                onValueChangeFinished = {
                    controller.seekTo(displayedPositionMs, resume = isPlaying)
                    draggingTimeline = false
                },
                valueRange = 0f..durationMs.coerceAtLeast(1L).toFloat(),
                modifier = Modifier
                    .weight(1f)
                    .semantics {
                        contentDescription = "Vị trí nghe kết quả đồng bộ"
                        stateDescription =
                            "${formatDuration(displayedPositionMs)} trên ${formatDuration(durationMs)}"
                    },
            )
        }
        Text(
            "${formatDuration(displayedPositionMs)} / ${formatDuration(durationMs)}",
            modifier = Modifier.clearAndSetSemantics { },
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        playbackError?.let { message ->
            Text(
                message,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }

        tracks.forEach { track ->
            val isSolo = soloId == track.id
            val isMuted = muted[track.id] == true
            val volume = volumes[track.id] ?: 1f
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    track.label,
                    modifier = Modifier.fillMaxWidth().clearAndSetSemantics { },
                    fontWeight = FontWeight.Medium,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(
                        selected = isSolo,
                        onClick = { soloId = if (isSolo) null else track.id },
                        label = { Text("Solo") },
                        modifier = Modifier
                            .weight(1f)
                            .semantics {
                                contentDescription = "Solo ${track.label.lowercase()}"
                                stateDescription = if (isSolo) "Đang bật" else "Đang tắt"
                            },
                    )
                    FilterChip(
                        selected = isMuted,
                        onClick = { muted[track.id] = !isMuted },
                        label = { Text("Tắt tiếng") },
                        modifier = Modifier
                            .weight(1f)
                            .semantics {
                                contentDescription = "Tắt tiếng ${track.label.lowercase()}"
                                stateDescription = if (isMuted) "Đang bật" else "Đang tắt"
                            },
                    )
                }
                Slider(
                    value = volume,
                    onValueChange = { volumes[track.id] = it },
                    valueRange = 0f..1f,
                    modifier = Modifier.semantics {
                        contentDescription = "Âm lượng ${track.label.lowercase()}"
                        stateDescription = "${(volume * 100f).roundToInt()} phần trăm"
                    },
                )
            }
        }
    }
}

private fun synchronizedTracks(success: SeparationState.Success): List<SynchronizedStemTrack> {
    val individualFourStem = listOfNotNull(
        success.drumsFile?.let { SynchronizedStemTrack("drums", "Trống", it) },
        success.bassFile?.let { SynchronizedStemTrack("bass", "Bass", it) },
        success.otherFile?.let { SynchronizedStemTrack("other", "Phần khác", it) },
    )
    return if (individualFourStem.size == 3) {
        listOf(SynchronizedStemTrack("vocals", "Giọng hát", success.vocalsFile)) + individualFourStem
    } else {
        listOf(
            SynchronizedStemTrack("vocals", "Giọng hát", success.vocalsFile),
            SynchronizedStemTrack("music", "Nhạc nền", success.musicFile),
        )
    }
}
