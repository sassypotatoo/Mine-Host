package com.example.ui.screens.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Backup
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Storefront
import androidx.compose.material.icons.outlined.Vibration
import androidx.compose.material.icons.outlined.VolumeUp
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.example.MainViewModel
import com.example.ui.components.GlassCard
import com.example.ui.components.MineHostBrandHeader
import com.example.ui.components.MineHostPageTitle
import com.example.ui.components.PastelIcon
import com.example.ui.components.SectionTitle
import com.example.ui.components.SettingsRow
import com.example.ui.theme.BlueSoft
import com.example.ui.theme.GreenSoft
import com.example.ui.theme.MineHostBackgroundBottom
import com.example.ui.theme.MineHostBackgroundTop
import com.example.ui.theme.MineHostBlue
import com.example.ui.theme.MineHostDivider
import com.example.ui.theme.MineHostGreen
import com.example.ui.theme.MineHostPurple
import com.example.ui.theme.MineHostRed
import com.example.ui.theme.MineHostTextSecondary
import com.example.ui.theme.PurpleSoft
import com.example.ui.theme.RedSoft

@Composable
fun NotificationsScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit
) {
    val settings by viewModel.notificationSettings.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(MineHostBackgroundTop, MineHostBackgroundBottom)
                )
            )
            .padding(horizontal = 16.dp)
    ) {
        MineHostBrandHeader(showBack = true, onBack = onBack, compact = true)
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            MineHostPageTitle(
                title = "Notifications",
                subtitle = "Choose which local server alerts you want to receive."
            )

            SectionTitle("Alerts")
            GlassCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(horizontal = 16.dp)) {
                    NotificationToggle(
                        title = "Server offline alerts",
                        subtitle = "Notify when the server process goes offline",
                        icon = Icons.Outlined.Dns,
                        checked = settings.serverOfflineAlerts,
                        onChecked = {
                            viewModel.updateNotificationSettings(
                                settings.copy(serverOfflineAlerts = it)
                            )
                        }
                    )
                    HorizontalDivider(color = MineHostDivider)
                    NotificationToggle(
                        title = "Crash alerts",
                        subtitle = "Alert when a crash or fatal error is detected",
                        icon = Icons.Outlined.WarningAmber,
                        checked = settings.crashAlerts,
                        onChecked = {
                            viewModel.updateNotificationSettings(
                                settings.copy(crashAlerts = it)
                            )
                        }
                    )
                    HorizontalDivider(color = MineHostDivider)
                    NotificationToggle(
                        title = "Backup reminders",
                        subtitle = "Remind you to create local backups",
                        icon = Icons.Outlined.Backup,
                        checked = settings.backupReminders,
                        onChecked = {
                            viewModel.updateNotificationSettings(
                                settings.copy(backupReminders = it)
                            )
                        }
                    )
                    HorizontalDivider(color = MineHostDivider)
                    NotificationToggle(
                        title = "Plugin update alerts",
                        subtitle = "Reserved for a trusted update catalog",
                        icon = Icons.Outlined.Extension,
                        checked = false,
                        enabled = false,
                        onChecked = {}
                    )
                    HorizontalDivider(color = MineHostDivider)
                    NotificationToggle(
                        title = "Marketplace announcements",
                        subtitle = "Requires the future online marketplace",
                        icon = Icons.Outlined.Storefront,
                        checked = false,
                        enabled = false,
                        onChecked = {}
                    )
                }
            }

            SectionTitle("Preferences")
            GlassCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(horizontal = 16.dp)) {
                    NotificationToggle(
                        title = "Sound",
                        subtitle = "Play a sound for notifications",
                        icon = Icons.Outlined.VolumeUp,
                        checked = settings.sound,
                        onChecked = {
                            viewModel.updateNotificationSettings(settings.copy(sound = it))
                        }
                    )
                    HorizontalDivider(color = MineHostDivider)
                    NotificationToggle(
                        title = "Vibration",
                        subtitle = "Vibrate for notifications",
                        icon = Icons.Outlined.Vibration,
                        checked = settings.vibration,
                        onChecked = {
                            viewModel.updateNotificationSettings(settings.copy(vibration = it))
                        }
                    )
                    HorizontalDivider(color = MineHostDivider)
                    NotificationToggle(
                        title = "Quiet hours",
                        subtitle = "Reduce noncritical alerts overnight",
                        icon = Icons.Outlined.Bedtime,
                        checked = settings.quietHoursEnabled,
                        onChecked = {
                            viewModel.updateNotificationSettings(
                                settings.copy(quietHoursEnabled = it)
                            )
                        }
                    )
                }
            }

            GlassCard(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(16.dp)) {
                    PastelIcon(
                        icon = Icons.Outlined.Info,
                        tint = MineHostBlue,
                        background = BlueSoft,
                        size = 46.dp
                    )
                    Spacer(Modifier.size(12.dp))
                    Column {
                        Text("Android permission", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "MineHost requests Android notification permission on supported versions. Android system settings can still override these preferences.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MineHostTextSecondary
                        )
                    }
                }
            }
            Spacer(Modifier.size(20.dp))
        }
    }
}

@Composable
private fun NotificationToggle(
    title: String,
    subtitle: String,
    icon: ImageVector,
    checked: Boolean,
    onChecked: (Boolean) -> Unit,
    enabled: Boolean = true
) {
    val accent = when (icon) {
        Icons.Outlined.WarningAmber -> MineHostRed
        Icons.Outlined.Backup -> MineHostGreen
        Icons.Outlined.Extension -> MineHostPurple
        else -> MineHostBlue
    }
    val soft = when (icon) {
        Icons.Outlined.WarningAmber -> RedSoft
        Icons.Outlined.Backup -> GreenSoft
        Icons.Outlined.Extension -> PurpleSoft
        else -> BlueSoft
    }
    SettingsRow(
        title = title,
        subtitle = subtitle,
        icon = icon,
        accent = accent,
        soft = soft,
        trailing = {
            Switch(
                checked = checked,
                onCheckedChange = onChecked,
                enabled = enabled,
                colors = SwitchDefaults.colors(checkedTrackColor = MineHostBlue)
            )
        }
    )
}
