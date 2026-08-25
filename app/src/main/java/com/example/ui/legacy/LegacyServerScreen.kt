package com.example.ui.legacy

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.MainViewModel
import com.example.server.ServerStatus
import com.example.ui.theme.*

@Composable
fun LegacyServerScreen(viewModel: MainViewModel, modifier: Modifier = Modifier) {
    val status by viewModel.status.collectAsState()
    val logs by viewModel.logs.collectAsState()
    val ipAddress by viewModel.ipAddress.collectAsState()
    val activeTemplate by viewModel.activeTemplate.collectAsState()
    val serverSettings by viewModel.serverSettings.collectAsState()

    val listState = rememberLazyListState()

    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) {
            listState.animateScrollToItem(logs.size - 1)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Status Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Server Status", color = ServerTextSecondary, fontWeight = FontWeight.Medium)
                    StatusBadge(status)
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        val label = "Connect via LAN"
                        val addr = ipAddress
                        
                        Text(label, color = ServerTextSecondary, fontSize = 12.sp)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(addr, style = MaterialTheme.typography.titleLarge, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                    }
                    Text(
                        "Port ${serverSettings.port}",
                        color = ServerTextSecondary,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }

        // Action Buttons
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Button(
                onClick = { viewModel.startServer() },
                enabled = status == ServerStatus.STOPPED || status == ServerStatus.FAILED || status == ServerStatus.CRASHED,
                modifier = Modifier.weight(1f).height(56.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = ServerSuccess)
            ) {
                Icon(Icons.Filled.PlayArrow, contentDescription = "Start", modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(8.dp))
                Text("Start", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            }

            Button(
                onClick = { viewModel.stopServer() },
                enabled = status == ServerStatus.ONLINE || status == ServerStatus.STARTING || status == ServerStatus.PREPARING || status == ServerStatus.DOWNLOADING,
                colors = ButtonDefaults.buttonColors(containerColor = ServerError),
                modifier = Modifier.weight(1f).height(56.dp),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Filled.Close, contentDescription = "Stop", modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(8.dp))
                Text("Stop", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            }
        }

        Button(
            onClick = { viewModel.restartServer() },
            enabled = status == ServerStatus.ONLINE,
            modifier = Modifier.fillMaxWidth().height(48.dp),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant, contentColor = MaterialTheme.colorScheme.onSurface),
            shape = RoundedCornerShape(12.dp)
        ) {
            Icon(Icons.Filled.Refresh, contentDescription = "Restart")
            Spacer(Modifier.width(8.dp))
            Text("Restart Server", fontWeight = FontWeight.Medium)
        }

        val memoryMb by viewModel.memoryMb.collectAsState()
        Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            Text("Allocated Memory: ${memoryMb} MB", color = MaterialTheme.colorScheme.onBackground, fontWeight = FontWeight.Medium, fontSize = 14.sp)
            Slider(
                value = memoryMb.toFloat(),
                onValueChange = { viewModel.setMemoryMb(it.toInt()) },
                valueRange = 256f..2048f,
                enabled = status == ServerStatus.STOPPED || status == ServerStatus.FAILED || status == ServerStatus.CRASHED
            )
        }

        val template by viewModel.activeTemplate.collectAsState()

        var engineExpanded by remember { mutableStateOf(false) }
        Box(modifier = Modifier.fillMaxWidth()) {
            Button(
                onClick = { 
                    engineExpanded = true
                },
                modifier = Modifier.fillMaxWidth().height(48.dp),
                enabled = status == ServerStatus.STOPPED || status == ServerStatus.FAILED || status == ServerStatus.CRASHED,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant, contentColor = MaterialTheme.colorScheme.onSurface),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("Engine: ${template.name}", fontSize = 14.sp)
            }
            DropdownMenu(expanded = engineExpanded, onDismissRequest = { engineExpanded = false }) {
                com.example.server.template.TemplateRegistry.ALL_TEMPLATES.forEach { tmpl ->
                    DropdownMenuItem(text = { Text(tmpl.name) }, onClick = { 
                        viewModel.setTemplate(tmpl)
                        engineExpanded = false
                    })
                }
            }
        }

        // Console Header
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Live Console",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onBackground
            )
            Row {
                val context = LocalContext.current
                val clipboardManager = LocalClipboardManager.current
                IconButton(onClick = {
                    clipboardManager.setText(AnnotatedString(logs.joinToString("\n")))
                    Toast.makeText(context, "Logs copied to clipboard", Toast.LENGTH_SHORT).show()
                }) {
                    Icon(Icons.Filled.ContentCopy, contentDescription = "Copy Logs", tint = ServerTextSecondary)
                }
                IconButton(onClick = { viewModel.clearLogs() }) {
                    Icon(Icons.Filled.Delete, contentDescription = "Clear Logs", tint = ServerTextSecondary)
                }
            }
        }

        // Live Log Console
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(ConsoleBackground)
                .border(1.dp, MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp))
                .padding(12.dp)
        ) {
            if (logs.isEmpty()) {
                 Text("Ready.", color = ServerTextSecondary, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
            }
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize()
            ) {
                items(logs) { log ->
                    Text(
                        text = log,
                        color = ConsoleText,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        lineHeight = 16.sp
                    )
                }
            }
        }
    }
}

@Composable
fun LegacyFileManagerScreen(viewModel: MainViewModel) {
    val currentFiles by viewModel.currentFiles.collectAsState()
    val currentPath by viewModel.currentPath.collectAsState()

    BackHandler {
        if (currentPath.isNotEmpty()) {
            viewModel.navigateUp()
        } else {
            viewModel.closeFileManager()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Filled.FolderOpen, contentDescription = "Path", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = if (currentPath.isEmpty()) "/" else "/$currentPath",
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurface
            )
        }

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            if (currentPath.isNotEmpty()) {
                item {
                    LegacyFileItem(
                        name = "..",
                        isDirectory = true,
                        onClick = { viewModel.navigateUp() }
                    )
                }
            }

            items(currentFiles) { file ->
                LegacyFileItem(
                    name = file.name,
                    isDirectory = file.isDirectory,
                    size = file.size,
                    onClick = {
                        if (file.isDirectory) {
                            viewModel.navigateToFolder(file.relativePath)
                        } else {
                            // View file info or open (future)
                        }
                    }
                )
            }
            
            if (currentFiles.isEmpty() && currentPath.isEmpty()) {
                item {
                    Box(modifier = Modifier.fillParentMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Filled.FolderOff, contentDescription = null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f))
                            Spacer(Modifier.height(8.dp))
                            Text("No files found. Start the server first?", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun LegacyFileItem(name: String, isDirectory: Boolean, size: Long = 0, onClick: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        color = Color.Transparent
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (isDirectory) Icons.Filled.Folder else Icons.Filled.Description,
                contentDescription = null,
                tint = if (isDirectory) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                if (!isDirectory && name != "..") {
                    Text("${size / 1024} KB", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                }
            }
            if (isDirectory && name != "..") {
                Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f))
            }
        }
    }
    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
}

@Composable
fun StatusBadge(status: ServerStatus) {
    val targetColor = when (status) {
        ServerStatus.STOPPED -> ServerTextSecondary
        ServerStatus.STARTING, ServerStatus.PREPARING, ServerStatus.DOWNLOADING,
        ServerStatus.PROCESS_STARTED, ServerStatus.NETWORK_READY, ServerStatus.ENGINE_READY,
        ServerStatus.WORLD_PROVISIONALLY_LOADED -> ServerWarning
        ServerStatus.WORLD_VERIFIED, ServerStatus.ONLINE -> ServerSuccess
        ServerStatus.STOPPING -> ServerWarning
        ServerStatus.CRASHED, ServerStatus.FAILED, ServerStatus.WORLD_LOAD_FAILED, ServerStatus.PORT_MISMATCH, ServerStatus.PROTOCOL_MISMATCH -> ServerError
    }
    
    val targetText = when (status) {
        ServerStatus.STOPPED -> "STOPPED"
        ServerStatus.STARTING -> "STARTING"
        ServerStatus.PREPARING -> "PREPARING"
        ServerStatus.DOWNLOADING -> "DOWNLOADING"
        ServerStatus.ONLINE -> "ONLINE"
        ServerStatus.STOPPING -> "STOPPING"
        ServerStatus.CRASHED -> "CRASHED"
        ServerStatus.FAILED -> "FAILED"
        ServerStatus.PROCESS_STARTED -> "PROCESS STARTED"
        ServerStatus.NETWORK_READY -> "NETWORK READY"
        ServerStatus.ENGINE_READY -> "ENGINE READY"
        ServerStatus.WORLD_PROVISIONALLY_LOADED -> "WORLD CHECK"
        ServerStatus.WORLD_VERIFIED -> "WORLD VERIFIED"
        ServerStatus.WORLD_LOAD_FAILED -> "WORLD ERROR"
        ServerStatus.PORT_MISMATCH -> "PORT ERROR"
        ServerStatus.PROTOCOL_MISMATCH -> "PROTOCOL ERROR"
    }

    val color by animateColorAsState(targetValue = targetColor, label = "StatusColor")

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(color.copy(alpha = 0.15f))
            .border(1.dp, color.copy(alpha = 0.3f), RoundedCornerShape(16.dp))
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(color)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = targetText,
            color = color,
            fontWeight = FontWeight.Bold,
            fontSize = 12.sp,
            letterSpacing = 0.5.sp
        )
    }
}
