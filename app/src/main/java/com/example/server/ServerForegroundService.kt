package com.example.server

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
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.net.NetworkInterface

class ServerForegroundService : Service() {

    companion object {
        const val ACTION_UPDATE = "com.example.minehost.SERVER_SERVICE_UPDATE"
        const val ACTION_STOP = "com.example.minehost.SERVER_SERVICE_STOP"
        const val EXTRA_ACTIVE_COUNT = "ACTIVE_SERVER_COUNT"
        const val EXTRA_ENGINE_NAMES = "ACTIVE_ENGINE_NAMES"
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: android.net.wifi.WifiManager.WifiLock? = null
    private var multicastLock: android.net.wifi.WifiManager.MulticastLock? = null
    private var diagnosticJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    private var engineName: String = "Minecraft Server"
    private var serverStatus: String = "Active"
    private var activeServerCount: Int = 1

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                stopForeground(true)
            }
            stopSelf()
            return START_NOT_STICKY
        }
        
        intent?.getStringExtra("ENGINE_NAME")?.let { engineName = it }
        intent?.getStringExtra("STATUS")?.let { serverStatus = it }
        activeServerCount = intent?.getIntExtra(EXTRA_ACTIVE_COUNT, activeServerCount)
            ?.coerceAtLeast(1) ?: activeServerCount
        intent?.getStringExtra(EXTRA_ENGINE_NAMES)
            ?.takeIf { it.isNotBlank() }
            ?.let { engineName = it }

        updateNotification()
        acquireLocks()
        startDiagnosticsLoop()

        return START_STICKY
    }

    private fun updateNotification() {
        val notificationIntent = packageManager.getLaunchIntentForPackage(packageName)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(this, "server_channel")
            .setContentTitle(if (activeServerCount == 1) engineName else "$activeServerCount MineHost servers running")
            .setContentText(if (activeServerCount == 1) "Status: $serverStatus" else engineName)
            .setSmallIcon(android.R.drawable.ic_menu_myplaces) 
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(19132, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(19132, notification)
        }
    }

    private fun acquireLocks() {
        if (wakeLock == null) {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "BedrockBox::ServerWakeLock"
            )
        }
        if (wakeLock?.isHeld == false) {
            wakeLock?.acquire()
        }

        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
        
        if (wifiLock == null) {
            wifiLock = wifiManager.createWifiLock(
                android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF,
                "BedrockBox::WifiLock"
            )
        }
        if (wifiLock?.isHeld == false) {
            wifiLock?.acquire()
        }

        if (multicastLock == null) {
            multicastLock = wifiManager.createMulticastLock("BedrockBox::MulticastLock")
        }
        if (multicastLock?.isHeld == false) {
            multicastLock?.acquire()
        }
    }

    private fun releaseLocks() {
        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
        }
        if (wifiLock?.isHeld == true) {
            wifiLock?.release()
        }
        if (multicastLock?.isHeld == true) {
            multicastLock?.release()
        }
    }

    private fun startDiagnosticsLoop() {
        diagnosticJob?.cancel()
        diagnosticJob = scope.launch {
            while (true) {
                if (activeServerCount > 0) {
                    logDiagnostics()
                }
                val delayMs = if (com.example.BuildConfig.DEBUG) 30_000L else 300_000L
                delay(delayMs)
            }
        }
    }

    private fun logDiagnostics() {
        Log.i("ServerDiagnostics", "--- 30s Server Diagnostic Report ---")
        Log.i("ServerDiagnostics", "Foreground Service: ACTIVE")
        Log.i("ServerDiagnostics", "WakeLock Held: ${wakeLock?.isHeld == true}")
        Log.i("ServerDiagnostics", "WifiLock Held: ${wifiLock?.isHeld == true}")
        Log.i("ServerDiagnostics", "MulticastLock Held: ${multicastLock?.isHeld == true}")
        
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            var hasActiveInterface = false
            if (interfaces != null) {
                for (netInt in interfaces) {
                    if (netInt.isUp && !netInt.isLoopback) {
                        hasActiveInterface = true
                        val addresses = netInt.inetAddresses.toList().joinToString { it.hostAddress ?: "" }
                        Log.i("ServerDiagnostics", "Active Interface: ${netInt.name} - $addresses")
                    }
                }
            }
            if (!hasActiveInterface) {
                Log.w("ServerDiagnostics", "No active external network interfaces found!")
            }
        } catch (e: Exception) {
            Log.e("ServerDiagnostics", "Failed to get network interfaces: ${e.message}")
        }
        
        // Process/PID/readiness truth belongs to the UUID runtime registry. The
        // foreground service deliberately does not infer that a server is alive.
        Log.i("ServerDiagnostics", "Process state: reported by the MineHost runtime registry")
        
        Log.i("ServerDiagnostics", "------------------------------------")
    }

    override fun onDestroy() {
        super.onDestroy()
        diagnosticJob?.cancel()
        scope.cancel()
        releaseLocks()
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                stopForeground(true)
            }
        } catch (e: Exception) {
            Log.e("ServerForegroundService", "Failed to stop foreground in onDestroy: ${e.message}")
        }
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        super.onTimeout(startId, fgsType)
        ServerServiceLeaseController.onSystemTimeout()
        val message = "Android timed out the Minecraft server foreground service (type=$fgsType)"
        Log.e("ServerForegroundService", message)
        ForegroundServiceFailureBus.publish(null, "server", message)
        releaseLocks()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf(startId)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "server_channel",
                "Minecraft Server Status",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }
}
