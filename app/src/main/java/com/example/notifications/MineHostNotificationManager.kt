package com.example.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.example.data.NotificationSettingsState
import java.util.Calendar

/**
 * Local-only notifications for the real server process and backup jobs.
 * This class does not contact any cloud account or marketplace service.
 */
class MineHostNotificationManager(private val context: Context) {
    private val manager = NotificationManagerCompat.from(context)

    init {
        // Channel creation moved to MineHostApplication onCreate
    }

    fun notifyServerProblem(
        title: String,
        message: String,
        settings: NotificationSettingsState,
        critical: Boolean = true
    ): NotificationResult {
        if (critical && !settings.crashAlerts) return NotificationResult.SkippedDisabled
        if (!critical && !settings.serverOfflineAlerts) return NotificationResult.SkippedDisabled
        return post(
            id = SERVER_ALERT_ID,
            title = title,
            message = message,
            settings = settings,
            critical = critical
        )
    }

    fun cancelServerProblem() {
        manager.cancel(SERVER_ALERT_ID)
    }

    fun notifyBackupCompleted(message: String, settings: NotificationSettingsState): NotificationResult {
        if (!settings.backupReminders) return NotificationResult.SkippedDisabled
        return post(
            id = BACKUP_ALERT_ID,
            title = "MineHost backup completed",
            message = message,
            settings = settings,
            critical = false
        )
    }

    fun notifyNewBuild(id: Int, engineId: String, releaseName: String, settings: NotificationSettingsState): NotificationResult {
        // Notification for new engine builds detected
        return post(
            id = id,
            title = "New $engineId build detected",
            message = "New build $releaseName detected. Compatibility verification is pending.",
            settings = settings,
            critical = false
        )
    }

    private fun post(
        id: Int,
        title: String,
        message: String,
        settings: NotificationSettingsState,
        critical: Boolean
    ): NotificationResult {
        if (!manager.areNotificationsEnabled()) return NotificationResult.SkippedPermissionDenied
        val quiet = settings.quietHoursEnabled && isQuietHours() && !critical
        
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
        val pendingIntent = android.app.PendingIntent.getActivity(
            context,
            id,
            intent,
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
        )

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(if (critical) android.R.drawable.stat_notify_error else android.R.drawable.stat_notify_more)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setAutoCancel(true)
            .setPriority(if (critical) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
            .setOnlyAlertOnce(true)
            .setContentIntent(pendingIntent)

        if (quiet || !settings.sound) builder.setSilent(true)
        if (!quiet && settings.vibration) builder.setVibrate(longArrayOf(0L, 180L, 90L, 180L))
        else builder.setVibrate(longArrayOf(0L))

        return try {
            manager.notify(id, builder.build())
            NotificationResult.Posted
        } catch (e: Exception) {
            NotificationResult.Failed(e.message ?: "Unknown error")
        }
    }

    sealed class NotificationResult {
        object Posted : NotificationResult()
        object SkippedDisabled : NotificationResult()
        object SkippedPermissionDenied : NotificationResult()
        data class Failed(val message: String) : NotificationResult()
    }

    private fun isQuietHours(): Boolean {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return hour >= 22 || hour < 7
    }

    companion object {
        const val CHANNEL_ID = "minehost_local_server_alerts"
        const val SERVER_ALERT_ID = 4101
        const val BACKUP_ALERT_ID = 4102
        const val NEW_BUILD_ALERT_ID = 4103
        
        fun createChannels(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val system = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(
                CHANNEL_ID,
                "MineHost server alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Local Minecraft server status and backup alerts"
                enableVibration(true)
            }
            system.createNotificationChannel(channel)
        }
    }
}
