package com.example.ui.screens.servers

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.MainViewModel
import com.example.ui.components.ToolCard
import com.example.ui.components.SectionTitle
import com.example.ui.theme.*

@Composable
fun ServerMoreScreen(
    viewModel: MainViewModel,
    serverId: String,
    onNavigateToActivity: () -> Unit,
    onNavigateToAiAssistant: () -> Unit,
    onNavigateToPerformance: () -> Unit,
    onNavigateToCrashAnalysis: () -> Unit,
    onNavigateToServerHealth: () -> Unit,
    onNavigateToAutoOptimization: () -> Unit,
    onNavigateToBackups: () -> Unit,
    onNavigateToWorlds: () -> Unit,
    onNavigateToNetwork: () -> Unit,
    onNavigateToEdit: () -> Unit,
    onDeleteServer: () -> Unit
) {
    val preparePlan by viewModel.preparePlan.collectAsState()
    val operationInProgress by viewModel.operationInProgress.collectAsState()
    val visiblePlan = preparePlan?.takeIf { it.serverUuid == serverId }

    if (visiblePlan != null) {
        AlertDialog(
            onDismissRequest = { if (!operationInProgress) viewModel.clearPrepareServerPlan() },
            title = { Text("Prepare this server?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    visiblePlan.components.forEach { component ->
                        Text("${component.label}: ${component.state.name.replace('_', ' ').lowercase()}")
                    }
                    Text("Required: ${visiblePlan.requiredBytes / (1024 * 1024)} MB")
                    Text("Available: ${visiblePlan.availableBytes / (1024 * 1024)} MB")
                    visiblePlan.warnings.forEach { Text(it) }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = visiblePlan.canProceed && !operationInProgress,
                    onClick = {
                        viewModel.clearPrepareServerPlan()
                        viewModel.prepareSelectedServer(serverId = serverId)
                    },
                ) { Text(if (operationInProgress) "Preparing…" else "Prepare") }
            },
            dismissButton = {
                TextButton(
                    enabled = !operationInProgress,
                    onClick = viewModel::clearPrepareServerPlan,
                ) { Text("Cancel") }
            },
        )
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        SectionTitle("Management")
        ToolCard("Edit Profile", "Change server name, engine, and resources", Icons.Outlined.Edit, MineHostBlue, BlueSoft, onNavigateToEdit)
        ToolCard("Delete Server", "Permanently remove this isolated profile", Icons.Outlined.Delete, MineHostRed, RedSoft, onDeleteServer)

        SectionTitle("Server Tools")
        ToolCard(
            "Prepare Server",
            "Verify and prepare the exact server runtime, engine files, and selected components",
            Icons.Outlined.Download,
            MineHostGreen,
            GreenSoft,
            { viewModel.calculatePrepareServerPlan(serverId = serverId) },
        )
        ToolCard("Activity", "View real server, backup, and settings events", Icons.Outlined.History, MineHostBlue, BlueSoft, onNavigateToActivity)
        ToolCard("AI Assistant", "Ask questions using local logs, metrics, and settings", Icons.Outlined.SmartToy, MineHostPurple, PurpleSoft, onNavigateToAiAssistant, badge = "Local")
        ToolCard("Performance Recommendations", "Review suggestions based on real local metrics", Icons.Outlined.Speed, MineHostBlue, BlueSoft, onNavigateToPerformance)
        ToolCard("Crash Analysis", "Inspect warnings, errors, and process failures", Icons.Outlined.BugReport, MineHostRed, RedSoft, onNavigateToCrashAnalysis)
        ToolCard("Server Health", "Check status, resource use, storage, and uptime", Icons.Outlined.HealthAndSafety, MineHostGreen, GreenSoft, onNavigateToServerHealth)
        ToolCard("Auto Optimization", "Back up and apply safe Bedrock settings", Icons.Outlined.AutoFixHigh, MineHostOrange, OrangeSoft, onNavigateToAutoOptimization)
        ToolCard("Backup Manager", "Create and restore real local server snapshots", Icons.Outlined.Backup, MineHostPurple, PurpleSoft, onNavigateToBackups)
        ToolCard("World Manager", "Import and switch real local world folders", Icons.Outlined.Public, MineHostGreen, GreenSoft, onNavigateToWorlds)
        ToolCard("Network & Friends", "Configure real FRP UDP tunnel and role-based access", Icons.Outlined.Router, MineHostBlue, BlueSoft, onNavigateToNetwork)
    }
}
