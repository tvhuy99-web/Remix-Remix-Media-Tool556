package com.example.core.ml

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

class StemService : Service() {
    companion object {
        const val CHANNEL_ID = "stem_service_channel"
        const val NOTIFICATION_ID = 2
        
        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP = "ACTION_STOP"
        
        const val EXTRA_URI = "EXTRA_URI"
        const val EXTRA_MODEL_FILE = "EXTRA_MODEL_FILE"
        
        private val _separationState = MutableStateFlow<SeparationState?>(null)
        val separationState: StateFlow<SeparationState?> = _separationState.asStateFlow()
        
        private val _isProcessing = MutableStateFlow(false)
        val isProcessing: StateFlow<Boolean> = _isProcessing.asStateFlow()
        
        private val _errorMsg = MutableStateFlow<String?>(null)
        val errorMsg: StateFlow<String?> = _errorMsg.asStateFlow()
        
        fun clearState() {
            _separationState.value = null
            _errorMsg.value = null
            _isProcessing.value = false
        }
    }

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == ACTION_START) {
            val uriString = intent.getStringExtra(EXTRA_URI)
            val modelFilePath = intent.getStringExtra(EXTRA_MODEL_FILE)
            
            if (uriString != null && modelFilePath != null) {
                startForegroundServiceWithNotification()
                val uri = Uri.parse(uriString)
                val modelFile = File(modelFilePath)
                
                serviceScope.launch {
                    _isProcessing.value = true
                    _errorMsg.value = null
                    _separationState.value = SeparationState.Progress(0f)
                    
                    try {
                        val separator = AudioSeparator(this@StemService, modelFile)
                        separator.separate(uri).collect { state ->
                            _separationState.value = state
                            if (state is SeparationState.Progress) {
                                updateNotification((state.value * 100).toInt())
                            } else if (state is SeparationState.Success) {
                                _isProcessing.value = false
                                stopForeground(STOP_FOREGROUND_REMOVE)
                                stopSelf()
                            }
                        }
                    } catch (e: Exception) {
                        _errorMsg.value = e.message ?: "Unknown error"
                        _isProcessing.value = false
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    }
                }
            }
        } else if (action == ACTION_STOP) {
            _isProcessing.value = false
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun startForegroundServiceWithNotification() {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Tách nhạc bằng AI")
            .setContentText("Đang chuẩn bị...")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
            
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
            if (type != 0) {
                startForeground(NOTIFICATION_ID, notification, type)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(progress: Int) {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Tách nhạc bằng AI")
            .setContentText("Đang xử lý âm thanh... $progress%")
            .setProgress(100, progress, false)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
            
        val manager = getSystemService(NotificationManager::class.java)
        manager?.notify(NOTIFICATION_ID, notification)
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "Xử lý AI",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(serviceChannel)
        }
    }
}
