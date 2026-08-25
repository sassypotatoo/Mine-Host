package com.example.server

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

/** Foreground owner for concurrent runtime/JAR preparation operations. */
class DownloadForegroundService : Service() {
    companion object {
        const val ACTION_UPDATE = "com.example.minehost.DOWNLOAD_SERVICE_UPDATE"
        const val ACTION_STOP = "com.example.minehost.DOWNLOAD_SERVICE_STOP"
        const val EXTRA_ACTIVE_COUNT = "ACTIVE_DOWNLOAD_COUNT"
        const val EXTRA_LABELS = "ACTIVE_DOWNLOAD_LABELS"
        const val EXTRA_PROGRESS_CONTENT = "PROGRESS_CONTENT"
        const val EXTRA_OWNER_ID = "OWNER_ID"
        private const val NOTIFICATION_ID = 19133
    }

    private var activeCount = 1
    private var labels = "Preparing server files"
    private var progressContent: String? = null
    private var ownerId: String? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        activeCount = intent?.getIntExtra(EXTRA_ACTIVE_COUNT, activeCount)?.coerceAtLeast(1) ?: activeCount
        labels = intent?.getStringExtra(EXTRA_LABELS)?.takeIf(String::isNotBlank) ?: labels
        progressContent = intent?.getStringExtra(EXTRA_PROGRESS_CONTENT) ?: progressContent
        ownerId = intent?.getStringExtra(EXTRA_OWNER_ID) ?: ownerId
        startAsForeground()
        return START_NOT_STICKY
    }

    private fun startAsForeground() {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, launchIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(this, "download_channel")
            .setContentTitle(if (activeCount == 1) "Preparing server" else "Preparing $activeCount servers")
            .setContentText(progressContent ?: labels)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        DownloadServiceLeaseController.onSystemTimeout()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf(startId)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(
                NotificationChannel("download_channel", "Server setup progress", NotificationManager.IMPORTANCE_LOW),
            )
        }
    }
}
