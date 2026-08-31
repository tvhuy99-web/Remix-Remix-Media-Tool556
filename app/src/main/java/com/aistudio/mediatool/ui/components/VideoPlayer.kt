package com.aistudio.mediatool.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay

@Composable
fun VideoPlayer(
    uri: android.net.Uri,
    modifier: Modifier = Modifier,
    onPlayerReady: (ExoPlayer) -> Unit = {},
) {
    val context = LocalContext.current
    val exoPlayer = remember(uri) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(uri))
            prepare()
        }
    }

    var isPlaying by remember(exoPlayer) { mutableStateOf(false) }
    var positionMs by remember(exoPlayer) { mutableLongStateOf(0L) }
    var durationMs by remember(exoPlayer) { mutableLongStateOf(1L) }

    LaunchedEffect(exoPlayer) {
        onPlayerReady(exoPlayer)
        while (true) {
            isPlaying = exoPlayer.isPlaying
            positionMs = exoPlayer.currentPosition.coerceAtLeast(0L)
            val duration = exoPlayer.duration
            if (duration != C.TIME_UNSET && duration > 0L) durationMs = duration
            delay(200L)
        }
    }

    DisposableEffect(exoPlayer) {
        onDispose { exoPlayer.release() }
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        AndroidView(
            modifier = Modifier
                .fillMaxWidth()
                .height(250.dp),
            factory = { ctx ->
                PlayerView(ctx).apply {
                    player = exoPlayer
                    useController = false
                    isFocusable = false
                    importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }
            },
            update = { playerView ->
                playerView.player = exoPlayer
                playerView.useController = false
            },
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(
                onClick = {
                    exoPlayer.seekTo((exoPlayer.currentPosition - SEEK_STEP_MS).coerceAtLeast(0L))
                },
                modifier = Modifier
                    .weight(1f)
                    .semantics { contentDescription = "Tua lùi 5 giây" },
            ) {
                Text("-5 giây")
            }

            Button(
                onClick = {
                    if (exoPlayer.playbackState == Player.STATE_ENDED) exoPlayer.seekTo(0L)
                    if (exoPlayer.isPlaying) exoPlayer.pause() else exoPlayer.play()
                },
                modifier = Modifier
                    .weight(1f)
                    .semantics {
                        contentDescription = if (isPlaying) "Tạm dừng" else "Phát"
                    },
            ) {
                Text(if (isPlaying) "Tạm dừng" else "Phát")
            }

            OutlinedButton(
                onClick = {
                    val upperBound = durationMs.takeIf { it > 1L } ?: Long.MAX_VALUE
                    exoPlayer.seekTo((exoPlayer.currentPosition + SEEK_STEP_MS).coerceAtMost(upperBound))
                },
                modifier = Modifier
                    .weight(1f)
                    .semantics { contentDescription = "Tua tới 5 giây" },
            ) {
                Text("+5 giây")
            }
        }

        Slider(
            value = positionMs.coerceIn(0L, durationMs).toFloat(),
            onValueChange = { value ->
                val target = value.toLong().coerceIn(0L, durationMs)
                positionMs = target
                exoPlayer.seekTo(target)
            },
            valueRange = 0f..durationMs.coerceAtLeast(1L).toFloat(),
            modifier = Modifier
                .fillMaxWidth()
                .semantics {
                    contentDescription = "Vị trí phát"
                    stateDescription = "${formatPlayerDuration(positionMs)} trên ${formatPlayerDuration(durationMs)}"
                },
        )

        Text(
            text = "${formatPlayerDuration(positionMs)} / ${formatPlayerDuration(durationMs)}",
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

private const val SEEK_STEP_MS = 5_000L

private fun formatPlayerDuration(durationMs: Long): String {
    val totalSeconds = durationMs.coerceAtLeast(0L) / 1_000L
    val hours = totalSeconds / 3_600L
    val minutes = (totalSeconds % 3_600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0L) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}
