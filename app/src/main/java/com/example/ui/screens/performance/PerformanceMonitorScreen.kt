package com.example.ui.screens.performance

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Group
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.MonitorHeart
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.dp
import com.example.MainViewModel
import com.example.server.ServerStatus
import com.example.ui.components.GlassCard
import com.example.ui.components.MetricCard
import com.example.ui.components.MineHostBrandHeader
import com.example.ui.components.PastelIcon
import com.example.ui.components.SectionTitle
import com.example.ui.components.ServerThumbnail
import com.example.ui.components.SettingsRow
import com.example.ui.components.StatusBadge
import com.example.ui.theme.BlueSoft
import com.example.ui.theme.GreenSoft
import com.example.ui.theme.MineHostBackgroundBottom
import com.example.ui.theme.MineHostBackgroundTop
import com.example.ui.theme.MineHostBlue
import com.example.ui.theme.MineHostGreen
import com.example.ui.theme.MineHostOrange
import com.example.ui.theme.MineHostPurple
import com.example.ui.theme.MineHostTextSecondary
import com.example.ui.theme.OrangeSoft
import com.example.ui.theme.PurpleSoft

@Composable
fun PerformanceMonitorScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit,
    serverId: String? = null,
) {
    val selectedServerId by viewModel.selectedServerId.collectAsState()
    val runtimeStates by viewModel.runtimeStates.collectAsState()
    val metricsByServer by viewModel.metricsByServer.collectAsState()
    val ownerId = serverId ?: selectedServerId
    val metrics = ownerId?.let { metricsByServer[it] } ?: com.example.data.RuntimeMetrics()
    val health by viewModel.health.collectAsState()
    val status = ownerId?.let { runtimeStates[it]?.status } ?: com.example.server.ServerStatus.STOPPED
    LaunchedEffect(ownerId) { ownerId?.let { if (serverId != null) viewModel.selectServer(it) } }
    val recommendations by viewModel.recommendations.collectAsState()
    val memoryMb by viewModel.memoryMb.collectAsState()

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
            PerformanceHero()

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MetricCard(
                    title = "CPU Usage",
                    value = metrics.cpuPercent?.let { "${it.toInt()}%" } ?: "—",
                    icon = Icons.Outlined.Speed,
                    modifier = Modifier.weight(1f),
                    accent = MineHostOrange,
                    softColor = OrangeSoft,
                    supporting = "Local process",
                    progress = metrics.cpuPercent?.let { (it / 100.0).toFloat() },
                    showChart = true
                )
                MetricCard(
                    title = "RAM Usage",
                    value = metrics.ramBytes?.let(::formatBytes) ?: "—",
                    icon = Icons.Outlined.Memory,
                    modifier = Modifier.weight(1f),
                    accent = MineHostPurple,
                    softColor = PurpleSoft,
                    supporting = "Configured $memoryMb MB",
                    progress = metrics.ramBytes?.let {
                        it / (memoryMb * 1024f * 1024f)
                    },
                    showChart = true
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MetricCard(
                    title = "TPS",
                    value = metrics.tps?.let { "%.1f".format(it) } ?: "—",
                    icon = Icons.Outlined.MonitorHeart,
                    modifier = Modifier.weight(1f),
                    accent = MineHostGreen,
                    softColor = GreenSoft,
                    supporting = "Maximum 20.0",
                    progress = metrics.tps?.let { (it / 20.0).toFloat() },
                    showChart = true
                )
                MetricCard(
                    title = "Disk Usage",
                    value = formatBytes(metrics.serverDataBytes),
                    icon = Icons.Outlined.Storage,
                    modifier = Modifier.weight(1f),
                    accent = MineHostBlue,
                    softColor = BlueSoft,
                    supporting = "Server data",
                    showChart = true
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MetricCard(
                    title = "Players",
                    value = metrics.playersOnline?.toString() ?: "—",
                    icon = Icons.Outlined.Group,
                    modifier = Modifier.weight(1f),
                    accent = MineHostGreen,
                    softColor = GreenSoft,
                    supporting = "Detected from logs",
                    showChart = true
                )
                MetricCard(
                    title = "Uptime",
                    value = metrics.uptimeMillis?.let(::formatDuration) ?: "—",
                    icon = Icons.Outlined.Schedule,
                    modifier = Modifier.weight(1f),
                    accent = MineHostOrange,
                    softColor = OrangeSoft,
                    supporting = "Process runtime",
                    showChart = true
                )
            }

            ServerHealthSummary(
                status = status,
                processId = metrics.processId,
                score = health.score
            )

            HealthPanels(
                status = status,
                hasMemoryMetric = metrics.ramBytes != null,
                warnings = health.warnings
            )

            GlassCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(15.dp)) {
                    SectionTitle("Optimization Insights")
                    if (recommendations.isEmpty()) {
                        Text(
                            "No recommendations yet. Start the server to collect more information.",
                            color = MineHostTextSecondary
                        )
                    } else {
                        recommendations.forEach { recommendation ->
                            SettingsRow(
                                title = recommendation,
                                subtitle = "Generated from the current local state",
                                icon = Icons.Outlined.Lightbulb,
                                accent = MineHostGreen,
                                soft = GreenSoft
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun PerformanceHero() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(150.dp)
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .fillMaxWidth(0.64f)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PastelIcon(
                    icon = Icons.Outlined.MonitorHeart,
                    tint = MineHostPurple,
                    background = PurpleSoft,
                    size = 44.dp
                )
                Spacer(Modifier.size(10.dp))
                Text(
                    "Performance Monitor",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MineHostPurple
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "Real-time local process and server insights.",
                color = MineHostTextSecondary
            )
        }
        PastelIcon(
            icon = Icons.Outlined.Dns,
            tint = MineHostPurple,
            background = PurpleSoft,
            modifier = Modifier.align(Alignment.CenterEnd),
            size = 100.dp
        )
    }
}

@Composable
private fun ServerHealthSummary(
    status: ServerStatus,
    processId: Long?,
    score: Int
) {
    GlassCard(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ServerThumbnail(Modifier.size(82.dp), corner = 18.dp)
            Spacer(Modifier.size(13.dp))
            Column(Modifier.weight(1f)) {
                Text("Local Bedrock Server", style = MaterialTheme.typography.titleLarge)
                StatusBadge(status)
                Text(
                    "Live process ID: ${processId ?: "—"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MineHostTextSecondary
                )
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    score.toString(),
                    style = MaterialTheme.typography.headlineMedium,
                    color = if (score >= 80) MineHostGreen else MineHostOrange
                )
                Text("Health Score", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun HealthPanels(
    status: ServerStatus,
    hasMemoryMetric: Boolean,
    warnings: List<String>
) {
    GlassCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            SectionTitle("Alerts")
            if (warnings.isEmpty()) {
                SettingsRow(
                    title = "All systems operational",
                    subtitle = "No current local health warnings",
                    icon = Icons.Outlined.CheckCircle,
                    accent = MineHostGreen,
                    soft = GreenSoft
                )
            } else {
                warnings.forEach { warning ->
                    SettingsRow(
                        title = warning,
                        subtitle = "Review server logs and settings",
                        icon = Icons.Outlined.WarningAmber,
                        accent = MineHostOrange,
                        soft = OrangeSoft
                    )
                }
            }
        }
    }

    GlassCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            SectionTitle("Health Checks")
            val checks = listOf(
                "Process" to (status == ServerStatus.ONLINE),
                "Memory metric" to hasMemoryMetric,
                "Storage access" to true,
                "Log stream" to true
            )
            checks.forEach { (name, ok) ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = if (ok) {
                            Icons.Outlined.CheckCircle
                        } else {
                            Icons.Outlined.RadioButtonUnchecked
                        },
                        contentDescription = null,
                        tint = if (ok) MineHostGreen else MineHostTextSecondary,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.size(8.dp))
                    Text(name, style = MaterialTheme.typography.bodyMedium)
                }
            }
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
