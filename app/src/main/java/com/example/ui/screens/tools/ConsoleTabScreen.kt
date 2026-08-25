package com.example.ui.screens.tools

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.MainViewModel
import com.example.ui.components.MineHostBrandHeader
import com.example.ui.components.MineHostScreen
import com.example.ui.navigation.MineHostDestination
import com.example.ui.screens.servers.ServerConsoleScreen
import com.example.ui.theme.MineHostBackgroundTop
import com.example.ui.theme.MineHostTextSecondary

@Composable
fun ConsoleTabScreen(viewModel: MainViewModel, onNavigate: (String) -> Unit) {
    val selectedServerId by viewModel.selectedServerId.collectAsState()
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
            
            Text("Live Console", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold)
            Text("Direct stream from the active server process.", style = MaterialTheme.typography.labelMedium, color = MineHostTextSecondary)
        }

        Spacer(Modifier.height(16.dp))

        Box(modifier = Modifier.padding(horizontal = 16.dp).weight(1f)) {
            val serverId = selectedServerId
            if (serverId == null) {
                Text("Select a server profile to view its real console.", color = MineHostTextSecondary)
            } else {
                ServerConsoleScreen(viewModel, serverId)
            }
        }
        
        Spacer(Modifier.height(110.dp)) // Padding for bottom bar
    }
}
