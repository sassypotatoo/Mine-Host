package com.example.ui.screens.profile

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import coil.compose.AsyncImage
import androidx.compose.ui.unit.dp
import com.example.MainViewModel
import com.example.R
import com.example.ui.components.GlassCard
import com.example.ui.components.MineHostBrandHeader
import com.example.ui.components.MineHostScreen
import com.example.ui.components.PastelIcon
import com.example.ui.components.SectionTitle
import com.example.ui.components.SettingsRow
import com.example.ui.components.StatusBadge
import com.example.ui.theme.BlueSoft
import com.example.ui.theme.GreenSoft
import com.example.ui.theme.MineHostBlue
import com.example.ui.theme.MineHostDivider
import com.example.ui.theme.MineHostGreen
import com.example.ui.theme.MineHostOrange
import com.example.ui.theme.MineHostPurple
import com.example.ui.theme.MineHostTextSecondary
import com.example.ui.theme.OrangeSoft
import com.example.ui.theme.PurpleSoft

@Composable
fun ProfileScreen(
    viewModel: MainViewModel,
    onNavigateToAppSettings: () -> Unit,
    onNavigateToAccount: () -> Unit,
    onNavigateToNotifications: () -> Unit
) {
    val plugins by viewModel.plugins.collectAsState()
    val metrics by viewModel.metrics.collectAsState()
    val profiles by viewModel.profiles.collectAsState()

    MineHostScreen(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 0.dp)
    ) {
        MineHostBrandHeader()

        GlassCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(120.dp)
                            .clip(androidx.compose.foundation.shape.RoundedCornerShape(28.dp))
                            .background(MineHostBlue.copy(alpha = 0.1f)),
                        contentAlignment = Alignment.Center
                    ) {
                        androidx.compose.material3.Icon(
                            androidx.compose.material.icons.Icons.Default.AccountCircle,
                            contentDescription = "Profile",
                            tint = MineHostBlue,
                            modifier = Modifier.fillMaxSize(0.7f)
                        )
                    }
                    Spacer(Modifier.size(18.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            "Local MineHost Profile",
                            style = MaterialTheme.typography.headlineSmall
                        )
                        Spacer(Modifier.height(5.dp))
                        StatusBadge(
                            text = "On-device",
                            color = MineHostBlue,
                            background = BlueSoft,
                            showDot = false
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "Manage local preferences and server data. Cloud account services are not configured.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MineHostTextSecondary
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))
                HorizontalDivider(color = MineHostDivider)
                Spacer(Modifier.height(14.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceAround
                ) {
                    ProfileStat(
                        icon = Icons.Outlined.Storage,
                        value = profiles.size.toString(),
                        label = "Servers",
                        accent = MineHostBlue,
                        soft = BlueSoft
                    )
                    ProfileStat(
                        icon = Icons.Outlined.Folder,
                        value = formatBytes(metrics.serverDataBytes),
                        label = "Storage",
                        accent = MineHostGreen,
                        soft = GreenSoft
                    )
                }
            }
        }

        SectionTitle("Settings")
        GlassCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(horizontal = 16.dp)) {
                SettingsRow(
                    title = "App settings",
                    subtitle = "Theme, storage, startup, and preferences",
                    icon = Icons.Outlined.Settings,
                    accent = MineHostBlue,
                    soft = BlueSoft,
                    onClick = onNavigateToAppSettings
                )
                HorizontalDivider(color = MineHostDivider)
                SettingsRow(
                    title = "Account",
                    subtitle = "Local profile, security, and future cloud access",
                    icon = Icons.Outlined.Person,
                    accent = MineHostPurple,
                    soft = PurpleSoft,
                    onClick = onNavigateToAccount
                )
                HorizontalDivider(color = MineHostDivider)
                SettingsRow(
                    title = "Notifications",
                    subtitle = "Server, crash, backup, and plugin alerts",
                    icon = Icons.Outlined.Notifications,
                    accent = MineHostOrange,
                    soft = OrangeSoft,
                    onClick = onNavigateToNotifications
                )
            }
        }

        Spacer(Modifier.height(24.dp))
        GlassCard(Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                PastelIcon(
                    icon = Icons.Outlined.Info,
                    tint = MineHostBlue,
                    background = BlueSoft,
                    size = 44.dp
                )
                Spacer(Modifier.size(12.dp))
                Column {
                    Text("MineHost local build", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Your server files and preferences stay on this device.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MineHostTextSecondary
                    )
                }
            }
        }
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun ProfileStat(
    icon: ImageVector,
    value: String,
    label: String,
    accent: Color,
    soft: Color
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        PastelIcon(icon = icon, tint = accent, background = soft, size = 43.dp)
        Spacer(Modifier.height(5.dp))
        Text(value, style = MaterialTheme.typography.titleLarge)
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MineHostTextSecondary
        )
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1_073_741_824L -> "%.1f GB".format(bytes / 1_073_741_824f)
    bytes >= 1_048_576L -> "%.0f MB".format(bytes / 1_048_576f)
    bytes >= 1024L -> "${bytes / 1024} KB"
    else -> "$bytes B"
}
