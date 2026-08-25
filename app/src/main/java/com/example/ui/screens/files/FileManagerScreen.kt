package com.example.ui.screens.files

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.DataObject
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderOff
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.NoteAdd
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SettingsSuggest
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.MainViewModel
import com.example.server.FileInfo
import com.example.ui.components.EmptyState
import com.example.ui.components.GlassCard
import com.example.ui.components.MineHostBrandHeader
import com.example.ui.components.MineHostButton
import com.example.ui.components.MineHostPageTitle
import com.example.ui.components.MineHostScreen
import com.example.ui.components.PastelIcon
import com.example.ui.components.RowLabelValue
import com.example.ui.components.ServerThumbnail
import com.example.ui.components.SoftIconButton
import com.example.ui.theme.BlueSoft
import com.example.ui.theme.MineHostBackgroundTop
import com.example.ui.theme.MineHostBlue
import com.example.ui.theme.MineHostDivider
import com.example.ui.theme.MineHostOrange
import com.example.ui.theme.MineHostRed
import com.example.ui.theme.MineHostTextSecondary
import com.example.ui.theme.OrangeSoft
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class CreateType { FILE, FOLDER }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileManagerScreen(viewModel: MainViewModel, serverId: String? = null) {
    val files by viewModel.currentFiles.collectAsState()
    val path by viewModel.currentPath.collectAsState()
    val selectedFile by viewModel.selectedFilePath.collectAsState()
    val selectedContent by viewModel.selectedFileContent.collectAsState()
    val operationInProgress by viewModel.operationInProgress.collectAsState()
    val appSettings by viewModel.appSettings.collectAsState()
    val selectedServerId by viewModel.selectedServerId.collectAsState()
    val ownerId = serverId ?: selectedServerId

    var query by remember { mutableStateOf("") }
    var createType by remember { mutableStateOf<CreateType?>(null) }
    var newName by remember { mutableStateOf("") }
    var selectedInfo by remember { mutableStateOf<FileInfo?>(null) }
    var renameTarget by remember { mutableStateOf<FileInfo?>(null) }
    var renameValue by remember { mutableStateOf("") }
    var deleteTarget by remember { mutableStateOf<FileInfo?>(null) }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let { viewModel.importFile(it, ownerId) }
    }

    LaunchedEffect(ownerId) {
        ownerId?.let {
            if (serverId != null) viewModel.selectServer(it)
            viewModel.refreshFiles(it)
        }
    }

    MineHostScreen(
        scrollable = false,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 0.dp)
    ) {
        MineHostBrandHeader()

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(124.dp)
        ) {
            Row(
                modifier = Modifier.align(Alignment.CenterStart),
                verticalAlignment = Alignment.CenterVertically
            ) {
                PastelIcon(
                    icon = Icons.Outlined.FolderOpen,
                    tint = MineHostBlue,
                    background = BlueSoft,
                    size = 58.dp
                )
                Spacer(Modifier.size(13.dp))
                MineHostPageTitle(
                    title = "File Manager",
                    subtitle = "Browse and manage real server files"
                )
            }
            ServerThumbnail(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .size(105.dp),
                corner = 24.dp
            )
        }

        GlassCard(Modifier.fillMaxWidth(), cornerRadius = 18.dp) {
            Column(Modifier.padding(14.dp)) {
                RowLabelValue(
                    label = "Current path",
                    value = if (path.isBlank()) "/server" else "/server/$path"
                )
                Spacer(Modifier.height(8.dp))
                RowLabelValue(
                    label = "Storage",
                    value = "Local app-controlled server storage"
                )
            }
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Search files and folders…") },
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                singleLine = true,
                shape = RoundedCornerShape(18.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    unfocusedContainerColor = Color.White.copy(alpha = 0.7f),
                    focusedContainerColor = Color.White
                )
            )
            SoftIconButton(
                icon = Icons.Outlined.FileUpload,
                contentDescription = "Import file",
                onClick = { importLauncher.launch(arrayOf("*/*")) }
            )
            SoftIconButton(
                icon = Icons.Outlined.CreateNewFolder,
                contentDescription = "New folder",
                onClick = { createType = CreateType.FOLDER }
            )
        }

        if (path.isNotBlank()) {
            Surface(
                onClick = { viewModel.navigateUp(ownerId) },
                color = BlueSoft,
                shape = RoundedCornerShape(14.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Outlined.ArrowUpward,
                        contentDescription = null,
                        tint = MineHostBlue
                    )
                    Spacer(Modifier.size(8.dp))
                    Text("Go to parent folder", color = MineHostBlue)
                }
            }
        }

        GlassCard(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            val visibleFiles = files.filter { it.name.contains(query, ignoreCase = true) }
            if (visibleFiles.isEmpty()) {
                EmptyState(
                    title = "This folder is empty",
                    message = "Create a folder or import a file to get started.",
                    icon = Icons.Outlined.FolderOff,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(visibleFiles, key = { it.relativePath }) { file ->
                        FileRow(
                            file = file,
                            onOpen = {
                                when {
                                    file.isDirectory -> viewModel.navigateToFolder(file.relativePath, ownerId)
                                    isEditableText(file.name) -> viewModel.openTextFile(file.relativePath, ownerId)
                                    else -> selectedInfo = file
                                }
                            },
                            onMore = { selectedInfo = file }
                        )
                        HorizontalDivider(color = MineHostDivider)
                    }
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            MineHostButton(
                text = "New File",
                onClick = { createType = CreateType.FILE },
                modifier = Modifier.weight(1f),
                icon = Icons.Outlined.NoteAdd,
                outlined = true
            )
            MineHostButton(
                text = "New Folder",
                onClick = { createType = CreateType.FOLDER },
                modifier = Modifier.weight(1f),
                icon = Icons.Filled.Add
            )
        }
        Spacer(Modifier.height(6.dp))
    }

    createType?.let { type ->
        AlertDialog(
            onDismissRequest = {
                createType = null
                newName = ""
            },
            title = {
                Text(if (type == CreateType.FOLDER) "Create folder" else "Create file")
            },
            text = {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    label = { Text("Name") },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (type == CreateType.FOLDER) {
                            viewModel.createFolder(newName, ownerId)
                        } else {
                            viewModel.createFile(newName, ownerId)
                        }
                        createType = null
                        newName = ""
                    },
                    enabled = newName.isNotBlank() && !operationInProgress
                ) {
                    Text("Create")
                }
            },
            dismissButton = {
                TextButton(onClick = { createType = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (selectedFile != null && selectedContent != null) {
        var editorText by remember(selectedFile, selectedContent) {
            mutableStateOf(selectedContent.orEmpty())
        }
        ModalBottomSheet(
            onDismissRequest = viewModel::closeTextFile,
            containerColor = MineHostBackgroundTop
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 400.dp, max = 720.dp)
                    .padding(16.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        selectedFile.orEmpty().substringAfterLast('/'),
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(
                        onClick = {
                            viewModel.saveTextFile(editorText, ownerId)
                            viewModel.closeTextFile(ownerId)
                        }
                    ) {
                        Icon(Icons.Outlined.Save, contentDescription = null)
                        Spacer(Modifier.size(5.dp))
                        Text("Save")
                    }
                }
                OutlinedTextField(
                    value = editorText,
                    onValueChange = { editorText = it },
                    modifier = Modifier.fillMaxSize(),
                    textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace),
                    shape = RoundedCornerShape(14.dp)
                )
            }
        }
    }

    selectedInfo?.let { file ->
        ModalBottomSheet(
            onDismissRequest = { selectedInfo = null },
            containerColor = MineHostBackgroundTop
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PastelIcon(
                        icon = if (file.isDirectory) Icons.Outlined.Folder else Icons.Outlined.Description,
                        tint = if (file.isDirectory) MineHostOrange else MineHostBlue,
                        background = if (file.isDirectory) OrangeSoft else BlueSoft,
                        size = 60.dp
                    )
                    Spacer(Modifier.size(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(file.name, style = MaterialTheme.typography.titleLarge)
                        Text(
                            if (file.isDirectory) "Folder" else formatSize(file.size),
                            color = MineHostTextSecondary
                        )
                    }
                }
                RowLabelValue("Path", file.relativePath)
                RowLabelValue(
                    "Modified",
                    SimpleDateFormat("MMM d, yyyy • h:mm a", Locale.getDefault())
                        .format(Date(file.lastModified))
                )
                if (file.isDirectory) {
                    MineHostButton(
                        text = "Open",
                        onClick = {
                            viewModel.navigateToFolder(file.relativePath, ownerId)
                            selectedInfo = null
                        },
                        modifier = Modifier.fillMaxWidth(),
                        icon = Icons.Outlined.FolderOpen
                    )
                }
                MineHostButton(
                    text = "Rename",
                    onClick = {
                        renameTarget = file
                        renameValue = file.name
                        selectedInfo = null
                    },
                    modifier = Modifier.fillMaxWidth(),
                    icon = Icons.Outlined.Edit,
                    outlined = true
                )
                MineHostButton(
                    text = "Delete",
                    onClick = {
                        if (appSettings.confirmDestructiveActions) {
                            deleteTarget = file
                        } else {
                            viewModel.deletePath(file.relativePath, ownerId)
                        }
                        selectedInfo = null
                    },
                    modifier = Modifier.fillMaxWidth(),
                    icon = Icons.Outlined.Delete,
                    containerColor = MineHostRed
                )
                Spacer(Modifier.height(20.dp))
            }
        }
    }

    renameTarget?.let { file ->
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("Rename ${if (file.isDirectory) "folder" else "file"}") },
            text = {
                OutlinedTextField(
                    value = renameValue,
                    onValueChange = { renameValue = it },
                    singleLine = true,
                    label = { Text("New name") }
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.renamePath(file.relativePath, renameValue, ownerId)
                        renameTarget = null
                    },
                    enabled = renameValue.isNotBlank() && renameValue != file.name
                ) {
                    Text("Rename")
                }
            },
            dismissButton = {
                TextButton(onClick = { renameTarget = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    deleteTarget?.let { file ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Delete ${file.name}?") },
            text = {
                Text(
                    if (file.isDirectory) {
                        "This deletes the folder and all files inside it. This action cannot be undone."
                    } else {
                        "This file will be permanently deleted."
                    }
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deletePath(file.relativePath, ownerId)
                        deleteTarget = null
                    }
                ) {
                    Text("Delete", color = MineHostRed)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun FileRow(
    file: FileInfo,
    onOpen: () -> Unit,
    onMore: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        PastelIcon(
            icon = if (file.isDirectory) Icons.Outlined.Folder else fileIcon(file.name),
            tint = if (file.isDirectory) MineHostOrange else MineHostBlue,
            background = if (file.isDirectory) OrangeSoft else BlueSoft,
            size = 46.dp
        )
        Spacer(Modifier.size(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                file.name,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                SimpleDateFormat("MMM d, yyyy • h:mm a", Locale.getDefault())
                    .format(Date(file.lastModified)),
                style = MaterialTheme.typography.bodySmall,
                color = MineHostTextSecondary
            )
        }
        Text(
            if (file.isDirectory) "Folder" else formatSize(file.size),
            style = MaterialTheme.typography.bodySmall,
            color = MineHostTextSecondary
        )
        IconButton(onClick = onMore) {
            Icon(Icons.Outlined.MoreVert, contentDescription = "More")
        }
    }
}

private fun isEditableText(name: String): Boolean =
    name.substringAfterLast('.', "").lowercase() in setOf(
        "txt", "yml", "yaml", "json", "properties", "conf", "log", "ini", "md", "xml"
    )

private fun fileIcon(name: String): ImageVector = when (
    name.substringAfterLast('.', "").lowercase()
) {
    "json" -> Icons.Outlined.DataObject
    "jar" -> Icons.Outlined.Inventory2
    "properties", "yml", "yaml" -> Icons.Outlined.SettingsSuggest
    else -> Icons.Outlined.Description
}

private fun formatSize(bytes: Long): String = when {
    bytes >= 1_073_741_824L -> "%.1f GB".format(bytes / 1_073_741_824f)
    bytes >= 1_048_576L -> "%.1f MB".format(bytes / 1_048_576f)
    bytes >= 1024L -> "%.1f KB".format(bytes / 1024f)
    else -> "$bytes B"
}
