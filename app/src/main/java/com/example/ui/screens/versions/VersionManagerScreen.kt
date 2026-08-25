package com.example.ui.screens.versions

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Power
import androidx.compose.material.icons.outlined.ViewInAr
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.example.MainViewModel
import com.example.server.ServerStatus
import com.example.server.version.EngineVersion
import com.example.server.template.ServerTemplate
import com.example.server.template.TemplateRegistry
import com.example.ui.components.GlassCard
import com.example.ui.components.MineHostBrandHeader
import com.example.ui.components.MineHostButton
import com.example.ui.components.MineHostPageTitle
import com.example.ui.components.PastelIcon
import com.example.ui.components.ServerThumbnail
import com.example.ui.components.StatusBadge
import com.example.ui.theme.BlueSoft
import com.example.ui.theme.GreenSoft
import com.example.ui.theme.MineHostBackgroundBottom
import com.example.ui.theme.MineHostBackgroundTop
import com.example.ui.theme.MineHostBlue
import com.example.ui.theme.MineHostDivider
import com.example.ui.theme.MineHostGreen
import com.example.ui.theme.MineHostOrange
import com.example.ui.theme.MineHostRed
import com.example.ui.theme.MineHostTextSecondary
import com.example.ui.theme.MineHostYellow
import com.example.ui.theme.OrangeSoft
import com.example.ui.theme.RedSoft
import com.example.server.updates.ReleaseVerificationStatus
import com.example.server.updates.DetectedEngineRelease
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Update
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts

@Composable
fun VersionManagerScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit,
    serverId: String? = null,
) {
    val active by viewModel.activeTemplate.collectAsState()
    val activeVersion by viewModel.activeEngineVersion.collectAsState()
    val selectedServerId by viewModel.selectedServerId.collectAsState()
    val runtimeStates by viewModel.runtimeStates.collectAsState()
    val ownerId = serverId ?: selectedServerId
    val status = ownerId?.let { runtimeStates[it]?.status } ?: com.example.server.ServerStatus.STOPPED
    LaunchedEffect(ownerId) { ownerId?.let { if (serverId != null) viewModel.selectServer(it) } }
    val context = androidx.compose.ui.platform.LocalContext.current
    val versionCatalog = (context.applicationContext as? com.example.MineHostApplication)?.catalogRepository
    
    var showDowngradeWarning by remember { mutableStateOf<EngineVersion?>(null) }
    var manualVerificationVersion by remember { mutableStateOf<EngineVersion?>(null) }
    var manualChecksum by remember { mutableStateOf("") }
    val sha256Pattern = remember { Regex("^[A-Fa-f0-9]{64}$") }
    val manualJarLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val version = manualVerificationVersion
        if (uri != null && version != null) {
            viewModel.importManuallyVerifiedEngineJar(
                uri = uri,
                targetVersionId = version.id,
                expectedSha256 = manualChecksum,
                serverId = ownerId,
            )
        }
        manualVerificationVersion = null
        manualChecksum = ""
    }
    val beginVersionInstall: (EngineVersion) -> Unit = { version ->
        val hasPublisherChecksum = version.sha256
            ?.trim()
            ?.matches(sha256Pattern) == true
        if (hasPublisherChecksum) {
            viewModel.setEngineVersion(version, ownerId)
        } else {
            manualChecksum = ""
            manualVerificationVersion = version
        }
    }

    // Removed automatic refresh on opening to respect cooldown and single-flight rules (Part 3)
    // androidx.compose.runtime.LaunchedEffect(Unit) {
    //    viewModel.refreshEngineVersions()
    // }

    if (showDowngradeWarning != null) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showDowngradeWarning = null },
            title = { Text("Confirm Downgrade") },
            text = { 
                Text("You are switching to an older engine build. This can cause world corruption or loss of plugin data. It is strongly recommended to create a backup first.\n\nContinue anyway?")
            },
            confirmButton = {
                androidx.compose.material3.TextButton(
                    onClick = {
                        showDowngradeWarning?.let(beginVersionInstall)
                        showDowngradeWarning = null
                    }
                ) {
                    Text("Downgrade")
                }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { showDowngradeWarning = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    manualVerificationVersion?.let { version ->
        AlertDialog(
            onDismissRequest = {
                manualVerificationVersion = null
                manualChecksum = ""
            },
            title = { Text("Manual engine verification") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "Automatic download is blocked because ${version.displayName} has no trusted publisher SHA-256 in the catalogue. Select the exact publisher JAR and enter a SHA-256 obtained independently from the publisher. MineHost will verify the file byte-for-byte and will not silently trust it."
                    )
                    OutlinedTextField(
                        value = manualChecksum,
                        onValueChange = { value ->
                            manualChecksum = value.filter { it.isLetterOrDigit() }.take(64)
                        },
                        label = { Text("Publisher SHA-256") },
                        supportingText = { Text("${manualChecksum.length}/64 hexadecimal characters") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = manualChecksum.matches(sha256Pattern),
                    onClick = {
                        manualJarLauncher.launch(
                            arrayOf(
                                "application/java-archive",
                                "application/x-java-archive",
                                "application/octet-stream",
                            )
                        )
                    },
                ) { Text("Choose exact JAR") }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        manualVerificationVersion = null
                        manualChecksum = ""
                    }
                ) { Text("Cancel") }
            },
        )
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
        MineHostBrandHeader(showBack = true, onBack = onBack, compact = true)
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                MineHostPageTitle(
                    title = "Version Manager",
                    subtitle = "Manage engines and builds for your active server profile.",
                    modifier = Modifier.weight(1f)
                )
                
                val isChecking by viewModel.isCheckingUpdates.collectAsState()
                if (isChecking) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp, color = MineHostBlue)
                } else {
                    IconButton(
                        onClick = { viewModel.refreshEngineVersions() },
                        enabled = !isChecking
                    ) {
                        androidx.compose.material3.Icon(
                            imageVector = Icons.Outlined.Refresh,
                            contentDescription = "Refresh",
                            tint = MineHostBlue
                        )
                    }
                }
            }

            // Current Version Info
            GlassCard(Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    PastelIcon(
                        icon = Icons.Outlined.Bolt,
                        tint = MineHostOrange,
                        background = OrangeSoft,
                        size = 64.dp
                    )
                    Spacer(Modifier.size(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            "Active Profile Engine",
                            style = MaterialTheme.typography.labelSmall,
                            color = MineHostTextSecondary
                        )
                        Text(active.name, style = MaterialTheme.typography.titleLarge)
                        activeVersion?.let {
                            val bedrockDisplay = if (it.compatibilityMode == com.example.server.version.CompatibilityMode.MULTI_VERSION) "Automatic" else it.supportedBedrockVersions.firstOrNull() ?: "Unknown"
                            Text(
                                "Build: ${it.displayName} ($bedrockDisplay)",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MineHostBlue
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                if (it.historical) StatusBadge("Historical", MineHostOrange, OrangeSoft, showDot = false)
                                if (it.recommended) StatusBadge("Recommended", MineHostGreen, GreenSoft, showDot = false)
                                StatusBadge(it.sourceType.name.replace("_", " "), MineHostBlue, BlueSoft, showDot = false)
                            }
                        }
                    }
                }
            }

            Text("Available Builds", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp))
            Text("Switching builds requires the server to be stopped. Historical builds may not support all plugins.", style = MaterialTheme.typography.bodySmall, color = MineHostTextSecondary)
            
            val builds = if (active.available) {
                versionCatalog?.getVersionsForEngine(active.id)?.filter { it.available }.orEmpty()
            } else emptyList()
            if (builds.isEmpty()) {
                Text("No verified builds found for this engine.", color = MineHostTextSecondary)
            } else {
                builds.sortedByDescending { it.recommended }.forEach { version ->
                    BuildItem(
                        version = version,
                        selected = version.id == activeVersion?.id,
                        onSelect = { 
                            if (status != ServerStatus.STOPPED && status != ServerStatus.FAILED && status != ServerStatus.CRASHED) {
                                viewModel.showMessage("Stop the server before changing its version")
                                return@BuildItem
                            }
                            val isDowngrade = version.historical && (activeVersion?.historical == false)
                            if (isDowngrade) {
                                showDowngradeWarning = version
                            } else {
                                beginVersionInstall(version)
                            }
                        }
                    )
                }
            }
            
            // Engine Updates Section (Part 9)
            EngineUpdatesSection(viewModel)
            
            Spacer(Modifier.size(24.dp))
        }
    }
}

@Composable
private fun EngineUpdatesSection(viewModel: MainViewModel) {
    val detectedReleases by viewModel.detectedReleases.collectAsState()
    val isChecking by viewModel.isCheckingUpdates.collectAsState()
    val checkError by viewModel.updatesCheckError.collectAsState()

    Text(
        "Engine Build Discovery",
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(top = 12.dp)
    )
    Text(
        "Automatically discovered builds from official sources. Verification is required before they appear in the Minecraft version selector.",
        style = MaterialTheme.typography.bodySmall,
        color = MineHostTextSecondary
    )

    if (checkError != null) {
        Text(checkError!!, color = MineHostRed, style = MaterialTheme.typography.bodySmall)
    }

    if (detectedReleases.isEmpty()) {
        GlassCard(Modifier.fillMaxWidth()) {
            val message = if (isChecking) "Checking for new builds..." else "No new builds discovered yet. Use the refresh icon to scan official sources."
            Text(
                message,
                modifier = Modifier.padding(16.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MineHostTextSecondary
            )
        }
    } else {
        detectedReleases.forEach { release ->
            DetectedReleaseItem(release, onVerify = { viewModel.verifyRelease(release) })
        }
    }
}

@Composable
private fun DetectedReleaseItem(
    release: DetectedEngineRelease,
    onVerify: () -> Unit
) {
    GlassCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(release.releaseName, style = MaterialTheme.typography.titleSmall)
                    Text(
                        "${release.engineId} • ${release.artifactName ?: "Unknown artifact"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MineHostBlue
                    )
                }
                if (release.prerelease) {
                    StatusBadge("Prerelease", MineHostYellow, OrangeSoft.copy(alpha = 0.5f), showDot = false)
                }
            }
            
            Spacer(Modifier.size(4.dp))
            
            val statusColor = when (release.verificationStatus) {
                ReleaseVerificationStatus.VERIFIED -> MineHostGreen
                ReleaseVerificationStatus.UNSUPPORTED -> MineHostOrange
                ReleaseVerificationStatus.FAILED -> MineHostRed
                ReleaseVerificationStatus.VERIFYING -> MineHostBlue
                else -> MineHostBlue
            }
            
            val statusText = when (release.verificationStatus) {
                ReleaseVerificationStatus.DETECTED -> "New build detected. Minecraft compatibility has not yet been verified."
                ReleaseVerificationStatus.VERIFYING -> "Compatibility verification in progress..."
                ReleaseVerificationStatus.VERIFIED -> "Verified for supported MineHost use."
                ReleaseVerificationStatus.UNSUPPORTED -> "This build cannot currently run in MineHost."
                ReleaseVerificationStatus.FAILED -> "Release verification failed."
            }
            
            Row(verticalAlignment = Alignment.Top) {
                androidx.compose.material3.Icon(
                    imageVector = Icons.Outlined.Update,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp).padding(top = 2.dp),
                    tint = statusColor
                )
                Spacer(Modifier.size(6.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        statusText,
                        style = MaterialTheme.typography.bodySmall,
                        color = statusColor
                    )
                    
                    if (release.verificationMessage != null) {
                        Text(
                            release.verificationMessage,
                            style = MaterialTheme.typography.labelSmall,
                            color = MineHostTextSecondary,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }

                    val date = release.publishedAt ?: release.firstDetectedAt
                    Text(
                        "Discovered: ${java.time.Instant.ofEpochMilli(date).atZone(java.time.ZoneId.systemDefault()).toLocalDate()}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MineHostTextSecondary,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }

                // Part 12 Controls
                if (release.verificationStatus == ReleaseVerificationStatus.DETECTED) {
                    MineHostButton(
                        text = "Verify",
                        onClick = onVerify,
                        modifier = Modifier.size(width = 80.dp, height = 36.dp),
                        containerColor = MineHostBlue.copy(alpha = 0.1f),
                        contentColor = MineHostBlue
                    )
                } else if (release.verificationStatus == ReleaseVerificationStatus.FAILED) {
                    MineHostButton(
                        text = "Retry",
                        onClick = onVerify,
                        modifier = Modifier.size(width = 80.dp, height = 36.dp),
                        containerColor = MineHostRed.copy(alpha = 0.1f),
                        contentColor = MineHostRed
                    )
                }
            }
        }
    }
}

@Composable
private fun BuildItem(
    version: EngineVersion,
    selected: Boolean,
    onSelect: () -> Unit
) {
    GlassCard(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (selected) Modifier.background(MineHostBlue.copy(alpha = 0.1f), RoundedCornerShape(16.dp)) else Modifier),
        onClick = if (!selected) onSelect else null,
        cornerRadius = 16.dp
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(version.displayName, style = MaterialTheme.typography.titleMedium)
                    val bedrockDisplay = if (version.compatibilityMode == com.example.server.version.CompatibilityMode.MULTI_VERSION) "Automatic Version Support" else "Supports: ${version.supportedBedrockVersions.joinToString(", ")}"
                    Text(bedrockDisplay, style = MaterialTheme.typography.bodySmall, color = MineHostBlue)
                    Text(version.versionName, style = MaterialTheme.typography.bodySmall, color = MineHostTextSecondary)
                }
                if (selected) {
                    StatusBadge("Active", MineHostGreen, GreenSoft)
                }
            }
            Spacer(Modifier.size(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (version.historical) StatusBadge("Historical", MineHostOrange, OrangeSoft, showDot = false)
                if (version.recommended) StatusBadge("Recommended", MineHostGreen, GreenSoft, showDot = false)
                if (version.deprecated) StatusBadge("Deprecated", MineHostRed, RedSoft, showDot = false)
                if (version.sha256?.matches(Regex("^[A-Fa-f0-9]{64}$")) != true) {
                    StatusBadge("Manual SHA required", MineHostOrange, OrangeSoft, showDot = false)
                }
                StatusBadge(version.sourceType.name, MineHostBlue, BlueSoft, showDot = false)
            }
            if (version.sha256?.matches(Regex("^[A-Fa-f0-9]{64}$")) != true) {
                Text(
                    "Remote installation is blocked until you verify the exact publisher JAR with an independently obtained SHA-256.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MineHostTextSecondary,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun VersionSummary(
    title: String,
    value: String,
    modifier: Modifier = Modifier,
    selected: Boolean = false
) {
    Column(
        modifier = modifier.padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            value,
            style = MaterialTheme.typography.titleLarge,
            color = if (selected) MineHostBlue else Color.Unspecified
        )
        Text(
            title,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) MineHostBlue else MineHostTextSecondary
        )
    }
}

@Composable
private fun EngineCard(
    template: ServerTemplate,
    index: Int,
    selected: Boolean,
    canSwitch: Boolean,
    onSelect: () -> Unit
) {
    GlassCard(Modifier.fillMaxWidth(), cornerRadius = 20.dp) {
        Column {
            Row(
                modifier = Modifier.padding(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                PastelIcon(
                    icon = engineIcon(index),
                    tint = engineColor(index),
                    background = engineSoft(index),
                    size = 58.dp
                )
                Spacer(Modifier.size(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(template.name, style = MaterialTheme.typography.titleLarge)
                    Text(
                        template.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MineHostTextSecondary
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        StatusBadge(
                            "Bedrock",
                            MineHostGreen,
                            GreenSoft,
                            showDot = false
                        )
                        StatusBadge(
                            "Local",
                            MineHostBlue,
                            BlueSoft,
                            showDot = false
                        )
                    }
                }
                if (selected) {
                    StatusBadge("Active", MineHostGreen, GreenSoft)
                } else {
                    MineHostButton(
                        text = "Use",
                        onClick = onSelect,
                        modifier = Modifier.size(width = 92.dp, height = 48.dp),
                        enabled = canSwitch
                    )
                }
            }
            HorizontalDivider(color = MineHostDivider)
            Text(
                text = if (selected) {
                    "Currently selected. Stop the server before switching."
                } else {
                    "This engine will be prepared when the server starts."
                },
                modifier = Modifier.padding(14.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MineHostTextSecondary
            )
        }
    }
}

private fun engineIcon(index: Int): ImageVector = listOf(
    Icons.Outlined.Bolt,
    Icons.Outlined.Power,
    Icons.Outlined.Cloud,
    Icons.Outlined.Memory,
    Icons.Outlined.ViewInAr
)[index % 5]

private fun engineColor(index: Int): Color = listOf(
    MineHostOrange,
    MineHostRed,
    MineHostBlue,
    MineHostGreen,
    MineHostYellow
)[index % 5]

private fun engineSoft(index: Int): Color = listOf(
    OrangeSoft,
    RedSoft,
    BlueSoft,
    GreenSoft,
    OrangeSoft
)[index % 5]
