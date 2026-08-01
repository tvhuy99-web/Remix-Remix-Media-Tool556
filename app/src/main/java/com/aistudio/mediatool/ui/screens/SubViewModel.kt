package com.aistudio.mediatool.ui.screens

import android.content.Context
import android.net.Uri
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aistudio.mediatool.core.subtitle.SubtitleParser
import com.aistudio.mediatool.core.subtitle.UtteranceQueueTracker
import com.aistudio.mediatool.core.subtitle.UtteranceCompletion
import com.aistudio.mediatool.core.diagnostics.DiagnosticLogger
import com.aistudio.mediatool.core.diagnostics.DiagnosticRedactor
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale

data class SubtitleItem(
    val id: Int,
    val startMs: Long,
    val endMs: Long,
    val text: String
)

data class SubScreenState(
    val videoUri: Uri? = null,
    val videoFileName: String = "Chưa chọn",
    
    val subUri: Uri? = null,
    val subFileName: String = "Chưa chọn",
    val subCount: Int = 0,
    
    val isPlaying: Boolean = false,
    val isPlayerReady: Boolean = false,
    val currentTimeMs: Long = 0,
    val durationMs: Long = 0,
    
    val videoVolume: Float = 1.0f,
    
    val autoDuck: Boolean = true,
    val ttsSpeed: Float = 1.0f,
    val ttsVolume: Float = 1.0f,
    
    // Extraction state
    val isExtracting: Boolean = false,
    val extractProgress: String = "",
    val extractOutputPath: String = "",
    
    // Subtitle display
    val currentSubtitleText: String = "",
    
    // Status/Error
    val ttsStatusMessage: String? = null
)

class SubViewModel : ViewModel() {
    private val _state = MutableStateFlow(SubScreenState())
    val state: StateFlow<SubScreenState> = _state.asStateFlow()

    private var exoPlayer: ExoPlayer? = null
    private var tts: TextToSpeech? = null
    
    private var subtitleList: List<SubtitleItem> = emptyList()
    private var currentSubIdx = 0
    
    // Track TTS reading to manage Auto-Duck
    @Volatile
    private var isSpeaking = false
    private val utteranceQueue = UtteranceQueueTracker()

    private var timeTrackerJob: Job? = null
    
    /**
     * Parse SRT content into a list of SubtitleItem
     */
    fun parseSrt(content: String) {
        subtitleList = SubtitleParser.parse(content).map {
            SubtitleItem(it.id, it.startMs, it.endMs, it.text)
        }
        currentSubIdx = 0
        _state.update { it.copy(subCount = subtitleList.size, currentSubtitleText = "") }
        DiagnosticLogger.info(
            component = TAG,
            event = "subtitle_parsed",
            fields = mapOf("cue_count" to subtitleList.size, "content_chars" to content.length),
        )
    }

    fun initPlayer(context: Context) {
        val appContext = context.applicationContext
        if (exoPlayer == null) {
            exoPlayer = ExoPlayer.Builder(appContext).build().apply {
                addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        _state.update {
                            it.copy(
                                isPlayerReady = playbackState == Player.STATE_READY || playbackState == Player.STATE_BUFFERING,
                                durationMs = if (playbackState == Player.STATE_READY) duration.coerceAtLeast(0) else it.durationMs,
                                currentSubtitleText = if (playbackState == Player.STATE_ENDED) "" else it.currentSubtitleText,
                            )
                        }
                        if (playbackState == Player.STATE_ENDED) stopTtsAndRestoreVolume()
                    }
                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        _state.update { it.copy(isPlaying = isPlaying) }
                        if (isPlaying) {
                            startTimeTracker()
                        } else {
                            stopTimeTracker()
                        }
                    }
                })
                volume = _state.value.videoVolume
            }
        }
        
        if (tts == null) {
            tts = TextToSpeech(appContext) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    val locale = Locale("vi", "VN")
                    val result = tts?.setLanguage(locale)
                    
                    if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                        val errorMsg = "Lỗi: Máy báo thiếu data hoặc không hỗ trợ Tiếng Việt (Mã: $result)"
                        DiagnosticLogger.error(
                            component = TAG,
                            event = "tts_language_unavailable",
                            message = errorMsg,
                            fields = mapOf("language_result" to result),
                        )
                        _state.update { it.copy(ttsStatusMessage = errorMsg) }
                    } else {
                        DiagnosticLogger.info(
                            component = TAG,
                            event = "tts_ready",
                            fields = mapOf("language_result" to result, "locale" to locale.toLanguageTag()),
                        )
                        _state.update { it.copy(ttsStatusMessage = null) }
                    }
                    
                    // Đảm bảo âm thanh TTS phát qua kệnh Media để đồng bộ âm lượng với Video
                    val audioAttributes = android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                    tts?.setAudioAttributes(audioAttributes)
                    
                    tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) {
                            if (utteranceQueue.isCurrent(utteranceId)) {
                                isSpeaking = true
                                applyAutoDuck(true)
                                DiagnosticLogger.info(
                                    component = TAG,
                                    event = "tts_utterance_start",
                                    fields = mapOf("utterance_id" to utteranceId),
                                )
                            }
                        }
                        override fun onDone(utteranceId: String?) {
                            val current = finishUtterance(utteranceId)
                            DiagnosticLogger.info(
                                component = TAG,
                                event = if (current) "tts_utterance_done" else "tts_stale_callback_ignored",
                                fields = mapOf("utterance_id" to utteranceId, "callback" to "done"),
                            )
                        }
                        @Deprecated("Deprecated in Java")
                        override fun onError(utteranceId: String?) {
                            if (finishUtterance(utteranceId)) {
                                DiagnosticLogger.error(
                                    component = TAG,
                                    event = "tts_utterance_error",
                                    fields = mapOf("utterance_id" to utteranceId),
                                )
                            }
                        }

                        override fun onError(utteranceId: String?, errorCode: Int) {
                            if (finishUtterance(utteranceId)) {
                                DiagnosticLogger.error(
                                    component = TAG,
                                    event = "tts_utterance_error",
                                    fields = mapOf("utterance_id" to utteranceId, "error_code" to errorCode),
                                )
                                _state.update { it.copy(ttsStatusMessage = "TTS không thể đọc câu hiện tại (mã $errorCode)") }
                            } else {
                                DiagnosticLogger.info(
                                    component = TAG,
                                    event = "tts_stale_callback_ignored",
                                    fields = mapOf("utterance_id" to utteranceId, "callback" to "error"),
                                )
                            }
                        }
                    })
                    setTtsSpeed(_state.value.ttsSpeed)
                } else {
                    // NẾU RƠI VÀO ĐÂY: LỖI DO HỆ THỐNG CHẶN ENGINE
                    val errorMsg = "Không thể khởi tạo bộ đọc văn bản. Mã trạng thái: $status"
                    DiagnosticLogger.error(
                        component = TAG,
                        event = "tts_init_failed",
                        message = errorMsg,
                        fields = mapOf("status" to status),
                    )
                    _state.update { it.copy(ttsStatusMessage = errorMsg) }
                    // Status = -1 (TextToSpeech.ERROR) thường là do bị ROM chặn chạy ngầm hoặc crash engine
                }
            }
        }
    }

    fun setVideo(uri: Uri, name: String) {
        stopTtsAndRestoreVolume()
        _state.update { it.copy(videoUri = uri, videoFileName = name, currentTimeMs = 0L, durationMs = 0L) }
        exoPlayer?.setMediaItem(MediaItem.fromUri(uri))
        exoPlayer?.prepare()
        syncSubIndex(0)
        DiagnosticLogger.info(
            component = TAG,
            event = "video_selected",
            fields = mapOf("source_id" to DiagnosticRedactor.stableId(uri.toString())),
        )
    }

    fun setSubtitle(uri: Uri, name: String, content: String) {
        stopTtsAndRestoreVolume()
        _state.update { it.copy(subUri = uri, subFileName = name) }
        parseSrt(content)
        syncSubIndex(exoPlayer?.currentPosition ?: 0)
    }

    fun togglePlayPause() {
        val player = exoPlayer ?: return
        if (player.isPlaying) {
            player.pause()
            stopTtsAndRestoreVolume()
        } else {
            val current = player.currentPosition
            syncSubIndex(current) // Đồng bộ lại hàng đợi phụ đề tránh đọc lại các câu cũ
            player.play()
        }
    }

    fun seekTo(timeMs: Long) {
        exoPlayer?.seekTo(timeMs)
        _state.update { it.copy(currentTimeMs = timeMs) }
        syncSubIndex(timeMs)
        updateCurrentSubtitleDisplay(timeMs)
        // Dừng TTS hiện tại và luôn khôi phục âm lượng video.
        stopTtsAndRestoreVolume()
    }

    fun setVideoVolume(vol: Float) {
        _state.update { it.copy(videoVolume = vol) }
        if (!isSpeaking || !_state.value.autoDuck) {
            exoPlayer?.volume = vol
        }
    }

    fun setAutoDuck(enabled: Boolean) {
        _state.update { it.copy(autoDuck = enabled) }
        if (isSpeaking) {
            if (enabled) {
                applyAutoDuck(true)
            } else {
                exoPlayer?.volume = _state.value.videoVolume
            }
        }
    }

    fun setTtsSpeed(speed: Float) {
        _state.update { it.copy(ttsSpeed = speed) }
        tts?.setSpeechRate(speed)
    }

    fun setTtsVolume(vol: Float) {
        _state.update { it.copy(ttsVolume = vol) }
    }

    private fun applyAutoDuck(duck: Boolean) {
        val player = exoPlayer ?: return
        if (!_state.value.autoDuck) return
        
        viewModelScope.launch(Dispatchers.Main) {
            if (duck) {
                // ducking to 20% of original volume
                player.volume = _state.value.videoVolume * 0.2f
            } else {
                player.volume = _state.value.videoVolume
            }
        }
    }

    private fun startTimeTracker() {
        timeTrackerJob?.cancel()
        timeTrackerJob = viewModelScope.launch(Dispatchers.Main) {
            while (isActive) {
                val current = exoPlayer?.currentPosition ?: 0L
                _state.update { it.copy(currentTimeMs = current) }
                
                checkSubtitleTrigger(current)
                updateCurrentSubtitleDisplay(current)
                
                delay(100)
            }
        }
    }

    private fun updateCurrentSubtitleDisplay(currentMs: Long) {
        if (subtitleList.isEmpty()) {
            _state.update { it.copy(currentSubtitleText = "") }
            return
        }
        
        // Cập nhật phụ đề đang được phát (cả khi đang pause)
        val currentSub = subtitleList.find { currentMs in it.startMs..it.endMs }
        _state.update { it.copy(currentSubtitleText = currentSub?.text ?: "") }
    }

    private fun stopTimeTracker() {
        timeTrackerJob?.cancel()
    }

    private fun syncSubIndex(currentTimeMs: Long) {
        if (subtitleList.isEmpty()) return
        var foundIdx = subtitleList.size // Default to end
        for (i in subtitleList.indices) {
            if (currentTimeMs < subtitleList[i].endMs) {
                foundIdx = i
                break
            }
        }
        currentSubIdx = foundIdx
    }

    private fun checkSubtitleTrigger(currentMs: Long) {
        if (currentSubIdx < subtitleList.size) {
            val sub = subtitleList[currentSubIdx]
            
            if (currentMs >= sub.startMs && currentMs <= sub.endMs) {
                // Đăng ký trước khi gọi engine để callback khởi động cực nhanh
                // vẫn thuộc đúng thế hệ hiện tại.
                val utteranceId = utteranceQueue.enqueue(sub.id)
                val params = android.os.Bundle().apply {
                    putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, _state.value.ttsVolume)
                }
                val result = tts?.speak(sub.text, TextToSpeech.QUEUE_ADD, params, utteranceId)
                if (result == TextToSpeech.SUCCESS) {
                    DiagnosticLogger.info(
                        component = TAG,
                        event = "tts_utterance_queued",
                        fields = mapOf(
                            "utterance_id" to utteranceId,
                            "cue_id" to sub.id,
                            "start_ms" to sub.startMs,
                            "end_ms" to sub.endMs,
                            "text_chars" to sub.text.length,
                        ),
                    )
                    currentSubIdx++
                } else {
                    finishUtterance(utteranceId)
                    DiagnosticLogger.error(
                        component = TAG,
                        event = "tts_speak_rejected",
                        fields = mapOf("utterance_id" to utteranceId, "result" to result),
                    )
                    _state.update { it.copy(ttsStatusMessage = "TTS từ chối câu phụ đề hiện tại") }
                }
            } else if (currentMs > sub.endMs + 2000) {
                // Fallback resync if it skipped
                syncSubIndex(currentMs)
            }
        }
    }

    private fun stopTtsAndRestoreVolume() {
        // Tăng thế hệ trước khi stop để mọi callback đến muộn đều vô hiệu.
        utteranceQueue.invalidate()
        runCatching { tts?.stop() }
        isSpeaking = false
        restoreVideoVolume()
        DiagnosticLogger.info(component = TAG, event = "tts_queue_invalidated")
    }

    private fun finishUtterance(utteranceId: String?): Boolean {
        return when (utteranceQueue.completeDetailed(utteranceId)) {
            UtteranceCompletion.STALE -> false
            UtteranceCompletion.CURRENT_PENDING -> true
            UtteranceCompletion.CURRENT_EMPTY -> {
                isSpeaking = false
                restoreVideoVolume()
                true
            }
        }
    }

    private fun restoreVideoVolume() {
        viewModelScope.launch(Dispatchers.Main) {
            exoPlayer?.volume = _state.value.videoVolume
        }
    }

    // Extraction State Handlers
    fun startExtraction() {
        _state.update { it.copy(isExtracting = true, extractProgress = "Đang kiểm tra...", extractOutputPath = "") }
    }
    fun updateExtractionProgress(msg: String) {
        _state.update { it.copy(extractProgress = msg) }
    }
    fun finishExtraction(success: Boolean, outPath: String, msg: String) {
        _state.update {
            it.copy(
                isExtracting = false,
                extractOutputPath = outPath,
                extractProgress = msg
            )
        }
    }

    fun clearAll() {
        exoPlayer?.stop()
        exoPlayer?.clearMediaItems()
        stopTtsAndRestoreVolume()
        subtitleList = emptyList()
        currentSubIdx = 0
        _state.update { SubScreenState() }
    }

    override fun onCleared() {
        timeTrackerJob?.cancel()
        timeTrackerJob = null

        stopTtsAndRestoreVolume()
        exoPlayer?.release()
        exoPlayer = null

        tts?.shutdown()
        tts = null
        super.onCleared()
    }

    companion object {
        private const val TAG = "SubViewModel"
    }
}
