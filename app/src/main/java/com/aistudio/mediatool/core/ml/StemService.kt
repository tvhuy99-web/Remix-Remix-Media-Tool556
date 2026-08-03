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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class StemService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var processingJob: Job? = null
    private var separator: StemEngineRouter? = null
    private var currentTaskId: String? = null
    private var processingWakeLock: PowerManager.WakeLock? = null
    @Volatile private var stopping = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        restorePersistedState(this)
        DiagnosticLogger.info(component = "StemService", event = "service_created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startProcessing(intent)
            ACTION_STOP -> stopProcessing("Đã hủy xử lý", PersistentTaskStatus.CANCELLED)
            else -> stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun startProcessing(intent: Intent) {
        if (processingJob?.isActive == true) {
            DiagnosticLogger.warn(
                component = "StemService",
                event = "duplicate_start_ignored",
                sessionId = currentTaskId,
            )
            return
        }
        currentTaskId = UUID.randomUUID().toString()
        val uri = intent.getStringExtra(EXTRA_URI)?.let(Uri::parse)
        val model = intent.getStringExtra(EXTRA_MODEL_FILE)?.let(::File)
        val requestedModelId = intent.getStringExtra(EXTRA_MODEL_ID)
        if (uri == null || model?.isFile != true || model.length() <= 0L) {
            failAndStop("Thiếu tệp đầu vào hoặc model AI")
            return
        }
        val modelDescriptor = when {
            requestedModelId != null -> StemModelRegistry.find(requestedModelId)
            else -> StemModelRegistry.findByFileName(model.name)
        }
        if (modelDescriptor == null) {
            failAndStop("Model AI không có descriptor tương thích")
            return
        }

        try {
            startAsForeground()
            acquireProcessingWakeLock()
        } catch (error: Exception) {
            DiagnosticLogger.error(
                component = "StemService",
                event = "foreground_start_failed",
                sessionId = currentTaskId,
                message = error.message,
                error = error,
            )
            failAndStop("Android không cho phép bắt đầu xử lý nền: ${error.message ?: "không xác định"}")
            return
        }
        val taskId = checkNotNull(currentTaskId)
        val sourceId = DiagnosticRedactor.stableId(uri.toString())
        val startedAt = SystemClock.elapsedRealtime()
        DiagnosticLogger.info(
            component = "StemService",
            event = "task_start",
            sessionId = taskId,
            fields = mapOf(
                "model_id" to modelDescriptor.id,
                "mode" to modelDescriptor.mode,
                "backend" to modelDescriptor.backend,
                "source_id" to sourceId,
                "model_bytes" to model.length(),
            ),
        )
        stopping = false
        _isProcessing.value = true
        _errorMsg.value = null
        _separationState.value = SeparationState.Progress(0f)
        saveTask(PersistentTaskStatus.RUNNING, 0f, "Đang kiểm tra model và tài nguyên")
        ProcessExitDiagnostics.checkpoint(
            context = this,
            taskType = TASK_TYPE,
            taskId = taskId,
            phase = "preflight",
            progress = 0f,
            modelId = modelDescriptor.id,
        )

        // MediaMetadataRetriever, StatFs và SHA-256 model đều có thể chặn lâu.
        // processingJob được gán ngay để chặn ACTION_START trùng trong preflight.
        processingJob = serviceScope.launch {
            try {
                val preflight = runPreflight(uri, modelDescriptor)
                val validationStartedAt = SystemClock.elapsedRealtime()
                require(ModelDownloader(this@StemService).isModelFileValid(model, modelDescriptor.modelSpec)) {
                    "Model AI không đúng dung lượng hoặc SHA-256; hãy tải lại model"
                }
                ProcessExitDiagnostics.checkpoint(
                    context = this@StemService,
                    taskType = TASK_TYPE,
                    taskId = taskId,
                    phase = "model_validated",
                    progress = 0.01f,
                    modelId = modelDescriptor.id,
                )
                DiagnosticLogger.info(
                    component = "StemService",
                    event = "model_validated",
                    sessionId = taskId,
                    fields = mapOf(
                        "model_id" to modelDescriptor.id,
                        "backend" to modelDescriptor.backend,
                        "elapsed_ms" to SystemClock.elapsedRealtime() - validationStartedAt,
                    ),
                )
                saveTask(
                    PersistentTaskStatus.RUNNING,
                    0f,
                    "Đang chuẩn bị ${modelDescriptor.displayName} (${preflight.stemCount} stem)",
                )

                val activeSeparator = StemEngineRouter(this@StemService, model, modelDescriptor, taskId)
                separator = activeSeparator
                var lastProgressBucket = -1
                activeSeparator.separate(uri).collect { state ->
                    _separationState.value = state
                    when (state) {
                        is SeparationState.Progress -> {
                            updateNotification((state.value * 100).toInt())
                            saveTask(PersistentTaskStatus.RUNNING, state.value, "Đang tách nhạc")
                            val bucket = (state.value.coerceIn(0f, 1f) * 10f).toInt()
                            if (bucket > lastProgressBucket) {
                                lastProgressBucket = bucket
                                DiagnosticLogger.info(
                                    component = "StemService",
                                    event = "task_progress",
                                    sessionId = taskId,
                                    fields = mapOf("percent" to bucket * 10, "model_id" to modelDescriptor.id),
                                )
                            }
                        }
                        is SeparationState.Success -> {
                            val outputs = listOfNotNull(
                                state.vocalsFile,
                                state.musicFile,
                                state.drumsFile,
                                state.bassFile,
                                state.otherFile,
                            ).distinctBy { it.absolutePath }
                            saveTask(
                                PersistentTaskStatus.SUCCESS,
                                1f,
                                "Tách nhạc hoàn tất",
                                outputs.map(File::getAbsolutePath),
                            )
                            DiagnosticLogger.info(
                                component = "StemService",
                                event = "task_success",
                                sessionId = taskId,
                                fields = mapOf(
                                    "model_id" to modelDescriptor.id,
                                    "backend" to modelDescriptor.backend,
                                    "output_count" to outputs.size,
                                    "output_bytes" to outputs.sumOf(File::length),
                                    "elapsed_ms" to SystemClock.elapsedRealtime() - startedAt,
                                ),
                            )
                            finishService()
                        }
                    }
                }
            } catch (_: CancellationException) {
                DiagnosticLogger.info(
                    component = "StemService",
                    event = "task_cancelled",
                    sessionId = taskId,
                    fields = mapOf("elapsed_ms" to SystemClock.elapsedRealtime() - startedAt),
                )
                if (!stopping) {
                    saveTask(PersistentTaskStatus.CANCELLED, message = "Đã hủy xử lý")
                    _errorMsg.value = "Đã hủy xử lý"
                    finishService()
                }
            } catch (error: Throwable) {
                val message = error.message ?: "Không thể tách nhạc"
                DiagnosticLogger.error(
                    component = "StemService",
                    event = "task_failed",
                    sessionId = taskId,
                    message = message,
                    fields = mapOf(
                        "model_id" to modelDescriptor.id,
                        "backend" to modelDescriptor.backend,
                        "source_id" to sourceId,
                        "elapsed_ms" to SystemClock.elapsedRealtime() - startedAt,
                        "out_of_memory" to (error is OutOfMemoryError),
                    ),
                    error = error,
                )
                _errorMsg.value = message
                saveTask(PersistentTaskStatus.FAILED, message = message)
                finishService()
            } finally {
                separator = null
            }
        }
    }

    private fun runPreflight(uri: Uri, model: StemModelDescriptor): StemResourceEstimate {
        val retriever = MediaMetadataRetriever()
        val durationMs = try {
            retriever.setDataSource(this, uri)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
        } finally {
            retriever.release()
        } ?: error("Không đọc được thời lượng tệp đầu vào")
        require(durationMs > 0L) { "Tệp đầu vào không có thời lượng hợp lệ" }
        require(durationMs <= MAX_DURATION_MS) { "Tệp dài quá 3 giờ; hãy cắt nhỏ trước khi tách stem" }
        val estimate = StemPreflight.estimate(
            durationMs = durationMs,
            stemCount = model.mode.stemCount,
            modelMinimumAvailableRamBytes = model.deviceRequirements.minimumAvailableRamBytes,
        )
        val freeBytes = StatFs(cacheDir.absolutePath).availableBytes
        val memoryInfo = ActivityManager.MemoryInfo()
        getSystemService(ActivityManager::class.java).getMemoryInfo(memoryInfo)
        DiagnosticLogger.info(
            component = "StemService",
            event = "preflight_snapshot",
            sessionId = currentTaskId,
            fields = mapOf(
                "model_id" to model.id,
                "backend" to model.backend,
                "duration_ms" to durationMs,
                "stem_count" to estimate.stemCount,
                "required_storage_bytes" to estimate.recommendedFreeBytes,
                "available_storage_bytes" to freeBytes,
                "total_ram_bytes" to memoryInfo.totalMem,
                "available_ram_bytes" to memoryInfo.availMem,
                "required_ram_bytes" to estimate.recommendedRamBytes,
                "low_memory" to memoryInfo.lowMemory,
            ),
        )
        require(freeBytes >= estimate.recommendedFreeBytes) {
            "Không đủ dung lượng tạm. Cần khoảng ${formatMb(estimate.recommendedFreeBytes)}, còn ${formatMb(freeBytes)}"
        }
        require(memoryInfo.totalMem >= model.deviceRequirements.minimumTotalRamBytes) {
            "Thiết bị không đủ RAM cho ${model.displayName}. ${model.deviceRequirements.userFacingSummary}"
        }
        require(!memoryInfo.lowMemory && memoryInfo.availMem >= estimate.recommendedRamBytes) {
            "Không đủ RAM trống cho model này (cần khoảng ${formatMb(estimate.recommendedRamBytes)}). Hãy đóng ứng dụng khác rồi thử lại."
        }
        return estimate
    }

    private fun stopProcessing(message: String, status: PersistentTaskStatus) {
        if (stopping) return
        stopping = true
        DiagnosticLogger.warn(
            component = "StemService",
            event = "stop_requested",
            sessionId = currentTaskId,
            message = message,
            fields = mapOf("status" to status),
        )
        separator?.cancel()
        processingJob?.cancel()
        processingJob = null
        _errorMsg.value = message
        saveTask(status, message = message)
        finishService()
    }

    private fun failAndStop(message: String) {
        DiagnosticLogger.error(
            component = "StemService",
            event = "start_rejected",
            sessionId = currentTaskId,
            message = message,
        )
        _errorMsg.value = message
        saveTask(PersistentTaskStatus.FAILED, message = message)
        finishService()
    }

    private fun acquireProcessingWakeLock() {
        if (processingWakeLock?.isHeld == true) return
        val lock = getSystemService(PowerManager::class.java).newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "$packageName:StemProcessing",
        ).apply {
            setReferenceCounted(false)
            acquire(WAKE_LOCK_TIMEOUT_MS)
        }
        processingWakeLock = lock
        DiagnosticLogger.info(
            component = "StemService",
            event = "wake_lock_acquired",
            sessionId = currentTaskId,
            fields = mapOf("timeout_ms" to WAKE_LOCK_TIMEOUT_MS),
        )
    }

    private fun releaseProcessingWakeLock(reason: String) {
        val lock = processingWakeLock ?: return
        processingWakeLock = null
        val wasHeld = runCatching { lock.isHeld }.getOrDefault(false)
        if (wasHeld) runCatching { lock.release() }
        DiagnosticLogger.info(
            component = "StemService",
            event = "wake_lock_released",
            sessionId = currentTaskId,
            fields = mapOf("held" to wasHeld, "reason" to reason),
        )
    }

    private fun finishService() {
        releaseProcessingWakeLock("finish")
        currentTaskId?.let { taskId -> ProcessExitDiagnostics.finish(this, TASK_TYPE, taskId) }
        _isProcessing.value = false
        processingJob = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        DiagnosticLogger.error(
            component = "StemService",
            event = "foreground_timeout",
            sessionId = currentTaskId,
            fields = mapOf("start_id" to startId, "fgs_type" to fgsType),
        )
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
            1,
            Intent(this, StemService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Tách nhạc")
            .setContentText("Đang chuẩn bị")
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

    private fun updateNotification(progress: Int) {
        val cancel = PendingIntent.getService(
            this,
            1,
            Intent(this, StemService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Tách nhạc")
            .setContentText("${progress.coerceIn(0, 100)}%")
            .setProgress(100, progress.coerceIn(0, 100), false)
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
                taskId = currentTaskId ?: "stem",
                type = TASK_TYPE,
                status = status,
                progress = progress,
                message = message,
                outputPaths = outputs,
            ),
        )
    }

    override fun onDestroy() {
        DiagnosticLogger.info(
            component = "StemService",
            event = "service_destroyed",
            sessionId = currentTaskId,
            fields = mapOf("processing" to (processingJob?.isActive == true), "stopping" to stopping),
        )
        separator?.cancel()
        processingJob?.cancel()
        releaseProcessingWakeLock("destroy")
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Tách nhạc", NotificationManager.IMPORTANCE_LOW),
            )
        }
    }

    private fun formatMb(bytes: Long): String = "${bytes / (1024L * 1024L)} MB"

    companion object {
        const val CHANNEL_ID = "stem_service_channel"
        const val NOTIFICATION_ID = 2
        const val ACTION_START = "com.aistudio.mediatool.action.START_STEM"
        const val ACTION_STOP = "com.aistudio.mediatool.action.STOP_STEM"
        const val EXTRA_URI = "extra_uri"
        const val EXTRA_MODEL_FILE = "extra_model_file"
        const val EXTRA_MODEL_ID = "extra_model_id"
        internal const val TASK_TYPE = "stem"
        private const val MAX_DURATION_MS = 3L * 60L * 60L * 1_000L
        private const val WAKE_LOCK_TIMEOUT_MS = 6L * 60L * 60L * 1_000L

        private val _separationState = MutableStateFlow<SeparationState?>(null)
        val separationState: StateFlow<SeparationState?> = _separationState.asStateFlow()
        private val _isProcessing = MutableStateFlow(false)
        val isProcessing: StateFlow<Boolean> = _isProcessing.asStateFlow()
        private val _errorMsg = MutableStateFlow<String?>(null)
        val errorMsg: StateFlow<String?> = _errorMsg.asStateFlow()

        fun restorePersistedState(context: android.content.Context) {
            val previous = TaskStateStore.load(context, TASK_TYPE) ?: return
            when (previous.status) {
                PersistentTaskStatus.INTERRUPTED -> {
                    _isProcessing.value = false
                    _errorMsg.value = previous.message ?: "Tác vụ tách nhạc trước đã bị hệ thống dừng"
                }
                PersistentTaskStatus.SUCCESS -> {
                    val files = previous.outputPaths.map(::File).filter { it.isFile && it.length() > 0L }
                    if (files.size >= 2) {
                        _isProcessing.value = false
                        _errorMsg.value = null
                        _separationState.value = SeparationState.Success(
                            vocalsFile = files[0],
                            musicFile = files[1],
                            drumsFile = files.getOrNull(2),
                            bassFile = files.getOrNull(3),
                            otherFile = files.getOrNull(4),
                        )
                    }
                }
                else -> Unit
            }
        }

        fun clearState(context: android.content.Context? = null) {
            _separationState.value = null
            _errorMsg.value = null
            _isProcessing.value = false
            context?.let { TaskStateStore.clear(it, TASK_TYPE) }
        }
    }
}
