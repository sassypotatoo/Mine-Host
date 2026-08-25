package com.example.ui.screens.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.MainViewModel
import com.example.server.template.TemplateRegistry
import com.example.data.DashboardServerCardUiModel
import com.example.data.HealthSnapshot
import com.example.server.ServerStatus
import com.example.server.canStart
import com.example.server.version.EngineProtocolCompatibility
import com.example.server.version.EngineProtocolCompatibilityState
import com.example.server.isBlocking
import com.example.server.isTransitioning
import com.example.ui.components.*
import com.example.ui.navigation.MineHostDestination
import com.example.ui.theme.*

@Composable
fun DashboardScreen(
    viewModel: MainViewModel,
    onNavigate: (String) -> Unit
) {
    val runtimeStates by viewModel.runtimeStates.collectAsStateWithLifecycle()
    val metricsByServer by viewModel.metricsByServer.collectAsStateWithLifecycle()
    val playersByServer by viewModel.playersByServer.collectAsStateWithLifecycle()
    val profiles by viewModel.profiles.collectAsStateWithLifecycle()

    val hasProfile = profiles.isNotEmpty()

    // Every card observes its own UUID-owned runtime. Selection is only navigation.
    val serverCards = profiles.map { serverProfile ->
        val profileTemplate = TemplateRegistry.ALL_TEMPLATES.find { it.id == serverProfile.engineId }
            ?: TemplateRegistry.BEDROCK_POWER_NUKKIT_X
        val runtime = runtimeStates[serverProfile.id]
            ?: com.example.data.ActiveServerRuntimeState(serverId = serverProfile.id, status = ServerStatus.STOPPED)
        val metrics = metricsByServer[serverProfile.id]
        val version = viewModel.engineVersionFor(serverProfile.engineVersionId)
        val profileStatus = runtime.status
        val protocolCheck = version?.let {
            EngineProtocolCompatibility.evaluate(
                selectedBedrockVersion = serverProfile.bedrockVersion,
                advertisedBedrockVersion = runtime.advertisedMinecraftVersion,
                advertisedProtocol = runtime.advertisedProtocol,
                expectedProtocols = it.protocolVersions
            )
        }
        val runtimeCompatibilitySummary = when (protocolCheck?.state) {
            EngineProtocolCompatibilityState.VERSION_MISMATCH,
            EngineProtocolCompatibilityState.PROTOCOL_MISMATCH -> "Compatibility warning: ${protocolCheck?.message.orEmpty()}"
            EngineProtocolCompatibilityState.VERIFIED -> buildString {
                append("Advertised ")
                append(runtime.advertisedMinecraftVersion ?: serverProfile.bedrockVersion)
                runtime.advertisedProtocol?.let { append(" • protocol $it") }
            }
            else -> version?.compatibilitySummary ?: version?.compatibilityLabel
        }
        val blockingReason = when {
            !profileTemplate.available -> profileTemplate.unavailableReason ?: "Temporarily unavailable"
            version?.available == false -> version.unavailableReason ?: "This engine build is unavailable"
            else -> null
        }

        DashboardServerCardUiModel(
            serverId = serverProfile.id,
            serverName = serverProfile.name,
            engineName = profileTemplate.name,
            status = profileStatus,
            currentPlayers = if (profileStatus == ServerStatus.ONLINE) playersByServer[serverProfile.id].orEmpty().size else null,
            maxPlayers = serverProfile.maxPlayers,
            cpuPercent = metrics?.cpuPercent,
            ramUsedBytes = metrics?.ramBytes,
            ramLimitBytes = serverProfile.memoryMb * 1024L * 1024L,
            tps = metrics?.tps,
            uptimeMillis = runtime.uptimeMillis ?: metrics?.uptimeMillis,
            ipAddress = if (profileStatus == ServerStatus.ONLINE) viewModel.ipAddress.value else null,
            port = runtime.port ?: serverProfile.port,
            compatibilitySummary = runtimeCompatibilitySummary,
            customIconPath = serverProfile.iconPath,
            activeWorldPath = "${serverProfile.serverDirectory}/worlds/${serverProfile.levelName}",
            isActiveRuntime = profileStatus.isBlocking(),
            isProcessAlive = runtime.processAlive,
            activeOperationServerId = if (profileStatus.isBlocking()) serverProfile.id else null,
            isStartEnabled = profileStatus.canStart() && blockingReason == null && profileTemplate.available,
            isStopEnabled = profileStatus == ServerStatus.ONLINE || profileStatus.isTransitioning(),
            networkCompatibility = runtime.networkCompatibility,
            worldCompatibility = runtime.worldCompatibility,
            blockingReason = blockingReason
        )
    }

    // Greeting
    val greetingTitle = "Your Servers"
    val greetingSubtitle = if (hasProfile) {
        "Manage your local Bedrock engines."
    } else {
        "Create your first local Bedrock server to get started."
    }

    var showDeleteDialog by remember { mutableStateOf<String?>(null) }
    val serverToDelete = remember(showDeleteDialog, profiles) { profiles.find { it.id == showDeleteDialog } }

    if (showDeleteDialog != null && serverToDelete != null) {
        ConfirmationDialog(
            title = "Delete Server",
            message = "Are you sure you want to delete \"${serverToDelete.name}\"? This action cannot be undone.",
            confirmText = "Delete",
            destructive = true,
            onConfirm = {
                viewModel.deleteServer(serverToDelete.id)
                showDeleteDialog = null
            },
            onDismiss = { showDeleteDialog = null }
        )
    }

    MineHostScreen(
        contentPadding = PaddingValues(horizontal = 0.dp, vertical = 0.dp),
        modifier = Modifier.background(MineHostBackgroundTop)
    ) {
        Column(Modifier.padding(horizontal = 16.dp)) {
            MineHostBrandHeader(
                compact = true,
                onProfile = { onNavigate(MineHostDestination.Profile.route) },
                onNotifications = { onNavigate(MineHostDestination.Notifications.route) }
            )

            // Hero Banner
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(160.dp),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Box {
                    coil.compose.AsyncImage(
                        model = com.example.R.drawable.dashboard_hero_1784469064120,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = androidx.compose.ui.layout.ContentScale.Crop
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                Brush.verticalGradient(
                                    colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.4f))
                                )
                            )
                    )
                    Column(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(16.dp)
                    ) {
                        Text(
                            "Welcome to MineHost",
                            style = MaterialTheme.typography.titleMedium,
                            color = Color.White,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            "Your native Android server hub",
                            style = MaterialTheme.typography.labelMedium,
                            color = Color.White.copy(alpha = 0.8f)
                        )
                    }
                }
            }
            
            Spacer(Modifier.height(16.dp))

            // Quick Actions / Offline Preparation
            GlassCard(
                modifier = Modifier.fillMaxWidth(),
                onClick = { onNavigate(MineHostDestination.BatchDownload.route) }
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        color = MineHostBlue.copy(alpha = 0.15f),
                        shape = CircleShape,
                        modifier = Modifier.size(48.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.Outlined.CloudDownload,
                                contentDescription = null,
                                tint = MineHostBlue,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }
                    Spacer(Modifier.width(16.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "Download Required Files",
                            style = MaterialTheme.typography.titleSmall,
                            color = Color.White,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            "Prepare all runtimes and engines for faster offline setup.",
                            style = MaterialTheme.typography.labelMedium,
                            color = Color.White.copy(alpha = 0.6f)
                        )
                    }
                    Icon(
                        Icons.Default.ChevronRight,
                        contentDescription = null,
                        tint = Color.White.copy(alpha = 0.3f)
                    )
                }
            }

            Spacer(Modifier.height(24.dp))
            
            Text(greetingTitle, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold)
            Text(greetingSubtitle, style = MaterialTheme.typography.labelMedium, color = MineHostTextSecondary)
        }

        Spacer(Modifier.height(16.dp))

        // Your Servers Header (Removing redundant title if greeting is already "Your Servers", but let's make it a section)
        if (hasProfile) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Management", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.width(8.dp))
                    Surface(
                        color = MineHostBlue.copy(alpha = 0.1f),
                        shape = CircleShape
                    ) {
                        Text(
                            profiles.size.toString(),
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                            style = MaterialTheme.typography.labelMedium,
                            color = MineHostBlue,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                
                TextButton(
                    onClick = { onNavigate(MineHostDestination.ServerCreation.route) },
                    contentPadding = PaddingValues(horizontal = 8.dp)
                ) {
                    Icon(Icons.Default.AddCircleOutline, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("New Server", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge)
                }
            }
            Spacer(Modifier.height(8.dp))
        }

        // Servers List
        Column(
            modifier = Modifier.padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (profiles.isEmpty()) {
                CreateServerCard(onClick = { onNavigate(MineHostDestination.ServerCreation.route) })
            } else {
                serverCards.forEach { server ->
                    DashboardServerCard(
                        server = server,
                        onStart = { serverId ->
                            viewModel.startServer(serverId)
                        },
                        onStop = { serverId ->
                            viewModel.stopServer(serverId)
                        },
                        onDelete = { serverId ->
                            showDeleteDialog = serverId
                        },
                        onClick = { serverId ->
                            viewModel.selectServer(serverId)
                            onNavigate(MineHostDestination.ServerOverview.createRoute(serverId))
                        },
                        onFiles = { serverId ->
                            viewModel.selectServer(serverId)
                            onNavigate(MineHostDestination.ServerFiles.createRoute(serverId))
                        },
                        onSettings = { serverId ->
                            viewModel.selectServer(serverId)
                            onNavigate(MineHostDestination.ServerEdit.createRoute(serverId))
                        }
                    )
                }
            }
        }

        Spacer(Modifier.height(110.dp))
    }
}
