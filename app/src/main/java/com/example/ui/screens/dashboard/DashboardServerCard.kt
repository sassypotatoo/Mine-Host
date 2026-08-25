package com.example.ui.screens.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import com.example.data.DashboardServerCardUiModel
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import android.content.Intent
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.server.ServerStatus
import com.example.server.isBlocking
import com.example.server.isActiveOperation
import com.example.server.isTransitioning
import com.example.ui.components.GlassCard
import com.example.ui.components.PastelIcon
import com.example.ui.components.ServerWorldArtwork
import com.example.ui.components.StatusBadge
import com.example.ui.theme.*

@Composable
fun DashboardServerCard(
    server: DashboardServerCardUiModel,
    onStart: (String) -> Unit,
    onStop: (String) -> Unit,
    onDelete: (String) -> Unit,
    onClick: (String) -> Unit,
    onFiles: (String) -> Unit = {},
    onSettings: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val isBusy = server.status.isActiveOperation()
    val canStop = server.status.isBlocking()
    val isStopped = server.status == ServerStatus.STOPPED
    val isOnline = server.status == ServerStatus.ONLINE // Added by AI Studio

    val connectionString = if (server.ipAddress != null) "${server.ipAddress}:${server.port}" else "127.0.0.1:${server.port}"

    fun formatUptime(millis: Long?): String {
        if (millis == null) return "00:00:00"
        val seconds = (millis / 1000) % 60
        val minutes = (millis / (1000 * 60)) % 60
        val hours = millis / (1000 * 60 * 60)
        return "%02d:%02d:%02d".format(hours, minutes, seconds)
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        onClick = { onClick(server.serverId) }
    ) {
        Column(Modifier.padding(16.dp)) {
            // Status Header Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val statusColor = when (server.status) {
                        ServerStatus.ONLINE,
                        ServerStatus.WORLD_VERIFIED -> MineHostGreen
                        ServerStatus.STOPPED -> MineHostTextSecondary.copy(alpha = 0.6f)
                        ServerStatus.STARTING,
                        ServerStatus.PREPARING,
                        ServerStatus.DOWNLOADING,
                        ServerStatus.PROCESS_STARTED,
                        ServerStatus.NETWORK_READY,
                        ServerStatus.ENGINE_READY,
                        ServerStatus.WORLD_PROVISIONALLY_LOADED,
                        ServerStatus.STOPPING -> MineHostOrange
                        else -> MineHostRed
                    }
                    val statusText = when (server.status) {
                        ServerStatus.ONLINE -> "ONLINE"
                        ServerStatus.STOPPED -> "OFFLINE"
                        ServerStatus.STARTING -> "STARTING SERVER..."
                        ServerStatus.STOPPING -> "STOPPING SERVER..."
                        else -> server.status.name
                    }
                    
                    Box(Modifier.size(8.dp).background(statusColor, CircleShape))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.ExtraBold,
                        color = statusColor,
                        letterSpacing = 0.5.sp
                    )
                }
                
                if (!isStopped) {
                    Text(
                        text = formatUptime(server.uptimeMillis),
                        style = MaterialTheme.typography.labelMedium,
                        color = MineHostTextSecondary
                    )
                }
            }
            
            Spacer(Modifier.height(12.dp))
            
            // Server Info Row
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    modifier = Modifier.size(64.dp),
                    shape = RoundedCornerShape(16.dp),
                    color = MineHostBackgroundBottom
                ) {
                    ServerWorldArtwork(
                        customIconPath = server.customIconPath,
                        activeWorldPath = server.activeWorldPath,
                        modifier = Modifier.fillMaxSize(),
                        corner = 16.dp
                    )
                }
                
                Spacer(Modifier.width(12.dp))
                
                Column(Modifier.weight(1f)) {
                    Text(
                        text = server.serverName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.ExtraBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = server.engineName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MineHostTextSecondary
                    )
                    if (server.compatibilitySummary != null) {
                        Text(
                            text = server.compatibilitySummary,
                            style = MaterialTheme.typography.labelSmall,
                            color = MineHostOrange,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    if (server.status != ServerStatus.STOPPED) {
                        Spacer(Modifier.height(4.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            com.example.ui.components.CompatibilityBadge(label = "Net", status = server.networkCompatibility)
                            Spacer(Modifier.width(8.dp))
                            com.example.ui.components.CompatibilityBadge(label = "World", status = server.worldCompatibility)
                        }
                    }
                }
            }
            
            // Metrics and Connection Row (Only if not stopped)
            if (!isStopped) {
                Spacer(Modifier.height(12.dp))
                
                if (isOnline) {
                    // Connection Info
                    Surface(
                        color = MineHostBackgroundBottom.copy(alpha = 0.5f),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Outlined.Link, null, modifier = Modifier.size(16.dp), tint = MineHostBlue)
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    connectionString,
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = MineHostBlue
                                )
                            }
                            Row {
                                TextButton(
                                    onClick = { clipboardManager.setText(AnnotatedString(connectionString)) },
                                    contentPadding = PaddingValues(horizontal = 8.dp)
                                ) {
                                    Text("Copy", style = MaterialTheme.typography.labelSmall)
                                }
                                TextButton(
                                    onClick = {
                                        val sendIntent: Intent = Intent().apply {
                                            action = Intent.ACTION_SEND
                                            putExtra(Intent.EXTRA_TEXT, "Join my Minecraft server at $connectionString")
                                            type = "text/plain"
                                        }
                                        val shareIntent = Intent.createChooser(sendIntent, null)
                                        context.startActivity(shareIntent)
                                    },
                                    contentPadding = PaddingValues(horizontal = 8.dp)
                                ) {
                                    Text("Share", style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }

                Surface(
                    color = MineHostBackgroundBottom.copy(alpha = 0.3f),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp, horizontal = 4.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        MetricItem(
                            icon = Icons.Outlined.Speed,
                            label = "CPU",
                            value = server.cpuPercent?.let { "${it.toInt()}%" } ?: "—"
                        )
                        Box(Modifier.width(1.dp).height(16.dp).background(MineHostOutline.copy(alpha = 0.1f)))
                        MetricItem(
                            icon = Icons.Outlined.Timer,
                            label = "TPS",
                            value = server.tps?.let { "%.1f".format(it) } ?: "—"
                        )
                        Box(Modifier.width(1.dp).height(16.dp).background(MineHostOutline.copy(alpha = 0.1f)))
                        MetricItem(
                            icon = Icons.Outlined.Memory,
                            label = "RAM",
                            value = server.ramUsedBytes?.let { formatBytes(it) } ?: "—"
                        )
                    }
                }
            }
            
            Spacer(Modifier.height(12.dp))
            
            // Actions Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Primary Action Button (Start/Stop)
                Button(
                    onClick = { if (canStop) onStop(server.serverId) else onStart(server.serverId) },
                    modifier = Modifier.weight(1.5f).height(52.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (canStop) MineHostRed else MineHostGreen,
                        disabledContainerColor = if (canStop) MineHostRed.copy(alpha = 0.5f) else MineHostGreen.copy(alpha = 0.5f),
                        contentColor = Color.White
                    ),
                    elevation = ButtonDefaults.buttonElevation(0.dp),
                    enabled = if (canStop) server.isStopEnabled else server.isStartEnabled,
                    contentPadding = PaddingValues(horizontal = 12.dp)
                ) {
                    if (isBusy) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
                    } else {
                        Icon(if (canStop) Icons.Default.Stop else Icons.Default.PlayArrow, null, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        val buttonText = when {
                            canStop -> "Stop"
                            server.blockingReason != null -> "Busy"
                            else -> "Start"
                        }
                        Text(
                            buttonText,
                            fontWeight = FontWeight.ExtraBold,
                            maxLines = 1,
                            overflow = TextOverflow.Clip
                        )
                    }
                }
                
                // Secondary Action Buttons
                ActionIconButton(Icons.Outlined.Folder, "Files", BlueSoft, MineHostBlue) { onFiles(server.serverId) }
                ActionIconButton(Icons.Outlined.Settings, "Settings", BlueSoft, MineHostBlue) { onSettings(server.serverId) }
                
                // Delete Button - Disabled if running
                ActionIconButton(
                    icon = Icons.Outlined.Delete, 
                    contentDescription = "Delete", 
                    background = if (canStop || isBusy) Color.LightGray.copy(alpha = 0.1f) else RedSoft, 
                    tint = if (canStop || isBusy) MineHostTextSecondary.copy(alpha = 0.3f) else MineHostRed,
                    enabled = !canStop && !isBusy
                ) { 
                    onDelete(server.serverId) 
                }
            }

            if (server.blockingReason != null && !isOnline) {
                Spacer(Modifier.height(8.dp))
                Text(
                    server.blockingReason,
                    style = MaterialTheme.typography.labelSmall,
                    color = MineHostRed,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
private fun ActionIconButton(
    icon: ImageVector,
    contentDescription: String,
    background: Color,
    tint: Color,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Surface(
        onClick = if (enabled) onClick else ({}),
        modifier = Modifier.size(52.dp),
        shape = RoundedCornerShape(12.dp),
        color = background,
        border = androidx.compose.foundation.BorderStroke(1.dp, tint.copy(alpha = 0.1f)),
        enabled = enabled
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription, tint = tint, modifier = Modifier.size(22.dp))
        }
    }
}

@Composable
private fun MetricItem(
    icon: ImageVector,
    label: String,
    value: String
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, modifier = Modifier.size(16.dp), tint = MineHostTextSecondary)
        Spacer(Modifier.width(6.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MineHostTextSecondary,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.width(8.dp))
        Text(
            value,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.ExtraBold,
            color = MineHostTextPrimary
        )
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024 * 1024 -> "%.1f GB".format(bytes / (1024f * 1024 * 1024))
    bytes >= 1024L * 1024 -> "%.0f MB".format(bytes / (1024f * 1024))
    else -> "${bytes / 1024} KB"
}
