package com.example.ui.screens

import android.content.Context
import android.net.Uri
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
import java.util.UUID

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
    private var originalVolumeBeforeDuck = 1.0f

    private var timeTrackerJob: Job? = null
    
    /**
     * Parse SRT content into a list of SubtitleItem
     */
    fun parseSrt(content: String) {
        val items = mutableListOf<SubtitleItem>()
        val lines = content.replace("\r\n", "\n").split("\n").map { it.trim() }
        var i = 0
        var idCounter = 1
        
        while (i < lines.size) {
            val line = lines[i]
            if (line.isEmpty() || line.startsWith("WEBVTT") || line.startsWith("Kind:")) {
                i++
                continue
            }
            
            // Tìm dòng Timecode
            if (line.contains("-->")) {
                val timecodes = line.split("-->").map { it.trim() }
                if (timecodes.size >= 2) {
                    val start = parseTimeMs(timecodes[0])
                    val end = parseTimeMs(timecodes[1])
                    i++
                    
                    val textBuilder = java.lang.StringBuilder()
                    while (i < lines.size && lines[i].isNotEmpty() && !lines[i].contains("-->")) {
                        // Skip if line is just the next block's index and next line is timecode
                        val isNextLineTimecode = i + 1 < lines.size && lines[i + 1].contains("-->")
                        if (isNextLineTimecode && lines[i].all { it.isDigit() }) {
                            break
                        }
                        
                        if (textBuilder.isNotEmpty()) textBuilder.append(" ")
                        textBuilder.append(lines[i])
                        i++
                    }
                    if (start != null && end != null) {
                        // Clean up HTML tags and entities before passing to TTS
                        val cleanText = textBuilder.toString()
                            .replace(Regex("<[^>]*>"), "")
                            .replace(Regex("\\{.*?\\}"), "")
                            .replace("&amp;", "&")
                            .replace("&lt;", "<")
                            .replace("&gt;", ">")
                            .replace("&quot;", "\"")
                            .replace("&#39;", "'")
                        items.add(SubtitleItem(idCounter++, start, end, cleanText))
                    }
                } else {
                    i++
                }
            } else {
                i++
            }
        }
        subtitleList = items
        currentSubIdx = 0
        _state.update { it.copy(subCount = items.size, currentSubtitleText = "") }
    }

    private fun parseTimeMs(timeStr: String): Long? {
        try {
            val clean = timeStr.replace(",", ".")
            val parts = clean.split(":")
            if (parts.size >= 2) {
                val hour = if (parts.size == 3) parts[0].toLong() else 0L
                val minStr = if (parts.size == 3) parts[1] else parts[0]
                val secParts = if (parts.size == 3) parts[2].split(".") else parts[1].split(".")
                
                val min = minStr.toLong()
                val sec = secParts[0].toLong()
                val ms = if (secParts.size > 1) {
                    secParts[1].padEnd(3, '0').substring(0, 3).toLong()
                } else 0L
                
                return (hour * 3600 + min * 60 + sec) * 1000 + ms
            }
        } catch (e: Exception) {
            Log.e("SubViewModel", "Lỗi parse time: $timeStr", e)
        }
        return null
    }

    fun initPlayer(context: Context) {
        val appContext = context.applicationContext
        if (exoPlayer == null) {
            exoPlayer = ExoPlayer.Builder(appContext).build().apply {
                addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        _state.update { it.copy(isPlayerReady = playbackState == Player.STATE_READY || playbackState == Player.STATE_BUFFERING) }
                        if (playbackState == Player.STATE_READY) {
                            _state.update { it.copy(durationMs = duration.coerceAtLeast(0)) }
                        }
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
                        Log.e("SubViewModel", errorMsg)
                        _state.update { it.copy(ttsStatusMessage = errorMsg) }
                    } else {
                        Log.d("SubViewModel", "TTS Init OK. Sẵn sàng đọc!")
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
                            isSpeaking = true
                            applyAutoDuck(true)
                        }
                        override fun onDone(utteranceId: String?) {
                            isSpeaking = false
                            applyAutoDuck(false)
                        }
                        @Deprecated("Deprecated in Java")
                        override fun onError(utteranceId: String?) {
                            isSpeaking = false
                            applyAutoDuck(false)
                        }
                    })
                    setTtsSpeed(_state.value.ttsSpeed)
                } else {
                    // NẾU RƠI VÀO ĐÂY: LỖI DO HỆ THỐNG CHẶN ENGINE
                    val errorMsg = "LỖI NẶNG: Không thể khởi tạo TextToSpeech. Mã lỗi (Status): $status"
                    Log.e("SubViewModel", errorMsg)
                    _state.update { it.copy(ttsStatusMessage = errorMsg) }
                    // Status = -1 (TextToSpeech.ERROR) thường là do bị ROM chặn chạy ngầm hoặc crash engine
                }
            }
        }
    }

    fun setVideo(uri: Uri, name: String) {
        _state.update { it.copy(videoUri = uri, videoFileName = name) }
        exoPlayer?.setMediaItem(MediaItem.fromUri(uri))
        exoPlayer?.prepare()
        syncSubIndex(0)
    }

    fun setSubtitle(uri: Uri, name: String, content: String) {
        _state.update { it.copy(subUri = uri, subFileName = name) }
        parseSrt(content)
        syncSubIndex(exoPlayer?.currentPosition ?: 0)
    }

    fun togglePlayPause() {
        val player = exoPlayer ?: return
        if (player.isPlaying) {
            player.pause()
            tts?.stop() // Dừng TTS ngay lập tức khi người dùng tạm dừng video
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
        // Dừng tts hiện tại và xoá hàng đợi
        tts?.stop()
    }

    fun setVideoVolume(vol: Float) {
        _state.update { it.copy(videoVolume = vol) }
        if (!isSpeaking || !_state.value.autoDuck) {
            exoPlayer?.volume = vol
        }
        originalVolumeBeforeDuck = vol
    }

    fun setAutoDuck(enabled: Boolean) {
        _state.update { it.copy(autoDuck = enabled) }
        if (!enabled && isSpeaking) {
            // Restore immediately if user turns off ducking while speaking
            exoPlayer?.volume = _state.value.videoVolume
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
                // It's time to speak!
                val utteranceId = UUID.randomUUID().toString()
                val params = android.os.Bundle().apply {
                    putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, _state.value.ttsVolume)
                }
                tts?.speak(sub.text, TextToSpeech.QUEUE_ADD, params, utteranceId)
                currentSubIdx++
            } else if (currentMs > sub.endMs + 2000) {
                // Fallback resync if it skipped
                syncSubIndex(currentMs)
            }
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
        tts?.stop()
        subtitleList = emptyList()
        currentSubIdx = 0
        _state.update { SubScreenState() }
    }

    override fun onCleared() {
        super.onCleared()
        timeTrackerJob?.cancel()
        timeTrackerJob = null
        
        exoPlayer?.release()
        exoPlayer = null
        
        tts?.stop()
        tts?.shutdown()
        tts = null
    }
}
