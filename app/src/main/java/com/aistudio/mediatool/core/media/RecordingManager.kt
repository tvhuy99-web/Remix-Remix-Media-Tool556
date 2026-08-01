package com.aistudio.mediatool.core.media

import android.content.Context
import android.content.Intent
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.os.Build
import androidx.core.content.ContextCompat
import com.aistudio.mediatool.core.PersistentTaskState
import com.aistudio.mediatool.core.PersistentTaskStatus
import com.aistudio.mediatool.core.SettingsManager
import com.aistudio.mediatool.core.TaskStateStore
import com.aistudio.mediatool.core.diagnostics.DiagnosticLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

object RecordingManager {
    private var mediaRecorder: MediaRecorder? = null
    private var usingWav = false
    private var timerJob: Job? = null
    private var taskId: String? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()
    private val _isStarting = MutableStateFlow(false)
    val isStarting: StateFlow<Boolean> = _isStarting.asStateFlow()
    private val _isFinalizing = MutableStateFlow(false)
    val isFinalizing: StateFlow<Boolean> = _isFinalizing.asStateFlow()
    private val _isPaused = MutableStateFlow(false)
    val isPaused: StateFlow<Boolean> = _isPaused.asStateFlow()
    private val _recordingTimeSec = MutableStateFlow(0)
    val recordingTimeSec: StateFlow<Int> = _recordingTimeSec.asStateFlow()
    private val _outputFile = MutableStateFlow<File?>(null)
    val outputFile: StateFlow<File?> = _outputFile.asStateFlow()
    private val _hasUnsavedFile = MutableStateFlow(false)
    val hasUnsavedFile: StateFlow<Boolean> = _hasUnsavedFile.asStateFlow()
    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    fun restore(context: Context) {
        val state = TaskStateStore.load(context, TASK_TYPE) ?: return
        if (state.type != TASK_TYPE) return
        val file = state.outputPaths.firstOrNull()?.let(::File)
        if (state.status == PersistentTaskStatus.SUCCESS && file?.isFile == true && file.length() > 0L) {
            _outputFile.value = file
            _hasUnsavedFile.value = true
            _recordingTimeSec.value = 0
        } else if (state.status == PersistentTaskStatus.INTERRUPTED) {
            _lastError.value = "Phiên ghi trước đã bị hệ thống dừng trước khi hoàn tất"
        }
        DiagnosticLogger.info(
            component = TAG,
            event = "state_restored",
            sessionId = state.taskId,
            fields = mapOf(
                "status" to state.status,
                "output_present" to (file?.isFile == true && file.length() > 0L),
            ),
        )
    }

    fun startRecording(context: Context) {
        if (!canStartNewRecording()) return
        prepareStartingState(context, captureMode = "microphone")
        try {
            ContextCompat.startForegroundService(
                context,
                Intent(context, RecordingService::class.java).setAction(RecordingService.ACTION_START_MIC),
            )
        } catch (error: Exception) {
            onCaptureFailed(context, error)
        }
    }

    fun startInternalRecording(context: Context, resultCode: Int, permissionData: Intent) {
        if (!canStartNewRecording() || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        prepareStartingState(context, captureMode = "internal_audio")
        try {
            ContextCompat.startForegroundService(
                context,
                Intent(context, RecordingService::class.java)
                    .setAction(RecordingService.ACTION_START_INTERNAL)
                    .putExtra(RecordingService.EXTRA_RESULT_CODE, resultCode)
                    .putExtra(RecordingService.EXTRA_PERMISSION_DATA, permissionData),
            )
        } catch (error: Exception) {
            onCaptureFailed(context, error)
        }
    }

    private fun canStartNewRecording(): Boolean {
        if (_isRecording.value || _isStarting.value || _isFinalizing.value) return false
        if (_hasUnsavedFile.value) {
            _lastError.value = "Hãy lưu hoặc xóa bản ghi hiện tại trước khi bắt đầu bản mới"
            return false
        }
        return true
    }

    private fun prepareStartingState(context: Context, captureMode: String) {
        taskId = UUID.randomUUID().toString()
        _isStarting.value = true
        _isFinalizing.value = false
        _lastError.value = null
        _recordingTimeSec.value = 0
        TaskStateStore.save(
            context,
            PersistentTaskState(taskId!!, TASK_TYPE, PersistentTaskStatus.RUNNING, message = "Đang khởi tạo ghi âm"),
        )
        DiagnosticLogger.info(
            component = TAG,
            event = "recording_start_requested",
            sessionId = taskId,
            fields = mapOf("capture_mode" to captureMode),
        )
    }

    @Synchronized
    internal fun beginMicrophoneCapture(context: Context) {
        if (_isRecording.value) return
        usingWav = false
        val target = newRecordingFile(context, "m4a")
        var recorder: MediaRecorder? = null
        try {
            val activeRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION") MediaRecorder()
            }
            recorder = activeRecorder
            activeRecorder.setAudioSource(MediaRecorder.AudioSource.MIC)
            activeRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            activeRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            activeRecorder.setAudioEncodingBitRate(SettingsManager.getAudioBitrateInt(context))
            activeRecorder.setAudioSamplingRate(48_000)
            activeRecorder.setOutputFile(target.absolutePath)
            activeRecorder.prepare()
            activeRecorder.start()
            mediaRecorder = activeRecorder
            onCaptureStarted(context, target)
        } catch (error: Exception) {
            if (mediaRecorder === recorder) mediaRecorder = null
            runCatching { recorder?.reset() }
            runCatching { recorder?.release() }
            target.delete()
            onCaptureFailed(context, error)
        }
    }

    @Synchronized
    internal fun beginInternalCapture(context: Context, mediaProjection: MediaProjection) {
        if (_isRecording.value || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        usingWav = true
        val target = newRecordingFile(context, "wav")
        try {
            WavRecorder.startRecording(mediaProjection, target)
            onCaptureStarted(context, target)
        } catch (error: Exception) {
            runCatching { WavRecorder.stopRecording() }
            target.delete()
            onCaptureFailed(context, error)
        }
    }

    private fun newRecordingFile(context: Context, extension: String): File =
        File(File(context.cacheDir, "recordings").apply { mkdirs() }, "record_${System.currentTimeMillis()}.$extension")

    private fun onCaptureStarted(context: Context, target: File) {
        _outputFile.value = target
        _isStarting.value = false
        _isRecording.value = true
        _isPaused.value = false
        _hasUnsavedFile.value = false
        TaskStateStore.save(
            context,
            PersistentTaskState(taskId ?: UUID.randomUUID().toString(), TASK_TYPE, PersistentTaskStatus.RUNNING, outputPaths = listOf(target.absolutePath)),
        )
        startTimer()
        DiagnosticLogger.info(
            component = TAG,
            event = "recording_started",
            sessionId = taskId,
            fields = mapOf(
                "capture_mode" to if (usingWav) "internal_audio" else "microphone",
                "format" to target.extension,
            ),
        )
    }

    internal fun onCaptureFailed(context: Context, error: Throwable) {
        runCatching { mediaRecorder?.release() }
        mediaRecorder = null
        runCatching { WavRecorder.stopRecording() }
        _isStarting.value = false
        _isFinalizing.value = false
        _isRecording.value = false
        _isPaused.value = false
        _hasUnsavedFile.value = false
        _outputFile.value?.delete()
        _outputFile.value = null
        _lastError.value = error.message ?: "Không thể bắt đầu ghi âm"
        timerJob?.cancel()
        TaskStateStore.save(
            context,
            PersistentTaskState(taskId ?: "recording", TASK_TYPE, PersistentTaskStatus.FAILED, message = _lastError.value),
        )
        DiagnosticLogger.error(
            component = TAG,
            event = "recording_start_failed",
            sessionId = taskId,
            message = _lastError.value,
            fields = mapOf("capture_mode" to if (usingWav) "internal_audio" else "microphone"),
            error = error,
        )
    }

    fun pauseRecording() {
        if (!_isRecording.value || _isPaused.value) return
        runCatching {
            if (usingWav) WavRecorder.pauseRecording()
            else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) mediaRecorder?.pause()
            _isPaused.value = true
        }.onFailure { error ->
            _lastError.value = error.message
            DiagnosticLogger.error(
                component = TAG,
                event = "recording_pause_failed",
                sessionId = taskId,
                message = error.message,
                error = error,
            )
        }
    }

    fun resumeRecording() {
        if (!_isRecording.value || !_isPaused.value) return
        runCatching {
            if (usingWav) WavRecorder.resumeRecording()
            else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) mediaRecorder?.resume()
            _isPaused.value = false
        }.onFailure { error ->
            _lastError.value = error.message
            DiagnosticLogger.error(
                component = TAG,
                event = "recording_resume_failed",
                sessionId = taskId,
                message = error.message,
                error = error,
            )
        }
    }

    fun stopRecording(context: Context) {
        if (!_isRecording.value && !_isStarting.value) return
        context.startService(Intent(context, RecordingService::class.java).setAction(RecordingService.ACTION_STOP))
    }

    @Synchronized
    internal fun finishCapture(context: Context) {
        val finishStartedAt = android.os.SystemClock.elapsedRealtime()
        _isFinalizing.value = true
        _isStarting.value = false
        val success = runCatching {
            if (usingWav) {
                val stopped = WavRecorder.stopRecording()
                if (!stopped && _lastError.value == null) {
                    _lastError.value = WavRecorder.lastError?.message
                        ?: "Không thể hoàn tất tệp WAV trong thời gian cho phép"
                }
                stopped
            } else {
                mediaRecorder?.stop()
                true
            }
        }.getOrElse {
            _lastError.value = it.message ?: "Không thể hoàn tất bản ghi"
            false
        }
        runCatching { mediaRecorder?.release() }
        mediaRecorder = null
        timerJob?.cancel()
        _isRecording.value = false
        _isPaused.value = false
        val file = _outputFile.value
        val minimumBytes = if (usingWav) WavHeader.HEADER_SIZE.toLong() else 0L
        val fileIsValid = file?.isFile == true && file.length() > minimumBytes
        _hasUnsavedFile.value = success && fileIsValid
        _isFinalizing.value = false
        if (!success || !fileIsValid) {
            file?.delete()
            _outputFile.value = null
            if (_lastError.value == null) _lastError.value = "Bản ghi không chứa dữ liệu hợp lệ"
            TaskStateStore.save(
                context,
                PersistentTaskState(taskId ?: "recording", TASK_TYPE, PersistentTaskStatus.FAILED, message = _lastError.value),
            )
            DiagnosticLogger.error(
                component = TAG,
                event = "recording_finish_failed",
                sessionId = taskId,
                message = _lastError.value,
                fields = mapOf(
                    "capture_mode" to if (usingWav) "internal_audio" else "microphone",
                    "elapsed_ms" to android.os.SystemClock.elapsedRealtime() - finishStartedAt,
                    "file_valid" to fileIsValid,
                ),
                error = if (usingWav) WavRecorder.lastError else null,
            )
        } else {
            val completedFile = requireNotNull(file)
            TaskStateStore.save(
                context,
                PersistentTaskState(
                    taskId ?: "recording",
                    TASK_TYPE,
                    PersistentTaskStatus.SUCCESS,
                    progress = 1f,
                    outputPaths = listOf(completedFile.absolutePath),
                ),
            )
            DiagnosticLogger.info(
                component = TAG,
                event = "recording_success",
                sessionId = taskId,
                fields = mapOf(
                    "capture_mode" to if (usingWav) "internal_audio" else "microphone",
                    "format" to completedFile.extension,
                    "bytes" to completedFile.length(),
                    "duration_seconds" to _recordingTimeSec.value,
                    "elapsed_ms" to android.os.SystemClock.elapsedRealtime() - finishStartedAt,
                ),
            )
        }
    }

    private fun startTimer() {
        timerJob?.cancel()
        timerJob = scope.launch {
            while (_isRecording.value) {
                delay(1_000)
                if (!_isPaused.value && _isRecording.value) _recordingTimeSec.value++
            }
        }
    }

    fun clearOutputFile(context: Context? = null, deleteFile: Boolean = true) {
        val removed = if (deleteFile) _outputFile.value?.let { !it.exists() || it.delete() } else null
        _outputFile.value = null
        _hasUnsavedFile.value = false
        _recordingTimeSec.value = 0
        _lastError.value = null
        context?.let { TaskStateStore.clear(it, TASK_TYPE) }
        DiagnosticLogger.info(
            component = TAG,
            event = "recording_output_cleared",
            sessionId = taskId,
            fields = mapOf("delete_requested" to deleteFile, "removed" to removed),
        )
    }

    private const val TASK_TYPE = "recording"
    private const val TAG = "RecordingManager"
}
