package com.aistudio.mediatool.core.media

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import com.aistudio.mediatool.MainActivity
import com.aistudio.mediatool.R
import com.aistudio.mediatool.core.diagnostics.DiagnosticLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class RecordingService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var mediaProjection: MediaProjection? = null
    private var projectionCallback: MediaProjection.Callback? = null
    private var stopping = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        DiagnosticLogger.info(component = TAG, event = "service_created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            when (intent?.action) {
                ACTION_START_MIC -> {
                    DiagnosticLogger.info(component = TAG, event = "microphone_start_received")
                    startAsForeground(isInternal = false)
                    serviceScope.launch {
                        RecordingManager.beginMicrophoneCapture(this@RecordingService)
                        if (!RecordingManager.isRecording.value) stopSelf()
                    }
                }
                ACTION_START_INTERNAL -> {
                    DiagnosticLogger.info(component = TAG, event = "internal_audio_start_received")
                    startInternalCapture(intent)
                }
                ACTION_STOP -> {
                    DiagnosticLogger.info(component = TAG, event = "stop_received")
                    stopCaptureAndSelf()
                }
                else -> stopSelf()
            }
        } catch (error: Exception) {
            DiagnosticLogger.error(
                component = TAG,
                event = "foreground_start_failed",
                message = error.message,
                error = error,
            )
            serviceScope.launch {
                RecordingManager.onCaptureFailed(this@RecordingService, error)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun startInternalCapture(intent: Intent) {
        startAsForeground(isInternal = true)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            serviceScope.launch {
                RecordingManager.onCaptureFailed(
                    this@RecordingService,
                    IllegalStateException("Ghi âm hệ thống cần Android 10 trở lên"),
                )
                stopSelf()
            }
            return
        }
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Int.MIN_VALUE)
        val data = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_PERMISSION_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION") intent.getParcelableExtra(EXTRA_PERMISSION_DATA)
        }
        runCatching {
            require(resultCode != Int.MIN_VALUE && data != null) { "Thiếu quyền MediaProjection" }
            val manager = getSystemService(MediaProjectionManager::class.java)
            val projection = manager.getMediaProjection(resultCode, data) ?: throw IllegalStateException("Không thể lấy MediaProjection")
            val callback = object : MediaProjection.Callback() {
                override fun onStop() {
                    // Người dùng hoặc hệ thống đã thu hồi token. Không gọi projection.stop() lần nữa.
                    mediaProjection = null
                    DiagnosticLogger.warn(component = TAG, event = "media_projection_revoked")
                    stopCaptureAndSelf(stopProjection = false)
                }
            }
            projection.registerCallback(callback, mainHandler)
            projectionCallback = callback
            mediaProjection = projection
            serviceScope.launch {
                RecordingManager.beginInternalCapture(this@RecordingService, projection)
                if (!RecordingManager.isRecording.value) stopCaptureAndSelf()
            }
        }.onFailure { error ->
            DiagnosticLogger.error(
                component = TAG,
                event = "media_projection_start_failed",
                message = error.message,
                error = error,
            )
            serviceScope.launch {
                RecordingManager.onCaptureFailed(this@RecordingService, error)
                stopCaptureAndSelf()
            }
        }
    }

    @Synchronized
    private fun stopCaptureAndSelf(stopProjection: Boolean = true) {
        if (stopping) return
        stopping = true
        DiagnosticLogger.info(
            component = TAG,
            event = "capture_finalization_start",
            fields = mapOf("stop_projection" to stopProjection),
        )
        val projection = mediaProjection
        val callback = projectionCallback
        mediaProjection = null
        projectionCallback = null
        serviceScope.launch {
            RecordingManager.finishCapture(this@RecordingService)
            if (callback != null) runCatching { projection?.unregisterCallback(callback) }
            if (stopProjection) runCatching { projection?.stop() }
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            DiagnosticLogger.info(component = TAG, event = "capture_finalization_complete")
        }
    }

    override fun onDestroy() {
        val needsFinalization = !stopping &&
            (RecordingManager.isRecording.value || RecordingManager.isStarting.value)
        stopping = true
        projectionCallback?.let { callback -> runCatching { mediaProjection?.unregisterCallback(callback) } }
        runCatching { mediaProjection?.stop() }
        mediaProjection = null
        projectionCallback = null
        serviceScope.cancel()
        if (needsFinalization) {
            // onDestroy chạy trên main thread. Một scope một-lần độc lập hoàn tất
            // WAV ở IO để không chặn UI tới STOP_TIMEOUT_MS.
            val appContext = applicationContext
            CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                RecordingManager.finishCapture(appContext)
            }
        }
        DiagnosticLogger.info(
            component = TAG,
            event = "service_destroyed",
            fields = mapOf("needed_async_finalization" to needsFinalization),
        )
        super.onDestroy()
    }

    private fun startAsForeground(isInternal: Boolean) {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, RecordingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(if (isInternal) "Đang ghi âm hệ thống" else "Đang ghi âm microphone")
            .setContentText("Chạm để quay lại MediaTool")
            .setSmallIcon(R.drawable.ic_notification_record)
            .setContentIntent(openApp)
            .addAction(R.drawable.ic_notification_record, "Dừng", stopIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val type = if (isInternal) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            } else {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            }
            startForeground(NOTIFICATION_ID, notification, type)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Ghi âm", NotificationManager.IMPORTANCE_LOW),
            )
        }
    }

    companion object {
        private const val TAG = "RecordingService"
        const val CHANNEL_ID = "recording_channel"
        const val NOTIFICATION_ID = 1
        const val ACTION_START_MIC = "com.aistudio.mediatool.action.START_MIC"
        const val ACTION_START_INTERNAL = "com.aistudio.mediatool.action.START_INTERNAL"
        const val ACTION_STOP = "com.aistudio.mediatool.action.STOP_RECORDING"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_PERMISSION_DATA = "permission_data"
    }
}
