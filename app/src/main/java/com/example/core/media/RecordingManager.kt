package com.example.core.media

import android.content.Context
import android.content.Intent
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.os.Build
import androidx.core.content.ContextCompat
import com.example.core.SettingsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

object RecordingManager {
    private var mediaRecorder: MediaRecorder? = null
    private var usingWav = false
    
    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private val _isPaused = MutableStateFlow(false)
    val isPaused: StateFlow<Boolean> = _isPaused.asStateFlow()

    private val _recordingTimeSec = MutableStateFlow(0)
    val recordingTimeSec: StateFlow<Int> = _recordingTimeSec.asStateFlow()

    private val _outputFile = MutableStateFlow<File?>(null)
    val outputFile: StateFlow<File?> = _outputFile.asStateFlow()

    private val _hasUnsavedFile = MutableStateFlow(false)
    val hasUnsavedFile: StateFlow<Boolean> = _hasUnsavedFile.asStateFlow()

    private var timerJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Main)

    fun startRecording(context: Context) {
        if (_isRecording.value) return
        usingWav = false
        try {
            val dir = File(context.cacheDir, "recordings").apply { mkdirs() }
            val file = File(dir, "record_${System.currentTimeMillis()}.m4a")
            
            val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                MediaRecorder()
            }
            
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC)
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            recorder.setAudioEncodingBitRate(SettingsManager.getAudioBitrateInt(context))
            recorder.setAudioSamplingRate(48000)
            recorder.setOutputFile(file.absolutePath)
            
            recorder.prepare()
            recorder.start()
            
            mediaRecorder = recorder
            _outputFile.value = file
            _isRecording.value = true
            _isPaused.value = false
            _hasUnsavedFile.value = false
            _recordingTimeSec.value = 0
            
            startTimer()
            startForegroundService(context, false)
        } catch (e: Exception) {
            e.printStackTrace()
            stopRecording(context)
        }
    }

    fun startInternalRecording(context: Context, mediaProjection: MediaProjection) {
        if (_isRecording.value) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        
        usingWav = true
        try {
            val dir = File(context.cacheDir, "recordings").apply { mkdirs() }
            val file = File(dir, "record_${System.currentTimeMillis()}.wav")
            
            WavRecorder.startRecording(mediaProjection, file)
            
            _outputFile.value = file
            _isRecording.value = true
            _isPaused.value = false
            _hasUnsavedFile.value = false
            _recordingTimeSec.value = 0
            
            startTimer()
            startForegroundService(context, true)
        } catch (e: Exception) {
            e.printStackTrace()
            stopRecording(context)
        }
    }

    private fun startTimer() {
        timerJob?.cancel()
        timerJob = scope.launch {
            while (true) {
                delay(1000)
                if (!_isPaused.value) {
                    _recordingTimeSec.value++
                }
            }
        }
    }

    private fun startForegroundService(context: Context, isInternal: Boolean) {
        val serviceIntent = Intent(context, RecordingService::class.java)
        serviceIntent.putExtra("is_internal", isInternal)
        ContextCompat.startForegroundService(context, serviceIntent)
    }

    fun pauseRecording() {
        if (!_isRecording.value || _isPaused.value) return
        try {
            if (usingWav) {
                WavRecorder.pauseRecording()
            } else {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    mediaRecorder?.pause()
                }
            }
            _isPaused.value = true
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun resumeRecording() {
        if (!_isRecording.value || !_isPaused.value) return
        try {
            if (usingWav) {
                WavRecorder.resumeRecording()
            } else {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    mediaRecorder?.resume()
                }
            }
            _isPaused.value = false
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun stopRecording(context: Context) {
        if (!_isRecording.value) return
        try {
            if (usingWav) {
                WavRecorder.stopRecording()
            } else {
                mediaRecorder?.stop()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            if (!usingWav) {
                mediaRecorder?.release()
                mediaRecorder = null
            }
            _isRecording.value = false
            _isPaused.value = false
            _hasUnsavedFile.value = true
            timerJob?.cancel()
            
            // Stop Foreground Service
            val serviceIntent = Intent(context, RecordingService::class.java)
            context.stopService(serviceIntent)
        }
    }
    
    fun clearOutputFile() {
        _outputFile.value = null
        _hasUnsavedFile.value = false
        _recordingTimeSec.value = 0
    }
}
