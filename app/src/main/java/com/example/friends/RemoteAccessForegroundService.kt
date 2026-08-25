package com.example.friends

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
import com.example.MainActivity
import com.example.MineHostApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Optional visible foreground owner for real remote access polling. */
class RemoteAccessForegroundService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pollingJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setContentTitle("MineHost remote access")
            .setContentText("Listening for permitted server actions")
            .setContentIntent(openApp)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!hasEnabledProfiles(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (pollingJob?.isActive != true) {
            val processor = RemoteAccessProcessor(application as MineHostApplication)
            pollingJob = scope.launch {
                while (isActive) {
                    runCatching { processor.processOnce() }
                    delay(20_000L)
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        pollingJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "MineHost remote access", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Visible service required while remote server access is enabled"
                setSound(null, null)
            },
        )
    }

    companion object {
        private const val CHANNEL_ID = "minehost_remote_access"
        private const val NOTIFICATION_ID = 5301

        fun refresh(context: Context): Result<Unit> = runCatching {
            val intent = Intent(context, RemoteAccessForegroundService::class.java)
            if (hasEnabledProfiles(context)) ContextCompat.startForegroundService(context, intent)
            else context.stopService(intent)
            Unit
        }

        private fun hasEnabledProfiles(context: Context): Boolean =
            context.getSharedPreferences("minehost_ui_prefs", Context.MODE_PRIVATE).all
                .any { (key, value) -> key.startsWith("remote_access_") && value == true }
    }
}
