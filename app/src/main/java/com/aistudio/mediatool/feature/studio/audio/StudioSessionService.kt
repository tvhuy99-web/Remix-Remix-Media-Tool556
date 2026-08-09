package com.aistudio.mediatool.feature.studio.audio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.aistudio.mediatool.MainActivity
import com.aistudio.mediatool.R

class StudioSessionService : Service() {
    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startAsForeground(intent.getStringExtra(EXTRA_PROJECT_NAME).orEmpty())
            ACTION_STOP_RECORDING -> StudioSessionRuntime.stopRecording()
            else -> stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startAsForeground(projectName: String) {
        val openApp = PendingIntent.getActivity(
            this,
            40,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stopRecording = PendingIntent.getService(
            this,
            41,
            Intent(this, StudioSessionService::class.java).setAction(ACTION_STOP_RECORDING),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_record)
            .setContentTitle("Studio đang thu âm")
            .setContentText(projectName.ifBlank { "MediaTool Studio" })
            .setContentIntent(openApp)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(R.drawable.ic_notification_record, "Dừng thu", stopRecording)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Studio recording",
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
        }
    }

    companion object {
        private const val CHANNEL_ID = "studio_recording_channel"
        private const val NOTIFICATION_ID = 40
        private const val ACTION_START = "com.aistudio.mediatool.action.START_STUDIO_RECORDING"
        private const val ACTION_STOP_RECORDING = "com.aistudio.mediatool.action.STOP_STUDIO_RECORDING"
        private const val EXTRA_PROJECT_NAME = "project_name"

        fun start(context: Context, projectName: String) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, StudioSessionService::class.java)
                    .setAction(ACTION_START)
                    .putExtra(EXTRA_PROJECT_NAME, projectName),
            )
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, StudioSessionService::class.java))
        }
    }
}
