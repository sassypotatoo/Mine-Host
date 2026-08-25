package com.example.ui.screens.servers

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.MainViewModel
import com.example.server.ServerStatus
import com.example.server.template.TemplateRegistry
import com.example.ui.components.*
import com.example.ui.components.ServerWorldArtwork
import com.example.ui.theme.*

@Composable
fun ServerManagementScreen(
    viewModel: MainViewModel,
    onNavigateToServer: (String) -> Unit,
    onCreateServer: () -> Unit = {}
) {
    val uiState by viewModel.dashboardUiState.collectAsStateWithLifecycle()
    val profiles by viewModel.profiles.collectAsState()
    val selectedId by viewModel.selectedServerId.collectAsState()
    val runtimeStates by viewModel.runtimeStates.collectAsState()
    val metricsByServer by viewModel.metricsByServer.collectAsState()
    val playersByServer by viewModel.playersByServer.collectAsState()
    var search by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf(ServerListFilter.ALL) }
    
    val filteredProfiles = profiles.filter { profile ->
        val profileStatus = runtimeStates[profile.id]?.status ?: ServerStatus.STOPPED
        (search.isBlank() || profile.name.contains(search, ignoreCase = true)) &&
        when (filter) {
            ServerListFilter.ALL -> true
            ServerListFilter.RUNNING -> profileStatus != ServerStatus.STOPPED && profileStatus != ServerStatus.FAILED && profileStatus != ServerStatus.CRASHED
            ServerListFilter.STOPPED -> profileStatus == ServerStatus.STOPPED || profileStatus == ServerStatus.FAILED || profileStatus == ServerStatus.CRASHED
        }
    }

    val runningCount = profiles.count { profile ->
        val state = runtimeStates[profile.id]?.status ?: ServerStatus.STOPPED
        state != ServerStatus.STOPPED && state != ServerStatus.FAILED && state != ServerStatus.CRASHED
    }
    val stoppedCount = profiles.size - runningCount

    MineHostScreen(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 0.dp)) {
        MineHostBrandHeader()
        Box(Modifier.fillMaxWidth().height(150.dp)) {
            Column(Modifier.align(Alignment.CenterStart).fillMaxWidth(.62f)) {
                MineHostPageTitle("Server Management", "View and control your isolated Minecraft server profiles.")
            }
            ServerWorldArtwork(
                customIconPath = null,
                activeWorldPath = null,
                modifier = Modifier.align(Alignment.CenterEnd).size(145.dp),
                corner = 28.dp
            )
        }

        GlassCard(Modifier.fillMaxWidth()) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                    PastelIcon(Icons.Outlined.Storage, MineHostBlue, BlueSoft, size = 46.dp)
                    Spacer(Modifier.width(10.dp))
                    Column { 
                        Text("${profiles.size}", style = MaterialTheme.typography.headlineSmall)
                        Text("Total Profiles", style = MaterialTheme.typography.bodySmall, color = MineHostTextSecondary) 
                    }
                }
                
                Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                    PastelIcon(
                        if (runningCount > 0) Icons.Outlined.PlayCircle else Icons.Outlined.StopCircle, 
                        if (runningCount > 0) MineHostGreen else MineHostRed, 
                        if (runningCount > 0) GreenSoft else RedSoft, 
                        size = 46.dp
                    )
                    Spacer(Modifier.width(10.dp))
                    Column { 
                        Text("$runningCount", style = MaterialTheme.typography.headlineSmall)
                        Text("Running", style = MaterialTheme.typography.bodySmall, color = MineHostTextSecondary) 
                    }
                }
            }
        }

        if (profiles.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = search, onValueChange = { search = it }, modifier = Modifier.weight(1f), singleLine = true,
                    placeholder = { Text("Search servers…") }, leadingIcon = { Icon(Icons.Outlined.Search, null) },
                    shape = RoundedCornerShape(18.dp), colors = OutlinedTextFieldDefaults.colors(unfocusedContainerColor = Color.White.copy(alpha = .55f), focusedContainerColor = Color.White.copy(alpha = .78f))
                )
                SoftIconButton(
                    Icons.Default.Add,
                    "Create Profile",
                    onCreateServer,
                    modifier = Modifier.align(Alignment.CenterVertically)
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                FilterChip(filter == ServerListFilter.ALL, { filter = ServerListFilter.ALL }, { Text("All ${profiles.size}") })
                FilterChip(filter == ServerListFilter.RUNNING, { filter = ServerListFilter.RUNNING }, { Text("Running $runningCount") })
                FilterChip(filter == ServerListFilter.STOPPED, { filter = ServerListFilter.STOPPED }, { Text("Stopped $stoppedCount") })
            }

            SectionTitle("Server Profiles")
            if (filteredProfiles.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    filteredProfiles.forEach { profile ->
                        val isActive = profile.id == selectedId
                        val profileStatus = runtimeStates[profile.id]?.status ?: ServerStatus.STOPPED
                        val profileMetrics = metricsByServer[profile.id]
                        ServerSummaryCard(
                            name = profile.name,
                            status = profileStatus,
                            engine = TemplateRegistry.ALL_TEMPLATES.find { it.id == profile.engineId }?.name ?: "Unknown",
                            memory = profileMetrics?.ramBytes?.let { formatBytes(it) } ?: "${profile.memoryMb} MB",
                            players = if (profileStatus == ServerStatus.ONLINE) "${playersByServer[profile.id].orEmpty().size} players" else "Port: ${profile.port}",
                            customIconPath = profile.iconPath,
                            onClick = { 
                                if (!isActive) viewModel.selectServer(profile.id)
                                onNavigateToServer(profile.id) 
                            },
                            featured = isActive
                        )
                    }
                }
            } else {
                EmptyState("No profiles found", "Try a different search term.", icon = Icons.Outlined.Search)
            }
        } else {
            Spacer(Modifier.height(16.dp))
            SectionTitle("Get Started")
            GlassCard(Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(24.dp).fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    PastelIcon(Icons.Outlined.Dns, MineHostBlue, BlueSoft, size = 64.dp)
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("No server created yet", style = MaterialTheme.typography.titleLarge)
                        Text(
                            "Create your first local Bedrock server to begin hosting.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MineHostTextSecondary,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }
                    MineHostButton(
                        text = "Create Server",
                        onClick = onCreateServer,
                        modifier = Modifier.fillMaxWidth(),
                        icon = Icons.Outlined.AddCircle
                    )
                }
            }
        }

        if (profiles.isNotEmpty()) {
            GlassCard(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { Text("Uptime", style = MaterialTheme.typography.labelMedium); Text(uiState.uptimeMillis?.let { formatDuration(it) } ?: "—", color = MineHostGreen, style = MaterialTheme.typography.titleMedium) }
                    MiniLineChart(MineHostGreen, Modifier.weight(1f))
                    Spacer(Modifier.width(14.dp))
                    Column { Text("Avg. TPS", style = MaterialTheme.typography.labelMedium); Text(uiState.tps?.let { "%.1f".format(it) } ?: "—", style = MaterialTheme.typography.titleMedium) }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
    }
}

private enum class ServerListFilter { ALL, RUNNING, STOPPED }

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024 * 1024 -> "%.1f GB".format(bytes / (1024f * 1024 * 1024))
    bytes >= 1024L * 1024 -> "%.0f MB".format(bytes / (1024f * 1024))
    else -> "${bytes / 1024} KB"
}
private fun formatDuration(ms: Long): String { val h=ms/3_600_000; val d=h/24; return if(d>0) "${d}d ${h%24}h" else "${h}h ${(ms/60_000)%60}m" }
