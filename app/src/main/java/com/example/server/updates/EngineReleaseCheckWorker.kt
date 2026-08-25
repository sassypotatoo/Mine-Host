package com.example.server.updates

import android.content.Context
import androidx.work.*
import com.example.MineHostApplication
import com.example.data.NotificationSettingsState
import java.util.concurrent.TimeUnit

class EngineReleaseCheckWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        android.util.Log.d("EngineReleaseCheckWorker", "Starting periodic engine release check...")
        
        val uiPrefs = applicationContext.getSharedPreferences("minehost_ui_prefs", Context.MODE_PRIVATE)
        val automaticChecks = uiPrefs.getBoolean(com.example.data.MineHostPreferenceKeys.AUTOMATIC_ENGINE_UPDATE_CHECKS, true)
        
        if (!automaticChecks) {
            android.util.Log.d("EngineReleaseCheckWorker", "Automatic checks disabled. Skipping.")
            return Result.success()
        }

        val repository = (applicationContext as MineHostApplication).updateRepository
        
        // Load AppSettingsState for checkNow logic
        val appSettings = com.example.data.AppSettingsState(
            darkMode = uiPrefs.getBoolean("dark_mode", false),
            language = uiPrefs.getString("language", "English") ?: "English",
            startupScreen = uiPrefs.getString("startup_screen", "Dashboard") ?: "Dashboard",
            dataSaver = uiPrefs.getBoolean("data_saver", false),
            hapticFeedback = uiPrefs.getBoolean("haptic", true),
            confirmDestructiveActions = uiPrefs.getBoolean("confirm_destructive", true),
            automaticEngineUpdateChecks = uiPrefs.getBoolean(com.example.data.MineHostPreferenceKeys.AUTOMATIC_ENGINE_UPDATE_CHECKS, true)
        )

        // We need notification settings to send notifications
        val notificationSettings = NotificationSettingsState(
            serverOfflineAlerts = uiPrefs.getBoolean("notify_offline", true),
            crashAlerts = uiPrefs.getBoolean("notify_crash", true),
            backupReminders = uiPrefs.getBoolean("notify_backup", true),
            pluginUpdateAlerts = uiPrefs.getBoolean(com.example.data.MineHostPreferenceKeys.NOTIFY_PLUGINS, false),
            marketplaceAnnouncements = uiPrefs.getBoolean("notify_marketplace", false),
            sound = uiPrefs.getBoolean("notify_sound", true),
            vibration = uiPrefs.getBoolean("notify_vibration", true),
            quietHoursEnabled = uiPrefs.getBoolean(com.example.data.MineHostPreferenceKeys.QUIET_HOURS, false)
        )

        val result = repository.checkAutomatically(notificationSettings, appSettings)
        
        return when (result) {
            is ReleaseCheckResult.Success, 
            is ReleaseCheckResult.PartialSuccess,
            is ReleaseCheckResult.SkippedCooldown,
            is ReleaseCheckResult.AlreadyRunning -> Result.success()
            is ReleaseCheckResult.Failure -> {
                if (result.failures.any { it.retryable }) Result.retry()
                else Result.failure()
            }
        }
    }

    companion object {
        const val WORK_NAME = "MineHostEngineReleaseCheck"

        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = PeriodicWorkRequestBuilder<EngineReleaseCheckWorker>(24, TimeUnit.HOURS)
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.HOURS)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }
    }
}
