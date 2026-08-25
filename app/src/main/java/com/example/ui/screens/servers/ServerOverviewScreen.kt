package com.example.ui.screens.servers

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.CloudDone
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.MonitorHeart
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.example.MainViewModel
import com.example.server.template.TemplateRegistry
import com.example.server.ServerStatus
import com.example.ui.components.EmptyState
import com.example.ui.components.GlassCard
import com.example.ui.components.MetricCard
import com.example.ui.components.PastelIcon
import com.example.ui.components.SectionTitle
import com.example.ui.components.ServerThumbnail
import com.example.ui.components.ServerWorldArtwork
import com.example.ui.components.StatusBadge
import com.example.ui.theme.BlueSoft
import com.example.ui.theme.CyanSoft
import com.example.ui.theme.GreenSoft
import com.example.ui.theme.MineHostBlue
import com.example.ui.theme.MineHostCyan
import com.example.ui.theme.MineHostDivider
import com.example.ui.theme.MineHostGreen
import com.example.ui.theme.MineHostOrange
import com.example.ui.theme.MineHostOutline
import com.example.ui.theme.MineHostPurple
import com.example.ui.theme.MineHostRed
import com.example.ui.theme.MineHostTextPrimary
import com.example.ui.theme.MineHostTextSecondary
import com.example.ui.theme.OrangeSoft
import com.example.ui.theme.PurpleSoft

@Composable
fun ServerOverviewScreen(
    viewModel: MainViewModel,
    serverId: String,
    onOpenLegacyControls: () -> Unit
) {
    val profiles by viewModel.profiles.collectAsState()
    val runtimeStates by viewModel.runtimeStates.collectAsState()
    val profile = remember(serverId, profiles) { profiles.find { it.id == serverId } }
    val hasProfile = profile != null
    val status = runtimeStates[serverId]?.status ?: ServerStatus.STOPPED
    
    val template = remember(profile) { TemplateRegistry.ALL_TEMPLATES.find { it.id == profile?.engineId } ?: TemplateRegistry.BEDROCK_POWER_NUKKIT_X }
    val ipAddress by viewModel.ipAddress.collectAsState()
    val metricsByServer by viewModel.metricsByServer.collectAsState()
    val metrics = metricsByServer[serverId] ?: com.example.data.RuntimeMetrics()
    
    val activities by viewModel.activities.collectAsState()
    val backups by viewModel.backups.collectAsState()
    val memoryMb by viewModel.memoryMb.collectAsState()
    val settings by viewModel.serverSettings.collectAsState()
    val worlds by viewModel.worlds.collectAsState()
    val activeWorld = worlds.find { it.name == (profile?.levelName ?: settings.levelName) }

    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    val address = "${ipAddress.ifBlank { "Unavailable" }}:${profile?.port ?: settings.port}"

    if (!hasProfile) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            EmptyState(
                title = "No server created",
                message = "You need to create a local Bedrock server profile first.",
                icon = Icons.Outlined.Dns
            )
        }
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        GlassCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                ServerWorldArtwork(
                    customIconPath = profile?.iconPath,
                    activeWorldPath = activeWorld?.path,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp),
                    corner = 24.dp
                )
                Spacer(Modifier.height(14.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = profile?.name ?: "Local Bedrock Server",
                            style = MaterialTheme.typography.headlineSmall,
                            maxLines = 2,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                        )
                        Text(
                            template.name,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MineHostTextSecondary
                        )
                    }
                    StatusBadge(status)
                }
                Spacer(Modifier.height(10.dp))
                Surface(
                    color = BlueSoft,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            clipboard.setText(AnnotatedString(address))
                            copied = true
                        }
                ) {
                    Row(
                        modifier = Modifier.padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Outlined.Dns, contentDescription = null, tint = MineHostBlue)
                        Spacer(Modifier.size(7.dp))
                        Text(
                            address,
                            color = MineHostTextSecondary,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            if (copied) "Copied" else "Copy",
                            style = MaterialTheme.typography.labelSmall,
                            color = MineHostBlue
                        )
                        Spacer(Modifier.size(5.dp))
                        Icon(
                            Icons.Outlined.ContentCopy,
                            contentDescription = "Copy address",
                            tint = MineHostBlue,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                Surface(
                    color = GreenSoft,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        PastelIcon(
                            icon = Icons.Outlined.MonitorHeart,
                            tint = MineHostGreen,
                            background = Color.White.copy(alpha = 0.6f),
                            size = 42.dp
                        )
                        Spacer(Modifier.size(10.dp))
                        Column {
                            Text(
                                if (status == ServerStatus.ONLINE) "Server running" else "Server not running",
                                color = if (status == ServerStatus.ONLINE) MineHostGreen else MineHostTextSecondary,
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                if (status == ServerStatus.ONLINE) {
                                    "Local process health checks are active"
                                } else {
                                    "Start the server to collect live health metrics"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MineHostTextSecondary
                            )
                        }
                    }
                }
            }
        }

        val ramProgress = metrics.ramBytes?.let { 
            (it.toFloat() / (memoryMb * 1024f * 1024f)).coerceIn(0f, 1f)
        }
        val cpuProgress = metrics.cpuPercent?.let { (it / 100.0).coerceIn(0.0, 1.0).toFloat() }

        Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            MetricCard(
                title = "TPS",
                value = metrics.tps?.let { "%.2f".format(it) } ?: "—",
                icon = Icons.Outlined.MonitorHeart,
                modifier = Modifier.weight(1f),
                accent = MineHostCyan,
                softColor = CyanSoft,
                supporting = "/ 20.00",
                showChart = true
            )
            MetricCard(
                title = "RAM",
                value = metrics.ramBytes?.let(::formatBytes) ?: "—",
                icon = Icons.Outlined.Memory,
                modifier = Modifier.weight(1f),
                accent = MineHostPurple,
                softColor = PurpleSoft,
                supporting = "/ $memoryMb MB",
                showChart = true,
                progress = ramProgress
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            MetricCard(
                title = "CPU",
                value = metrics.cpuPercent?.let { "${it.toInt()}%" } ?: "—",
                icon = Icons.Outlined.Speed,
                modifier = Modifier.weight(1f),
                accent = MineHostOrange,
                softColor = OrangeSoft,
                supporting = "/ 100%",
                showChart = true,
                progress = cpuProgress
            )
            MetricCard(
                title = "Uptime",
                value = metrics.uptimeMillis?.let(::formatDuration) ?: "—",
                icon = Icons.Outlined.Schedule,
                modifier = Modifier.weight(1f),
                accent = MineHostBlue,
                softColor = BlueSoft,
                showChart = true
            )
        }

        GlassCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp)) {
                Text("Quick Actions", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CompactAction(
                        "Start",
                        Icons.Filled.PlayArrow,
                        MineHostGreen,
                        status != ServerStatus.ONLINE && status != ServerStatus.STARTING && status != ServerStatus.PREPARING && status != ServerStatus.DOWNLOADING,
                        viewModel::startServer,
                        Modifier.weight(1f)
                    )
                    CompactAction(
                        "Stop",
                        Icons.Filled.Stop,
                        MineHostRed,
                        status == ServerStatus.ONLINE || status == ServerStatus.STARTING || status == ServerStatus.PREPARING || status == ServerStatus.DOWNLOADING,
                        viewModel::stopServer,
                        Modifier.weight(1f)
                    )
                    CompactAction(
                        "Restart",
                        Icons.Outlined.Refresh,
                        MineHostBlue,
                        status == ServerStatus.ONLINE,
                        viewModel::restartServer,
                        Modifier.weight(1f)
                    )
                }
                Spacer(Modifier.height(8.dp))
                CompactAction(
                    "Open legacy runtime controls",
                    Icons.Outlined.History,
                    MineHostPurple,
                    true,
                    onOpenLegacyControls,
                    Modifier.fillMaxWidth()
                )
            }
        }

        GlassCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp)) {
                SectionTitle("Recent Maintenance")
                if (backups.isEmpty()) {
                    EmptyState(
                        title = "No maintenance history",
                        message = "Backups and maintenance actions will appear here.",
                        icon = Icons.Outlined.Build
                    )
                } else {
                    backups.take(3).forEachIndexed { index, backup ->
                        SimpleHistoryRow(
                            icon = Icons.Outlined.CloudDone,
                            title = "Backup completed",
                            subtitle = formatBytes(backup.sizeBytes),
                            accent = MineHostGreen
                        )
                        if (index < backups.take(3).lastIndex) {
                            androidx.compose.material3.HorizontalDivider(color = MineHostDivider)
                        }
                    }
                }
            }
        }

        GlassCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp)) {
                SectionTitle("Recent Activity")
                if (activities.isEmpty()) {
                    EmptyState(
                        title = "No recent activity",
                        message = "Server events will appear here.",
                        icon = Icons.Outlined.History
                    )
                } else {
                    activities.take(4).forEach { event ->
                        SimpleHistoryRow(
                            icon = Icons.Outlined.Bolt,
                            title = event.title,
                            subtitle = event.description,
                            accent = MineHostBlue
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CompactAction(
    label: String,
    icon: ImageVector,
    accent: Color,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(64.dp),
        shape = RoundedCornerShape(16.dp),
        color = Color.White.copy(alpha = 0.65f),
        border = androidx.compose.foundation.BorderStroke(1.dp, MineHostOutline)
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 10.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (enabled) accent else MineHostTextSecondary,
                modifier = Modifier.size(22.dp)
            )
            Spacer(Modifier.size(6.dp))
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                color = if (enabled) MineHostTextPrimary else MineHostTextSecondary
            )
        }
    }
}

@Composable
private fun SimpleHistoryRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    accent: Color
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        PastelIcon(
            icon = icon,
            tint = accent,
            background = when (accent) {
                MineHostGreen -> GreenSoft
                MineHostOrange -> OrangeSoft
                else -> BlueSoft
            },
            size = 38.dp
        )
        Spacer(Modifier.size(10.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MineHostTextSecondary
            )
        }
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1_073_741_824L -> "%.1f GB".format(bytes / 1_073_741_824f)
    bytes >= 1_048_576L -> "%.0f MB".format(bytes / 1_048_576f)
    bytes >= 1024L -> "${bytes / 1024} KB"
    else -> "$bytes B"
}

private fun formatDuration(milliseconds: Long): String {
    val hours = milliseconds / 3_600_000
    return if (hours >= 24) {
        "${hours / 24}d ${hours % 24}h"
    } else {
        "${hours}h ${(milliseconds / 60_000) % 60}m"
    }
}
