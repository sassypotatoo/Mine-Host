package com.example.ui.screens.servers

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.dp
import com.example.MainViewModel
import com.example.server.ServerStatus
import com.example.server.template.TemplateRegistry
import com.example.ui.components.*
import com.example.ui.theme.*

@Composable
fun ServerDetailScreen(
    viewModel: MainViewModel,
    serverId: String,
    onBack: () -> Unit,
    onOpenLegacyControls: () -> Unit,
    onNavigateToActivity: () -> Unit,
    onNavigateToAiAssistant: () -> Unit,
    onNavigateToPerformance: () -> Unit,
    onNavigateToCrashAnalysis: () -> Unit,
    onNavigateToServerHealth: () -> Unit,
    onNavigateToAutoOptimization: () -> Unit,
    onNavigateToBackups: () -> Unit,
    onNavigateToWorlds: () -> Unit,
    onNavigateToNetwork: () -> Unit,
    onNavigateToEdit: (String) -> Unit
) {
    var selectedTab by remember { mutableStateOf(ServerTab.Overview) }
    val profiles by viewModel.profiles.collectAsState()
    val profile = remember(serverId, profiles) { profiles.find { it.id == serverId } }
    val runtimeStates by viewModel.runtimeStates.collectAsState()
    val status = runtimeStates[serverId]?.status ?: ServerStatus.STOPPED
    val template = remember(profile) { TemplateRegistry.ALL_TEMPLATES.find { it.id == profile?.engineId } ?: TemplateRegistry.BEDROCK_POWER_NUKKIT_X }
    val ip by viewModel.ipAddress.collectAsState()
    val settings by viewModel.serverSettings.collectAsState()
    val worlds by viewModel.worlds.collectAsState()
    val activeWorld = worlds.find { it.name == (profile?.levelName ?: settings.levelName) }
    LaunchedEffect(serverId) { viewModel.selectServer(serverId) }

    Column(
        Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(MineHostBackgroundTop, MineHostBackgroundBottom))).padding(horizontal = 16.dp)
    ) {
        MineHostBrandHeader(showBack = true, onBack = onBack, compact = true)
        if (selectedTab != ServerTab.Overview) {
            ServerDetailHeader(
                serverName = profile?.name ?: "Local Bedrock Server",
                status = status,
                engine = template.name,
                address = "${ip.ifBlank { "Unavailable" }}:${profile?.port ?: settings.port}",
                customIconPath = profile?.iconPath,
                activeWorldPath = activeWorld?.path
            )
            Spacer(Modifier.height(10.dp))
        }
        ServerTabBar(selectedTab, { selectedTab = it })
        Spacer(Modifier.height(10.dp))
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (selectedTab) {
                ServerTab.Overview -> ServerOverviewScreen(viewModel, serverId, onOpenLegacyControls)
                ServerTab.Console -> ServerConsoleScreen(viewModel, serverId)
                ServerTab.Players -> ServerPlayersScreen(viewModel, serverId)
                ServerTab.Settings -> ServerSettingsScreen(viewModel, serverId)
                ServerTab.More -> ServerMoreScreen(
                    viewModel, serverId, onNavigateToActivity, onNavigateToAiAssistant,
                    onNavigateToPerformance, onNavigateToCrashAnalysis, onNavigateToServerHealth,
                    onNavigateToAutoOptimization,
                    onNavigateToBackups,
                    onNavigateToWorlds,
                    onNavigateToNetwork,
                    { onNavigateToEdit(serverId) },
                    { viewModel.deleteServer(serverId); onBack() }
                )
            }
        }
    }
}
