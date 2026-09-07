package com.example.ui.screens.tools

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.Unit.dp
import androidx.compose.ui.graphics.Color
import com.example.MainViewModel
import com.example.ui.components.MineHostBrandHeader
import com.example.ui.components.MineHostScreen
import com.example.ui.navigation.MineHostDestination
import com.example.ui.screens.servers.ServerConsoleScreen
import com.example.ui.theme.MineHostBackgroundTop
import com.example.ui.theme.MineHostTextSecondary
import com.example.server.ServerStatus

@Composable
fun ConsoleTabScreen(viewModel: MainViewModel, onNavigate: (String) -> Unit) {
    val selectedServerId by viewModel.selectedServerId.collectAsState()
    val logsByServer by viewModel.logsByServer.collectAsState()
    val runtimeStates by viewModel.runtimeStates.collectAsState()
    val profiles by viewModel.profiles.collectAsState()

    // Get a distinct list of server IDs from logsByServer and runtimeStates
    val serverIds = (logsByServer.keys + runtimeStates.keys).distinct().sortedBy {
        profiles.firstOrNull { it.id == it }?.name ?: it
    }

    val selectedTabIndex = serverIds.indexOf(firstOrNull { it == selectedServerId })?.coerceAtLeast(0) ?: 0

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

            // TabRow for server tabs
            if (serverIds.isNotEmpty()) {
                TabRow(
                    selectedIndex = selectedTabIndex,
                    indicator = { tabPositions ->
                        // Default indicator (can be customized if needed)
                        TabDefaults.indicator(tabPositions, color = MaterialTheme.colorScheme.primary)
                    },
                    backgroundColor = MaterialTheme.colorScheme.surfaceVariant,
                    divider = {
                        // Default divider (can be customized if needed)
                        TabDefaults.divider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 1.dp)
                    }
                ) {
                    serverIds.forEach { serverId ->
                        Tab(
                            text = {
                                val profile = profiles.firstOrNull { it.id == serverId }
                                val serverName = profile?.name ?: serverId
                                val status = runtimeStates[serverId]?.status ?: ServerStatus.UNKNOWN
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Text(serverName)
                                    // Status indicator
                                    Box(
                                        modifier = Modifier
                                            .size(8.dp)
                                            .background(getStatusColor(status))
                                            .clip(CircleShape)
                                    )
                                }
                            },
                            onClick = {
                                // Update the selected server in the ViewModel
                                viewModel.selectedServerId.value = serverId
                            }
                        )
                    }
                }
            } else {
                // Show a message when no servers are available
                Text(
                    "No servers available",
                    color = MineHostTextSecondary,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .fillMaxWidth()
                        .padding(top = 24.dp)
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Content for the selected tab
            if (selectedServerId != null) {
                ServerConsoleScreen(viewModel, selectedServerId)
            } else if (serverIds.isEmpty()) {
                // Already handled above, but just in case
                Text(
                    "No servers available",
                    color = MineHostTextSecondary,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.align(Alignment.Center)
                )
            } else {
                // This case shouldn't happen, but show a placeholder
                Text(
                    "Select a server to view its console",
                    color = MineHostTextSecondary,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.align(Alignment.Center)
                )
            }

            Spacer(modifier = Modifier.height(110.dp)) // Padding for bottom bar
        }
    }

    // Helper function to get color for server status
    @Composable
    private fun getStatusColor(status: ServerStatus): Color {
        return when (status) {
            ServerStatus.ONLINE -> MaterialTheme.colorScheme.success
            ServerStatus.STOPPED -> MaterialTheme.colorScheme.error
            ServerStatus.STARTING -> MaterialTheme.colorScheme.warning
            ServerStatus.STOPPING -> MaterialTheme.colorScheme.warning
            ServerStatus.FAILED -> MaterialTheme.colorScheme.error
            ServerStatus.CRASHED -> MaterialTheme.colorScheme.error
            else -> MaterialTheme.colorScheme.onSurfaceVariant // for UNKNOWN or other statuses
        }
    }
}