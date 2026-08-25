package com.example.ui.screens.backups

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Backup
import androidx.compose.material.icons.outlined.CloudQueue
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.dp
import com.example.MainViewModel
import com.example.data.BackupEntry
import com.example.ui.components.ConfirmationDialog
import com.example.ui.components.EmptyState
import com.example.ui.components.GlassCard
import com.example.ui.components.MineHostBrandHeader
import com.example.ui.components.MineHostButton
import com.example.ui.components.MineHostPageTitle
import com.example.ui.components.PastelIcon
import com.example.ui.theme.MineHostBackgroundBottom
import com.example.ui.theme.MineHostBackgroundTop
import com.example.ui.theme.MineHostPurple
import com.example.ui.theme.MineHostRed
import com.example.ui.theme.MineHostTextSecondary
import com.example.ui.theme.PurpleSoft
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun BackupManagerScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit,
    serverId: String? = null,
) {
    val backups by viewModel.backups.collectAsState()
    val operationInProgress by viewModel.operationInProgress.collectAsState()
    val appSettings by viewModel.appSettings.collectAsState()
    val selectedServerId by viewModel.selectedServerId.collectAsState()
    val ownerId = serverId ?: selectedServerId
    var restoreTarget by remember { mutableStateOf<BackupEntry?>(null) }
    var deleteTarget by remember { mutableStateOf<BackupEntry?>(null) }

    LaunchedEffect(ownerId) {
        ownerId?.let {
            if (serverId != null) viewModel.selectServer(it)
            viewModel.refreshBackups(it)
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
        MineHostPageTitle(
            title = "Backup Manager",
            subtitle = "Create and restore real local server snapshots."
        )
        Spacer(Modifier.height(10.dp))

        GlassCard(Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                PastelIcon(
                    icon = Icons.Outlined.Backup,
                    tint = MineHostPurple,
                    background = PurpleSoft,
                    size = 58.dp
                )
                Spacer(Modifier.size(13.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        "${backups.size} backups",
                        style = MaterialTheme.typography.titleLarge
                    )
                    Text(
                        "Stored inside the selected server directory",
                        style = MaterialTheme.typography.bodySmall,
                        color = MineHostTextSecondary
                    )
                }
                MineHostButton(
                    text = "Create",
                    onClick = { viewModel.createBackup(ownerId) },
                    icon = Icons.Outlined.Add,
                    enabled = !operationInProgress
                )
            }
        }
        Spacer(Modifier.height(10.dp))

        if (backups.isEmpty()) {
            GlassCard(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                EmptyState(
                    title = "No backups yet",
                    message = "Create your first backup before changing worlds, plugins, or important settings.",
                    icon = Icons.Outlined.CloudQueue,
                    modifier = Modifier.fillMaxSize()
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(9.dp),
                contentPadding = PaddingValues(vertical = 4.dp)
            ) {
                items(backups, key = { it.fileName }) { backup ->
                    BackupRow(
                        backup = backup,
                        busy = operationInProgress,
                        onRestore = {
                            if (appSettings.confirmDestructiveActions) restoreTarget = backup
                            else viewModel.restoreBackup(backup, ownerId)
                        },
                        onDelete = {
                            if (appSettings.confirmDestructiveActions) deleteTarget = backup
                            else viewModel.deleteBackup(backup, ownerId)
                        }
                    )
                }
            }
        }
    }

    restoreTarget?.let { backup ->
        ConfirmationDialog(
            title = "Restore backup?",
            message = "The current server files will be replaced by ${backup.fileName}. Stop the server first and keep another backup if needed.",
            confirmText = "Restore",
            onConfirm = {
                viewModel.restoreBackup(backup, ownerId)
                restoreTarget = null
            },
            onDismiss = { restoreTarget = null }
        )
    }

    deleteTarget?.let { backup ->
        ConfirmationDialog(
            title = "Delete backup?",
            message = "${backup.fileName} will be permanently removed.",
            confirmText = "Delete",
            destructive = true,
            onConfirm = {
                viewModel.deleteBackup(backup, ownerId)
                deleteTarget = null
            },
            onDismiss = { deleteTarget = null }
        )
    }
}

@Composable
private fun BackupRow(
    backup: BackupEntry,
    busy: Boolean,
    onRestore: () -> Unit,
    onDelete: () -> Unit
) {
    GlassCard(Modifier.fillMaxWidth(), cornerRadius = 20.dp) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            PastelIcon(
                icon = Icons.Outlined.Inventory2,
                tint = MineHostPurple,
                background = PurpleSoft,
                size = 52.dp
            )
            Spacer(Modifier.size(12.dp))
            Column(Modifier.weight(1f)) {
                Text(backup.fileName, style = MaterialTheme.typography.titleMedium)
                Text(
                    "${formatSize(backup.sizeBytes)} • ${formatDate(backup.createdAt)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MineHostTextSecondary
                )
            }
            TextButton(onClick = onRestore, enabled = !busy) {
                Text("Restore")
            }
            IconButton(onClick = onDelete, enabled = !busy) {
                Icon(
                    imageVector = Icons.Outlined.Delete,
                    contentDescription = "Delete",
                    tint = MineHostRed
                )
            }
        }
    }
}

private fun formatDate(timestamp: Long): String =
    SimpleDateFormat("MMM d, yyyy • h:mm a", Locale.getDefault()).format(Date(timestamp))

private fun formatSize(bytes: Long): String = when {
    bytes >= 1_073_741_824L -> "%.1f GB".format(bytes / 1_073_741_824f)
    bytes >= 1_048_576L -> "%.1f MB".format(bytes / 1_048_576f)
    bytes >= 1024L -> "${bytes / 1024} KB"
    else -> "$bytes B"
}
