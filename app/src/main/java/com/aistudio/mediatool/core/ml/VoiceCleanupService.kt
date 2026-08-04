package com.aistudio.mediatool.core.ml

import android.app.ActivityManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.StatFs
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import com.aistudio.mediatool.MainActivity
import com.aistudio.mediatool.R
import com.aistudio.mediatool.core.PersistentTaskState
import com.aistudio.mediatool.core.PersistentTaskStatus
import com.aistudio.mediatool.core.TaskStateStore
import com.aistudio.mediatool.core.diagnostics.DiagnosticLogger
import com.aistudio.mediatool.core.diagnostics.DiagnosticRedactor
import com.aistudio.mediatool.core.diagnostics.ProcessExitDiagnostics
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class VoiceCleanupService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val taskGate = VoiceCleanupTaskGate()
    private var processingJob: Job? = null
    private var processor: VoiceCleanupProcessor? = null
    private var currentTaskId: String? = null
    private var currentTaskToken: Long? = null
    private var processingWakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        restorePersistedState(this)
        DiagnosticLogger.info(TAG, "service_created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startProcessing(intent)
            ACTION_STOP -> stopProcessing("Đã hủy làm sạch giọng", PersistentTaskStatus.CANCELLED)
            else -> stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun startProcessing(intent: Intent) {
        if (currentTaskToken != null || processingJob != null) {
            DiagnosticLogger.warn(TAG, "duplicate_start_ignored", sessionId = currentTaskId)
            return
        }
        val uri = intent.getStringExtra(EXTRA_URI)?.let(Uri::parse)
        val modelFile = intent.getStringExtra(EXTRA_MODEL_FILE)?.let(::File)
        if (uri == null || modelFile?.isFile != true || modelFile.length() <= 0L) {
            failAndStop("Thiếu tệp đầu vào hoặc model MossFormer2")
            return
        }
        val config = runCatching {
            VoiceCleanupConfig(
                loudnessMode = VoiceCleanupLoudnessMode.fromName(
                    intent.getStringExtra(EXTRA_LOUDNESS_MODE),
                ),
                targetLufs = intent.getFloatExtra(EXTRA_TARGET_LUFS, -16f),
                outputGainDb = intent.getFloatExtra(EXTRA_OUTPUT_GAIN_DB, 0f),
                limiterEnabled = intent.getBooleanExtra(EXTRA_LIMITER_ENABLED, true),
                limiterCeilingDb = intent.getFloatExtra(EXTRA_LIMITER_CEILING_DB, -1f),
            )
        }.getOrElse { error ->
            failAndStop(error.message ?: "Thiết lập âm lượng không hợp lệ")
            return
        }
        val taskToken = taskGate.tryStart()
        if (taskToken == null) {
            DiagnosticLogger.warn(TAG, "duplicate_start_ignored", sessionId = currentTaskId)
            return
        }
        currentTaskToken = taskToken
        currentTaskId = UUID.randomUUID().toString()

        try {
            startAsForeground()
            acquireProcessingWakeLock()
        } catch (error: Exception) {
            DiagnosticLogger.error(TAG, "foreground_start_failed", currentTaskId, error.message, error = error)
            failAndStop(
                "Android không cho phép bắt đầu xử lý nền: ${error.message ?: "không xác định"}",
                taskToken,
            )
            return
        }

        val taskId = checkNotNull(currentTaskId)
        val sourceId = DiagnosticRedactor.stableId(uri.toString())
        val startedAt = SystemClock.elapsedRealtime()
        _isProcessing.value = true
        _errorMsg.value = null
        _cleanupState.value = VoiceCleanupState.Progress(0f, "Đang kiểm tra model và tài nguyên")
        saveTask(PersistentTaskStatus.RUNNING, 0f, "Đang kiểm tra model và tài nguyên")
        ProcessExitDiagnostics.checkpoint(
            context = this,
            taskType = VoiceCleanupTask.TYPE,
            taskId = taskId,
            phase = "preflight",
            progress = 0f,
            modelId = VoiceCleanupModelRegistry.MOSSFORMER2_ID,
        )

        val job = serviceScope.launch(start = CoroutineStart.LAZY) {
            try {
                val durationMs = runPreflight(uri)
                val descriptor = VoiceCleanupModelRegistry.mossFormer2
                require(ModelDownloader(this@VoiceCleanupService).isModelFileValid(modelFile, descriptor.modelSpec)) {
                    "Model MossFormer2 không đúng dung lượng hoặc SHA-256; hãy tải lại model"
                }
                DiagnosticLogger.info(
                    TAG,
                    "model_validated",
                    taskId,
                    fields = mapOf(
                        "model_id" to descriptor.id,
                        "duration_ms" to durationMs,
                        "model_bytes" to modelFile.length(),
                        "source_id" to sourceId,
                    ) + config.diagnosticFields(),
                )
                val activeProcessor = VoiceCleanupProcessor(
                    context = this@VoiceCleanupService,
                    modelFile = modelFile,
                    taskId = taskId,
                    config = config,
                )
                processor = activeProcessor
                var lastProgressBucket = -1
                activeProcessor.cleanup(uri).collect { state ->
                    if (!taskGate.isCurrent(taskToken)) return@collect
                    _cleanupState.value = state
                    when (state) {
                        is VoiceCleanupState.Progress -> {
                            val progress = state.value.coerceIn(0f, 1f)
                            updateNotification(progress, state.phase)
                            saveTask(PersistentTaskStatus.RUNNING, progress, state.phase)
                            val bucket = (progress * 10f).toInt()
                            if (bucket > lastProgressBucket) {
                                lastProgressBucket = bucket
                                DiagnosticLogger.info(
                                    TAG,
                                    "task_progress",
                                    taskId,
                                    fields = mapOf(
                                        "percent" to bucket * 10,
                                        "phase" to state.phase,
                                        "model_id" to descriptor.id,
                                    ),
                                )
                            }
                        }

                        is VoiceCleanupState.Success -> {
                            saveTask(
                                PersistentTaskStatus.SUCCESS,
                                1f,
                                "Làm sạch giọng hoàn tất",
                                listOf(state.outputFile.absolutePath),
                            )
                            DiagnosticLogger.info(
                                TAG,
                                "task_success",
                                taskId,
                                fields = mapOf(
                                    "model_id" to descriptor.id,
                                    "source_id" to sourceId,
                                    "output_bytes" to state.outputFile.length(),
                                    "elapsed_ms" to SystemClock.elapsedRealtime() - startedAt,
                                ),
                            )
                            finishService(taskToken)
                        }
                    }
                }
            } catch (_: CancellationException) {
                if (taskGate.isRunning(taskToken)) {
                    _errorMsg.value = "Đã hủy làm sạch giọng"
                    saveTask(PersistentTaskStatus.CANCELLED, message = _errorMsg.value)
                    finishService(taskToken)
                }
            } catch (error: Throwable) {
                if (taskGate.isCurrent(taskToken)) {
                    val message = error.message ?: "Không thể làm sạch giọng"
                    DiagnosticLogger.error(
                        TAG,
                        "task_failed",
                        taskId,
                        message,
                        fields = mapOf(
                            "source_id" to sourceId,
                            "model_id" to VoiceCleanupModelRegistry.MOSSFORMER2_ID,
                            "elapsed_ms" to SystemClock.elapsedRealtime() - startedAt,
                            "out_of_memory" to (error is OutOfMemoryError),
                        ),
                        error = error,
                    )
                    _errorMsg.value = message
                    saveTask(PersistentTaskStatus.FAILED, message = message)
                    finishService(taskToken)
                }
            } finally {
                releaseTask(taskToken)
            }
        }
        processingJob = job
        job.start()
    }

    private fun runPreflight(uri: Uri): Long {
        val retriever = MediaMetadataRetriever()
        val durationMs = try {
            retriever.setDataSource(this, uri)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
        } finally {
            retriever.release()
        } ?: error("Không đọc được thời lượng tệp đầu vào")
        require(durationMs > 0L) { "Tệp đầu vào không có thời lượng hợp lệ" }
        require(durationMs <= MAX_DURATION_MS) { "Tệp dài quá 3 giờ; hãy cắt nhỏ trước khi xử lý" }

        val samples = durationMs * MossFormer2Dsp.SAMPLE_RATE / 1_000L
        val recommendedStorage = samples * Float.SIZE_BYTES * 2L + STORAGE_RESERVE_BYTES
        val freeBytes = StatFs(cacheDir.absolutePath).availableBytes
        val memoryInfo = ActivityManager.MemoryInfo()
        getSystemService(ActivityManager::class.java).getMemoryInfo(memoryInfo)
        DiagnosticLogger.info(
            TAG,
            "preflight_snapshot",
            currentTaskId,
            fields = mapOf(
                "duration_ms" to durationMs,
                "required_storage_bytes" to recommendedStorage,
                "available_storage_bytes" to freeBytes,
                "available_ram_bytes" to memoryInfo.availMem,
                "low_memory" to memoryInfo.lowMemory,
            ),
        )
        require(freeBytes >= recommendedStorage) {
            "Không đủ dung lượng tạm. Cần khoảng ${formatMb(recommendedStorage)}, còn ${formatMb(freeBytes)}"
        }
        require(!memoryInfo.lowMemory && memoryInfo.availMem >= MIN_AVAILABLE_RAM_BYTES) {
            "MossFormer2 cần khoảng 768 MB RAM trống. Hãy đóng ứng dụng khác rồi thử lại."
        }
        return durationMs
    }

    private fun acquireProcessingWakeLock() {
        if (processingWakeLock?.isHeld == true) return
        processingWakeLock = getSystemService(PowerManager::class.java).newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "$packageName:MossFormer2Processing",
        ).apply {
            setReferenceCounted(false)
            acquire(WAKE_LOCK_TIMEOUT_MS)
        }
        DiagnosticLogger.info(TAG, "wake_lock_acquired", currentTaskId)
    }

    private fun releaseProcessingWakeLock() {
        val lock = processingWakeLock ?: return
        processingWakeLock = null
        if (lock.isHeld) {
            runCatching(lock::release)
            DiagnosticLogger.info(TAG, "wake_lock_released", currentTaskId)
        }
    }

    private fun stopProcessing(message: String, status: PersistentTaskStatus) {
        val taskToken = currentTaskToken ?: return
        if (!taskGate.beginStop(taskToken)) return
        processor?.cancel()
        processingJob?.cancel()
        _errorMsg.value = message
        saveTask(status, message = message)
        finishService(taskToken)
    }

    private fun failAndStop(message: String, taskToken: Long? = currentTaskToken) {
        _errorMsg.value = message
        saveTask(PersistentTaskStatus.FAILED, message = message)
        if (taskToken != null) {
            finishService(taskToken)
            if (processingJob == null) releaseTask(taskToken)
        } else {
            releaseProcessingWakeLock()
            _isProcessing.value = false
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun finishService(taskToken: Long) {
        if (!taskGate.isCurrent(taskToken)) return
        taskGate.beginStop(taskToken)
        currentTaskId?.let { ProcessExitDiagnostics.finish(this, VoiceCleanupTask.TYPE, it) }
        releaseProcessingWakeLock()
        _isProcessing.value = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun releaseTask(taskToken: Long) {
        if (!taskGate.finish(taskToken)) return
        processor = null
        processingJob = null
        currentTaskId = null
        currentTaskToken = null
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        stopProcessing("Android đã hết thời gian cho phép xử lý media nền", PersistentTaskStatus.INTERRUPTED)
    }

    private fun startAsForeground() {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val cancel = PendingIntent.getService(
            this,
            3,
            Intent(this, VoiceCleanupService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Làm sạch giọng")
            .setContentText("Đang chuẩn bị MossFormer2")
            .setSmallIcon(R.drawable.ic_notification_ai)
            .setContentIntent(openApp)
            .addAction(R.drawable.ic_notification_ai, "Hủy", cancel)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(progress: Float, phase: String) {
        val percent = (progress.coerceIn(0f, 1f) * 100f).toInt()
        val cancel = PendingIntent.getService(
            this,
            3,
            Intent(this, VoiceCleanupService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Làm sạch giọng")
            .setContentText("$phase: $percent%")
            .setProgress(100, percent, false)
            .setSmallIcon(R.drawable.ic_notification_ai)
            .addAction(R.drawable.ic_notification_ai, "Hủy", cancel)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification)
    }

    private fun saveTask(
        status: PersistentTaskStatus,
        progress: Float = 0f,
        message: String? = null,
        outputs: List<String> = emptyList(),
    ) {
        TaskStateStore.save(
            this,
            PersistentTaskState(
                taskId = currentTaskId ?: VoiceCleanupTask.TYPE,
                type = VoiceCleanupTask.TYPE,
                status = status,
                progress = progress,
                message = message,
                outputPaths = outputs,
            ),
        )
    }

    override fun onDestroy() {
        processor?.cancel()
        processingJob?.cancel()
        releaseProcessingWakeLock()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Làm sạch giọng", NotificationManager.IMPORTANCE_LOW),
            )
        }
    }

    private fun formatMb(bytes: Long): String = "${bytes / (1024L * 1024L)} MB"

    companion object {
        private const val TAG = "VoiceCleanupService"
        const val CHANNEL_ID = "voice_cleanup_service_channel"
        const val NOTIFICATION_ID = 3
        const val ACTION_START = "com.aistudio.mediatool.action.START_VOICE_CLEANUP"
        const val ACTION_STOP = "com.aistudio.mediatool.action.STOP_VOICE_CLEANUP"
        const val EXTRA_URI = "extra_uri"
        const val EXTRA_MODEL_FILE = "extra_model_file"
        const val EXTRA_LOUDNESS_MODE = "extra_loudness_mode"
        const val EXTRA_TARGET_LUFS = "extra_target_lufs"
        const val EXTRA_OUTPUT_GAIN_DB = "extra_output_gain_db"
        const val EXTRA_LIMITER_ENABLED = "extra_limiter_enabled"
        const val EXTRA_LIMITER_CEILING_DB = "extra_limiter_ceiling_db"
        private const val MAX_DURATION_MS = 3L * 60L * 60L * 1_000L
        private const val STORAGE_RESERVE_BYTES = 256L * 1024L * 1024L
        private const val MIN_AVAILABLE_RAM_BYTES = 768L * 1024L * 1024L
        private const val WAKE_LOCK_TIMEOUT_MS = 6L * 60L * 60L * 1_000L

        private val _cleanupState = MutableStateFlow<VoiceCleanupState?>(null)
        val cleanupState: StateFlow<VoiceCleanupState?> = _cleanupState.asStateFlow()
        private val _isProcessing = MutableStateFlow(false)
        val isProcessing: StateFlow<Boolean> = _isProcessing.asStateFlow()
        private val _errorMsg = MutableStateFlow<String?>(null)
        val errorMsg: StateFlow<String?> = _errorMsg.asStateFlow()

        fun restorePersistedState(context: android.content.Context) {
            val previous = TaskStateStore.load(context, VoiceCleanupTask.TYPE) ?: return
            when (previous.status) {
                PersistentTaskStatus.INTERRUPTED -> {
                    _isProcessing.value = false
                    _errorMsg.value = previous.message ?: "Tác vụ trước đã bị hệ thống dừng"
                }

                PersistentTaskStatus.SUCCESS -> {
                    val output = previous.outputPaths.firstOrNull()?.let(::File)
                        ?.takeIf { it.isFile && it.length() > 0L }
                    if (output != null) {
                        _isProcessing.value = false
                        _errorMsg.value = null
                        _cleanupState.value = VoiceCleanupState.Success(output)
                    }
                }

                else -> Unit
            }
        }

        fun clearState(context: android.content.Context? = null) {
            _cleanupState.value = null
            _errorMsg.value = null
            _isProcessing.value = false
            context?.let { TaskStateStore.clear(it, VoiceCleanupTask.TYPE) }
        }
    }
}
