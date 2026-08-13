package com.aistudio.mediatool.feature.studio.ui

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.aistudio.mediatool.ui.components.ToolScaffold
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun StudioPitchToolsScreen(projectId: String, onNavigateBack: () -> Unit) {
    val context = LocalContext.current
    val session = remember(context) { StudioPitchToolSession(context) }
    val runner = remember(projectId, session) { StudioPitchToolRunner(projectId, session) }
    val scope = rememberCoroutineScope()
    val player = remember(context) { ExoPlayer.Builder(context).build() }
    var playing by remember { mutableStateOf(false) }
    var model by remember(projectId) { mutableStateOf(StudioPitchUiModel()) }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) { playing = isPlaying }
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) playing = false
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }

    LaunchedEffect(projectId) {
        val project = withContext(Dispatchers.IO) { session.load(projectId) }
        model = model.copy(
            project = project,
            selectedTrackId = project?.pitchVocalTracks()?.firstOrNull()?.id,
            status = if (project == null) "Không tìm thấy dự án Studio" else model.status,
        )
    }

    fun stopPreview() {
        player.stop()
        player.clearMediaItems()
        playing = false
    }

    fun invalidate(next: StudioPitchUiModel) {
        stopPreview()
        model = next.copy(preview = null)
    }

    fun requestBack() {
        if (model.processing) {
            model = model.copy(status = "Đang tạo file xử lý. Hãy đợi hoàn tất trước khi quay lại.")
        } else {
            stopPreview()
            onNavigateBack()
        }
    }

    fun runPreview() {
        if (!model.canProcess) return
        val request = model.copy(processing = true, preview = null, status = "Đang chuẩn bị xử lý cao độ...")
        stopPreview()
        model = request
        scope.launch {
            runCatching {
                runner.preview(request) { message ->
                    scope.launch(Dispatchers.Main) { model = model.copy(status = message) }
                }
            }.onSuccess { model = it }
                .onFailure { model = model.copy(processing = false, status = it.message ?: "Không thể tạo bản nghe thử") }
        }
    }

    fun applyPreview() {
        if (model.preview == null || model.processing) return
        val request = model.copy(processing = true, status = "Đang áp dụng bản xử lý...")
        stopPreview()
        model = request
        scope.launch {
            runCatching { runner.apply(request) }
                .onSuccess { model = it }
                .onFailure { model = model.copy(processing = false, status = it.message ?: "Không thể áp dụng") }
        }
    }

    fun restoreAutoTune() {
        if (model.appliedAutoTune == null || model.processing) return
        val request = model.copy(processing = true, status = "Đang khôi phục giọng gốc...")
        stopPreview()
        model = request
        scope.launch {
            runCatching { runner.restore(request) }
                .onSuccess { model = it }
                .onFailure { model = model.copy(processing = false, status = it.message ?: "Không thể khôi phục") }
        }
    }

    fun togglePreview() {
        val preview = model.preview ?: return
        scope.launch {
            val file = withContext(Dispatchers.IO) { session.previewFile(projectId, preview.asset.id) }
            if (file == null) {
                model = model.copy(status = "Không tìm thấy bản nghe thử")
            } else if (player.isPlaying) {
                player.pause()
            } else {
                player.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
                player.prepare()
                player.play()
            }
        }
    }

    BackHandler(onBack = ::requestBack)
    ToolScaffold(title = "Auto-Tune & bè", onNavigateBack = ::requestBack) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            StudioPitchToolsSurface(
                model = model,
                playing = playing,
                onSelectTrack = { invalidate(model.copy(selectedTrackId = it)) },
                onMode = { invalidate(model.copy(mode = it)) },
                onStrength = { invalidate(model.copy(strength = it)) },
                onMaxCents = { invalidate(model.copy(maxCents = it)) },
                onHarmonyPreset = { invalidate(model.copy(harmonyPreset = it)) },
                onHarmonyVolume = { invalidate(model.copy(harmonyVolume = it)) },
                onHarmonyPan = { invalidate(model.copy(harmonyPan = it)) },
                onCreatePreview = ::runPreview,
                onTogglePreview = ::togglePreview,
                onApply = ::applyPreview,
                onRestore = ::restoreAutoTune,
            )
        }
    }
}
