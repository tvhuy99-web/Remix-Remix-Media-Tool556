package com.aistudio.mediatool.core.media

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.aistudio.mediatool.MainActivity
import com.aistudio.mediatool.R
import com.aistudio.mediatool.core.diagnostics.DiagnosticLogger
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Foreground keep-alive shared by FFmpeg jobs that otherwise live in screen coroutines.
 * Keeping the process foreground + holding a PARTIAL_WAKE_LOCK prevents ordinary Home/screen-off
 * transitions from suspending long media work. The FFmpeg session still owns actual cancellation.
 */
object MediaProcessingForegroundController {
    private val activeTasks = ConcurrentHashMap<String, String>()
    private val serviceRequested = AtomicBoolean(false)
    private val wakeLockGuard = Any()
    @Volatile private var wakeLock: PowerManager.WakeLock? = null

    fun acquire(context: Context, taskId: String, label: String) {
        val appContext = context.applicationContext
        activeTasks[taskId] = label
        acquireWakeLock(appContext)

        if (serviceRequested.compareAndSet(false, true)) {
            runCatching {
                ContextCompat.startForegroundService(
                    appContext,
                    Intent(appContext, MediaProcessingForegroundService::class.java),
                )
            }.onFailure { error ->
                serviceRequested.set(false)
                DiagnosticLogger.error(
                    component = "MediaProcessingForegroundController",
                    event = "foreground_service_start_failed",
                    sessionId = taskId,
                    message = error.message,
                    error = error,
                )
            }
        }
        MediaProcessingForegroundService.requestRefresh()
        DiagnosticLogger.info(
            component = "MediaProcessingForegroundController",
            event = "background_keepalive_acquired",
            sessionId = taskId,
            fields = mapOf("phase" to label, "active_tasks" to activeTasks.size),
        )
    }

    fun update(context: Context, taskId: String, label: String) {
        if (activeTasks.replace(taskId, label) != null) {
            MediaProcessingForegroundService.requestRefresh()
        }
    }

    fun release(context: Context, taskId: String, reason: String) {
        activeTasks.remove(taskId)
        DiagnosticLogger.info(
            component = "MediaProcessingForegroundController",
            event = "background_keepalive_released",
            sessionId = taskId,
            fields = mapOf("reason" to reason, "active_tasks" to activeTasks.size),
        )
        if (activeTasks.isEmpty()) {
            releaseWakeLock(reason)
            serviceRequested.set(false)
            runCatching {
                context.applicationContext.stopService(
                    Intent(context.applicationContext, MediaProcessingForegroundService::class.java),
                )
            }
        } else {
            MediaProcessingForegroundService.requestRefresh()
        }
    }

    internal fun snapshot(): Pair<Int, String?> {
        val entries = activeTasks.entries.toList()
        return entries.size to entries.lastOrNull()?.value
    }

    internal fun markServiceStopped() {
        serviceRequested.set(false)
    }

    private fun acquireWakeLock(context: Context) = synchronized(wakeLockGuard) {
        val current = wakeLock
        if (current?.isHeld == true) return@synchronized
        wakeLock = context.getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "${context.packageName}:MediaProcessing")
            .apply {
                setReferenceCounted(false)
                acquire(WAKE_LOCK_TIMEOUT_MS)
            }
    }

    private fun releaseWakeLock(reason: String) = synchronized(wakeLockGuard) {
        val current = wakeLock ?: return@synchronized
        wakeLock = null
        val held = runCatching { current.isHeld }.getOrDefault(false)
        if (held) runCatching { current.release() }
        DiagnosticLogger.info(
            component = "MediaProcessingForegroundController",
            event = "processing_wake_lock_released",
            fields = mapOf("held" to held, "reason" to reason),
        )
    }

    private const val WAKE_LOCK_TIMEOUT_MS = 6L * 60L * 60L * 1000L
}

class MediaProcessingForegroundService : Service() {
    override fun onCreate() {
        super.onCreate()
        instance = this
        createChannel()
        renderNotification()
        DiagnosticLogger.info(component = TAG, event = "service_created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        renderNotification()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        if (instance === this) instance = null
        MediaProcessingForegroundController.markServiceStopped()
        DiagnosticLogger.info(component = TAG, event = "service_destroyed")
        super.onDestroy()
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        DiagnosticLogger.error(
            component = TAG,
            event = "foreground_timeout",
            fields = mapOf("start_id" to startId, "fgs_type" to fgsType),
        )
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun renderNotification() {
        val (count, label) = MediaProcessingForegroundController.snapshot()
        if (count <= 0) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }

        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val text = when {
            count > 1 -> "Đang xử lý $count tác vụ media"
            !label.isNullOrBlank() -> label
            else -> "Đang xử lý media"
        }
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_ai)
            .setContentTitle("Media Tool đang chạy nền")
            .setContentText(text)
            .setContentIntent(openApp)
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

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Xử lý media nền",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Giữ các tác vụ media tiếp tục chạy khi rời ứng dụng"
            },
        )
    }

    companion object {
        private const val TAG = "MediaProcessingForegroundService"
        private const val CHANNEL_ID = "media_processing_background"
        private const val NOTIFICATION_ID = 2406
        @Volatile private var instance: MediaProcessingForegroundService? = null

        fun requestRefresh() {
            val service = instance ?: return
            service.mainExecutor.execute { service.renderNotification() }
        }
    }
}
