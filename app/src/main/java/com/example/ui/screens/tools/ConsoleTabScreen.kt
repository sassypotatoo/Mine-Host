package com.example.ui.screens.tools

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import com.example.ui.theme.MineHostDivider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.MainViewModel
import com.example.data.ServerEdition
import com.example.data.ServerNetworkType
import com.example.server.ServerStatus
import com.example.server.template.TemplateRegistry
import com.example.ui.components.GlassCard
import com.example.ui.components.MineHostBrandHeader
import com.example.ui.components.MineHostScreen
import com.example.ui.navigation.MineHostDestination
import com.example.ui.theme.*
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ConsoleTabScreen(viewModel: MainViewModel, onNavigate: (String) -> Unit) {
    val logsByServer by viewModel.logsByServer.collectAsState()
    val runtimeStates by viewModel.runtimeStates.collectAsState()
    val metricsByServer by viewModel.metricsByServer.collectAsState()
    val playersByServer by viewModel.playersByServer.collectAsState()
    val profiles by viewModel.profiles.collectAsState()

    MineHostScreen(
        contentPadding = PaddingValues(horizontal = 0.dp, vertical = 0.dp),
        modifier = Modifier.background(MineHostBackgroundTop)
    ) {
        Column(Modifier.padding(horizontal = 16.dp)) {
            MineHostBrandHeader(
                compact = true,
                onProfile = { onNavigate(MineHostDestination.Profile.route) },
                onNotifications = { onNavigate(MineHostDestination.Notifications.route) },
                showNotifications = false
            )

            if (profiles.isEmpty()) {
                Text(
                    "No servers available",
                    color = MineHostTextSecondary,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 24.dp)
                        .wrapContentWidth(Alignment.CenterHorizontally)
                )
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(profiles, key = { it.id }) { profile ->
                        val runtimeState = runtimeStates[profile.id]
                        val metrics = metricsByServer[profile.id]
                        val logs = logsByServer[profile.id].orEmpty()
                        val players = playersByServer[profile.id].orEmpty()
                        val status = runtimeState?.status ?: ServerStatus.STOPPED

                        ServerConsoleCard(
                            serverName = profile.name,
                            edition = profile.edition,
                            engineId = profile.engineId,
                            status = status,
                            logs = logs,
                            metrics = metrics,
                            playerCount = players.size,
                            playerNames = players.filter { it.online }.map { it.name },
                            uptimeMillis = runtimeState?.uptimeMillis,
                            onStart = { viewModel.startServer(profile.id) },
                            onStop = { viewModel.stopServer(profile.id) },
                            onRestart = { viewModel.restartServer(profile.id) },
                            onSendCommand = { cmd -> viewModel.sendCommand(cmd, profile.id) }
                        )
                    }

                    item { Spacer(modifier = Modifier.height(110.dp)) }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ServerConsoleCard(
    serverName: String,
    edition: ServerEdition,
    engineId: String,
    status: ServerStatus,
    logs: List<String>,
    metrics: com.example.data.RuntimeMetrics?,
    playerCount: Int,
    playerNames: List<String>,
    uptimeMillis: Long?,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onRestart: () -> Unit,
    onSendCommand: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    var command by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    val engineName = TemplateRegistry.getTemplate(engineId)?.name ?: engineId
    val editionLabel = if (edition == ServerEdition.JAVA) "Java" else "Bedrock"
    val isRunning = status == ServerStatus.ONLINE || status == ServerStatus.WORLD_PROVISIONALLY_LOADED
    val isTransitioning = status == ServerStatus.STARTING || status == ServerStatus.STOPPING

    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty() && expanded) {
            listState.animateScrollToItem(logs.lastIndex)
        }
    }

    GlassCard(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // ── Header: Name, Status, Expand Toggle ──
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Status indicator
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(getStatusColor(status))
                    )
                    Column {
                        Text(
                            text = serverName,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "$editionLabel • $engineName",
                            style = MaterialTheme.typography.bodySmall,
                            color = MineHostTextSecondary
                        )
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = status.name,
                        style = MaterialTheme.typography.labelSmall,
                        color = getStatusColor(status)
                    )
                    IconButton(onClick = { expanded = !expanded }) {
                        Icon(
                            imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                            contentDescription = if (expanded) "Collapse" else "Expand"
                        )
                    }
                }
            }

            // ── Metrics Row (always visible) ──
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                MetricChip(
                    label = "CPU",
                    value = metrics?.cpuPercent?.let { "${String.format("%.0f", it)}%" } ?: "—"
                )
                MetricChip(
                    label = "RAM",
                    value = metrics?.ramBytes?.let { formatBytes(it) } ?: "—"
                )
                MetricChip(
                    label = "Players",
                    value = if (isRunning) "$playerCount" else "—"
                )
                if (uptimeMillis != null && uptimeMillis > 0) {
                    MetricChip(
                        label = "Up",
                        value = formatDuration(uptimeMillis)
                    )
                }
            }

            // ── Controls ──
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (!isRunning) {
                    Button(
                        onClick = onStart,
                        enabled = !isTransitioning,
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MineHostGreen)
                    ) {
                        Icon(Icons.Outlined.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.size(4.dp))
                        Text("Start")
                    }
                } else {
                    OutlinedButton(
                        onClick = onStop,
                        enabled = !isTransitioning,
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Outlined.Stop, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.size(4.dp))
                        Text("Stop")
                    }
                    OutlinedButton(
                        onClick = onRestart,
                        enabled = !isTransitioning,
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Outlined.RestartAlt, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.size(4.dp))
                        Text("Restart")
                    }
                }
            }

            // ── Expanded Section: Console + Command Input ──
            if (expanded) {
                HorizontalDivider(color = MineHostDivider)

                // Console output
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 120.dp, max = 240.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(ConsoleBackground)
                        .padding(8.dp)
                ) {
                    if (logs.isEmpty()) {
                        Text(
                            text = "[INFO] Console ready. Start the server to stream logs.",
                            color = ConsoleText.copy(alpha = 0.7f),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp
                        )
                    } else {
                        LazyColumn(
                            state = listState,
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                            modifier = Modifier.fillMaxSize()
                        ) {
                            items(logs.takeLast(200)) { line ->
                                Text(
                                    text = line,
                                    color = consoleColor(line),
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 10.sp,
                                    lineHeight = 13.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }

                // Command input
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = command,
                        onValueChange = { command = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text(if (isRunning) "Type a command…" else "Start server first") },
                        enabled = isRunning,
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = ConsoleText,
                            unfocusedTextColor = ConsoleText,
                            focusedContainerColor = ConsoleSurface,
                            unfocusedContainerColor = ConsoleSurface,
                            focusedBorderColor = MineHostBlue,
                            unfocusedBorderColor = Color(0xFF3A4A5D)
                        )
                    )
                    FilledIconButton(
                        onClick = {
                            if (command.isNotBlank()) {
                                onSendCommand(command)
                                command = ""
                            }
                        },
                        enabled = isRunning && command.isNotBlank(),
                        shape = RoundedCornerShape(12.dp),
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = MineHostBlue
                        )
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send command")
                    }
                }

                // Player list (if any)
                if (playerNames.isNotEmpty()) {
                    Text(
                        text = "Online: ${playerNames.joinToString(", ")}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MineHostTextSecondary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun MetricChip(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MineHostTextSecondary
        )
    }
}

@Composable
private fun getStatusColor(status: ServerStatus): Color {
    return when (status) {
        ServerStatus.ONLINE -> MineHostGreen
        ServerStatus.STOPPED -> MaterialTheme.colorScheme.error
        ServerStatus.STARTING -> MineHostBlue
        ServerStatus.STOPPING -> MaterialTheme.colorScheme.error.copy(alpha = 0.7f)
        ServerStatus.FAILED -> MaterialTheme.colorScheme.error
        ServerStatus.CRASHED -> MaterialTheme.colorScheme.error
        ServerStatus.WORLD_PROVISIONALLY_LOADED -> MineHostGreen.copy(alpha = 0.8f)
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
}

private fun consoleColor(line: String): Color = when {
    line.contains("error", true) || line.contains("exception", true) -> Color(0xFFFF5664)
    line.contains("warn", true) -> Color(0xFFFFB020)
    line.contains("join", true) || line.contains("done", true) -> Color(0xFF42DB7B)
    else -> ConsoleText
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return "${String.format("%.0f", kb)} KB"
    val mb = kb / 1024.0
    if (mb < 1024) return "${String.format("%.1f", mb)} MB"
    val gb = mb / 1024.0
    return "${String.format("%.1f", gb)} GB"
}

private fun formatDuration(millis: Long): String {
    val hours = TimeUnit.MILLISECONDS.toHours(millis)
    val minutes = TimeUnit.MILLISECONDS.toMinutes(millis) % 60
    return if (hours > 0) "${hours}h ${minutes}m" else "${minutes}m"
}
