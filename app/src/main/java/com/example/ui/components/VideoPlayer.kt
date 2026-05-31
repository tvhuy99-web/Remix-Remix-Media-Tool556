package com.example.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView

@Composable
fun VideoPlayer(
    uri: android.net.Uri,
    modifier: Modifier = Modifier,
    onPlayerReady: (ExoPlayer) -> Unit = {}
) {
    val context = LocalContext.current
    
    val exoPlayer = remember(uri) {
        ExoPlayer.Builder(context).build().apply {
            val mediaItem = MediaItem.fromUri(uri)
            setMediaItem(mediaItem)
            prepare()
            onPlayerReady(this)
        }
    }
    
    DisposableEffect(exoPlayer) {
        onDispose {
            exoPlayer.release()
        }
    }

    AndroidView(
        modifier = modifier
            .fillMaxWidth()
            .height(250.dp),
        factory = { ctx ->
            PlayerView(ctx).apply {
                player = exoPlayer
            }
        },
        update = { playerView ->
            playerView.player = exoPlayer
        }
    )
}
