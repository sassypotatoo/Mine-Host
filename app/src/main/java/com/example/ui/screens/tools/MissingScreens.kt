package com.example.ui.screens.tools

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.outlined.AutoFixHigh
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.HealthAndSafety
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.MonitorHeart
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.ui.text.font.FontWeight
import com.example.MainViewModel
import com.example.data.ActivityEvent
import com.example.data.ActivityType
import com.example.data.AssistantMessage
import com.example.data.CrashEntry
import com.example.data.CrashSeverity
import com.example.server.ServerStatus
import com.example.ui.components.EmptyState
import com.example.ui.components.GlassCard
import com.example.ui.components.MetricCard
import com.example.ui.components.MineHostBrandHeader
import com.example.ui.components.MineHostButton
import com.example.ui.components.MineHostPageTitle
import com.example.ui.components.PastelIcon
import com.example.ui.components.SectionTitle
import com.example.ui.components.SettingsRow
import com.example.ui.components.StatusBadge
import com.example.ui.theme.BlueSoft
import com.example.ui.theme.GreenSoft
import com.example.ui.theme.MineHostBackgroundBottom
import com.example.ui.theme.MineHostBackgroundTop
import com.example.ui.theme.MineHostBlue
import com.example.ui.theme.MineHostDivider
import com.example.ui.theme.MineHostGreen
import com.example.ui.theme.MineHostOrange
import com.example.ui.theme.MineHostPurple
import com.example.ui.theme.MineHostRed
import com.example.ui.theme.MineHostSurfaceVariant
import com.example.ui.theme.MineHostTextPrimary
import com.example.ui.theme.MineHostTextSecondary
import com.example.ui.theme.OrangeSoft
import com.example.ui.theme.PurpleSoft
import com.example.ui.theme.RedSoft
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
private fun ToolPage(
    title: String,
    onBack: () -> Unit,
    scrollable: Boolean = false,
    content: @Composable ColumnScope.() -> Unit
) {
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
        MineHostPageTitle(title)
        Spacer(Modifier.height(10.dp))
        val bodyModifier = Modifier.weight(1f)
        Column(
            modifier = if (scrollable) {
                bodyModifier.verticalScroll(rememberScrollState())
            } else {
                bodyModifier
            },
            verticalArrangement = Arrangement.spacedBy(10.dp),
            content = content
        )
    }
}

@Composable
fun ActivityScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit,
    serverId: String,
) {
    val allActivities by viewModel.activities.collectAsState()
    val activities = allActivities.filter { it.serverId == null || it.serverId == serverId }
    LaunchedEffect(serverId) { if (serverId.isNotBlank()) viewModel.selectServer(serverId) }
    ToolPage("Activity", onBack) {
        if (activities.isEmpty()) {
            GlassCard(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                EmptyState(
                    title = "No recent activity",
                    message = "Real server actions, backups, settings changes, and runtime events will appear here.",
                    icon = Icons.Outlined.History,
                    modifier = Modifier.fillMaxSize()
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(bottom = 12.dp)
            ) {
                items(activities, key = { it.id }) { activity ->
                    ActivityCard(activity)
                }
            }
        }
    }
}

@Composable
private fun ActivityCard(activity: ActivityEvent) {
    val accent = when (activity.type) {
        ActivityType.SUCCESS -> MineHostGreen
        ActivityType.WARNING -> MineHostOrange
        ActivityType.ERROR -> MineHostRed
        else -> MineHostBlue
    }
    val soft = when (activity.type) {
        ActivityType.SUCCESS -> GreenSoft
        ActivityType.WARNING -> OrangeSoft
        ActivityType.ERROR -> RedSoft
        else -> BlueSoft
    }
    GlassCard(Modifier.fillMaxWidth(), cornerRadius = 18.dp) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            PastelIcon(
                icon = Icons.Outlined.Bolt,
                tint = accent,
                background = soft,
                size = 44.dp
            )
            Spacer(Modifier.size(12.dp))
            Column(Modifier.weight(1f)) {
                Text(activity.title, style = MaterialTheme.typography.titleMedium)
                Text(
                    activity.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MineHostTextSecondary
                )
            }
            Text(
                SimpleDateFormat("h:mm a", Locale.getDefault())
                    .format(Date(activity.timestamp)),
                style = MaterialTheme.typography.labelSmall,
                color = MineHostTextSecondary
            )
        }
    }
}

@Composable
fun AiAssistantScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit,
    serverId: String,
) {
    LaunchedEffect(serverId) { if (serverId.isNotBlank()) viewModel.selectServer(serverId) }
    val messages by viewModel.assistantMessages.collectAsState()
    var inputText by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

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
        MineHostBrandHeader(compact = true, showBack = true, onBack = onBack)

        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(messages, key = { it.id }) { message ->
                AssistantBubble(message)
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = inputText,
                onValueChange = { inputText = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Ask about your server...") },
                maxLines = 3,
                shape = RoundedCornerShape(24.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    unfocusedContainerColor = Color.White.copy(alpha = 0.5f),
                    focusedContainerColor = Color.White.copy(alpha = 0.8f),
                    unfocusedBorderColor = Color.Transparent,
                    focusedBorderColor = MineHostPurple.copy(alpha = 0.5f)
                )
            )
            Spacer(Modifier.width(8.dp))
            IconButton(
                onClick = {
                    if (inputText.isNotBlank()) {
                        viewModel.askAssistant(inputText, serverId)
                        inputText = ""
                    }
                },
                modifier = Modifier
                    .size(48.dp)
                    .background(MineHostPurple, CircleShape),
                colors = IconButtonDefaults.iconButtonColors(contentColor = Color.White)
            ) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
            }
        }
    }
}

@Composable
private fun AssistantBubble(message: AssistantMessage) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (message.fromUser) Arrangement.End else Arrangement.Start
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(0.84f)
                .background(
                    color = if (message.fromUser) MineHostBlue else Color.White.copy(alpha = 0.9f),
                    shape = RoundedCornerShape(
                        topStart = 19.dp,
                        topEnd = 19.dp,
                        bottomStart = if (message.fromUser) 19.dp else 5.dp,
                        bottomEnd = if (message.fromUser) 5.dp else 19.dp
                    )
                )
                .padding(horizontal = 14.dp, vertical = 11.dp)
        ) {
            Text(
                text = message.text,
                color = if (message.fromUser) Color.White else MineHostTextPrimary,
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

@Composable
fun PerformanceRecommendationsScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit,
    serverId: String,
) {
    LaunchedEffect(serverId) { if (serverId.isNotBlank()) viewModel.selectServer(serverId) }
    val recommendations by viewModel.recommendations.collectAsState()
    ToolPage("Performance Recommendations", onBack) {
        GlassCard(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            if (recommendations.isEmpty()) {
                EmptyState(
                    title = "No recommendations yet",
                    message = "Start the server to collect local metrics and settings information.",
                    icon = Icons.Outlined.Lightbulb,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(15.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(recommendations) { recommendation ->
                        SettingsRow(
                            title = recommendation,
                            subtitle = "Generated from real local server state",
                            icon = Icons.Outlined.Lightbulb,
                            accent = MineHostGreen,
                            soft = GreenSoft
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun CrashAnalysisScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit,
    serverId: String,
) {
    val allCrashes by viewModel.crashes.collectAsState()
    val crashes = allCrashes.filter { it.serverId == null || it.serverId == serverId }
    LaunchedEffect(serverId) { if (serverId.isNotBlank()) viewModel.selectServer(serverId) }
    ToolPage("Crash Analysis", onBack) {
        if (crashes.isEmpty()) {
            GlassCard(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                EmptyState(
                    title = "No crash events recorded",
                    message = "Runtime errors detected from the real console will be summarized here.",
                    icon = Icons.Outlined.BugReport,
                    modifier = Modifier.fillMaxSize()
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(bottom = 12.dp)
            ) {
                items(crashes, key = { it.id }) { crash ->
                    CrashCard(crash)
                }
            }
        }
    }
}

@Composable
private fun CrashCard(crash: CrashEntry) {
    val accent = when (crash.severity) {
        CrashSeverity.WARNING -> MineHostOrange
        CrashSeverity.ERROR, CrashSeverity.CRITICAL -> MineHostRed
        else -> MineHostBlue
    }
    val soft = when (crash.severity) {
        CrashSeverity.WARNING -> OrangeSoft
        CrashSeverity.ERROR, CrashSeverity.CRITICAL -> RedSoft
        else -> BlueSoft
    }
    GlassCard(Modifier.fillMaxWidth(), cornerRadius = 18.dp) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.Top
        ) {
            PastelIcon(
                icon = Icons.Outlined.BugReport,
                tint = accent,
                background = soft,
                size = 46.dp
            )
            Spacer(Modifier.size(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        crash.title,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f)
                    )
                    StatusBadge(
                        text = crash.severity.name.lowercase().replaceFirstChar { it.uppercase() },
                        color = accent,
                        background = soft,
                        showDot = false
                    )
                }
                Text(
                    crash.details,
                    style = MaterialTheme.typography.bodySmall,
                    color = MineHostTextSecondary,
                    maxLines = 7
                )
            }
        }
    }
}

@Composable
fun ServerHealthScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit,
    serverId: String,
) {
    val health by viewModel.health.collectAsState()
    val metricsByServer by viewModel.metricsByServer.collectAsState()
    val runtimeStates by viewModel.runtimeStates.collectAsState()
    val metrics = metricsByServer[serverId] ?: com.example.data.RuntimeMetrics()
    val status = runtimeStates[serverId]?.status ?: ServerStatus.STOPPED
    LaunchedEffect(serverId) { if (serverId.isNotBlank()) viewModel.selectServer(serverId) }

    ToolPage("Server Health", onBack, scrollable = true) {
        GlassCard(Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(18.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    health.score.toString(),
                    style = MaterialTheme.typography.displaySmall,
                    color = if (health.score >= 80) MineHostGreen else MineHostOrange
                )
                Text("Health Score", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(12.dp))
                StatusBadge(status)
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            MetricCard(
                title = "CPU",
                value = metrics.cpuPercent?.let { "${it.toInt()}%" } ?: "—",
                icon = Icons.Outlined.Speed,
                modifier = Modifier.weight(1f),
                accent = MineHostOrange,
                softColor = OrangeSoft,
                showChart = true
            )
            MetricCard(
                title = "RAM",
                value = metrics.ramBytes?.let(::formatBytes) ?: "—",
                icon = Icons.Outlined.Memory,
                modifier = Modifier.weight(1f),
                accent = MineHostPurple,
                softColor = PurpleSoft,
                showChart = true
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            MetricCard(
                title = "Storage",
                value = formatBytes(metrics.serverDataBytes),
                icon = Icons.Outlined.Storage,
                modifier = Modifier.weight(1f),
                accent = MineHostBlue,
                softColor = BlueSoft,
                showChart = true
            )
            MetricCard(
                title = "Uptime",
                value = metrics.uptimeMillis?.let(::formatDuration) ?: "—",
                icon = Icons.Outlined.Schedule,
                modifier = Modifier.weight(1f),
                accent = MineHostGreen,
                softColor = GreenSoft,
                showChart = true
            )
        }

        GlassCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(15.dp)) {
                SectionTitle("Health Checks")
                if (health.warnings.isEmpty()) {
                    SettingsRow(
                        title = "Everything looks healthy",
                        subtitle = "No current local warning was detected",
                        icon = Icons.Outlined.CheckCircle,
                        accent = MineHostGreen,
                        soft = GreenSoft
                    )
                } else {
                    health.warnings.forEach { warning ->
                        SettingsRow(
                            title = warning,
                            subtitle = "Review console and performance details",
                            icon = Icons.Outlined.WarningAmber,
                            accent = MineHostOrange,
                            soft = OrangeSoft
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
fun AutoOptimizationScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit,
    serverId: String,
) {
    val settings by viewModel.serverSettings.collectAsState()
    val operationInProgress by viewModel.operationInProgress.collectAsState()
    val runtimeStates by viewModel.runtimeStates.collectAsState()
    val status = runtimeStates[serverId]?.status ?: ServerStatus.STOPPED
    LaunchedEffect(serverId) { if (serverId.isNotBlank()) viewModel.selectServer(serverId) }

    ToolPage("Auto Optimization", onBack, scrollable = true) {
        GlassCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PastelIcon(
                        icon = Icons.Outlined.AutoFixHigh,
                        tint = MineHostOrange,
                        background = OrangeSoft,
                        size = 62.dp
                    )
                    Spacer(Modifier.size(13.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Safe Mobile Profile", style = MaterialTheme.typography.titleLarge)
                        Text(
                            "A backup is created before any change is applied.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MineHostTextSecondary
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                SettingsRow(
                    title = "View distance",
                    subtitle = "${settings.viewDistance} → 8 chunks",
                    icon = Icons.Outlined.MonitorHeart,
                    accent = MineHostBlue,
                    soft = BlueSoft
                )
                SettingsRow(
                    title = "Max players",
                    subtitle = "Limited to 50 or lower",
                    icon = Icons.Outlined.HealthAndSafety,
                    accent = MineHostGreen,
                    soft = GreenSoft
                )
                SettingsRow(
                    title = "Automatic restart",
                    subtitle = "Enabled after the safe profile is saved",
                    icon = Icons.Outlined.AutoFixHigh,
                    accent = MineHostOrange,
                    soft = OrangeSoft
                )
            }
        }

        GlassCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text(
                    "The server must be stopped. This action edits only supported local settings and never claims to optimize unsupported engine internals.",
                    color = MineHostTextSecondary,
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(12.dp))
                MineHostButton(
                    text = if (operationInProgress) "Working…" else "Back Up & Apply",
                    onClick = { viewModel.applySafeOptimization(serverId) },
                    modifier = Modifier.fillMaxWidth(),
                    icon = Icons.Outlined.AutoFixHigh,
                    enabled = !operationInProgress && status != ServerStatus.ONLINE && status != ServerStatus.STARTING && status != ServerStatus.PREPARING && status != ServerStatus.DOWNLOADING,
                    containerColor = MineHostOrange
                )
            }
        }
        Spacer(Modifier.height(12.dp))
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
