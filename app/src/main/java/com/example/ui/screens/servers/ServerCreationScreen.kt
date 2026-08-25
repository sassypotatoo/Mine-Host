package com.example.ui.screens.servers

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.dp
import com.example.MainViewModel
import com.example.data.ServerCreationDraft
import com.example.data.ServerProfileChanges
import com.example.server.Downloader
import com.example.server.ServerStatus
import com.example.server.template.TemplateRegistry
import com.example.ui.components.GlassCard
import com.example.ui.components.MineHostBrandHeader
import com.example.ui.components.MineHostButton
import com.example.ui.components.MineHostPageTitle
import com.example.ui.components.PastelIcon
import com.example.server.version.EngineVersionCatalogRepository
import com.example.server.version.EngineInstallability
import com.example.server.version.installability
import com.example.ui.theme.BlueSoft
import com.example.ui.theme.MineHostBackgroundBottom
import com.example.ui.theme.MineHostBackgroundTop
import com.example.ui.theme.MineHostBlue
import com.example.ui.theme.MineHostTextSecondary

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.material.icons.filled.AddAPhoto
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
@Composable
fun ServerCreationScreen(
    viewModel: MainViewModel,
    serverId: String? = null,
    onBack: () -> Unit,
    onDone: () -> Unit
) {
    val profiles by viewModel.profiles.collectAsState()
    val isEditMode = serverId != null
    val existingProfile = remember(serverId, profiles) { profiles.find { it.id == serverId } }
    
    val status by viewModel.status.collectAsState()
    val saveResult by viewModel.saveResult.collectAsState()
    val canConfigure = status == ServerStatus.STOPPED || status == ServerStatus.FAILED || status == ServerStatus.CRASHED
    
    var serverName by remember(existingProfile) { mutableStateOf(existingProfile?.name ?: "") }
    var engineId by remember(existingProfile) { mutableStateOf(existingProfile?.engineId ?: TemplateRegistry.BEDROCK_POWER_NUKKIT_X.id) }
    var engineVersionId by remember(existingProfile) { mutableStateOf(existingProfile?.engineVersionId ?: "") }
    var bedrockVersion by remember(existingProfile) { mutableStateOf(existingProfile?.bedrockVersion ?: "") }
    var levelName by remember(existingProfile) { mutableStateOf(existingProfile?.levelName ?: "world") }
    var memoryMb by remember(existingProfile) { mutableStateOf(existingProfile?.memoryMb ?: 600) }
    var maxPlayers by remember(existingProfile) { mutableStateOf(existingProfile?.maxPlayers ?: 10) }
    var port by remember(existingProfile) { mutableStateOf(existingProfile?.port ?: 19132) }
    var selectedIconUri by remember { mutableStateOf<Uri?>(null) }

    val context = androidx.compose.ui.platform.LocalContext.current
    val catalog = (context.applicationContext as? com.example.MineHostApplication)?.catalogRepository

    androidx.compose.runtime.LaunchedEffect(saveResult) {
        if (saveResult is MainViewModel.SaveServerResult.Success) {
            viewModel.clearSaveResult()
            onDone()
        }
    }

    // Logic for engine change (Part 1)
    val onEngineChange: (String) -> Unit = { newEngineId ->
        if (engineId != newEngineId) {
            engineId = newEngineId
            val defaultVersion = catalog?.getDefaultVersion(newEngineId)
            engineVersionId = defaultVersion?.id ?: ""
            bedrockVersion = defaultVersion?.recommendedBedrockVersion ?: ""
        }
    }

    // Initialize version if empty (e.g. first create)
    remember(engineId) {
        if (engineVersionId.isEmpty()) {
            val defaultVersion = catalog?.getDefaultVersion(engineId)
            engineVersionId = defaultVersion?.id ?: ""
            bedrockVersion = defaultVersion?.recommendedBedrockVersion ?: ""
        }
        Unit
    }

    val selectedTemplate = remember(engineId) { 
        TemplateRegistry.ALL_TEMPLATES.find { it.id == engineId } ?: TemplateRegistry.BEDROCK_POWER_NUKKIT_X 
    }
    val selectedEngineVersion = catalog?.findVersion(engineVersionId)
    val selectedEngineReady = selectedEngineVersion != null &&
        selectedEngineVersion.installability() != EngineInstallability.UNAVAILABLE &&
        Downloader.getTrustedChecksumForInstall(context, selectedEngineVersion) != null

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
        onResult = { uri -> selectedIconUri = uri }
    )
    
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
            MineHostPageTitle(
                title = if (isEditMode) "Edit Server Profile" else "Create New Server",
                subtitle = if (isEditMode) {
                    "Modify the configuration of '${existingProfile?.name ?: "this server"}'."
                } else {
                    "Set up a new isolated Minecraft server profile."
                }
            )

            GlassCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("Server Artwork", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.size(12.dp))
                    Box(
                        modifier = Modifier
                            .size(120.dp)
                            .clip(RoundedCornerShape(20.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .clickable {
                                photoPickerLauncher.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                )
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        if (selectedIconUri != null) {
                            AsyncImage(
                                model = selectedIconUri,
                                contentDescription = "Selected icon",
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop
                            )
                        } else {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(
                                    Icons.Default.PhotoLibrary,
                                    contentDescription = null,
                                    tint = MineHostBlue,
                                    modifier = Modifier.size(32.dp)
                                )
                                Spacer(Modifier.size(4.dp))
                                Text(
                                    "Pick Icon",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MineHostBlue
                                )
                            }
                        }
                    }
                    Spacer(Modifier.size(15.dp))
                    Text("Server name", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.size(7.dp))
                    OutlinedTextField(
                        value = serverName,
                        onValueChange = { serverName = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        placeholder = { Text("e.g. My Survival Server") },
                        shape = RoundedCornerShape(16.dp)
                    )
                    Spacer(Modifier.size(15.dp))
                    Text("Server engine", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.size(8.dp))
                    val availableTemplates = remember(catalog, isEditMode, existingProfile?.engineId) {
                        TemplateRegistry.ALL_TEMPLATES.filter { template ->
                            val hasInstallableBuild = catalog?.getVersionsForEngine(template.id)?.any { version ->
                                version.installability() != EngineInstallability.UNAVAILABLE &&
                                    Downloader.getTrustedChecksumForInstall(context, version) != null
                            } == true
                            hasInstallableBuild || (isEditMode && existingProfile?.engineId == template.id)
                        }
                    }
                    if (availableTemplates.isEmpty() && !isEditMode) {
                        Text(
                            "No engine build is currently authorized for installation in this screen. Use the guided creation flow to provide and verify a manual JAR when publisher checksums are unavailable.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    availableTemplates.forEach { template ->
                        FilterChip(
                            selected = engineId == template.id,
                            onClick = { onEngineChange(template.id) },
                            label = { Text(template.name) },
                            leadingIcon = {
                                Icon(Icons.Outlined.Dns, contentDescription = null)
                            }
                        )
                    }
                }
            }

            GlassCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("Resources", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.size(8.dp))
                    Text(
                        "Memory: ${memoryMb} MB",
                        color = MineHostTextSecondary
                    )
                    Slider(
                        value = memoryMb.toFloat(),
                        onValueChange = {
                            memoryMb = (it / 64).toInt() * 64
                        },
                        valueRange = 384f..4096f
                    )
                    Text(
                        "Max players: ${maxPlayers}",
                        color = MineHostTextSecondary
                    )
                    Slider(
                        value = maxPlayers.toFloat(),
                        onValueChange = {
                            maxPlayers = it.toInt()
                        },
                        valueRange = 1f..100f
                    )
                    Text("Port", style = MaterialTheme.typography.titleMedium)
                    OutlinedTextField(
                        value = port.toString(),
                        onValueChange = { value ->
                            value.toIntOrNull()?.let { p ->
                                port = p
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        shape = RoundedCornerShape(16.dp)
                    )
                }
            }

            GlassCard(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(15.dp)) {
                    PastelIcon(
                        icon = Icons.Outlined.Info,
                        tint = MineHostBlue,
                        background = BlueSoft,
                        size = 44.dp
                    )
                    Spacer(Modifier.size(12.dp))
                    Text(
                        if (canConfigure) {
                            if (isEditMode) "Updating settings might require a server restart to take effect."
                            else if (!selectedEngineReady) {
                                "Profile creation is blocked here because this build has neither a trusted publisher SHA-256 nor an exact verified cached JAR. Use the guided creation flow for manual verification."
                            } else "Each server profile has its own isolated directory and settings. You can switch between them from the server list."
                        } else {
                            "Stop the running server before changing its configuration."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MineHostTextSecondary
                    )
                }
            }

            MineHostButton(
                text = if (isEditMode) "Save Changes" else "Create Profile",
                onClick = {
                    if (isEditMode) {
                        viewModel.updateServer(
                            serverId!!,
                            ServerProfileChanges(
                                name = serverName,
                                engineId = engineId,
                                memoryMb = memoryMb,
                                maxPlayers = maxPlayers,
                                port = port,
                                engineVersionId = engineVersionId,
                                bedrockVersion = bedrockVersion
                            ),
                            selectedIconUri
                        )
                    } else {
                        viewModel.createServer(
                            ServerCreationDraft(
                                name = serverName,
                                engineId = engineId,
                                memoryMb = memoryMb,
                                maxPlayers = maxPlayers,
                                port = port,
                                engineVersionId = engineVersionId,
                                bedrockVersion = bedrockVersion,
                                worldSeed = com.example.world.WorldSeedFactory.next(),
                                worldSeedMode = com.example.server.engine.WorldSeedMode.RANDOM,
                                worldSeedKnown = true
                            ),
                            selectedIconUri
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                icon = Icons.Outlined.Save,
                enabled = serverName.isNotBlank() && canConfigure &&
                    (isEditMode || selectedEngineReady)
            )
            Spacer(Modifier.size(22.dp))
        }
    }
}
