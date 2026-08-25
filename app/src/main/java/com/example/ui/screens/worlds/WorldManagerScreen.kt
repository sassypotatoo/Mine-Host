package com.example.ui.screens.worlds

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.TextButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.MainViewModel
import com.example.data.WorldEntry
import com.example.ui.components.ConfirmationDialog
import com.example.ui.components.EmptyState
import com.example.ui.components.GlassCard
import com.example.ui.components.MineHostBrandHeader
import com.example.ui.components.MineHostButton
import com.example.ui.components.MineHostPageTitle
import com.example.ui.components.ServerThumbnail
import com.example.ui.components.StatusBadge
import com.example.ui.theme.GreenSoft
import com.example.ui.theme.MineHostBackgroundBottom
import com.example.ui.theme.MineHostBackgroundTop
import com.example.ui.theme.MineHostBlue
import com.example.ui.theme.MineHostGreen
import com.example.ui.theme.MineHostTextSecondary
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun WorldManagerScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit,
    serverId: String? = null,
) {
    val worlds by viewModel.worlds.collectAsState()
    val operationInProgress by viewModel.operationInProgress.collectAsState()
    val appSettings by viewModel.appSettings.collectAsState()
    val selectedServerId by viewModel.selectedServerId.collectAsState()
    val serverStatus by viewModel.status.collectAsState()
    val pendingImport by viewModel.pendingWorldImport.collectAsState()
    val importProgress by viewModel.worldImportProgress.collectAsState()
    val ownerId = serverId ?: selectedServerId
    
    var activateTarget by remember { mutableStateOf<WorldEntry?>(null) }
    var regenerateTarget by remember { mutableStateOf<WorldEntry?>(null) }
    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let { viewModel.importWorld(it, ownerId) }
    }
    val folderLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        uri?.let { viewModel.importWorldFolder(it, serverId = ownerId) }
    }

    LaunchedEffect(ownerId) {
        ownerId?.let {
            if (serverId != null) viewModel.selectServer(it)
            viewModel.refreshWorlds(it)
        }
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
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(135.dp)
        ) {
            MineHostPageTitle(
                title = "World Manager",
                subtitle = "Import, inspect, and activate local server worlds.",
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .fillMaxWidth(0.65f)
            )
            ServerThumbnail(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .size(112.dp),
                corner = 24.dp
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            MineHostButton(
                text = "Import Archive",
                onClick = {
                    importLauncher.launch(
                        arrayOf(
                            "application/zip",
                            "application/octet-stream",
                            "application/x-zip-compressed",
                            "application/vnd.minecraft.world"
                        )
                    )
                },
                modifier = Modifier.weight(1f),
                icon = Icons.Outlined.FileUpload,
                enabled = !operationInProgress
            )
            MineHostButton(
                text = "Import Folder",
                onClick = {
                    folderLauncher.launch(null)
                },
                modifier = Modifier.weight(1f),
                icon = Icons.Outlined.FileUpload,
                enabled = !operationInProgress
            )
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            MineHostButton(
                text = "Refresh",
                onClick = { viewModel.refreshWorlds(ownerId) },
                modifier = Modifier.weight(1f),
                icon = Icons.Outlined.Refresh,
                outlined = true
            )
        }
        Spacer(Modifier.height(10.dp))

        if (worlds.isEmpty()) {
            GlassCard(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                EmptyState(
                    title = "No worlds found",
                    message = "A world will appear after the server creates one or you import a ZIP.",
                    icon = Icons.Outlined.Public,
                    modifier = Modifier.fillMaxSize()
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(bottom = 12.dp)
            ) {
                items(worlds, key = { it.path }) { world ->
                    WorldRow(
                        world = world,
                        busy = operationInProgress,
                        serverStatus = serverStatus,
                        onActivate = {
                            if (appSettings.confirmDestructiveActions) activateTarget = world
                            else viewModel.activateWorld(world, ownerId)
                        },
                        onRegenerate = { regenerateTarget = world }
                    )
                }
            }
        }
    }

    var verificationText by remember { mutableStateOf("") }

    regenerateTarget?.let { world ->
        AlertDialog(
            onDismissRequest = { 
                regenerateTarget = null
                verificationText = ""
            },
            title = { Text("Regenerate World?", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text(
                        "WARNING: This will permanently delete all terrain and structures in '${world.name}'. This action cannot be undone.\n\nTo confirm, type REGENERATE below:",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                    Spacer(Modifier.height(16.dp))
                    OutlinedTextField(
                        value = verificationText,
                        onValueChange = { verificationText = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("REGENERATE") },
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            viewModel.regenerateExact(serverId = ownerId, worldName = world.name)
                            regenerateTarget = null
                            verificationText = ""
                        },
                        enabled = verificationText == "REGENERATE",
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    ) {
                        Text("Regenerate (Same Seed)")
                    }
                    Button(
                        onClick = {
                            viewModel.regenerateNewSeed(serverId = ownerId, worldName = world.name)
                            regenerateTarget = null
                            verificationText = ""
                        },
                        enabled = verificationText == "REGENERATE",
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error.copy(alpha = 0.8f))
                    ) {
                        Text("Regenerate (New Random Seed)")
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { 
                    regenerateTarget = null
                    verificationText = ""
                }) {
                    Text("Cancel")
                }
            }
        )
    }

    activateTarget?.let { world ->
        ConfirmationDialog(
            title = "Switch active world?",
            message = "${world.name} will be used the next time the server starts. The server must be stopped.",
            confirmText = "Use World",
            onConfirm = {
                viewModel.activateWorld(world, ownerId)
                activateTarget = null
            },
            onDismiss = { activateTarget = null }
        )
    }

    pendingImport?.let { preview ->
        WorldImportPreviewDialog(
            preview = preview,
            existingWorlds = worlds.map { it.name },
            onConfirm = { finalName, replaceActive, selectedWorldName ->
                viewModel.confirmWorldImport(finalName, replaceActive, selectedWorldName)
            },
            onCancel = { viewModel.cancelWorldImport() }
        )
    }

    importProgress?.let { progress ->
        ImportProgressDialog(progress = progress)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ImportProgressDialog(progress: com.example.world.WorldManagerV2.ImportProgress) {
    AlertDialog(onDismissRequest = {}, confirmButton = {}, dismissButton = null, text = {
        GlassCard(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp)
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = progress.phase,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                
                if (progress.indeterminate) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(48.dp),
                        color = MineHostBlue
                    )
                } else {
                    val p = if (progress.totalBytes > 0) progress.bytesProcessed.toFloat() / progress.totalBytes else 0f
                    LinearProgressIndicator(
                        progress = { p },
                        modifier = Modifier.fillMaxWidth(),
                        color = MineHostBlue
                    )
                    Text(
                        text = "${formatSize(progress.bytesProcessed)} / ${formatSize(progress.totalBytes)}",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                
                progress.currentFile?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.labelSmall,
                        color = MineHostTextSecondary,
                        maxLines = 1
                    )
                }
            }
        }
    })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WorldImportPreviewDialog(
    preview: com.example.world.WorldManagerV2.WorldImportPreview,
    existingWorlds: List<String>,
    onConfirm: (String, Boolean, String?) -> Unit,
    onCancel: () -> Unit
) {
    var finalName by remember { mutableStateOf(preview.detectedWorldName) }
    var replaceActive by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf(false) }
    var selectedRootName by remember { mutableStateOf(preview.detectedWorldName) }

    ConfirmationDialog(
        title = "Import World",
        message = "",
        confirmText = "Import Now",
        onConfirm = { onConfirm(finalName, replaceActive, if (preview.possibleWorlds.size > 1) selectedRootName else null) },
        onDismiss = onCancel,
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Source: ${preview.sourceName}", style = MaterialTheme.typography.bodyMedium)
                Text("Detected Format: ${preview.format}", style = MaterialTheme.typography.bodyMedium)
                Text("Size: ${formatSize(preview.sizeBytes)}", style = MaterialTheme.typography.bodyMedium)
                
                if (preview.availableSpace < preview.requiredSpace) {
                    Text(
                        "Warning: Insufficient storage! Required: ${formatSize(preview.requiredSpace)}, Available: ${formatSize(preview.availableSpace)}",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                } else {
                    Text(
                        "Storage Check: OK (Available: ${formatSize(preview.availableSpace)})",
                        color = MineHostGreen,
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                if (preview.possibleWorlds.size > 1) {
                    Text("Select world to import:", style = MaterialTheme.typography.labelMedium)
                    ExposedDropdownMenuBox(
                        expanded = expanded,
                        onExpandedChange = { expanded = it }
                    ) {
                        TextField(
                            value = selectedRootName,
                            onValueChange = {},
                            readOnly = true,
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                            colors = ExposedDropdownMenuDefaults.textFieldColors(),
                            modifier = Modifier.menuAnchor().fillMaxWidth()
                        )
                        ExposedDropdownMenu(
                            expanded = expanded,
                            onDismissRequest = { expanded = false }
                        ) {
                            preview.possibleWorlds.forEach { entry ->
                                DropdownMenuItem(
                                    text = { Text(entry.key) },
                                    onClick = {
                                        selectedRootName = entry.key
                                        finalName = entry.key
                                        expanded = false
                                    }
                                )
                            }
                        }
                    }
                }

                Text("Final World Name:", style = MaterialTheme.typography.labelMedium)
                TextField(
                    value = finalName,
                    onValueChange = { finalName = it.replace(Regex("[^A-Za-z0-9_. -]"), "_") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    isError = existingWorlds.contains(finalName) && !replaceActive
                )
                if (existingWorlds.contains(finalName) && !replaceActive) {
                    Text(
                        "Warning: A world with this name already exists. It will be replaced if you proceed.",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = replaceActive, onCheckedChange = { replaceActive = it })
                    Text("Set as active world", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    )
}

@Composable
private fun WorldRow(
    world: WorldEntry,
    busy: Boolean,
    serverStatus: com.example.server.ServerStatus,
    onActivate: () -> Unit,
    onRegenerate: () -> Unit
) {
    GlassCard(Modifier.fillMaxWidth(), cornerRadius = 20.dp) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ServerThumbnail(Modifier.size(78.dp), corner = 17.dp)
            Spacer(Modifier.size(13.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(world.name, style = MaterialTheme.typography.titleLarge)
                    if (world.active) {
                        Spacer(Modifier.size(8.dp))
                        StatusBadge(
                            "Active",
                            MineHostGreen,
                            GreenSoft,
                            showDot = false
                        )
                    }
                }
                Text(
                    "${formatSize(world.sizeBytes)} • ${formatDate(world.modifiedAt)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MineHostTextSecondary
                )
                Text(
                    world.path,
                    style = MaterialTheme.typography.labelSmall,
                    color = MineHostBlue,
                    maxLines = 1
                )
            }
            if (!world.active) {
                MineHostButton(
                    text = "Use",
                    onClick = onActivate,
                    modifier = Modifier.size(width = 86.dp, height = 48.dp),
                    enabled = !busy
                )
            } else if (serverStatus == com.example.server.ServerStatus.STOPPED || serverStatus == com.example.server.ServerStatus.FAILED) {
                MineHostButton(
                    text = "Regen",
                    onClick = onRegenerate,
                    modifier = Modifier.size(width = 86.dp, height = 48.dp),
                    enabled = !busy,
                    outlined = true
                )
            }
        }
    }
}

private fun formatDate(timestamp: Long): String =
    SimpleDateFormat("MMM d • h:mm a", Locale.getDefault()).format(Date(timestamp))

private fun formatSize(bytes: Long): String = when {
    bytes >= 1_073_741_824L -> "%.1f GB".format(bytes / 1_073_741_824f)
    bytes >= 1_048_576L -> "%.0f MB".format(bytes / 1_048_576f)
    bytes >= 1024L -> "${bytes / 1024} KB"
    else -> "$bytes B"
}
